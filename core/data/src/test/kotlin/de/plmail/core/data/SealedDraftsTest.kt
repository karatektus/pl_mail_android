package de.plmail.core.data

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import de.plmail.core.database.AccountEntity
import de.plmail.core.database.MailboxEntity
import de.plmail.core.database.PlMailDatabase
import de.plmail.core.database.StoreKey
import de.plmail.core.datastore.AccountPrefsStore
import de.plmail.core.datastore.CredentialStore
import de.plmail.core.datastore.KeyVault
import de.plmail.core.datastore.ServerConnection
import de.plmail.crypto.AddressKeys
import de.plmail.crypto.EncryptedMessages
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
import de.plmail.jmap.mail.EmailAddress
import java.io.File
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Saving an end-to-end encrypted draft (#9, phase 4): built and encrypted here, uploaded, imported
 * into Drafts with the fingerprints the server checks — and opened again with the fixture key to
 * prove what was uploaded is what was written.
 *
 * The keys are the openpgp.js fixtures the other end-to-end tests use: the person's address key
 * (`ada@example.org`), opened with the stored user key, and Grace's public key as a recipient's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SealedDraftsTest {

    private lateinit var database: PlMailDatabase
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        database = inMemoryDatabase()
        runBlocking {
            database
                .accounts()
                .upsert(
                    listOf(
                        AccountEntity(
                            uid = testAccountKey,
                            serverId = TEST_SERVER,
                            accountId = TEST_ACCOUNT_ID,
                            name = "ada@example.org",
                        )
                    )
                )
            database.mailboxes().upsert(listOf(mailbox(DRAFTS, "drafts"), mailbox(TRASH, "trash")))
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `an encrypted draft is built here, imported into Drafts, and opens with the person's key`() =
        runTest {
            val server = FakeServer()
            val file = File(context.cacheDir, "numbers.txt").apply { writeText("Q3: 42\n") }

            val saved =
                repository(server)
                    .save(
                        draft()
                            .copy(
                                attachments =
                                    listOf(
                                        StagedAttachment(
                                            name = "numbers.txt",
                                            type = "text/plain",
                                            size = 7,
                                            uri = Uri.fromFile(file).toString(),
                                        )
                                    )
                            )
                    )

            assertEquals("101", saved.emailId)
            assertTrue(saved.savedSealed)

            val import = server.imports.single()
            assertEquals(listOf(DRAFTS), import["mailboxIds"]!!.jsonObject.keys.toList())
            assertEquals(setOf("\$draft", "\$seen"), import["keywords"]!!.jsonObject.keys)
            assertEquals(
                setOf(GRACE_FINGERPRINT, OWN_FINGERPRINT),
                import["fingerprints"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet(),
            )

            val raw = server.uploads.values.single()
            val text = raw.toString(Charsets.UTF_8)
            assertTrue("\r\nSubject: ...\r\n" in text, text.take(400))
            assertFalse("Quarterly" in text)

            val opened = EncryptedMessages.open(raw, keys())
            assertEquals("Quarterly numbers", opened.subject)
            assertTrue("the <b>numbers</b>" in opened.html!!)
            assertEquals("Q3: 42\n", opened.parts.single().bytes.toString(Charsets.UTF_8))
        }

    @Test
    fun `saving it again imports a new draft and moves the old one to Trash`() = runTest {
        val server = FakeServer()
        val repository = repository(server)

        val first = repository.save(draft())
        val second = repository.save(first.copy(bodyHtml = "<p>Changed.</p>"))

        assertEquals("102", second.emailId)
        assertEquals(2, server.imports.size)
        assertEquals(
            setOf(TRASH),
            server.updates.getValue("101").filterValues { it }.keys,
        )
        assertEquals(false, server.updates.getValue("101")[DRAFTS])
    }

    @Test
    fun `a recipient without a key stops the save before anything is uploaded`() = runTest {
        val server = FakeServer()

        val refused =
            assertFailsWith<EncryptionRefused.Missing> {
                repository(server)
                    .save(draft().copy(cc = listOf(EmailAddress(null, "nokey@example.net"))))
            }

        assertEquals(listOf("nokey@example.net"), refused.addresses)
        assertTrue(server.uploads.isEmpty())
        assertTrue(server.imports.isEmpty())
    }

    @Test
    fun `an import the server refuses for its keys says a key changed`() = runTest {
        val server = FakeServer(refuseImport = true)

        assertFailsWith<EncryptionRefused.KeysChanged> { repository(server).save(draft()) }
    }

    @Test
    fun `switching encryption off replaces the encrypted draft with a plain one`() = runTest {
        val server = FakeServer()
        val repository = repository(server)

        val sealed = repository.save(draft())
        val plain = repository.save(sealed.copy(sealAs = null))

        assertEquals("201", plain.emailId)
        assertFalse(plain.savedSealed)
        // Written as fields, in the clear, as the person chose.
        assertTrue("numbers" in server.created.single().toString())
        assertEquals(setOf(TRASH), server.updates.getValue("101").filterValues { it }.keys)
    }

    // ------------------------------------------------------ S/MIME (#47)

    @Test
    fun `to a contact with only a certificate the draft is sealed with S-MIME, and opens with it`() =
        runTest {
            val server = FakeServer(smime = true)

            val saved =
                repository(server)
                    .save(
                        draft()
                            .copy(sealAs = SealAs("ada@example.org", "Ada", EncryptionScheme.SMIME))
                    )

            assertTrue(saved.savedSealed)

            val import = server.imports.single()
            assertEquals(
                setOf(GRACE_CERTIFICATE, OWN_CERTIFICATE),
                import["fingerprints"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet(),
            )
            assertEquals(OWN_CERTIFICATE, import["signer"]!!.jsonPrimitive.content)

            val raw = server.uploads.values.single()
            assertTrue("smime-type=enveloped-data" in raw.toString(Charsets.UTF_8))

            val opened =
                EncryptedMessages.open(raw, emptyList(), smimeKeys = listOf(ownCertificate()))
            assertEquals("Quarterly numbers", opened.subject)
            assertEquals(
                OWN_CERTIFICATE,
                assertIs<de.plmail.crypto.InnerSignature.Certificate>(opened.signature).fingerprint,
            )
        }

    @Test
    fun `S-MIME is chosen only when OpenPGP cannot reach everybody, and never with Bcc`() {
        fun recipient(email: String, key: Boolean, certificate: Boolean) =
            de.plmail.jmap.methods.RecipientKey(
                email = email,
                key =
                    if (key) de.plmail.jmap.methods.RecipientPublicKey(fingerprint = "f") else null,
                smime =
                    if (certificate) de.plmail.jmap.methods.RecipientCertificate(fingerprint = "c")
                    else null,
            )

        val known =
            RecipientKeys.Known(
                ownKey = true,
                ownCertificate = true,
                byAddress =
                    mapOf(
                        "both@example.net" to recipient("both@example.net", true, true),
                        "cert@example.net" to recipient("cert@example.net", false, true),
                        "pgp@example.net" to recipient("pgp@example.net", true, false),
                        "none@example.net" to recipient("none@example.net", false, false),
                    ),
            )

        val bothWays = plan(EncryptionMode.AUTO, listOf("both@example.net"), known)
        assertEquals(EncryptionScheme.PGP, bothWays.scheme)

        val certificates =
            plan(EncryptionMode.AUTO, listOf("both@example.net", "cert@example.net"), known)
        assertTrue(certificates.encrypt)
        assertEquals(EncryptionScheme.SMIME, certificates.scheme)

        val hidden =
            plan(
                EncryptionMode.AUTO,
                listOf("both@example.net", "cert@example.net"),
                known,
                bcc = listOf("cert@example.net"),
            )
        assertFalse(hidden.encrypt)

        val mixed = plan(EncryptionMode.ON, listOf("cert@example.net", "pgp@example.net"), known)
        assertEquals(EncryptionBlock.Missing(listOf("cert@example.net")), mixed.blocked)

        // Without a certificate of one's own, a contact with only one is missing.
        assertFalse(
            plan(
                    EncryptionMode.AUTO,
                    listOf("cert@example.net"),
                    known.copy(ownCertificate = false),
                )
                .encrypt
        )
    }

    private fun ownCertificate(): de.plmail.crypto.UnlockedSmimeKey {
        val entry =
            Json.parseToJsonElement(resource("smime/smime-keyring.json").decodeToString())
                .jsonObject["smimeCertificates"]!!
                .jsonArray
                .single()
                .jsonObject

        return de.plmail.crypto.SmimeKeys.unlock(
            certificatePem = entry["certificate"]!!.jsonPrimitive.content,
            chainPem = entry["chain"]!!.jsonPrimitive.content,
            wrappedPrivateKey = entry["privateKey"]!!.jsonPrimitive.content,
            userKey = UserKey(3, userKey()),
            addresses = listOf("ada@example.org"),
            isActive = true,
        )
    }

    @Test
    fun `the decision follows the web app's rules`() {
        val known =
            RecipientKeys.Known(
                ownKey = true,
                byAddress =
                    mapOf(
                        "grace@example.net" to recipient("grace@example.net", key = true),
                        "nokey@example.net" to recipient("nokey@example.net", key = false),
                        "always@example.net" to
                            recipient("always@example.net", key = true, encrypt = "always"),
                        "never@example.net" to
                            recipient("never@example.net", key = true, encrypt = "never"),
                    ),
            )

        val auto = plan(EncryptionMode.AUTO, listOf("Grace@example.net"), known)
        assertTrue(auto.encrypt)
        assertNull(auto.blocked)

        val partial =
            plan(EncryptionMode.AUTO, listOf("grace@example.net", "nokey@example.net"), known)
        assertFalse(partial.encrypt)
        assertEquals(listOf("nokey@example.net"), partial.unencryptedFor)
        assertNull(partial.blocked)

        val demanded =
            plan(EncryptionMode.AUTO, listOf("always@example.net", "nokey@example.net"), known)
        assertIs<EncryptionBlock.Partial>(demanded.blocked)

        assertFalse(plan(EncryptionMode.AUTO, listOf("never@example.net"), known).encrypt)

        val forced = plan(EncryptionMode.ON, listOf("nokey@example.net"), known)
        assertTrue(forced.encrypt)
        assertEquals(EncryptionBlock.Missing(listOf("nokey@example.net")), forced.blocked)

        assertFalse(plan(EncryptionMode.OFF, listOf("grace@example.net"), known).encrypt)

        val unasked = plan(EncryptionMode.AUTO, listOf("new@example.net"), known)
        assertTrue(unasked.pending)
        assertFalse(unasked.encrypt)

        assertEquals(
            EncryptionBlock.NoOwnKey,
            plan(EncryptionMode.ON, listOf("grace@example.net"), known.copy(ownKey = false))
                .blocked,
        )
        assertFalse(plan(EncryptionMode.AUTO, listOf("grace@example.net"), null).encrypt)
        assertFalse(
            plan(EncryptionMode.AUTO, listOf("grace@example.net"), RecipientKeys.Unavailable)
                .available
        )
    }

    // ------------------------------------------------------------- fixtures

    private fun draft() =
        ComposeDraft(
            accountKey = testAccountKey,
            identityId = "1",
            to = listOf(EmailAddress("Grace Hopper", "grace@example.net")),
            subject = "Quarterly numbers",
            bodyHtml = "<p>Hello Grace, the <b>numbers</b>.</p>",
            sealAs = SealAs("ada@example.org", "Ada Lovelace"),
        )

    private fun mailbox(id: String, role: String) =
        MailboxEntity(
            uid = StoreKey.objectKey(testAccountKey, id),
            accountKey = testAccountKey,
            mailboxId = id,
            name = role,
            role = role,
        )

    private fun recipient(email: String, key: Boolean, encrypt: String = "auto") =
        de.plmail.jmap.methods.RecipientKey(
            email = email,
            key = if (key) de.plmail.jmap.methods.RecipientPublicKey(fingerprint = "f") else null,
            encrypt = encrypt,
        )

    private fun repository(server: FakeServer): ComposeRepository {
        val preferences = InMemoryPreferences()
        val credentials = CredentialStore(preferences, PlainCipher)
        val vault = KeyVault(preferences, PlainCipher)

        runBlocking {
            credentials.save(
                ServerConnection(
                    address = (ServerAddress.parse(TEST_SERVER) as ParsedAddress.Valid).address,
                    credential = Credential.AppPassword("plmail_" + "a".repeat(64)),
                    username = "ada@example.org",
                )
            )
            vault.save(listOf(UserKey(3, userKey())))
        }

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

        val clients = AccountClients(credentials, transports)
        val encryption = EncryptionRepository(clients, vault)

        return ComposeRepository(
            context = context,
            database = database,
            clients = clients,
            credentials = credentials,
            mail = MailRepository(database),
            accounts =
                AccountsRepository(
                    database,
                    AccountPrefsStore(InMemoryPreferences()),
                    clients,
                    credentials,
                ),
            sealing = OutgoingEncryption(clients, encryption),
            decryptor =
                MessageDecryptor(BlobStore(context, database, credentials, transports), encryption),
            decryptedFiles = DecryptedFiles(context),
        )
    }

    private fun keys() =
        json("keyring-get.json")["list"]!!
            .jsonArray
            .single()
            .jsonObject["addressKeys"]!!
            .jsonArray
            .map { it.jsonObject }
            .map { entry ->
                AddressKeys.unlock(
                    entry["privateKey"]!!.jsonPrimitive.content,
                    AddressKeys.token(
                        UserKey(3, userKey()),
                        entry["token"]!!.jsonPrimitive.content,
                    ),
                )
            }

    private fun userKey(): ByteArray =
        Base64.getDecoder().decode(json("pairing.json")["userKey"]!!.jsonPrimitive.content)

    /** Answers the calls an encrypted save makes, and records what it was handed. */
    private class FakeServer(
        private val refuseImport: Boolean = false,
        /**
         * The S/MIME world (#47): the person's certificate in the keyring and named for the From
         * address, and Grace with a certificate instead of an OpenPGP key.
         */
        private val smime: Boolean = false,
    ) : DownloadingTransport {
        val uploads = linkedMapOf<String, ByteArray>()
        val imports = mutableListOf<JsonObject>()
        val created = mutableListOf<JsonObject>()
        val updates = mutableMapOf<String, Map<String, Boolean>>()

        private var nextImport = 101
        private var nextCreate = 201

        override suspend fun send(request: HttpRequest): HttpResponse {
            val body = request.body?.decodeToString().orEmpty()

            val answer =
                when {
                    request.url.contains("well-known") -> SESSION
                    request.url.contains("/upload") -> {
                        val blobId = "G${uploads.size + 1}"
                        uploads[blobId] = request.body!!
                        """{"accountId":"$TEST_ACCOUNT_ID","blobId":"$blobId","type":"message/rfc822","size":${request.body!!.size}}"""
                    }
                    "Keyring/get" in body -> methods("Keyring/get", keyring())
                    "ContactKey/lookup" in body -> methods("ContactKey/lookup", lookup(body))
                    "Email/import" in body -> methods("Email/import", import(body))
                    "Email/set" in body -> methods("Email/set", set(body))
                    "Email/get" in body ->
                        methods(
                            "Email/get",
                            """{"accountId":"$TEST_ACCOUNT_ID","state":"s","notFound":[],"list":[{"id":"201","attachments":[]}]}""",
                        )
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
        ): T = error("nothing is downloaded on this path")

        private fun arguments(body: String): JsonObject =
            (Json.parseToJsonElement(body).jsonObject["methodCalls"] as JsonArray)[0]
                .jsonArray[1]
                .jsonObject

        private fun lookup(body: String): String {
            val emails = arguments(body)["emails"]!!.jsonArray.map { it.jsonPrimitive.content }
            val grace = json("sender.json")
            val certificate = Json.encodeToString(String.serializer(), graceCertificate())

            val recipients =
                emails.joinToString(",") { email ->
                    val isGrace = email == "grace@example.net"
                    val key =
                        if (isGrace && !smime) {
                            """{"fingerprint":"$GRACE_FINGERPRINT","publicKey":${grace["publicKey"]},"pinned":false}"""
                        } else {
                            "null"
                        }
                    val smimeCertificate =
                        if (isGrace && smime) {
                            """{"fingerprint":"$GRACE_CERTIFICATE","certificate":$certificate,"verified":false}"""
                        } else {
                            "null"
                        }
                    """{"email":"$email","own":false,"key":$key,"changed":false,"encrypt":"auto","sign":"auto","scheme":"pgp-mime","smime":$smimeCertificate}"""
                }

            val own = if (smime) "\"$OWN_CERTIFICATE\"" else "null"
            return """{"recipients":[$recipients],"sender":{"email":"ada@example.org","fingerprint":"$OWN_FINGERPRINT","smime":$own,"keepsDecrypted":false}}"""
        }

        private fun keyring(): String {
            val keyring = resource("keyring-get.json").decodeToString()
            if (!smime) return keyring

            val certificates =
                resource("smime/smime-keyring.json")
                    .decodeToString()
                    .substringAfter("\"smimeCertificates\":")
                    .substringBeforeLast("}")
            val end = keyring.lastIndexOf("}", keyring.lastIndexOf("]"))

            return keyring.substring(0, end) +
                ",\"smimeCertificates\":" +
                certificates +
                keyring.substring(end)
        }

        private fun import(body: String): String {
            val email = arguments(body)["emails"]!!.jsonObject.values.single().jsonObject
            imports += email

            if (refuseImport) {
                return """{"accountId":"$TEST_ACCOUNT_ID","newState":"s","created":{},"notCreated":{"c1":{"type":"forbidden","properties":["fingerprints"]}}}"""
            }

            val id = nextImport++
            return """{"accountId":"$TEST_ACCOUNT_ID","newState":"s","created":{"c1":{"id":"$id","blobId":"m-$id","threadId":"t$id","size":1}},"notCreated":{}}"""
        }

        private fun set(body: String): String {
            val arguments = arguments(body)

            arguments["update"]?.jsonObject?.forEach { (id, patch) ->
                updates[id] =
                    patch.jsonObject
                        .filterKeys { it.startsWith("mailboxIds/") }
                        .map { (key, value) ->
                            key.removePrefix("mailboxIds/") to (value.toString() == "true")
                        }
                        .toMap()
            }

            arguments["create"]?.jsonObject?.values?.forEach { created += it.jsonObject }

            val id = if (arguments["create"] != null) nextCreate++ else null

            return """{"accountId":"$TEST_ACCOUNT_ID","oldState":"s","newState":"s",""" +
                (if (id != null) """"created":{"c1":{"id":"$id"}},""" else """"created":{},""") +
                """"updated":{},"notCreated":{},"notUpdated":{},"destroyed":[],"notDestroyed":{}}"""
        }

        private fun methods(name: String, arguments: String) =
            """{"methodResponses":[["$name",$arguments,"c0"]],"sessionState":"s"}"""
    }

    private companion object {
        const val DRAFTS = "3"
        const val TRASH = "4"
        const val OWN_FINGERPRINT = "ed079e2c13b83c85ff5c6fd7d67f3f6459d147c9"
        val OWN_CERTIFICATE: String by lazy {
            json("smime/smime-keyring.json")["smimeCertificates"]!!
                .jsonArray
                .single()
                .jsonObject["fingerprint"]!!
                .jsonPrimitive
                .content
        }
        val GRACE_CERTIFICATE: String by lazy {
            json("smime/grace.json")["fingerprint"]!!.jsonPrimitive.content
        }

        fun graceCertificate(): String = resource("smime/grace.pem").decodeToString()

        val GRACE_FINGERPRINT: String by lazy {
            json("sender.json")["fingerprint"]!!.jsonPrimitive.content.lowercase()
        }

        val SESSION =
            """
            {
              "capabilities": {
                "urn:ietf:params:jmap:core": {"maxSizeUpload": 50000000},
                "urn:ietf:params:jmap:mail": {},
                "urn:ietf:params:jmap:submission": {},
                "urn:plmail:params:jmap:keys": {
                  "kdf": {"algorithm": "PBKDF2-SHA256", "wrapKeyInfo": "plmail/user-key-wrap"},
                  "purposes": {"userKey": "plmail/user-key", "addressKeyToken": "plmail/address-key-token",
                    "pairingTransfer": "plmail/pairing-transfer", "smimeKey": "plmail/smime-key"},
                  "decryptedMailAllowed": true, "maxContactKeyLookups": 50
                }
              },
              "accounts": {"$TEST_ACCOUNT_ID": {"name": "ada@example.org", "accountCapabilities":
                {"urn:ietf:params:jmap:mail": {}, "urn:ietf:params:jmap:submission": {},
                 "urn:plmail:params:jmap:keys": {"keepsDecryptedMail": false}}}},
              "primaryAccounts": {"urn:ietf:params:jmap:mail": "$TEST_ACCOUNT_ID"},
              "username": "ada@example.org",
              "apiUrl": "$TEST_SERVER/jmap/api",
              "downloadUrl": "$TEST_SERVER/jmap/download/{accountId}/{blobId}/{name}?type={type}",
              "uploadUrl": "$TEST_SERVER/jmap/upload/{accountId}/",
              "state": "s"
            }
            """

        fun resource(name: String): ByteArray =
            SealedDraftsTest::class.java.getResource("/e2e/$name")!!.readBytes()

        fun json(name: String): JsonObject =
            Json.parseToJsonElement(resource(name).decodeToString()).jsonObject
    }
}
