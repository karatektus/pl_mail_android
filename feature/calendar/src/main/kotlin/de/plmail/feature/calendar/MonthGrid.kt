package de.plmail.feature.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.plmail.core.designsystem.PlMailTheme
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * The month: six weeks of day cells, each carrying a coloured mark per meeting.
 *
 * **Always six weeks, never five-or-six**, which is why the window is 42 days from the week start
 * on or before the first — see `CalendarViewMode.window`. A grid that changed height between months
 * would move every cell under the finger as you page through it, and the surrounding layout would
 * have to reserve for the taller case anyway.
 *
 * **Dots rather than the web's titled chips**, and that is the one place this view deliberately
 * departs from the browser. A month cell on a 411dp phone is about 55dp wide; a chip there fits
 * roughly four characters, so a column of them says "Stan…", "Quar…", "Zahn…" — three marks that
 * carry no more information than three dots and read as broken text. What a dot *does* carry at
 * that size is the calendar's own colour, which is the thing a month grid is scanned for. The
 * titles are one tap away in the day view, and TalkBack gets the whole day as a sentence rather
 * than the dots.
 *
 * **Tapping a day opens that day**, in Day view, rather than scoping the agenda to it. Two reasons:
 * the day view is where a month's ambiguity actually resolves (which of these four is at nine, and
 * do any of them overlap), and it means the "more than fits" case and the ordinary case land in the
 * same place — the web's own "+n" link goes to `view: day` for exactly that reason. It also leaves
 * the switcher telling the truth about where the user now is, which an agenda secretly scoped to
 * one day would not.
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
) {
    val theme = PlMailTheme.values

    Column(modifier = modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = theme.spacing.tiny)) {
            days.take(CalendarViewMode.WEEK_DAYS).forEach { day ->
                Text(
                    text = day.date.format(weekday),
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.colors.inkFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier =
                        Modifier.weight(1f)
                            .padding(horizontal = theme.spacing.tiny)
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
            Row(modifier = Modifier.fillMaxWidth().weight(1f).background(theme.colors.line)) {
                week.forEach { day ->
                    DayCell(
                        day = day,
                        isToday = day.date == today,
                        isOtherMonth = day.date.monthValue != anchorMonth,
                        isWeekend =
                            day.date.dayOfWeek == DayOfWeek.SATURDAY ||
                                day.date.dayOfWeek == DayOfWeek.SUNDAY,
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

/** One day of a month grid, with what is on it already collapsed into rows a person reads. */
data class MonthDay(val date: LocalDate, val clusters: List<EventCluster>)

/**
 * How many marks a cell draws before it counts the rest.
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

@Composable
private fun DayCell(
    day: MonthDay,
    isToday: Boolean,
    isOtherMonth: Boolean,
    isWeekend: Boolean,
    onOpen: () -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = PlMailTheme.values
    val indicators = monthIndicators(day.clusters)
    val sentence = day.a11ySentence()

    // Today first, then the weekend, then the ordinary case. The spill-in days
    // of the neighbouring months are NOT given a fill of their own -- their date
    // and their dots are dimmed instead, because a fourth cell colour in a grid
    // this small stops reading as information and starts reading as noise.
    val fill =
        when {
            isToday -> theme.colors.accentSoft
            isWeekend -> theme.colors.sunken
            else -> theme.colors.surface
        }

    Box(
        modifier =
            modifier
                .combinedClickable(onClick = onOpen, onLongClick = onCreate)
                // The whole cell is one target and one sentence. Four dots as
                // four stops is a month of unlabelled graphics.
                .clearAndSetSemantics { contentDescription = sentence }
                .padding(theme.spacing.hair)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().background(fill).padding(theme.spacing.tiny),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(theme.spacing.tiny),
        ) {
            Box(
                modifier =
                    Modifier.size(TODAY_PIP)
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
                    // The spill-in days are dimmed rather than hidden: their
                    // events are real, and drawing them at full strength reads
                    // as part of this month.
                    color =
                        when {
                            isToday -> theme.colors.onAccent
                            isOtherMonth -> theme.colors.inkFaint
                            else -> theme.colors.inkMuted
                        },
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(theme.spacing.hair),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                indicators.shown.forEach { cluster ->
                    CalendarDot(colors = cluster.dotColors(), size = MONTH_DOT)
                }
            }

            if (indicators.overflow > 0) {
                Text(
                    text =
                        pluralStringResource(
                            R.plurals.calendar_more_count,
                            indicators.overflow,
                            indicators.overflow,
                        ),
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.colors.inkFaint,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * What TalkBack reads for one month cell.
 *
 * The date, then how many meetings and the first few by name — which is the whole cell as a
 * sentence, because the alternative is four unlabelled graphics per day and 168 stops in a month.
 * An empty day says it is empty rather than saying nothing: a cell that announces only its date
 * leaves a listener unable to tell "nothing on" from "the app did not read it out".
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

/** Nine in the morning, which is where the web's per-cell create starts an event too. */
private val NEW_EVENT_HOUR: LocalTime = LocalTime.of(9, 0)

private val TODAY_PIP = 22.dp

/** Smaller than an agenda row's dot: four of them have to fit across a 55dp cell. */
private val MONTH_DOT = 7.dp
