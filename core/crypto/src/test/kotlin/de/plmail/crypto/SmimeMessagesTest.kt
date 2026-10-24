package de.plmail.crypto

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reading S/MIME the way it arrives from other clients (#47).
 *
 * Every message here was written by OpenSSL (`smime/make-fixtures.sh`), which is what Thunderbird
 * and Outlook interoperate with: encrypted to the person's certificate with AES-256, AES-192 with
 * RSA-OAEP, AES-128, and 3DES; signed inside by a contact, detached or opaque; encrypted to
 * somebody else; and signed only, opaquely. The person's key comes out of a `Keyring/get` entry,
 * wrapped under the fixture user key exactly as the server serves it.
 */
class SmimeMessagesTest {

    private val own by lazy { ownKey() }

    @Test
    fun `the keyring entry opens with the user key, and names its certificate as the server does`() {
        val entry = keyringEntry()

        assertEquals(entry["fingerprint"]!!.jsonPrimitive.content, own.fingerprint)
        assertEquals(listOf("ada@example.org"), own.addresses)
        assertTrue(own.servesAt("Ada@Example.org"))
        assertFalse(own.servesAt("grace@example.net"))
    }

    @Test
    fun `signed then encrypted mail opens, with its protected subject and the contact's signature`() {
        val opened = open("signed-encrypted.eml")

        assertEquals("Quarterly report, by S/MIME", opened.subject)
        assertTrue("<b>numbers</b>" in opened.html!!)
        assertEquals("notes.txt", opened.parts.single().name)

        val signature = assertIs<InnerSignature.Certificate>(opened.signature)
        assertTrue(signature.isIntact)
        assertEquals(grace(), signature.fingerprint)
        assertEquals(listOf("grace@example.net"), signature.addresses)
    }

    @Test
    fun `the signature wrapper is not handed on as content`() {
        val opened = open("signed-encrypted.eml")
        val mime = opened.mime.toString(Charsets.UTF_8)

        assertTrue(mime.startsWith("Content-Type: multipart/mixed"), mime.take(80))
        assertFalse("pkcs7-signature" in mime)
        assertTrue(opened.parts.none { it.type.contains("pkcs7") })
    }

    @Test
    fun `an opaque signature inside the envelope is checked and unwrapped`() {
        val opened = open("opaque-encrypted.eml")

        assertEquals("Quarterly report, by S/MIME", opened.subject)
        assertTrue(assertIs<InnerSignature.Certificate>(opened.signature).isIntact)
    }

    @Test
    fun `RSA-OAEP key transport and AES-192 open, unsigned`() {
        val opened = open("oaep-encrypted.eml")

        assertEquals("Quarterly report, by S/MIME", opened.subject)
        assertEquals(InnerSignature.None, opened.signature)
    }

    @Test
    fun `3DES, which old Outlook still sends, opens too`() {
        assertEquals("Quarterly report, by S/MIME", open("des3-encrypted.eml").subject)
    }

    @Test
    fun `mail encrypted to somebody else says so`() {
        val failed =
            assertFailsWith<DecryptionException> {
                EncryptedMessages.open(eml("not-for-us.eml"), emptyList(), smimeKeys = listOf(own))
            }

        assertEquals(Reason.NO_KEY, failed.reason)
    }

    @Test
    fun `without a certificate on the device the reason is that, not a broken message`() {
        val failed =
            assertFailsWith<DecryptionException> {
                EncryptedMessages.open(eml("signed-encrypted.eml"), emptyList())
            }

        assertEquals(Reason.NO_CERTIFICATE, failed.reason)
    }

    @Test
    fun `opaque signed mail, not encrypted, is unwrapped and checked`() {
        val opened = EncryptedMessages.open(eml("opaque-signed.eml"), emptyList())

        assertEquals("Quarterly report, by S/MIME", opened.subject)
        assertTrue("<b>numbers</b>" in opened.html!!)
        assertTrue(assertIs<InnerSignature.Certificate>(opened.signature).isIntact)
    }

    @Test
    fun `a signature over bytes that were changed is not intact`() {
        val tampered =
            eml("opaque-signed.eml").toString(Charsets.ISO_8859_1).let { message ->
                val body =
                    message.substringAfter("\n\n").ifEmpty { message.substringAfter("\r\n\r\n") }
                val der = Base64.getMimeDecoder().decode(body.trim())
                // Flip one byte of the signed content ("numbers" -> "Numbers").
                val at = String(der, Charsets.ISO_8859_1).indexOf("numbers")
                der[at] = 'N'.code.toByte()
                message.substringBefore(body) + Base64.getMimeEncoder().encodeToString(der)
            }

        val opened = EncryptedMessages.open(tampered.toByteArray(Charsets.ISO_8859_1), emptyList())

        assertFalse(assertIs<InnerSignature.Certificate>(opened.signature).isIntact)
    }

    // ------------------------------------------------------------- sending

    @Test
    fun `a message sealed with S-MIME opens again, signed by the person, its subject protected`() {
        val raw =
            OutgoingMessages.sealSmime(
                outgoing(),
                recipients = listOf(certificate("grace.pem")),
                signer = own,
            )
        val text = raw.toString(Charsets.UTF_8)
        val headers = text.substringBefore("\r\n\r\n")

        assertTrue("\r\nSubject: ...\r\n" in headers, headers)
        assertTrue("application/pkcs7-mime; smime-type=enveloped-data" in headers, headers)
        assertFalse("cellar" in text)

        val opened = open(raw)
        assertEquals("Der Plan, über S/MIME", opened.subject)
        assertTrue("<b>plan</b>" in opened.html!!)
        assertEquals("plan.txt", opened.parts.single().name)

        val signature = assertIs<InnerSignature.Certificate>(opened.signature)
        assertTrue(signature.isIntact)
        assertEquals(own.fingerprint, signature.fingerprint)
    }

    @Test
    fun `the signed entity inside is what OpenSSL and Outlook expect`() {
        val signed =
            SmimeOutgoing.sign(
                    OutgoingMessages.entity(outgoing(), protectHeaders = true),
                    own,
                )
                .toString(Charsets.UTF_8)

        assertTrue(
            signed.startsWith(
                "Content-Type: multipart/signed; protocol=\"application/pkcs7-signature\";\r\n micalg=sha-256;"
            ),
            signed.take(120),
        )
        assertTrue("Content-Type: application/pkcs7-signature; name=\"smime.p7s\"" in signed)
        assertFalse(Regex("[^\r]\n").containsMatchIn(signed))
    }

    @Test
    fun `a recipient whose certificate cannot be read stops the seal`() {
        assertFailsWith<IllegalArgumentException> {
            OutgoingMessages.sealSmime(
                outgoing(),
                recipients = listOf("not a certificate"),
                signer = own,
            )
        }
    }

    private fun outgoing() =
        OutgoingContent(
            from = MailAddress("ada@example.org", "Ada"),
            to = listOf(MailAddress("grace@example.net")),
            subject = "Der Plan, über S/MIME",
            html = "<p>The <b>plan</b> is in the cellar.</p>",
            text = "The plan is in the cellar.",
            files = listOf(OutgoingFile("plan.txt", "text/plain", "Step one.\n".toByteArray())),
            messageId = "<smime@example.org>",
            date = java.time.ZonedDateTime.now(),
        )

    private fun certificate(name: String): String =
        SmimeMessagesTest::class.java.getResource("/crypto/smime/$name")!!.readText()

    private fun open(raw: ByteArray) =
        EncryptedMessages.open(raw, emptyList(), smimeKeys = listOf(own))

    // ------------------------------------------------------------- fixtures

    private fun open(name: String) =
        EncryptedMessages.open(eml(name), emptyList(), smimeKeys = listOf(own))

    private fun keyringEntry(): JsonObject =
        json("smime/smime-keyring.json")["smimeCertificates"]!!.jsonArray.single().jsonObject

    private fun ownKey(): UnlockedSmimeKey {
        val entry = keyringEntry()

        return SmimeKeys.unlock(
            certificatePem = entry["certificate"]!!.jsonPrimitive.content,
            chainPem = entry["chain"]!!.jsonPrimitive.content,
            wrappedPrivateKey = entry["privateKey"]!!.jsonPrimitive.content,
            userKey = UserKey(3, userKey()),
            addresses = entry["addresses"]!!.jsonArray.map { it.jsonPrimitive.content },
            isActive = true,
        )
    }

    private fun grace(): String = json("smime/grace.json")["fingerprint"]!!.jsonPrimitive.content

    private fun userKey(): ByteArray =
        Base64.getDecoder().decode(json("pairing.json")["userKey"]!!.jsonPrimitive.content)

    private fun eml(name: String): ByteArray =
        SmimeMessagesTest::class.java.getResource("/crypto/smime/$name")!!.readBytes()

    private fun json(name: String): JsonObject =
        Json.parseToJsonElement(
                SmimeMessagesTest::class.java.getResource("/crypto/$name")!!.readText()
            )
            .jsonObject
}
