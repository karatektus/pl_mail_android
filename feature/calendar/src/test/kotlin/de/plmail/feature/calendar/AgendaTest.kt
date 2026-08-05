package de.plmail.feature.calendar

import androidx.compose.ui.graphics.Color
import de.plmail.core.database.AgendaRow
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the agenda makes of what the DAO hands it.
 *
 * These are the parts of the screen that are decisions rather than drawing: which day a row belongs
 * to, what an empty day means, and whether a calendar's own colour survives being read out of a
 * string the server chose the spelling of.
 */
class AgendaTest {

    @Test
    fun `groups rows into the days they are on`() {
        val days =
            groupByDay(
                listOf(
                    row(date = "2026-08-06", title = "Standup"),
                    row(date = "2026-08-06", title = "Lunch"),
                    row(date = "2026-08-09", title = "Flight"),
                )
            )

        assertEquals(
            listOf(LocalDate.parse("2026-08-06"), LocalDate.parse("2026-08-09")),
            days.map { it.date },
        )
        assertEquals(listOf("Standup", "Lunch"), days.first().rows.map { it.title })
    }

    /**
     * The empty days between entries are not represented at all.
     *
     * Which is what an agenda *is*: the days between the 6th and the 9th above have no header, no
     * placeholder and no row. A rolling month drawn with them would be twenty-six lines of
     * furniture around four appointments.
     */
    @Test
    fun `leaves out the days nothing is on`() {
        val days = groupByDay(listOf(row(date = "2026-08-06"), row(date = "2026-08-20")))

        assertEquals(2, days.size)
    }

    /** The DAO has already ordered them; grouping must not offer a second opinion. */
    @Test
    fun `keeps the order it was given inside a day`() {
        val rows =
            listOf(
                row(date = "2026-08-06", title = "Sommerfest", isAllDay = true),
                row(date = "2026-08-06", title = "Standup", startLocal = "2026-08-06T09:00:00"),
            )

        assertEquals(
            listOf("Sommerfest", "Standup"),
            groupByDay(rows).single().rows.map { it.title },
        )
    }

    /**
     * A hidden calendar is hidden everywhere, which is what the web promises.
     *
     * Dropped here rather than in SQL: `isVisible` is a display preference the server does not act
     * on, so the rows are cached either way and a calendar ticked back on shows immediately instead
     * of waiting for a refresh.
     */
    @Test
    fun `drops rows from a calendar the user has hidden`() {
        val rows =
            listOf(
                row(date = "2026-08-06", title = "Shown", isVisible = true),
                row(date = "2026-08-06", title = "Hidden", isVisible = false),
            )

        assertEquals(listOf("Shown"), groupByDay(rows).single().rows.map { it.title })
    }

    /** A date nothing can parse is a row nothing can place. Dropped, not drawn under "null". */
    @Test
    fun `drops a row whose day cannot be read`() {
        assertTrue(groupByDay(listOf(row(date = "not a date"))).isEmpty())
    }

    @Test
    fun `reads a calendar colour with and without its hash`() {
        assertEquals(Color(0xFF3B82F6), calendarColor("#3b82f6"))
        assertEquals(Color(0xFF3B82F6), calendarColor("3B82F6"))
    }

    /**
     * Null rather than a substituted grey.
     *
     * The caller falls back to a token, which is a mark the user can see; a grey invented here
     * would claim they had chosen grey.
     */
    @Test
    fun `refuses a colour it cannot read`() {
        assertNull(calendarColor(null))
        assertNull(calendarColor(""))
        assertNull(calendarColor("#fff"))
        assertNull(calendarColor("#gggggg"))
    }

    @Test
    fun `takes the start time from the stored wall clock`() {
        assertEquals(
            LocalTime.of(9, 30),
            startTimeOf(row(startLocal = "2026-08-06T09:30:00")),
        )
    }

    /** An all-day row has no time, and the screen draws the word instead of an empty column. */
    @Test
    fun `has no start time for an all-day row`() {
        assertNull(startTimeOf(row(isAllDay = true, startLocal = "2026-08-06T00:00:00")))
    }

    @Test
    fun `says how long something lasts in words`() {
        assertEquals("15 min", durationWords(15, "h", "min"))
        assertEquals("1 h", durationWords(60, "h", "min"))
        assertEquals("1 h 30 min", durationWords(90, "h", "min"))
    }

    /** A zero-length event is real. Saying nothing beats saying it takes no time. */
    @Test
    fun `says nothing about a length of nothing`() {
        assertNull(durationWords(0, "h", "min"))
    }

    private fun row(
        date: String = "2026-08-06",
        title: String = "Standup",
        startLocal: String? = "2026-08-06T09:00:00",
        endLocal: String? = "2026-08-06T09:15:00",
        isAllDay: Boolean = false,
        isVisible: Boolean? = true,
    ) =
        AgendaRow(
            date = date,
            startLocal = startLocal,
            endLocal = endLocal,
            zoneId = "Europe/Berlin",
            isAllDay = isAllDay,
            eventKey = "https://nas.local/13#$title",
            eventId = title,
            title = title,
            location = null,
            description = null,
            status = "confirmed",
            isRecurring = false,
            calendarKey = "https://nas.local/13#c1",
            calendarName = "Work",
            calendarColor = "#3b82f6",
            calendarIsVisible = isVisible,
        )
}
