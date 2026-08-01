package de.plmail.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import de.plmail.core.datastore.OutboxStore
import de.plmail.jmap.protocol.JmapError
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * The queue that exists because every action is applied locally first.
 *
 * The rule the whole thing turns on is that a **transport failure queues and a server rejection
 * does not**, and it is the kind of distinction that is obvious while writing it and invisible six
 * months later — at which point somebody simplifies the two catch arms into one and the app
 * develops a queue that can never empty, retrying an `Email/set` the server has already refused,
 * once every fifteen minutes, forever, with a banner on the mail list saying so.
 *
 * Ordering is the other one. Star-then-unstar and unstar-then-star are different end states, so a
 * drain that reordered or grouped would settle on whichever it happened to send last.
 */
class OutboxTest {

    private val targets = listOf(ActionTarget("https://nas.local/1", "t1"))

    /** No labels, which is what makes the SetLabel case below drop rather than send. */
    private fun outbox(
        store: OutboxStore = OutboxStore(FakePreferences()),
        labels: Map<String, Label> = emptyMap(),
    ) = Outbox(store) { labels }

    @Test
    fun `an action that could not be sent is kept`() = runTest {
        val outbox = outbox()

        assertTrue(outbox.enqueue(MailAction.Archive, targets, at = 1_000))

        assertEquals(1, outbox.state.first().pending)
        assertEquals(1_000, outbox.state.first().oldestQueuedAt)
    }

    @Test
    fun `draining sends what is waiting, in the order it was made`() = runTest {
        val outbox = outbox()

        outbox.enqueue(MailAction.Star(flagged = true), targets, at = 1)
        outbox.enqueue(MailAction.Star(flagged = false), targets, at = 2)
        outbox.enqueue(MailAction.Archive, targets, at = 3)

        val sent = mutableListOf<MailAction>()
        val result = outbox.drain { action, _ -> sent += action }

        assertEquals(3, result.sent)
        assertEquals(0, result.remaining)
        assertEquals(
            // Not a set, and not deduplicated: the two stars are opposite
            // changes to the same conversation and the end state is whichever
            // went last.
            listOf(MailAction.Star(true), MailAction.Star(false), MailAction.Archive),
            sent,
        )
        assertTrue(outbox.state.first().isEmpty)
    }

    /**
     * The case that decides whether the queue is a queue or a shuffle.
     *
     * Two changes waiting, the first one fails to send. Everything from the failure onward has to
     * stay, *including the one that failed* — it was never sent, and dropping it would lose a
     * change the user can still see on their phone. And the second must not be attempted, because
     * replaying a later change before an earlier one is the same ordering bug from the other end.
     */
    @Test
    fun `a drain that hits the network again stops there and keeps the rest`() = runTest {
        val outbox = outbox()

        outbox.enqueue(MailAction.Archive, targets, at = 1)
        outbox.enqueue(MailAction.Trash, targets, at = 2)

        val attempted = mutableListOf<MailAction>()

        val result = outbox.drain { action, _ ->
            attempted += action
            throw IOException("no route to host")
        }

        assertEquals(0, result.sent)
        assertEquals(2, result.remaining)
        assertEquals<List<MailAction>>(
            listOf(MailAction.Archive),
            attempted,
            "the second must not be attempted",
        )
        assertEquals(2, outbox.state.first().pending)
    }

    @Test
    fun `a JMAP-level unreachable stops the drain too`() = runTest {
        val outbox = outbox()

        outbox.enqueue(MailAction.Archive, targets, at = 1)

        val result = outbox.drain { _, _ -> throw JmapError.Unreachable("nas.local", null) }

        assertEquals(1, result.remaining)
        assertEquals(1, outbox.state.first().pending)
    }

    /**
     * A refusal is an answer, and an answer is not a reason to ask again.
     *
     * This is the single most important assertion in the file. Without it the obvious "just retry
     * everything that failed" produces a queue that cannot empty, a banner on the mail list that
     * never goes away, and a request to somebody's Raspberry Pi every fifteen minutes for a change
     * it has already declined.
     */
    @Test
    fun `a change the server refused is dropped rather than retried forever`() = runTest {
        val outbox = outbox()

        outbox.enqueue(MailAction.Archive, targets, at = 1)
        outbox.enqueue(MailAction.Trash, targets, at = 2)

        val attempted = mutableListOf<MailAction>()

        val result = outbox.drain { action, _ ->
            attempted += action
            if (action == MailAction.Archive) error("notFound")
        }

        assertEquals(2, result.sent)
        assertEquals(0, result.remaining)
        // The one after it still went: a refusal is about that change, not
        // about the connection, so there is no reason to stop.
        assertEquals(listOf(MailAction.Archive, MailAction.Trash), attempted)
        assertTrue(outbox.state.first().isEmpty)
    }

    /**
     * A label that has gone since the change was made.
     *
     * The stored form carries the label *key* rather than the [Label] itself, because a Label's
     * bindings are mailbox ids and mailbox ids are cache — re-synced, renumbered, or deleted
     * between the tap and the send. Dropped rather than retried, for the same reason a refusal is:
     * there is nothing to apply it to, and a queue that cannot empty is a banner that never goes
     * away.
     */
    @Test
    fun `a queued label whose label no longer exists is dropped`() = runTest {
        val outbox = outbox()
        val label =
            Label(
                key = "gone",
                name = "Steuer",
                path = "Steuer",
                role = null,
                unreadThreads = 0,
                totalThreads = 0,
                mayRename = true,
                mayDelete = true,
                bindings = emptyList(),
            )

        outbox.enqueue(MailAction.SetLabel(label, applied = true), targets, at = 1)

        val attempted = mutableListOf<MailAction>()
        val result = outbox.drain { action, _ -> attempted += action }

        assertEquals(1, result.sent)
        assertTrue(attempted.isEmpty(), "nothing should have been sent")
        assertTrue(outbox.state.first().isEmpty)
    }

    @Test
    fun `a queue written by a build that is no longer installed clears rather than crashes`() =
        runTest {
            val preferences = FakePreferences()
            val store = OutboxStore(preferences)

            store.update { "this is not json" }

            assertEquals(0, outbox(store).state.first().pending)
        }
}

/**
 * A DataStore that keeps its preferences in memory.
 *
 * A `MutableStateFlow` here rather than the emit-on-every-write fake `:core:datastore` uses,
 * because nothing in this file is about emission counts — what is being tested is what comes back
 * out, and conflating equal values is exactly what makes that read cleanly.
 */
private class FakePreferences : DataStore<Preferences> {
    private val current = MutableStateFlow(emptyPreferences())

    override val data: Flow<Preferences>
        get() = current

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences
    ): Preferences {
        current.value = transform(current.value)

        return current.value
    }
}
