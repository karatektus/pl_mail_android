package de.plmail.feature.calendar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
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
 * The shell every grid chip shares: the wash, the rail, the target and the sentence.
 *
 * One composable for it because the *decoration* is the part that must not diverge — the day a
 * block and an all-day chip disagree about how a calendar's colour arrives is the day the grid
 * stops reading as one surface. What differs between them is only what text goes inside, which is
 * why the content is a slot rather than a set of flags.
 *
 * **The colour arrives as a wash and a rail, never as the text.** Identical to [MonthEventChip]'s
 * recipe, and for its reason: the calendar's hex is a literal the user picked in a browser, it has
 * no dark variant, and putting it into the one role on the screen that has to stay legible in both
 * schemes is the one thing `PaletteContrastTest` cannot check for. The rail carries it at full
 * strength where it costs 3dp and no contrast.
 *
 * **The whole chip carries one sentence** for TalkBack rather than three stops. Three stops per
 * event is a screenful of fragments — "09:00", "Standup", "Arbeit" — that the listener has to
 * reassemble, and a week grid has thirty of them.
 */
@Composable
private fun ChipShell(
    cluster: EventCluster,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val theme = PlMailTheme.values
    val tint = calendarColor(cluster.primary.calendarColor) ?: theme.colors.accent
    val sentence = cluster.a11ySentence()

    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(theme.radii.small))
                .background(tint.copy(alpha = BLOCK_TINT))
                .background(theme.colors.surface.copy(alpha = BLOCK_VEIL))
                .clickable(onClick = onClick)
                .clearAndSetSemantics { contentDescription = sentence }
    ) {
        CalendarRail(
            colors = cluster.dotColors(),
            modifier = Modifier.fillMaxHeight().width(CHIP_RAIL),
        )

        content()
    }
}

/**
 * One row of a grid chip.
 *
 * **Ellipsis, always, and that is the point of it being one composable.** A `maxLines = 1` with
 * Compose's default overflow clips at the pixel, which is how the Sunday column came to read "11:4"
 * for a quarter to twelve — a clock cut mid-character, which a person reads as a rendering fault
 * rather than as a truncation. Every line of text in this file's chips goes through here so that
 * cannot come back.
 *
 * The size is passed rather than taken from the type scale because a chip in a seventh of a phone
 * and a chip in a whole one are not the same typographic problem — see [BlockText] — but both are
 * `sp`, so both still answer to the system font size.
 *
 * **[hyphenate] is for the lines that actually wrap, and only those.** A column fifty dp wide holds
 * about eight characters, which is narrower than a great many single words — so a wrapping title
 * breaks *inside* a word most times it wraps, and the platform's default break leaves no mark that
 * it did: the week grid read "Quarterl / y" and "Elternab / end", which is a title that looks
 * misspelt rather than one that ran out of room. Asking for hyphenation puts the break where the
 * language keeps one and marks it, and German — a language whose ordinary nouns are compounds — is
 * where this stops being a nicety. It is off for the single-line lines because a line that
 * ellipsizes never breaks, and turning it on there would only cost the layout a hyphenator.
 */
@Composable
private fun ChipLine(
    text: String,
    size: TextUnit,
    line: TextUnit,
    color: Color,
    maxLines: Int,
    modifier: Modifier = Modifier,
    weight: FontWeight? = null,
    cancelled: Boolean = false,
    hyphenate: Boolean = false,
) {
    val base = MaterialTheme.typography.labelSmall.copy(fontSize = size, lineHeight = line)

    Text(
        text = text,
        // Copied onto the base rather than always set, so a line that does not
        // wrap keeps whatever the type scale decided about breaking.
        style =
            if (hyphenate) base.copy(lineBreak = LineBreak.Paragraph, hyphens = Hyphens.Auto)
            else base,
        color = color,
        fontWeight = weight,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        textDecoration = if (cancelled) TextDecoration.LineThrough else null,
        modifier = modifier,
    )
}

/**
 * One all-day row in the band above the axis: the title, the whole column wide.
 *
 * **The title gets every pixel that is not the rail.** This chip used to lead with a 8dp dot and
 * 4dp of padding either side of it, which in a week column about 50dp wide is a third of the row
 * spent saying something the wash and the rail already say in colour — the band read "So…" for
 * "Sommerfest der Nachbarschaft". Dropping the dot roughly doubles the characters that survive.
 *
 * One line, ellipsized, at a height that follows the font scale: the band is a strip, and an
 * all-day title that wrapped would push the hours it sits above off the screen.
 */
@Composable
internal fun AllDayChip(cluster: EventCluster, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val theme = PlMailTheme.values
    val row = cluster.primary

    ChipShell(
        cluster = cluster,
        onClick = onClick,
        modifier = modifier.height(allDayChipHeight()),
    ) {
        BoxWithConstraints(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            contentAlignment = Alignment.CenterStart,
        ) {
            val text = blockTextFor(maxWidth)

            ChipLine(
                text = row.title,
                size = text.title,
                line = text.line,
                color = theme.colors.ink,
                maxLines = 1,
                weight = FontWeight.Medium,
                cancelled = row.status == STATUS_CANCELLED,
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(horizontal = theme.spacing.hair, vertical = theme.spacing.hair),
            )
        }
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
 * A block on the time grid: the title first, and whatever else the block has the height to hold.
 *
 * **The title, then the clock, then the place — in that order, and cut from the bottom.** The
 * previous drawing was a row of dot, clock and title, which in a week column is not a hierarchy but
 * a queue: the dot and the clock are laid out first, they are the ones that fit, and the title —
 * the only part that says what the meeting *is* — was measured against what was left and got
 * nothing. A whole week of blocks reading "● 9:00" was the result. Stacking the lines and giving
 * the title the leftover ones inverts that: the title is the line that always exists, and the clock
 * appears when the block is two lines tall rather than instead of the title.
 *
 * **How much fits is measured, never assumed** — [blockTextPlan] over the block's own height, the
 * same discipline `monthChipSlots` applies to a month cell. A quarter-hour block at the floor gets
 * one line and one line only; a ninety-minute one gets the title wrapped over as many as it needs
 * and a clock underneath.
 *
 * **A wide column is a different problem from a seventh of one**, so the type and the content both
 * follow the measured width: a day view column has room for a larger face and for the location,
 * which in a week column would be four ellipsized characters of noise. See [BlockText].
 *
 * The floor is what a fifteen-minute meeting needs to be readable. A quarter hour is a ninety-sixth
 * of the column — about twelve pixels at the height this grid gets on a phone — so a short event
 * drew a sliver with its own label spilling out of it.
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
    val row = placed.cluster.primary
    val cancelled = row.status == STATUS_CANCELLED
    val time = startTimeOf(row)?.format(CLOCK)
    val place = row.location?.takeIf { it.isNotBlank() }

    ChipShell(
        cluster = placed.cluster,
        onClick = onClick,
        modifier = modifier.padding(BLOCK_GAP),
    ) {
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxHeight()) {
            val text = blockTextFor(maxWidth)
            val line = with(LocalDensity.current) { text.line.toDp() }
            val plan =
                blockTextPlan(
                    // Net of the padding the lines are about to sit inside, so
                    // the arithmetic divides the room the text actually gets
                    // rather than the room the block has.
                    available = maxHeight - theme.spacing.hair * 2,
                    line = line,
                    hasTime = time != null,
                    hasPlace = text.roomy && place != null,
                )

            // A wide column that is only one line tall puts the clock *beside*
            // the title rather than dropping it. The queue this composable
            // exists to undo was a narrow-column problem: at 355dp a clock
            // costs about a seventh of the row and the title still gets the
            // rest, where at 50dp it took everything. A quarter-hour meeting in
            // Day view is exactly this case, and it is the one place the old
            // one-line drawing was right.
            val inlineTime = time?.takeIf { text.roomy && !plan.showsTime }

            Column(
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(
                            horizontal = if (text.roomy) theme.spacing.tiny else theme.spacing.hair,
                            vertical = theme.spacing.hair,
                        )
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(theme.spacing.tiny)) {
                    ChipLine(
                        text = row.title,
                        size = text.title,
                        line = text.line,
                        color = theme.colors.ink,
                        maxLines = plan.titleLines,
                        weight = FontWeight.Medium,
                        cancelled = cancelled,
                        // Only where the line can actually wrap. See [ChipLine].
                        hyphenate = plan.titleLines > 1,
                        modifier = if (inlineTime != null) Modifier.weight(1f) else Modifier,
                    )

                    if (inlineTime != null) {
                        ChipLine(
                            text = inlineTime,
                            size = text.meta,
                            line = text.line,
                            color = theme.colors.inkMuted,
                            maxLines = 1,
                        )
                    }
                }

                if (plan.showsTime && time != null) {
                    ChipLine(
                        text = time,
                        size = text.meta,
                        line = text.line,
                        color = theme.colors.inkMuted,
                        maxLines = 1,
                    )
                }

                if (plan.showsPlace && place != null) {
                    ChipLine(
                        text = place,
                        size = text.meta,
                        line = text.line,
                        color = theme.colors.inkFaint,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * How a chip sets its text at a given column width.
 *
 * Two settings rather than one, because a week column and a day column are not the same problem: a
 * seventh of a 411dp phone leaves about 44dp for text, where 13sp fits six characters and 11sp fits
 * eight — and eight is the difference between "Standu…" and "Standup". A day view column has twenty
 * times that width and would read as wilfully tiny at the week's size.
 *
 * Both are `sp` and both scale with the system font size; [roomy] additionally decides whether the
 * location is worth drawing at all, since a place name ellipsized to four characters is noise
 * occupying the row a title could have wrapped into.
 */
private data class BlockText(
    val title: TextUnit,
    val meta: TextUnit,
    val line: TextUnit,
    val roomy: Boolean,
)

private fun blockTextFor(width: Dp): BlockText =
    if (width >= BLOCK_ROOMY_WIDTH) RoomyBlockText else CompactBlockText

/**
 * Which lines a block draws in the height it measured, and how many of them the title may take.
 *
 * **The title's line is the one that always exists.** Everything else is bought out of what is left
 * over, which is what makes this a priority order rather than a layout: one line is a title, two is
 * a title and a clock, four in a wide column is a title over a clock over a place — and a title
 * long enough to need them takes the spare lines back before the clock is offered one.
 *
 * Separated from the drawing so it can be asserted without a canvas, for the reason `monthCellPlan`
 * gives: what a person reads off a grid is decided here, and "one line short" is the difference
 * between a readable block and a clock with no title.
 *
 * An unbounded height answers one line rather than throwing. A chip laid out in a column that never
 * constrained it — the all-day band, before it was given a height of its own — is asking to be as
 * small as it can be, not as large.
 */
internal fun blockTextPlan(
    available: Dp,
    line: Dp,
    hasTime: Boolean,
    hasPlace: Boolean,
): BlockTextPlan {
    val lines =
        if (!available.value.isFinite() || line.value <= 0f) TITLE_MIN_LINES
        else maxOf(TITLE_MIN_LINES, (available / line).toInt())

    val time = hasTime && lines > TITLE_MIN_LINES
    val place = hasPlace && lines > TITLE_MIN_LINES + 1

    val spent = (if (time) 1 else 0) + (if (place) 1 else 0)

    return BlockTextPlan(
        titleLines = (lines - spent).coerceIn(TITLE_MIN_LINES, TITLE_MAX_LINES),
        showsTime = time,
        showsPlace = place,
    )
}

data class BlockTextPlan(val titleLines: Int, val showsTime: Boolean, val showsPlace: Boolean)

/**
 * How tall one all-day chip is on this device.
 *
 * One line plus its padding, scaled by the font scale for the reason `monthChipHeight` is — the box
 * has to grow with the text inside it, or a large-text user gets the same strip with the titles
 * clipped. Sized off the roomier of the two settings so the one height serves both columns.
 */
@Composable
internal fun allDayChipHeight(): Dp =
    with(LocalDensity.current) { BLOCK_LINE_ROOMY.toDp() } + ALL_DAY_CHIP_PADDING

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

/** The calendar's colour down a chip's leading edge. See [CalendarRail]. */
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

/**
 * The line a title is never cut below, and the one it is never grown past.
 *
 * A block always says *something* about what it is, even in the sliver a quarter-hour meeting gets:
 * a block drawn with no title at all is a coloured rectangle, which is the state this view was in.
 * The ceiling is the other end of the same argument — an all-afternoon block would otherwise wrap a
 * long title over nine lines and read as a paragraph pinned to the grid.
 */
private const val TITLE_MIN_LINES = 1

private const val TITLE_MAX_LINES = 4

/**
 * A grid chip's text, in the two settings it has.
 *
 * `sp` throughout, so every one of them answers to the system font size — small is a decision about
 * these lines' place in the hierarchy, not a refusal to scale. The line height is what
 * [blockTextPlan] divides the block by, which is why it is stated rather than left to the type
 * scale's own leading: the arithmetic and the drawing have to be using the same number or a block
 * plans for three lines and draws two and a half.
 *
 * The meta lines sit a step below the title for the reason [MonthEventChip]'s clock does — the
 * title is what the event *is* — and it buys the same thing it does there: "10:00 AM" is eight
 * characters against a week column that fits about eight of the title's.
 */
private val BLOCK_TITLE = 11.sp

private val BLOCK_META = 10.sp

private val BLOCK_LINE = 13.sp

private val BLOCK_TITLE_ROOMY = 13.sp

private val BLOCK_META_ROOMY = 11.sp

private val BLOCK_LINE_ROOMY = 16.sp

/** The week's setting: as many characters per line as fifty dp can be made to hold. */
private val CompactBlockText =
    BlockText(title = BLOCK_TITLE, meta = BLOCK_META, line = BLOCK_LINE, roomy = false)

/** The day's: a full-width column, which has room for a readable face and for the place. */
private val RoomyBlockText =
    BlockText(
        title = BLOCK_TITLE_ROOMY,
        meta = BLOCK_META_ROOMY,
        line = BLOCK_LINE_ROOMY,
        roomy = true,
    )

/**
 * How wide a column has to be before a chip sets its text for one.
 *
 * Between the two widths that actually occur and closer to neither by accident: a week column on a
 * 411dp phone is about 50dp and a day column is about 355dp, so anything between 60 and 300 draws
 * the same picture. It is expressed as a width rather than as a view mode because the day view
 * splits its column when two meetings overlap, and a half-width lane is a real case that should get
 * whichever setting it has the room for.
 */
private val BLOCK_ROOMY_WIDTH = 160.dp

/** The room an all-day chip keeps around its single line. See [allDayChipHeight]. */
private val ALL_DAY_CHIP_PADDING = 6.dp

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
