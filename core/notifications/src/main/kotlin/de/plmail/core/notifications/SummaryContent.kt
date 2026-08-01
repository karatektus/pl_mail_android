package de.plmail.core.notifications

import de.plmail.core.data.NewMessage

/**
 * What an account's summary notification says.
 *
 * Pure, and separate from the builder, because the counting is where this goes wrong and the
 * counting is invisible on a device until it is wrong twice.
 */
internal data class SummaryContent(
    val lines: List<String>,
    /** How many conversations exist beyond the lines shown, for the "and N more" tail. */
    val overflow: Int,
) {
    val total: Int
        get() = lines.size + overflow
}

/**
 * Builds the summary from the arriving batch and whatever is already in the shade.
 *
 * Two things here are easy to get wrong and both produce a plausible-looking wrong number.
 *
 * **A conversation already showing must not be counted twice.** Notifications are keyed on the
 * thread, so a reply to a conversation the user has already been told about *replaces* its
 * notification. Adding the batch size to the number of posted children would count that thread on
 * both sides, and the summary would say "3 new messages" over two rows.
 *
 * **The overflow is what is not listed, not what did not fit in the batch.** Mail that arrived ten
 * minutes ago and is still unread belongs in the count even though this call has no idea what it
 * said — its lines are gone and only its notification id remains, which is exactly why the count
 * and the lines are computed from different sources.
 */
internal fun summaryContent(
    arriving: List<NewMessage>,
    alreadyShowing: List<Int>,
    maxLines: Int,
): SummaryContent {
    val replacing = arriving.map(::notificationId).toSet()
    val carriedOver = alreadyShowing.count { it !in replacing }

    val lines =
        arriving
            .distinctBy(::notificationId)
            .map { message ->
                // Sender and subject, separated by a wide space rather than a
                // dash: InboxStyle draws one line per entry with no columns, and
                // punctuation between two pieces of user-supplied text reads as
                // part of the subject the moment somebody's subject contains one.
                listOfNotNull(message.sender.takeIf { it.isNotBlank() }, message.subject)
                    .joinToString("  ")
            }
            .filter { it.isNotBlank() }

    val shown = lines.take(maxLines)

    return SummaryContent(lines = shown, overflow = lines.size - shown.size + carriedOver)
}
