package de.plmail.core.data

import app.cash.turbine.test
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
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
 * The other half is the rule that decides where the days come from. The client is forbidden from
 * expanding a recurrence rule, so every occurrence is one the server named, through
 * `expandRecurrences` — and the ids it names them with are **opaque**, which is asserted here by a
 * fake that mints ids carrying neither the series nor the date. `requests` is asserted for the same
 * reason it always was: it is the visible difference between this design and one that quietly
 * issues a round trip per day to draw a month of dentist appointments.
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
     * The whole reason this class asks the server where the occurrences are.
     *
     * A Mon/Wed/Fri series is on the Monday, the Wednesday and the Friday, and it is **not** on the
     * Thursday — which is the assertion, because a client expanding `byDay` locally would get this
     * one right and get the DST week wrong in a way no test written from a rule would notice.
     * Verified against the 8002 stack on 2026-08-06: one expanded query over August returns fifteen
     * ids, and the thirteen recurring ones are exactly the standup's Mondays, Wednesdays and
     * Fridays.
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
     * The server resolves it now — the occurrence's object carries the moved `start` and the
     * override's title, keyed by an id that still names its *original* start — so what this pins is
     * that the client draws the answer rather than the map: applying `recurrenceOverrides` here as
     * well would move the occurrence twice, and reading the id would move it back.
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
     * `{"excluded": true}` cancels one occurrence, and it simply is not there.
     *
     * The one occurrence with no id: an excluded instance has no occurrence row on the server, so
     * it is absent from the expanded query and `notFound` from the getter. The client has nothing
     * to skip and nothing to interpret, which is the point — the old code carried a second
     * implementation of exclusion for the days a probe answer could not speak to.
     */
    @Test
    fun `an excluded occurrence never reaches the cache`() = runTest {
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
     * A window with nothing recurring in it renders from one query, as it always did.
     *
     * `expandRecurrences` is the same answer as a collapsed query when nothing recurs — a one-off
     * keeps its plain series id, because its single occurrence *is* the event — so the argument
     * costs a month of dentist appointments exactly nothing. Asserted because the opposite would be
     * invisible: the rows would be identical and only the traffic would differ.
     */
    @Test
    fun `a window with no recurring events renders from a single expanded query`() = runTest {
        val server =
            FakeCalendarServer(
                events = mutableListOf(oneOff("10865", "Zahnarzt", "2026-08-06T09:30:00"))
            )
        val transport = calendarTransport(server)
        val repository = calendarStack(database, transport)

        val result = repository.refresh(week) as CalendarRefresh.Refreshed

        assertEquals(1, result.requests)
        assertEquals(1, server.expandedQueries, "one expanded query for the window, and one only")
        assertEquals(
            1,
            transport.requests.count { it.url.endsWith("/jmap/api") },
            "and the transport saw exactly that one, plus discovery",
        )
        assertEquals(listOf("2026-08-06 Zahnarzt"), agenda())
    }

    /**
     * And a window full of a recurring series costs the same one round trip.
     *
     * This is the whole change, stated as the number that used to be five. A week of a Mon/We/Fr
     * standup used to cost the window query plus a batch of seven one-day probes; a month cost the
     * window query plus three batches of thirty-one. It now costs one request whatever recurs in
     * it, because the query that finds the events is the query that places them.
     */
    @Test
    fun `a week holding a recurring series costs one round trip`() = runTest {
        val server = FakeCalendarServer(events = seededWeek())
        val transport = calendarTransport(server)
        val repository = calendarStack(database, transport)

        val result = repository.refresh(week) as CalendarRefresh.Refreshed

        assertEquals(1, result.requests, "the window, expanded, with both gets back-referenced")
        assertEquals(1, server.expandedQueries)
        assertEquals(1, server.collapsedQueries, "the series a form is edited through")
        assertEquals(
            1,
            transport.requests.count { it.url.endsWith("/jmap/api") },
            "and the transport saw exactly that one, plus discovery",
        )
    }

    /**
     * A month of the same series costs one round trip too, which is the number that was thirty-two.
     *
     * The month is the case the old design was worst at and the one a calendar screen actually
     * opens on: every day in the window cost a `CalendarEvent/query` call once anything in it
     * recurred, batched into requests but still four or five of them.
     */
    @Test
    fun `a month holding a recurring series still costs one round trip`() = runTest {
        val august = CalendarWindow(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1))
        val server =
            FakeCalendarServer(
                events =
                    mutableListOf(
                        recurring(
                            id = "10867",
                            title = "Team-Standup",
                            start = "2026-08-03T10:00:00",
                            days =
                                generateSequence(LocalDate.of(2026, 8, 3)) { it.plusDays(1) }
                                    .takeWhile { it < LocalDate.of(2026, 9, 1) }
                                    .filter { it.dayOfWeek.value in setOf(1, 3, 5) }
                                    .toSet(),
                        )
                    )
            )
        val repository = calendarStack(database, calendarTransport(server))

        val result = repository.refresh(august) as CalendarRefresh.Refreshed

        assertEquals(1, result.requests)
        assertEquals(13, result.occurrences, "every Monday, Wednesday and Friday from the third")
        assertEquals(1, server.expandedQueries)
    }

    /**
     * The occurrence id is opaque, and this is the test that fails if anything reads it.
     *
     * The real server builds it as `<seriesId>_<recurrenceId>` — `42_20260304T090000Z` — and it is
     * documented opaque all the same, because the separator is plMail's own choice and the draft
     * says so. This fake mints ids that carry **neither** half: `o1`, `o2`, `o3`. Code that placed
     * an occurrence by parsing a timestamp out of its id, or filed it under a series id parsed out
     * of the same, has nothing to parse here and draws nothing.
     *
     * The dates asserted are therefore only reachable through the object's own `start`, and the
     * series row is only reachable through its `seriesId`.
     */
    @Test
    fun `occurrences are placed from their objects, never from their ids`() = runTest {
        val server = FakeCalendarServer(events = seededWeek())
        val repository = calendarStack(database, calendarTransport(server))

        repository.refresh(week)

        assertEquals(
            listOf("2026-08-03", "2026-08-05", "2026-08-07"),
            database.calendarEvents().occurrencesOf(standupKey).map { it.date },
            "the ids the server issued were o1, o2 and o3 and say nothing about August",
        )
        assertEquals(
            "10867",
            database.calendarEvents().byUid(standupKey)?.eventId,
            "and the series row is keyed by seriesId, which is the id a write is addressed to",
        )
    }

    /**
     * An edit of a recurring series goes to the series id, never to an occurrence's.
     *
     * `CalendarEvent/set` refuses an occurrence id by name — `invalidArguments`, pointing at
     * `seriesId` — so a cache that had filed a standup under `o2` would have an editor that cannot
     * save. The occurrence ids never reach a row at all, which is what this asserts from the other
     * end: the id on the wire is the plain one.
     */
    @Test
    fun `an edit of a recurring series is addressed to the series id`() = runTest {
        val server = FakeCalendarServer(events = seededWeek())
        val repository = calendarStack(database, calendarTransport(server))

        repository.refresh(week)

        var updatedId: String? = null

        server.onSet = { arguments ->
            val id = arguments["update"]!!.jsonObject.keys.single()

            updatedId = id

            updated(id)
        }

        val result =
            repository.update(
                eventKey = standupKey,
                draft =
                    EventDraft(
                        title = "Team-Standup (neu)",
                        start = LocalDateTime.of(2026, 8, 3, 10, 0),
                        duration = Duration.ofMinutes(15),
                    ),
            )

        assertTrue(result is CalendarWriteResult.Applied, "got $result")
        assertEquals("10867", updatedId)
    }

    /**
     * The series row keeps the series' own start, not the start of whichever occurrence was last.
     *
     * The reason the refresh asks a collapsed query as well as an expanded one. An occurrence's
     * object is the series with its override merged in — its `start` is that Tuesday's — and the
     * editor opens on this row: a form seeded from an occurrence would send that occurrence's date
     * as the series' start and drag the whole standup onto the day somebody was looking at. The
     * moved occurrence is in this fixture on purpose, because it is the one whose start differs
     * most.
     */
    @Test
    fun `the cached series keeps its own start while its occurrences keep theirs`() = runTest {
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

        val series = database.calendarEvents().byUid(standupKey)

        assertEquals("2026-08-03T10:00:00", series?.start)
        assertEquals("Team-Standup", series?.title)
        assertTrue(series?.isRecurring == true)
        assertEquals(
            listOf("2026-08-03T10:00:00", "2026-08-05T10:00:00", "2026-08-07T11:00:00"),
            database.calendarEvents().occurrencesOf(standupKey).map { it.startLocal },
        )
    }

    /**
     * A cancelled occurrence is not drawn.
     *
     * `status: cancelled` on an override keeps the occurrence row on the server — it still resolves
     * through `CalendarEvent/get` — and takes it out of the range query the calendar is drawn from,
     * which is `CalendarEventOccurrenceRepository::findInRange` excluding cancelled rows. So the
     * client is never handed the id and has nothing to filter. Distinct from `excluded`, which
     * deletes the row outright; both are invisible here, which is the correct amount of difference
     * for a day view.
     */
    @Test
    fun `a cancelled occurrence leaves the window`() = runTest {
        val server =
            FakeCalendarServer(
                events =
                    mutableListOf(
                        recurring(
                            id = "10867",
                            title = "Team-Standup",
                            start = "2026-08-03T10:00:00",
                            days = setOf(monday, wednesday, friday),
                            overrides = """{"2026-08-05T10:00:00":{"status":"cancelled"}}""",
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
     * A window reaching past the horizon is clamped, and the clamp is reported.
     *
     * An expanded query past the account's `materialisedHorizon` is refused **outright** —
     * `cannotCalculateOccurrences` — rather than answered short, so a client that sent the window
     * it wanted would draw nothing at all for a month eighteen out. What it sends instead is the
     * part it can trust, and `mayBeIncomplete` is the sentence the agenda already has a footer for.
     *
     * The client's line is a year either side of today while this server materialises two years
     * forward, so the clamp is conservative on purpose: it asks for less than is there and says so.
     */
    @Test
    fun `a window past the horizon is clamped and reported as possibly incomplete`() = runTest {
        val server = FakeCalendarServer(events = seededWeek())
        val repository = calendarStack(database, calendarTransport(server))

        val far = CalendarWindow(LocalDate.of(2027, 7, 1), LocalDate.of(2027, 9, 1))
        val result = repository.refresh(far) as CalendarRefresh.Refreshed

        assertTrue(result.mayBeIncomplete, "part of that window is past the line the client trusts")
        assertEquals("+2 years", result.horizon.future, "the server's own words travel with it")
        assertEquals(
            LocalDateTime.of(2027, 6, 30, 22, 0) to LocalDateTime.of(2027, 8, 5, 0, 0),
            server.windows.first(),
            "asked about up to a year from today and not one day further",
        )
    }

    /**
     * A window entirely past the horizon asks nothing about events and empties nothing.
     *
     * The important half is what it does *not* do: the reconcile is skipped. "The server was not
     * asked" and "the server reports nothing here" are the same empty answer, and deleting a
     * window's occurrences on the strength of the first would clear a month the phone is holding
     * for a question nobody put.
     */
    @Test
    fun `a window entirely past the horizon leaves the cache alone`() = runTest {
        val server = FakeCalendarServer(events = seededWeek())
        val repository = calendarStack(database, calendarTransport(server))

        repository.refresh(week)

        val before = agenda()
        val result =
            repository.refresh(CalendarWindow(LocalDate.of(2029, 1, 1), LocalDate.of(2029, 2, 1)))
                as CalendarRefresh.Refreshed

        assertTrue(result.mayBeIncomplete)
        assertEquals(0, result.occurrences)
        assertEquals(before, agenda(), "nothing was asked, so nothing was swept")
        assertEquals(
            listOf("Feiertage", "Personal"),
            repository.calendars().first().map { it.name }.sorted(),
            "the calendar list is not windowed and is still worth the round trip",
        )
    }

    /**
     * A server that refuses the expansion anyway is an honesty gap, not a failure.
     *
     * The clamp is the client's own conservative line; an instance materialising less than this one
     * does would refuse a window the client thought safe. The cache stands, and the screen says the
     * server cannot promise more — the same sentence a clamped window gets, because it is the same
     * situation seen from the other side.
     */
    @Test
    fun `an expansion the server refuses is reported as possibly incomplete`() = runTest {
        val server = FakeCalendarServer(events = seededWeek(), refuseExpansion = true)
        val repository = calendarStack(database, calendarTransport(server))

        val result = repository.refresh(week)

        assertTrue(result is CalendarRefresh.Refreshed, "got $result")
        assertTrue(result.mayBeIncomplete)
        assertEquals(
            emptyList(),
            database.calendarEvents().occurrencesBetween("2026-01-01", "2027-01-01"),
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

    // ------------------------------------------- one meeting, one row per calendar

    /**
     * The reported bug: an event created on the phone, drawn twice from the next sync onwards.
     *
     * The calendar it was created on is a **mirrored** one — a Google account plMail syncs into a
     * calendar of its own — so the create is pushed out to the provider and re-imported on the way
     * back, and the same meeting is then a second row with a second server id and the same `uid`.
     * Both are answered by the next `CalendarEvent/query`, and a cache keyed on the server id alone
     * wrote both: one create, two chips, at the same time on the same day.
     *
     * `EventCluster` cannot answer this one — it is specified never to merge two rows on **one**
     * calendar — so the cache resolves it, on the only key that survives a re-mint.
     */
    @Test
    fun `a created event the mirror re-imports is cached once, not twice`() = runTest {
        val server = FakeCalendarServer(events = seededWeek())
        val repository = calendarStack(database, calendarTransport(server))

        repository.refresh(week)

        server.onSet = {
            server.events +=
                oneOff(id = "10999", title = "Neuer Termin", start = "2026-08-09T14:00:00")

            createdEvent(id = "10999")
        }

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

        assertTrue(result is CalendarWriteResult.Applied, "got $result")

        // The mirror's round trip, as the server then reports it: the same
        // meeting, the same uid, a row id of the provider import's own.
        server.events +=
            oneOff(
                id = "11005",
                title = "Neuer Termin",
                start = "2026-08-09T14:00:00",
                uid = "10999@plmail",
            )

        repository.refresh(week)

        assertEquals(
            listOf("2026-08-09 Neuer Termin"),
            agenda().filter { it.endsWith("Neuer Termin") },
            "one create is one row, whatever the mirror did with it",
        )
        assertNotNull(
            database.calendarEvents().byUid(StoreKey.objectKey(testAccountKey, "10999")),
            "the lower id survives, which is the copy the web's chip names too",
        )
        assertNull(database.calendarEvents().byUid(StoreKey.objectKey(testAccountKey, "11005")))
        assertEquals(
            emptyList(),
            database.calendarEvents().occurrencesOf(StoreKey.objectKey(testAccountKey, "11005")),
            "the copy that was dropped took its days with it",
        )
    }

    /**
     * The same reconcile, on a cache that is already wrong — which is what repairs an installed
     * app.
     *
     * Here the mirror kept only its own copy, so the row the create wrote is one the server will
     * never mention again — and this one is **three days long**, which is what makes it a case the
     * window sweep cannot reach. `clearOccurrencesBetween` empties the window that was asked about
     * and `deleteUnplacedEvents` only sweeps a series whose days have *all* gone, so a stale copy
     * with a day past the window's end keeps that day and its row for good: the event stays drawn
     * on the Monday and the Tuesday with nothing able to correct it. Nothing but the uid can
     * recognise it, which is also why no migration is needed for the duplicates already on
     * somebody's phone.
     */
    @Test
    fun `a re-minted id takes the place of the row the create wrote, days and all`() = runTest {
        val server = FakeCalendarServer(events = seededWeek())
        val repository = calendarStack(database, calendarTransport(server))

        repository.refresh(week)

        server.onSet = { createdEvent(id = "10999") }

        repository.create(
            calendarKey = testCalendarKey,
            draft =
                EventDraft(
                    title = "Konferenz",
                    start = LocalDateTime.of(2026, 8, 9, 14, 0),
                    // Past the window's last day, so the optimistic placement
                    // leaves rows on days no refresh of this window clears.
                    duration = Duration.ofDays(3),
                ),
        )

        assertEquals(
            listOf("2026-08-09", "2026-08-10", "2026-08-11", "2026-08-12"),
            database
                .calendarEvents()
                .occurrencesOf(StoreKey.objectKey(testAccountKey, "10999"))
                .map { it.date },
            "the create placed all four days from the draft, as it always has",
        )

        server.events +=
            oneOff(
                id = "11005",
                title = "Konferenz",
                start = "2026-08-09T14:00:00",
                duration = "PT72H",
                uid = "10999@plmail",
            )

        repository.refresh(week)

        assertEquals(
            listOf("2026-08-09 Konferenz"),
            agenda().filter { it.endsWith("Konferenz") },
            "the stale copy's days outside the refreshed window went with its row",
        )
        assertNotNull(database.calendarEvents().byUid(StoreKey.objectKey(testAccountKey, "11005")))
        assertNull(
            database.calendarEvents().byUid(StoreKey.objectKey(testAccountKey, "10999")),
            "the id the create answered with is not an identity the server still recognises",
        )
    }

    /**
     * The duplicate that is **not** one, and must survive: one meeting on two calendars.
     *
     * plMail legitimately holds a meeting twice — extracted from its invitation onto the account's
     * own calendar and mirrored from a provider onto a connected one — and both rows are correct.
     * That is what `EventCluster` collapses at draw time, and collapsing it in the cache instead
     * would throw away a calendar's copy of a meeting and with it the merged chip's second colour.
     */
    @Test
    fun `one meeting on two calendars keeps a row on each`() = runTest {
        val server =
            FakeCalendarServer(
                events =
                    mutableListOf(
                        oneOff(
                            id = "10871",
                            title = "Quartalsreview",
                            start = "2026-08-04T11:00:00",
                        )
                    )
            )
        val repository = calendarStack(database, calendarTransport(server))

        server.events +=
            oneOff(
                id = "10872",
                title = "Quartalsreview",
                start = "2026-08-04T11:00:00",
                uid = "10871@plmail",
                calendarId = "10599",
            )

        repository.refresh(week)

        assertEquals(
            listOf("2026-08-04 Quartalsreview", "2026-08-04 Quartalsreview"),
            agenda(),
            "two calendars' copies are two rows; the collapse is EventCluster's, at draw time",
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

    /**
     * The one o'clock event, which is the defect this whole conversion exists for.
     *
     * Watched on a device on 2026-08-06: the editor created an event at 01:00 Europe/Berlin, the
     * server stored it correctly at 23:00Z the day before, and the refresh that followed asked for
     * `after: 2026-08-06T00:00:00` — which the server reads as UTC, so the answer was honestly a
     * day with nothing on it, and the reconcile swept the event the user had just saved. It stayed
     * on the server and vanished off the phone, which is the worst shape a bug can have: the app
     * was wrong and looked right.
     *
     * The create is what the fake reports afterwards, rather than a fixture put there in advance,
     * so this is the same sequence in the same order rather than a re-statement of it.
     *
     * The clock is 01:30 in Berlin, because the agenda's window starts at **today** — and the
     * defect lives on that edge: a whole week either side of the event hides it, since a window
     * shifted by two hours still contains an event in the middle of it. What made this vanish on a
     * device is that the event and the window's own lower bound were the same night.
     */
    @Test
    fun `an event created at one in the morning survives the refresh that follows it`() = runTest {
        val server = FakeCalendarServer(events = mutableListOf())
        val repository =
            calendarStack(
                database,
                calendarTransport(server),
                clock = berlinClock("2026-08-05T23:30:00Z"),
            )
        val agendaFromToday =
            CalendarWindow(LocalDate.of(2026, 8, 6), LocalDate.of(2026, 8, 6).plusDays(30))

        server.onSet = {
            server.events += oneOff("10999", "Vom Handy erstellt", "2026-08-06T01:00:00")

            createdEvent(id = "10999")
        }

        repository.refresh(agendaFromToday)

        val created =
            repository.create(
                calendarKey = testCalendarKey,
                draft =
                    EventDraft(
                        title = "Vom Handy erstellt",
                        start = LocalDateTime.of(2026, 8, 6, 1, 0),
                        duration = Duration.ofHours(1),
                    ),
            )

        assertTrue(created is CalendarWriteResult.Applied, "got $created")

        repository.refresh(agendaFromToday)

        assertEquals(
            listOf("2026-08-06 Vom Handy erstellt"),
            agenda(),
            "the refresh asked the server about the right instants, so its answer kept the event",
        )
    }

    /**
     * The window on the wire is UTC, and this is the assertion that says so in so many words.
     *
     * Pinned as instants rather than left implicit in a placement, because every other test here
     * would still pass if the conversion were dropped and the fake's semantics went back with it.
     * `2026-08-03T00:00` in Berlin is `2026-08-02T22:00Z`, and the lower bound is the earlier of
     * the two readings — see `CalendarWindow.fetchAfter`.
     */
    @Test
    fun `the window a refresh asks about is in UTC`() = runTest {
        val server = FakeCalendarServer(events = seededWeek())
        val repository = calendarStack(database, calendarTransport(server))

        repository.refresh(week)

        assertEquals(
            LocalDateTime.of(2026, 8, 2, 22, 0) to LocalDateTime.of(2026, 8, 10, 0, 0),
            server.windows.first(),
        )
        assertEquals(
            setOf(LocalDateTime.of(2026, 8, 2, 22, 0) to LocalDateTime.of(2026, 8, 10, 0, 0)),
            server.windows.toSet(),
            "and it is the only window asked about: the collapsed and expanded queries are the " +
                "same span, and there are no one-day windows any more",
        )
    }

    /**
     * An all-day event is on its own date, on a device two hours ahead of UTC.
     *
     * The Sommerfest is the eighth wherever it is read, which is the entire reason all-day events
     * exist — so it is placed from the wall clock the server published and never from which
     * converted window returned it. That distinction is not academic here: an all-day event's
     * midnight-to-midnight span overlaps *two* of the UTC-converted day windows on this device, so
     * a client trusting a converted probe would draw the party twice, on the day it is on and the
     * day after.
     */
    @Test
    fun `an all-day event lands on its own date on a device two hours ahead of UTC`() = runTest {
        val server = FakeCalendarServer(events = seededWeek())
        val repository = calendarStack(database, calendarTransport(server))

        repository.refresh(week)

        assertEquals(
            listOf("2026-08-08"),
            database
                .calendarEvents()
                .occurrencesOf(StoreKey.objectKey(testAccountKey, "10866"))
                .map { it.date },
        )
    }

    /**
     * And a *recurring* all-day series lands on its own dates, one row per date.
     *
     * A floating event — every all-day one, since the server nulls the zone of one — is stored as
     * the wall clock it was given, in a column read as UTC. The window that reaches it is therefore
     * the union of the converted and the naive bounds, and each occurrence is then placed from the
     * wall clock the server published rather than from which end of the window found it. Getting
     * that second half wrong draws the holiday twice, on its own day and the morning after: two
     * rows, one holiday, and the phone disagreeing with the web about when the office is shut.
     */
    @Test
    fun `a recurring all-day series lands on its own dates`() = runTest {
        val server =
            FakeCalendarServer(
                events =
                    mutableListOf(
                        recurring(
                            id = "10870",
                            title = "Feiertag",
                            start = "2026-08-04T00:00:00",
                            days = setOf(LocalDate.of(2026, 8, 4), LocalDate.of(2026, 8, 8)),
                            duration = "P1D",
                            timeZone = null,
                            showWithoutTime = true,
                        )
                    )
            )
        val repository = calendarStack(database, calendarTransport(server))

        repository.refresh(week)

        assertEquals(
            listOf("2026-08-04", "2026-08-08"),
            database
                .calendarEvents()
                .occurrencesOf(StoreKey.objectKey(testAccountKey, "10870"))
                .map { it.date },
            "the days the server reports it on, and not the mornings after them",
        )
    }

    /**
     * The Sunday the clocks go back is twenty-five hours long, and the window says so.
     *
     * Derived from `ZonedDateTime` day boundaries rather than by adding a day at a time, which is
     * the difference this pins: an event at 23:30 on 2026-10-25 is 22:30Z, and a window closed by
     * twenty-four-hour arithmetic would end an hour before it. The client is forbidden from
     * expanding a recurrence rule for exactly this reason, and the server now does that part — but
     * the window is still the client's, and a window an hour short is a night shift nobody is told
     * about.
     */
    @Test
    fun `a local day across the autumn clock change is twenty-five hours long`() = runTest {
        val saturday = LocalDate.of(2026, 10, 24)
        val sunday = LocalDate.of(2026, 10, 25)
        val monday = LocalDate.of(2026, 10, 26)

        val server =
            FakeCalendarServer(
                events =
                    mutableListOf(
                        recurring(
                            id = "10871",
                            title = "Nachtschicht",
                            start = "2026-10-24T23:30:00",
                            days = setOf(saturday, sunday, monday),
                        )
                    )
            )
        val repository = calendarStack(database, calendarTransport(server))

        repository.refresh(CalendarWindow(saturday, monday.plusDays(1)))

        assertEquals(
            listOf("2026-10-24", "2026-10-25", "2026-10-26"),
            database
                .calendarEvents()
                .occurrencesOf(StoreKey.objectKey(testAccountKey, "10871"))
                .map { it.date },
            "the 23:30 of the long Sunday belongs to the Sunday",
        )
        assertEquals(
            // 2026-10-24T00:00 in Berlin is 22:00Z on the 23rd; the 27th's start
            // is an hour later in UTC than the 24th's, because an hour was given
            // back in between.
            LocalDateTime.of(2026, 10, 23, 22, 0) to LocalDateTime.of(2026, 10, 27, 0, 0),
            server.windows.first(),
        )
    }

    /**
     * The drawer's calendar row appears when a credential does, without being collected again.
     *
     * Seen on a freshly installed device on 2026-08-06: paired, mail syncing, and no Calendar row
     * until the process was force-stopped and relaunched. The session probe ran once per
     * collection, `MainViewModel` collects it before pairing has written anything, and nothing ever
     * asked again — while the cache leg could not answer either, because the cache only gains
     * calendars from a refresh and the only way to a refresh was the row that was not there.
     *
     * Collected once and never re-subscribed here, deliberately: re-collecting is what a process
     * restart does, and it is the workaround this test exists to make unnecessary.
     */
    @Test
    fun `availability turns true when a credential appears, on the same collection`() = runTest {
        val server = FakeCalendarServer(events = seededWeek())
        val credentials = calendarCredentials()
        val repository =
            calendarStack(
                database = database,
                transport = calendarTransport(server),
                credentials = credentials,
                paired = false,
            )

        repository.isAvailable().test {
            assertFalse(awaitItem(), "nothing is paired, so there is nothing to offer")

            credentials.pairWithTestServer()

            assertTrue(awaitItem(), "the session was re-asked because the connection changed")

            cancelAndIgnoreRemainingEvents()
        }
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

    // ---------------------------------------------- paging past maxEventsInGet

    /**
     * A quarter of a daily standup, which is where the chunking first matters.
     *
     * `maxEventsInGet` is **100** and it is counted in *occurrences* — a get handed three hundred
     * ids is refused outright, and the refusal is of the whole call, so a busy quarter would draw
     * nothing at all rather than its first hundred. Nothing in the suite had ever driven the fake
     * past one page, so the second request's `position` was unexercised: an off-by-one there
     * duplicates or silently drops a day, and both look like the server being wrong.
     *
     * The two sides page independently, which this also pins: one series, a hundred and twenty
     * occurrences, so the collapsed query is finished after the first request and only the expanded
     * one asks again.
     */
    @Test
    fun `a window holding more occurrences than one get allows is paged`() = runTest {
        val start = LocalDate.of(2026, 8, 3)
        val days = (0 until 120).map { start.plusDays(it.toLong()) }.toSet()

        val server =
            FakeCalendarServer(
                events =
                    mutableListOf(
                        recurring(
                            id = "10867",
                            title = "Standup",
                            start = "2026-08-03T09:00:00",
                            days = days,
                        )
                    )
            )

        val repository = calendarStack(database, calendarTransport(server))

        val result =
            repository.refresh(CalendarWindow(start, start.plusDays(120)))
                as CalendarRefresh.Refreshed

        // Two round trips rather than one: 120 occurrences at 100 a page.
        assertEquals(2, result.requests)
        assertEquals(120, result.occurrences)

        val cached = database.calendarEvents().occurrencesOf(standupKey)

        // Every day exactly once. A `position` that restarted at zero would put
        // the first hundred in twice, and the uid's start component would not
        // save it -- the occurrences are genuinely distinct rows.
        assertEquals(120, cached.size)
        assertEquals(days.map { it.toString() }.sorted(), cached.map { it.date }.sorted())
    }

    @Test
    fun `the second page asks from where the first stopped, and only for what is left`() = runTest {
        val start = LocalDate.of(2026, 8, 3)
        val days = (0 until 120).map { start.plusDays(it.toLong()) }.toSet()

        val server =
            FakeCalendarServer(
                events =
                    mutableListOf(
                        recurring(
                            id = "10867",
                            title = "Standup",
                            start = "2026-08-03T09:00:00",
                            days = days,
                        )
                    )
            )

        val repository = calendarStack(database, calendarTransport(server))

        repository.refresh(CalendarWindow(start, start.plusDays(120)))

        // Three windowed queries in all: the collapsed one, which finishes
        // in a single page because there is one series, and two expanded
        // ones. The collapsed side must *not* ask again -- a client that
        // paged both in lockstep would re-fetch the series 120 times over a
        // year of standups.
        assertEquals(1, server.collapsedQueries)
        assertEquals(2, server.expandedQueries)
    }

    // --------------------------------------------------- a floating series

    /**
     * A floating multi-day series, which is two departures from the ordinary case at once.
     *
     * **Floating** means no zone at all — a conference that is "9am wherever you are", stored by
     * the server as a bare wall clock in a UTC column rather than as an instant. The client must
     * neither convert it nor inherit the calendar's zone: the *fetch* window is deliberately the
     * union of the converted and naive bounds precisely so an event like this at the edge is
     * reachable, and the placement comes from the occurrence's own published wall clock.
     *
     * **Multi-day** means one occurrence occupies several rows, one per day it covers, so a day
     * view can draw it on each. Together they are the case REMAINING.md lists as never watched: a
     * floating recurring series.
     */
    @Test
    fun `a floating multi-day series is placed on every day it covers, in no zone at all`() =
        runTest {
            val server =
                FakeCalendarServer(
                    events =
                        mutableListOf(
                            recurring(
                                id = "10870",
                                title = "Workshop",
                                start = "2026-08-03T09:00:00",
                                // Two runs, a fortnight apart, each 30 hours long.
                                days = setOf(LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 17)),
                                duration = "PT30H",
                                timeZone = null,
                            )
                        )
                )

            val repository = calendarStack(database, calendarTransport(server))

            repository.refresh(CalendarWindow(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1)))

            val occurrences =
                database.calendarEvents().occurrencesOf(StoreKey.objectKey(testAccountKey, "10870"))

            // Each 30-hour run covers its own day and the next.
            assertEquals(
                listOf("2026-08-03", "2026-08-04", "2026-08-17", "2026-08-18"),
                occurrences.map { it.date }.sorted(),
            )

            // No zone, and not the calendar's Europe/Berlin either. Inheriting
            // it would turn "9am wherever you are" into an instant, which is a
            // different meeting for anybody who travels -- and the calendar's
            // zone is exactly the plausible wrong answer, because every other
            // event in this fixture does inherit it.
            assertTrue(
                occurrences.all { it.zoneId == null },
                "a floating occurrence inherits no zone: got ${occurrences.map { it.zoneId }}",
            )
            // And it is not an all-day event, which is the other thing a null
            // zone could be mistaken for. It has a wall-clock time.
            assertTrue(occurrences.none { it.isAllDay })
            assertEquals(
                listOf("2026-08-03T09:00:00", "2026-08-03T09:00:00"),
                occurrences.filter { it.date.startsWith("2026-08-0") }.map { it.startLocal },
            )
        }

    @Test
    fun `a floating series keeps its own wall clock rather than the device's offset`() = runTest {
        // The device is two hours ahead of UTC. A client that converted a
        // floating event out of the device zone would file the 09:00 run at
        // 07:00 or 11:00, and the day view would draw it in the wrong slot for
        // everyone but a user in Berlin.
        val server =
            FakeCalendarServer(
                events =
                    mutableListOf(
                        recurring(
                            id = "10870",
                            title = "Workshop",
                            start = "2026-08-03T09:00:00",
                            days = setOf(LocalDate.of(2026, 8, 3)),
                            duration = "PT2H",
                            timeZone = null,
                        )
                    )
            )

        val repository = calendarStack(database, calendarTransport(server), clock = berlinClock())

        repository.refresh(CalendarWindow(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1)))

        val occurrence =
            database
                .calendarEvents()
                .occurrencesOf(StoreKey.objectKey(testAccountKey, "10870"))
                .single()

        assertEquals("2026-08-03T09:00:00", occurrence.startLocal)
        assertEquals("2026-08-03T11:00:00", occurrence.endLocal)
        assertEquals("2026-08-03", occurrence.date)
    }

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
