package de.plmail.feature.mail.reader

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Snooze
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.plmail.core.data.Label
import de.plmail.core.designsystem.PlMailLabelColor
import de.plmail.core.designsystem.PlMailTheme
import de.plmail.feature.mail.R
import de.plmail.feature.mail.SnoozeMenu
import de.plmail.feature.mail.SnoozePicker
import de.plmail.feature.mail.displayName
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * What the reader says about a conversation above its messages, beyond the subject.
 *
 * Read off the cached thread row rather than off the messages, and observed rather than read once:
 * every one of these changes under the reader — a star tapped here, a label ticked in the sheet
 * hosted a level up, a snooze ending — and a heading that kept the answer it opened with would
 * disagree with the list the moment the user went back to it.
 */
data class ReaderHeaderState(
    val isStarred: Boolean = false,
    /** The user's own labels on this conversation, in sidebar order. Never a system role. */
    val labels: List<Label> = emptyList(),
    val snoozedUntil: Long? = null,
    val messageCount: Int = 0,
    /**
     * The account this conversation is in, or null where there is only one.
     *
     * Null on a single-account install for the reason a list row carries no account mark there: it
     * would say the same thing on every conversation, which is decoration rather than information.
     */
    val accountName: String? = null,
)

/**
 * The conversation's heading: its subject, and what is true of it.
 *
 * **The subject is unbounded on purpose.** The whole reason it left the bar is that a subject is
 * not a label to be truncated: "Re: Ihre Anfrage vom 3. Oktober – Rückfrage zu Position 4" cut off
 * after "Re: Ihre Anf…" names nothing, and the reader is the one screen whose job is to show the
 * thing in full.
 *
 * **The star is here rather than in the bar**, beside the thing it marks, where Gmail has taught
 * everybody to look for it. It also *shows* the state, which the overflow's "Star" never could: a
 * menu item is a verb, and a starred conversation offered "Star" again is a control that does
 * nothing.
 *
 * **A chip is two controls.** A tap goes to that label's list; a long press takes the label off,
 * and says so to TalkBack as the long-click action. A separate × per chip would be the more
 * discoverable version and it does not fit: every tappable thing here is 48dp, and two of those per
 * label is a row of buttons rather than a row of labels. Removing is undoable, like every other
 * change to mail, which is what makes a gesture with no confirmation safe.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReaderHeader(
    subject: String,
    state: ReaderHeaderState,
    onStar: (Boolean) -> Unit,
    onOpenLabel: (Label) -> Unit,
    onRemoveLabel: (Label) -> Unit,
    onAddLabel: () -> Unit,
    onSnooze: (Instant?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = PlMailTheme.spacing
    val colors = PlMailTheme.colors

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(start = spacing.gutter)
                .padding(top = spacing.small, bottom = spacing.medium)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = subject,
                style = MaterialTheme.typography.titleLarge,
                color = colors.ink,
                modifier =
                    Modifier.weight(1f)
                        // Level with the star's glyph rather than with its 48dp
                        // box, so the first line and the star read as one row.
                        .padding(top = spacing.small)
                        .semantics { heading() },
            )

            IconButton(onClick = { onStar(!state.isStarred) }) {
                Icon(
                    imageVector =
                        if (state.isStarred) Icons.Filled.Star else Icons.Outlined.StarOutline,
                    contentDescription =
                        stringResource(
                            if (state.isStarred) R.string.action_unstar else R.string.action_star
                        ),
                    tint = if (state.isStarred) colors.accent else colors.inkSoft,
                )
            }
        }

        ReaderFacts(state)

        FlowRow(
            modifier = Modifier.padding(end = spacing.gutter),
            horizontalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            state.labels.forEach { label ->
                ReaderLabelChip(
                    label = label,
                    onOpen = { onOpenLabel(label) },
                    onRemove = { onRemoveLabel(label) },
                )
            }

            AddLabelChip(onClick = onAddLabel)
        }

        state.snoozedUntil?.let { until -> SnoozedLine(until = until, onSnooze = onSnooze) }
    }
}

/** "4 messages · Work account", in the muted ink of a caption. Nothing at all for one message. */
@Composable
private fun ReaderFacts(state: ReaderHeaderState) {
    val count =
        state.messageCount
            .takeIf { it > 1 }
            ?.let { pluralStringResource(R.plurals.reader_message_count, it, it) }
    val facts = listOfNotNull(count, state.accountName)

    if (facts.isEmpty()) return

    Text(
        text = facts.joinToString(FACT_SEPARATOR),
        style = MaterialTheme.typography.bodySmall,
        color = PlMailTheme.colors.inkMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(end = PlMailTheme.spacing.gutter),
    )
}

/**
 * One label, as something to press.
 *
 * Drawn as the list row's chip is — sunken fill, hairline, the label's colour in the hairline and
 * the ink and never in the fill — because it is the same mark saying the same thing, and a second
 * style for it here would read as a second kind of object. Larger, because this one is a control:
 * the visible pill sits inside a 48dp target.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReaderLabelChip(label: Label, onOpen: () -> Unit, onRemove: () -> Unit) {
    val theme = PlMailTheme.values
    val accent = PlMailLabelColor.fromWire(label.color)?.let { theme.colors.labelColor(it) }

    Box(
        modifier =
            Modifier.heightIn(min = theme.spacing.touchTarget)
                .combinedClickable(
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.reader_label_open),
                    onLongClickLabel = stringResource(R.string.reader_label_remove),
                    onLongClick = onRemove,
                    onClick = onOpen,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Pill(accent = accent) {
            Text(
                text = label.displayName(),
                style = MaterialTheme.typography.labelMedium,
                color = accent ?: theme.colors.inkSoft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = CHIP_MAX_WIDTH),
            )
        }
    }
}

/** The way into the label sheet, at the end of the row it adds to. */
@Composable
private fun AddLabelChip(onClick: () -> Unit) {
    val theme = PlMailTheme.values

    Box(
        modifier =
            Modifier.heightIn(min = theme.spacing.touchTarget)
                .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Pill(accent = null) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = stringResource(R.string.labels_apply),
                tint = theme.colors.inkMuted,
                modifier = Modifier.size(ADD_ICON),
            )
        }
    }
}

@Composable
private fun Pill(accent: androidx.compose.ui.graphics.Color?, content: @Composable () -> Unit) {
    val theme = PlMailTheme.values
    val shape = RoundedCornerShape(theme.radii.control)

    Box(
        modifier =
            Modifier.clip(shape)
                .background(theme.colors.sunken)
                .border(theme.spacing.hair, accent ?: theme.colors.line, shape)
                .heightIn(min = PILL_HEIGHT)
                .padding(horizontal = theme.spacing.medium),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/**
 * "Snoozed until Fri, 9 Oct, 09:00", and the two things to do about it.
 *
 * On the page because it is the one fact about this conversation that explains why it is not where
 * the user expects it — and until now the only trace of it was the overflow offering "Unsnooze"
 * instead of "Snooze". The time is the control for changing it; waking it up has a button of its
 * own, since that is the commoner wish once a snoozed conversation has been opened.
 */
@Composable
private fun SnoozedLine(until: Long, onSnooze: (Instant?) -> Unit) {
    val spacing = PlMailTheme.spacing
    val colors = PlMailTheme.colors

    var isMenuOpen by remember { mutableStateOf(false) }
    var isPickingTime by remember { mutableStateOf(false) }

    if (isPickingTime) {
        SnoozePicker(
            onDismiss = { isPickingTime = false },
            onChosen = {
                isPickingTime = false
                onSnooze(it)
            },
        )
    }

    val at =
        remember(until) {
            SNOOZED_UNTIL.format(Instant.ofEpochMilli(until).atZone(ZoneId.systemDefault()))
        }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.weight(1f)) {
            Row(
                modifier =
                    Modifier.heightIn(min = spacing.touchTarget).clickable(
                        onClickLabel = stringResource(R.string.reader_snooze_change),
                        role = Role.Button,
                    ) {
                        isMenuOpen = true
                    },
                horizontalArrangement = Arrangement.spacedBy(spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Snooze,
                    contentDescription = null,
                    tint = colors.inkMuted,
                    modifier = Modifier.size(SNOOZE_ICON),
                )
                Text(
                    text = stringResource(R.string.reader_snoozed_until, at),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkSoft,
                )
            }

            SnoozeMenu(
                isOpen = isMenuOpen,
                onDismiss = { isMenuOpen = false },
                onChosen = { onSnooze(it) },
                onPickExact = { isPickingTime = true },
            )
        }

        TextButton(onClick = { onSnooze(null) }) { Text(stringResource(R.string.unsnooze)) }
    }
}

private const val FACT_SEPARATOR = " · "

/** The visible pill inside a chip's 48dp target. */
private val PILL_HEIGHT = 28.dp

/** Past this a label name ellipsises, so one long name cannot take the whole row. */
private val CHIP_MAX_WIDTH = 200.dp
private val ADD_ICON = 16.dp
private val SNOOZE_ICON = 18.dp

/** The reader's locale decides the order and the words; this only says how much to show. */
private val SNOOZED_UNTIL: DateTimeFormatter
    get() = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
