package de.plmail.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The messages this device has already interrupted somebody about.
 *
 * A ledger of message keys, and the last line of defence against announcing the same mail twice.
 * There are already two in front of it — the cache is asked "have you seen this id" before anything
 * is written, and a message is filtered rather than fanned out over its labels — and neither closes
 * the case this exists for: **two syncs running at once**. A push arriving while the periodic
 * worker is mid-hydration gives two coroutines that both read `known` before either writes, and
 * both then honestly conclude the same message is new.
 *
 * [claim] closes it because DataStore serialises writes to one file: the read, the decision and the
 * write happen inside a single `edit`, so the second caller sees the first caller's entries.
 * Nothing else here needs a lock, and adding one around the sync instead would be a lock held
 * across network calls.
 *
 * **Not a cache of what is on screen.** Dismissing a notification does not come back here, and it
 * must not: the question this answers is "have we already told them", and a message the user swiped
 * away has been told. That is also why the retention is measured in days rather than in what the
 * shade is showing.
 */
@Singleton
class NotifiedMessageStore @Inject constructor(private val preferences: DataStore<Preferences>) {

    /**
     * Takes the messages that have not been announced before, and records them as announced.
     *
     * Returns the subset of [keys] this call is responsible for — the caller must announce exactly
     * those and nothing else. Order is preserved, because the caller's list is in the order the
     * server answered and the notification's `when` is drawn from it.
     *
     * Pruning happens here rather than on a schedule, for the same reason the channel is created on
     * every notification: a maintenance task that only runs when someone remembers to call it is a
     * maintenance task that stops running. It costs one pass over a list that is capped at
     * [MAX_ENTRIES].
     */
    suspend fun claim(keys: List<String>, now: Long): List<String> {
        if (keys.isEmpty()) return emptyList()

        var fresh: List<String> = emptyList()

        preferences.edit { store ->
            val held = decode(store[NOTIFIED])
            val alive = held.filterTo(mutableMapOf()) { now - it.value < RETENTION_MILLIS }

            // Distinct as well as unheld: one `Email/get` cannot answer the same
            // id twice, but this method's contract is "announce exactly what I
            // return" and a duplicate in the argument would make that a lie.
            fresh = keys.distinct().filterNot { it in alive }

            fresh.forEach { alive[it] = now }

            store[NOTIFIED] = encode(alive)
        }

        return fresh
    }

    /**
     * `key<TAB>millis` per line.
     *
     * Tab inside a record and newline between them, because both halves have to be impossible in
     * the data: a message key is `<server>/<accountId>#<emailId>`, a URL and two server-issued ids,
     * and none of those can carry either character. An entry that does not parse is dropped rather
     * than crashing the sync — the cost of forgetting one is one repeated notification, and the
     * cost of throwing here is a sync that dies inside a broadcast receiver.
     */
    private fun decode(stored: String?): Map<String, Long> =
        stored
            ?.lineSequence()
            .orEmpty()
            .mapNotNull { line ->
                val at = line.lastIndexOf(FIELD)

                if (at <= 0) return@mapNotNull null

                val millis = line.substring(at + 1).toLongOrNull() ?: return@mapNotNull null

                line.substring(0, at) to millis
            }
            .toMap()

    /**
     * Newest first, capped.
     *
     * The cap is a second bound beside the age one, and it is the one that matters on a busy
     * mailbox: this file also holds the credential and is rewritten in full on every write, so an
     * unbounded ledger would make every preference write in the app proportional to how much mail
     * arrived this week.
     */
    private fun encode(entries: Map<String, Long>): String =
        entries.entries
            .sortedByDescending { it.value }
            .take(MAX_ENTRIES)
            .joinToString(RECORD) { "${it.key}$FIELD${it.value}" }

    private companion object {
        val NOTIFIED = stringPreferencesKey("notified_messages")

        const val RECORD = "\n"
        const val FIELD = '\t'

        /**
         * A week.
         *
         * Long enough that nothing plausible re-announces — the cache would have to have forgotten
         * the message *and* the server re-report it as changed — and short enough that the ledger
         * stays small on a mailbox nobody reads.
         */
        const val RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000

        /** Roughly a fortnight of heavy mail, and about 100KB of preferences file at worst. */
        const val MAX_ENTRIES = 2_000
    }
}
