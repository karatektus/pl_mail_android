package de.plmail.jmap.calendar

import de.plmail.jmap.Fixture
import de.plmail.jmap.methods.CalendarEventGet
import de.plmail.jmap.methods.CalendarEventQuery
import de.plmail.jmap.methods.CalendarEventSet
import de.plmail.jmap.methods.CalendarGet
import de.plmail.jmap.protocol.AccountId
import de.plmail.jmap.protocol.CalendarEventId
import de.plmail.jmap.protocol.CalendarId
import de.plmail.jmap.protocol.JmapError
import de.plmail.jmap.protocol.JmapMethod
import de.plmail.jmap.protocol.MethodHandle
import de.plmail.jmap.protocol.MethodResults
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonPrimitive

/**
 * Decoding real calendar responses.
 *
 * Every fixture here came off the live 8002 stack. The assertions worth having are the ones that
 * contradict the shape a reader would assume from the mail surface: an event whose `timeZone` key
 * is absent entirely, an `isRecurring` that is not `recurrenceRules != null`, and a `state` that is
 * a constant rather than a cursor.
 */
class CalendarMethodResultsTest {

    private val account = AccountId("1")
    private val window = CalendarEventFilter("2026-08-01T00:00:00", "2026-09-01T00:00:00")

    private fun results(fixture: String) =
        MethodResults.decode(Fixture.read(fixture).encodeToByteArray(), status = 200)

    // The fixtures carry the server's own call ids, so handles are built against
    // those rather than the ones RequestBuilder would have generated.
    private fun <R> handle(method: JmapMethod<R>, callId: String) = MethodHandle(method, callId)

    private fun calendars() =
        results("calendar-get.json").result(handle(CalendarGet(account), "c0"))

    private fun eventGet(fixture: String, callId: String) =
        results(fixture).result(handle(CalendarEventGet(account), callId))

    // --- Calendar/get ---

    @Test
    fun `decodes calendars with their rights`() {
        val personal = calendars().list.first()

        assertEquals(CalendarId("10542"), personal.id)
        assertEquals("Personal", personal.name)
        assertEquals("Europe/Berlin", personal.timeZone)
        assertTrue(personal.isDefault)
        assertTrue(personal.myRights.mayAddItems)
        assertTrue(personal.myRights.mayUpdateAll)
        assertTrue(personal.myRights.mayRemoveItems)
    }

    @Test
    fun `no calendar may be deleted, not even the default one`() {
        // The server owns which calendars exist -- mayCreateCalendar is false in
        // the account capability too. A UI offering delete would be offering
        // something that always fails.
        assertTrue(calendars().list.all { !it.myRights.mayDelete })
    }

    @Test
    fun `calendar colour is hex, not the label colour token vocabulary`() {
        // Mailbox.color is `blue`/`amber`/`pink`, resolved per theme. This one is
        // a literal the user picked, and a resolver written for one vocabulary
        // draws nothing for the other.
        assertEquals("#2563eb", calendars().list.first().color)
    }

    @Test
    fun `the role vocabulary is open and carries plMail's own values`() {
        // `account` is not an RFC 8984 role. Kept as a string so a role added on
        // the next server release still reaches the client.
        assertEquals(listOf("default", "account", "account"), calendars().list.map { it.role })
    }

    @Test
    fun `calendar state is the constant fixed, not a cursor`() {
        // There is no Calendar/changes to hand it back to. Storing it as a sync
        // cursor would build a delta path that can never fire.
        assertEquals("fixed", calendars().state)
    }

    @Test
    fun `the default calendar is found by the flag rather than by the role name`() {
        assertEquals(CalendarId("10542"), calendars().default()?.id)
        assertEquals(3, calendars().writable().size)
    }

    // --- CalendarEvent/query paired with get ---

    @Test
    fun `a query orders series by their first occurrence in the window`() {
        // Not by id: ascending id order here would be 10865, 10866, 10867, 10868.
        // The standup starts on the 3rd and sorts first despite the highest id.
        val query =
            results("event-query-get.json")
                .result(
                    handle(
                        CalendarEventQuery(
                            account,
                            window,
                        ),
                        "q0",
                    )
                )

        assertEquals(listOf("10867", "10865", "10868", "10866"), query.ids.map { it.value })
        assertEquals(4, query.total)
        assertEquals("fixed", query.queryState)
        assertFalse(query.canCalculateChanges, "there is no CalendarEvent/queryChanges")
    }

    @Test
    fun `the back-referenced get answers the query's own ids`() {
        val results = results("event-query-get.json")
        val requested = results.result(handle(CalendarEventQuery(account, window), "q0")).ids
        val returned = results.result(handle(CalendarEventGet(account), "g0"))

        assertEquals(requested, returned.list.map { it.id })
        assertEquals(
            requested,
            returned.ordered(requested).map { it.id },
            "ordered() must be a no-op while the server preserves order, and the safety net if " +
                "it stops -- Email/get and Thread/get both reorder",
        )
    }

    @Test
    fun `a request pairs query and get through a hash-ids back-reference`() {
        val query = handle(CalendarEventQuery(account, window), "q0")
        val arguments = CalendarEventGet.byReference(account, query.reference("/ids")).arguments()

        // Without the '#' the server sees an unknown argument called `ids`
        // holding an object and answers as though nothing was sent.
        assertContains(arguments.keys, "#ids")
        assertFalse(arguments.containsKey("ids"))
        assertEquals(
            "CalendarEvent/query",
            arguments
                .getValue("#ids")
                .let { it as kotlinx.serialization.json.JsonObject }["name"]
                ?.jsonPrimitive
                ?.content,
        )
    }

    // --- The event object ---

    @Test
    fun `an all-day event has no timeZone key at all`() {
        // Not null, not the calendar's -- absent. A model that defaulted this to
        // the calendar's zone would move a birthday when the user travels, and a
        // non-null Kotlin type would fail to decode the event outright.
        val allDay = eventGet("event-query-get.json", "g0").list.single { it.id.value == "10866" }

        assertNull(allDay.timeZone)
        assertTrue(allDay.showWithoutTime)
        assertEquals("P1D", allDay.duration)
    }

    @Test
    fun `start is a local date-time with no offset and no trailing Z`() {
        val standup = eventGet("event-query-get.json", "g0").list.first()

        assertEquals("2026-08-03T10:00:00", standup.start)
        assertFalse(standup.start!!.endsWith("Z"), "parsing this as an instant shifts every event")
        // The envelope timestamps *are* instants, which is the trap: two
        // date-time spellings in one object.
        assertTrue(standup.created!!.endsWith("Z"))
    }

    @Test
    fun `a location is a map of at most one label`() {
        val dentist = eventGet("event-query-get.json", "g0").list.single { it.id.value == "10865" }

        assertEquals("Praxis Dr. Weber", dentist.locations.values.single().name)
        assertEquals("Kontrolle", dentist.description)
    }

    @Test
    fun `an event with neither description nor locations decodes`() {
        val dinner = eventGet("event-query-get.json", "g0").list.single { it.id.value == "10868" }

        assertNull(dinner.description)
        assertEquals(emptyMap(), dinner.locations)
        assertEquals("tentative", dinner.status)
    }

    @Test
    fun `isRecurring is read from the wire rather than derived from the rules`() {
        // A rule plMail cannot convert is stored verbatim and expands to one
        // occurrence, so `recurrenceRules != null` is a different question and
        // the two disagree on exactly the imported events users notice.
        val events = eventGet("event-query-get.json", "g0").list
        val standup = events.first()
        val dentist = events.single { it.id.value == "10865" }

        assertTrue(standup.isRecurring)
        assertEquals("weekly", standup.recurrenceRules.single().frequency)
        assertEquals(
            listOf("mo", "we", "fr"),
            standup.recurrenceRules.single().byDay.map { it.day },
        )

        assertFalse(dentist.isRecurring)
        assertEquals(emptyList(), dentist.recurrenceRules)
    }

    @Test
    fun `overrides are kept as raw json keyed by the occurrence's original start`() {
        // The key says which occurrence; the `start` inside says where it moved
        // to. Typing the value would drop whatever this version has not heard of.
        val standup = eventGet("event-overrides.json", "g1").list.single()
        val override = standup.recurrenceOverrides.getValue("2026-08-07T10:00:00")

        assertEquals("2026-08-07T11:00:00", override["start"]?.jsonPrimitive?.content)
        assertEquals("Standup (Retro-Woche)", override["title"]?.jsonPrimitive?.content)
    }

    @Test
    fun `plMail's own event extensions survive decoding`() {
        val standup = eventGet("event-query-get.json", "g0").list.first()

        assertEquals("manual", standup.source)
        assertNull(standup.kind, "kind is published as null rather than omitted")
        assertEquals("public", standup.privacy)
    }

    @Test
    fun `missing ids come back in notFound`() {
        val get = eventGet("event-errors-update-destroy.json", "g-notfound")

        assertEquals(listOf(CalendarEventId("99999")), get.notFound)
        assertEquals(1, get.list.size)
    }

    // --- CalendarEvent/set ---

    @Test
    fun `a create echoes only what the server decided`() {
        val set = results("event-set-create.json").result(handle(CalendarEventSet(account), "s0"))

        assertEquals(setOf("e1", "e2", "e3", "e4"), set.created.keys)

        val standup = set.created.getValue("e3")

        assertEquals(CalendarEventId("10867"), standup.id)
        assertEquals("df7a06897f850359167280505e68e0ef@plmail", standup.uid)
        assertEquals(CalendarId("10542"), standup.calendarId)
        assertTrue(standup.isRecurring, "derived server-side, even on the create response")
        assertEquals(0, standup.sequence)
        assertFalse(set.hasFailures)
    }

    @Test
    fun `both states are the same constant even across a change that happened`() {
        val set =
            results("event-errors-update-destroy.json")
                .result(
                    handle(
                        CalendarEventSet(account),
                        "upd",
                    )
                )

        assertEquals("fixed", set.oldState)
        assertEquals("fixed", set.newState)
    }

    @Test
    fun `an update reports a null value and a destroy reports a plain id list`() {
        val set =
            results("event-errors-update-destroy.json")
                .result(
                    handle(
                        CalendarEventSet(account),
                        "upd",
                    )
                )

        assertEquals(setOf("10865"), set.updated.keys)
        assertNull(set.updated.getValue("10865"), "a successful update says nothing but its key")
        assertEquals(listOf(CalendarEventId("10868")), set.destroyed)
    }

    // --- Errors ---

    @Test
    fun `a missing window is a bare invalidArguments that does not say which end`() {
        // Which is why CalendarEventFilter takes both as constructor arguments:
        // there is nothing in the response to debug from.
        val failure =
            results("event-errors-update-destroy.json")
                .failure(handle(CalendarEventQuery(account, window), "bad-window"))

        assertNotNull(failure)
        assertEquals("invalidArguments", failure.type)
        assertNull(failure.description)
    }

    @Test
    fun `sorting is refused with its own error type`() {
        // Distinct from the mail surface's unsupportedFilter, so a query builder
        // can at least tell this mistake from a bad filter.
        val failure =
            results("event-errors-update-destroy.json")
                .failure(handle(CalendarEventQuery(account, window), "bad-sort"))

        assertEquals("unsupportedSort", failure?.type)
    }

    @Test
    fun `any account but the calendar one is refused by the method`() {
        // Calendars are user-scoped while JMAP accounts are per connected
        // mailbox, so fanning a calendar request out the way the unified inbox
        // fans out Email/query fails on every account but one.
        val results = results("event-errors-update-destroy.json")
        val handle = handle(CalendarGet(account), "bad-account")

        assertEquals("accountNotSupportedByMethod", results.failure(handle)?.type)
        assertFailsWith<JmapError.MethodFailed> { results.result(handle) }
    }

    @Test
    fun `a refused property is named per object rather than failing the call`() {
        // The call succeeds; one creation inside it did not. A client that only
        // looked at method-level errors would report the event saved.
        val set =
            results("event-errors-update-destroy.json")
                .result(
                    handle(
                        CalendarEventSet(account),
                        "bad-props",
                    )
                )

        val error = set.notCreated.getValue("bad")

        assertEquals("invalidProperties", error.type)
        assertEquals(listOf("participants", "alerts"), error.properties)
        assertTrue(set.hasFailures)
        assertEquals(error, set.firstFailure())
    }

    @Test
    fun `a badly spelled start is reported as an invalid property with the rule in it`() {
        val set =
            results("event-errors-update-destroy.json")
                .result(
                    handle(
                        CalendarEventSet(account),
                        "bad-start",
                    )
                )

        val error = set.notCreated.getValue("badz")

        assertEquals(listOf("start"), error.properties)
        assertContains(error.description!!, "no offset and no trailing Z")
    }
}
