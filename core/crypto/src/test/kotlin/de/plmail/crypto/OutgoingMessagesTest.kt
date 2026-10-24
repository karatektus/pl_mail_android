package de.plmail.crypto

import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.pgpainless.PGPainless

/**
 * Building encrypted mail (#9, phase 4), checked by opening it again with the reader's own code —
 * the code that opens what openpgp.js writes — as the fixture address key's owner.
 */
class OutgoingMessagesTest {

    private val keys by lazy { keys() }
    private val own by lazy { keys.single() }

    @Test
    fun `a sealed message opens with its protected subject, bodies and files, signed by the sender`() {
        val raw = OutgoingMessages.seal(content(), listOf(grace()), own)

        val opened = EncryptedMessages.open(raw, keys, verifiers = listOf(own.key.toCertificate()))

        assertEquals("Grüße zum Quartal — Zahlen anbei", opened.subject)
        assertEquals("Hello Grace,\nthe numbers are attached.", opened.text)
        assertTrue("<b>numbers</b>" in opened.html!!)

        val file = opened.parts.single()
        assertEquals("Übersicht.txt", file.name)
        assertEquals("Q3: 42\n", file.bytes.toString(Charsets.UTF_8))
        assertFalse(file.isInline)

        val signed = assertIs<InnerSignature.Verified>(opened.signature)
        assertEquals(own.fingerprint, signed.fingerprint)
    }

    @Test
    fun `the outside says nothing the inside protects, and carries what the server files the draft by`() {
        val raw = OutgoingMessages.seal(content(), listOf(grace()), own).toString(Charsets.UTF_8)
        val headers = raw.substringBefore("\r\n\r\n")

        assertTrue("\r\nSubject: ...\r\n" in headers, headers)
        assertTrue("To: Grace Hopper <grace@example.net>" in headers, headers)
        assertTrue("Message-ID: <abc@example.org>" in headers, headers)
        assertTrue("In-Reply-To: <original@example.net>" in headers, headers)
        assertTrue(
            "Content-Type: multipart/encrypted; protocol=\"application/pgp-encrypted\";" in headers
        )
        assertFalse("Quartal" in raw)
        assertFalse("numbers" in raw)
        assertTrue("\r\nVersion: 1\r\n" in raw)
        // CRLF throughout: no bare LF anywhere.
        assertFalse(Regex("[^\r]\n").containsMatchIn(raw))
    }

    @Test
    fun `the protected headers sit on top of the inner entity, marked`() {
        val entity =
            OutgoingMessages.entity(content(), protectHeaders = true).toString(Charsets.UTF_8)

        val lines = entity.lines()
        assertTrue(lines[0].startsWith("Subject: =?utf-8?B?"), lines[0])
        assertTrue("From: ada@example.org" in entity)
        assertTrue("To: grace@example.net" in entity)
        assertTrue(
            Regex("Content-Type: multipart/mixed; boundary=\"[^\"]+\"; protected-headers=\"v1\"")
                .containsMatchIn(entity),
            entity.take(400),
        )
        assertTrue("filename*=utf-8''%C3%9Cbersicht.txt" in entity)
    }

    @Test
    fun `with bcc the recipients are hidden, and every key still opens it`() {
        val raw =
            OutgoingMessages.seal(
                content().copy(bcc = listOf(MailAddress("grace@example.net"))),
                listOf(grace()),
                own,
            )

        assertTrue("Bcc: grace@example.net" in raw.toString(Charsets.UTF_8))
        assertEquals(
            "Grüße zum Quartal — Zahlen anbei",
            EncryptedMessages.open(raw, keys).subject,
        )
    }

    @Test
    fun `a text-only message is one text leaf`() {
        val entity =
            OutgoingMessages.entity(
                    content().copy(html = null, files = emptyList()),
                    protectHeaders = false,
                )
                .toString(Charsets.UTF_8)

        assertTrue(entity.startsWith("Content-Type: text/plain; charset=utf-8\r\n"), entity)
        assertEquals(
            "Hello Grace,\nthe numbers are attached.",
            EncryptedMessages.open(
                    OutgoingMessages.seal(
                        content().copy(html = null, files = emptyList()),
                        listOf(grace()),
                        own,
                        protectSubject = false,
                    ),
                    keys,
                )
                .text,
        )
    }

    @Test
    fun `long subjects become several encoded words, folded`() {
        val encoded = OutgoingMessages.encodedText("Ü".repeat(60))

        assertEquals(3, encoded.split("\r\n ").size)
        encoded.split("\r\n ").forEach { assertTrue(it.length <= 76, it) }
    }

    // ------------------------------------------------------------- fixtures

    private fun content() =
        OutgoingContent(
            from = MailAddress("ada@example.org", "Ada Lovelace"),
            to = listOf(MailAddress("grace@example.net", "Grace Hopper")),
            subject = "Grüße zum Quartal — Zahlen anbei",
            html = "<p>Hello Grace,<br>the <b>numbers</b> are attached.</p>",
            text = "Hello Grace,\nthe numbers are attached.",
            files = listOf(OutgoingFile("Übersicht.txt", "text/plain", "Q3: 42\n".toByteArray())),
            messageId = "<abc@example.org>",
            date = ZonedDateTime.of(2026, 10, 21, 9, 30, 0, 0, ZoneOffset.UTC),
            inReplyTo = listOf("<original@example.net>"),
            references = listOf("<original@example.net>"),
        )

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

    private fun grace() =
        PGPainless.getInstance()
            .readKey()
            .parseCertificate(json("sender.json")["publicKey"]!!.jsonPrimitive.content)

    private fun json(name: String): JsonObject =
        Json.parseToJsonElement(
                OutgoingMessagesTest::class.java.getResource("/crypto/$name")!!.readText()
            )
            .jsonObject
}
