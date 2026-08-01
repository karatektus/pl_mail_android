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
import java.time.LocalDate

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
 * Unread is a weight change and a step up the ink scale — semibold sender, semibold full-ink
 * subject, a medium-weight date — and not bold on everything. A row where three lines all go bold
 * is a row that shouts, and an inbox where half the rows shout says nothing.
 *
 * **Nothing on this row is accented, and that is deliberate.** Two marks have now been taken off
 * it, for one reason each time, and the reason is the same reason.
 *
 * The first was an accent dot under the date. It was redundant — the subject was already bold — and
 * it shared a slot with the star. That column answers "what does this conversation carry": an
 * attachment, a star the user put there. Unread is not something the conversation carries, it is
 * something the reader has not done, and two kinds of fact in one slot means neither reads at a
 * glance.
 *
 * The second was the accent on the *date*, which inherited the dot's problem rather than being
 * freed of it. Judged the only way it can be judged — `ThreadListScreenshotTest` renders fourteen
 * rows in both schemes — and the verdict was clear in both directions. With every row unread, which
 * is a fresh account, an overnight batch or a Monday, the accent appeared eleven times and
 * distinguished nothing: it was the most repeated colour on screen while carrying no information at
 * all. In dark it was worse, because the accent brightens to a mint that also appears in the avatar
 * ramp two hundred pixels to the left — the same hue on both edges of every row, meaning "unread"
 * on one side and nothing whatsoever on the other. And in the mixed case, where it did help, it was
 * never the signal doing the work: semibold sender over a semibold full-ink subject is legible from
 * across the room without it.
 *
 * So the ink scale carries hierarchy *within* a row, and the accent is reserved for what the app
 * offers rather than what the mail is — the compose button, the label you are looking at, a link. A
 * date is neither.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ThreadRow(
    thread: ThreadEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    isSelected: Boolean = false,
    /**
     * What "today" means for the date column.
     *
     * Hoisted for the same reason [asListDate] hoists it one level down: the column's answer is
     * relative, so a screenshot baseline that reads the clock is a baseline that stops matching
     * overnight — and "time today, weekday this week, otherwise a date" is precisely the behaviour
     * a list of rows is worth looking at. Every caller in the app takes the default.
     */
    today: LocalDate = LocalDate.now(),
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
                text = thread.latestReceivedAt.asListDate(today = today),
                style = MaterialTheme.typography.labelSmall,
                // Promoted up the ink scale and one weight step, rather than
                // accented. See the note on this composable for why the accent
                // came off it. Null rather than Normal for the read case: the
                // caption style is already Medium, and overriding it downward
                // would quietly make every date in the app lighter than the
                // scale says a caption is.
                fontWeight = if (thread.isUnread) FontWeight.SemiBold else null,
                color = if (thread.isUnread) colors.ink else colors.inkMuted,
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
