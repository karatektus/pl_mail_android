package de.plmail.core.data

import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The merge, and the four ways it goes wrong on a real mailbox.
 *
 * Each of these is a bug that looks like something else when it happens: rows appearing above ones
 * already drawn reads as the list shuffling itself; a dropped second reads as random missing
 * messages; a hole after a restart reads as mail that never arrived; and one unreachable NAS
 * blanking the whole list reads as data loss.
 */
class UnifiedFeedTest {

    private val second = 1_000L

    @Test
    fun `rows come back newest first across accounts`() = runTest {
        val work = source("work", row("w1", 5 * second), row("w2", 3 * second))
        val home = source("home", row("h1", 4 * second), row("h2", 2 * second))

        val batch = UnifiedFeed(listOf(work, home)).next(10)

        assertEquals(listOf("w1", "h1", "w2", "h2"), batch.rows.map { it.id })
        assertTrue(batch.isExhausted)
    }

    /**
     * The invariant the merge exists for.
     *
     * The slow account holds the newest message. A merge that emitted from whichever source
     * answered first would draw the fast account's older rows and then have to insert a newer one
     * above them.
     */
    @Test
    fun `nothing is emitted before every account has shown its head`() = runTest {
        val order = mutableListOf<String>()
        val fast = recordingSource("fast", order, row("f1", 1 * second))
        val slow = recordingSource("slow", order, row("s1", 9 * second))

        val batch = UnifiedFeed(listOf(fast, slow)).next(1)

        assertEquals(listOf("s1"), batch.rows.map { it.id })
        assertEquals(
            setOf("fast", "slow"),
            order.toSet(),
            "both accounts must be asked before the first row is chosen",
        )
    }

    /**
     * `before` is a strict `<` at one-second granularity.
     *
     * Three messages share a second across a page boundary. A feed that paged with `before: cursor`
     * would drop the two it had not yet emitted, and they would simply never appear.
     */
    @Test
    fun `messages sharing a second across a page boundary are not dropped`() = runTest {
        val account =
            PagingSource(
                "work",
                listOf(
                    row("a", 5 * second),
                    row("b", 4 * second),
                    row("c", 4 * second),
                    row("d", 4 * second),
                    row("e", 1 * second),
                ),
            )

        val feed = UnifiedFeed(listOf(account), pageSize = 2)
        val all = mutableListOf<String>()
        repeat(5) { all += feed.next(1).rows.map { it.id } }

        assertEquals(listOf("a", "b", "c", "d", "e"), all)
    }

    @Test
    fun `the boundary window is asked for inclusively and excludes what was emitted`() = runTest {
        val account = PagingSource("work", listOf(row("a", 4 * second), row("b", 4 * second)))
        val feed = UnifiedFeed(listOf(account), pageSize = 1)

        feed.next(1)
        feed.next(1)

        // Second call: same instant, and the row already handed out named so
        // the source can subtract it.
        val secondAsk = account.asks[1]
        assertEquals(4 * second, secondAsk.atOrBefore)
        assertEquals(setOf("a"), secondAsk.alreadyEmitted)
    }

    /**
     * Cursors advance on emit, not on fetch.
     *
     * A page holds five rows; only two are handed out before the process dies. Resuming from a
     * cursor that had advanced to the end of the *page* would skip the three that were never shown.
     */
    @Test
    fun `a feed resumes where the rows were emitted, not where the page ended`() = runTest {
        val rows = (1..5).map { row("m$it", (10 - it) * second) }

        val first = UnifiedFeed(listOf(PagingSource("work", rows)), pageSize = 5)
        val shown = first.next(2).rows.map { it.id }
        assertEquals(listOf("m1", "m2"), shown)

        // Process death: only the cursors survive.
        val resumed = UnifiedFeed(listOf(PagingSource("work", rows)), pageSize = 5)
        resumed.restore(first.cursors)

        assertEquals(listOf("m3", "m4", "m5"), resumed.next(10).rows.map { it.id })
    }

    @Test
    fun `resuming does not re-emit rows sharing the cursor's second`() = runTest {
        val rows = listOf(row("a", 4 * second), row("b", 4 * second), row("c", 4 * second))

        val first = UnifiedFeed(listOf(PagingSource("work", rows)), pageSize = 3)
        assertEquals(listOf("a", "b"), first.next(2).rows.map { it.id })

        val resumed = UnifiedFeed(listOf(PagingSource("work", rows)), pageSize = 3)
        resumed.restore(first.cursors)

        assertEquals(listOf("c"), resumed.next(10).rows.map { it.id })
    }

    /**
     * One failing account must never blank the list.
     *
     * "Your NAS is rebooting" and "your mail is gone" have to look different.
     */
    @Test
    fun `a failing account is named, and the others keep working`() = runTest {
        val working = source("work", row("w1", 5 * second), row("w2", 3 * second))
        val broken =
            object : FeedSource {
                override val accountKey = "broken"

                override suspend fun page(
                    atOrBefore: Long?,
                    alreadyEmitted: Set<String>,
                    limit: Int,
                ): FeedPage = throw IOException("nas.local is not answering")
            }

        val batch = UnifiedFeed(listOf(working, broken)).next(10)

        assertEquals(listOf("w1", "w2"), batch.rows.map { it.id })
        assertEquals(listOf("broken"), batch.failures.map { it.accountKey })
        assertTrue(batch.failures.single().error is IOException)
    }

    @Test
    fun `a failing account is not retried on every row`() = runTest {
        var attempts = 0
        val broken =
            object : FeedSource {
                override val accountKey = "broken"

                override suspend fun page(
                    atOrBefore: Long?,
                    alreadyEmitted: Set<String>,
                    limit: Int,
                ): FeedPage {
                    attempts++
                    throw IOException("down")
                }
            }

        UnifiedFeed(listOf(source("work", row("w1", 2 * second)), broken)).next(10)

        // Otherwise a dead server is hammered once per emitted row, which is
        // exactly the busy-loop this product must not do to someone's Pi.
        assertEquals(1, attempts)
    }

    @Test
    fun `an empty account does not stall the feed`() = runTest {
        val empty = source("empty")
        val work = source("work", row("w1", 5 * second))

        val batch = UnifiedFeed(listOf(empty, work)).next(10)

        assertEquals(listOf("w1"), batch.rows.map { it.id })
        assertTrue(batch.isExhausted)
    }

    @Test
    fun `every account empty is an exhausted, empty feed rather than a hang`() = runTest {
        val batch = UnifiedFeed(listOf(source("a"), source("b"))).next(10)

        assertTrue(batch.rows.isEmpty())
        assertTrue(batch.isExhausted)
        assertTrue(batch.failures.isEmpty())
    }

    /**
     * A total order, so the same mail merges the same way twice.
     *
     * A message delivered to two accounts in the same second is ordinary — a mailing list. Without
     * a deterministic tie-break the two could swap places between loads, which reads as the list
     * shuffling itself.
     */
    @Test
    fun `ties across accounts break deterministically`() = runTest {
        fun build() =
            UnifiedFeed(
                listOf(
                    source("zulu", row("z", 4 * second)),
                    source("alpha", row("a", 4 * second)),
                )
            )

        val first = build().next(10).rows.map { it.id }
        val again = build().next(10).rows.map { it.id }

        assertEquals(first, again)
        assertEquals(listOf("a", "z"), first, "ties order by account, then id")
    }

    @Test
    fun `a partial batch means the end rather than more to come`() = runTest {
        val feed = UnifiedFeed(listOf(source("work", row("w1", 1 * second))))

        val batch = feed.next(10)

        assertEquals(1, batch.rows.size)
        assertTrue(batch.isExhausted)
        assertTrue(feed.next(10).rows.isEmpty())
        assertFalse(feed.cursors.single().atOrBefore == null)
    }

    // -- helpers -----------------------------------------------------------

    private fun row(id: String, sortDate: Long, account: String = "work") =
        FeedRow(accountKey = account, id = id, threadId = "t-$id", sortDate = sortDate)

    /** Answers everything at once, then reports exhaustion. */
    /**
     * Cancellation is not an unreachable server.
     *
     * `CancellationException` is an `Exception` in Kotlin, so the arm that absorbs a failing
     * account absorbed this one too — and the app then told the user its own server could not be
     * reached because they had tapped a different label. The page in flight is cancelled every time
     * the list switches, so this was not an edge case; it was one tap away at all times.
     */
    @Test
    fun `a cancelled page is not reported as a failed account`() = runTest {
        val cancelled =
            object : FeedSource {
                override val accountKey = "work"

                override suspend fun page(
                    atOrBefore: Long?,
                    alreadyEmitted: Set<String>,
                    limit: Int,
                ): FeedPage = throw CancellationException("the list moved on")
            }

        assertFailsWith<CancellationException> { UnifiedFeed(listOf(cancelled)).next(10) }
    }

    private fun source(account: String, vararg rows: FeedRow): FeedSource =
        PagingSource(account, rows.toList(), pageEverything = true)

    private fun recordingSource(
        account: String,
        order: MutableList<String>,
        vararg rows: FeedRow,
    ): FeedSource =
        object : FeedSource {
            override val accountKey = account

            override suspend fun page(
                atOrBefore: Long?,
                alreadyEmitted: Set<String>,
                limit: Int,
            ): FeedPage {
                order += account
                return FeedPage(rows.filter { it.matches(atOrBefore, alreadyEmitted) })
            }
        }

    private data class Ask(val atOrBefore: Long?, val alreadyEmitted: Set<String>)

    /**
     * A source that pages honestly.
     *
     * It applies the inclusive-boundary contract itself, so a feed that asked with the wrong window
     * loses rows here exactly as it would against the server.
     */
    private class PagingSource(
        override val accountKey: String,
        private val all: List<FeedRow>,
        private val pageEverything: Boolean = false,
    ) : FeedSource {
        val asks = mutableListOf<Ask>()

        override suspend fun page(
            atOrBefore: Long?,
            alreadyEmitted: Set<String>,
            limit: Int,
        ): FeedPage {
            asks += Ask(atOrBefore, alreadyEmitted)

            val matching =
                all.filter { it.copy(accountKey = accountKey).matches(atOrBefore, alreadyEmitted) }
                    .sortedWith(compareByDescending<FeedRow> { it.sortDate }.thenBy { it.id })
                    .map { it.copy(accountKey = accountKey) }

            val taken = if (pageEverything) matching else matching.take(limit)

            return FeedPage(rows = taken, isExhausted = taken.size == matching.size)
        }
    }
}

private fun FeedRow.matches(atOrBefore: Long?, alreadyEmitted: Set<String>): Boolean =
    (atOrBefore == null || sortDate <= atOrBefore) && id !in alreadyEmitted
