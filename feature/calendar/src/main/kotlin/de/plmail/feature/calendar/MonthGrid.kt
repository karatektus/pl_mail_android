package de.plmail.feature.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.plmail.core.database.CalendarEntity
import de.plmail.core.designsystem.PlMailTheme
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The month: six weeks of day cells, each carrying what is on the day.
 *
 * **Always six weeks, never five-or-six**, which is why the window is 42 days from the week start
 * on or before the first — see `CalendarViewMode.window`. A grid that changed height between months
 * would move every cell under the finger as you page through it, and the surrounding layout would
 * have to reserve for the taller case anyway.
 *
 * **Titled chips, at whatever number the cell can actually hold.** This view used to draw dots and
 * only dots, on the argument that a chip in a 55dp cell fits four characters and reads as broken
 * text. That argument was about the *width* and it is still true of a chip laid out as one line of
 * "09:00 Standup" — but a chip stacked two lines deep, title over time, gives the title the whole
 * cell width instead of what a clock leaves of it, which is about eleven characters rather than
 * four. Eleven is the difference between "Stan…" and "Standup". So the chips are back, in the shape
 * that fits, and [MonthCellStyle.DOTS] keeps the old drawing for the one place it is still right —
 * the compact grid above the mixture view's agenda, where a cell is a third of the height.
 *
 * **How many fit is measured, never assumed.** [monthChipSlots] divides the room left under the
 * date by the chip height, so the same code draws three chips on a tall phone, two on a short one,
 * and falls back to dots on a device where a chip does not fit at all. The chip height itself
 * follows the user's font scale — see [monthChipHeight] — so a large-text phone gets *fewer* chips
 * rather than the same number with their titles clipped.
 *
 * **Tapping a day opens that day**, in Day view, rather than scoping the agenda to it. Two reasons:
 * the day view is where a month's ambiguity actually resolves (which of these four is at nine, and
 * do any of them overlap), and it means the "more than fits" case and the ordinary case land in the
 * same place — the web's own "+n" link goes to `view: day` for exactly that reason. It also leaves
 * the switcher telling the truth about where the user now is, which an agenda secretly scoped to
 * one day would not. In the mixture view the same tap *selects* instead, because there the day's
 * events are already on screen underneath.
 *
 * **The chips are not themselves targets, and that is the whole point of the cell being one.** A
 * three-chip cell with three tappable children is three TalkBack stops of four-character titles and
 * three 15dp touch targets, in place of one 100dp target that reads the day out as a sentence. So a
 * chip is a picture of an event and the cell is the control — including the "+n" chip, which lands
 * in the same day view a tap anywhere else in the cell does.
 *
 * **Long-pressing a day creates on it**, at nine in the morning, matching the web's per-cell `+`
 * (`start: dayKey ~ ' 09:00'`). Same gesture as the time grid's, for the same reason, and the top
 * bar's `+` remains the accessible route.
 */
@Composable
internal fun MonthGrid(
    days: List<MonthDay>,
    today: LocalDate,
    anchorMonth: Int,
    weekday: DateTimeFormatter,
    onOpenDay: (LocalDate) -> Unit,
    onCreateAt: (LocalDateTime) -> Unit,
    modifier: Modifier = Modifier,
    style: MonthCellStyle = MonthCellStyle.CHIPS,
    selected: LocalDate? = null,
) {
    val theme = PlMailTheme.values

    // The rules are the full month's and the compact grid's absence of them is
    // the compact grid's whole character. Six weeks of hairlines over a third
    // of the height is a net rather than a calendar, and the grid up there is
    // being *scanned* -- for which the alignment of seven columns is already
    // doing the work a line would do. The full month keeps them, because a cell
    // with chips in it needs a boundary or the chips read as belonging to the
    // column rather than to the day.
    val ruled = style == MonthCellStyle.CHIPS

    Column(modifier = modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = theme.spacing.tiny)) {
            days.take(CalendarViewMode.WEEK_DAYS).forEach { day ->
                val isSunday = day.date.dayOfWeek == DayOfWeek.SUNDAY

                Text(
                    // Upper case with the letters opened out: at this size a
                    // strip of small caps reads as a legend rather than as a
                    // seven-word row of content, which is what it is. Cased
                    // against the *device's* locale rather than the root one --
                    // a Turkish phone lower-cases its own dotted i differently,
                    // and `uppercase()` with no argument is the bug that ships.
                    text = day.date.format(weekday).uppercase(Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall,
                    // Sunday in the accent, which is the one column a month is
                    // scanned against. It is also the convention this app's
                    // German audience already reads -- Sundays are the coloured
                    // ones in every wall calendar they own -- and it survives
                    // the week starting on a Monday, which tinting "the first
                    // column" would not.
                    color = if (isSunday) theme.colors.accent else theme.colors.inkFaint,
                    fontWeight = if (isSunday) FontWeight.SemiBold else FontWeight.Medium,
                    letterSpacing = WEEKDAY_TRACKING,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier =
                        Modifier.weight(1f)
                            .padding(horizontal = theme.spacing.hair)
                            // The weekday strip is a legend for the grid below,
                            // and every cell already names its own weekday in
                            // full. Seven extra stops before the first day is a
                            // week of swiping to reach Monday.
                            .clearAndSetSemantics {},
                )
            }
        }

        // The line colour is the grid's *background*, and every cell leaves a
        // hairline of it showing round its own edge. One rule per boundary
        // rather than a border per cell, which would double every internal
        // line and leave a two-pixel seam down the middle of the month.
        days.chunked(CalendarViewMode.WEEK_DAYS).forEach { week ->
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .weight(1f)
                        .then(if (ruled) Modifier.background(theme.colors.line) else Modifier)
            ) {
                week.forEach { day ->
                    DayCell(
                        day = day,
                        isToday = day.date == today,
                        isOtherMonth = day.date.monthValue != anchorMonth,
                        isWeekend =
                            day.date.dayOfWeek == DayOfWeek.SATURDAY ||
                                day.date.dayOfWeek == DayOfWeek.SUNDAY,
                        isSelected = day.date == selected,
                        style = style,
                        onOpen = { onOpenDay(day.date) },
                        onCreate = {
                            onCreateAt(LocalDateTime.of(day.date, NEW_EVENT_HOUR))
                        },
                        modifier = Modifier.weight(1f).fillMaxSize(),
                    )
                }
            }
        }
    }
}

/**
 * How a cell draws what is on the day.
 *
 * Two drawings of one grid rather than two grids: the weekday strip, the six-week rule, the today
 * fill, the weekend fill, the spill-in dimming, the long-press and the whole accessibility sentence
 * are the same in both, and the day they were written twice is the day one of them stopped saying
 * what the other did.
 */
internal enum class MonthCellStyle {
    /** Titled chips, as many as the cell measures room for, then "+n". The full-screen month. */
    CHIPS,

    /** A dot per meeting and nothing else. The compact grid over the mixture view's agenda. */
    DOTS,
}

/** One day of a month grid, with what is on it already collapsed into rows a person reads. */
data class MonthDay(val date: LocalDate, val clusters: List<EventCluster>)

/**
 * How many dots a cell draws before it counts the rest.
 *
 * Four fits a 55dp cell at every density with the "+n" beside it. A cell that grew to fit
 * everything would make every other row in the grid taller for one busy Tuesday.
 */
internal const val MONTH_DOTS = 4

/**
 * The marks and the overflow count one cell shows, from what is on the day.
 *
 * Separated from the drawing so the arithmetic can be asserted without a canvas: the count is what
 * a person reads as "and three more", and getting it off by one is the difference between an honest
 * grid and one that hides a meeting.
 */
internal fun monthIndicators(clusters: List<EventCluster>): MonthIndicators =
    MonthIndicators(
        shown = clusters.take(MONTH_DOTS),
        // Never negative, and never "+0": a day with exactly four is a day with
        // no overflow, and a footer saying "+0 weitere" is a line that has to be
        // read to learn nothing.
        overflow = maxOf(0, clusters.size - MONTH_DOTS),
    )

data class MonthIndicators(val shown: List<EventCluster>, val overflow: Int)

/**
 * Which chips a cell draws and what it says about the rest, given the room it measured.
 *
 * **The overflow chip costs a slot, and the arithmetic has to pay for it.** A cell with room for
 * three and four things on it draws *two* chips and "+2 more", not three chips and a "+1 more" with
 * nowhere to go. That is why the reserve is subtracted before the take rather than after: taking
 * three and then discovering the counter needs a row is how a grid ends up either hiding the fourth
 * meeting silently or drawing a fourth row it has no room for.
 *
 * It also means the counter never reads "+1 more". Overflow only exists once there are more things
 * than slots, so the smallest it can ever be is two — one that did not fit plus the one whose row
 * the counter took. A "+1 more" occupying the row that the event itself could have occupied is a
 * line that costs exactly what it saves.
 *
 * No slots at all is a real answer rather than an error: a cell too short for one chip reports
 * everything as overflow, and the caller draws dots instead.
 */
internal fun monthCellPlan(clusters: List<EventCluster>, slots: Int): MonthCellPlan {
    if (clusters.size <= slots) return MonthCellPlan(shown = clusters, overflow = 0)

    val room = maxOf(0, slots - OVERFLOW_SLOT)

    return MonthCellPlan(shown = clusters.take(room), overflow = clusters.size - room)
}

data class MonthCellPlan(val shown: List<EventCluster>, val overflow: Int)

/** The row the "+n" chip takes when there is one. See [monthCellPlan]. */
private const val OVERFLOW_SLOT = 1

/**
 * How many chips fit in [available], stacked with [gap] between them.
 *
 * Measured rather than written down, because the answer legitimately differs between a 891dp phone
 * and a 640dp one, and a constant tuned on whichever device the feature was written on is how a
 * grid ends up drawing three chips into the room for two on somebody else's.
 *
 * The gap only exists *between* chips, which is why it is added to the numerator: two 30dp chips
 * with a 2dp gap need 62dp and not 64, and a floor division that charged for a trailing gap would
 * drop the second chip on a cell that had room for it.
 */
internal fun monthChipSlots(
    available: Dp,
    chip: Dp = MONTH_CHIP_HEIGHT,
    gap: Dp = MONTH_CHIP_GAP,
): Int = if (available < chip) 0 else ((available + gap) / (chip + gap)).toInt()

/**
 * How tall one month chip is on this device.
 *
 * Scaled by the font scale, deliberately, because the chip's height is what the slot arithmetic
 * divides by and the chip's *content* is two lines of text. A fixed 30dp would be honest at the
 * default scale and would clip both lines at 1.5×, which is the scale a large-text user actually
 * runs; scaling it means such a user gets two chips and a "+3 more" where somebody else gets three
 * chips — fewer events named, none of them unreadable, and nothing hidden without being counted.
 */
@Composable internal fun monthChipHeight(): Dp = MONTH_CHIP_HEIGHT * LocalDensity.current.fontScale

@Composable
private fun DayCell(
    day: MonthDay,
    isToday: Boolean,
    isOtherMonth: Boolean,
    isWeekend: Boolean,
    isSelected: Boolean,
    style: MonthCellStyle,
    onOpen: () -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = PlMailTheme.values
    val sentence = day.a11ySentence()
    val ruled = style == MonthCellStyle.CHIPS

    // Today first, then the selection, then the weekend, then the ordinary
    // case. The spill-in days of the neighbouring months are NOT given a fill
    // of their own -- their date and their events are dimmed instead, because a
    // fourth cell colour in a grid this small stops reading as information and
    // starts reading as noise.
    //
    // The borderless grid takes none of them but the selection: today is
    // already a filled circle round its own number, and a whole cell washed in
    // `accentSoft` under it says the same thing twice at ten times the area. A
    // selection has nothing else to say it, so it keeps its fill.
    val fill =
        when {
            isToday && ruled -> theme.colors.accentSoft
            isSelected -> theme.colors.hover
            isWeekend && ruled -> theme.colors.sunken
            ruled -> theme.colors.surface
            else -> Color.Transparent
        }

    Box(
        modifier =
            modifier
                .combinedClickable(onClick = onOpen, onLongClick = onCreate)
                // The whole cell is one target and one sentence. Three chips as
                // three stops is a month of four-character fragments, and the
                // selected state is said in words rather than left to the fill,
                // which is a colour a listener does not get.
                .clearAndSetSemantics {
                    contentDescription = sentence
                    selected = isSelected
                }
                .padding(theme.spacing.hair)
    ) {
        Column(
            modifier =
                Modifier.fillMaxSize()
                    // Square where the grid is ruled, so the fill meets the
                    // hairlines it is bounded by; rounded where it is not,
                    // because a hard-edged rectangle floating in whitespace is
                    // a cell border drawn in the selection colour.
                    .then(
                        if (ruled) Modifier
                        else Modifier.clip(RoundedCornerShape(theme.radii.small))
                    )
                    .background(fill)
                    .padding(theme.spacing.hair),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DayNumber(
                day = day.date.dayOfMonth,
                isToday = isToday,
                isOtherMonth = isOtherMonth,
            )

            when (style) {
                MonthCellStyle.CHIPS -> Chips(day = day, isOtherMonth = isOtherMonth)
                MonthCellStyle.DOTS -> Dots(day = day)
            }
        }
    }
}

/** The date, in the filled circle on today and bare everywhere else. */
@Composable
private fun DayNumber(day: Int, isToday: Boolean, isOtherMonth: Boolean) {
    val theme = PlMailTheme.values

    Box(
        modifier =
            Modifier.size(TODAY_PIP)
                .then(
                    if (isToday) Modifier.background(theme.colors.accent, CircleShape) else Modifier
                ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = day.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Normal,
            // The spill-in days are dimmed rather than hidden: their events are
            // real, and drawing them at full strength reads as part of this
            // month.
            color =
                when {
                    isToday -> theme.colors.onAccent
                    isOtherMonth -> theme.colors.inkFaint
                    else -> theme.colors.inkMuted
                },
        )
    }
}

/**
 * The titled chips, and the count of whatever did not fit.
 *
 * [BoxWithConstraints] rather than a height passed down from the grid: the cell's height is the
 * week row's height divided by nothing, but it is also the *measured* height after the weekday
 * strip, the banners and whatever the app bar took, and the only place that number is known is
 * inside the cell that got it.
 */
@Composable
private fun Chips(day: MonthDay, isOtherMonth: Boolean) {
    val theme = PlMailTheme.values
    val chip = monthChipHeight()

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val plan = monthCellPlan(day.clusters, monthChipSlots(maxHeight, chip = chip))

        Column(verticalArrangement = Arrangement.spacedBy(MONTH_CHIP_GAP)) {
            plan.shown.forEach { cluster ->
                MonthEventChip(
                    cluster = cluster,
                    dimmed = isOtherMonth,
                    modifier = Modifier.fillMaxWidth().height(chip),
                )
            }

            if (plan.overflow > 0) {
                Text(
                    text =
                        pluralStringResource(
                            R.plurals.calendar_more_count,
                            plan.overflow,
                            plan.overflow,
                        ),
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.colors.inkFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier =
                        Modifier.fillMaxWidth()
                            .height(chip)
                            .padding(horizontal = theme.spacing.hair),
                )
            }
        }
    }
}

/** A dot per meeting, and the count of the rest. The compact grid, and nothing else. */
@Composable
private fun Dots(day: MonthDay) {
    val theme = PlMailTheme.values
    val indicators = monthIndicators(day.clusters)

    Row(
        modifier = Modifier.padding(top = theme.spacing.hair),
        horizontalArrangement = Arrangement.spacedBy(theme.spacing.hair),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        indicators.shown.forEach { cluster ->
            CalendarDot(colors = cluster.dotColors(), size = MONTH_DOT)
        }

        // A fifth dot rather than "+n" in words: this style exists because the
        // cell is a third of its usual height, which is not room for a line of
        // text under four dots. It is deliberately drawn in ink rather than in
        // a calendar's colour -- it stands for "and more", and giving it one
        // member's colour would claim the rest are all on that calendar.
        if (indicators.overflow > 0) {
            Box(
                modifier =
                    Modifier.size(MONTH_DOT).clip(CircleShape).background(theme.colors.inkFaint)
            )
        }
    }
}

/**
 * What TalkBack reads for one month cell.
 *
 * The date, then how many meetings and the first few by name — which is the whole cell as a
 * sentence, because the alternative is three unlabelled chips per day and 126 stops in a month. An
 * empty day says it is empty rather than saying nothing: a cell that announces only its date leaves
 * a listener unable to tell "nothing on" from "the app did not read it out".
 *
 * The names are capped at [MONTH_DOTS] whatever the cell drew, on purpose. What is read out is not
 * a transcript of the pixels — the count before it is already the honest total, and a listener on a
 * busy Tuesday wants "eleven events" and a few names, not eleven titles they cannot interrupt.
 */
@Composable
private fun MonthDay.a11ySentence(): String {
    val date = date.format(DAY_HEADER)

    if (clusters.isEmpty()) return stringResource(R.string.calendar_day_a11y_empty, date)

    val titles = clusters.take(MONTH_DOTS).joinToString(", ") { it.primary.title }

    return pluralStringResource(
        R.plurals.calendar_day_a11y,
        clusters.size,
        date,
        clusters.size,
        titles,
    )
}

/**
 * Which colour is which calendar, under the month.
 *
 * **The one place the grid's colours are explained.** Every other view names the calendar in the
 * row it draws — the agenda's description says "on Arbeit", a detail screen spells it out — but a
 * month cell has room for a rail 3dp wide and nothing else, so without this the colours are a code
 * with no key. It sits under the grid rather than over it because it is a reference, consulted once
 * and then ignored, and a legend above the month would push the 27th off the bottom of every
 * screen.
 *
 * It also buys the grid something the owner asked for directly: six week rows over a legend are
 * shorter than six week rows over nothing, and the cells were reading as too tall.
 *
 * **Only the calendars that are actually drawn.** A calendar the user has un-ticked has no events
 * in this grid — `groupByDay` drops them — so listing it would be a key to a colour that is not on
 * the screen. Unnamed calendars are dropped for the same reason: a dot beside an empty label
 * explains nothing.
 *
 * **One sentence for TalkBack rather than N stops.** A screen reader walking eight bare calendar
 * names between the month and the bottom of the screen gets a list of words with nothing saying
 * what they are; the sentence says what they are once.
 */
/**
 * Which calendars the legend explains, out of everything the account holds.
 *
 * **The ones whose colours are actually on the grid, and only those.** A calendar the user has
 * un-ticked has no events in this month — `groupByDay` drops its rows before anything is drawn — so
 * listing it would be a key to a colour that appears nowhere, which is worse than no key at all: it
 * invites somebody to go looking for a green event that is not there. An unnamed calendar goes for
 * the same reason, since a dot beside an empty label explains nothing.
 *
 * The order is the one it was given. That comes from the DAO's `sortOrder`, which is the order the
 * web sidebar lists them in, and re-sorting here would be a second opinion about a question already
 * answered — the two surfaces disagreeing about which calendar comes first is exactly the kind of
 * small difference that reads as one of them being wrong.
 */
internal fun legendCalendars(calendars: List<CalendarEntity>): List<CalendarEntity> =
    calendars.filter {
        it.isVisible && it.name.isNotBlank()
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MonthLegend(calendars: List<CalendarEntity>, modifier: Modifier = Modifier) {
    val theme = PlMailTheme.values
    val shown = remember(calendars) { legendCalendars(calendars) }

    if (shown.isEmpty()) return

    val sentence =
        stringResource(R.string.calendar_legend_a11y, shown.joinToString(", ") { it.name })

    FlowRow(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(
                    horizontal = theme.spacing.gutter,
                    vertical = theme.spacing.small,
                )
                .clearAndSetSemantics { contentDescription = sentence },
        horizontalArrangement = Arrangement.spacedBy(theme.spacing.medium),
        verticalArrangement = Arrangement.spacedBy(theme.spacing.tiny),
    ) {
        shown.forEach { calendar ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(theme.spacing.tiny),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CalendarDot(
                    colors = listOf(calendarColor(calendar.color) ?: theme.colors.inkFaint),
                    size = MONTH_DOT,
                )

                Text(
                    text = calendar.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.colors.inkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Nine in the morning, which is where the web's per-cell create starts an event too. */
private val NEW_EVENT_HOUR: LocalTime = LocalTime.of(9, 0)

/**
 * How far the weekday strip's letters are opened out.
 *
 * Small caps at 11sp set solid read as a single word per column; the tracking is what turns three
 * letters back into an abbreviation.
 */
private val WEEKDAY_TRACKING = 0.08.sp

private val TODAY_PIP = 22.dp

/** Smaller than an agenda row's dot: four of them have to fit across a 55dp cell. */
private val MONTH_DOT = 7.dp
