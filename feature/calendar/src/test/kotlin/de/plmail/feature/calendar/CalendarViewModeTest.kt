package de.plmail.feature.calendar

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * What each view asks the server for, and where a step lands.
 *
 * These are the statements that decide how many round trips a calendar costs. A week that computed
 * seven one-day windows would draw identically and cost seven times as much against a machine with
 * one PHP worker pool — which is exactly the trap the `expandRecurrences` adoption was meant to
 * close — so the assertions below are about the *window*, not about what is drawn from it.
 */
class CalendarViewModeTest {

    private val monday = DayOfWeek.MONDAY
    private val sunday = DayOfWeek.SUNDAY

    /** A Thursday, deliberately: an anchor that is already a week start proves nothing. */
    private val thursday = LocalDate.parse("2026-08-06")

    @Test
    fun `a day is one day`() {
        val window = CalendarViewMode.DAY.window(thursday, monday)

        assertEquals(thursday, window.from)
        assertEquals(thursday.plusDays(1), window.to)
    }

    /**
     * One window of seven days, and that is the whole point of this test.
     *
     * A week is one request whatever recurs in it. Seven windows would be seven.
     */
    @Test
    fun `a week is one window of seven days, starting on the locale's first day`() {
        val window = CalendarViewMode.WEEK.window(thursday, monday)

        assertEquals(LocalDate.parse("2026-08-03"), window.from)
        assertEquals(LocalDate.parse("2026-08-10"), window.to)
    }

    /** The week start is the device's, not a hardcoded Monday. */
    @Test
    fun `a week can start on a Sunday`() {
        val window = CalendarViewMode.WEEK.window(thursday, sunday)

        assertEquals(LocalDate.parse("2026-08-02"), window.from)
        assertEquals(LocalDate.parse("2026-08-09"), window.to)
    }

    /**
     * Six whole weeks, always, from the week start on or before the first.
     *
     * Never five-or-six: a grid that changed height between months would move every cell under the
     * finger as somebody pages through it.
     */
    @Test
    fun `a month is always six weeks from the week containing the first`() {
        val window = CalendarViewMode.MONTH.window(thursday, monday)

        // 1 August 2026 is a Saturday, so the grid opens on Monday 27 July.
        assertEquals(LocalDate.parse("2026-07-27"), window.from)
        assertEquals(42, daysBetween(window.from, window.to))
    }

    /** Every month of a decade fits inside its own six-week grid, in both week conventions. */
    @Test
    fun `six weeks always covers the whole month`() {
        listOf(monday, sunday).forEach { firstDay ->
            var day = LocalDate.parse("2020-01-01")

            while (day < LocalDate.parse("2030-01-01")) {
                val window = CalendarViewMode.MONTH.window(day, firstDay)
                val lastOfMonth = day.withDayOfMonth(day.lengthOfMonth())

                assertTrue(
                    !window.from.isAfter(day.withDayOfMonth(1)) && window.to.isAfter(lastOfMonth),
                    "$day under $firstDay: $window does not cover its month",
                )
                assertEquals(42, daysBetween(window.from, window.to))

                day = day.plusMonths(1)
            }
        }
    }

    /** The agenda is a rolling month from its anchor, matching the web's own agenda. */
    @Test
    fun `the agenda is thirty days from its anchor`() {
        val window = CalendarViewMode.AGENDA.window(thursday, monday)

        assertEquals(thursday, window.from)
        assertEquals(30, daysBetween(window.from, window.to))
    }

    /**
     * A month steps by a month, not by the length of its own grid.
     *
     * Stepping a six-week grid by 42 days would skip a fortnight every time — the kind of defect
     * that only shows up after three taps, by which point it reads as the calendar being broken
     * rather than as arithmetic.
     */
    @Test
    fun `a month steps by a month`() {
        assertEquals(
            LocalDate.parse("2026-09-06"),
            CalendarViewMode.MONTH.step(thursday, forward = true),
        )
        assertEquals(
            LocalDate.parse("2026-07-06"),
            CalendarViewMode.MONTH.step(thursday, forward = false),
        )
    }

    /**
     * `plusMonths` off the 31st lands on the last day of the shorter month rather than overflowing.
     */
    @Test
    fun `stepping off the thirty-first does not overflow`() {
        assertEquals(
            LocalDate.parse("2026-02-28"),
            CalendarViewMode.MONTH.step(LocalDate.parse("2026-01-31"), forward = true),
        )
    }

    @Test
    fun `a day steps by a day and a week by a week`() {
        assertEquals(
            LocalDate.parse("2026-08-07"),
            CalendarViewMode.DAY.step(thursday, forward = true),
        )
        assertEquals(
            LocalDate.parse("2026-07-30"),
            CalendarViewMode.WEEK.step(thursday, forward = false),
        )
    }

    /**
     * The agenda does not step at all.
     *
     * It is a rolling list from today and scrolling already moves through it; a Previous would be a
     * control whose only effect is to scroll, on a list that scrolls.
     */
    @Test
    fun `the agenda does not step`() {
        assertEquals(thursday, CalendarViewMode.AGENDA.step(thursday, forward = true))
        assertEquals(thursday, CalendarViewMode.AGENDA.step(thursday, forward = false))
    }

    /** A view a newer build wrote is the default rather than a crash. See `CalendarPrefsStore`. */
    @Test
    fun `an unknown or absent stored view is the default`() {
        assertEquals(CalendarViewMode.Default, CalendarViewMode.fromWire(null))
        assertEquals(CalendarViewMode.Default, CalendarViewMode.fromWire("quarter"))
        assertEquals(CalendarViewMode.WEEK, CalendarViewMode.fromWire("week"))
    }

    /**
     * The mixture view is a month, in every way that costs a request.
     *
     * Its window, its step and its heading are the month's — which is what makes switching between
     * the two free: the six weeks are already on the phone, so the toggle redraws them rather than
     * asking the server for the same span in a different shape.
     */
    @Test
    fun `the mixture view is a month in window, step and heading`() {
        val formats = CalendarFormats.ofPatterns()

        assertEquals(
            CalendarViewMode.MONTH.window(thursday, monday),
            CalendarViewMode.MONTH_AGENDA.window(thursday, monday),
        )
        assertEquals(
            CalendarViewMode.MONTH.step(thursday, forward = true),
            CalendarViewMode.MONTH_AGENDA.step(thursday, forward = true),
        )
        assertEquals(
            "August 2026",
            CalendarViewMode.MONTH_AGENDA.heading(thursday, monday, formats),
        )
        assertTrue(CalendarViewMode.MONTH_AGENDA.isPaged)
    }

    /**
     * Both month views answer to the predicate the shared behaviour hangs off, and nothing else.
     */
    @Test
    fun `the month predicate names both month views and no other`() {
        assertEquals(
            listOf(CalendarViewMode.MONTH, CalendarViewMode.MONTH_AGENDA),
            CalendarViewMode.entries.filter { it.isMonthGrid },
        )
    }

    /**
     * Every view survives being written to the preferences file and read back.
     *
     * The wire names *are* the persistence — see `CalendarPrefsStore`, which stores the raw string
     * — so a name that did not round trip would silently put somebody back on the agenda on their
     * next launch, with nothing to tell them their choice had been dropped. Over `entries` rather
     * than a list written here, so a sixth view cannot be added without this test covering it.
     */
    @Test
    fun `every view's stored name round trips`() {
        CalendarViewMode.entries.forEach {
            assertEquals(it, CalendarViewMode.fromWire(it.wire), "${it.name} does not round trip")
        }

        assertEquals(
            CalendarViewMode.entries.size,
            CalendarViewMode.entries.map { it.wire }.toSet().size,
            "two views share a wire name",
        )
    }

    /** Every day of the window is a column, whether anything is on it or not. */
    @Test
    fun `a window enumerates every day it covers`() {
        val days = CalendarViewMode.WEEK.window(thursday, monday).days()

        assertEquals(7, days.size)
        assertEquals(LocalDate.parse("2026-08-03"), days.first())
        assertEquals(LocalDate.parse("2026-08-09"), days.last())
    }

    /** A week heading is a range, and drops from the near end whatever the far end repeats. */
    @Test
    fun `a week heading collapses what both ends share`() {
        val formats = CalendarFormats.ofPatterns()

        // Inside one month: the near end is a bare day number.
        assertEquals(
            "3 – 9 Aug 2026",
            CalendarViewMode.WEEK.heading(thursday, monday, formats),
        )

        // Across a month boundary, same year: the near end keeps its month.
        assertEquals(
            "27 Jul – 2 Aug 2026",
            CalendarViewMode.WEEK.heading(LocalDate.parse("2026-07-30"), monday, formats),
        )

        // Across a year boundary: the near end keeps its year too.
        assertEquals(
            "28 Dec 2026 – 3 Jan 2027",
            CalendarViewMode.WEEK.heading(LocalDate.parse("2026-12-30"), monday, formats),
        )
    }

    @Test
    fun `a month heading is the month, and a day heading is the day`() {
        val formats = CalendarFormats.ofPatterns()

        assertEquals("August 2026", CalendarViewMode.MONTH.heading(thursday, monday, formats))
        assertEquals("6 Aug 2026", CalendarViewMode.DAY.heading(thursday, monday, formats))
    }

    private fun daysBetween(from: LocalDate, to: LocalDate): Int =
        java.time.temporal.ChronoUnit.DAYS.between(from, to).toInt()
}
