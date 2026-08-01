package de.plmail.core.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.plmail.core.database.ThreadEntity
import de.plmail.core.designsystem.LocalPlMailTheme
import de.plmail.core.designsystem.PlMailAvatar

/**
 * One conversation in the list.
 *
 * Everything drawn here comes off the row itself — the thread table is denormalised precisely so
 * that fifty of these can scroll at 120fps without a join or a lazy load per row.
 *
 * The hierarchy is built from **weight and colour, not size**. Sender, subject and preview are
 * within a few points of one another; what separates them is that the sender is medium-weight ink,
 * the subject is plain ink, and the preview is muted. Doing it with size instead makes the preview
 * the smallest line on the row, which is exactly backwards — it is the line people actually read to
 * decide whether to open the mail.
 *
 * Unread is a weight change on the sender and subject plus an accent date, not bold on everything.
 * A row where three lines all go bold is a row that shouts, and an inbox where half the rows shout
 * says nothing.
 *
 * There was a fourth mark — an accent dot under the date — and it is gone. Two reasons, and the
 * second is the one that matters. It was redundant: the subject was already bold and the date
 * already accented, so on an inbox where most mail is unread the dot was the third time the row
 * said the same thing, and the accent this palette rations was suddenly the most repeated colour on
 * screen. Worse, it shared a slot with the star. That column answers "what does this conversation
 * carry" — an attachment, a star the user put there — and unread is not something the conversation
 * carries, it is something the reader has not done. Two different kinds of fact in one slot means
 * neither reads at a glance, and a starred unread thread had them fighting for the same six pixels.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ThreadRow(
    thread: ThreadEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    isSelected: Boolean = false,
) {
    val theme = LocalPlMailTheme.current
    val colors = theme.colors
    val spacing = theme.spacing

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(if (isSelected) colors.accentSoft else colors.surface)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                // The row is never shorter than a touch target, whatever the
                // density: a compact list still has to be tappable by someone
                // walking.
                .heightIn(min = spacing.touchTarget)
                .padding(horizontal = spacing.gutter, vertical = spacing.medium)
                .clearAndSetSemantics { contentDescription = thread.spoken() },
        horizontalArrangement = Arrangement.spacedBy(spacing.medium),
        verticalAlignment = Alignment.Top,
    ) {
        val seed = thread.participantsAddress ?: thread.participantsSummary

        PlMailAvatar(seed = seed, label = avatarLetter(seed))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(spacing.tiny / 2),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
                Text(
                    text =
                        thread.participantsSummary.ifBlank { stringResource(R.string.no_sender) },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (thread.isUnread) FontWeight.SemiBold else FontWeight.Medium,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )

                if (thread.messageCount > 1) {
                    // The count sits with the sender rather than in the right
                    // column: it describes the conversation, and the right
                    // column is when-and-what-kind.
                    Text(
                        text = thread.messageCount.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.inkMuted,
                    )
                }
            }

            Text(
                // "(no subject)" rather than an empty line: a blank row looks
                // like a rendering failure, and mail genuinely arrives without
                // a subject.
                text =
                    thread.subject?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.no_subject),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (thread.isUnread) FontWeight.SemiBold else FontWeight.Normal,
                color = if (thread.isUnread) colors.ink else colors.inkSoft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                text = thread.snippet,
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(spacing.tiny),
        ) {
            Text(
                text = thread.latestReceivedAt.asListDate(),
                style = MaterialTheme.typography.labelSmall,
                // The one place unread changes a *colour* rather than a weight:
                // the date is the first thing scanned, and accent on it reads
                // as "new" without another bold line.
                color = if (thread.isUnread) colors.accent else colors.inkMuted,
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.tiny),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (thread.hasAttachment) {
                    Icon(
                        imageVector = Icons.Outlined.AttachFile,
                        contentDescription = null,
                        modifier = Modifier.size(AFFORDANCE),
                        tint = colors.inkFaint,
                    )
                }

                if (thread.isFlagged) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = null,
                        modifier = Modifier.size(AFFORDANCE),
                        tint = colors.warning,
                    )
                }
            }
        }
    }
}

private val AFFORDANCE = 15.dp

/** What the screen reader says, as one sentence rather than eight disconnected fragments. */
private fun ThreadEntity.spoken(): String = buildList {
    if (isUnread) add("Unread")
    add(participantsSummary.ifBlank { "Unknown sender" })
    add(subject?.takeIf { it.isNotBlank() } ?: "No subject")
    if (messageCount > 1) add("$messageCount messages")
    if (hasAttachment) add("has attachment")
    if (isFlagged) add("starred")
}
    .joinToString(", ")
