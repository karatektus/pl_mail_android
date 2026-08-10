package de.plmail.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

/**
 * The ledger that stops one message being announced twice.
 *
 * Its contract is narrow and unusually strict: **announce exactly what `claim` returns**. Anything
 * looser and the caller has to decide, which is precisely the decision two concurrent syncs make
 * differently.
 */
class NotifiedMessageStoreTest {

    private val now = 1_760_000_000_000

    private val first = "https://nas.local/13#5"
    private val second = "https://nas.local/13#6"

    @Test
    fun `the first claim takes everything`() = runTest {
        val store = NotifiedMessageStore(EmittingDataStore())

        assertEquals(listOf(first, second), store.claim(listOf(first, second), now))
    }

    /**
     * The plain re-sync case: the same message offered again takes nothing.
     *
     * The cache's own `known` check normally catches this first. This is what catches it when that
     * check cannot — a re-paged account, a cache eviction, a server re-reporting a row as created.
     */
    @Test
    fun `a message already claimed is not claimed again`() = runTest {
        val store = NotifiedMessageStore(EmittingDataStore())

        store.claim(listOf(first), now)

        assertTrue(store.claim(listOf(first), now + 1_000).isEmpty())
    }

    /** A batch that is half old takes only the half nobody has been told about. */
    @Test
    fun `a mixed batch claims only what is new`() = runTest {
        val store = NotifiedMessageStore(EmittingDataStore())

        store.claim(listOf(first), now)

        assertEquals(listOf(second), store.claim(listOf(first, second), now + 1_000))
    }

    /**
     * **The case the ledger exists for.**
     *
     * A push arriving while the periodic worker is mid-hydration gives two coroutines that both
     * read the cache before either writes, and both honestly conclude the same message is new.
     * Exactly one of them may come back with the id.
     *
     * The double this runs against suspends *inside* the transform, which is what makes the test
     * worth having: it is the window a read-then-write implementation would interleave in, and
     * against one this assertion fails with two claims for one message. What holds it shut is that
     * the read, the decision and the write are one `edit` — and that DataStore serialises those per
     * file, which [SerialisingDataStore] reproduces rather than assumes.
     */
    @Test
    fun `two concurrent claims for the same message split it between them, never share it`() =
        runTest {
            val store = NotifiedMessageStore(SerialisingDataStore())

            val both =
                listOf(
                        async { store.claim(listOf(first), now) },
                        async { store.claim(listOf(first), now) },
                    )
                    .awaitAll()

            assertEquals(listOf(first), both.flatten())
        }

    /** The caller's own duplicate must not become two notifications either. */
    @Test
    fun `a duplicate inside one batch is claimed once`() = runTest {
        val store = NotifiedMessageStore(EmittingDataStore())

        assertEquals(listOf(first), store.claim(listOf(first, first), now))
    }

    /**
     * Retention, from the other end.
     *
     * A message the ledger has forgotten is claimable again — which is correct, and harmless: for
     * it to reach here at all the cache would have to have forgotten it too.
     */
    @Test
    fun `an entry older than the retention window is forgotten`() = runTest {
        val store = NotifiedMessageStore(EmittingDataStore())
        val eightDays = 8L * 24 * 60 * 60 * 1000

        store.claim(listOf(first), now)

        assertEquals(listOf(first), store.claim(listOf(first), now + eightDays))
    }

    /**
     * The cap, which is what keeps this file from growing with the mailbox.
     *
     * The preferences file is rewritten in full on every write and also holds the credential, so an
     * unbounded ledger would make every unrelated preference write proportional to how much mail
     * arrived this week. Newest survives: the oldest ids are the ones nothing will offer again.
     */
    @Test
    fun `the ledger stays bounded and keeps the newest`() = runTest {
        val store = NotifiedMessageStore(EmittingDataStore())

        // Past the 2,000 cap, each a millisecond newer than the last.
        repeat(2_100) { store.claim(listOf("https://nas.local/13#$it"), now + it) }

        // The very first is long evicted, so it is claimable again...
        assertEquals(
            listOf("https://nas.local/13#0"),
            store.claim(listOf("https://nas.local/13#0"), now + 3_000),
        )

        // ...while the most recent is still held.
        assertTrue(store.claim(listOf("https://nas.local/13#2099"), now + 3_000).isEmpty())
    }

    @Test
    fun `claiming nothing writes nothing`() = runTest {
        assertTrue(NotifiedMessageStore(EmittingDataStore()).claim(emptyList(), now).isEmpty())
    }
}

/**
 * A preferences store with real DataStore's concurrency shape: one writer at a time, and a genuine
 * suspension inside the transform.
 *
 * Separate from [EmittingDataStore] rather than folded into it. That one exists to emit on every
 * write so a `distinctUntilChanged` test cannot pass vacuously, and it is deliberately as simple as
 * that job needs. This one exists to *open* the window a racing implementation would fall through —
 * the `yield` is the whole point, because without it two coroutines on a single-threaded test
 * dispatcher never interleave and the concurrency test passes whatever the code does.
 */
private class SerialisingDataStore : DataStore<Preferences> {
    private val writing = Mutex()
    private var current: Preferences = emptyPreferences()
    private val emissions = MutableStateFlow(current)

    override val data: Flow<Preferences>
        get() = emissions

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences
    ): Preferences = writing.withLock {
        val read = current

        // Where a read-then-write implementation loses the race.
        yield()

        current = transform(read)
        emissions.value = current

        current
    }
}
