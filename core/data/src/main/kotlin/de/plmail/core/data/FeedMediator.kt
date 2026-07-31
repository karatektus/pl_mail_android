package de.plmail.core.data

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.room.withTransaction
import de.plmail.core.database.FeedCursorEntity
import de.plmail.core.database.FeedEntryEntity
import de.plmail.core.database.PlMailDatabase
import de.plmail.core.database.StoreKey
import de.plmail.core.database.ThreadEntity

/**
 * Fills the materialised feed table from the network, one page at a time.
 *
 * Paging reads the table; this is the only thing that writes it. The split is what makes a cold
 * launch instant — the list draws from rows already on disk while this catches up behind it — and
 * it is why the table exists at all rather than the list sorting every cached thread.
 *
 * The merge itself lives in [UnifiedFeed]. This is the adapter that gives it somewhere to write and
 * remembers where it got to.
 */
@OptIn(ExperimentalPagingApi::class)
class FeedMediator(
    private val feedId: String,
    private val database: PlMailDatabase,
    private val sources: List<FeedSource>,
    /** Reported so the list can name an account that is unreachable rather than showing nothing. */
    private val onFailures: (List<SourceFailure>) -> Unit = {},
    /**
     * Whether rows already in the table are an answer to *this* feed's question.
     *
     * True for a mailbox, where yesterday's inbox is still the inbox. False for a search, where the
     * table holds the previous query's results: showing those is not a stale answer but an answer
     * to a question nobody asked, and it would arrive instantly and look authoritative.
     */
    private val cachedRowsAnswerThis: Boolean = true,
) : RemoteMediator<Int, ThreadEntity>() {

    private val feed = UnifiedFeed(sources)
    private var restored = false

    /**
     * Cached rows are shown immediately and refreshed behind them — unless there are none.
     *
     * Skipping the initial refresh is what makes a cold launch instant: the server is frequently a
     * NAS that is asleep or a Tailscale node unreachable from this network, and blocking the first
     * frame on it turns "your mail, instantly" into a spinner. What is on disk is already correct
     * as of the last sync.
     *
     * But an empty table is not a synced empty inbox, it is a client that has never synced.
     * Skipping there leaves a freshly paired account showing nothing at all until the user scrolls
     * far enough to trigger an append — which, on an empty list, they cannot do.
     */
    override suspend fun initialize(): InitializeAction =
        if (!cachedRowsAnswerThis || database.feed().count(feedId) == 0)
            InitializeAction.LAUNCH_INITIAL_REFRESH
        else InitializeAction.SKIP_INITIAL_REFRESH

    override suspend fun load(
        loadType: LoadType,
        state: PagingState<Int, ThreadEntity>,
    ): MediatorResult {
        return try {
            when (loadType) {
                // The list is newest-first and only grows downward: everything
                // newer arrives through sync, not through paging upward.
                LoadType.PREPEND -> return MediatorResult.Success(endOfPaginationReached = true)

                LoadType.REFRESH -> restart()
                LoadType.APPEND -> ensureRestored()
            }

            val batch = feed.next(state.config.pageSize)
            if (batch.failures.isNotEmpty()) onFailures(batch.failures)

            database.withTransaction {
                if (loadType == LoadType.REFRESH) database.feed().clearFeed(feedId)

                database.feed().upsertEntries(batch.rows.map { it.toEntry(feedId) })
                feed.cursors.forEach { database.feed().upsertCursor(it.toEntity(feedId)) }
            }

            // Exhaustion comes from the merge, not from the row count: a batch
            // can be short because one account failed while others still have
            // pages left, and calling that the end would stop the list from
            // ever loading them.
            MediatorResult.Success(endOfPaginationReached = batch.isExhausted)
        } catch (failed: Exception) {
            // Only a failure no source could absorb reaches here -- UnifiedFeed
            // already turns a single unreachable account into a recorded
            // failure rather than a throw.
            MediatorResult.Error(failed)
        }
    }

    /** Drops every cursor so the next pull starts from the top. */
    private suspend fun restart() {
        sources.forEach { database.feed().clearCursors(it.accountKey) }
        feed.restore(sources.map { FeedCursor(accountKey = it.accountKey, atOrBefore = null) })
        restored = true
    }

    /**
     * Loads the persisted cursors once, on the first append after a cold start.
     *
     * Without this, an append following a process death would page from the top and rewrite rows
     * the user has already scrolled past — the list would appear to jump back to the newest mail as
     * soon as they reached the bottom.
     */
    private suspend fun ensureRestored() {
        if (restored) return

        feed.restore(
            sources.mapNotNull { source ->
                database.feed().cursor(feedId, source.accountKey)?.toCursor()
            }
        )

        restored = true
    }
}

private fun FeedRow.toEntry(feedId: String): FeedEntryEntity =
    FeedEntryEntity(
        // Keyed on the feed and the thread, so the same conversation appearing
        // in two lists is two rows and re-paging one list replaces its own row
        // rather than duplicating it.
        uid = "$feedId#${StoreKey.objectKey(accountKey, threadId)}",
        feedId = feedId,
        sortDate = sortDate,
        accountKey = accountKey,
        threadId = threadId,
        emailId = id,
    )

private fun FeedCursor.toEntity(feedId: String): FeedCursorEntity =
    FeedCursorEntity(
        uid = "$feedId#$accountKey",
        feedId = feedId,
        accountKey = accountKey,
        lastSortDate = atOrBefore,
        boundaryIds = emittedAtCursor.joinToString(","),
        isExhausted = isExhausted,
    )

private fun FeedCursorEntity.toCursor(): FeedCursor =
    FeedCursor(
        accountKey = accountKey,
        atOrBefore = lastSortDate,
        emittedAtCursor = boundaryIds.split(",").filter { it.isNotBlank() }.toSet(),
        isExhausted = isExhausted,
    )
