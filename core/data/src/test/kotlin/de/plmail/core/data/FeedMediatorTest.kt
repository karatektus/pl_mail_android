package de.plmail.core.data

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingConfig
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import de.plmail.core.database.PlMailDatabase
import de.plmail.core.database.ThreadEntity
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a refresh is allowed to destroy, and what it must not.
 *
 * Every test here is a regression. Nothing called `REFRESH` after the first load until
 * pull-to-refresh existed, so the mediator's refresh arm had never been run against a server that
 * did not answer — and it turned out to throw away, in order: how deep *every other list* had been
 * paged, and then the rows of the list being refreshed, on a pull made in a tunnel. Both are
 * unrecoverable from the device's point of view; the second is a user emptying their own inbox by
 * tugging on it.
 *
 * The fourth is the initialisation rule that made `SyncResult.NeedsRepage` durable at last. Before
 * it the sync would work out that an account could no longer be described incrementally, throw that
 * conclusion away, and the list would go on drawing whatever it happened to hold — forever, because
 * a full feed table meant "skip the refresh" and PREPEND has always been a no-op.
 */
@OptIn(ExperimentalPagingApi::class)
@RunWith(RobolectricTestRunner::class)
// sdk = 36, for the reason `core/ui`'s screenshot tests give.
@Config(sdk = [36])
class FeedMediatorTest {

    private lateinit var database: PlMailDatabase

    private val inbox = Feed.UNIFIED_INBOX.id
    private val promotions = MailCategory.PROMOTIONS.feedId

    @Before
    fun open() {
        database = inMemoryDatabase()
    }

    @After
    fun close() {
        database.close()
    }

    /**
     * A full table is not a describable one.
     *
     * "No delta cursor" is exactly "this device can no longer describe this account incrementally",
     * which is exactly "re-page" — and the account row is already where that fact lives, which is
     * what makes the decision survive the process that made it.
     */
    @Test
    fun `a source account with no delta cursor is re-paged even though the feed has rows`() =
        runTest {
            database.seedAccount(emailState = null)
            database.seedEntry(inbox, "t1")

            assertEquals(
                RemoteMediator.InitializeAction.LAUNCH_INITIAL_REFRESH,
                mediator(EmptySource).initialize(),
            )
        }

    /** And the ordinary case it must not swallow: cached rows are shown, not fetched again. */
    @Test
    fun `an account that can still be described incrementally skips the initial refresh`() =
        runTest {
            database.seedAccount(emailState = "s5")
            database.seedEntry(inbox, "t1")

            assertEquals(
                RemoteMediator.InitializeAction.SKIP_INITIAL_REFRESH,
                mediator(EmptySource).initialize(),
            )
        }

    /**
     * The wipe-everything bug, as a test.
     *
     * `clearCursors` was scoped to the *account*, so refreshing the inbox deleted the cursors of
     * every label and category list that account contributes to. The next append in any of them
     * started from the top and rewrote rows the user had already scrolled past — a list that jumps
     * back to the newest mail the moment somebody reaches the bottom of it, caused by a gesture
     * made on a different screen.
     */
    @Test
    fun `a refresh clears its own feed's cursors and leaves every other list alone`() = runTest {
        database.seedAccount()
        database.seedCursor(inbox, lastSortDate = 4_000)
        database.seedCursor(promotions, lastSortDate = 2_000)

        val result = mediator(OneRow(sortDate = 9_000)).load(LoadType.REFRESH, state())

        assertTrue(result is RemoteMediator.MediatorResult.Success)

        val survived = database.feed().cursor(promotions, testAccountKey)
        assertNotNull(survived, "refreshing the inbox must not re-page the tabs")
        assertEquals(2_000, survived.lastSortDate)

        assertEquals(
            9_000,
            database.feed().cursor(inbox, testAccountKey)?.lastSortDate,
            "its own cursor is the one this pull just wrote",
        )
    }

    /**
     * Pull to refresh in a tunnel.
     *
     * `feed.next` does **not** throw when a source fails — it records the failure and returns the
     * rows it could get — so an unreachable server arrives here with an empty batch, and a refresh
     * that cleared unconditionally would then commit that emptiness over the top of the list. The
     * user's own gesture, deleting their own inbox, on a phone that is behaving correctly in every
     * other respect.
     *
     * Keeping the rows on *any* failure rather than per account is deliberate. A partial refresh
     * leaves a conversation archived elsewhere on screen until a clean one, which is a stale list;
     * the alternative is a blank one, and only one of those two can be mistaken for lost mail.
     *
     * The rows are asserted and the cursor is not, and that is a statement about what survives
     * rather than an omission: `restart()` has already reset the in-memory cursors before the pull
     * is attempted, and `load` writes them back whether or not it answered — so a failed refresh
     * does still forget how deep the list had been paged. The cost of that is re-fetching pages the
     * cache already holds on the next append. The cost of clearing the rows is somebody's inbox.
     */
    @Test
    fun `a refresh whose source could not be reached keeps the rows already on disk`() = runTest {
        database.seedAccount()
        database.seedCursor(inbox, lastSortDate = 4_000)
        database.seedEntry(inbox, "t1")
        database.seedEntry(inbox, "t2")

        val failures = mutableListOf<SourceFailure>()
        val result =
            mediator(Unreachable, onFailures = { failures += it }).load(LoadType.REFRESH, state())

        assertTrue(result is RemoteMediator.MediatorResult.Success, "one dead account is not fatal")
        assertEquals(1, failures.size, "and the list is told which account, so it can say so")
        assertEquals(2, database.feed().count(inbox), "the mail is still there")
    }

    /**
     * A refresh that throws outright never reaches the clear at all.
     *
     * The only exception `UnifiedFeed` lets past is a cancellation, and a cancellation is the
     * ordinary consequence of leaving one list for another while its page is in flight. That is why
     * the cursor clear had to move out of `restart()` and into the load transaction: `restart()`
     * runs *before* the pull, so clearing there meant tapping a label in the drawer at the wrong
     * moment threw away how deep the inbox had been paged, for a refresh that never happened.
     */
    @Test
    fun `a refresh that is cancelled mid-pull leaves the persisted cursors intact`() = runTest {
        database.seedAccount()
        database.seedCursor(inbox, lastSortDate = 4_000)
        database.seedEntry(inbox, "t1")

        val result = mediator(Cancelling).load(LoadType.REFRESH, state())

        assertTrue(result is RemoteMediator.MediatorResult.Error)
        assertEquals(4_000, database.feed().cursor(inbox, testAccountKey)?.lastSortDate)
        assertEquals(1, database.feed().count(inbox))
    }

    // -- helpers -----------------------------------------------------------

    private fun mediator(
        source: FeedSource,
        onFailures: (List<SourceFailure>) -> Unit = {},
    ): FeedMediator =
        FeedMediator(
            feedId = inbox,
            database = database,
            sources = listOf(source),
            onFailures = onFailures,
        )

    private fun state(): PagingState<Int, ThreadEntity> =
        PagingState(
            pages = emptyList(),
            anchorPosition = null,
            config = PagingConfig(pageSize = 25),
            leadingPlaceholderCount = 0,
        )

    /** An account with nothing left to give — the end of a list, not a failure. */
    private object EmptySource : FeedSource {
        override val accountKey = testAccountKey

        override suspend fun page(
            atOrBefore: Long?,
            alreadyEmitted: Set<String>,
            limit: Int,
        ): FeedPage = FeedPage(rows = emptyList(), isExhausted = true)
    }

    /** One conversation, then the end. Enough to make a refresh commit something. */
    private class OneRow(private val sortDate: Long) : FeedSource {
        override val accountKey = testAccountKey

        private var served = false

        override suspend fun page(
            atOrBefore: Long?,
            alreadyEmitted: Set<String>,
            limit: Int,
        ): FeedPage =
            if (served) FeedPage(emptyList(), isExhausted = true)
            else {
                served = true

                FeedPage(
                    listOf(
                        FeedRow(
                            accountKey = testAccountKey,
                            id = "e1",
                            threadId = "t9",
                            sortDate = sortDate,
                        )
                    )
                )
            }
    }

    /** The NAS that is asleep. Recorded as a failure by the merge, never thrown. */
    private object Unreachable : FeedSource {
        override val accountKey = testAccountKey

        override suspend fun page(
            atOrBefore: Long?,
            alreadyEmitted: Set<String>,
            limit: Int,
        ): FeedPage = throw IOException("no route to host")
    }

    /** Leaving this list for another one, which cancels the page in flight. */
    private object Cancelling : FeedSource {
        override val accountKey = testAccountKey

        override suspend fun page(
            atOrBefore: Long?,
            alreadyEmitted: Set<String>,
            limit: Int,
        ): FeedPage = throw CancellationException("the list was left")
    }
}
