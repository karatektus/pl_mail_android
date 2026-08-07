package de.plmail.feature.calendar

import de.plmail.core.database.CalendarEventEntity
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The editor's arithmetic, which is where an event goes wrong quietly.
 *
 * The wire takes a start and an ISO duration rather than two times, so every one of these is a
 * statement about a conversion the server never gets to check: an end that lands before its start,
 * a day count that is out by one, a rule sent on an update that should never carry one.
 */
class EventFormTest {

    private val clock = Clock.fixed(Instant.parse("2026-08-06T14:07:00Z"), ZoneId.of("UTC"))

    @Test
    fun `computes a duration from the two ends`() {
        val draft =
            timed(start = LocalTime.of(9, 0), end = LocalTime.of(10, 30)).toDraft(UNTITLED, true)

        assertEquals(Duration.ofMinutes(90), draft.duration)
        assertEquals(LocalDateTime.parse("2026-08-06T09:00"), draft.start)
    }

    /**
     * Refused, and refused *inline*.
     *
     * The server would refuse it too, but only after the round trip and after the whole form has
     * been filled in. Equal times count as wrong: an event that ends when it starts is a duration
     * of zero, which the picker cannot have been asked for.
     */
    @Test
    fun `refuses an end that is not after the start`() {
        assertTrue(timed(start = LocalTime.of(10, 0), end = LocalTime.of(9, 0)).endsBeforeStart)
        assertTrue(timed(start = LocalTime.of(9, 0), end = LocalTime.of(9, 0)).endsBeforeStart)
        assertFalse(timed(start = LocalTime.of(9, 0), end = LocalTime.of(9, 15)).endsBeforeStart)
    }

    /**
     * An all-day event on one day is one day long, not zero.
     *
     * The commonest all-day event there is starts and ends on the same date, so comparing the two
     * midnights the way a timed event is compared would refuse it.
     */
    @Test
    fun `allows an all-day event that starts and ends on the same day`() {
        val form = allDay(from = "2026-08-08", to = "2026-08-08")

        assertFalse(form.endsBeforeStart)
        assertEquals(Duration.ofDays(1), form.toDraft(UNTITLED, true).duration)
    }

    @Test
    fun `counts both ends of an all-day event`() {
        assertEquals(
            Duration.ofDays(3),
            allDay(from = "2026-08-08", to = "2026-08-10").toDraft(UNTITLED, true).duration,
        )
    }

    @Test
    fun `refuses an all-day event that ends before it starts`() {
        assertTrue(allDay(from = "2026-08-10", to = "2026-08-08").endsBeforeStart)
    }

    /**
     * The word, not an empty string.
     *
     * JMAP refuses an empty title outright and the web stores *Untitled* rather than refusing the
     * save, so this matches it: an event made on a phone and one made in a browser have to be the
     * same event.
     */
    @Test
    fun `substitutes the untitled word for a blank title`() {
        assertEquals(UNTITLED, timed().copy(title = "   ").toDraft(UNTITLED, true).title)
        assertEquals("Standup", timed().copy(title = "  Standup ").toDraft(UNTITLED, true).title)
    }

    /** Blank is nothing, not an empty place. The patch's own null is what removes a location. */
    @Test
    fun `sends no location or description when they are blank`() {
        val draft = timed().copy(location = "  ", description = "").toDraft(UNTITLED, true)

        assertNull(draft.location)
        assertNull(draft.description)
    }

    /**
     * A rule on a create and never on an update.
     *
     * This is the assertion that keeps a foreign rule intact. The cache stores *whether* a series
     * recurs and not what by, so a rule built from this dropdown on an update would flatten "every
     * second Tuesday" to "every week" — and `Repeat.NEVER` would clear the recurrence entirely,
     * because null on that property means "stop recurring".
     */
    @Test
    fun `carries a repeat rule only when creating`() {
        val weekly = timed().copy(repeat = Repeat.WEEKLY)

        assertEquals(
            "weekly",
            weekly.toDraft(UNTITLED, isCreating = true).recurrenceRule?.frequency,
        )
        assertNull(weekly.toDraft(UNTITLED, isCreating = false).recurrenceRule)
        assertNull(timed().toDraft(UNTITLED, isCreating = true).recurrenceRule)
    }

    /**
     * The zone is left to the calendar, which is what the web does.
     *
     * Sending the device's zone would be recording a decision the user was never offered — this
     * editor has no zone control — and would make the same event read differently on the two
     * surfaces the first time somebody travels.
     */
    @Test
    fun `leaves the time zone to the calendar`() {
        assertNull(timed().toDraft(UNTITLED, true).timeZone)
    }

    @Test
    fun `opens a new event on the next whole hour`() {
        val form = EventFormState.forNewEvent(clock)

        assertEquals(LocalTime.of(15, 0), form.startTime)
        assertEquals(LocalTime.of(16, 0), form.endTime)
        assertEquals(LocalDate.parse("2026-08-06"), form.startDate)
    }

    /** The series' own times, so saving a corrected title cannot move the series. */
    @Test
    fun `opens an existing event on the series times`() {
        val form =
            EventFormState.of(event(start = "2026-08-03T09:00:00", duration = "PT30M"), clock)

        assertEquals(LocalDate.parse("2026-08-03"), form.startDate)
        assertEquals(LocalTime.of(9, 0), form.startTime)
        assertEquals(LocalTime.of(9, 30), form.endTime)
        assertEquals(Duration.ofMinutes(30), form.toDraft(UNTITLED, false).duration)
    }

    /**
     * A one-day all-day event opens showing one day.
     *
     * Its stored duration counts whole days from midnight, so its end is the midnight *after* the
     * last day it covers. Drawing that date in the Ends field would say a one-day event lasts two,
     * and saving it back would make that true.
     */
    @Test
    fun `opens an all-day event on the day it actually covers`() {
        val form =
            EventFormState.of(
                event(start = "2026-08-08T00:00:00", duration = "P1D", allDay = true),
                clock,
            )

        assertEquals(LocalDate.parse("2026-08-08"), form.startDate)
        assertEquals(LocalDate.parse("2026-08-08"), form.endDate)
        assertEquals(Duration.ofDays(1), form.toDraft(UNTITLED, false).duration)
    }

    /** `P1M` is not a fixed number of seconds. The form opens anyway rather than not at all. */
    @Test
    fun `opens an event whose duration cannot be parsed`() {
        val form = EventFormState.of(event(start = "2026-08-03T09:00:00", duration = "P1M"), clock)

        assertEquals(LocalTime.of(10, 0), form.endTime)
    }

    private fun timed(
        start: LocalTime = LocalTime.of(9, 0),
        end: LocalTime = LocalTime.of(10, 0),
    ) =
        EventFormState(
            title = "Standup",
            startDate = LocalDate.parse("2026-08-06"),
            startTime = start,
            endDate = LocalDate.parse("2026-08-06"),
            endTime = end,
            calendarKey = "https://nas.local/13#c1",
        )

    /**
     * A long press on the grid proposes the slot it landed in, and nothing else.
     *
     * The end-to-end statement of the create-from-the-grid gesture: `slotAt` snaps the touch to the
     * quarter hour, and the form has to carry *that* through to the wire. Rounding a second time
     * here would quietly move a press on the 14:15 line to 15:00, which is the one thing the
     * gesture promises not to do — and the promise is invisible until somebody looks at the saved
     * event.
     */
    @Test
    fun `a slot fills the form with its own time and an hour's length`() {
        val slot = slotAt(LocalDate.parse("2026-08-06"), fractionOf(14, 22))
        val form = EventFormState.forNewEventAt(slot).copy(calendarKey = "https://nas.local/13#c1")

        assertEquals(LocalDate.parse("2026-08-06"), form.startDate)
        assertEquals(LocalTime.of(14, 15), form.startTime)
        assertEquals(LocalTime.of(15, 15), form.endTime)

        val draft = form.toDraft(UNTITLED, isCreating = true)

        assertEquals(LocalDateTime.parse("2026-08-06T14:15"), draft.start)
        assertEquals(Duration.ofHours(1), draft.duration)
        assertFalse(draft.isAllDay)
    }

    /**
     * A slot at the end of the day still ends on a real time.
     *
     * 23:45 plus an hour is 00:45 the next morning, and the *duration* is what goes on the wire —
     * so the event is an hour long and crosses midnight, which is a real thing to create and not
     * something to refuse.
     */
    @Test
    fun `a slot in the last quarter of the day runs into the next one`() {
        val slot = slotAt(LocalDate.parse("2026-08-06"), 1f)
        val draft =
            EventFormState.forNewEventAt(slot)
                .copy(calendarKey = "https://nas.local/13#c1")
                .toDraft(UNTITLED, isCreating = true)

        assertEquals(LocalDateTime.parse("2026-08-06T23:45"), draft.start)
        assertEquals(Duration.ofHours(1), draft.duration)
    }

    /** The `+` button's proposal is unchanged: the next whole hour, never the awkward one now. */
    @Test
    fun `the plus button still proposes the next whole hour`() {
        val form = EventFormState.forNewEvent(clock)

        assertEquals(LocalTime.of(15, 0), form.startTime)
        assertEquals(LocalTime.of(16, 0), form.endTime)
    }

    private fun fractionOf(hour: Int, minute: Int): Float = (hour * 60 + minute) / 1440f

    private fun allDay(from: String, to: String) =
        EventFormState(
            title = "Sommerfest",
            startDate = LocalDate.parse(from),
            startTime = LocalTime.MIDNIGHT,
            endDate = LocalDate.parse(to),
            endTime = LocalTime.MIDNIGHT,
            isAllDay = true,
            calendarKey = "https://nas.local/13#c1",
        )

    private fun event(start: String, duration: String, allDay: Boolean = false) =
        CalendarEventEntity(
            uid = "https://nas.local/13#42",
            accountKey = "https://nas.local/13",
            eventId = "42",
            calendarKey = "https://nas.local/13#c1",
            calendarId = "c1",
            title = "Standup",
            start = start,
            duration = duration,
            isAllDay = allDay,
        )

    private companion object {
        const val UNTITLED = "Untitled"
    }
}
