package de.plmail.crypto

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.pgpainless.PGPainless

/**
 * Reading mail the way it arrives from other OpenPGP clients.
 *
 * Every `.eml` here was written by openpgp.js 6.3.2 — the web app's library — encrypting to the
 * fixture address key and, for the signed one, signing with a separate sender key: PGP/MIME with
 * protected headers, an HTML body with a `cid:` image and some markup that must never run, a
 * plain-text alternative, an attachment; a message without a signature; one encrypted to somebody
 * else; and inline PGP in a text body.
 */
class EncryptedMessagesTest {

    @Test
    fun `a signed pgp-mime message opens with its protected subject, bodies and parts`() {
        val opened = EncryptedMessages.open(eml("pgpmime-signed.eml"), keys(), listOf(sender()))

        assertEquals("Quarterly report", opened.subject)
        assertEquals("Grüße, the numbers are attached.", opened.text!!.trim())
        assertTrue("<b>numbers</b>" in opened.html!!)

        val logo = opened.parts.single { it.cid != null }
        assertEquals("logo@example.net", logo.cid)
        assertEquals("image/png", logo.type)
        assertTrue(logo.isInline)

        val notes = opened.parts.single { it.name == "notes.txt" }
        assertFalse(notes.isInline)
        assertEquals("Q3 numbers\n", notes.bytes.toString(Charsets.UTF_8))
    }

    @Test
    fun `the signature inside checks out against the sender's key`() {
        val opened = EncryptedMessages.open(eml("pgpmime-signed.eml"), keys(), listOf(sender()))

        val verified = assertIs<InnerSignature.Verified>(opened.signature)
        assertEquals(
            json("sender.json")["fingerprint"]!!.jsonPrimitive.content,
            verified.fingerprint,
        )
    }

    @Test
    fun `without the sender's key the signature is from an unknown key, and the mail still opens`() {
        val opened =
            EncryptedMessages.open(eml("pgpmime-signed.eml"), keys(), verifiers = emptyList())

        assertIs<InnerSignature.UnknownKey>(opened.signature)
        assertEquals("Quarterly report", opened.subject)
    }

    @Test
    fun `an unsigned message says so`() {
        val opened = EncryptedMessages.open(eml("pgpmime-unsigned.eml"), keys())

        assertEquals(InnerSignature.None, opened.signature)
        assertEquals("Just text, no signature.", opened.text!!.trim())
        assertNull(opened.subject)
    }

    @Test
    fun `inline pgp opens as plain text`() {
        val opened = EncryptedMessages.open(eml("inline.eml"), keys())

        assertEquals("Hello from inline PGP.\nSecond line.", opened.text)
        assertNull(opened.html)
    }

    @Test
    fun `a message encrypted to somebody else does not open`() {
        val failure =
            assertFailsWith<DecryptionException> {
                EncryptedMessages.open(eml("pgpmime-not-for-us.eml"), keys())
            }

        assertEquals(Reason.NO_KEY, failure.reason)
    }

    @Test
    fun `a device with no keys is told so before anything is tried`() {
        val failure =
            assertFailsWith<DecryptionException> {
                EncryptedMessages.open(eml("pgpmime-signed.eml"), keys = emptyList())
            }

        assertEquals(Reason.NO_KEY, failure.reason)
    }

    @Test
    fun `a message with no ciphertext in it is not mistaken for one`() {
        val plain =
            "From: a@b\r\nSubject: hi\r\nContent-Type: text/plain\r\n\r\nNothing secret.\r\n"

        val failure =
            assertFailsWith<DecryptionException> {
                EncryptedMessages.open(plain.toByteArray(), keys())
            }

        assertEquals(Reason.NO_CIPHERTEXT, failure.reason)
    }

    @Test
    fun `the placeholders encrypting clients put on the outside are recognised`() {
        listOf("...", " [...] ", "Encrypted Message", "encrypted message").forEach {
            assertTrue(EncryptedMessages.isPlaceholderSubject(it), it)
        }
        listOf("Quarterly report", "...and more", "", null).forEach {
            assertFalse(EncryptedMessages.isPlaceholderSubject(it), it.toString())
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun keys(): List<UnlockedAddressKey> {
        val keyring = json("keyring-get.json")["list"]!!.jsonArray.single().jsonObject
        val entry = keyring["addressKeys"]!!.jsonArray.single().jsonObject
        val userKey =
            UserKey(
                3,
                Base64.getDecoder().decode(json("pairing.json")["userKey"]!!.jsonPrimitive.content),
            )
        val token = AddressKeys.token(userKey, entry["token"]!!.jsonPrimitive.content)

        return listOf(AddressKeys.unlock(entry["privateKey"]!!.jsonPrimitive.content, token))
    }

    private fun sender() =
        PGPainless.getInstance()
            .readKey()
            .parseCertificate(json("sender.json")["publicKey"]!!.jsonPrimitive.content)

    private fun eml(name: String): ByteArray =
        EncryptedMessagesTest::class.java.getResource("/crypto/$name")!!.readBytes()

    private fun json(name: String): JsonObject =
        Json.parseToJsonElement(
                EncryptedMessagesTest::class.java.getResource("/crypto/$name")!!.readText()
            )
            .jsonObject
}
