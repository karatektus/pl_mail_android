package de.plmail.feature.calendar

import androidx.compose.ui.unit.dp
import de.plmail.core.database.AgendaRow
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * What a month cell says it is holding.
 *
 * The count is the one number in that view a person actually reads — "and three more" — and getting
 * it off by one is the difference between an honest grid and one that hides a meeting. It is
 * arithmetic over clusters rather than over rows, deliberately: a day holding one meeting stored
 * twice has **one** dot and no overflow, which is the whole reason the collapse happens before the
 * grid is built.
 *
 * Two arithmetics now, for the two ways a cell draws: [monthIndicators] for the compact grid's
 * dots, and [monthCellPlan] for the full month's titled chips, which differ in that a chip's
 * counter needs a row of its own and a dot's does not. Both are pure functions of what is on the
 * day and how much room there is, which is what lets the one number a person reads be asserted
 * without a canvas.
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

    /**
     * The ordinary cell: everything on the day has a chip of its own and there is nothing to count.
     */
    @Test
    fun `a cell with room for everything draws everything`() {
        val plan = monthCellPlan(clusters(2), slots = 3)

        assertEquals(2, plan.shown.size)
        assertEquals(0, plan.overflow)
    }

    /** Exactly as many as fit is not an overflow. The counter would have nothing to sit on. */
    @Test
    fun `a cell filled exactly to its slots counts nothing`() {
        val plan = monthCellPlan(clusters(3), slots = 3)

        assertEquals(3, plan.shown.size)
        assertEquals(0, plan.overflow)
    }

    /**
     * The counter costs a row, and the arithmetic pays for it.
     *
     * Three slots and four meetings is **two** chips and "+2 more" — not three chips and a counter
     * with nowhere to sit, and not three chips and a silently dropped fourth.
     */
    @Test
    fun `a full cell gives the counter a row of its own`() {
        val plan = monthCellPlan(clusters(4), slots = 3)

        assertEquals(2, plan.shown.size)
        assertEquals(2, plan.overflow)
    }

    @Test
    fun `a busy cell draws what fits and counts the rest`() {
        val plan = monthCellPlan(clusters(11), slots = 3)

        assertEquals(2, plan.shown.size)
        assertEquals(9, plan.overflow)
    }

    /**
     * Never "+1 more", at any size of cell.
     *
     * A counter reading "+1 more" would be occupying the exact row the event it is counting could
     * have occupied, which is a line that costs what it saves. It cannot happen — overflow only
     * begins once there are more meetings than slots, so the smallest it can be is two — and this
     * asserts it across the whole range rather than at the one size somebody happened to try.
     */
    @Test
    fun `never counts a single hidden meeting`() {
        (1..6).forEach { slots ->
            (0..12).forEach { count ->
                val plan = monthCellPlan(clusters(count), slots)

                assertTrue(
                    plan.overflow == 0 || plan.overflow >= 2,
                    "$count meetings in $slots slots counted ${plan.overflow}",
                )
                // Nothing is ever hidden without being counted.
                assertEquals(count, plan.shown.size + plan.overflow)
            }
        }
    }

    /** A cell too short for one chip counts everything, and the caller draws dots instead. */
    @Test
    fun `a cell with no room for a chip counts everything`() {
        val plan = monthCellPlan(clusters(3), slots = 0)

        assertEquals(0, plan.shown.size)
        assertEquals(3, plan.overflow)
    }

    @Test
    fun `an empty day in a cell with no room says nothing at all`() {
        val plan = monthCellPlan(emptyList(), slots = 0)

        assertEquals(0, plan.shown.size)
        assertEquals(0, plan.overflow)
    }

    /** One meeting stored twice is one chip, in the view where a second would read as two. */
    @Test
    fun `a meeting held on two calendars takes one chip`() {
        val duplicated =
            clusterRows(
                listOf(
                    row("1", "Arbeit", uid = "m@plmail", title = "Elternabend"),
                    row("2", "Privat", uid = "m@plmail", title = "Elternabend"),
                    row("3", "Arbeit", uid = "other@plmail", title = "Standup"),
                )
            )

        val plan = monthCellPlan(duplicated, slots = 2)

        assertEquals(2, plan.shown.size)
        assertEquals(0, plan.overflow)
    }

    /**
     * How many chips a measured cell holds.
     *
     * The gap lives *between* chips, which is the off-by-one this is here to pin: two 30dp chips
     * with a 2dp gap need 62dp, and an implementation charging for a trailing gap would draw one.
     */
    @Test
    fun `counts the chips a cell has measured room for`() {
        val chip = 30.dp
        val gap = 2.dp

        assertEquals(3, monthChipSlots(100.dp, chip, gap))
        assertEquals(2, monthChipSlots(62.dp, chip, gap))
        assertEquals(1, monthChipSlots(61.dp, chip, gap))
        assertEquals(1, monthChipSlots(chip, chip, gap))
    }

    /** A cell shorter than one chip holds none, rather than half of one. */
    @Test
    fun `a cell shorter than a chip holds none`() {
        assertEquals(0, monthChipSlots(29.dp, 30.dp, 2.dp))
        assertEquals(0, monthChipSlots(0.dp, 30.dp, 2.dp))
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
