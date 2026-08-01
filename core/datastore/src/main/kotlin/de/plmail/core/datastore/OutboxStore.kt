package de.plmail.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Changes the user made that have not reached the server.
 *
 * **Not in Room, and this is the clearest case in the app for why that rule exists.** Every row in
 * that database is reconstructible from the server, which is what licenses "on any migration or
 * corruption failure, drop it and re-sync". A queued mutation is the exact opposite: it is the one
 * piece of state the server *does not have*, and dropping it would silently discard something the
 * user did — an archive that never happened, on a phone that showed them it had. So it lives here,
 * where nothing throws it away.
 *
 * Held as an opaque string rather than as parsed values, for the same reason [AppearanceStore]
 * holds wire strings: what a mutation *is* belongs to `:core:data`, which owns the actions and the
 * JSON, and a store that understood them would have to be changed every time one was added.
 *
 * The mutex is not decoration. Two failing actions a second apart both read-modify-write this key,
 * and DataStore's own `edit` serialises writes but not the decode-append-encode around them — so
 * without it the second would overwrite a queue it had read before the first was added, and the
 * change it dropped would be the one nobody could see was missing.
 */
@Singleton
class OutboxStore @Inject constructor(private val preferences: DataStore<Preferences>) {

    private val mutex = Mutex()

    /** The queue as `:core:data` wrote it, or empty when there is none. */
    val queue: Flow<String> =
        preferences.data
            .map { it[QUEUE].orEmpty() }
            // The mail list subscribes to this to draw "waiting to send"; the
            // same file carries the push state and the credential, both written
            // during an ordinary sync.
            .distinctUntilChanged()

    /**
     * Rewrites the queue from whatever [transform] makes of it.
     *
     * Read and write inside one lock, so an append and a drain running at once cannot lose each
     * other's work.
     */
    suspend fun update(transform: (String) -> String) {
        mutex.withLock { preferences.edit { it[QUEUE] = transform(it[QUEUE].orEmpty()) } }
    }

    private companion object {
        val QUEUE = stringPreferencesKey("outbox_queue")
    }
}
