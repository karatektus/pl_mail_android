package de.plmail.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
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
 *
 * ## Protected keys (phase 5)
 *
 * A person can ask to confirm it is them before encrypted mail opens. The keys are then sealed
 * under [protectedCipher] instead, a second Keystore key that opens only within a few minutes of a
 * fingerprint, a face or the screen lock — unlocking the phone counts. Until then [userKeys] throws
 * [DeviceLockRequired], which is not "no keys": nothing is gone, the person has to be asked.
 */
@Singleton
class KeyVault
@Inject
constructor(
    private val preferences: DataStore<Preferences>,
    @param:KeyVaultCipher private val cipher: SecretCipher,
    @param:ProtectedKeyVaultCipher private val protectedCipher: SecretCipher = cipher,
) {

    /** Whether keys are stored. Says nothing about whether they still open; [userKeys] does. */
    val hasKeys: Flow<Boolean> =
        preferences.data.map { it[USER_KEYS] != null }.distinctUntilChanged()

    /** Whether the keys are sealed under the key that needs the person present. */
    val isProtected: Flow<Boolean> =
        preferences.data.map { it[PROTECTED] == true }.distinctUntilChanged()

    /**
     * The stored user keys, opened, or empty when there are none or they no longer open.
     *
     * Throws [DeviceLockRequired] while they are protected and the person has not confirmed it is
     * them recently enough.
     */
    suspend fun userKeys(): List<UserKey> {
        val stored = preferences.data.first()
        val sealed = stored[USER_KEYS] ?: return emptyList()
        val opened =
            cipherFor(protected = stored[PROTECTED] == true).open(SealedSecret(sealed))
                ?: return emptyList()

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

        val protected = preferences.data.first()[PROTECTED] == true

        // Under the key the person chose. If that key cannot seal now — a
        // pairing or a password unlock right after the screen lock was
        // removed, or before anybody confirmed it is them — the keys are stored
        // unprotected and the setting says so, rather than not stored at all.
        val sealed =
            if (protected) {
                runCatching { protectedCipher.seal(plain) }.getOrNull()
            } else {
                null
            }

        preferences.edit {
            if (sealed != null) {
                it[USER_KEYS] = sealed.encoded
            } else {
                it[USER_KEYS] = cipher.seal(plain).encoded
                it.remove(PROTECTED)
            }
        }
    }

    /**
     * Seals the keys again under the other key: [on] for the one that needs the person present.
     *
     * The caller has the person confirm it is them first — both directions need it, one to seal
     * under the protected key and the other to open what it sealed. Throws [DeviceLockRequired]
     * when that has not happened, and changes nothing then. Without keys only the choice is kept,
     * for the next keys to arrive.
     */
    suspend fun setProtected(on: Boolean) {
        val stored = preferences.data.first()
        val wasProtected = stored[PROTECTED] == true
        if (wasProtected == on) return

        // Keys that no longer open are no keys: dropped, as [userKeys] would
        // answer for them, and the choice kept for the next ones.
        val plain = stored[USER_KEYS]?.let { cipherFor(wasProtected).open(SealedSecret(it)) }
        val resealed = plain?.let {
            try {
                cipherFor(on).seal(it)
            } catch (locked: DeviceLockRequired) {
                throw locked
            } catch (failed: Exception) {
                // No screen lock to gate a key on, most likely.
                throw DeviceLockRequired(failed)
            }
        }

        preferences.edit {
            if (resealed != null) it[USER_KEYS] = resealed.encoded else it.remove(USER_KEYS)
            if (on) it[PROTECTED] = true else it.remove(PROTECTED)
        }
    }

    private fun cipherFor(protected: Boolean): SecretCipher =
        if (protected) protectedCipher else cipher

    /**
     * Removes the keys from this device.
     *
     * On signing out, on the app password being refused (a revoked password must take the keys with
     * it — the documented rule for clients), and when the person asks. The wrapped copies on the
     * server are untouched; a new pairing or the password brings them back.
     */
    suspend fun forget() {
        preferences.edit {
            it.remove(USER_KEYS)
            it.remove(PROTECTED)
        }
    }

    @Serializable private data class Stored(val keys: List<Entry>)

    @Serializable private data class Entry(val id: Long, val key: String)

    private companion object {
        val USER_KEYS = stringPreferencesKey("e2e_user_keys")
        val PROTECTED = booleanPreferencesKey("e2e_user_keys_protected")

        val JSON = Json { ignoreUnknownKeys = true }
        val ENCODER: Base64.Encoder = Base64.getEncoder()
        val DECODER: Base64.Decoder = Base64.getDecoder()
    }
}

/** The [SecretCipher] the end-to-end keys are sealed with, distinct from the app password's. */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class KeyVaultCipher

/** The [SecretCipher] protected end-to-end keys are sealed with: it needs the person present. */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class ProtectedKeyVaultCipher
