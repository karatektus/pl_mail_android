package de.plmail.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import de.plmail.jmap.client.Credential
import de.plmail.jmap.client.KeyFingerprint
import de.plmail.jmap.client.ParsedAddress
import de.plmail.jmap.client.ServerAddress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Everything the app needs to reach one server again after a restart.
 *
 * This — with the DataStore preferences beside it — is the *only* irreplaceable state the app
 * holds. Every row in the Room cache is reconstructible from the server, which is what licenses the
 * database's destructive-migration policy; none of this is, which is why it lives somewhere else
 * entirely and is never written to that database.
 */
data class ServerConnection(
    val address: ServerAddress,
    val credential: Credential.AppPassword,
    /**
     * The key the user accepted, if the platform would not vouch for the certificate on its own.
     *
     * Null is the ordinary case for a server with a publicly-trusted certificate, and is *not* the
     * same as "not checked yet": `ServerTrust` re-evaluates platform trust on every connection and
     * only consults a pin when that fails.
     */
    val pinnedKey: KeyFingerprint? = null,
    /** The address the server says this credential belongs to, shown back before saving. */
    val username: String = "",
)

/**
 * Reads and writes the one connection.
 *
 * One, not many: a plMail app password is **user-scoped**, so a single credential already
 * enumerates every mailbox the user has connected — the multiple accounts the unified inbox merges
 * are JMAP accounts behind one login, not separate logins. A second *server* is a real future
 * feature, and when it arrives this becomes a keyed collection; building that now would be a schema
 * carrying a case that does not exist, and the migration from one to many is trivial because
 * everything here is written in one place.
 *
 * [DataStore] is injected rather than constructed so the whole store can be exercised on the JVM.
 * The Keystore cannot be, but nothing here depends on the Keystore directly — see [SecretCipher].
 */
class CredentialStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: SecretCipher,
) {

    /**
     * The stored connection, or null when there is none *or* when it can no longer be read.
     *
     * An unopenable secret deliberately reads as absent: the app's answer to both is the same
     * onboarding screen. It is logged as distinct at the point of failure rather than modelled as a
     * separate state here, because a caller that had to handle "there is a credential but it is
     * unreadable" would only ever do what it does for "there is none".
     */
    val connection: Flow<ServerConnection?>
        get() = dataStore.data.map(::read)

    suspend fun save(connection: ServerConnection) {
        val sealed = cipher.seal(connection.credential.secret)

        dataStore.edit { preferences ->
            preferences[ADDRESS] = connection.address.display
            preferences[SECRET] = sealed.encoded
            preferences[USERNAME] = connection.username

            val pin = connection.pinnedKey
            if (pin == null) preferences.remove(PINNED_KEY) else preferences[PINNED_KEY] = pin.hex
        }
    }

    /**
     * Records the key the user accepted for a server already saved.
     *
     * Separate from [save] because it happens at a different moment and must not be able to rewrite
     * the credential: the trust prompt is answered *during* a connection attempt, and a method that
     * took a whole [ServerConnection] would invite a caller to reconstruct one from stale state and
     * quietly overwrite the token with it.
     */
    suspend fun pin(fingerprint: KeyFingerprint) {
        dataStore.edit { preferences -> preferences[PINNED_KEY] = fingerprint.hex }
    }

    /**
     * Forgets the connection.
     *
     * The Keystore key is left in place. It is useless without the ciphertext, rotating it would
     * mean handling the failure of that rotation on a path the user has asked to be simple, and the
     * next [save] overwrites what it protects anyway.
     */
    suspend fun clear() {
        dataStore.edit { preferences ->
            preferences.remove(ADDRESS)
            preferences.remove(SECRET)
            preferences.remove(USERNAME)
            preferences.remove(PINNED_KEY)
        }
    }

    private fun read(preferences: Preferences): ServerConnection? {
        val stored = preferences[ADDRESS] ?: return null
        val sealed = preferences[SECRET] ?: return null

        // Re-parsed rather than stored field by field: `display` is by
        // construction free of the well-known path, so it round-trips, and one
        // parser means the address can never be reconstituted into something
        // the parser would have rejected.
        val address = (ServerAddress.parse(stored) as? ParsedAddress.Valid)?.address ?: return null
        val secret = cipher.open(SealedSecret(sealed)) ?: return null

        return ServerConnection(
            address = address,
            credential = Credential.AppPassword(secret),
            pinnedKey = preferences[PINNED_KEY]?.let(KeyFingerprint::parse),
            username = preferences[USERNAME].orEmpty(),
        )
    }

    companion object {
        /** The DataStore file name, so the Hilt module and the tests cannot disagree about it. */
        const val FILE = "plmail.connection"

        private val ADDRESS = stringPreferencesKey("server.address")
        private val SECRET = stringPreferencesKey("server.secret")
        private val USERNAME = stringPreferencesKey("server.username")
        private val PINNED_KEY = stringPreferencesKey("server.pinnedKey")
    }
}
