package de.plmail.core.data

/**
 * One row of a merged list: the newest message of one conversation, in one account.
 *
 * [id] is the message the page was keyed on and is what boundary exclusion works with, because that
 * is what the server's query returns. [threadId] is what the row actually draws.
 */
data class FeedRow(
    val accountKey: String,
    val id: String,
    val threadId: String,
    val sortDate: Long,
)

/** A page of rows from one account, newest first. */
data class FeedPage(val rows: List<FeedRow>, val isExhausted: Boolean = false)

/**
 * One account's contribution to a feed.
 *
 * The contract is deliberately inclusive at the boundary: [atOrBefore] means `sortDate <=
 * atOrBefore`, **not** `<`. JMAP's `before` filter is a strict `<` at one-second granularity, so a
 * source asking `before: cursor` would silently drop every message that shares its second with the
 * last row emitted — a real case on a mailbox that receives a batch of mail at once, and one that
 * looks like random missing messages rather than a paging bug. Sources widen the window and the
 * feed hands back [alreadyEmitted] so they can subtract what has been seen.
 */
interface FeedSource {
    val accountKey: String

    suspend fun page(atOrBefore: Long?, alreadyEmitted: Set<String>, limit: Int): FeedPage
}

/** An account that could not be paged, so the UI can say which one rather than showing nothing. */
data class SourceFailure(val accountKey: String, val error: Throwable)

/**
 * What one pull produced.
 *
 * [failures] is not an error channel — the rows are still valid and still ordered. It exists so a
 * banner can name the account that is unreachable while the rest of the list keeps working.
 */
data class FeedBatch(
    val rows: List<FeedRow>,
    val failures: List<SourceFailure> = emptyList(),
    val isExhausted: Boolean = false,
)

/**
 * Merges several accounts into one list, newest first.
 *
 * plMail exposes one JMAP account per connected mailbox and has no cross-account query, so the
 * unified inbox — the product's default view — is this merge. Two rules make it correct, and both
 * are easy to get subtly wrong in ways that only show on a real mailbox.
 *
 * **Nothing is emitted until every source has a visible head or is known to be finished.** A merge
 * that emitted from whichever account answered first would put a slow account's newer message
 * *below* rows already drawn, and the list would then reorder itself as pages arrived. So every
 * source with an empty buffer is refilled before any row is chosen.
 *
 * **Cursors advance on emit, not on fetch.** A page is fetched in blocks but consumed one row at a
 * time; advancing when the page arrives means a process death mid-scroll resumes *after* rows that
 * were never shown, leaving a hole in the middle of someone's inbox. Advancing on emit means the
 * worst case is re-fetching a page that is already cached.
 *
 * A failing account is skipped, never fatal. One unreachable server must not blank a list that
 * three other accounts can still fill — that is the difference between "your NAS is rebooting" and
 * "your mail is gone".
 */
class UnifiedFeed(sources: List<FeedSource>, private val pageSize: Int = DEFAULT_PAGE_SIZE) {

    private val states = sources.map(::SourceState)

    /** Where each account has been paged to, for persisting across process death. */
    val cursors: List<FeedCursor>
        get() = states.map {
            FeedCursor(
                accountKey = it.source.accountKey,
                atOrBefore = it.cursor,
                emittedAtCursor = it.emittedAtCursor.toSet(),
                // Buffered rows have been fetched but never handed out,
                // and a resumed feed starts with an empty buffer. Calling
                // that "exhausted" would silently drop them: the resumed
                // feed would refuse to ask again and the list would simply
                // end early, in the middle of someone's mail.
                isExhausted = it.isExhausted && it.buffer.isEmpty(),
            )
        }

    /** Restores cursors saved by a previous process. Unknown accounts are ignored. */
    fun restore(saved: List<FeedCursor>) {
        val byAccount = saved.associateBy { it.accountKey }

        states.forEach { state ->
            byAccount[state.source.accountKey]?.let {
                state.cursor = it.atOrBefore
                state.emittedAtCursor.clear()
                state.emittedAtCursor += it.emittedAtCursor
                state.isExhausted = it.isExhausted
                state.buffer.clear()
            }
        }
    }

    /**
     * Pulls up to [limit] rows in date order across every account.
     *
     * Returns fewer only when every source is exhausted or failed; a short batch is therefore the
     * end of the list rather than a hint to ask again.
     */
    suspend fun next(limit: Int): FeedBatch {
        val rows = mutableListOf<FeedRow>()
        val failures = mutableListOf<SourceFailure>()

        while (rows.size < limit) {
            refill(failures)

            // The invariant, in one line: only sources that can no longer
            // produce a head are absent from `ready`, so the newest head among
            // them really is the newest row remaining anywhere.
            val ready = states.filter { it.buffer.isNotEmpty() }
            if (ready.isEmpty()) break

            val next = ready.minWith(NEWEST_FIRST)
            val row = next.buffer.removeAt(0)

            rows += row
            next.consume(row)
        }

        return FeedBatch(
            rows = rows,
            failures = failures,
            isExhausted = states.all { it.isFinished && it.buffer.isEmpty() },
        )
    }

    /**
     * Tops up every source that could still yield a head.
     *
     * A source that answers with nothing is exhausted: `Email/query` reports no `hasMore`, so an
     * empty page is the only signal the end has been reached.
     */
    private suspend fun refill(failures: MutableList<SourceFailure>) {
        states.forEach { state ->
            if (state.buffer.isNotEmpty() || state.isFinished) return@forEach

            try {
                // A copy: this set keeps mutating as rows are emitted, and a
                // source that held the reference -- a cache, a test double, a
                // request being assembled asynchronously -- would see it change
                // underneath it.
                val page = state.source.page(state.cursor, state.emittedAtCursor.toSet(), pageSize)

                state.buffer.addAll(page.rows)
                if (page.rows.isEmpty() || page.isExhausted) state.isExhausted = true
            } catch (unreachable: Exception) {
                // Recorded rather than thrown. The other accounts' rows are
                // still correct and still ordered, and a list that refuses to
                // draw because one server is rebooting is worse than a list
                // with a banner on it.
                state.failure = unreachable
                failures += SourceFailure(state.source.accountKey, unreachable)
            }
        }
    }

    private class SourceState(val source: FeedSource) {
        val buffer = mutableListOf<FeedRow>()
        val emittedAtCursor = mutableSetOf<String>()

        var cursor: Long? = null
        var isExhausted = false
        var failure: Throwable? = null

        val isFinished: Boolean
            get() = isExhausted || failure != null

        /**
         * Advances past a row that has actually been handed out.
         *
         * The set is reset rather than accumulated whenever the date moves, because it only ever
         * has to cover rows sharing the *current* second — keeping every id ever emitted would grow
         * without bound over a long scroll for no benefit.
         */
        fun consume(row: FeedRow) {
            if (row.sortDate != cursor) {
                cursor = row.sortDate
                emittedAtCursor.clear()
            }

            emittedAtCursor += row.id
        }
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 50

        /**
         * Newest first, with ties broken deterministically.
         *
         * The tie-break is not cosmetic. Messages sharing a second across two accounts are common
         * (a mailing list delivered to both), and without a total order the merge could emit them
         * in one order on first load and the other after a restart, which reads as the list
         * shuffling itself.
         */
        private val NEWEST_FIRST =
            compareByDescending<SourceState> { it.buffer.first().sortDate }
                .thenBy { it.buffer.first().accountKey }
                .thenBy { it.buffer.first().id }
    }
}

/** How far one account has been paged within one feed. */
data class FeedCursor(
    val accountKey: String,
    val atOrBefore: Long?,
    /**
     * Ids already handed out at exactly [atOrBefore].
     *
     * Persisted with the cursor because without them a resumed feed re-emits every row sharing that
     * second — duplicates at exactly the place the user was looking when the app was killed.
     */
    val emittedAtCursor: Set<String> = emptySet(),
    val isExhausted: Boolean = false,
)
