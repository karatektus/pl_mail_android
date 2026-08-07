package de.plmail.feature.calendar

import de.plmail.core.database.AgendaRow
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

/**
 * What a month cell says it is holding.
 *
 * The count is the one number in that view a person actually reads — "and three more" — and getting
 * it off by one is the difference between an honest grid and one that hides a meeting. It is
 * arithmetic over clusters rather than over rows, deliberately: a day holding one meeting stored
 * twice has **one** dot and no overflow, which is the whole reason the collapse happens before the
 * grid is built.
 */
class MonthIndicatorsTest {

    @Test
    fun `a quiet day draws a dot each and counts nothing`() {
        val indicators = monthIndicators(clusters(3))

        assertEquals(3, indicators.shown.size)
        assertEquals(0, indicators.overflow)
    }

    /** Exactly as many as fit is not an overflow, and "+0 weitere" is a line that says nothing. */
    @Test
    fun `a day with exactly as many as fit counts nothing`() {
        val indicators = monthIndicators(clusters(MONTH_DOTS))

        assertEquals(MONTH_DOTS, indicators.shown.size)
        assertEquals(0, indicators.overflow)
    }

    @Test
    fun `a busy day draws what fits and counts the rest`() {
        val indicators = monthIndicators(clusters(MONTH_DOTS + 3))

        assertEquals(MONTH_DOTS, indicators.shown.size)
        assertEquals(3, indicators.overflow)
    }

    @Test
    fun `an empty day draws nothing and counts nothing`() {
        val indicators = monthIndicators(emptyList())

        assertEquals(0, indicators.shown.size)
        assertEquals(0, indicators.overflow)
    }

    /**
     * One meeting stored twice is one dot.
     *
     * The defect the user photographed, in the view where it would be least obvious: two dots of
     * two colours in a 55dp cell reads as two meetings and there is no room for a title to say
     * otherwise.
     */
    @Test
    fun `a meeting held on two calendars counts once`() {
        val duplicated =
            clusterRows(
                listOf(
                    row("1", "Arbeit", uid = "m@plmail", title = "Elternabend"),
                    row("2", "Privat", uid = "m@plmail", title = "Elternabend"),
                    row("3", "Arbeit", uid = "other@plmail", title = "Standup"),
                )
            )

        val indicators = monthIndicators(duplicated)

        assertEquals(2, indicators.shown.size)
        assertEquals(0, indicators.overflow)
    }

    private fun clusters(count: Int): List<EventCluster> =
        (1..count).map { EventCluster(listOf(row(it.toString(), "Arbeit", uid = "e$it@plmail"))) }

    private fun row(
        id: String,
        calendarName: String,
        uid: String?,
        title: String = "Event $id",
    ) =
        AgendaRow(
            date = "2026-08-06",
            startLocal = "2026-08-06T09:00:00",
            endLocal = "2026-08-06T09:30:00",
            zoneId = "Europe/Berlin",
            isAllDay = false,
            eventKey = "https://nas.local/13#$id",
            eventId = id,
            title = title,
            location = null,
            description = null,
            status = "confirmed",
            isRecurring = false,
            calendarKey = "https://nas.local/13#$calendarName",
            calendarName = calendarName,
            calendarColor = "#3b82f6",
            calendarIsVisible = true,
            eventUid = uid,
        )
}
