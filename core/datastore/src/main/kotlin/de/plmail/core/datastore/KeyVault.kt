package de.plmail.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import de.plmail.crypto.UserKey
import java.util.Base64
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The person's user keys on this device, for end-to-end encryption (#9).
 *
 * Sealed under a Keystore key of their own — [KeyVaultCipher], alias `plmail.e2e.keys` — rather
 * than the app password's. Two reasons. The keys are worth more than the password: the password can
 * be revoked from the web app, mail read with these keys cannot be un-read. And the optional
 * "protect encrypted mail" setting (phase 5) will put user authentication on this one key, which
 * must not also lock the app password that background sync and push need with the screen off.
 *
 * Without that setting the key, like the password's, needs no authentication: encrypted mail opens
 * without a prompt, and later phases can decrypt in the background. The keys are only ever opened
 * into memory, and [UserKey.toString] never prints them.
 *
 * ## When it cannot be read back
 *
 * A Keystore key does not survive a restore onto new hardware. [userKeys] then answers empty, the
 * same as a device that never had keys, and the app offers to unlock again with the password or a
 * new pairing — nothing is lost, the keys still live wrapped on the server.
 *
 * ## Writes
 *
 * This shares the app's one preferences file, and every write re-emits every flow over it (see
 * `CredentialStore.connection`). It writes on pairing, on unlocking and on forgetting — never in a
 * loop — and [hasKeys] is distinct, so nothing downstream sees those writes as changes of its own.
 */
@Singleton
class KeyVault
@Inject
constructor(
    private val preferences: DataStore<Preferences>,
    @param:KeyVaultCipher private val cipher: SecretCipher,
) {

    /** Whether keys are stored. Says nothing about whether they still open; [userKeys] does. */
    val hasKeys: Flow<Boolean> =
        preferences.data.map { it[USER_KEYS] != null }.distinctUntilChanged()

    /** The stored user keys, opened, or empty when there are none or they no longer open. */
    suspend fun userKeys(): List<UserKey> {
        val sealed = preferences.data.first()[USER_KEYS] ?: return emptyList()
        val opened = cipher.open(SealedSecret(sealed)) ?: return emptyList()

        return try {
            JSON.decodeFromString(Stored.serializer(), opened).keys.map { entry ->
                val bytes = DECODER.decode(entry.key)
                try {
                    UserKey(entry.id, bytes)
                } finally {
                    bytes.fill(0)
                }
            }
        } catch (unreadable: SerializationException) {
            emptyList()
        } catch (unreadable: IllegalArgumentException) {
            // Not base64, or not 32 bytes: unreadable is unreadable.
            emptyList()
        }
    }

    /**
     * Replaces whatever was stored with [keys].
     *
     * Replaces rather than merges: what is handed in is always the complete set a pairing or an
     * unlock produced, and keeping an older key beside it would keep a key the person may have
     * retired with a reset.
     */
    suspend fun save(keys: List<UserKey>) {
        if (keys.isEmpty()) return forget()

        val plain =
            JSON.encodeToString(
                Stored.serializer(),
                Stored(keys.map { key -> Entry(key.id, ENCODER.encodeToString(key.bytes)) }),
            )

        preferences.edit { it[USER_KEYS] = cipher.seal(plain).encoded }
    }

    /**
     * Removes the keys from this device.
     *
     * On signing out, on the app password being refused (a revoked password must take the keys with
     * it — the documented rule for clients), and when the person asks. The wrapped copies on the
     * server are untouched; a new pairing or the password brings them back.
     */
    suspend fun forget() {
        preferences.edit { it.remove(USER_KEYS) }
    }

    @Serializable private data class Stored(val keys: List<Entry>)

    @Serializable private data class Entry(val id: Long, val key: String)

    private companion object {
        val USER_KEYS = stringPreferencesKey("e2e_user_keys")

        val JSON = Json { ignoreUnknownKeys = true }
        val ENCODER: Base64.Encoder = Base64.getEncoder()
        val DECODER: Base64.Decoder = Base64.getDecoder()
    }
}

/** The [SecretCipher] the end-to-end keys are sealed with, distinct from the app password's. */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class KeyVaultCipher
