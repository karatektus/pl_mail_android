package de.plmail.feature.calendar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.plmail.core.designsystem.PlMailTheme

/**
 * The mark that says which calendar a row is on — and, when a meeting is held on more than one,
 * that it is on several.
 *
 * A pie of equal slices, one per member, which is the phone's reading of the web chip's
 * `conic-gradient`. Deliberately the **same dot at the same size** as a single-calendar row's, so a
 * list of fifty events does not change its rhythm because two of them happen to be duplicated: a
 * merged row is one meeting, and it should look like one row.
 *
 * Two colours say "more than one calendar" and nothing else, which is why every caller also names
 * the calendars in the row's description — see `calendar_row_a11y_merged`. The dot itself is
 * `aria-hidden`'s equivalent: it carries no description, because the sentence around it already
 * carries the whole fact and a TalkBack user should not hear "graphic" between the time and the
 * title.
 */
@Composable
internal fun CalendarDot(colors: List<Color>, modifier: Modifier = Modifier, size: Dp = DOT_SIZE) {
    val slices = colors.ifEmpty { listOf(fallbackDotColor()) }

    if (slices.size == 1) {
        Canvas(modifier = modifier.size(size)) { drawCircle(color = slices.first()) }

        return
    }

    Canvas(modifier = modifier.size(size)) {
        val sweep = FULL_TURN / slices.size

        slices.forEachIndexed { index, color ->
            drawArc(
                color = color,
                // From the top rather than from three o'clock, so a two-colour
                // dot splits left/right instead of top/bottom. A horizontal
                // split reads at 10dp; a horizontal *line* across a circle that
                // small reads as a defect in the drawing.
                startAngle = TOP_OF_CIRCLE + index * sweep,
                sweepAngle = sweep,
                useCenter = true,
            )
        }
    }
}

/** A degree of arc. */
private const val FULL_TURN = 360f

/** Where a canvas angle of zero points, corrected to twelve o'clock. */
private const val TOP_OF_CIRCLE = -90f

/** The colour dot. Small: it identifies a calendar, it is not a second accent. */
internal val DOT_SIZE = 10.dp

/** The token a calendar with no colour of its own falls back to. Never a grey invented here. */
@Composable private fun fallbackDotColor(): Color = PlMailTheme.colors.inkFaint

/**
 * Every member calendar's colour, in member order, with the theme standing in for an absent one.
 */
@Composable
internal fun EventCluster.dotColors(): List<Color> {
    val fallback = fallbackDotColor()

    return colors.map { calendarColor(it) ?: fallback }
}

/**
 * One cluster as a grid or a month cell draws it: the dot, the time, the title.
 *
 * The same content in every view, which is the point of it being one composable — the web keeps one
 * `_event_chip.html.twig` for the same reason, and the day it grew a second kind of chip is the day
 * the two would start disagreeing about what a cancelled meeting looks like.
 *
 * [tall] fills a block that has a height of its own: the title wraps into the space instead of
 * being truncated to one line, and everything is aligned to the top rather than centred, because a
 * chip centred in a ninety-minute block reads as floating.
 *
 * The whole chip carries **one sentence** for TalkBack rather than three stops. Three stops per
 * event is a screenful of fragments — "09:00", "Standup", "Arbeit" — that the listener has to
 * reassemble, and a week grid has thirty of them.
 */
@Composable
internal fun EventChip(
    cluster: EventCluster,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showTime: Boolean = true,
    tall: Boolean = false,
) {
    val theme = PlMailTheme.values
    val row = cluster.primary
    val cancelled = row.status == STATUS_CANCELLED
    val time = startTimeOf(row)?.format(CLOCK)
    val sentence = cluster.a11ySentence()

    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(theme.radii.small))
                .clickable(onClick = onClick)
                .padding(horizontal = theme.spacing.tiny, vertical = theme.spacing.hair)
                .clearAndSetSemantics { contentDescription = sentence },
        verticalAlignment = if (tall) Alignment.Top else Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(theme.spacing.tiny),
    ) {
        CalendarDot(
            colors = cluster.dotColors(),
            // Nudged onto the first line's baseline-ish rather than the top of
            // the box, which is where `Alignment.Top` would put it in a block
            // three lines tall.
            modifier = if (tall) Modifier.padding(top = theme.spacing.hair) else Modifier,
            size = CHIP_DOT,
        )

        if (showTime && time != null && !row.isAllDay) {
            Text(
                text = time,
                style = MaterialTheme.typography.labelSmall,
                color = theme.colors.inkFaint,
                maxLines = 1,
            )
        }

        Text(
            text = row.title,
            style = MaterialTheme.typography.labelMedium,
            color = theme.colors.ink,
            maxLines = if (tall) TALL_LINES else 1,
            overflow = TextOverflow.Ellipsis,
            textDecoration = if (cancelled) TextDecoration.LineThrough else null,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * A block on the time grid: the chip inside a coloured, floored box.
 *
 * The floor is what a fifteen-minute meeting needs to be readable. A quarter hour is a ninety-sixth
 * of the column — about twelve pixels at the height this grid gets on a phone — and one line of
 * "9:00 Standup" wants nearer thirty, so a short event drew a sliver with its own label spilling
 * out of it.
 *
 * The floored block is then taller than its duration and can reach over the one after it. That is
 * the right trade rather than a compromise: the block's **top** is where the meeting starts and
 * stays honest, only the padded bottom is a fiction, and the alternative — reflowing so short
 * events push their neighbours down — would move a 10:00 meeting off the 10:00 line, which is the
 * one thing a time grid exists to get right.
 */
@Composable
internal fun EventBlock(placed: PlacedCluster, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val theme = PlMailTheme.values
    val tint = calendarColor(placed.cluster.primary.calendarColor) ?: theme.colors.accent

    Row(
        modifier =
            modifier
                .padding(BLOCK_GAP)
                .clip(RoundedCornerShape(theme.radii.small))
                .background(tint.copy(alpha = BLOCK_TINT))
                .background(theme.colors.surface.copy(alpha = BLOCK_VEIL))
    ) {
        EventChip(
            cluster = placed.cluster,
            onClick = onClick,
            tall = true,
            modifier = Modifier.fillMaxSize().padding(theme.spacing.hair),
        )
    }
}

/**
 * What TalkBack reads for one row, merged or not.
 *
 * Read out of composition rather than inside a `semantics {}` block, which is not a composable
 * scope and cannot reach a string resource from inside itself — a trap this repo has hit twice.
 */
@Composable
internal fun EventCluster.a11ySentence(): String {
    val allDay = stringResource(R.string.calendar_all_day)
    val row = primary
    val time = startTimeOf(row)?.format(CLOCK) ?: allDay
    val place = row.location?.takeIf { it.isNotBlank() }
    val calendars = calendarNames.joinToString(", ")

    // A merged row says both calendars, because the two-colour dot says "more
    // than one" and nothing else -- which for a screen reader is nothing at all.
    return when {
        isMerged && place != null ->
            stringResource(
                R.string.calendar_row_a11y_merged_located,
                time,
                row.title,
                place,
                calendars,
            )
        isMerged -> stringResource(R.string.calendar_row_a11y_merged, time, row.title, calendars)
        place != null ->
            stringResource(R.string.calendar_row_a11y_located, time, row.title, place, calendars)
        else -> stringResource(R.string.calendar_row_a11y, time, row.title, calendars)
    }
}

/** The dot inside a chip, smaller than an agenda row's: a grid block has less room to spare. */
private val CHIP_DOT = 8.dp

/** How many lines a title may take inside a block before it is cut. */
private const val TALL_LINES = 3

/** The hairline between two adjacent blocks, so a lane boundary is visible. */
private val BLOCK_GAP = 1.dp

/** How much of the calendar's own colour a block is washed with. */
private const val BLOCK_TINT = 0.18f

/**
 * A veil of the surface over the tint.
 *
 * The calendar's colour is a literal hex the user picked in a browser and is not a design token, so
 * it has no dark variant — a saturated `#3b82f6` at 18% over a near-black surface is a different
 * weight from the same wash over paper. The veil is what keeps the two readable without
 * reinterpreting a colour the user chose.
 */
private const val BLOCK_VEIL = 0.35f
