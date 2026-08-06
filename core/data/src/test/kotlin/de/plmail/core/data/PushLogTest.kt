package de.plmail.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import de.plmail.core.datastore.PushLogStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * The receiving half of the pair.
 *
 * The server records what it dispatched; this records what landed, and the two are compared against
 * each other. Everything asserted here is a property that comparison depends on: newest first, so
 * the last delivery is the first line; bounded, so a phone that has been running for a month is not
 * holding a month of entries; and **no mail content**, ever — what a push carries is a map of
 * account id to the types whose state token moved, and a subject line here would be a copy of
 * somebody's mail sitting in a diagnostics file for the next two hundred messages.
 */
class PushLogTest {

    private fun log() = PushLog(PushLogStore(FakeLogPreferences()))

    @Test
    fun `entries come back newest first`() = runTest {
        val log = log()

        log.record(ReceivedPush(at = 1, transport = "unifiedpush", type = "StateChange"))
        log.record(ReceivedPush(at = 2, transport = "fcm", type = "StateChange"))
        log.record(ReceivedPush(at = 3, transport = "stream", type = "StateChange"))

        assertEquals(listOf(3L, 2L, 1L), log.entries.first().map { it.at })
    }

    /**
     * Bounded at write time rather than at read time.
     *
     * Trimming when the screen draws would leave the whole history in a preference value the app
     * rewrites on every push, which on a busy mailbox is a growing read-modify-write on the same
     * file that holds the credential.
     */
    @Test
    fun `the log is bounded and drops the oldest`() = runTest {
        val log = log()

        repeat(PushLogStore.LIMIT + 20) { index ->
            log.record(ReceivedPush(at = index.toLong(), transport = "fcm", type = "StateChange"))
        }

        val entries = log.entries.first()

        assertEquals(PushLogStore.LIMIT, entries.size)
        assertEquals((PushLogStore.LIMIT + 19).toLong(), entries.first().at)
        assertEquals(20L, entries.last().at)
    }

    @Test
    fun `an entry keeps which accounts and types moved, and nothing else`() = runTest {
        val log = log()

        log.record(
            ReceivedPush(
                at = 5,
                transport = "fcm",
                type = "StateChange",
                changed = mapOf("7" to listOf("Email", "Thread")),
            )
        )

        val entry = log.entries.first().single()

        assertEquals(PushDelivery.FCM, entry.delivery)
        assertEquals(mapOf("7" to listOf("Email", "Thread")), entry.changed)
        assertEquals(null, entry.note)
    }

    /**
     * The transports stay distinguishable through a round trip.
     *
     * The whole value of the screen is that "arrived by push" and "arrived down a stream while the
     * app happened to be open" are different facts, and every other view in the app draws them
     * identically.
     */
    @Test
    fun `every transport survives being written and read`() = runTest {
        val log = log()

        PushDelivery.entries.forEach { delivery ->
            log.record(ReceivedPush(at = 1, transport = delivery.wire, type = "StateChange"))
        }

        val read = log.entries.first().mapNotNull { it.delivery }.toSet()

        assertEquals(PushDelivery.entries.toSet(), read)
    }

    /** A line from an older build, or a half-flushed write, is dropped rather than drawn. */
    @Test
    fun `an unreadable line is skipped rather than surfaced`() = runTest {
        val store = PushLogStore(FakeLogPreferences())
        val log = PushLog(store)

        store.append("this is not json")
        log.record(ReceivedPush(at = 9, transport = "fcm", type = "StateChange"))

        val entries = log.entries.first()

        assertEquals(1, entries.size)
        assertEquals(9L, entries.single().at)
    }

    @Test
    fun `clearing empties it`() = runTest {
        val log = log()

        log.record(ReceivedPush(at = 1, transport = "fcm", type = "StateChange"))
        log.clear()

        assertTrue(log.entries.first().isEmpty())
    }
}

private class FakeLogPreferences : DataStore<Preferences> {
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
