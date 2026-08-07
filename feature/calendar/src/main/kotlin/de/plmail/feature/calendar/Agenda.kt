package de.plmail.feature.calendar

import androidx.compose.ui.graphics.Color
import de.plmail.core.database.AgendaRow
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * One day, with everything on it, already collapsed into the rows a person reads.
 *
 * [clusters] rather than raw occurrence rows, because one meeting can be held in two of them — see
 * [clusterRows] — and every view in this app, the agenda included, has to draw it once.
 */
data class AgendaDay(val date: LocalDate, val clusters: List<EventCluster>)

/**
 * Groups occurrence rows into days, collapsing duplicates as it goes.
 *
 * The DAO has already ordered them — by date, all-day first, then start, then id — so this
 * preserves the order it was given rather than sorting again. Re-sorting here would be a second
 * opinion about a question the query has already answered, and the two would disagree the day one
 * of them changed. (The `id` tiebreak is new and is what makes [clusterRows]'s choice of
 * representative stable; see that function.)
 *
 * **The collapse happens per day rather than over the whole window.** A cluster's members share a
 * start, so they share a day, and clustering inside the day is the same answer for less work — it
 * is also what the web does, where `CalendarRangeReader` clusters before the day walk.
 *
 * Rows on a calendar the user has hidden are dropped **here rather than in SQL**, because
 * `isVisible` is a display preference the server does not act on: `CalendarEvent/query` returns
 * events from hidden calendars just the same, so filtering at sync time would leave a calendar
 * ticked back on empty until the next refresh. Dropped **before** clustering, deliberately: a copy
 * on a hidden calendar was never drawn, so counting its colour into a merged dot would say the
 * meeting is on a calendar the user cannot see.
 */
fun groupByDay(rows: List<AgendaRow>): List<AgendaDay> =
    rows
        .filter { it.calendarIsVisible != false }
        .groupBy { it.date }
        .mapNotNull { (date, onThatDay) ->
            // A date the cache cannot parse is a row nothing can place. Dropped
            // rather than shown under a header saying "null": the occurrence
            // table is derived from a query the client can simply re-run.
            date.toLocalDateOrNull()?.let { AgendaDay(it, clusterRows(onThatDay)) }
        }
        .sortedBy { it.date }

/**
 * A calendar's own colour, parsed at draw time.
 *
 * This is the one colour in the app that is **not** a design token, and that is the server's shape
 * rather than an oversight: a label's colour is a token that resolves per theme, a calendar's is a
 * literal `#rrggbb` the user picked in the web UI. Normalising it into the token vocabulary would
 * mean choosing which of the two products' answers is the real one — and a phone that drew a
 * different colour from the web for the same calendar would be the visible result.
 *
 * Null for anything unparseable, so the caller can fall back to a token rather than this function
 * inventing a grey the user never chose.
 */
fun calendarColor(hex: String?): Color? {
    val digits = hex?.trim()?.removePrefix("#") ?: return null

    if (
        digits.length != HEX_DIGITS ||
            digits.any { !it.isDigit() && it.lowercaseChar() !in 'a'..'f' }
    ) {
        return null
    }

    return runCatching { Color(digits.toLong(radix = 16) or OPAQUE) }.getOrNull()
}

/** A `#rrggbb` has six digits. Three-digit CSS shorthand is not what the server writes. */
private const val HEX_DIGITS = 6

/** The alpha byte the six digits do not carry. */
private const val OPAQUE = 0xFF000000L

/**
 * When one occurrence starts, as a list draws it.
 *
 * The stored wall-clock time, **not** an instant converted into the device's zone. An occurrence is
 * stored as the local time in the event's own zone precisely so that resolving it at sync time
 * cannot bake in whichever zone the phone was in — and a day view places by local time anyway,
 * which is why the day header and this string have to come from the same value.
 *
 * Null for an all-day row and for a row whose start the cache cannot parse; the caller draws the
 * "All day" word rather than an empty column, because a blank in the time slot reads as a rendering
 * failure.
 */
fun startTimeOf(row: AgendaRow): LocalTime? =
    if (row.isAllDay) null else row.startLocal.toLocalDateTimeOrNull()?.toLocalTime()

fun endTimeOf(row: AgendaRow): LocalTime? =
    if (row.isAllDay) null else row.endLocal.toLocalDateTimeOrNull()?.toLocalTime()

/** The wire's LocalDateTime spelling, tolerant of the seconds being absent. */
internal fun String?.toLocalDateTimeOrNull(): LocalDateTime? =
    this?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }

internal fun String?.toLocalDateOrNull(): LocalDate? =
    this?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

/**
 * How long a timed occurrence lasts, in the words a person uses.
 *
 * "1 h 30 min" rather than "PT1H30M", and never a bare "0 min": a zero-length event is a real thing
 * on this server — an appointment saved with the same start and end — and saying nothing about its
 * length is better than saying it takes no time.
 */
fun durationWords(minutes: Long, hourWord: String, minuteWord: String): String? {
    if (minutes <= 0) return null

    val hours = minutes / MINUTES_IN_HOUR
    val rest = minutes % MINUTES_IN_HOUR

    return when {
        hours == 0L -> "$rest $minuteWord"
        rest == 0L -> "$hours $hourWord"
        else -> "$hours $hourWord $rest $minuteWord"
    }
}

private const val MINUTES_IN_HOUR = 60L

/**
 * The day header's date, and one occurrence's time.
 *
 * Localised by the platform rather than by a pattern written here, which is not a nicety: a pattern
 * of `HH:mm` gives a phone set to English (US) a 24-hour clock it never asked for, and `EEEE, d
 * MMMM` puts the day before the month on a locale that writes it the other way round. The full date
 * keeps the weekday, which is the half of an agenda header anybody actually reads — nobody counts
 * rows to work out which Thursday they are looking at.
 *
 * `Locale.getDefault()` is what these resolve against, and it is correct under a per-app locale
 * too: the platform sets it from the app's own locale list before any composition runs.
 */
internal val DAY_HEADER: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)

internal val CLOCK: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
