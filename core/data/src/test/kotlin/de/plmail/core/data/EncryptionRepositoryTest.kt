package de.plmail.core.data

import de.plmail.core.datastore.CredentialStore
import de.plmail.core.datastore.KeyVault
import de.plmail.core.datastore.ServerConnection
import de.plmail.crypto.UserKey
import de.plmail.jmap.client.Credential
import de.plmail.jmap.client.HttpResponse
import de.plmail.jmap.client.JmapTransport
import de.plmail.jmap.client.KeyFingerprint
import de.plmail.jmap.client.ParsedAddress
import de.plmail.jmap.client.ServerAddress
import de.plmail.jmap.client.StreamingTransport
import de.plmail.jmap.testing.RecordingTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest

/**
 * Getting the keys onto the device, against a keyring the browser's algorithms wrote.
 *
 * `e2e/keyring-get.json` is a `Keyring/get` answer in the server's shape whose wrappings were made
 * with Python's `cryptography` and whose address key is a real openpgp.js 6 key — see
 * `KeyringInteropTest` in `:core:crypto`. Its password is [PASSWORD], at 1000 PBKDF2 rounds so the
 * suite stays fast; the real server asks for hundreds of thousands.
 */
class EncryptionRepositoryTest {

    private val preferences = InMemoryPreferences()
    private val vault = KeyVault(preferences, PlainCipher)

    @Test
    fun `a server without the capability has no encryption to offer`() = runTest {
        val repository = repository(session = SESSION_WITHOUT_KEYS) { error("nothing is asked") }

        assertEquals(EncryptionStatus.Unsupported, repository.status())
    }

    @Test
    fun `a keyring this device holds no keys for is locked`() = runTest {
        val status = repository { KEYRING }.status()

        assertEquals(EncryptionStatus.Locked(isPassphrase = false, hadKeys = false), status)
    }

    @Test
    fun `the password opens the keyring, and the address key then unlocks`() = runTest {
        val repository = repository { KEYRING }

        assertEquals(UnlockResult.Unlocked, repository.unlock(PASSWORD))

        assertEquals(listOf(3L), vault.userKeys().map { it.id })
        val ready = assertIs<EncryptionStatus.Ready>(repository.status())
        val address = ready.addresses.single()
        assertEquals("ada@example.org", address.primaryAddress)
        assertTrue(address.opens)
        assertEquals(1, repository.unlockedAddressKeys().size)
    }

    @Test
    fun `a wrong password opens nothing and stores nothing`() = runTest {
        val result = repository { KEYRING }.unlock("correct horse battery")

        assertEquals(UnlockResult.WrongSecret, result)
        assertTrue(vault.userKeys().isEmpty())
    }

    /** A reset in the web app retired the user key this device still holds. */
    @Test
    fun `keys that open none of the address keys read as locked, not ready`() = runTest {
        vault.save(listOf(UserKey(3, ByteArray(32) { 5 })))

        val status = repository { KEYRING }.status()

        assertEquals(EncryptionStatus.Locked(isPassphrase = false, hadKeys = true), status)
    }

    @Test
    fun `an account with no keyring yet is not set up, and there is nothing to unlock`() = runTest {
        val notSetUp =
            """{"methodResponses":[["Keyring/get",{"state":"s","notFound":[],"list":[{"id":"singleton",
            "enabled":true,"root":"passphrase","rootKdf":{"salt":null,"iterations":600000},
            "userKeys":[],"addressKeys":[]}]},"c0"]]}"""
        val repository = repository { notSetUp }

        assertEquals(EncryptionStatus.NotSetUp(isPassphrase = true), repository.status())
        assertEquals(UnlockResult.NotSetUp, repository.unlock(PASSWORD))
    }

    /** The documented rule for clients: a refused app password takes the keys with it. */
    @Test
    fun `an app password the server refuses takes the keys with it`() = runTest {
        vault.save(listOf(UserKey(3, ByteArray(32) { 5 })))
        val repository = repository(apiStatus = 401) { """{"type":"about:blank","status":401}""" }

        assertIs<EncryptionStatus.Unreachable>(repository.status())

        assertTrue(vault.userKeys().isEmpty())
    }

    @Test
    fun `keyring get is sent as a singleton under the keys capability`() = runTest {
        val transport = transport(SESSION, 200) { KEYRING }

        repository(transport).status()

        val body = transport.requests.last().body!!.decodeToString()
        assertTrue("\"Keyring/get\"" in body)
        assertTrue("urn:plmail:params:jmap:keys" in body)
        assertTrue("accountId" !in body)
    }

    // ------------------------------------------------------------- fixtures

    private fun repository(
        session: String = SESSION,
        apiStatus: Int = 200,
        api: () -> String,
    ): EncryptionRepository = repository(transport(session, apiStatus, api))

    private fun repository(transport: JmapTransport): EncryptionRepository {
        val credentials = CredentialStore(preferences, PlainCipher)
        val transports =
            object : TransportFactory {
                override fun create(
                    address: ServerAddress,
                    pinned: KeyFingerprint?,
                ): JmapTransport = transport

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
        }

        return EncryptionRepository(AccountClients(credentials, transports), vault)
    }

    private fun transport(session: String, apiStatus: Int, api: () -> String) =
        RecordingTransport { request ->
            val discovery = request.url.contains("well-known")
            HttpResponse(
                status = if (discovery) 200 else apiStatus,
                headers = mapOf("Content-Type" to "application/json"),
                body = (if (discovery) session else api()).encodeToByteArray(),
            )
        }

    private companion object {
        const val PASSWORD = "correct horse ü battery"

        val KEYRING: String =
            """{"methodResponses":[["Keyring/get",""" +
                EncryptionRepositoryTest::class
                    .java
                    .getResource("/e2e/keyring-get.json")!!
                    .readText() +
                ""","c0"]]}"""

        val SESSION =
            """
            {
              "capabilities": {
                "urn:ietf:params:jmap:core": {},
                "urn:plmail:params:jmap:keys": {
                  "kdf": {"algorithm": "PBKDF2-SHA256", "wrapKeyInfo": "plmail/user-key-wrap"},
                  "purposes": {"userKey": "plmail/user-key", "addressKeyToken": "plmail/address-key-token",
                    "pairingTransfer": "plmail/pairing-transfer", "smimeKey": "plmail/smime-key"}
                }
              },
              "accounts": {"$TEST_ACCOUNT_ID": {"name": "ada@example.org"}},
              "username": "ada@example.org",
              "apiUrl": "$TEST_SERVER/jmap/api",
              "downloadUrl": "$TEST_SERVER/jmap/download",
              "uploadUrl": "$TEST_SERVER/jmap/upload"
            }
            """

        val SESSION_WITHOUT_KEYS =
            """
            {
              "capabilities": {"urn:ietf:params:jmap:core": {}},
              "accounts": {"$TEST_ACCOUNT_ID": {"name": "ada@example.org"}},
              "username": "ada@example.org",
              "apiUrl": "$TEST_SERVER/jmap/api",
              "downloadUrl": "$TEST_SERVER/jmap/download",
              "uploadUrl": "$TEST_SERVER/jmap/upload"
            }
            """
    }
}
