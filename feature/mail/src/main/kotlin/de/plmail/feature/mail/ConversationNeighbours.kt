package de.plmail.feature.mail

import de.plmail.core.data.ActionTarget

/**
 * Where the conversation beside the open one sits in the list it was opened from, or null.
 *
 * "Next" is the row below, which in every list here is the older conversation — the order the
 * reader works through an inbox in. Matched on the account and the thread id rather than on the
 * row's uid, because a conversation opened from a notification arrives with only those two, and if
 * it happens to be in the list the user is looking at, swiping from it should work too.
 *
 * Null both when the open conversation is not in [rows] — a list switched underneath, a
 * notification for mail in another label — and when it is the first or last row loaded. The caller
 * cannot tell those apart and does not need to: in each case there is nowhere to go.
 *
 * [rows] is what Paging has loaded so far, placeholders as null. An index that lands on a
 * placeholder is still returned; reading it through `LazyPagingItems` is what asks Paging for it.
 */
internal fun neighbourIndex(rows: List<ActionTarget?>, open: ActionTarget, next: Boolean): Int? {
    val at = rows.indexOf(open)
    if (at < 0) return null

    return (if (next) at + 1 else at - 1).takeIf { it in rows.indices }
}
