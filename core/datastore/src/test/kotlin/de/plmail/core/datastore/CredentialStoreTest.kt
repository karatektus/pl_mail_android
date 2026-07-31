package de.plmail.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import de.plmail.jmap.client.Credential
import de.plmail.jmap.client.KeyFingerprint
import de.plmail.jmap.client.ParsedAddress
import de.plmail.jmap.client.ServerAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest

/**
 * The store, tested without a device.
 *
 * Both collaborators are substituted, and that is the point of their being interfaces: the Android
 * Keystore has no JVM provider, so a store that reached for it directly could only ever be tested
 * on an emulator — and this is the code path that decides whether someone has to pair again, which
 * is exactly the thing worth being able to run in seconds. The real cipher is covered by an
 * instrumented test; what is checked here is everything around it.
 */
class CredentialStoreTest {

    private val address = validAddress("https://nas.local:8443")
    private val secret = "plmail_" + "a".repeat(64)

    @Test
    fun `a saved connection reads back`() = runTest {
        val store = CredentialStore(FakeDataStore(), RecordingCipher())

        store.save(
            ServerConnection(
                address = address,
                credential = Credential.AppPassword(secret),
                username = "someone@example.com",
            )
        )

        val loaded = requireNotNull(store.connection.first())
        assertEquals(address, loaded.address)
        assertEquals(secret, loaded.credential.secret)
        assertEquals("someone@example.com", loaded.username)
        assertNull(loaded.pinnedKey)
    }

    @Test
    fun `nothing saved reads as null rather than throwing`() = runTest {
        val store = CredentialStore(FakeDataStore(), RecordingCipher())

        assertNull(store.connection.first())
    }

    @Test
    fun `the secret is never written in clear`() = runTest {
        val backing = FakeDataStore()
        val store = CredentialStore(backing, RecordingCipher())

        store.save(ServerConnection(address, Credential.AppPassword(secret)))

        val written = backing.data.first().asMap().values.map(Any::toString)
        assertTrue(
            written.none { it.contains(secret) },
            "the app password reached DataStore unencrypted: $written",
        )
    }

    @Test
    fun `a pin survives a round trip and can be added afterwards`() = runTest {
        val store = CredentialStore(FakeDataStore(), RecordingCipher())
        val fingerprint = requireNotNull(KeyFingerprint.parse("ab".repeat(32)))

        store.save(ServerConnection(address, Credential.AppPassword(secret)))
        store.pin(fingerprint)

        assertEquals(fingerprint, store.connection.first()?.pinnedKey)
    }

    @Test
    fun `pinning does not disturb the credential`() = runTest {
        val store = CredentialStore(FakeDataStore(), RecordingCipher())

        store.save(
            ServerConnection(address, Credential.AppPassword(secret), username = "someone@example")
        )
        store.pin(requireNotNull(KeyFingerprint.parse("cd".repeat(32))))

        val loaded = requireNotNull(store.connection.first())
        assertEquals(secret, loaded.credential.secret)
        assertEquals("someone@example", loaded.username)
        assertEquals(address, loaded.address)
    }

    /**
     * The case that decides whether a restored phone crashes or asks to pair again.
     *
     * A Keystore key does not survive a restore onto new hardware, so the ciphertext is still there
     * and is permanently unreadable. Reading as absent is what makes the app show onboarding.
     */
    @Test
    fun `an unopenable secret reads as no connection`() = runTest {
        val backing = FakeDataStore()
        CredentialStore(backing, RecordingCipher())
            .save(ServerConnection(address, Credential.AppPassword(secret)))

        val afterKeyLoss = CredentialStore(backing, RecordingCipher(canOpen = false))

        assertNull(afterKeyLoss.connection.first())
    }

    @Test
    fun `clearing forgets everything`() = runTest {
        val backing = FakeDataStore()
        val store = CredentialStore(backing, RecordingCipher())

        store.save(ServerConnection(address, Credential.AppPassword(secret), username = "someone"))
        store.clear()

        assertNull(store.connection.first())
        assertTrue(backing.data.first().asMap().isEmpty(), "keys were left behind")
    }

    @Test
    fun `an address that no longer parses reads as no connection`() = runTest {
        // Rather than trusting a stored string blindly, which would hand the
        // transport a URL the parser would have refused at onboarding.
        val backing = FakeDataStore()
        val cipher = RecordingCipher()
        backing.write(
            mutablePreferencesOf().apply {
                set(stringPreferencesKey("server.address"), "")
                set(stringPreferencesKey("server.secret"), cipher.seal(secret).encoded)
            }
        )

        assertNull(CredentialStore(backing, cipher).connection.first())
    }

    private fun validAddress(text: String): ServerAddress {
        val parsed = ServerAddress.parse(text)
        check(parsed is ParsedAddress.Valid) { "test address “$text” does not parse" }

        return parsed.address
    }
}

/**
 * An in-memory [DataStore].
 *
 * The real `PreferenceDataStoreFactory` wants a file and a scope and enforces one instance per
 * path, none of which a unit test benefits from. This keeps the semantics that matter to the store
 * under test — `data` is a flow that re-emits after every edit, and `updateData` is applied
 * atomically.
 */
private class FakeDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())

    override val data: Flow<Preferences>
        get() = state

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences
    ): Preferences {
        val updated = transform(state.value)
        state.update { updated }

        return updated
    }

    fun write(preferences: Preferences) {
        state.update { preferences }
    }
}

/**
 * A [SecretCipher] that is reversible but not encryption.
 *
 * Deliberately not a no-op: sealing prefixes a marker, so a store that forgot to seal before
 * writing would be caught by the "never written in clear" test rather than passing it by accident.
 */
private class RecordingCipher(private val canOpen: Boolean = true) : SecretCipher {

    override fun seal(plaintext: String): SealedSecret =
        SealedSecret(SEALED_PREFIX + plaintext.reversed())

    override fun open(sealed: SealedSecret): String? =
        if (canOpen && sealed.encoded.startsWith(SEALED_PREFIX)) {
            sealed.encoded.removePrefix(SEALED_PREFIX).reversed()
        } else {
            null
        }

    private companion object {
        const val SEALED_PREFIX = "sealed:"
    }
}
