package de.plmail.jmap.calendar

import de.plmail.jmap.methods.CalendarEventGet
import de.plmail.jmap.methods.CalendarEventPatch
import de.plmail.jmap.methods.CalendarEventQuery
import de.plmail.jmap.methods.CalendarEventSet
import de.plmail.jmap.methods.CalendarGet
import de.plmail.jmap.methods.EventTimeZone
import de.plmail.jmap.methods.NewCalendarEvent
import de.plmail.jmap.methods.RecurrenceOverride
import de.plmail.jmap.protocol.AccountId
import de.plmail.jmap.protocol.CalendarEventId
import de.plmail.jmap.protocol.CalendarId
import de.plmail.jmap.protocol.Capability
import de.plmail.jmap.protocol.RequestBuilder
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What the client *sends* to the calendar surface.
 *
 * These are the mistakes the server reports late or vaguely: a window it will not say is missing, a
 * timeZone whose absence and whose null mean different events, a `guard` on a state that cannot
 * change, and a patch path that mail accepts and this method refuses.
 */
class CalendarWriteMethodsTest {

    private val account = AccountId("1")
    private val personal = CalendarId("10542")
    private val window = CalendarEventFilter("2026-08-01T00:00:00", "2026-09-01T00:00:00")

    private fun newEvent(
        title: String = "Team-Standup",
        timeZone: EventTimeZone? = null,
        showWithoutTime: Boolean = false,
        location: String? = null,
        recurrenceRule: RecurrenceRule? = null,
        recurrenceOverrides: Map<String, RecurrenceOverride> = emptyMap(),
    ) =
        NewCalendarEvent(
            calendarId = personal,
            title = title,
            start = "2026-08-03T10:00:00",
            duration = "PT15M",
            timeZone = timeZone,
            showWithoutTime = showWithoutTime,
            location = location,
            recurrenceRule = recurrenceRule,
            recurrenceOverrides = recurrenceOverrides,
        )

    // --- using ---

    @Test
    fun `a calendar request declares the vendor URN and not mail`() {
        // The opposite of the push URN, which the server advertises and refuses
        // in `using`. Omitting this one fails the calendar call; including mail
        // declares a capability the request never uses.
        val request = RequestBuilder(Capability.USING_CALENDARS).apply { add(CalendarGet(account)) }

        val using = request.build()["using"]!!.jsonArray.map { it.jsonPrimitive.content }

        assertEquals(listOf(Capability.CORE, Capability.CALENDARS), using)
        assertFalse(using.contains(Capability.MAIL))
        assertFalse(using.contains(Capability.PUSH))
    }

    // --- query ---

    @Test
    fun `the query window cannot be left out`() {
        // A blank end is a method-level invalidArguments with no description, so
        // the failure has to happen here rather than on the wire.
        assertFailsWith<IllegalArgumentException> { CalendarEventFilter("", "2026-09-01T00:00:00") }
        assertFailsWith<IllegalArgumentException> { CalendarEventFilter("2026-08-01T00:00:00", "") }
    }

    @Test
    fun `the filter carries the window and nothing that would be refused`() {
        // No operator, no conditions array: FilterOperator is refused outright,
        // so there is deliberately nothing here to compose with.
        val filter = window.copy(inCalendar = personal).toJson()

        assertEquals(setOf("after", "before", "inCalendar"), filter.keys)
        assertEquals("10542", filter["inCalendar"]?.jsonPrimitive?.content)
    }

    @Test
    fun `paging is position and limit, with no sort argument`() {
        val arguments = CalendarEventQuery(account, window, position = 20, limit = 50).arguments()

        assertEquals(setOf("accountId", "filter", "position", "limit"), arguments.keys)
        assertFalse(arguments.containsKey("sort"), "any sort raises unsupportedSort")
        assertFalse(arguments.containsKey("anchor"), "anchor raises unsupportedFilter")
    }

    @Test
    fun `a negative position is rejected here rather than on the wire`() {
        assertFailsWith<IllegalArgumentException> {
            CalendarEventQuery(
                account,
                window,
                position = -1,
            )
        }
    }

    // --- get ---

    @Test
    fun `a get is capped at the calendar limit, not core's object limit`() {
        // 100, not 500. A client chunking by maxObjectsInGet would have every
        // calendar request refused.
        val ids = (1..CalendarEventGet.MAX_EVENTS_IN_GET).map { CalendarEventId(it.toString()) }

        CalendarEventGet(account, ids)

        assertFailsWith<IllegalArgumentException> {
            CalendarEventGet(account, ids + CalendarEventId("101"))
        }
    }

    // --- set ---

    @Test
    fun `a set has no way to express ifInState`() {
        // The state is the constant "fixed", so a guard on it can never fail and
        // would read as conflict detection while providing none. The server
        // refuses it with invalidArguments; this makes it unrepresentable.
        val arguments =
            CalendarEventSet(account, destroy = listOf(CalendarEventId("7"))).arguments()

        assertEquals(setOf("accountId", "destroy"), arguments.keys)
        assertFalse(arguments.containsKey("ifInState"))
    }

    @Test
    fun `an empty set omits create, update and destroy entirely`() {
        assertEquals(setOf("accountId"), CalendarEventSet(account).arguments().keys)
    }

    @Test
    fun `a create sends exactly the writable properties`() {
        // Anything outside this set comes back as invalidProperties naming the
        // offenders, after the user has typed the whole event.
        val create = newEvent().toJson()

        assertEquals(
            setOf("@type", "calendarId", "title", "start", "duration", "status"),
            create.keys,
        )
        assertEquals("Event", create["@type"]?.jsonPrimitive?.content)
        assertFalse(create.containsKey("privacy"), "published but not settable")
        assertFalse(create.containsKey("isRecurring"), "derived server-side")
    }

    @Test
    fun `an omitted timeZone and a floating one are different events`() {
        // Omitted means the calendar's zone. Explicit null means the same
        // wall-clock time everywhere. Modelling both as null makes a birthday
        // move when the user travels, or fail to.
        assertFalse(newEvent().toJson().containsKey("timeZone"))
        assertEquals(JsonNull, newEvent(timeZone = EventTimeZone.Floating).toJson()["timeZone"])
        assertEquals(
            "Europe/Berlin",
            newEvent(timeZone = EventTimeZone.Zone("Europe/Berlin"))
                .toJson()["timeZone"]
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun `a place is one label under an arbitrary key, and absent when there is none`() {
        // The server keeps only `@type` and `name` from a Location, so offering
        // coordinates would offer something that does not survive the save.
        val located = newEvent(location = "Praxis Dr. Weber").toJson()
        val place = located["locations"]!!.jsonObject.values.single().jsonObject

        assertEquals("Location", place["@type"]?.jsonPrimitive?.content)
        assertEquals("Praxis Dr. Weber", place["name"]?.jsonPrimitive?.content)
        assertFalse(newEvent().toJson().containsKey("locations"))
    }

    @Test
    fun `a recurrence rule is sent as a one-element array with lowercase day codes`() {
        // JSCalendar spells days `mo`; iCalendar spells them `MO`. An importer
        // written against RFC 5545 gets this wrong silently.
        val create =
            newEvent(
                    recurrenceRule =
                        RecurrenceRule(
                            frequency = "weekly",
                            byDay = listOf(NDay(day = "mo"), NDay(day = "we")),
                        )
                )
                .toJson()

        val rules = create["recurrenceRules"]!!.jsonArray

        assertEquals(1, rules.size, "the server refuses a second rule")
        assertEquals("RecurrenceRule", rules[0].jsonObject["@type"]?.jsonPrimitive?.content)
        assertEquals(
            listOf("mo", "we"),
            rules[0].jsonObject["byDay"]!!.jsonArray.map {
                it.jsonObject["day"]!!.jsonPrimitive.content
            },
        )
    }

    @Test
    fun `an override is keyed by the occurrence's original start`() {
        // Moving an occurrence keeps the key it had before the move: the key
        // says which occurrence, the start inside says where it went.
        val create =
            newEvent(
                    recurrenceOverrides =
                        mapOf(
                            "2026-08-07T10:00:00" to
                                RecurrenceOverride.build {
                                    title("Standup (Retro-Woche)")
                                    start("2026-08-07T11:00:00")
                                }
                        )
                )
                .toJson()

        val override =
            create["recurrenceOverrides"]!!.jsonObject.getValue("2026-08-07T10:00:00").jsonObject

        assertEquals("2026-08-07T11:00:00", override["start"]?.jsonPrimitive?.content)
    }

    @Test
    fun `cancelling one occurrence is an exclusion, not a destroy`() {
        // There is no id for a single occurrence, so destroy would take the whole
        // series with it.
        assertEquals(
            true,
            RecurrenceOverride.EXCLUDED.toJson()["excluded"]?.jsonPrimitive?.content?.toBoolean(),
        )
    }

    // --- patch ---

    @Test
    fun `a patch sends whole properties, never a pointer`() {
        // Email/set accepts `mailboxIds/42`; this method answers invalidPatch
        // for anything with a slash in the key.
        val patch = CalendarEventPatch.build { title("Zahnarzt (verschoben)") }.toJson()

        assertEquals(setOf("title"), patch.keys)
        assertTrue(patch.keys.none { it.contains('/') })
    }

    @Test
    fun `moving an event between calendars is a plain property change`() {
        val patch = CalendarEventPatch.build { calendarId(CalendarId("10543")) }.toJson()

        assertEquals("10543", patch["calendarId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `clearing a description sends null rather than an empty string`() {
        assertEquals(
            JsonNull,
            CalendarEventPatch.build { description("  ") }.toJson()["description"],
        )
        assertEquals(JsonNull, CalendarEventPatch.build { location(null) }.toJson()["locations"])
    }

    @Test
    fun `stopping a series recurring nulls the whole rules array`() {
        assertEquals(
            JsonNull,
            CalendarEventPatch.build { recurrenceRule(null) }.toJson()["recurrenceRules"],
        )
    }

    @Test
    fun `overrides replace the whole map`() {
        // The server takes the map wholesale, so sending one override drops every
        // other exception on the series. Read them, change the one, send them all.
        val patch =
            CalendarEventPatch.build {
                    recurrenceOverrides(mapOf("2026-08-10T10:00:00" to RecurrenceOverride.EXCLUDED))
                }
                .toJson()

        assertEquals(setOf("2026-08-10T10:00:00"), patch["recurrenceOverrides"]!!.jsonObject.keys)
    }

    @Test
    fun `an update is keyed by the event id and carries the patch verbatim`() {
        val arguments =
            CalendarEventSet(
                    account,
                    update =
                        mapOf(
                            CalendarEventId("10865") to
                                CalendarEventPatch.build { start("2026-08-06T10:30:00") }
                        ),
                )
                .arguments()

        val patch = arguments["update"]?.jsonObject?.get("10865")?.jsonObject

        assertEquals("2026-08-06T10:30:00", patch?.get("start")?.jsonPrimitive?.content)
    }

    @Test
    fun `a create is keyed by a creation id usable later in the same request`() {
        val arguments = CalendarEventSet(account, create = mapOf("e1" to newEvent())).arguments()

        assertContains(arguments["create"]!!.jsonObject.keys, "e1")
    }
}
