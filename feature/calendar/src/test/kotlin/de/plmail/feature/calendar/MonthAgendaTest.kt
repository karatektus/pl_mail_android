package de.plmail.feature.calendar

import de.plmail.core.database.AgendaRow
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

/**
 * Where the mixture view's list lands when a day is chosen in the grid above it.
 *
 * The list is flat — a header per day, then a row per cluster on it — so "scroll to the 12th" is
 * arithmetic over everything before the 12th, and being one out lands somebody in the middle of the
 * 11th with no way of telling that the app misunderstood them. It is counted here rather than
 * searched for at scroll time because the index has to agree with what `AgendaList` emits, and the
 * only way to hold two files to one shape is to write the shape down.
 */
class MonthAgendaTest {

    private val first = LocalDate.parse("2026-08-03")
    private val second = LocalDate.parse("2026-08-06")
    private val third = LocalDate.parse("2026-08-09")

    /** The first day's header is the first item. Nothing precedes it, not even a heading. */
    @Test
    fun `anchors the first day at the top of the list`() {
        assertEquals(0, agendaAnchorIndex(days(), first))
    }

    /**
     * A header, plus one item per cluster, per day before it.
     *
     * The 3rd contributes its header and its two rows, so the 6th's header is the fourth item.
     */
    @Test
    fun `counts a header and a row per cluster of every earlier day`() {
        assertEquals(3, agendaAnchorIndex(days(), second))
        assertEquals(3 + 1 + 1, agendaAnchorIndex(days(), third))
    }

    /**
     * A quiet day scrolls to the next one that has anything.
     *
     * The agenda leaves empty days out — that is what makes it an agenda — so there is no header
     * for the 5th to land on. Landing on the 6th is what "show me from here" means in a list with
     * nothing to show for the 5th, and it beats both refusing to move and scrolling back to the day
     * before.
     */
    @Test
    fun `anchors an empty day to the next day something is on`() {
        assertEquals(3, agendaAnchorIndex(days(), LocalDate.parse("2026-08-05")))
    }

    /** A day before the whole list lands at its top rather than nowhere. */
    @Test
    fun `anchors a day before the list at its first item`() {
        assertEquals(0, agendaAnchorIndex(days(), LocalDate.parse("2026-07-28")))
    }

    /**
     * Past the last day there is nothing to point at, and the caller does not scroll.
     *
     * A list jerking to its own end because somebody tapped the 31st says the app did something,
     * when the truth is that there was nothing to do.
     */
    @Test
    fun `has no anchor past the last day anything is on`() {
        assertNull(agendaAnchorIndex(days(), LocalDate.parse("2026-08-10")))
    }

    @Test
    fun `has no anchor in an empty list`() {
        assertNull(agendaAnchorIndex(emptyList(), second))
    }

    /**
     * One meeting stored on two calendars is one item.
     *
     * The list draws a cluster per row, so counting the *rows* here would put every day after a
     * merged meeting one item further down than it really is.
     */
    @Test
    fun `counts a merged meeting once`() {
        val merged =
            AgendaDay(
                date = first,
                clusters =
                    clusterRows(
                        listOf(
                            row(first, "Elternabend", calendar = "Arbeit", uid = "e@plmail"),
                            row(first, "Elternabend", calendar = "Privat", uid = "e@plmail"),
                        )
                    ),
            )
        val after = AgendaDay(date = second, clusters = listOf(cluster(second, "Standup")))

        // The header and the one merged row, so the next day's header is item
        // two -- not three, which is what two stored copies would have made it.
        assertEquals(2, agendaAnchorIndex(listOf(merged, after), second))
    }

    private fun days() =
        listOf(
            AgendaDay(
                date = first,
                clusters = listOf(cluster(first, "Standup"), cluster(first, "Zahnarzt")),
            ),
            AgendaDay(date = second, clusters = listOf(cluster(second, "Sommerfest"))),
            AgendaDay(date = third, clusters = listOf(cluster(third, "Flug"))),
        )

    private fun cluster(date: LocalDate, title: String) =
        EventCluster(listOf(row(date, title, uid = "$title@plmail")))

    private fun row(
        date: LocalDate,
        title: String,
        calendar: String = "Arbeit",
        uid: String? = null,
    ) =
        AgendaRow(
            date = date.toString(),
            startLocal = "${date}T09:00:00",
            endLocal = "${date}T09:30:00",
            zoneId = "Europe/Berlin",
            isAllDay = false,
            eventKey = "https://nas.local/13#$title$calendar",
            eventId = "$title$calendar",
            title = title,
            location = null,
            description = null,
            status = "confirmed",
            isRecurring = false,
            calendarKey = "https://nas.local/13#$calendar",
            calendarName = calendar,
            calendarColor = "#3b82f6",
            calendarIsVisible = true,
            eventUid = uid,
        )
}
