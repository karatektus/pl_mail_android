package de.plmail.core.data

import de.plmail.core.datastore.ScheduledSendStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One message the server is holding, as this device remembers it.
 *
 * [sendAt] is the server's own `sendAt` from the create response and never a time computed here: a
 * `HOLDFOR` is measured from when the request *arrived*, and a phone whose clock is a minute fast
 * would otherwise show a release that has not happened.
 *
 * [subject] is stored rather than looked up, because the whole point of this record is to survive
 * the cache being dropped — a scheduled send whose subject resolves to nothing would show as an
 * unnamed row the user cannot recognise.
 */
@Serializable
data class ScheduledSend(
    val accountKey: String,
    /** Which draft. Also the submission id — plMail's submission id *is* the Email id. */
    val emailId: String,
    val identityId: String,
    val subject: String,
    /** Epoch millis, from the server's `sendAt`. */
    val sendAt: Long,
) {
    /** Whether the release time is still ahead, which is the whole window cancelling exists in. */
    fun isPendingAt(now: Long): Boolean = sendAt > now
}

/**
 * Every send this device knows the server is holding.
 *
 * **The list used to be the client's alone and is now a cache of the server's.**
 * `EmailSubmission/get` reports a held submission as `pending` with its real `sendAt`, so
 * [ScheduledSendReconciler] can put a schedule made on a laptop into this list and take out one
 * cancelled there — see [ScheduledSendStore] for what changed and what did not. What is left of the
 * old asymmetry is the retirement rule, still deliberately conservative:
 *
 * - **Before the release time** the record stands. The mail is held, the draft is still in Drafts,
 *   and cancelling is a call the server will honour whichever device asked for the hold.
 * - **After it** the client can no longer promise anything of its own. The cancel would race the
 *   worker. The server's own answer settles it — `canceled` and `final` are both "stop showing
 *   this" — and a record the server says nothing useful about is dropped after a grace, because a
 *   row still saying "leaving at 08:00" at half past nine is worse than no row at all.
 *
 * A record is **never** dropped on silence alone before its release time. That is the whole of the
 * fallback for an older plMail: there, silence is what a held submission sounds like.
 *
 * Nothing here refuses a schedule the server would take. The ceiling comes from the session.
 */
@Singleton
class ScheduledSends @Inject constructor(private val store: ScheduledSendStore) {

    /** Everything recorded for one account, which is the unit a reconcile works in. */
    suspend fun forAccount(accountKey: String): List<ScheduledSend> =
        decode(store.records.first()).filter { it.accountKey == accountKey }

    /**
     * Applies a whole account's reconciliation in one write.
     *
     * One `update` rather than a `record` per row and a `forget` per drop: every one of those wakes
     * the bar over the mail list, and a reconcile that added three schedules and retired two would
     * redraw it five times, twice showing a list that was never true.
     *
     * Records for **other** accounts are carried through untouched — this is a statement about one
     * account, and a reconcile of the second account must not erase the first one's schedule.
     */
    suspend fun replaceAccount(accountKey: String, records: List<ScheduledSend>) {
        store.update { raw ->
            encode(decode(raw).filterNot { it.accountKey == accountKey } + records)
        }
    }

    /** Where each account's `EmailSubmission/changes` walk has reached, by account key. */
    suspend fun cursors(): Map<String, String> = decodeCursors(store.cursors.first())

    suspend fun setCursor(accountKey: String, state: String) {
        store.updateCursors { raw -> encodeCursors(decodeCursors(raw) + (accountKey to state)) }
    }

    /** Everything recorded, soonest first. */
    val all: Flow<List<ScheduledSend>> = store.records.map { decode(it).sortedBy { it.sendAt } }

    /** The ones still ahead of [now], which are the ones a user can still do something about. */
    fun pending(now: () -> Long): Flow<List<ScheduledSend>> = all.map { records ->
        records.filter { it.isPendingAt(now()) }
    }

    suspend fun record(send: ScheduledSend) {
        store.update { raw ->
            // Replaced rather than appended: one draft has at most one live
            // submission, and re-scheduling the same message must not leave the
            // old release time behind for the user to read.
            encode(decode(raw).filterNot { it.matches(send.accountKey, send.emailId) } + send)
        }
    }

    suspend fun forget(accountKey: String, emailId: String) {
        store.update { raw -> encode(decode(raw).filterNot { it.matches(accountKey, emailId) }) }
    }

    suspend fun forDraft(accountKey: String, emailId: String): ScheduledSend? =
        decode(store.records.first()).firstOrNull { it.matches(accountKey, emailId) }

    /** The records whose release time has passed, for the settle pass described above. */
    suspend fun elapsed(now: Long): List<ScheduledSend> =
        decode(store.records.first()).filterNot { it.isPendingAt(now) }

    private fun decode(raw: String): List<ScheduledSend> =
        if (raw.isBlank()) emptyList()
        else
        // A record written by a build that is no longer installed. Cleared
        // rather than crashed on, matching the outbox: the alternative is an
        // app that cannot start until somebody clears its data.
        runCatching { JSON.decodeFromString<List<ScheduledSend>>(raw) }.getOrDefault(emptyList())

    private fun encode(records: List<ScheduledSend>): String = JSON.encodeToString(records)

    private fun decodeCursors(raw: String): Map<String, String> =
        if (raw.isBlank()) emptyMap()
        else
            runCatching { JSON.decodeFromString<Map<String, String>>(raw) }.getOrDefault(emptyMap())

    private fun encodeCursors(cursors: Map<String, String>): String = JSON.encodeToString(cursors)

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
    }
}

private fun ScheduledSend.matches(accountKey: String, emailId: String): Boolean =
    this.accountKey == accountKey && this.emailId == emailId
