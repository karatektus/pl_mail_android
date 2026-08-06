package de.plmail.core.data

import de.plmail.jmap.methods.EmailSubmissionChanges
import de.plmail.jmap.methods.EmailSubmissionGet
import de.plmail.jmap.methods.SubmissionRecord
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * Keeps this device's list of scheduled sends in step with the server's.
 *
 * **This exists because a server behaviour changed.** plMail used to reconstruct a submission from
 * the Message and skip any Message with no `sentAt`, so a submission still being *held* answered
 * `notFound` from `EmailSubmission/get` — indistinguishable from a draft nobody ever submitted. The
 * release time therefore existed in the create response and nowhere else, the schedule had to live
 * in [ScheduledSendStore], and the feature's honest limit was that a message scheduled on one phone
 * was invisible on every other device and could not be called back from any of them.
 *
 * That is over. `EmailSubmission/get` now answers `pending` with the real `sendAt` for a held
 * submission, `canceled` for one declined before it left and `final` for one that has gone, and
 * `EmailSubmission/changes` reports each transition. So:
 *
 * - A schedule made on a laptop **appears here**, with the server's own release time, and the
 *   Cancel button on it works — cancellation was never device-bound, only the *knowledge* of what
 *   there was to cancel.
 * - A cancel made on the laptop **removes the row here** rather than leaving it counting down to a
 *   release that will not happen.
 * - The local store becomes the cache: right on the first frame, before any network, and still the
 *   only record there is on a server that answers the old way.
 *
 * ## Feature detection, by behaviour
 *
 * Nothing in the session says which generation of plMail this is, and asking for a version would be
 * the wrong question anyway — what matters is what `/get` does, and that is directly observable.
 * The signal is sharp and needs no probe request of its own:
 *
 * > A submission this device submitted, whose release time has **not** yet passed, that comes back
 * > in `notFound`.
 *
 * A server that reports held submissions must have answered `pending` for that one; it is holding
 * it. So `notFound` there is proof of the old behaviour, and the client stays on the local record.
 * Conversely a record answering `pending` or `canceled` is proof of the new behaviour — `final`
 * alone is not, because the old server reported that too.
 *
 * Everything past the release time proves nothing either way and is treated as it always was, on
 * the settle grace. And the rule that makes the fallback safe without any stored flag at all is
 * that **a record is dropped on the server's word and never on its silence**: `canceled`, `final`,
 * or [SendQueue.SETTLE_GRACE_MS] past a release nobody will confirm. An older plMail is silent
 * about exactly the records it is holding, so nothing of the user's is lost by mistaking one server
 * for the other in the direction that matters.
 *
 * ## What was probed, on 8002, 2026-08-06
 *
 * A `HOLDUNTIL` thirty minutes out: `/get` answered `pending` with the `sendAt` from the create
 * response, to the second. `undoStatus: "canceled"` on it: `updated`, then `/get` answered
 * `canceled` keeping that same `sendAt`, and `/changes` reported the id under `updated`. A draft
 * created and never submitted: `notFound`. A get naming all three at once partitioned them
 * correctly. `EmailSubmission/query` does not exist (`unknownMethod`) and `/get` with `ids: null`
 * is `requestTooLarge`, which is why discovery goes through the change log and nothing else.
 *
 * `final` was **not** observed and could not be: the 8002 stack runs `in-memory://` with no
 * consumer, so no submission on it ever completes. It is handled from the documented contract.
 */
@Singleton
class ScheduledSendReconciler
@Inject
constructor(private val scheduled: ScheduledSends, private val directory: SubmissionDirectory) {

    /**
     * Reconciles every account, and never lets one account's unreachable server stop another.
     *
     * Failures are swallowed per account for the same reason [AppearanceRepository.refresh]
     * swallows its own: this runs from sync and from the mail list appearing, nothing here is an
     * operation the user asked for, and the local records are still on disk for the next attempt.
     */
    suspend fun reconcileAll(now: Long = System.currentTimeMillis()) {
        directory.accountKeys().forEach { accountKey ->
            try {
                reconcile(accountKey, now)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // See the docblock.
            }
        }
    }

    /**
     * Reconciles one account and reports what the server's answers proved about it.
     *
     * The return value is the feature detection, and it is returned rather than stored: it is a
     * property of the *answer*, so caching it would be a claim that outlives its evidence — and the
     * one thing this class must never do is act on a stale belief that the server reports holds,
     * because that is the belief under which a real schedule gets deleted.
     */
    suspend fun reconcile(
        accountKey: String,
        now: Long = System.currentTimeMillis(),
    ): SubmissionVisibility {
        val known = scheduled.forAccount(accountKey)
        val discovered = discover(accountKey)

        // Nothing recorded here and nothing new on the server. The common case
        // by a wide margin, and it costs one `/changes` rather than a `/get`.
        val ids = (known.map { it.emailId } + discovered).filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return SubmissionVisibility.UNKNOWN

        val found = mutableListOf<SubmissionRecord>()
        val notFound = mutableSetOf<String>()

        ids.chunked(EmailSubmissionGet.MAX_IDS).forEach { chunk ->
            val snapshot = directory.submissions(accountKey, chunk)

            found += snapshot.found
            notFound += snapshot.notFound
        }

        val outcome = reconcile(accountKey, known, found, notFound, now, SendQueue.SETTLE_GRACE_MS)

        // Only for schedules this device did not make: the subject on a record
        // it did make is the one the user typed, and re-reading it would replace
        // it with whatever the server stored, which for a draft with no subject
        // is an empty string where the bar wants "(no subject)".
        val subjects =
            if (outcome.needSubject.isEmpty()) emptyMap()
            else directory.subjects(accountKey, outcome.needSubject)

        scheduled.replaceAccount(
            accountKey,
            outcome.keep.map { send ->
                if (send.subject.isNotBlank()) send
                else send.copy(subject = subjects[send.emailId].orEmpty())
            },
        )

        return outcome.visibility
    }

    /**
     * Submission ids this device has not heard of, from the change log.
     *
     * The only route there is: there is no `EmailSubmission/query` and `/get` refuses to enumerate.
     * Both `created` and `updated` count — a cancel made elsewhere arrives as an update to an id
     * this device may never have seen created.
     *
     * Two bounds, both deliberate and both worth knowing before trusting this to find everything:
     *
     * - **[MAX_PAGES] pages per run.** The cursor advances by whatever was consumed, so the next
     *   run continues rather than restarting; the cap only decides how much of a long backlog one
     *   reconcile pays for.
     * - **[MAX_DISCOVERED] ids handed to `/get`, taken from the end.** The change log is
     *   chronological, `maxDelayedSend` is thirty days, and a submission old enough to have been
     *   pushed off the tail by that many later ones is not one still waiting to leave. This is the
     *   line where "every schedule on the account" becomes "every recent schedule", and it is here
     *   rather than nowhere because a first run against a mailbox with years of sent mail would
     *   otherwise be hundreds of round trips against a Raspberry Pi.
     */
    private suspend fun discover(accountKey: String): List<String> {
        var cursor = scheduled.cursors()[accountKey] ?: EmailSubmissionChanges.FROM_THE_BEGINNING
        val ids = mutableListOf<String>()
        var pages = 0

        while (pages < MAX_PAGES) {
            val delta = directory.submissionChanges(accountKey, cursor)

            ids += delta.changed
            pages++

            // A server that answers with the state it was handed has nothing to
            // say and would otherwise spin this loop MAX_PAGES times.
            if (delta.newState == cursor) break

            cursor = delta.newState

            if (!delta.hasMore) break
        }

        scheduled.setCursor(accountKey, cursor)

        return ids.distinct().takeLast(MAX_DISCOVERED)
    }

    private companion object {
        const val MAX_PAGES = 8
        const val MAX_DISCOVERED = 128
    }
}

/**
 * What the server proved about whether it reports submissions it is still holding.
 *
 * Deliberately three-valued. "Nothing was learned" is the ordinary answer — an account with no
 * schedules teaches nothing — and collapsing it into either of the other two would be a guess
 * dressed as a fact.
 */
enum class SubmissionVisibility {
    /** A held or cancelled submission was reported. The server's answer is authoritative. */
    REPORTS_HELD,

    /**
     * A submission known to be held came back `notFound`. An older plMail; the local store is the
     * only record of this schedule and nothing may retire it before its time.
     */
    HIDES_HELD,

    /** No evidence either way, which is what an account with nothing scheduled looks like. */
    UNKNOWN,
}

/** What one account's reconciliation decided. */
internal data class Reconciliation(
    /** The whole of this account's schedule afterwards. Written as one list, not as edits. */
    val keep: List<ScheduledSend>,
    /** Ids in [keep] that arrived from the server and have no subject yet. */
    val needSubject: List<String>,
    val visibility: SubmissionVisibility,
)

/**
 * The decision, with no store, no client and no coroutine in it.
 *
 * Pure because it is the whole of the policy, and because every case in it is one somebody has to
 * be able to check without standing up a server: a cancel made elsewhere, a hold an old server will
 * not admit to, a release time that has quietly passed. The same reason
 * `AppearanceRepository.resolve` is a function.
 *
 * [notFound] is a set rather than a list because the only question asked of it is membership, and
 * because a server naming an id twice across two chunks must not count twice.
 */
internal fun reconcile(
    accountKey: String,
    known: List<ScheduledSend>,
    found: List<SubmissionRecord>,
    notFound: Set<String>,
    now: Long,
    graceMs: Long,
): Reconciliation {
    val byId = known.associateBy { it.emailId }
    val keep = mutableListOf<ScheduledSend>()
    val needSubject = mutableListOf<String>()
    var reportsHeld = false
    var hidesHeld = false

    fun discovered(emailId: String, record: SubmissionRecord, sendAt: Long): ScheduledSend {
        needSubject += emailId

        return ScheduledSend(
            accountKey = accountKey,
            emailId = emailId,
            identityId = record.identityId.orEmpty(),
            subject = "",
            sendAt = sendAt,
        )
    }

    found.forEach { record ->
        val emailId = record.emailId.ifBlank { record.id }
        val local = byId[emailId]

        // `final` is not evidence: the old server reported completed sends too,
        // and reading it as proof of the new behaviour is exactly the mistake
        // that would let an old server's silence retire a live schedule.
        if (record.isPending || record.isCanceled) reportsHeld = true

        // Declined here or elsewhere, or gone. Either way there is nothing left
        // to show and nothing left to cancel, and the Sent label is where the
        // answer lives now.
        if (record.isCanceled || record.isFinal) return@forEach

        val sendAt = record.sendAt?.toEpochMillisOrNull() ?: local?.sendAt ?: return@forEach

        when {
            // Still held and still ahead of us: the row the user can act on. The
            // server's `sendAt` wins over the local copy without ceremony -- it
            // is the same number when this device made the schedule, and it is
            // the only one there is when another device did.
            sendAt > now ->
                keep +=
                    local?.copy(
                        sendAt = sendAt,
                        identityId = record.identityId ?: local.identityId,
                    ) ?: discovered(emailId, record, sendAt)

            // Pending with a release time behind us: the worker is late, or the
            // server rounded. Believed for the grace and then dropped, which is
            // the rule the settle pass has always used.
            now - sendAt <= graceMs ->
                keep += local?.copy(sendAt = sendAt) ?: discovered(emailId, record, sendAt)
        }
    }

    known
        .filter { it.emailId in notFound }
        .forEach { local ->
            if (local.isPendingAt(now)) {
                // The detection. This device submitted it, the release time has not
                // arrived, so a server that reports holds would be reporting this
                // one. It is not, so it does not -- and its silence is not consent
                // to forget the only copy of the release time in existence.
                hidesHeld = true
                keep += local
            } else if (now - local.sendAt <= graceMs) {
                // Past its time and unconfirmed. Ambiguous on both generations of
                // server; kept for the grace and no longer.
                keep += local
            }
        }

    return Reconciliation(
        keep = keep.distinctBy { it.emailId }.sortedBy { it.sendAt },
        needSubject = needSubject.distinct(),
        visibility =
            when {
                // Proof beats absence of proof, and a *single* record answering
                // `pending` settles it -- an account can hold one schedule this
                // server reports and another whose Email somebody destroyed.
                reportsHeld -> SubmissionVisibility.REPORTS_HELD
                hidesHeld -> SubmissionVisibility.HIDES_HELD
                else -> SubmissionVisibility.UNKNOWN
            },
    )
}

/**
 * The half of [ComposeRepository] a reconcile needs.
 *
 * An interface for the same reason [DraftSender] is one: the decisions above are worth a test that
 * runs on the JVM in milliseconds rather than one that needs a database, a credential store and a
 * server that can be persuaded to hold a message.
 */
interface SubmissionDirectory {

    /** Every account this device could reconcile. */
    suspend fun accountKeys(): List<String>

    /** `EmailSubmission/get`, partitioned exactly as the server partitioned it. */
    suspend fun submissions(accountKey: String, ids: List<String>): SubmissionSnapshot

    /** One page of `EmailSubmission/changes`. */
    suspend fun submissionChanges(accountKey: String, sinceState: String): SubmissionDelta

    /**
     * Subjects for messages this device did not compose.
     *
     * By email id, missing where the server would not say — a schedule with no subject draws as
     * "(no subject)", which is the same thing the composer shows for a draft with none.
     */
    suspend fun subjects(accountKey: String, emailIds: List<String>): Map<String, String>
}

data class SubmissionSnapshot(
    val found: List<SubmissionRecord> = emptyList(),
    val notFound: List<String> = emptyList(),
)

data class SubmissionDelta(
    val newState: String = "",
    val changed: List<String> = emptyList(),
    val hasMore: Boolean = false,
)
