package de.plmail.core.data

import de.plmail.jmap.methods.SendHold
import de.plmail.jmap.methods.SubmissionRecord
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Everything between tapping Send and the mail leaving: the undo window, and "send later".
 *
 * **The undo window is now the server's hold, not a timer in this class**, and that is the change
 * worth understanding. It used to be a local `delay` before submitting at all, which had two
 * defects that were invisible until they bit: a process death inside those six seconds dropped the
 * send silently — the user had watched the composer close and believed the mail had gone — and the
 * mail left six seconds late even when nobody was going to undo anything. `EmailSubmission/set` now
 * takes `HOLDFOR`, so the submission goes out immediately with a six-second hold on it, and undo is
 * a cancel the server honours. Kill the app mid-window and the mail still leaves on time.
 *
 * What that costs, honestly: undo is now a network round trip that can fail or arrive too late, and
 * both outcomes are reported rather than swallowed — [SendState.TooLate] exists because "undone"
 * must never be shown over a message that has been sent.
 *
 * The local path is kept for a server that advertises no hold ([SubmissionMode.LOCAL_DELAY]), and
 * it is the old behaviour exactly. Which one is in force is read from the session per account,
 * never assumed.
 *
 * The ordering is the part that has not changed and must not: the draft is written to the server
 * **first**, then submitted. Sending straight from memory would mean a failure loses the message
 * with no trace of it anywhere; this way the worst case is a draft in Drafts, which is exactly what
 * the user would expect to find.
 */
@Singleton
class SendQueue
@Inject
constructor(
    private val compose: DraftSender,
    private val scheduled: ScheduledSends,
    /**
     * What keeps the schedule agreeing with the server's own.
     *
     * Injected rather than constructed so a test of the undo window does not have to stand up a
     * reconcile it has nothing to say about — and so the reconcile's own tests do not have to go
     * through a send.
     */
    private val reconciler: ScheduledSendReconciler,
    /**
     * A scope that deliberately outlives the composer.
     *
     * The screen closes the instant Send is tapped — that is the whole point — so a
     * `viewModelScope` would cancel the send along with it. Injected rather than constructed here
     * so the window can be tested against a virtual clock instead of six real seconds.
     */
    @param:ApplicationScope private val scope: CoroutineScope,
) {

    private val mutex = Mutex()
    private var job: Job? = null

    private val _state = MutableStateFlow<SendState>(SendState.Idle)

    /** What the snackbar over the mail list should be saying. */
    val state: StateFlow<SendState> = _state.asStateFlow()

    /** Every send this device is still holding a promise about, soonest first. */
    val schedule = scheduled.all

    /**
     * Saves [draft] and sends it, either shortly or at [at].
     *
     * One at a time: a second send while one is pending waits for the first rather than racing it,
     * because both would otherwise write to the same account's Email state and one would be
     * rejected with `stateMismatch` for reasons the user could not connect to anything they did.
     */
    fun enqueue(draft: ComposeDraft, at: Instant? = null) {
        scope.launch {
            mutex.withLock {
                job?.join()

                job = scope.launch { run(draft, at) }
            }
        }
    }

    private suspend fun run(draft: ComposeDraft, at: Instant?) {
        // Tracked separately so a failure reports the draft as the *server* last
        // knew it. Reporting the one handed in would drop the id a successful
        // save had just assigned, and reopening the composer on that would
        // create a second draft beside the first.
        var current = draft

        try {
            current = compose.save(draft)

            if (at != null) {
                schedule(current, at)
                return
            }

            when (compose.submissionMode(current.accountKey)) {
                SubmissionMode.SERVER_HOLD -> sendHeld(current)
                SubmissionMode.LOCAL_DELAY -> sendAfterLocalDelay(current)
            }
        } catch (cancelled: CancellationException) {
            // Undo of a local-delay send. `runCatching` here would swallow this
            // and report "StandaloneCoroutine was cancelled" as a send failure --
            // a red snackbar for the user's own deliberate action.
            throw cancelled
        } catch (failure: Exception) {
            // Never swallowed. The composer has already closed, so a send that
            // fails silently is a message the user believes they have sent.
            _state.value =
                SendState.Failed(
                    draft = current,
                    message = failure.message ?: "The message could not be sent.",
                )
        }
    }

    /**
     * Submits at once with a short hold, and lets the window run over a send that is already the
     * server's problem.
     *
     * The countdown here is presentation only — it decides how long the Undo button is offered, not
     * when the mail goes. If this process dies at second three the snackbar disappears and the mail
     * still leaves at second six, which is the whole reason for the change.
     */
    private suspend fun sendHeld(draft: ComposeDraft) {
        val receipt = compose.submit(draft, SendHold.For(UNDO_WINDOW_MS / 1_000))

        _state.value =
            SendState.Pending(
                draft = draft,
                endsAt = System.currentTimeMillis() + UNDO_WINDOW_MS,
                submissionId = receipt.submissionId,
            )

        delay(UNDO_WINDOW_MS)

        // Only if nothing else has happened in the meantime. An undo that
        // cancelled the send has already moved the state on, and overwriting it
        // with "Sent" would be the one lie this class exists to avoid.
        if (_state.value.isPendingFor(receipt.submissionId)) _state.value = SendState.Sent
    }

    /** The old behaviour, for a server that offers no hold: wait here, then submit. */
    private suspend fun sendAfterLocalDelay(draft: ComposeDraft) {
        _state.value =
            SendState.Pending(draft = draft, endsAt = System.currentTimeMillis() + UNDO_WINDOW_MS)

        delay(UNDO_WINDOW_MS)
        compose.submit(draft, hold = null)

        _state.value = SendState.Sent
    }

    private suspend fun schedule(draft: ComposeDraft, at: Instant) {
        val receipt = compose.submit(draft, SendHold.Until(at.toUtcDate()))

        // The server's own answer, not the instant the user picked. They agree
        // to the second here, and on an instance that rounds or clamps they
        // would not -- and the one the user is shown has to be the one the mail
        // actually leaves at.
        val sendAt = receipt.sendAt?.toEpochMillisOrNull() ?: at.toEpochMilli()

        scheduled.record(
            ScheduledSend(
                accountKey = draft.accountKey,
                emailId = draft.emailId.orEmpty(),
                identityId = draft.identityId,
                subject = draft.subject,
                sendAt = sendAt,
            )
        )

        _state.value = SendState.Scheduled(draft = draft, sendAt = sendAt)
    }

    /**
     * Cancels a send still inside its undo window and hands the draft back.
     *
     * Returns null when there was nothing to cancel — the window had already elapsed, or the
     * snackbar was tapped twice. Reporting that as an error would be worse than doing nothing.
     *
     * When the send is the server's hold, this is a real request, and it can come back too late:
     * the state then becomes [SendState.TooLate] and null is returned, because the mail has gone
     * and there is nothing to reopen.
     */
    suspend fun undo(): ComposeDraft? {
        val pending = _state.value as? SendState.Pending ?: return null

        mutex.withLock {
            job?.cancel()
            job = null
        }

        val submissionId = pending.submissionId

        if (submissionId == null) {
            // Nothing was ever submitted: the local delay was the whole window.
            _state.value = SendState.Idle
            return pending.draft
        }

        val outcome = runCatching {
            compose.cancel(pending.draft.accountKey, submissionId)
        }
            .getOrElse { failure ->
                _state.value =
                    SendState.Failed(
                        draft = pending.draft,
                        message =
                            failure.message
                                ?: "The send could not be called back. It may already have " +
                                    "gone.",
                    )
                return null
            }

        return when (outcome) {
            CancelOutcome.Cancelled -> {
                _state.value = SendState.Idle
                pending.draft
            }
            CancelOutcome.AlreadySent -> {
                _state.value = SendState.TooLate
                null
            }
        }
    }

    /**
     * Calls back a message scheduled for later.
     *
     * The record is forgotten on success and also when the server says the mail has already left —
     * in both cases there is nothing left for this device to promise. A failure keeps the record,
     * because the send is still coming.
     */
    suspend fun cancelScheduled(send: ScheduledSend): CancelOutcome {
        val outcome = compose.cancel(send.accountKey, send.emailId)

        scheduled.forget(send.accountKey, send.emailId)

        return outcome
    }

    /**
     * Retires records whose release time has passed.
     *
     * **A non-null answer is no longer enough to drop one, and that is the change here.**
     * `EmailSubmission/get` used to resolve only for a completed send, so anything it returned
     * meant "gone". It now also answers `pending` for a submission the worker has not got to yet —
     * so a queue running a minute behind would, under the old rule, have made the row vanish while
     * the mail was still sitting in it. A record is dropped when the server says the send is
     * settled — [SubmissionRecord.isFinal] or [SubmissionRecord.isCanceled] — and otherwise only
     * after [SETTLE_GRACE_MS], because a row still saying "leaving at 08:00" at half past nine is
     * worse than no row at all.
     *
     * The grace is also what covers the server that says nothing useful: an older plMail answers
     * `notFound` for a held submission, and a `null` here is that, a cancelled send and a failed
     * one all wearing the same face.
     */
    suspend fun settle(now: Long = System.currentTimeMillis()) {
        scheduled.elapsed(now).forEach { send ->
            val record = runCatching {
                compose.releasedAt(send.accountKey, send.emailId)
            }
                .getOrNull()

            val settled = record != null && (record.isFinal || record.isCanceled)

            if (settled || now - send.sendAt > SETTLE_GRACE_MS) {
                scheduled.forget(send.accountKey, send.emailId)
            }
        }
    }

    /**
     * Brings the schedule in line with the server's, which is now where it actually lives.
     *
     * Kept behind the queue rather than injected wherever it is needed, so the one class that owns
     * "what is still waiting to go" stays the one thing a screen has to know about.
     */
    suspend fun reconcile() = reconciler.reconcileAll()

    /** Clears a terminal state once it has been shown. */
    fun acknowledge() {
        if (_state.value !is SendState.Pending) _state.value = SendState.Idle
    }

    companion object {
        /**
         * Six seconds, matching the undo window the rest of the app uses for archive and trash.
         *
         * Long enough to read a snackbar and react, short enough that nobody wonders whether the
         * mail went. Gmail offers 5–30s as a setting; that belongs with the rest of the settings in
         * M10 rather than as a constant nobody can reach.
         *
         * A whole number of seconds on purpose: it becomes `HOLDFOR`, which the RFC counts in them.
         */
        const val UNDO_WINDOW_MS = 6_000L

        /** How long past its release time a schedule is still believed. See [settle]. */
        const val SETTLE_GRACE_MS = 5 * 60_000L
    }
}

/**
 * Which mechanism holds a send back for its undo window.
 *
 * Decided per account from the session, never from a constant here — the ceiling and the
 * `FUTURERELEASE` extension are the server's answers, and an instance that publishes neither has to
 * keep working.
 */
enum class SubmissionMode {
    /** `HOLDFOR` on the submission. Survives the app being killed. */
    SERVER_HOLD,

    /** A `delay` before submitting at all. Does not. */
    LOCAL_DELAY,
}

/** What the server said when it took a submission. */
data class Submitted(
    /** plMail's submission id is the Email id, but it is read back rather than assumed. */
    val submissionId: String,
    /** The server's own release time, as a JMAP `UTCDate`. Null for an immediate send. */
    val sendAt: String? = null,
)

/** Whether a cancel arrived in time. */
enum class CancelOutcome {
    Cancelled,
    AlreadySent,
}

/**
 * The half of [ComposeRepository] the queue needs.
 *
 * An interface for one reason: [SendQueue]'s ordering — save first, then submit — is the rule that
 * decides whether a failure loses someone's mail, and it is worth a test that runs on the JVM in
 * milliseconds rather than one that needs a database, a content resolver and a server.
 */
interface DraftSender {

    suspend fun save(draft: ComposeDraft): ComposeDraft

    suspend fun submit(draft: ComposeDraft, hold: SendHold?): Submitted

    suspend fun cancel(accountKey: String, submissionId: String): CancelOutcome

    /** The completed send, or null — see [SendQueue.settle] for why null is three things. */
    suspend fun releasedAt(accountKey: String, submissionId: String): SubmissionRecord?

    suspend fun submissionMode(accountKey: String): SubmissionMode
}

/** Where a send has got to. */
sealed interface SendState {

    data object Idle : SendState

    /**
     * Submitted with a short hold, or waiting to be submitted, and undoable either way.
     *
     * [endsAt] rather than a remaining duration, so a snackbar recomposing at 60fps does not need
     * the queue to tick, and a screen rotation does not restart the countdown.
     *
     * [submissionId] is null only in [SubmissionMode.LOCAL_DELAY], where nothing has been submitted
     * yet — which is exactly the difference between an undo that is free and an undo that is a
     * request the server may refuse.
     */
    data class Pending(
        val draft: ComposeDraft,
        val endsAt: Long,
        val submissionId: String? = null,
    ) : SendState

    data object Sent : SendState

    /** Accepted and held until [sendAt], which is the server's release time and not the user's. */
    data class Scheduled(val draft: ComposeDraft, val sendAt: Long) : SendState

    /** Undo lost the race. The mail has gone and there is nothing to reopen. */
    data object TooLate : SendState

    /** [draft] so the composer can be reopened on exactly what failed to go. */
    data class Failed(val draft: ComposeDraft, val message: String) : SendState
}

private fun SendState.isPendingFor(submissionId: String): Boolean =
    this is SendState.Pending && this.submissionId == submissionId

/**
 * A JMAP `UTCDate`: whole seconds, `Z`, never an offset.
 *
 * Explicit rather than `Instant.toString()`, which drops the seconds when they are zero and emits
 * fractional ones when they are not — `2026-08-07T06:00Z` is a shape the server's date parser
 * happens to take and not one worth relying on.
 */
internal fun Instant.toUtcDate(): String = UTC_DATE.format(this)

/** The server's `sendAt`, or null when it is absent or in a form this client cannot read. */
internal fun String.toEpochMillisOrNull(): Long? = runCatching {
    Instant.parse(this).toEpochMilli()
}
    // The offset form as well as `Z`: a server behind a proxy that rewrites
    // dates hands back `2026-08-07T08:00:00+02:00`, which is a perfectly
    // good instant that `Instant.parse` refuses.
    .recoverCatching { OffsetDateTime.parse(this).toInstant().toEpochMilli() }
    .getOrNull()

private val UTC_DATE: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)
