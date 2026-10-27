package de.plmail.jmap.calendar

import de.plmail.jmap.methods.CalendarEventGet
import de.plmail.jmap.methods.CalendarEventPatch
import de.plmail.jmap.methods.CalendarEventSet
import de.plmail.jmap.methods.NewCalendarEvent
import de.plmail.jmap.protocol.AccountId
import de.plmail.jmap.protocol.CalendarEventId
import de.plmail.jmap.protocol.CalendarId
import de.plmail.jmap.protocol.Capability
import de.plmail.jmap.protocol.MethodResults
import de.plmail.jmap.protocol.Session
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Which calendars URN the app speaks, and the vendor one it still reads.
 *
 * The answers here are written out rather than captured, because no seed of the 8002 stack speaks
 * the IETF URN yet; they follow the server's own `IetfCalendarShape`, which is what turns plMail's
 * shape into the draft's under that URN: `calendarIds` for `calendarId`, one `recurrenceRule` for
 * `recurrenceRules`, `baseEventId` for `seriesId`, `plmail.org:isRecurring` for `isRecurring`, and
 * no `kind` or `source`.
 */
class CalendarDialectTest {

    private val account = AccountId("1")

    // --- which URN ---

    @Test
    fun `a server that speaks both is read through the IETF URN`() {
        val session = session(Capability.CALENDARS, Capability.PLMAIL_CALENDARS)

        assertEquals(CalendarDialect.IETF, session.calendarDialect)
        assertEquals(AccountId("1"), session.primaryCalendarAccount)
        assertEquals(
            listOf(Capability.CORE, Capability.CALENDARS),
            session.calendarDialect!!.using,
        )
    }

    @Test
    fun `a server from before the IETF URN is read through the vendor one`() {
        val session = session(Capability.PLMAIL_CALENDARS)

        assertEquals(CalendarDialect.PLMAIL, session.calendarDialect)
        assertEquals(AccountId("1"), session.primaryCalendarAccount)
        assertEquals(100, session.calendars(account)!!.maxEventsInGet)
    }

    @Test
    fun `a server with neither serves no calendars`() {
        val session = session()

        assertNull(session.calendarDialect)
        assertNull(session.primaryCalendarAccount)
        assertNull(session.calendars(account))
        assertFalse(session.supportsCalendars)
    }

    @Test
    fun `under the IETF URN a get is held to core's limit, and no horizon is published`() {
        // The draft gives a client no calendar limit, and the server holds its
        // calendar gets to maxObjectsInGet there instead.
        val limits = session(Capability.CALENDARS, Capability.PLMAIL_CALENDARS).calendars(account)!!

        assertEquals(500, limits.maxEventsInGet)
        assertFalse(limits.mayCreateCalendar)
        assertEquals("", limits.materialisedHorizon.future, "unknown, as on any bare capability")
    }

    // --- reading ---

    @Test
    fun `an IETF event is read in the draft's names`() {
        val events =
            get(
                CalendarDialect.IETF,
                """
                {"id":"10867","@type":"Event","calendarIds":{"10542":true},"title":"Standup",
                 "start":"2026-08-03T10:00:00","duration":"PT15M","timeZone":"Europe/Berlin",
                 "recurrenceRule":{"@type":"RecurrenceRule","frequency":"weekly",
                                   "byDay":[{"@type":"NDay","day":"mo"}]}},
                {"id":"10867_20260805T080000Z","calendarIds":{"10542":true},
                 "baseEventId":"10867","recurrenceId":"2026-08-05T10:00:00",
                 "start":"2026-08-05T10:00:00","recurrenceRule":null},
                {"id":"10865","calendarIds":{"10542":true},"title":"Dentist",
                 "start":"2026-08-04T09:00:00","recurrenceRule":null}
                """,
            )
        val (standup, occurrence, dentist) = events

        assertEquals(CalendarId("10542"), standup.calendarId)
        assertEquals("weekly", standup.recurrenceRule?.frequency)
        assertTrue(
            standup.isRecurring,
            "a server that publishes no plmail.org:isRecurring leaves the rule as the answer",
        )
        assertNull(standup.publishedRecurring)

        assertTrue(occurrence.isOccurrence)
        assertEquals(CalendarEventId("10867"), occurrence.writableId)

        assertFalse(dentist.isRecurring)
    }

    /** A rule the server could not convert: it is kept, and the event does not recur. */
    @Test
    fun `an IETF event's own word on recurring beats its rule`() {
        val (imported) =
            get(
                CalendarDialect.IETF,
                """
                {"id":"10870","calendarIds":{"10542":true},"title":"Imported",
                 "recurrenceRule":{"@type":"RecurrenceRule","frequency":"yearly"},
                 "plmail.org:isRecurring":false}
                """,
            )

        assertEquals("yearly", imported.recurrenceRule?.frequency)
        assertEquals(false, imported.publishedRecurring)
        assertFalse(imported.isRecurring)
    }

    @Test
    fun `a vendor event is read into the same names`() {
        val (standup, occurrence) =
            get(
                CalendarDialect.PLMAIL,
                """
                {"id":"10867","calendarId":"10542","title":"Standup",
                 "recurrenceRules":[{"@type":"RecurrenceRule","frequency":"weekly"}],
                 "isRecurring":false,"kind":null,"source":"manual"},
                {"id":"10867_20260805T080000Z","calendarId":"10542","seriesId":"10867",
                 "recurrenceRules":null}
                """,
            )

        assertEquals(CalendarId("10542"), standup.calendarId)
        assertEquals("weekly", standup.recurrenceRule?.frequency)
        assertFalse(
            standup.isRecurring,
            "the server's own answer wins: a rule it could not convert does not recur",
        )
        assertEquals(CalendarEventId("10867"), occurrence.writableId)
        assertNull(occurrence.recurrenceRule)
    }

    @Test
    fun `properties are asked for in the dialect's names`() {
        val asked = listOf("id", "calendarIds", "baseEventId", "recurrenceRule", "title")

        assertEquals(
            asked + "plmail.org:isRecurring",
            propertiesSent(CalendarDialect.IETF, asked),
            "whether it recurs is asked for wherever the rule is",
        )
        assertEquals(
            listOf("id", "calendarId", "seriesId", "recurrenceRules", "title", "isRecurring"),
            propertiesSent(CalendarDialect.PLMAIL, asked),
        )
        assertEquals(listOf("id"), propertiesSent(CalendarDialect.IETF, listOf("id")))
    }

    // --- writing ---

    @Test
    fun `a vendor create is sent in plMail's names`() {
        val create =
            CalendarEventSet(
                    account,
                    create = mapOf("e1" to newEvent(RecurrenceRule(frequency = "daily"))),
                    dialect = CalendarDialect.PLMAIL,
                )
                .arguments()["create"]!!
                .jsonObject
                .getValue("e1")
                .jsonObject

        assertEquals("10542", create["calendarId"]?.jsonPrimitive?.content)
        assertFalse(create.containsKey("calendarIds"))
        assertEquals(
            "daily",
            create["recurrenceRules"]!!
                .jsonArray
                .single()
                .jsonObject["frequency"]
                ?.jsonPrimitive
                ?.content,
        )
        assertFalse(create.containsKey("recurrenceRule"))
    }

    @Test
    fun `an IETF create is sent as built`() {
        val event = newEvent(RecurrenceRule(frequency = "daily"))
        val create =
            CalendarEventSet(account, create = mapOf("e1" to event))
                .arguments()["create"]!!
                .jsonObject
                .getValue("e1")

        assertEquals(event.toJson(), create)
    }

    @Test
    fun `a vendor patch moving calendars and stopping a series is sent in plMail's names`() {
        val patch =
            CalendarEventSet(
                    account,
                    update =
                        mapOf(
                            CalendarEventId("10867") to
                                CalendarEventPatch.build {
                                    calendarId(CalendarId("10543"))
                                    recurrenceRule(null)
                                }
                        ),
                    dialect = CalendarDialect.PLMAIL,
                )
                .arguments()["update"]!!
                .jsonObject
                .getValue("10867")
                .jsonObject

        assertEquals(setOf("calendarId", "recurrenceRules"), patch.keys)
        assertEquals("10543", patch["calendarId"]?.jsonPrimitive?.content)
        assertEquals(JsonNull, patch["recurrenceRules"])
    }

    @Test
    fun `a vendor create's answer is read in the draft's names`() {
        val created =
            CalendarEventSet(account, dialect = CalendarDialect.PLMAIL)
                .decode(
                    MethodResults.JMAP_JSON,
                    Json.parseToJsonElement(
                            """
                            {"accountId":"1","oldState":"fixed","newState":"fixed",
                             "created":{"c1":{"id":"10999","calendarId":"10542",
                                              "isRecurring":true,"sequence":0}}}
                            """
                        )
                        .jsonObject,
                )
                .created
                .getValue("c1")

        assertEquals(CalendarId("10542"), created.calendarId)
        assertEquals(true, created.isRecurring)
    }

    private fun newEvent(rule: RecurrenceRule?) =
        NewCalendarEvent(
            calendarId = CalendarId("10542"),
            title = "Standup",
            start = "2026-08-03T10:00:00",
            duration = "PT15M",
            recurrenceRule = rule,
        )

    private fun get(dialect: CalendarDialect, list: String): List<CalendarEvent> =
        CalendarEventGet(account, dialect = dialect)
            .decode(
                MethodResults.JMAP_JSON,
                Json.parseToJsonElement("""{"accountId":"1","state":"s1","list":[$list]}""")
                    .jsonObject,
            )
            .list

    private fun propertiesSent(dialect: CalendarDialect, asked: List<String>): List<String> =
        CalendarEventGet(account, ids = emptyList(), properties = asked, dialect = dialect)
            .arguments()["properties"]!!
            .jsonArray
            .map { it.jsonPrimitive.content }

    /** A one-account session advertising [calendars], at session level and on the account. */
    private fun session(vararg calendars: String): Session {
        val top = calendars.joinToString("") { ""","$it":{}""" }
        val perAccount =
            calendars.joinToString("") {
                if (it == Capability.PLMAIL_CALENDARS) ""","$it":{"maxEventsInGet":100}"""
                else ""","$it":{"maxCalendarsPerEvent":1,"mayCreateCalendar":false}"""
            }
        val primary = calendars.joinToString("") { ""","$it":"1"""" }

        return MethodResults.JMAP_JSON.decodeFromString(
            Session.serializer(),
            """
            {"capabilities":{"urn:ietf:params:jmap:core":{"maxObjectsInGet":500},
                             "urn:ietf:params:jmap:mail":{}$top},
             "accounts":{"1":{"name":"a@example.org","isPersonal":true,"isReadOnly":false,
                              "accountCapabilities":{"urn:ietf:params:jmap:mail":{}$perAccount}}},
             "primaryAccounts":{"urn:ietf:params:jmap:mail":"1"$primary},
             "username":"a@example.org","apiUrl":"https://x/jmap/api",
             "downloadUrl":"https://x/d","uploadUrl":"https://x/u","state":"0"}
            """,
        )
    }
}
