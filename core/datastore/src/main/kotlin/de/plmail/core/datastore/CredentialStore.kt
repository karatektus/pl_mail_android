package de.plmail.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import de.plmail.jmap.client.Credential
import de.plmail.jmap.client.KeyFingerprint
import de.plmail.jmap.client.ParsedAddress
import de.plmail.jmap.client.ServerAddress
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
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
    /**
     * Where the ciphertext is opened — see [connection] for why it must not be the caller's thread.
     *
     * [EmptyCoroutineContext] by default, which `flowOn` treats as "no hop at all", so a test
     * reading this store is exactly as synchronous as it was before this parameter existed. The
     * production wiring passes `Dispatchers.IO`, and the reason is entirely the Android Keystore:
     * it is the one collaborator that cannot be exercised off-device, so it is the one cost no test
     * can see and no test should have to pay for.
     */
    private val opening: CoroutineContext = EmptyCoroutineContext,
) {

    /**
     * The one ciphertext this process has opened, and what it opened to.
     *
     * A cache, and it is on the critical path of a cold launch rather than a nicety. Opening the
     * secret is an Android Keystore operation: a binder round trip to the keystore daemon to fetch
     * the key handle, and a second into the TEE — or, on a phone that has StrongBox, into a
     * separate security chip that is an order of magnitude slower again. One is affordable. What
     * the app was doing was not: [connection] is collected by `MainViewModel`, by the calendar
     * probe, by `FeedRepository` when it builds the pager and by `AccountsRepository` for the
     * banner's hostname, so a cold launch opened the same ciphertext four times before the first
     * page could be asked for — and, because `dataStore.data` re-emits the whole file on every
     * write and this app keeps every preference in one file, opened it again in every one of those
     * collectors each time the push log recorded a delivery.
     *
     * Keyed by the ciphertext, so [save] invalidates it by construction: a re-pair writes a new
     * sealed value, which does not match, which decrypts. [clear] never reaches here at all.
     *
     * **Only successes are remembered.** Caching a null would turn "the Keystore was busy for a
     * moment" into "this install has no server" for the rest of the process, and that state is the
     * one that sends somebody back through pairing. A key that genuinely did not survive a restore
     * fails every time anyway, so the honest path costs nothing where it matters and keeps a retry
     * where it might.
     *
     * Holding the plaintext is not a new exposure: every emission of [connection] already carries
     * it, and half the app is holding one of those.
     */
    private var opened: Pair<String, String>? = null

    private val openLock = Any()

    /**
     * The stored connection, or null when there is none *or* when it can no longer be read — and
     * emitted **only when it actually changes**.
     *
     * An unopenable secret deliberately reads as absent: the app's answer to both is the same
     * onboarding screen. It is logged as distinct at the point of failure rather than modelled as a
     * separate state here, because a caller that had to handle "there is a credential but it is
     * unreadable" would only ever do what it does for "there is none".
     *
     * `dataStore.data` emits the whole preference map on every write to the *file*, whichever key
     * moved — and this app keeps everything in one file, so a push registration timestamp being
     * recorded re-emits this too. Without the dedupe that is not merely wasteful: `MainViewModel`
     * reacts to each emission by scheduling background sync and calling `PushSetup.enable`, so a
     * write from the push path re-registers with the distributor, which issues a new endpoint,
     * which is recorded, which writes the file again. A closed loop, running as fast as DataStore
     * can commit, hammering the distributor and the server with it.
     *
     * Found exactly that way: adding `PushStateStore` to the same file turned a latent hazard into
     * a live one within a second of launch. The dedupe belongs here rather than at the one caller,
     * because "the server this app is connected to" is what this flow means, and re-announcing it
     * unchanged is wrong for every subscriber rather than inconvenient for one.
     *
     * Note that the dedupe is *downstream* of [read] and so has never saved a single decryption —
     * it suppresses the emission after the work has been done. [opened] is what makes the work
     * happen once, and [opening] is what keeps it off whichever thread asked.
     *
     * **[flowOn] is load-bearing, not tidiness.** Every operator here otherwise runs in the
     * collector's context, and the collectors that matter are `stateIn(viewModelScope)` and
     * `cachedIn(viewModelScope)` — which is `Dispatchers.Main.immediate`. So the Keystore round
     * trips described on [opened] ran on the UI thread, one after another because they shared it,
     * in front of the first frame and in front of the pager that draws the cached list.
     */
    val connection: Flow<ServerConnection?>
        get() = dataStore.data.map(::read).distinctUntilChanged().flowOn(opening)

    suspend fun save(connection: ServerConnection) {
        val sealed = cipher.seal(connection.credential.secret)

        // Primed rather than left to be opened again a moment later. Saving is
        // the end of pairing, and the screen that replaces onboarding starts by
        // reading this back.
        synchronized(openLock) { opened = sealed.encoded to connection.credential.secret }

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
        val secret = open(sealed) ?: return null

        return ServerConnection(
            address = address,
            credential = Credential.AppPassword(secret),
            pinnedKey = preferences[PINNED_KEY]?.let(KeyFingerprint::parse),
            username = preferences[USERNAME].orEmpty(),
        )
    }

    /**
     * The plaintext secret behind one stored ciphertext, from [opened] where it can be.
     *
     * Locked rather than merely volatile, so the launch storm this exists for collapses into one
     * Keystore call instead of four concurrent ones: the waiters block for the length of that call
     * and then find it done. Blocking a thread is the right shape here — [opening] puts them on
     * `Dispatchers.IO`, which is the pool for exactly this, and the alternative is four TEE
     * operations racing each other on a device that can only run them one at a time anyway.
     */
    private fun open(sealed: String): String? =
        synchronized(openLock) {
            opened?.let { (ciphertext, secret) -> if (ciphertext == sealed) return secret }

            cipher.open(SealedSecret(sealed))?.also { secret -> opened = sealed to secret }
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
