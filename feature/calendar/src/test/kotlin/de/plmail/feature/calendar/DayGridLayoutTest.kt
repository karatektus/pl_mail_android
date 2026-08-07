package de.plmail.feature.calendar

import de.plmail.core.database.AgendaRow
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Where a block goes on a time grid, and how wide it is when something else is happening at once.
 *
 * A port of the server's `DayGridLayout` and pinned the same way it is, because the phone and the
 * browser drawing one day differently is the kind of disagreement a user reads as one of them being
 * broken. The two decisions worth having a test each for are the **run**, which is what makes a
 * column of blocks line up down a busy morning instead of stepping in and out, and the **clip**,
 * which is what keeps an overnight flight from drawing over the top of the day.
 */
class DayGridLayoutTest {

    private val day: LocalDate = LocalDate.parse("2026-08-06")

    @Test
    fun `a block starts and ends where the event does`() {
        val grid = place(event("09:00", "10:30"))
        val placed = grid.timed.single()

        assertEquals(540f / MINUTES_IN_DAY, placed.top)
        assertEquals(90f / MINUTES_IN_DAY, placed.height)
        assertEquals(0, placed.lane)
        assertEquals(1, placed.lanes)
    }

    /** An all-day row has nowhere on the axis to be and belongs in the band above it. */
    @Test
    fun `all-day events are lifted out of the axis`() {
        val grid = place(event("09:00", "10:00"), allDay())

        assertEquals(1, grid.timed.size)
        assertEquals(1, grid.allDay.size)
    }

    @Test
    fun `two overlapping events take a lane each and share a width`() {
        val grid = place(event("09:00", "10:00"), event("09:30", "10:30", id = "2"))

        assertEquals(listOf(0, 1), grid.timed.map { it.lane })
        assertTrue(grid.timed.all { it.lanes == 2 })
    }

    /**
     * Everything in one unbroken run shares a lane count.
     *
     * Sizing each block against only what it personally overlaps is the obvious cheaper thing, and
     * it produces a column where a half-width block sits beside a third-width one, which reads as a
     * rendering fault rather than as information. Here the 09:00 stub overlaps only the first of
     * the three long meetings — and is still drawn a third wide, because it is inside their run.
     */
    @Test
    fun `a run shares one width even where its ends do not overlap`() {
        val grid =
            place(
                event("09:00", "09:15"),
                event("09:10", "11:00", id = "2"),
                event("09:20", "11:00", id = "3"),
                event("09:30", "11:00", id = "4"),
            )

        assertTrue(grid.timed.all { it.lanes == 3 }, grid.timed.map { it.lanes }.toString())
        assertEquals(0, grid.timed.single { it.cluster.primary.eventId == "1" }.lane)
    }

    /**
     * A lane is reused by anything that starts after its occupant has finished, run or no run.
     *
     * Greedy interval colouring on a list sorted by start, which is optimal — and is why two
     * meetings that merely *share a neighbour* are drawn one above the other rather than side by
     * side.
     */
    @Test
    fun `two events that do not overlap share a lane inside one run`() {
        val grid =
            place(
                event("09:00", "10:00"),
                event("09:30", "11:00", id = "2"),
                event("10:30", "11:30", id = "3"),
            )

        assertTrue(grid.timed.all { it.lanes == 2 }, grid.timed.map { it.lanes }.toString())
        assertEquals(
            grid.timed.single { it.cluster.primary.eventId == "1" }.lane,
            grid.timed.single { it.cluster.primary.eventId == "3" }.lane,
        )
    }

    /** A gap ends the run, so the afternoon is not narrowed by a busy morning. */
    @Test
    fun `a gap starts a new run at full width`() {
        val grid =
            place(
                event("09:00", "10:00"),
                event("09:30", "10:30", id = "2"),
                event("14:00", "15:00", id = "3"),
            )

        val afternoon = grid.timed.single { it.cluster.primary.eventId == "3" }

        assertEquals(1, afternoon.lanes)
        assertEquals(0, afternoon.lane)
    }

    /** A lane is reused the moment its occupant has finished. */
    @Test
    fun `a lane is reused inside a run`() {
        val grid =
            place(
                // The long one covers the whole run.
                event("09:00", "12:00"),
                event("09:00", "10:00", id = "2"),
                event("10:00", "11:00", id = "3"),
            )

        assertTrue(grid.timed.all { it.lanes == 2 })
        assertEquals(
            0,
            grid.timed.single { it.cluster.primary.eventId == "1" }.lane,
        )
        assertEquals(
            1,
            grid.timed.single { it.cluster.primary.eventId == "3" }.lane,
        )
    }

    /**
     * Longest first among equal starts.
     *
     * The reverse puts the long block in the rightmost lane with a column of stubs beside it that
     * look like the main event.
     */
    @Test
    fun `the longest of two events starting together takes lane zero`() {
        val grid = place(event("09:00", "09:15"), event("09:00", "11:00", id = "2"))

        assertEquals(0, grid.timed.single { it.cluster.primary.eventId == "2" }.lane)
    }

    /**
     * An event that began yesterday is clipped to the top of the column and says so.
     *
     * The cache places a multi-day occurrence on every day it covers, each row carrying the *whole*
     * span, so this is the ordinary shape of the second day of an overnight flight rather than an
     * edge case.
     */
    @Test
    fun `an event running in from yesterday is clipped and flagged`() {
        val grid =
            place(
                event(
                    startAt = LocalDateTime.parse("2026-08-05T22:00:00"),
                    endAt = LocalDateTime.parse("2026-08-06T06:00:00"),
                )
            )

        val placed = grid.timed.single()

        assertEquals(0f, placed.top)
        assertEquals(360f / MINUTES_IN_DAY, placed.height)
        assertTrue(placed.continuesBefore)
        assertFalse(placed.continuesAfter)
    }

    @Test
    fun `an event running out into tomorrow is clipped at the bottom and flagged`() {
        val grid =
            place(
                event(
                    startAt = LocalDateTime.parse("2026-08-06T22:00:00"),
                    endAt = LocalDateTime.parse("2026-08-07T06:00:00"),
                )
            )

        val placed = grid.timed.single()

        assertEquals(1f, placed.top + placed.height)
        assertTrue(placed.continuesAfter)
        assertFalse(placed.continuesBefore)
    }

    /**
     * An event finishing exactly at midnight is not continuing into tomorrow.
     *
     * Its end reads as 00:00, and without the `>=` on the day boundary it would be given a height
     * running from its start back up to the top of the column — a block drawn upwards over
     * everything above it.
     */
    @Test
    fun `an event ending exactly at midnight fills to the bottom and no further`() {
        val grid =
            place(
                event(
                    startAt = LocalDateTime.parse("2026-08-06T23:00:00"),
                    endAt = LocalDateTime.parse("2026-08-07T00:00:00"),
                )
            )

        val placed = grid.timed.single()

        assertEquals(1f, placed.top + placed.height)
        assertFalse(placed.continuesAfter)
    }

    /** An end before its start is data to survive, not a negative height to draw. */
    @Test
    fun `an end before its start draws no height rather than a negative one`() {
        val grid =
            place(
                event(
                    startAt = LocalDateTime.parse("2026-08-06T10:00:00"),
                    endAt = LocalDateTime.parse("2026-08-06T09:00:00"),
                )
            )

        assertEquals(0f, grid.timed.single().height)
    }

    /**
     * A merged meeting is placed once, by its representative's span.
     *
     * Members that disagree about when they are have already been split apart by the clusterer, so
     * there is no second answer to choose between.
     */
    @Test
    fun `a merged cluster is one block`() {
        val rows =
            listOf(
                row(
                    "1",
                    "2026-08-06T09:00:00",
                    "2026-08-06T10:00:00",
                    "Arbeit",
                    uid = "m@plmail",
                    title = "Elternabend",
                ),
                row(
                    "2",
                    "2026-08-06T09:00:00",
                    "2026-08-06T10:00:00",
                    "Privat",
                    uid = "m@plmail",
                    title = "Elternabend",
                ),
            )

        val grid = placeDay(day, clusterRows(rows))

        assertEquals(1, grid.timed.size)
        assertEquals(2, grid.timed.single().cluster.members.size)
    }

    /** The tap-to-create slot, which is the only arithmetic the gesture contributes. */
    @Test
    fun `a slot snaps down to the quarter hour`() {
        // 09:07 -> 09:00; 09:22 -> 09:15; 09:59 -> 09:45.
        assertEquals(
            LocalDateTime.parse("2026-08-06T09:00:00"),
            slotAt(day, minute(9, 7)),
        )
        assertEquals(
            LocalDateTime.parse("2026-08-06T09:15:00"),
            slotAt(day, minute(9, 22)),
        )
        assertEquals(
            LocalDateTime.parse("2026-08-06T09:45:00"),
            slotAt(day, minute(9, 59)),
        )
    }

    /**
     * Snapped **down**, never to the nearest.
     *
     * A tap just above the 10:00 line meaning "ten" is the same gesture as a tap just below it, and
     * rounding up would put the event at a time above where the finger was.
     */
    @Test
    fun `a slot never rounds up past the finger`() {
        assertEquals(
            LocalDateTime.parse("2026-08-06T09:45:00"),
            slotAt(day, minute(9, 59)),
        )
    }

    /** A fling that ends past the bottom must not propose 24:00, which is not a time. */
    @Test
    fun `a slot is clamped to the day it is on`() {
        assertEquals(LocalDateTime.parse("2026-08-06T00:00:00"), slotAt(day, -0.5f))
        assertEquals(LocalDateTime.parse("2026-08-06T23:45:00"), slotAt(day, 1.5f))
    }

    private fun minute(hour: Int, minute: Int): Float =
        (hour * 60 + minute).toFloat() / MINUTES_IN_DAY

    private fun place(vararg clusters: EventCluster) = placeDay(day, clusters.toList())

    private fun event(
        from: String = "09:00",
        to: String = "10:00",
        id: String = "1",
        startAt: LocalDateTime? = null,
        endAt: LocalDateTime? = null,
    ): EventCluster =
        EventCluster(
            listOf(
                row(
                    id,
                    (startAt ?: LocalDateTime.parse("2026-08-06T$from:00")).toString(),
                    (endAt ?: LocalDateTime.parse("2026-08-06T$to:00")).toString(),
                    "Arbeit",
                )
            )
        )

    private fun allDay(): EventCluster =
        EventCluster(listOf(row("9", null, null, "Arbeit", isAllDay = true)))

    private fun row(
        id: String,
        start: String?,
        end: String?,
        calendarName: String,
        isAllDay: Boolean = false,
        uid: String? = "e$id@plmail",
        title: String = "Event $id",
    ) =
        AgendaRow(
            date = "2026-08-06",
            startLocal = start,
            endLocal = end,
            zoneId = "Europe/Berlin",
            isAllDay = isAllDay,
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
