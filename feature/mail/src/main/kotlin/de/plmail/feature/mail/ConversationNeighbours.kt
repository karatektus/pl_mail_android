package de.plmail.feature.mail

import de.plmail.core.database.ThreadEntity

/**
 * Where the open conversation sits in the list it was opened from, [at], and the rows beside it:
 * [previous] above, [next] below, each null at its end of what is loaded.
 *
 * A data class so that a `derivedStateOf` yielding it only invalidates the reader when one of the
 * three indices moves (#34), not on every page Paging loads or row it rewrites.
 */
internal data class Neighbours(val at: Int, val previous: Int?, val next: Int?)

/**
 * The open conversation's [Neighbours] among [rows], or null when it is not there — a list switched
 * underneath, a notification for mail in another label. There is nowhere to go then, as there is
 * past either end.
 *
 * "Next" is the row below, which in every list here is the older conversation — the order the
 * reader works through an inbox in. Matched on the account and the thread id rather than on the
 * row's uid, because a conversation opened from a notification arrives with only those two, and if
 * it happens to be in the list the user is looking at, swiping from it should work too.
 *
 * [rows] is what Paging has loaded so far, placeholders as null. An index that lands on a
 * placeholder is still returned; reading it through `LazyPagingItems` is what asks Paging for it.
 * The rows are compared where they stand, so a walk over a few hundred of them allocates nothing.
 */
internal fun neighboursOf(
    rows: List<ThreadEntity?>,
    accountKey: String,
    threadId: String,
): Neighbours? {
    val at = rows.indexOfFirst { it?.accountKey == accountKey && it.threadId == threadId }
    if (at < 0) return null

    return Neighbours(
        at = at,
        previous = neighbourIndex(at, rows.size, next = false),
        next = neighbourIndex(at, rows.size, next = true),
    )
}

/** The row beside [at] in a list of [count] rows, or null past either end. */
internal fun neighbourIndex(at: Int, count: Int, next: Boolean): Int? =
    (if (next) at + 1 else at - 1).takeIf { it in 0 until count }
