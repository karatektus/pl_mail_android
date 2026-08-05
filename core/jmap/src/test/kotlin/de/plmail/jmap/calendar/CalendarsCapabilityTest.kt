package de.plmail.jmap.calendar

import de.plmail.jmap.Fixture
import de.plmail.jmap.protocol.AccountId
import de.plmail.jmap.protocol.CalendarsCapability
import de.plmail.jmap.protocol.Capability
import de.plmail.jmap.protocol.Session
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject

/**
 * How the session announces calendars.
 *
 * Captured from a two-account login, which is the shape that matters: only one of the two accounts
 * serves calendars, and the difference is invisible from the session's top level.
 */
class CalendarsCapabilityTest {

    private val session = Fixture.decode<Session>("session-calendars.json")

    @Test
    fun `the vendor URN is advertised at the session level`() {
        assertTrue(session.supportsCalendars)
        assertFalse(
            Fixture.decode<Session>("session.json").supportsCalendars,
            "an instance without the extension must degrade rather than fail",
        )
    }

    @Test
    fun `the calendar account is read from its own primaryAccounts key`() {
        // It equals the mail primary on this server, which is exactly what would
        // let a client reusing primaryMailAccount go unnoticed until an instance
        // disagreed.
        assertEquals(AccountId("1"), session.primaryCalendarAccount)
        assertEquals(session.primaryMailAccount, session.primaryCalendarAccount)
        assertEquals("1", session.primaryAccounts[Capability.CALENDARS])
    }

    @Test
    fun `only one account of the two carries the calendar capability`() {
        // The second connected mailbox has no calendars entry at all. Fanning a
        // calendar request over every account the way the unified inbox fans out
        // Email/query answers accountNotSupportedByMethod on all but one.
        assertNotNull(session.calendars(AccountId("1")))
        assertNull(session.calendars(AccountId("2")))
        assertEquals(listOf(AccountId("1"), AccountId("2")), session.accountIds)
    }

    @Test
    fun `the get limit is a fifth of core's, and must not be confused with it`() {
        val calendars = session.calendars(AccountId("1"))!!

        assertEquals(100, calendars.maxEventsInGet)
        assertEquals(500, session.core.maxObjectsInGet)
        assertEquals(500, calendars.maxEventsInSet)
    }

    @Test
    fun `no client may create a calendar`() {
        assertFalse(session.calendars(AccountId("1"))!!.mayCreateCalendar)
    }

    @Test
    fun `the materialised horizon is carried as two opaque strings`() {
        // PHP relative-date expressions, not ISO 8601 durations. Nothing in the
        // client may parse them; they exist so a query outside the window can be
        // explained rather than looking like an empty month.
        val horizon = session.calendars(AccountId("1"))!!.materialisedHorizon

        assertEquals("-1 year", horizon.past)
        assertEquals("+2 years", horizon.future)
    }

    @Test
    fun `a capability object missing its limits falls back rather than failing to parse`() {
        // The mail capability already publishes `null` for two of its own limits,
        // so a calendar instance omitting one is the ordinary kind of surprise
        // rather than an exotic one.
        val bare = CalendarsCapability.from(JsonObject(emptyMap()))

        assertEquals(100, bare.maxEventsInGet)
        assertEquals(500, bare.maxEventsInSet)
        assertEquals("", bare.materialisedHorizon.future)
    }
}
