package de.plmail.feature.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.plmail.core.designsystem.PlMailTheme
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Day and week, as a time grid: hours down the side, an event drawn where it is and as long as it
 * is.
 *
 * One composable for both, because a day *is* a week of one column — the web reaches the same
 * conclusion in `_view_timegrid.html.twig`, and the day the two were written separately is the day
 * they would start disagreeing about where 09:00 is.
 *
 * **The all-day band is always drawn, even when it is empty.** Showing it only on days that have
 * one makes the grid jump by a row as you page through, and the band is a permanent part of the
 * axis — it is where "this event has no time" is expressible at all.
 *
 * **A seven-day week is tight on a phone, and what a block spends that width on is the decision.**
 * Seven columns share whatever is left after the hour gutter, which at 411dp is about fifty each. A
 * block there used to spend it on a dot and a clock and had nothing left for the title, so the week
 * read as a column of times with no meetings attached; it now spends the whole width on the title,
 * wrapped over as many lines as the block is tall, and offers the clock the line underneath only
 * when there is one. See `EventBlock`. The tightness is not solved by a horizontal scroll, which
 * would need the heading row, the all-day band and the hours kept in step; what answers the rest of
 * it is that neither width is forced on anybody — Day view is one tap away in the switcher and is a
 * single full-width column, where the same block draws the location too.
 *
 * **Creating from the grid is a long press**, not a tap, and that is a platform choice rather than
 * a transcription of the web's double-click. The reasoning is the web's, though: a single tap on
 * the background has to be told apart from the end of a scroll fling on every release, and getting
 * that wrong opens an editor every time somebody scrolls to the evening. Android has no double-tap
 * idiom for "act here" and does have a long press, which is what Google Calendar's own grid uses.
 * The haptic tick is what makes it discoverable at all — without it a long press that opened a
 * screen would feel like a mis-tap. **The `+` action in the top bar stays and is the real
 * accessible route**: a long press is not a gesture TalkBack's explore-by-touch offers.
 */
@Composable
internal fun TimeGrid(
    days: List<DayGrid>,
    today: LocalDate,
    now: LocalTime,
    formats: CalendarFormats,
    onOpen: (EventCluster) -> Unit,
    onCreateAt: (LocalDateTime) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    val density = LocalDensity.current

    // Opened on the working morning rather than at midnight. A grid that starts
    // at 00:00 spends its first screen on the eight hours nothing is ever in,
    // and the first thing every user does is scroll past them. Keyed on the
    // window's first day, so paging re-opens there and a scroll inside one day
    // is not fought.
    LaunchedEffect(days.firstOrNull()?.date) {
        scroll.scrollTo(with(density) { (HOUR_HEIGHT * OPENING_HOUR).roundToPx() })
    }

    Column(modifier = modifier.fillMaxSize()) {
        DayHeadings(days = days, today = today, weekday = formats.weekday)

        AllDayBand(days = days, today = today, onOpen = onOpen)

        Box(modifier = Modifier.fillMaxSize().verticalScroll(scroll)) {
            Row(modifier = Modifier.fillMaxWidth().height(HOUR_HEIGHT * HOURS_IN_DAY)) {
                HourGutter(formats.hour)

                days.forEach { day ->
                    DayColumn(
                        day = day,
                        isToday = day.date == today,
                        now = now,
                        onOpen = onOpen,
                        onCreateAt = onCreateAt,
                        modifier = Modifier.weight(1f).fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun DayHeadings(days: List<DayGrid>, today: LocalDate, weekday: DateTimeFormatter) {
    val theme = PlMailTheme.values

    Row(
        modifier =
            Modifier.fillMaxWidth()
                .background(theme.colors.surface)
                .padding(vertical = theme.spacing.tiny)
    ) {
        Box(modifier = Modifier.width(GUTTER))

        days.forEach { day ->
            val isToday = day.date == today

            Row(
                modifier = Modifier.weight(1f).columnRule(theme.colors.line, theme.spacing.hair),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = day.date.format(weekday),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isToday) theme.colors.accent else theme.colors.inkFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )

                Box(
                    modifier =
                        Modifier.padding(start = theme.spacing.tiny)
                            .size(TODAY_PIP)
                            .then(
                                if (isToday) Modifier.background(theme.colors.accent, CircleShape)
                                else Modifier
                            ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = day.date.dayOfMonth.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isToday) theme.colors.onAccent else theme.colors.inkMuted,
                    )
                }
            }
        }
    }
}

/**
 * The band above the axis, for everything that has no time.
 *
 * Capped rather than unbounded: a week with six all-day rows on one day would otherwise push the
 * hours off the screen entirely. What overflows is reachable by opening the day, which is the same
 * answer the month grid's overflow count gives.
 */
@Composable
private fun AllDayBand(days: List<DayGrid>, today: LocalDate, onOpen: (EventCluster) -> Unit) {
    val theme = PlMailTheme.values

    Row(
        modifier =
            Modifier.fillMaxWidth()
                .background(theme.colors.sunken)
                .heightIn(min = ALL_DAY_MIN)
                .padding(vertical = theme.spacing.hair)
    ) {
        Text(
            text = stringResource(R.string.calendar_all_day),
            style = MaterialTheme.typography.labelSmall,
            color = theme.colors.inkFaint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(GUTTER).padding(horizontal = theme.spacing.tiny),
        )

        days.forEach { day ->
            Column(
                modifier =
                    Modifier.weight(1f)
                        .columnRule(theme.colors.line, theme.spacing.hair)
                        .then(
                            if (day.date == today) Modifier.background(theme.colors.accentSoft)
                            else Modifier
                        ),
                verticalArrangement = Arrangement.spacedBy(theme.spacing.hair),
            ) {
                day.allDay.take(ALL_DAY_MAX).forEach { cluster ->
                    AllDayChip(
                        cluster = cluster,
                        onClick = { onOpen(cluster) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (day.allDay.size > ALL_DAY_MAX) {
                    Overflow(count = day.allDay.size - ALL_DAY_MAX)
                }
            }
        }
    }
}

/** The hour labels, sitting **on** their own line rather than inside the row below it. */
@Composable
private fun HourGutter(hourFormat: DateTimeFormatter) {
    val theme = PlMailTheme.values

    Column(modifier = Modifier.width(GUTTER).fillMaxSize()) {
        (0 until HOURS_IN_DAY).forEach { hour ->
            Box(modifier = Modifier.fillMaxWidth().height(HOUR_HEIGHT)) {
                // Midnight is skipped: its label would sit half above the top of
                // the grid, and nothing needs telling that a day starts at 00:00.
                if (hour > 0) {
                    Text(
                        text = LocalTime.of(hour, 0).format(hourFormat),
                        style = MaterialTheme.typography.labelSmall,
                        color = theme.colors.inkFaint,
                        maxLines = 1,
                        modifier =
                            Modifier.align(Alignment.TopEnd)
                                .offset(y = -HOUR_LABEL_LIFT)
                                .padding(end = theme.spacing.tiny),
                    )
                }
            }
        }
    }
}

/**
 * One day's column: the hour lines, the blocks over them, and the now line on today.
 *
 * The blocks sit in a layer over the lines rather than between them, so the lines stay visible
 * behind and between the blocks and the axis is readable through a busy morning.
 *
 * Every block is positioned by **offset** rather than by a nested layout of lanes, and that is
 * load-bearing: a block's position must not depend on the block before it having been measured,
 * which is exactly what a `Row` of lanes would make it depend on, and one floored fifteen-minute
 * block would then push its neighbour off its own hour.
 */
@Composable
private fun DayColumn(
    day: DayGrid,
    isToday: Boolean,
    now: LocalTime,
    onOpen: (EventCluster) -> Unit,
    onCreateAt: (LocalDateTime) -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = PlMailTheme.values
    val haptics = LocalHapticFeedback.current

    BoxWithConstraints(
        modifier =
            modifier
                .columnRule(theme.colors.line, theme.spacing.hair)
                .then(if (isToday) Modifier.background(theme.colors.accentSoft) else Modifier)
                .pointerInput(day.date) {
                    detectTapGestures(
                        onLongPress = { at ->
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onCreateAt(slotAt(day.date, at.y / size.height.toFloat()))
                        }
                    )
                }
    ) {
        val height = maxHeight
        val width = maxWidth

        Column(modifier = Modifier.fillMaxSize()) {
            repeat(HOURS_IN_DAY) {
                Box(modifier = Modifier.fillMaxWidth().height(HOUR_HEIGHT)) {
                    Box(
                        modifier =
                            Modifier.fillMaxWidth()
                                .height(theme.spacing.hair)
                                .align(Alignment.BottomStart)
                                .background(theme.colors.line)
                    )
                }
            }
        }

        day.timed.forEach { placed ->
            val lane = width / placed.lanes

            EventBlock(
                placed = placed,
                onClick = { onOpen(placed.cluster) },
                modifier =
                    Modifier.offset(x = lane * placed.lane, y = height * placed.top)
                        .width(lane)
                        .height(maxOf(height * placed.height, BLOCK_MIN_HEIGHT)),
            )
        }

        if (isToday) NowLine(now = now, height = height)
    }
}

/**
 * The current time, drawn once at the minute the view was composed.
 *
 * Never on a timer, which is the same decision the web makes and for a sharper reason here: a
 * recomposition a minute, forever, on a screen somebody has left open is exactly the kind of
 * background work the rest of this app refuses. The line is redrawn whenever anything else moves
 * the view — a refresh, a page, a return to the foreground — which is every moment somebody is
 * looking at it.
 */
@Composable
private fun NowLine(now: LocalTime, height: Dp) {
    val theme = PlMailTheme.values
    val fraction = now.toSecondOfDay().toFloat() / SECONDS_IN_DAY

    Box(
        modifier =
            Modifier.fillMaxWidth()
                .offset(y = height * fraction)
                .height(NOW_LINE)
                .background(theme.colors.danger)
                // Decoration over a fact the rest of the screen already states.
                // A screen reader stopping on "now" between two meetings costs a
                // swipe and says nothing.
                .clearAndSetSemantics {}
    )
}

@Composable
private fun Overflow(count: Int) {
    val theme = PlMailTheme.values
    val text = pluralStringResource(R.plurals.calendar_more_count, count, count)

    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = theme.colors.inkFaint,
        maxLines = 1,
        modifier =
            Modifier.padding(horizontal = theme.spacing.tiny).clearAndSetSemantics {
                contentDescription = text
            },
    )
}

/**
 * The hairline down the left of a day column.
 *
 * Drawn rather than laid out, so it costs no width: seven columns each giving up a device pixel to
 * a border is seven pixels off a week that has about fifty per column to start with. It is also
 * what makes the headings, the all-day band and the hours read as one grid rather than three rows
 * that happen to be the same width.
 */
private fun Modifier.columnRule(color: Color, width: Dp): Modifier = drawBehind {
    drawLine(
        color = color,
        start = Offset.Zero,
        end = Offset(0f, size.height),
        strokeWidth = width.toPx(),
    )
}

/** How tall one hour is. Fixed rather than fitted, so 24 hours scroll instead of squashing. */
internal val HOUR_HEIGHT = 56.dp

/** How wide the hour gutter is. Sized for "12:00 AM" rather than for "13:00". */
private val GUTTER = 56.dp

/** The floor a fifteen-minute block needs to be readable. See `EventBlock`. */
private val BLOCK_MIN_HEIGHT = 28.dp

private val ALL_DAY_MIN = 28.dp

/** How many all-day rows a column shows before it counts the rest. */
private const val ALL_DAY_MAX = 2

private val NOW_LINE = 2.dp

private val TODAY_PIP = 22.dp

/** Half a label's height, so "10:00" sits on the 10:00 line rather than under it. */
private val HOUR_LABEL_LIFT = 6.dp

private const val HOURS_IN_DAY = 24

private const val SECONDS_IN_DAY = 24 * 60 * 60

/** Where the grid opens. Early enough to see an 08:00, late enough to skip the night. */
private const val OPENING_HOUR = 7
