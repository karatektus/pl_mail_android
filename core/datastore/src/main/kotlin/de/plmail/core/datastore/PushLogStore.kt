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
 * Every push this device has actually received, newest first, bounded.
 *
 * **Why it exists.** plMail's server can say what it *sent*; only the phone can say what arrived.
 * Between those two facts sit a distributor, or Google's servers, or a socket the OS closed — and
 * the interesting failures all live in that gap. A log kept here can be compared line for line
 * against the server's own delivery log, which is the only way to answer "the server says it sent
 * it, so where did it go?" without guessing.
 *
 * **Why not Room.** Every row in that database is reconstructible from the server, which is what
 * licenses its drop-and-resync migration policy. This is the exact opposite: it is a record of
 * events the server cannot replay, and a schema bump for an unrelated mail column would silently
 * erase the evidence somebody was collecting. It is also small — [LIMIT] lines of a few dozen bytes
 * — so the argument for a table is weak in both directions.
 *
 * **Opaque strings, deliberately.** What a delivery *is* belongs to `:core:data`, which owns the
 * payload types and the JSON; a store that understood them would need changing every time a field
 * was added, exactly as [OutboxStore] would. One line per entry, newest first, and entries are
 * compact JSON so a newline can be the separator without any escaping.
 *
 * The mutex is not decoration. Two pushes arriving a moment apart both read-modify-write this key,
 * and DataStore's `edit` serialises writes but not the decode-prepend-encode around them — so
 * without it the second would overwrite a log it had read before the first was added, and the entry
 * it dropped would be the one nobody could see was missing.
 */
@Singleton
class PushLogStore @Inject constructor(private val preferences: DataStore<Preferences>) {

    private val mutex = Mutex()

    /** The log, newest first, as `:core:data` wrote it. */
    val entries: Flow<List<String>> =
        preferences.data
            .map { stored -> stored[ENTRIES]?.lines()?.filter { it.isNotBlank() }.orEmpty() }
            // The same file carries the credential and the push state, both
            // written during an ordinary sync, and this backs a list that
            // redraws on every emission.
            .distinctUntilChanged()

    /**
     * Prepends one entry and drops whatever falls off the end.
     *
     * Bounded here rather than at read time, so a phone that has been running for a month is not
     * storing a month of pushes to display two hundred of them.
     */
    suspend fun append(entry: String) {
        val line = entry.replace('\n', ' ')

        mutex.withLock {
            preferences.edit { store ->
                val existing = store[ENTRIES]?.lines()?.filter { it.isNotBlank() }.orEmpty()

                store[ENTRIES] = (listOf(line) + existing).take(LIMIT).joinToString("\n")
            }
        }
    }

    /**
     * Empties the log. Offered on the screen, because a log you cannot reset is a log you distrust.
     */
    suspend fun clear() {
        mutex.withLock { preferences.edit { it.remove(ENTRIES) } }
    }

    companion object {
        /**
         * How many deliveries are kept.
         *
         * Enough to cover a busy day and to see a pattern in it; small enough that the whole log is
         * one preference value read in one go. A user comparing against the server's log is looking
         * at the last few hours, not at history.
         */
        const val LIMIT = 200

        private val ENTRIES = stringPreferencesKey("push_log_entries")
    }
}
