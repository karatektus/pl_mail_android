package de.plmail.feature.calendar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * One cluster as a month cell draws it: a tinted card, the title over the time.
 *
 * **Two lines rather than the grid chip's one, and that is what makes a title readable here.** A
 * month cell on a 411dp phone is about 55dp wide. Laid out as one line, the clock takes half of it
 * and the title gets four characters — "Stan…", which is the argument this view drew dots on for as
 * long as it did. Stacked, the title gets the whole width and the clock gets the line under it, and
 * four characters become eleven.
 *
 * **The calendar's colour arrives twice, as a wash and as a rail**, and neither of them is the
 * text. The wash is the same recipe [EventBlock] uses — the calendar's own hex over a veil of the
 * surface, because that hex is a literal the user picked in a browser and has no dark variant of
 * its own. Drawing the *title* in it, which is what the reference this was modelled on does, would
 * put a user-chosen colour into the one role on the screen that has to stay legible in both schemes
 * and is the one thing `PaletteContrastTest` cannot check. The rail carries the colour at full
 * strength instead, where it costs 3dp and no contrast, and it is what a merged meeting splits in
 * two — the same fact the pie dot carries everywhere else in this app.
 *
 * The chip is **not a target**. See `MonthGrid`: the cell is the control, and a chip is the picture
 * of an event inside it.
 */
@Composable
internal fun MonthEventChip(
    cluster: EventCluster,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
) {
    val theme = PlMailTheme.values
    val row = cluster.primary
    val cancelled = row.status == STATUS_CANCELLED
    val allDay = stringResource(R.string.calendar_all_day)
    val time = startTimeOf(row)?.format(CLOCK) ?: allDay
    val tint = calendarColor(row.calendarColor) ?: theme.colors.accent

    Row(
        modifier =
            modifier
                // The spill-in days of the neighbouring months dim whole, chip
                // and rail together, rather than the date alone going grey over
                // chips at full strength -- which would leave last month's
                // Tuesday reading as the loudest thing in the grid.
                .alpha(if (dimmed) OTHER_MONTH_ALPHA else 1f)
                .clip(RoundedCornerShape(theme.radii.small))
                .background(tint.copy(alpha = BLOCK_TINT))
                .background(theme.colors.surface.copy(alpha = BLOCK_VEIL))
    ) {
        CalendarRail(
            colors = cluster.dotColors(),
            modifier = Modifier.fillMaxHeight().width(CHIP_RAIL),
        )

        Column(
            modifier =
                Modifier.weight(1f)
                    .padding(horizontal = theme.spacing.hair)
                    // Centred in the chip rather than pinned to its top: the
                    // chip's height follows the font scale, and two lines
                    // floating at the top of a tall box is what that looks like
                    // when it does not quite fill.
                    .fillMaxHeight(),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = row.title,
                // Smaller than the type scale's smallest label, and tighter
                // leading than it too, which are the liberties this chip takes.
                // Both are bought with a measurement rather than taste: a 55dp
                // cell fits about eleven characters of `labelSmall` and about
                // thirteen of this, and labelSmall's own 16sp leading over two
                // lines is taller than the chip it has to sit in. Still `sp`,
                // so the whole thing answers to the system font size -- see
                // `monthChipHeight`, which scales the box these lines live in.
                style =
                    MaterialTheme.typography.labelSmall.copy(
                        fontSize = CHIP_TITLE,
                        lineHeight = CHIP_LINE,
                    ),
                color = theme.colors.ink,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (cancelled) TextDecoration.LineThrough else null,
            )

            Text(
                text = time,
                // A step smaller than the title, which is the second liberty
                // this chip takes and the one a 12-hour locale forces. "10:00
                // AM" is eight characters and a 55dp cell fits about eight and
                // a half of them at the title's size, so the line arrived
                // reading "10:00 A" -- a clock with its meridiem cut in half,
                // which is worse than no clock at all. It is also the right
                // hierarchy independently: the title is what the event *is*.
                style =
                    MaterialTheme.typography.labelSmall.copy(
                        fontSize = CHIP_TIME,
                        lineHeight = CHIP_LINE,
                    ),
                color = theme.colors.inkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The calendar's colour down the leading edge of a chip, split when the meeting is on more than
 * one.
 *
 * The rail's reading of the pie dot: equal slices in member order, so the two marks tell the same
 * story about the same meeting. Vertical rather than horizontal, because 3dp across is not a
 * division anybody would see.
 */
@Composable
private fun CalendarRail(colors: List<Color>, modifier: Modifier = Modifier) {
    val slices = colors.ifEmpty { listOf(fallbackDotColor()) }

    Column(modifier = modifier) {
        slices.forEach { color ->
            Box(modifier = Modifier.weight(1f).fillMaxWidth().background(color))
        }
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

/** The calendar's colour down a month chip's leading edge. See [CalendarRail]. */
private val CHIP_RAIL = 3.dp

/** Two lines in a chip 27dp tall, with room left for the rounding. See [MonthEventChip]. */
private val CHIP_LINE = 12.sp

/**
 * A month chip's title, and the clock under it.
 *
 * In `sp` rather than `dp`, so both still answer to the system font size — small is a decision
 * about these lines' place in the hierarchy, not a refusal to scale. The chip's own height scales
 * with them; see `monthChipHeight`.
 *
 * The clock is a step below the title and has to be: "10:00 AM" is eight characters against a cell
 * that fits about thirteen of the title's, and a 12-hour locale set at the title's size arrived
 * reading "10:00 A" — a meridiem cut in half, which is worse than no clock at all.
 */
private val CHIP_TITLE = 10.sp

private val CHIP_TIME = 8.sp

/**
 * How tall one month chip is at a font scale of one.
 *
 * The number the slot arithmetic divides by — see `monthChipSlots` — and therefore a number the two
 * lines above have to actually fit inside at the default scale. `monthChipHeight` is what scales
 * it.
 */
internal val MONTH_CHIP_HEIGHT = 27.dp

/** The gap between two stacked month chips. Between them only, never after the last. */
internal val MONTH_CHIP_GAP = 2.dp

/**
 * How far a neighbouring month's chips are faded.
 *
 * Faded rather than hidden: those events are real and a person paging through a month legitimately
 * cares that the 1st is already full. Far enough down that the month being drawn is unmistakably
 * the loud one.
 */
private const val OTHER_MONTH_ALPHA = 0.55f

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
