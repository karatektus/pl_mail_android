package de.plmail.feature.calendar

import de.plmail.core.database.AgendaRow
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * When two rows are one meeting, and when they are not.
 *
 * The screenshot that started this was one meeting drawn twice — same title, same time, two colours
 * — because plMail legitimately holds it on two calendars and `CalendarEvent/query` deliberately
 * answers with the ids of the rows it holds. So the collapse is the client's, and every rule here
 * is copied from `App\Service\Calendar\EventClusterer` rather than invented; a test that agreed
 * with this file and disagreed with that one would be the two surfaces drawing a different number
 * of chips for one meeting, which is precisely what this is for.
 *
 * The two halves worth being careful about are **agreement**, where merging is right, and
 * **disagreement**, where merging is a cover-up: a merged chip that picked a winner would hide an
 * update that reached one copy and not the other behind a tidier UI.
 */
class EventClusterTest {

    @Test
    fun `two copies of one meeting on two calendars are one row`() {
        val clusters = clusterRows(listOf(work(), personal()))

        assertEquals(1, clusters.size)
        assertTrue(clusters.single().isMerged)
        assertEquals(2, clusters.single().members.size)
    }

    /** The dot's whole job: both calendars' colours, in member order, for the pie. */
    @Test
    fun `a merged row carries every member calendar's colour and name`() {
        val cluster = clusterRows(listOf(work(), personal())).single()

        assertEquals(listOf("#3b82f6", "#a855f7"), cluster.colors)
        assertEquals(listOf("Arbeit", "Persönlich"), cluster.calendarNames)
    }

    /**
     * Two different meetings at the same hour are two rows.
     *
     * The one failure this whole design exists to avoid: matching on title and time would collapse
     * a weekly 1:1 held with two different people at the same hour into one chip, and a meeting
     * quietly disappearing from a calendar is the worst shape a calendar bug takes.
     */
    @Test
    fun `different uids at the same time never merge`() {
        val clusters = clusterRows(listOf(work(uid = "a@plmail"), personal(uid = "b@plmail")))

        assertEquals(2, clusters.size)
        assertTrue(clusters.none { it.isMerged })
    }

    /** Two occurrences of one series are the same event and not the same meeting. */
    @Test
    fun `the same uid at two times never merges`() {
        val clusters =
            clusterRows(
                listOf(
                    work(start = "2026-08-06T09:00:00", end = "2026-08-06T09:30:00"),
                    personal(start = "2026-08-06T14:00:00", end = "2026-08-06T14:30:00"),
                )
            )

        assertEquals(2, clusters.size)
    }

    /**
     * Disagreement splits the group — the **whole** group, not the minority.
     *
     * A majority is a winner picked with extra steps, and the point of splitting is that the user
     * sees the disagreement rather than a tidier version of it.
     */
    @Test
    fun `a title that disagrees splits the whole group`() {
        val clusters =
            clusterRows(
                listOf(
                    work(),
                    personal(),
                    third(title = "Standup (verschoben)"),
                )
            )

        assertEquals(3, clusters.size)
        assertTrue(clusters.none { it.isMerged })
    }

    @Test
    fun `an end that disagrees splits the group`() {
        val clusters = clusterRows(listOf(work(), personal(end = "2026-08-06T10:00:00")))

        assertEquals(2, clusters.size)
    }

    @Test
    fun `all-day against timed splits the group`() {
        val clusters = clusterRows(listOf(work(), personal(isAllDay = true)))

        assertEquals(2, clusters.size)
    }

    /**
     * Cancelled on one copy and confirmed on the other is a disagreement, and a loud one.
     *
     * Merging those draws a live meeting that one of the two paths has been told is off.
     */
    @Test
    fun `cancelled against confirmed splits the group`() {
        val clusters = clusterRows(listOf(work(), personal(status = "cancelled")))

        assertEquals(2, clusters.size)
    }

    /** Both cancelled is agreement, and one struck-through row is the honest drawing. */
    @Test
    fun `two cancelled copies still merge`() {
        val clusters =
            clusterRows(listOf(work(status = "cancelled"), personal(status = "cancelled")))

        assertEquals(1, clusters.size)
        assertTrue(clusters.single().isMerged)
    }

    /**
     * Recurrence is deliberately not one of the five compared fields.
     *
     * Two copies where one repeats and the other does not agree about the occurrence they share and
     * about nothing else — and the repeating copy draws its own rows on every later day with no
     * partner to merge with, which is the visible signal that the two differ.
     */
    @Test
    fun `recurrence is not compared`() {
        val clusters = clusterRows(listOf(work(), personal(isRecurring = true)))

        assertEquals(1, clusters.size)
    }

    /**
     * Two rows on **one** calendar are two meetings, by construction.
     *
     * The server makes a UID unique within a calendar, so a repeat there is one series with two
     * occurrences at the same instant — an instance dragged onto a sibling's time. Merging those
     * would erase one of them from the view.
     */
    @Test
    fun `two rows on the same calendar never merge`() {
        val clusters = clusterRows(listOf(work(eventId = "1"), work(eventId = "2")))

        assertEquals(2, clusters.size)
    }

    /** Nothing is matchable against a row with no uid, so each is a cluster of one. */
    @Test
    fun `rows without a uid never merge`() {
        val clusters = clusterRows(listOf(work(uid = null), personal(uid = null)))

        assertEquals(2, clusters.size)
        assertTrue(clusters.none { it.isMerged })
    }

    /**
     * Two zones naming the same instant are the same moment.
     *
     * The server compares timestamps rather than zoned values for exactly this reason: one copy
     * extracted with the organiser's zone and one mirrored with the calendar's must not read as a
     * disagreement about when the meeting is.
     */
    @Test
    fun `the same instant written in two zones still merges`() {
        val clusters =
            clusterRows(
                listOf(
                    work(zone = "Europe/Berlin"),
                    personal(
                        start = "2026-08-06T08:00:00",
                        end = "2026-08-06T08:30:00",
                        zone = "Europe/London",
                    ),
                )
            )

        assertEquals(1, clusters.size)
    }

    /**
     * A floating copy and a zoned one are two different claims about when the meeting is.
     *
     * Resolving the floating one against the device to compare them would make the same event
     * compare unequal to itself the day somebody travels, which is the whole reason a floating
     * event is stored as a bare wall clock.
     */
    @Test
    fun `a floating copy does not merge with a zoned one`() {
        val clusters = clusterRows(listOf(work(zone = "Europe/Berlin"), personal(zone = null)))

        assertEquals(2, clusters.size)
    }

    /**
     * The representative is stable, and picked rather than inherited.
     *
     * The web gets this for free from `ORDER BY startsAt, id`; a Room query ordered only by start
     * hands ties back in whatever order SQLite found them, so a detail screen would open on a
     * different copy each time it was tapped. Numeric where the ids are numbers, so "10" does not
     * lead "9".
     */
    @Test
    fun `the representative is the lowest id, whichever order the rows arrive in`() {
        val forwards = clusterRows(listOf(work(eventId = "9"), personal(eventId = "10")))
        val backwards = clusterRows(listOf(personal(eventId = "10"), work(eventId = "9")))

        assertEquals("9", forwards.single().primary.eventId)
        assertEquals("9", backwards.single().primary.eventId)
    }

    /**
     * A lone occurrence is a cluster of one, which is the ordinary case and draws as it always did.
     */
    @Test
    fun `a single row is a cluster of one`() {
        val cluster = clusterRows(listOf(work())).single()

        assertFalse(cluster.isMerged)
        assertEquals(1, cluster.members.size)
        assertEquals(listOf("#3b82f6"), cluster.colors)
    }

    /** Grouping must not reorder a day the DAO has already sorted. */
    @Test
    fun `the order the rows arrived in survives`() {
        val clusters =
            clusterRows(
                listOf(
                    work(
                        uid = "a@plmail",
                        start = "2026-08-06T09:00:00",
                        end = "2026-08-06T09:30:00",
                    ),
                    work(
                        uid = "b@plmail",
                        start = "2026-08-06T11:00:00",
                        end = "2026-08-06T11:30:00",
                    ),
                    personal(uid = "a@plmail"),
                )
            )

        // Two rows: the 09:00 pair merged, and the 11:00 on its own, in that
        // order -- the group's first member decides where the group goes.
        assertEquals(2, clusters.size)
        assertTrue(clusters.first().isMerged)
        assertEquals("2026-08-06T11:00:00", clusters.last().primary.startLocal)
    }

    private fun work(
        uid: String? = "meeting@plmail",
        eventId: String = "1",
        start: String? = "2026-08-06T09:00:00",
        end: String? = "2026-08-06T09:30:00",
        title: String = "Standup",
        isAllDay: Boolean = false,
        status: String? = "confirmed",
        isRecurring: Boolean = false,
        zone: String? = "Europe/Berlin",
    ) =
        row(
            uid = uid,
            eventId = eventId,
            start = start,
            end = end,
            title = title,
            isAllDay = isAllDay,
            status = status,
            isRecurring = isRecurring,
            zone = zone,
            calendarName = "Arbeit",
            color = "#3b82f6",
        )

    private fun personal(
        uid: String? = "meeting@plmail",
        eventId: String = "2",
        start: String? = "2026-08-06T09:00:00",
        end: String? = "2026-08-06T09:30:00",
        title: String = "Standup",
        isAllDay: Boolean = false,
        status: String? = "confirmed",
        isRecurring: Boolean = false,
        zone: String? = "Europe/Berlin",
    ) =
        row(
            uid = uid,
            eventId = eventId,
            start = start,
            end = end,
            title = title,
            isAllDay = isAllDay,
            status = status,
            isRecurring = isRecurring,
            zone = zone,
            calendarName = "Persönlich",
            color = "#a855f7",
        )

    private fun third(title: String) =
        row(
            uid = "meeting@plmail",
            eventId = "3",
            start = "2026-08-06T09:00:00",
            end = "2026-08-06T09:30:00",
            title = title,
            isAllDay = false,
            status = "confirmed",
            isRecurring = false,
            zone = "Europe/Berlin",
            calendarName = "Familie",
            color = "#22c55e",
        )

    @Suppress("LongParameterList")
    private fun row(
        uid: String?,
        eventId: String,
        start: String?,
        end: String?,
        title: String,
        isAllDay: Boolean,
        status: String?,
        isRecurring: Boolean,
        zone: String?,
        calendarName: String,
        color: String,
    ) =
        AgendaRow(
            date = "2026-08-06",
            startLocal = start,
            endLocal = end,
            zoneId = zone,
            isAllDay = isAllDay,
            eventKey = "https://nas.local/13#$calendarName/$eventId",
            eventId = eventId,
            title = title,
            location = null,
            description = null,
            status = status,
            isRecurring = isRecurring,
            calendarKey = "https://nas.local/13#$calendarName",
            calendarName = calendarName,
            calendarColor = color,
            calendarIsVisible = true,
            eventUid = uid,
        )
}
