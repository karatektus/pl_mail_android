package de.plmail.core.data

import de.plmail.core.datastore.CredentialStore
import de.plmail.core.datastore.KeyVault
import de.plmail.core.datastore.ServerConnection
import de.plmail.crypto.UserKey
import de.plmail.jmap.client.Credential
import de.plmail.jmap.client.DownloadingTransport
import de.plmail.jmap.client.HttpRequest
import de.plmail.jmap.client.HttpResponse
import de.plmail.jmap.client.JmapTransport
import de.plmail.jmap.client.KeyFingerprint
import de.plmail.jmap.client.ParsedAddress
import de.plmail.jmap.client.ResponseBody
import de.plmail.jmap.client.ServerAddress
import de.plmail.jmap.client.StreamingTransport
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Handing the server decrypted copies, for an account that keeps them (#9, phase 3).
 *
 * The messages are the openpgp.js fixtures `KeyringInteropTest` and `EncryptedMessagesTest` use,
 * encrypted to the fixture address key the stored user key opens. The fake server answers
 * `DecryptedMail/pending`, `Email/get`, the blob download, the upload and `DecryptedMail/store`,
 * and records what it was handed.
 */
class DecryptedMailKeeperTest {

    private val preferences = InMemoryPreferences()
    private val vault = KeyVault(preferences, PlainCipher)

    @Test
    fun `pending encrypted mail is decrypted and handed over, the inner message whole`() = runTest {
        val server = FakeServer(pending = listOf("11", "12"))

        val kept = keeper(server).keep()

        assertEquals(2, kept)
        assertEquals(setOf("11", "12"), server.stored.keys)
        val pgpMime = server.uploads[server.stored.getValue("11")]!!.toString(Charsets.UTF_8)
        assertTrue(pgpMime.startsWith("Content-Type: multipart/mixed"), pgpMime.take(60))
        val inline = server.uploads[server.stored.getValue("12")]!!.toString(Charsets.UTF_8)
        assertTrue(inline.startsWith("Content-Type: text/plain; charset=utf-8\r\n"), inline)
    }

    @Test
    fun `S-MIME encrypted mail is handed over too, the signed content without its signature`() =
        runTest {
            val server = FakeServer(pending = listOf("14"), smime = true)

            assertEquals(1, keeper(server).keep())

            val handed = server.uploads[server.stored.getValue("14")]!!.toString(Charsets.UTF_8)
            assertTrue(handed.startsWith("Content-Type: multipart/mixed"), handed.take(80))
            assertTrue("pkcs7-signature" !in handed)
        }

    @Test
    fun `a message the keys do not open is skipped, and the run ends rather than asking again`() =
        runTest {
            val server = FakeServer(pending = listOf("13"))

            assertEquals(0, keeper(server).keep())
            assertTrue(server.stored.isEmpty())
            // Asked once; the id came back with nothing that would open, so the
            // run stopped instead of looping on it.
            assertEquals(1, server.pendingCalls)
        }

    @Test
    fun `an account that does not keep decrypted mail is left alone`() = runTest {
        val server = FakeServer(pending = listOf("11"), keeps = false)

        assertEquals(0, keeper(server).keep())
        assertEquals(0, server.pendingCalls)
    }

    @Test
    fun `nothing is done without keys on the device`() = runTest {
        val server = FakeServer(pending = listOf("11"))

        assertEquals(0, keeper(server, withKeys = false).keep())
        assertEquals(0, server.pendingCalls)
    }

    // ------------------------------------------------------------- fixtures

    private fun keeper(server: FakeServer, withKeys: Boolean = true): DecryptedMailKeeper {
        val credentials = CredentialStore(preferences, PlainCipher)
        val transports =
            object : TransportFactory {
                override fun create(
                    address: ServerAddress,
                    pinned: KeyFingerprint?,
                ): JmapTransport = server

                override fun createStreaming(
                    address: ServerAddress,
                    pinned: KeyFingerprint?,
                ): StreamingTransport = error("no stream is opened on this path")
            }

        runBlocking {
            credentials.save(
                ServerConnection(
                    address = (ServerAddress.parse(TEST_SERVER) as ParsedAddress.Valid).address,
                    credential = Credential.AppPassword("plmail_" + "a".repeat(64)),
                    username = "ada@example.org",
                )
            )
            if (withKeys) vault.save(listOf(UserKey(3, userKey())))
        }

        val clients = AccountClients(credentials, transports)

        return DecryptedMailKeeper(clients, EncryptionRepository(clients, vault))
    }

    private fun userKey(): ByteArray =
        Base64.getDecoder()
            .decode(
                Json.parseToJsonElement(resource("pairing.json").decodeToString())
                    .jsonObject["userKey"]!!
                    .jsonPrimitive
                    .content
            )

    private class FakeServer(
        private val pending: List<String>,
        private val keeps: Boolean = true,
        /** Serves a keyring with the person's S/MIME certificate in it (#47). */
        private val smime: Boolean = false,
    ) : DownloadingTransport {
        val uploads = mutableMapOf<String, ByteArray>()
        val stored = mutableMapOf<String, String>()
        var pendingCalls = 0

        private val sources =
            mapOf(
                "11" to "pgpmime-signed.eml",
                "12" to "inline.eml",
                "13" to "pgpmime-not-for-us.eml",
                "14" to "smime/signed-encrypted.eml",
            )

        override suspend fun send(request: HttpRequest): HttpResponse {
            val body = request.body?.decodeToString().orEmpty()

            val answer =
                when {
                    request.url.contains("well-known") -> session(keeps)
                    request.url.contains("/upload") -> {
                        val blobId = "G${uploads.size + 1}"
                        uploads[blobId] = request.body!!
                        """{"accountId":"$TEST_ACCOUNT_ID","blobId":"$blobId","type":"message/rfc822","size":${request.body!!.size}}"""
                    }
                    "Keyring/get" in body ->
                        methods(
                            "Keyring/get",
                            if (smime) keyringWithCertificate()
                            else resource("keyring-get.json").decodeToString(),
                        )
                    "DecryptedMail/pending" in body -> {
                        pendingCalls++
                        val ids =
                            if (pendingCalls == 1) pending else pending.filterNot { it in stored }
                        methods(
                            "DecryptedMail/pending",
                            """{"accountId":"$TEST_ACCOUNT_ID","ids":[${ids.joinToString(",") { "\"$it\"" }}]}""",
                        )
                    }
                    "Email/get" in body ->
                        methods(
                            "Email/get",
                            """{"accountId":"$TEST_ACCOUNT_ID","state":"s","notFound":[],"list":[""" +
                                pending.joinToString(",") { """{"id":"$it","blobId":"m-$it"}""" } +
                                "]}",
                        )
                    "DecryptedMail/store" in body -> {
                        val emails =
                            Json.parseToJsonElement(body)
                                .jsonObject["methodCalls"]!!
                                .let { it as kotlinx.serialization.json.JsonArray }[0]
                                .let { it as kotlinx.serialization.json.JsonArray }[1]
                                .jsonObject["emails"]!!
                                .jsonObject
                        emails.forEach { (id, blob) -> stored[id] = blob.jsonPrimitive.content }
                        methods(
                            "DecryptedMail/store",
                            """{"accountId":"$TEST_ACCOUNT_ID","stored":[${emails.keys.joinToString(",") { "\"$it\"" }}],"notStored":{}}""",
                        )
                    }
                    else -> error("unexpected request: ${request.url} $body")
                }

            return HttpResponse(
                200,
                mapOf("Content-Type" to "application/json"),
                answer.encodeToByteArray(),
            )
        }

        override suspend fun <T> download(
            request: HttpRequest,
            receive: suspend (ResponseBody) -> T,
        ): T {
            val id = sources.keys.first { request.url.contains("m-$it") }
            val bytes = resource(sources.getValue(id))

            return receive(
                ResponseBody(200, "message/rfc822", bytes.size.toLong(), bytes.inputStream())
            )
        }

        private fun keyringWithCertificate(): String {
            val keyring = resource("keyring-get.json").decodeToString()
            val certificates =
                resource("smime/smime-keyring.json")
                    .decodeToString()
                    .substringAfter("\"smimeCertificates\":")
                    .substringBeforeLast("}")

            // Into the one keyring the list holds, before its closing brace.
            val end = keyring.lastIndexOf("}", keyring.lastIndexOf("]"))
            return keyring.substring(0, end) +
                ",\"smimeCertificates\":" +
                certificates +
                keyring.substring(end)
        }

        private fun methods(name: String, arguments: String) =
            """{"methodResponses":[["$name",$arguments,"c0"]],"sessionState":"s"}"""

        private fun session(keeps: Boolean) =
            """
            {
              "capabilities": {
                "urn:ietf:params:jmap:core": {"maxSizeUpload": 50000000},
                "urn:plmail:params:jmap:keys": {
                  "kdf": {"algorithm": "PBKDF2-SHA256", "wrapKeyInfo": "plmail/user-key-wrap"},
                  "purposes": {"userKey": "plmail/user-key", "addressKeyToken": "plmail/address-key-token",
                    "pairingTransfer": "plmail/pairing-transfer", "smimeKey": "plmail/smime-key"},
                  "decryptedMailAllowed": true, "maxDecryptedMailSize": 50331648
                }
              },
              "accounts": {"$TEST_ACCOUNT_ID": {"name": "ada@example.org", "accountCapabilities":
                {"urn:ietf:params:jmap:mail": {}, "urn:plmail:params:jmap:keys": {"keepsDecryptedMail": $keeps}}}},
              "primaryAccounts": {"urn:ietf:params:jmap:mail": "$TEST_ACCOUNT_ID"},
              "username": "ada@example.org",
              "apiUrl": "$TEST_SERVER/jmap/api",
              "downloadUrl": "$TEST_SERVER/jmap/download/{accountId}/{blobId}/{name}?type={type}",
              "uploadUrl": "$TEST_SERVER/jmap/upload/{accountId}/",
              "state": "s"
            }
            """
    }

    private companion object {
        fun resource(name: String): ByteArray =
            DecryptedMailKeeperTest::class.java.getResource("/e2e/$name")!!.readBytes()
    }
}
