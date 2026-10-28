package de.plmail.feature.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The draft a discard used to leave behind.
 *
 * The composer's first save creates the draft on the server, and the composer learns its id only
 * when that request comes back. Discard, close and send all cancelled the autosave and then read
 * the id — so a first save still in flight was cut off client-side while the server went ahead and
 * created the draft, and they read no id at all: nothing went to Trash, or a second copy was saved,
 * or the message was sent as a new one. The created draft stayed in Drafts with nothing on the
 * phone that knew of it. [SaveGate] is what makes them wait for that answer instead.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SaveGateTest {

    @Test
    fun `a save in flight finishes even when its caller is cancelled`() = runTest {
        val gate = SaveGate()
        val server = CompletableDeferred<String>()
        var savedId: String? = null

        val autosave = launch { gate.save { savedId = server.await() } }
        runCurrent()

        // The composer closing: the autosave is cancelled while its request is
        // out. Before, this dropped the answer, and the id with it.
        autosave.cancel()
        server.complete("42")
        runCurrent()

        assertEquals("42", savedId)
    }

    @Test
    fun `a discard reads the draft only after the save in flight came back`() = runTest {
        val gate = SaveGate()
        val server = CompletableDeferred<String>()
        var draftId: String? = null

        val autosave = launch { gate.save { draftId = server.await() } }
        runCurrent()
        autosave.cancel()

        val discarded = async { gate.afterSaves { draftId } }
        runCurrent()

        // Still waiting: reading now would find no id and move nothing to Trash.
        assertTrue(discarded.isActive)

        server.complete("42")

        assertEquals("42", discarded.await())
    }

    @Test
    fun `with no save in flight nothing waits`() = runTest {
        val gate = SaveGate()

        assertEquals("read", gate.afterSaves { "read" })
    }
}
