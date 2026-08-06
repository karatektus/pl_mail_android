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
 * Every send this device is still holding a promise about.
 *
 * The list is the client's, not the server's, and that asymmetry is the design rather than an
 * oversight — see [ScheduledSendStore] for why there is nothing to read back. What follows from it
 * is the retirement rule, which is deliberately conservative:
 *
 * - **Before the release time** the record is authoritative. The mail is held, the draft is still
 *   in Drafts, and cancelling is a call the server will honour.
 * - **After it** the client can no longer promise anything. The cancel would race the worker, and
 *   `EmailSubmission/get` answers either "sent, at this time" or `notFound` — which is a cancelled
 *   send, a send in flight and a send that failed, all wearing the same face. So the record is
 *   settled once and retired either way: what happened to the mail is then the Sent label's answer
 *   to give, and it is a truthful one.
 *
 * Nothing here refuses a schedule the server would take. The ceiling comes from the session.
 */
@Singleton
class ScheduledSends @Inject constructor(private val store: ScheduledSendStore) {

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

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
    }
}

private fun ScheduledSend.matches(accountKey: String, emailId: String): Boolean =
    this.accountKey == accountKey && this.emailId == emailId
