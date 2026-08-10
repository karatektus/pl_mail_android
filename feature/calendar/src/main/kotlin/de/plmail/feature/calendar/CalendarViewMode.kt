package de.plmail.feature.calendar

import de.plmail.core.data.CalendarWindow
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * The ways this app draws a calendar, and the one place that says what each of them covers.
 *
 * Four of them are the web's, with the same wire names, because the two surfaces are one product
 * and a person who reads their week in a browser should find a week here under the same word. The
 * names are also what is persisted — see `CalendarPrefsStore` — so they may not be renamed without
 * stranding whatever is already in somebody's preferences file.
 *
 * **[MONTH_AGENDA] is the phone's own, and deliberately so.** A month grid on a 411dp phone can
 * carry two or three titled chips per cell before it starts counting the rest, which is enough to
 * recognise a day and not enough to read one; the browser never had that problem because its cells
 * are three times the size. Splitting the screen — a grid you scan, a list you read — is the answer
 * to a constraint that only exists here, so inventing a fifth view is honest where copying a fifth
 * *web* view would not be. Its wire name is one the web will never write, and
 * [CalendarViewMode.fromWire] degrades anything unknown to the default, so neither surface can
 * strand the other.
 *
 * **A window is one request.** Every method here answers with a [CalendarWindow], because the round
 * trip discipline the `expandRecurrences` adoption bought is that a *visible span* costs one
 * request whatever recurs inside it — so a week is one window and not seven, and a month is one
 * window and not thirty-one. Anything that asked per day would put a Raspberry Pi through thirty
 * round trips to page one screen backwards.
 */
enum class CalendarViewMode(val wire: String) {
    AGENDA("agenda"),
    DAY("day"),
    WEEK("week"),
    MONTH("month"),
    MONTH_AGENDA("month-agenda");

    /** Whether this view is a page that steps, as opposed to a rolling list that scrolls. */
    val isPaged: Boolean
        get() = this != AGENDA

    /**
     * Whether this view draws the six-week grid.
     *
     * Both month views share a window, a step and a heading, and every one of those is a place
     * where a `== MONTH` written before the split would silently do the wrong thing for the new
     * view. One predicate rather than five comparisons, so adding the third month view — if there
     * ever is one — is not another sweep of the file.
     */
    val isMonthGrid: Boolean
        get() = this == MONTH || this == MONTH_AGENDA

    /**
     * The span this view draws when it is anchored on [anchor].
     *
     * The agenda is deliberately the odd one: it is a rolling list *from* its anchor rather than a
     * page containing it, which is why it has no previous and no next — see [step].
     *
     * The month asks for six whole weeks rather than the month itself, and that is what the grid
     * draws: always six, never five-or-six, so paging through the year does not change the grid's
     * height under the finger. The leading and trailing part-weeks are real days with real events
     * on them and asking about a window smaller than the one being drawn would leave them empty.
     */
    fun window(anchor: LocalDate, firstDayOfWeek: DayOfWeek): CalendarWindow =
        when (this) {
            AGENDA -> CalendarWindow(from = anchor, to = anchor.plusDays(AGENDA_DAYS))
            DAY -> CalendarWindow(from = anchor, to = anchor.plusDays(1))
            WEEK -> weekStart(anchor, firstDayOfWeek).let { CalendarWindow(it, it.plusDays(7)) }
            MONTH,
            MONTH_AGENDA ->
                monthGridStart(anchor, firstDayOfWeek).let {
                    CalendarWindow(it, it.plusDays(MONTH_GRID_DAYS))
                }
        }

    /**
     * Where the anchor lands after one step.
     *
     * A month steps by a *month* rather than by 42 days, which is the whole reason this is not
     * arithmetic on the window: stepping a six-week grid by its own length would skip a fortnight
     * every time. `plusMonths` also does the right thing off the 31st, landing on the 30th or the
     * 28th rather than overflowing into the month after next.
     *
     * The agenda does not step at all. It is a rolling list whose first day is today, and a
     * Previous on it would be a control that scrolls — which the list already does.
     */
    fun step(anchor: LocalDate, forward: Boolean): LocalDate {
        val direction = if (forward) 1L else -1L

        return when (this) {
            AGENDA -> anchor
            DAY -> anchor.plusDays(direction)
            WEEK -> anchor.plusWeeks(direction)
            MONTH,
            MONTH_AGENDA -> anchor.plusMonths(direction)
        }
    }

    companion object {
        /**
         * What an install that has never chosen gets, and what an unreadable choice falls back to.
         */
        val Default = AGENDA

        /**
         * Thirty days, which is what the web's agenda covers.
         *
         * Matching it rather than choosing a number: the two surfaces answering "what is coming up"
         * differently is the kind of disagreement a user reads as one of them being broken.
         */
        const val AGENDA_DAYS = 30L

        /** Six weeks. See [window]. */
        const val MONTH_GRID_DAYS = 42L

        /**
         * How many days a month grid's row is. Written down because the grid and the window share
         * it.
         */
        const val WEEK_DAYS = 7

        /** Unknown is the default, never a crash — see `CalendarPrefsStore`. */
        fun fromWire(wire: String?): CalendarViewMode =
            entries.firstOrNull { it.wire == wire } ?: Default
    }
}

/** The [firstDayOfWeek] on or before [day]. */
internal fun weekStart(day: LocalDate, firstDayOfWeek: DayOfWeek): LocalDate =
    day.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))

/**
 * The first cell of the six-week grid containing [day]'s month.
 *
 * The week start on or before the first of the month, which is what puts the 1st in its own weekday
 * column. Six weeks from there always covers the month: the longest case is a 31-day month
 * beginning on the last day of a week, which is 6 + 31 = 37 days, well inside 42.
 */
internal fun monthGridStart(day: LocalDate, firstDayOfWeek: DayOfWeek): LocalDate =
    weekStart(day.with(TemporalAdjusters.firstDayOfMonth()), firstDayOfWeek)

/**
 * The days a view covers, in order, whether anything is on them or not.
 *
 * A grid draws its empty cells — that is the difference between a grid and an agenda — so the
 * columns come from the window rather than from what the query answered.
 */
internal fun CalendarWindow.days(): List<LocalDate> =
    generateSequence(from) { it.plusDays(1) }.takeWhile { it < to }.toList()

/**
 * The four date patterns a view heading and a grid column need, arranged the way a locale writes
 * them.
 *
 * **Handed in rather than built here, and that is not indirection for its own sake.** The only
 * arrangement of arbitrary date fields available on minSdk 31 is
 * `android.text.format.DateFormat.getBestDateTimePattern` — `DateTimeFormatter.ofLocalizedPattern`
 * is API 34 — and reaching for an Android class from a top-level `val` puts it out of reach of a
 * plain JVM test, which is where the *rule* below is worth pinning. So the platform builds these
 * once per composition (see `rememberCalendarFormats`) and the rule is a pure function of them.
 *
 * A hand-written `ofPattern("MMMM yyyy")` would be the obvious shortcut and is the same mistake the
 * day header already avoids: it happens to read correctly in English and in German and is neither
 * the order nor the wording a Japanese or Hungarian locale uses.
 */
data class CalendarFormats(
    /** A whole date, medium — "6. Aug. 2026". */
    val date: DateTimeFormatter,
    /** Month and year — "August 2026". */
    val month: DateTimeFormatter,
    /** A bare day number — "27". */
    val dayOfMonth: DateTimeFormatter,
    /** Month and day, no year — "27. Jul." */
    val monthAndDay: DateTimeFormatter,
    /** The abbreviated weekday over a grid column — "Mi.". */
    val weekday: DateTimeFormatter,
    /**
     * A bare hour for the time grid's axis — "9 AM", or "09" on a 24-hour locale.
     *
     * Not the short *time* format the rows use. "10:00 AM" needs about seventy density-independent
     * pixels and the gutter is fifty-six; widening the gutter instead would take the same width off
     * seven day columns that are already down to fifty each. The axis says which hour, and the
     * minutes on it are always zero.
     */
    val hour: DateTimeFormatter,
) {
    companion object {
        /**
         * Formatters built from explicit patterns, for a test and for nothing else.
         *
         * The app's come from the platform's locale data; these exist so the week-range rule can be
         * asserted without a Robolectric runtime standing behind `DateFormat`.
         */
        fun ofPatterns(
            date: String = "d MMM yyyy",
            month: String = "MMMM yyyy",
            dayOfMonth: String = "d",
            monthAndDay: String = "d MMM",
            weekday: String = "EEE",
            hour: String = "h a",
            locale: Locale = Locale.ENGLISH,
        ) =
            CalendarFormats(
                date = DateTimeFormatter.ofPattern(date, locale),
                month = DateTimeFormatter.ofPattern(month, locale),
                dayOfMonth = DateTimeFormatter.ofPattern(dayOfMonth, locale),
                monthAndDay = DateTimeFormatter.ofPattern(monthAndDay, locale),
                weekday = DateTimeFormatter.ofPattern(weekday, locale),
                hour = DateTimeFormatter.ofPattern(hour, locale),
            )
    }
}

/**
 * The heading over a view: where you are, in as few words as say it.
 *
 * A week is a **range**, and printing its anchor over a grid running from the 27th of July to the
 * 2nd of August would simply be wrong. The near end drops what the far end repeats — the year when
 * both share it, the month too when the week does not cross one — which is the web toolbar's own
 * rule, carried across so the two surfaces name the same week the same way.
 *
 * The agenda's heading is its first day rather than a range, because it is a rolling list and the
 * only honest thing to say about where it *ends* is "thirty days from here", which the horizon
 * footer already says better.
 */
internal fun CalendarViewMode.heading(
    anchor: LocalDate,
    firstDayOfWeek: DayOfWeek,
    formats: CalendarFormats,
): String =
    when (this) {
        CalendarViewMode.AGENDA,
        CalendarViewMode.DAY -> anchor.format(formats.date)
        CalendarViewMode.MONTH,
        CalendarViewMode.MONTH_AGENDA -> anchor.format(formats.month)
        CalendarViewMode.WEEK -> {
            val from = weekStart(anchor, firstDayOfWeek)
            val to = from.plusDays(6)
            val near =
                when {
                    from.year != to.year -> formats.date
                    from.month != to.month -> formats.monthAndDay
                    else -> formats.dayOfMonth
                }

            "${from.format(near)} – ${to.format(formats.date)}"
        }
    }
