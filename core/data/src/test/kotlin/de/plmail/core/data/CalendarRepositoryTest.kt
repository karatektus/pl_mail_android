package de.plmail.core.data

import de.plmail.core.database.PlMailDatabase
import de.plmail.core.database.StoreKey
import de.plmail.jmap.client.HttpRequest
import de.plmail.jmap.protocol.CalendarEventId
import de.plmail.jmap.testing.RecordingTransport
import java.io.IOException
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The calendar cache, and the one thing about it that is not like the mail cache.
 *
 * Mail has a delta: `Email/changes` says what moved and the client applies it. Calendars have
 * nothing of the sort — the state is the literal `"fixed"`, there is no `/changes`, and push does
 * not carry events — so the *only* way this cache learns that something is gone is that a re-run
 * query no longer mentions it. Half the tests here are that sentence stated as an assertion,
 * because every one of the failures they pin looks like a working app: an event still on screen a
 * week after it was cancelled from the web, a standup drawn on the Thursday it was never on, an
 * occurrence at the time it used to be at.
 *
 * The other half is the rule that stops the fix costing more than the bug. The client is forbidden
 * from expanding a recurrence rule, so day membership is asked of the server one day at a time —
 * and asking is only allowed when there is a recurring event to ask about, batched, and never on a
 * timer. `requests` and `dayProbes` are asserted for that reason: they are the only visible
 * difference between this design and one that quietly issues thirty-one round trips to draw a month
 * of dentist appointments.
 *
 * Run against a real database under Robolectric, like the feed suite, because every assertion here
 * is a statement about what is in a table.
 */
@RunWith(RobolectricTestRunner::class)
// sdk = 36 for the reason `core/ui`'s screenshot tests give: a library module
// declares no targetSdk, so it inherits compileSdk 37 and Robolectric has no
// Android 37 to emulate. 36 is what :app targets anyway.
@Config(sdk = [36])
class CalendarRepositoryTest {

    private lateinit var database: PlMailDatabase

    /** Monday to Sunday of the seeded week. Half-open, like the wire's own window. */
    private val week = CalendarWindow(LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 10))

    private val monday = LocalDate.of(2026, 8, 3)
    private val wednesday = LocalDate.of(2026, 8, 5)
    private val thursday = LocalDate.of(2026, 8, 6)
    private val friday = LocalDate.of(2026, 8, 7)

    private val standupKey = StoreKey.objectKey(testAccountKey, "10867")
    private val dentistKey = StoreKey.objectKey(testAccountKey, "10865")

    @Before
    fun open() {
        database = inMemoryDatabase()
    }

    @After
    fun close() {
        database.close()
    }

    /**
     * A refresh writes the three things a calendar screen reads.
     *
     * The calendars, so a row can carry a colour; the series, so it can carry a title; and the
     * days, which are the only one of the three the server does not simply hand over.
     */
    @Test
    fun `a window refresh writes calendars, series and the days they fall on`() = runTest {
        val server = FakeCalendarServer(events = seededWeek())
        val repository = calendarStack(database, calendarTransport(server))

        val result = repository.refresh(week)

        assertTrue(result is CalendarRefresh.Refreshed, "got $result")
        assertEquals(3, result.events)
        assertEquals(5, result.occurrences, "three standups, one dentist, one Sommerfest")

        assertEquals(
            listOf("Feiertage", "Personal"),
            repository.calendars().first().map { it.name }.sorted(),
        )

        assertEquals(
            listOf(
                "2026-08-03 Team-Standup",
                "2026-08-05 Team-Standup",
                "2026-08-06 Zahnarzt",
                "2026-08-07 Team-Standup",
                "2026-08-08 Sommerfest",
            ),
            agenda(),
        )
    }

    /**
     * The colour comes off the calendar, resolved by the join rather than copied onto the row.
     *
     * Copied would have been fewer moving parts and wrong the first time somebody recolours a
     * calendar in the web UI: every cached occurrence would keep the old colour until it was
     * refreshed, one window at a time.
     */
    @Test
    fun `an occurrence carries its calendar's colour without storing it`() = runTest {
        val server = FakeCalendarServer(events = seededWeek())
        val repository = calendarStack(database, calendarTransport(server))

        repository.refresh(week)

        val rows = repository.occurrences(week).first()

        assertEquals("#2563eb", rows.first().calendarColor)
        assertEquals("Personal", rows.first().calendarName)
    }

    /**
     * The whole reason this class asks the server about days.
     *
     * A Mon/Wed/Fri series is on the Monday, the Wednesday and the Friday, and it is **not** on the
     * Thursday — which is the assertion, because a client expanding `byDay` locally would get this
     * one right and get the DST week wrong in a way no test written from a rule would notice.
     * Verified against the running stack: the Friday and Monday one-day windows return event 10867
     * and the Thursday one does not.
     */
    @Test
    fun `a recurring series lands only on the days the server reports it on`() = runTest {
        val server = FakeCalendarServer(events = seededWeek())
        val repository = calendarStack(database, calendarTransport(server))

        repository.refresh(week)

        assertEquals(
            listOf("2026-08-03", "2026-08-05", "2026-08-07"),
            database.calendarEvents().occurrencesOf(standupKey).map { it.date },
        )
        assertTrue(
            database.calendarEvents().occurrencesOf(standupKey).none { it.date == "2026-08-06" },
            "the Thursday was never one of the days the server reported",
        )
    }

    /**
     * An override moves one occurrence, and only that one.
     *
     * The key is the occurrence's *original* start and the `start` inside is where it went, so a
     * client matching on the value rather than the key would move the wrong day — and one applying
     * the override to the series would move all three.
     */
    @Test
    fun `an override moves one occurrence's time and renames it`() = runTest {
        val server =
            FakeCalendarServer(
                events =
                    mutableListOf(
                        recurring(
                            id = "10867",
                            title = "Team-Standup",
                            start = "2026-08-03T10:00:00",
                            days = setOf(monday, wednesday, friday),
                            overrides =
                                """
                                {"2026-08-07T10:00:00":
                                  {"title":"Standup (Retro-Woche)","start":"2026-08-07T11:00:00"}}
                                """,
                        )
                    )
            )
        val repository = calendarStack(database, calendarTransport(server))

        repository.refresh(week)

        val days = database.calendarEvents().occurrencesOf(standupKey)

        assertEquals(
            listOf("2026-08-03T10:00:00", "2026-08-05T10:00:00", "2026-08-07T11:00:00"),
            days.map { it.startLocal },
        )
        assertEquals(
            listOf(null, null, "Standup (Retro-Woche)"),
            days.map { it.titleOverride },
            "only the overridden occurrence carries a title of its own",
        )
    }

    /**
     * `{"excluded": true}` cancels one occurrence, and the client honours it.
     *
     * The server also drops an excluded occurrence from its own query, so this fake deliberately
     * still reports the Wednesday — the check has to hold when the two disagree, which is exactly
     * the state after a local write and before the refresh that would settle it.
     */
    @Test
    fun `an excluded occurrence is dropped even when the day query still reports it`() = runTest {
        val server =
            FakeCalendarServer(
                events =
                    mutableListOf(
                        recurring(
                            id = "10867",
                            title = "Team-Standup",
                            start = "2026-08-03T10:00:00",
                            days = setOf(monday, wednesday, friday),
                            overrides = """{"2026-08-05T10:00:00":{"excluded":true}}""",
                        )
                    )
            )
        val repository = calendarStack(database, calendarTransport(server))

        repository.refresh(week)

        assertEquals(
            listOf("2026-08-03", "2026-08-07"),
            database.calendarEvents().occurrencesOf(standupKey).map { it.date },
        )
    }

    /**
     * An event that stops being reported stops being drawn.
     *
     * There is no `CalendarEvent/changes` and no push on this surface, so a re-run query saying
     * nothing about an event is the *only* signal the cache will ever get — whether the event was
     * deleted from the web, moved out of the window, or cancelled by an override. The three are
     * deliberately not distinguished inside a refreshed window, because a day view is not asking
     * which of them happened: it is asking what is on Thursday.
     *
     * The series row goes with the last of its days, because nothing joins to a series with no
     * occurrences and nothing would ever delete it later.
     */
    @Test
    fun `an event the server no longer reports leaves the window and takes its series with it`() =
        runTest {
            val server = FakeCalendarServer(events = seededWeek())
            val repository = calendarStack(database, calendarTransport(server))

            repository.refresh(week)
            assertNotNull(database.calendarEvents().byUid(dentistKey))

            server.events.removeAll { it.id == "10865" }
            repository.refresh(week)

            assertEquals(emptyList(), database.calendarEvents().occurrencesOf(dentistKey))
            assertNull(
                database.calendarEvents().byUid(dentistKey),
                "and the series row went with its last day",
            )
            assertTrue(agenda().none { it.endsWith("Zahnarzt") })
        }

    /**
     * A window with nothing recurring in it costs one round trip.
     *
     * This is the cost side of the whole design. Day probes are what make a recurring event
     * placeable at all, and they are also thirty-one method calls — so issuing them for a month of
     * one-off appointments would spend the entire budget of a Raspberry Pi to learn what `start`
     * already said.
     */
    @Test
    fun `a window with no recurring events issues no day probes`() = runTest {
        val server =
            FakeCalendarServer(
                events = mutableListOf(oneOff("10865", "Zahnarzt", "2026-08-06T09:30:00"))
            )
        val repository = calendarStack(database, calendarTransport(server))

        val result = repository.refresh(week) as CalendarRefresh.Refreshed

        assertEquals(1, result.requests)
        assertEquals(0, server.dayProbes)
    }

    /**
     * And a window with one costs a batch, not a round trip per day.
     *
     * Seven days is seven `CalendarEvent/query` calls; the session says thirty-two calls fit in a
     * request, so they travel in one. The assertion is on the *requests*, because the number of
     * probes is fixed by the calendar and the number of round trips is the part a mistake changes.
     */
    @Test
    fun `day probes for a week travel in a single request`() = runTest {
        val server = FakeCalendarServer(events = seededWeek())
        val transport = calendarTransport(server)
        val repository = calendarStack(database, transport)

        val result = repository.refresh(week) as CalendarRefresh.Refreshed

        assertEquals(7, server.dayProbes, "one query per day of the window")
        assertEquals(2, result.requests, "the window with its get, then one batch of probes")
        assertEquals(
            2,
            transport.requests.count { it.url.endsWith("/jmap/api") },
            "and the transport saw exactly those two, plus discovery",
        )
    }

    /**
     * A one-off is placed on the phone from the answer, without a second round trip.
     *
     * The id has to come from the server — it is what every later edit is addressed to — but where
     * the event goes does not: a non-recurring event's days are its own `start` and `duration`,
     * which is reading published data rather than expanding anything.
     */
    @Test
    fun `a created one-off appears in the cache from the create response alone`() = runTest {
        val server = FakeCalendarServer(events = seededWeek())
        server.onSet = { createdEvent(id = "10999") }

        val repository = calendarStack(database, calendarTransport(server))

        repository.refresh(week)

        val result =
            repository.create(
                calendarKey = testCalendarKey,
                draft =
                    EventDraft(
                        title = "Neuer Termin",
                        start = LocalDateTime.of(2026, 8, 9, 14, 0),
                        duration = Duration.ofHours(1),
                    ),
            )

        val created = StoreKey.objectKey(testAccountKey, "10999")

        assertEquals(CalendarWriteResult.Applied(created, CalendarEventId("10999")), result)
        assertEquals("Neuer Termin", database.calendarEvents().byUid(created)?.title)
        assertEquals(
            listOf("2026-08-09"),
            database.calendarEvents().occurrencesOf(created).map { it.date },
        )
    }

    /**
     * A refusal is an answer, and nothing is written on the strength of it.
     *
     * Distinct from a transport failure on purpose: this one will never succeed however many times
     * it is sent, which is why calendar writes are not queued and why the reason has to reach the
     * screen rather than a log.
     */
    @Test
    fun `a refused create is reported and leaves the cache alone`() = runTest {
        val server = FakeCalendarServer(events = seededWeek())
        server.onSet = {
            refusedCreate("invalidProperties", "Not settable on a CalendarEvent: participants.")
        }

        val repository = calendarStack(database, calendarTransport(server))

        repository.refresh(week)

        val before = agenda()
        val result =
            repository.create(
                calendarKey = testCalendarKey,
                draft =
                    EventDraft(
                        title = "Abgelehnt",
                        start = LocalDateTime.of(2026, 8, 9, 14, 0),
                        duration = Duration.ofHours(1),
                    ),
            )

        assertEquals(
            CalendarWriteResult.Rejected(
                "invalidProperties",
                "Not settable on a CalendarEvent: participants.",
            ),
            result,
        )
        assertEquals(before, agenda(), "nothing was written for a create that did not happen")
    }

    /**
     * An edit shows before the server has answered, and is taken back when it refuses.
     *
     * Taking it back is where this deliberately differs from the mail actions, which never roll a
     * local change back: an archive that fails leaves a snackbar offering the way back and a
     * durable queue that will retry it. A calendar edit has neither yet, so keeping it would leave
     * the phone showing a time that is never going to become true with nothing on screen able to
     * correct it.
     */
    @Test
    fun `an edit is applied optimistically and reverted when the server refuses it`() = runTest {
        val server = FakeCalendarServer(events = seededWeek())
        val repository = calendarStack(database, calendarTransport(server))

        repository.refresh(week)

        var seenDuringWrite: String? = null

        server.onSet = {
            // Read inside the handler, which is the only moment the optimistic
            // row and the un-answered request coexist. Asserting afterwards
            // could not tell an optimistic write that was reverted from one that
            // never happened.
            seenDuringWrite = "asked"
            refusedUpdate("10865", "forbidden", "This calendar is read-only.")
        }

        val result =
            repository.update(
                eventKey = dentistKey,
                draft =
                    EventDraft(
                        title = "Zahnarzt (verschoben)",
                        start = LocalDateTime.of(2026, 8, 6, 15, 0),
                        duration = Duration.ofMinutes(45),
                    ),
            )

        assertEquals("asked", seenDuringWrite)
        assertTrue(result is CalendarWriteResult.Rejected && result.isForbidden, "got $result")
        assertEquals(
            "Zahnarzt",
            database.calendarEvents().byUid(dentistKey)?.title,
            "the refused edit was taken back off the cache",
        )
        assertEquals(
            listOf("2026-08-06T09:30:00"),
            database.calendarEvents().occurrencesOf(dentistKey).map { it.startLocal },
        )
    }

    /** An accepted edit stays, and the day it moved to is the day it is drawn on. */
    @Test
    fun `an accepted edit keeps its optimistic day`() = runTest {
        val server = FakeCalendarServer(events = seededWeek())
        server.onSet = { updated("10865") }

        val repository = calendarStack(database, calendarTransport(server))

        repository.refresh(week)

        val result =
            repository.update(
                eventKey = dentistKey,
                draft =
                    EventDraft(
                        title = "Zahnarzt",
                        start = LocalDateTime.of(2026, 8, 4, 9, 30),
                        duration = Duration.ofMinutes(45),
                    ),
            )

        assertTrue(result is CalendarWriteResult.Applied, "got $result")
        assertEquals(
            listOf("2026-08-04"),
            database.calendarEvents().occurrencesOf(dentistKey).map { it.date },
        )
    }

    /**
     * A delete the server refuses puts the event back.
     *
     * `forbidden` on a read-only calendar is the case the seeded server cannot produce — every
     * calendar it serves reports `mayAddItems: true` — and it is the one where leaving the local
     * delete in place would hide an event that demonstrably still exists.
     */
    @Test
    fun `a refused delete restores the event and its days`() = runTest {
        val server = FakeCalendarServer(events = seededWeek())
        val repository = calendarStack(database, calendarTransport(server))

        repository.refresh(week)

        server.onSet = { refusedDestroy("10865", "forbidden", "This calendar is read-only.") }

        val result = repository.delete(dentistKey)

        assertTrue(result is CalendarWriteResult.Rejected && result.isForbidden, "got $result")
        assertNotNull(database.calendarEvents().byUid(dentistKey))
        assertEquals(
            listOf("2026-08-06"),
            database.calendarEvents().occurrencesOf(dentistKey).map { it.date },
        )
    }

    /** An accepted delete takes the event and every day it was on. */
    @Test
    fun `an accepted delete removes the event everywhere`() = runTest {
        val server = FakeCalendarServer(events = seededWeek())
        val repository = calendarStack(database, calendarTransport(server))

        repository.refresh(week)

        server.onSet = { destroyed("10865") }

        assertTrue(repository.delete(dentistKey) is CalendarWriteResult.Applied)
        assertNull(database.calendarEvents().byUid(dentistKey))
        assertEquals(emptyList(), database.calendarEvents().occurrencesOf(dentistKey))
    }

    /**
     * Nothing answering is not the same as the server saying no.
     *
     * The change reached nothing, so it is taken off the cache — there is no queue behind calendar
     * writes in this milestone and saying so is the honest thing rather than leaving a row that
     * will vanish at the next refresh for no reason the user can see.
     */
    @Test
    fun `a write that reaches nothing is reported as unreachable, not as a refusal`() = runTest {
        val server = FakeCalendarServer(events = seededWeek())
        val healthy = calendarTransport(server)
        val repository = calendarStack(database, healthy)

        repository.refresh(week)

        val offline = calendarStack(database, failingAfterDiscovery(server))
        val result = offline.delete(dentistKey)

        assertTrue(result is CalendarWriteResult.Unreachable, "got $result")
        assertNotNull(
            database.calendarEvents().byUid(dentistKey),
            "the delete reached nothing, so the event is still the truth",
        )
    }

    /**
     * A server with no calendar account is a state, not a failure.
     *
     * plMail's calendars are user-scoped while JMAP accounts are per connected mailbox, so an
     * instance without the vendor extension publishes no calendar primary at all — and that is a
     * supported instance. Reporting it as an error would put a "could not reach your server" banner
     * in front of somebody whose server is fine.
     */
    @Test
    fun `an instance with no calendar account says so rather than failing`() = runTest {
        val server = FakeCalendarServer(calendarAccount = null, events = seededWeek())
        val repository = calendarStack(database, calendarTransport(server))

        assertEquals(CalendarRefresh.NoCalendarAccount, repository.refresh(week))
        assertEquals(emptyList(), repository.calendars().first())
    }

    /**
     * The all-day event is one day, not two.
     *
     * `P1D` from `2026-08-08T00:00:00` ends at midnight on the ninth, and an end landing exactly on
     * midnight belongs to the day before it — otherwise every all-day event in the product draws a
     * second, empty day beside itself.
     */
    @Test
    fun `an all-day event occupies exactly one day`() = runTest {
        val server = FakeCalendarServer(events = seededWeek())
        val repository = calendarStack(database, calendarTransport(server))

        repository.refresh(week)

        val sommerfest =
            database.calendarEvents().occurrencesOf(StoreKey.objectKey(testAccountKey, "10866"))

        assertEquals(listOf("2026-08-08"), sommerfest.map { it.date })
        assertTrue(sommerfest.single().isAllDay)
        assertNull(
            sommerfest.single().zoneId,
            "the all-day event carries no zone at all, and inherits none",
        )
    }

    /** Monday, Wednesday and Friday standup; a dentist on the Thursday; an all-day Saturday. */
    private fun seededWeek(): MutableList<FakeEvent> =
        mutableListOf(
            recurring(
                id = "10867",
                title = "Team-Standup",
                start = "2026-08-03T10:00:00",
                days = setOf(monday, wednesday, friday),
            ),
            oneOff("10865", "Zahnarzt", "2026-08-06T09:30:00", duration = "PT45M"),
            oneOff(
                id = "10866",
                title = "Sommerfest",
                start = "2026-08-08T00:00:00",
                duration = "P1D",
                showWithoutTime = true,
                timeZone = null,
            ),
        )

    /** The agenda as `"<day> <title>"`, which is the shape a mismatch is readable in. */
    private suspend fun agenda(): List<String> =
        database.calendarEvents().observeAgenda(from = "2026-01-01", limit = 100).first().map {
            "${it.date} ${it.title}"
        }

    /**
     * A transport that discovers the session and then refuses to carry anything.
     *
     * Discovery has to work, because a client that cannot fetch a session fails before the write
     * and would test the wrong path — the case being pinned is the request that carries the change
     * reaching nothing.
     */
    private fun failingAfterDiscovery(server: FakeCalendarServer): RecordingTransport =
        RecordingTransport { request: HttpRequest ->
            if (request.url.endsWith("/jmap/api")) throw IOException("the network went away")

            calendarTransport(server).send(request)
        }
}
