package de.plmail.core.data

import de.plmail.jmap.methods.SendHold
import de.plmail.jmap.methods.SubmissionRecord
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The undo window, which is the server's hold now, and the fallback for a server without one.
 *
 * The things worth guarding are all invisible when they go wrong: that the draft reaches the server
 * *before* anything asks for it to leave, that undoing hands back the draft the server knows about
 * rather than the one the composer started with, and — new — that an undo the server refuses is
 * never reported as an undo. Get the first wrong and a failure loses a message with no trace of it
 * anywhere; get the second wrong and pressing undo, editing and sending again leaves a duplicate in
 * Drafts forever; get the third wrong and the app tells someone a sent message was called back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SendQueueTest {

    private val draft = ComposeDraft(accountKey = "s/1", identityId = "1", subject = "Hello")

    private class FakeSender(
        var savedAs: String? = "42",
        var failOnSave: String? = null,
        var failOnSubmit: String? = null,
        var mode: SubmissionMode = SubmissionMode.LOCAL_DELAY,
        var cancelAnswer: CancelOutcome = CancelOutcome.Cancelled,
    ) : DraftSender {
        val saved = mutableListOf<ComposeDraft>()
        val submitted = mutableListOf<Pair<ComposeDraft, SendHold?>>()
        val cancelled = mutableListOf<String>()

        override suspend fun save(draft: ComposeDraft): ComposeDraft {
            failOnSave?.let { error(it) }
            saved += draft

            return draft.copy(emailId = savedAs)
        }

        override suspend fun submit(draft: ComposeDraft, hold: SendHold?): Submitted {
            failOnSubmit?.let { error(it) }
            submitted += draft to hold

            return Submitted(
                submissionId = draft.emailId.orEmpty(),
                sendAt = "2026-08-07T06:00:00Z",
            )
        }

        override suspend fun cancel(accountKey: String, submissionId: String): CancelOutcome {
            cancelled += submissionId

            return cancelAnswer
        }

        /** What `EmailSubmission/get` answers, by submission id. Null is `notFound`. */
        var released: Map<String, SubmissionRecord> = emptyMap()

        /** Set to make the get throw, which is a server that could not be reached. */
        var failOnGet: String? = null

        val gets = mutableListOf<String>()

        override suspend fun releasedAt(
            accountKey: String,
            submissionId: String,
        ): SubmissionRecord? {
            gets += submissionId
            failOnGet?.let { error(it) }

            return released[submissionId]
        }

        override suspend fun submissionMode(accountKey: String) = mode
    }

    private fun TestScope.queueOf(
        sender: FakeSender,
        scheduled: ScheduledSends = scheduledSends(),
    ): SendQueue =
        SendQueue(
            sender,
            scheduled,
            ScheduledSendReconciler(scheduled, NoSubmissions),
            backgroundScope,
        )

    @Test
    fun `the draft is on the server before the window starts`() = runTest {
        // The whole reason the ordering is this way round: if the process dies
        // inside the undo window, the worst case has to be a draft in Drafts.
        val sender = FakeSender()
        val queue = queueOf(sender)

        queue.enqueue(draft)
        runCurrent()

        assertEquals(listOf(draft), sender.saved)
        assertTrue(sender.submitted.isEmpty())
        assertIs<SendState.Pending>(queue.state.value)
    }

    @Test
    fun `nothing is submitted until the window has elapsed`() = runTest {
        val sender = FakeSender()
        val queue = queueOf(sender)

        queue.enqueue(draft)
        advanceTimeBy(SendQueue.UNDO_WINDOW_MS - 1)
        runCurrent()

        assertTrue(sender.submitted.isEmpty())

        advanceTimeBy(2)
        runCurrent()

        assertEquals(1, sender.submitted.size)
        assertEquals(SendState.Sent, queue.state.value)
    }

    @Test
    fun `undo cancels the submission`() = runTest {
        val sender = FakeSender()
        val queue = queueOf(sender)

        queue.enqueue(draft)
        runCurrent()
        queue.undo()

        advanceTimeBy(SendQueue.UNDO_WINDOW_MS * 2)
        runCurrent()

        assertTrue(sender.submitted.isEmpty())
        assertEquals(SendState.Idle, queue.state.value)
    }

    @Test
    fun `undo returns the draft the server knows about, not the one that was handed in`() =
        runTest {
            // The composer reopens on what comes back. Handing back the original --
            // which has no emailId -- makes the next save create a *second* draft
            // and leaves the first in the list, which nothing ever cleans up.
            val sender = FakeSender(savedAs = "77")
            val queue = queueOf(sender)

            queue.enqueue(draft)
            runCurrent()

            assertEquals("77", queue.undo()?.emailId)
        }

    @Test
    fun `undo after the window is a no-op rather than an error`() = runTest {
        // By then the mail really has gone. Reporting a failure would suggest
        // there was something to recover.
        val sender = FakeSender()
        val queue = queueOf(sender)

        queue.enqueue(draft)
        advanceTimeBy(SendQueue.UNDO_WINDOW_MS + 1)
        runCurrent()

        assertNull(queue.undo())
        assertEquals(1, sender.submitted.size)
    }

    @Test
    fun `a save that fails is reported, never swallowed`() = runTest {
        // The composer has already closed by this point, so a silent failure is
        // a message the user believes they have sent.
        val sender = FakeSender(failOnSave = "The server is unreachable.")
        val queue = queueOf(sender)

        queue.enqueue(draft)
        runCurrent()

        val state = queue.state.value

        assertIs<SendState.Failed>(state)
        assertEquals("The server is unreachable.", state.message)
        assertEquals(draft, state.draft)
    }

    @Test
    fun `a submission that fails hands back the saved draft`() = runTest {
        // With its id, so reopening the composer edits the draft that is already
        // in Drafts rather than starting a duplicate beside it.
        val sender = FakeSender(savedAs = "9", failOnSubmit = "Rejected by the mail server.")
        val queue = queueOf(sender)

        queue.enqueue(draft)
        advanceTimeBy(SendQueue.UNDO_WINDOW_MS + 1)
        runCurrent()

        val state = queue.state.value

        assertIs<SendState.Failed>(state)
        assertEquals("9", state.draft.emailId)
    }

    @Test
    fun `two sends do not overlap`() = runTest {
        // Both would write to the same account's Email state, and the second
        // would come back `stateMismatch` for a reason the user cannot connect
        // to anything they did.
        val sender = FakeSender()
        val queue = queueOf(sender)

        queue.enqueue(draft)
        queue.enqueue(draft.copy(subject = "Second"))
        runCurrent()

        assertEquals(1, sender.saved.size)

        advanceTimeBy(SendQueue.UNDO_WINDOW_MS + 1)
        runCurrent()

        assertEquals(2, sender.saved.size)
        assertEquals("Hello", sender.submitted.single().first.subject)
    }

    // ------------------------------------------------------------ server hold

    @Test
    fun `a server that holds is submitted at once, with the window as HOLDFOR`() = runTest {
        // The point of the conversion. The submission is the server's business
        // from the first moment, so killing the app inside the window no longer
        // drops a message the user watched the composer close over -- and the
        // mail still leaves exactly six seconds late rather than six seconds
        // after the app happens to get around to it.
        val sender = FakeSender(mode = SubmissionMode.SERVER_HOLD)
        val queue = queueOf(sender)

        queue.enqueue(draft)
        runCurrent()

        val (_, hold) = sender.submitted.single()

        assertEquals(SendHold.For(SendQueue.UNDO_WINDOW_MS / 1_000), hold)
        assertIs<SendState.Pending>(queue.state.value)
    }

    @Test
    fun `undo of a held send is a cancel the server is asked for`() = runTest {
        val sender = FakeSender(savedAs = "55", mode = SubmissionMode.SERVER_HOLD)
        val queue = queueOf(sender)

        queue.enqueue(draft)
        runCurrent()

        assertEquals("55", queue.undo()?.emailId)
        assertEquals(listOf("55"), sender.cancelled)
        assertEquals(SendState.Idle, queue.state.value)
    }

    @Test
    fun `a cancel the server refuses is never reported as an undo`() = runTest {
        // The one sentence this app must not write. The mail has gone; handing
        // a draft back would reopen a composer over a message already delivered
        // and imply it never went.
        val sender =
            FakeSender(mode = SubmissionMode.SERVER_HOLD, cancelAnswer = CancelOutcome.AlreadySent)
        val queue = queueOf(sender)

        queue.enqueue(draft)
        runCurrent()

        assertNull(queue.undo())
        assertEquals(SendState.TooLate, queue.state.value)
    }

    // -------------------------------------------------------------- scheduled

    @Test
    fun `a scheduled send holds until the time asked for and is remembered`() = runTest {
        // The record is the *server's* sendAt rather than the instant handed in:
        // it is the only one the mail actually leaves at, and -- against a
        // server that does not report held submissions -- it is still the only
        // copy of that fact anywhere on the device.
        val sender = FakeSender(savedAs = "9", mode = SubmissionMode.SERVER_HOLD)
        val scheduled = scheduledSends()
        val queue = queueOf(sender, scheduled)

        queue.enqueue(draft, at = Instant.parse("2026-08-07T05:59:00Z"))
        runCurrent()

        val (_, hold) = sender.submitted.single()

        assertEquals(SendHold.Until("2026-08-07T05:59:00Z"), hold)

        val recorded = scheduled.all.first().single()

        assertEquals("9", recorded.emailId)
        assertEquals(Instant.parse("2026-08-07T06:00:00Z").toEpochMilli(), recorded.sendAt)
        assertIs<SendState.Scheduled>(queue.state.value)
    }

    // ------------------------------------------------------------- settle

    private val releaseTime = Instant.parse("2026-08-07T06:00:00Z").toEpochMilli()

    private fun scheduledRecord(sendAt: Long = releaseTime) =
        ScheduledSend(
            accountKey = "s/1",
            emailId = "9",
            identityId = "1",
            subject = "Hello",
            sendAt = sendAt,
        )

    private fun record(status: String) =
        SubmissionRecord(
            id = "9",
            emailId = "9",
            sendAt = Instant.ofEpochMilli(releaseTime).toString(),
            undoStatus = status,
        )

    @Test
    fun `settle retires a record the server says has gone`() = runTest {
        val sender = FakeSender().apply { released = mapOf("9" to record(SubmissionRecord.FINAL)) }
        val scheduled = scheduledSends()
        val queue = queueOf(sender, scheduled)

        scheduled.record(scheduledRecord())
        queue.settle(now = releaseTime + 1_000)

        assertTrue(scheduled.all.first().isEmpty())
    }

    @Test
    fun `settle retires a record the server says was cancelled`() = runTest {
        // A cancel made on another device, seen after the release time had
        // passed. Before the server reported `canceled` at all this arrived as
        // `notFound` and could only be waited out.
        val sender =
            FakeSender().apply { released = mapOf("9" to record(SubmissionRecord.CANCELED)) }
        val scheduled = scheduledSends()
        val queue = queueOf(sender, scheduled)

        scheduled.record(scheduledRecord())
        queue.settle(now = releaseTime + 1_000)

        assertTrue(scheduled.all.first().isEmpty())
    }

    @Test
    fun `settle keeps a record the server still reports as pending`() = runTest {
        // The regression the three-state answer makes possible, and the reason
        // `settle` no longer treats any non-null answer as "gone": a queue a
        // minute behind reports `pending` with a release time in the past, and
        // dropping the row there makes it vanish while the mail is still in the
        // queue -- the exact failure the grace was invented to avoid.
        val sender =
            FakeSender().apply { released = mapOf("9" to record(SubmissionRecord.PENDING)) }
        val scheduled = scheduledSends()
        val queue = queueOf(sender, scheduled)

        scheduled.record(scheduledRecord())
        queue.settle(now = releaseTime + 60_000)

        assertEquals(listOf("9"), scheduled.all.first().map { it.emailId })
    }

    @Test
    fun `settle keeps a record the server cannot be asked about, until the grace runs out`() =
        runTest {
            // Three things wearing one face: an older plMail holding it, a
            // server that was unreachable, and a send that failed. None of them
            // is a reason to make the row disappear at the release time, and all
            // of them are a reason not to still be drawing it half an hour on.
            val sender = FakeSender().apply { failOnGet = "unreachable" }
            val scheduled = scheduledSends()
            val queue = queueOf(sender, scheduled)

            scheduled.record(scheduledRecord())
            queue.settle(now = releaseTime + SendQueue.SETTLE_GRACE_MS - 1)

            assertEquals(1, scheduled.all.first().size)

            queue.settle(now = releaseTime + SendQueue.SETTLE_GRACE_MS + 1)

            assertTrue(scheduled.all.first().isEmpty())
        }

    @Test
    fun `settle leaves a record whose time has not come alone`() = runTest {
        // No request either. Polling a hold that is still hours away is a round
        // trip per app launch for an answer nobody can act on.
        val sender = FakeSender()
        val scheduled = scheduledSends()
        val queue = queueOf(sender, scheduled)

        scheduled.record(scheduledRecord())
        queue.settle(now = releaseTime - 60_000)

        assertEquals(1, scheduled.all.first().size)
        assertTrue(sender.gets.isEmpty())
    }

    @Test
    fun `a scheduled send is cancelled and forgotten whichever device made it`() = runTest {
        // The record reaching this method by way of a reconcile rather than a
        // send is the whole of "cancellable from another device": nothing in the
        // cancel path was ever device-bound, only the knowledge that there was
        // something to cancel.
        val sender = FakeSender()
        val scheduled = scheduledSends()
        val queue = queueOf(sender, scheduled)

        val elsewhere = scheduledRecord().copy(emailId = "900", subject = "")

        scheduled.record(elsewhere)

        assertEquals(CancelOutcome.Cancelled, queue.cancelScheduled(elsewhere))
        assertEquals(listOf("900"), sender.cancelled)
        assertTrue(scheduled.all.first().isEmpty())
    }

    @Test
    fun `a scheduled send the server says has already gone is forgotten too`() = runTest {
        // There is nothing left for this device to promise. Keeping the row
        // would offer a Cancel button over a message that has been delivered.
        val sender = FakeSender(cancelAnswer = CancelOutcome.AlreadySent)
        val scheduled = scheduledSends()
        val queue = queueOf(sender, scheduled)

        scheduled.record(scheduledRecord())

        assertEquals(CancelOutcome.AlreadySent, queue.cancelScheduled(scheduledRecord()))
        assertTrue(scheduled.all.first().isEmpty())
    }

    // ----------------------------------------------- the hold boundary itself

    @Test
    fun `which side of the hold boundary undo is on is decided locally`() = runTest {
        // **What is deterministic and what is not, written down rather than
        // asserted around.**
        //
        // Deterministic: which side of `UNDO_WINDOW_MS` the *client* thinks
        // it is on. `SendState.Pending` is set when the submission is
        // accepted and replaced by `Sent` when the local delay elapses, and
        // `undo()` returns null the moment the state is no longer `Pending`.
        // That is a virtual-clock fact and it is what this test pins.
        //
        // NOT deterministic, and deliberately untested: whether a cancel
        // that leaves the device at window-minus-one-millisecond arrives
        // before the server's own release. That race is decided by the
        // network and by the worker's poll, on two clocks that are not
        // synchronised, and the only honest client-side statement about it
        // is that the answer is read rather than assumed --
        // `CancelOutcome.AlreadySent` becomes `SendState.TooLate`, which
        // `a cancel the server refuses is never reported as an undo` above
        // pins. A test that drove real time to the boundary would be
        // asserting the speed of the test machine.
        val sender = FakeSender(savedAs = "55", mode = SubmissionMode.SERVER_HOLD)
        val queue = queueOf(sender)

        queue.enqueue(draft)
        advanceTimeBy(SendQueue.UNDO_WINDOW_MS - 1)
        runCurrent()

        assertIs<SendState.Pending>(queue.state.value)
        assertEquals("55", queue.undo()?.emailId)
        assertEquals(listOf("55"), sender.cancelled)
    }

    @Test
    fun `a held send left alone reports Sent exactly once the window is over`() = runTest {
        val sender = FakeSender(savedAs = "55", mode = SubmissionMode.SERVER_HOLD)
        val queue = queueOf(sender)

        queue.enqueue(draft)
        advanceTimeBy(SendQueue.UNDO_WINDOW_MS - 1)
        runCurrent()

        assertIs<SendState.Pending>(queue.state.value)

        advanceTimeBy(2)
        runCurrent()

        assertEquals(SendState.Sent, queue.state.value)
        // And no second cancel is possible: the window is closed on this side
        // before the server's hold expires on the other.
        assertNull(queue.undo())
        assertTrue(sender.cancelled.isEmpty())
    }
}
