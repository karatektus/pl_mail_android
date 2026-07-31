package de.plmail.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The undo window, which the server deliberately does not provide.
 *
 * The two things worth guarding are both invisible when they go wrong: that the draft reaches the
 * server *before* the window rather than after it, and that undoing hands back the draft the server
 * knows about rather than the one the composer started with. Get the first wrong and a process
 * death inside six seconds loses a message with no trace of it anywhere; get the second wrong and
 * pressing undo, editing and sending again leaves a duplicate in Drafts forever.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SendQueueTest {

    private val draft = ComposeDraft(accountKey = "s/1", identityId = "1", subject = "Hello")

    private class FakeSender(
        var savedAs: String? = "42",
        var failOnSave: String? = null,
        var failOnSubmit: String? = null,
    ) : DraftSender {
        val saved = mutableListOf<ComposeDraft>()
        val submitted = mutableListOf<ComposeDraft>()

        override suspend fun save(draft: ComposeDraft): ComposeDraft {
            failOnSave?.let { error(it) }
            saved += draft

            return draft.copy(emailId = savedAs)
        }

        override suspend fun submit(draft: ComposeDraft) {
            failOnSubmit?.let { error(it) }
            submitted += draft
        }
    }

    @Test
    fun `the draft is on the server before the window starts`() = runTest {
        // The whole reason the ordering is this way round: if the process dies
        // inside the undo window, the worst case has to be a draft in Drafts.
        val sender = FakeSender()
        val queue = SendQueue(sender, backgroundScope)

        queue.enqueue(draft)
        runCurrent()

        assertEquals(listOf(draft), sender.saved)
        assertTrue(sender.submitted.isEmpty())
        assertIs<SendState.Pending>(queue.state.value)
    }

    @Test
    fun `nothing is submitted until the window has elapsed`() = runTest {
        val sender = FakeSender()
        val queue = SendQueue(sender, backgroundScope)

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
        val queue = SendQueue(sender, backgroundScope)

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
            val queue = SendQueue(sender, backgroundScope)

            queue.enqueue(draft)
            runCurrent()

            assertEquals("77", queue.undo()?.emailId)
        }

    @Test
    fun `undo after the window is a no-op rather than an error`() = runTest {
        // By then the mail really has gone. Reporting a failure would suggest
        // there was something to recover.
        val sender = FakeSender()
        val queue = SendQueue(sender, backgroundScope)

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
        val queue = SendQueue(sender, backgroundScope)

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
        val queue = SendQueue(sender, backgroundScope)

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
        val queue = SendQueue(sender, backgroundScope)

        queue.enqueue(draft)
        queue.enqueue(draft.copy(subject = "Second"))
        runCurrent()

        assertEquals(1, sender.saved.size)

        advanceTimeBy(SendQueue.UNDO_WINDOW_MS + 1)
        runCurrent()

        assertEquals(2, sender.saved.size)
        assertEquals("Hello", sender.submitted.single().subject)
    }
}
