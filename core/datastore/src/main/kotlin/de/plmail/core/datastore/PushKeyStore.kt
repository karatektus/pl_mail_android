package de.plmail.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import de.plmail.jmap.push.PushKeys
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The key pair a push with content in it is sealed to, kept across launches.
 *
 * ## Why it has to persist
 *
 * The server holds the public half and seals to it for as long as the subscription lives. A device
 * that came back from a restart with a different pair could open nothing it was sent, and nothing
 * would say so: the pushes would arrive, fail to open, and be dropped.
 *
 * ## How it is kept
 *
 * The private half is sealed with [SecretCipher] — the keystore-held AES key that already protects
 * the server password — and only the ciphertext is written. The public half and the auth secret are
 * stored as they are: the server has both, and neither opens anything without the private half.
 *
 * ## When it cannot be read back
 *
 * A keystore key does not survive a restore onto new hardware, and the sealed private half is then
 * permanently unreadable. [keys] answers null and [keysOrCreate] makes a new pair, which the caller
 * registers again — the stale public half on the server is what `PushStateStore` remembering
 * *which* keys were registered is for.
 *
 * ## One write
 *
 * Everything in this app shares one preferences file and every write re-emits every flow over it
 * (see `CredentialStore.connection`). This store writes exactly once per key pair, on the launch
 * that creates it, and reads with `first()` rather than exposing a flow of its own.
 */
@Singleton
class PushKeyStore
@Inject
constructor(private val preferences: DataStore<Preferences>, private val cipher: SecretCipher) {

    private val creating = Mutex()

    /** The stored keys, or null when there are none or they can no longer be opened. */
    suspend fun keys(): PushKeys? {
        val stored = preferences.data.first()

        val sealed = stored[PRIVATE] ?: return null
        val public = stored[PUBLIC] ?: return null
        val auth = stored[AUTH] ?: return null

        val private = cipher.open(SealedSecret(sealed)) ?: return null

        return try {
            PushKeys(DECODER.decode(private), DECODER.decode(public), DECODER.decode(auth))
        } catch (malformed: IllegalArgumentException) {
            // Not base64url, or not the sizes a key pair has. Unreadable is
            // unreadable, whichever way it got there.
            null
        }
    }

    /**
     * The stored keys, or a new pair stored and returned.
     *
     * Locked, so two callers arriving together on a first launch — a registration and a delivery —
     * do not each generate a pair and leave the server holding the one that lost the write.
     */
    suspend fun keysOrCreate(): PushKeys = creating.withLock {
        keys()
            ?: PushKeys.generate().also { created ->
                preferences.edit { store ->
                    store[PRIVATE] =
                        cipher.seal(ENCODER.encodeToString(created.privateScalar)).encoded
                    store[PUBLIC] = created.p256dh
                    store[AUTH] = created.authSecret
                }
            }
    }

    private companion object {
        val PRIVATE = stringPreferencesKey("push_sealing_private")
        val PUBLIC = stringPreferencesKey("push_sealing_public")
        val AUTH = stringPreferencesKey("push_sealing_auth")

        val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        val DECODER: Base64.Decoder = Base64.getUrlDecoder()
    }
}
