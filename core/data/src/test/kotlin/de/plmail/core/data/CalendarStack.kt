package de.plmail.core.data

import de.plmail.core.database.PlMailDatabase
import de.plmail.core.datastore.CredentialStore
import de.plmail.core.datastore.ServerConnection
import de.plmail.jmap.client.Credential
import de.plmail.jmap.client.HttpResponse
import de.plmail.jmap.client.JmapTransport
import de.plmail.jmap.client.KeyFingerprint
import de.plmail.jmap.client.ParsedAddress
import de.plmail.jmap.client.ServerAddress
import de.plmail.jmap.client.StreamingTransport
import de.plmail.jmap.testing.RecordingTransport
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The calendar the fake server serves, and the one every fixture below files events into. */
internal const val TEST_CALENDAR_ID = "10542"

internal val testCalendarKey: String = "$testAccountKey#$TEST_CALENDAR_ID"

/**
 * Midweek of the seeded week, so a window either side of it is inside the materialised horizon.
 *
 * Fixed rather than `LocalDate.now()`: the repository compares the refreshed window against today
 * to decide whether the server may have answered part of it from a partial index, and a suite that
 * took the real date would start saying something different on a day nobody chose.
 */
internal val testClock: Clock = Clock.fixed(Instant.parse("2026-08-05T12:00:00Z"), ZoneOffset.UTC)

/**
 * A calendar server that answers from day membership rather than from a script.
 *
 * The one thing the repository is *about* is asking the server which days a recurring series falls
 * on, so a transport keyed on call order — or one returning a canned month — would answer the
 * question the code is supposed to be asking. This one holds the truth as a set of days per event
 * and derives both answers from it: a windowed query returns every series with a day inside the
 * window, and a one-day query returns every series with that day. Which is exactly what the real
 * server does, and it means a test can move a day and watch the placement follow.
 */
internal class FakeCalendarServer(
    /** Null publishes no calendars capability at all, which is the no-calendar-account case. */
    var calendarAccount: String? = TEST_ACCOUNT_ID,
    var events: MutableList<FakeEvent> = mutableListOf(),
    /** The `CalendarEvent/set` result arguments, when a test exercises a write. */
    var onSet: ((JsonObject) -> String)? = null,
) {
    /** Every window a `CalendarEvent/query` asked about, in order. Half the value of the fake. */
    val windows = mutableListOf<Pair<LocalDate, LocalDate>>()

    /** How many one-day windows were asked about, which is what batching is measured in. */
    val dayProbes: Int
        get() = windows.count { (from, to) -> from.plusDays(1) == to }
}

/** One event, and the days the server would report it on. */
internal data class FakeEvent(
    val id: String,
    val title: String,
    val start: String,
    val days: Set<LocalDate>,
    val duration: String = "PT1H",
    val isRecurring: Boolean = false,
    val timeZone: String? = "Europe/Berlin",
    val showWithoutTime: Boolean = false,
    /** Raw JSON for `recurrenceOverrides`, or null. */
    val overrides: String? = null,
    val calendarId: String = TEST_CALENDAR_ID,
)

/** A one-off on one day, placed from its own start. */
internal fun oneOff(
    id: String,
    title: String,
    start: String,
    duration: String = "PT1H",
    showWithoutTime: Boolean = false,
    timeZone: String? = "Europe/Berlin",
): FakeEvent =
    FakeEvent(
        id = id,
        title = title,
        start = start,
        days = setOf(LocalDateTime.parse(start).toLocalDate()),
        duration = duration,
        showWithoutTime = showWithoutTime,
        timeZone = timeZone,
    )

/** A series the server reports on [days], with no rule the client is ever shown. */
internal fun recurring(
    id: String,
    title: String,
    start: String,
    days: Set<LocalDate>,
    duration: String = "PT15M",
    overrides: String? = null,
): FakeEvent =
    FakeEvent(
        id = id,
        title = title,
        start = start,
        days = days,
        duration = duration,
        isRecurring = true,
        overrides = overrides,
    )

/**
 * The transport in front of [server].
 *
 * Answers each call in a batch by name, which is what makes the day probes testable at all: a probe
 * request carries up to thirty-one `CalendarEvent/query` calls and every one needs its own answer
 * against its own call id.
 */
internal fun calendarTransport(server: FakeCalendarServer): RecordingTransport =
    RecordingTransport { request ->
        val body =
            if (request.url.endsWith("/.well-known/jmap")) calendarSession(server.calendarAccount)
            else answer(server, request.body!!.decodeToString())

        HttpResponse(200, mapOf("Content-Type" to "application/json"), body.encodeToByteArray())
    }

private fun answer(server: FakeCalendarServer, requestBody: String): String {
    val calls = Json.parseToJsonElement(requestBody).jsonObject["methodCalls"]!!.jsonArray
    val responses = mutableListOf<String>()

    // The ids the previous call in this batch produced, which is what a
    // back-referenced `CalendarEvent/get` resolves to. Held rather than parsed
    // out of the `#ids` argument because the point of the reference is that the
    // client never names them.
    var lastQueried = emptyList<String>()

    calls.forEach { call ->
        val entry = call.jsonArray
        val name = entry[0].jsonPrimitive.content
        val arguments = entry[1].jsonObject
        val callId = entry[2].jsonPrimitive.content

        when (name) {
            "Calendar/get" -> responses += """["Calendar/get", $CALENDARS_RESULT, "$callId"]"""

            "CalendarEvent/query" -> {
                val filter = arguments["filter"]!!.jsonObject
                val from =
                    LocalDateTime.parse(filter["after"]!!.jsonPrimitive.content).toLocalDate()
                val to = LocalDateTime.parse(filter["before"]!!.jsonPrimitive.content).toLocalDate()

                server.windows += from to to

                val matched =
                    server.events
                        .filter { event -> event.days.any { it >= from && it < to } }
                        .sortedBy { event -> event.days.filter { it >= from && it < to }.min() }

                lastQueried = matched.map { it.id }

                val ids = lastQueried.joinToString(",") { "\"$it\"" }

                responses +=
                    """
                    ["CalendarEvent/query",
                     {"accountId":"$TEST_ACCOUNT_ID","queryState":"fixed",
                      "canCalculateChanges":false,"position":0,"ids":[$ids],
                      "total":${matched.size},"limit":500},"$callId"]
                    """
            }

            "CalendarEvent/get" -> {
                val list =
                    server.events.filter { it.id in lastQueried }.joinToString(",") { it.toJson() }

                responses +=
                    """
                    ["CalendarEvent/get",
                     {"accountId":"$TEST_ACCOUNT_ID","state":"fixed","list":[$list],
                      "notFound":[]},"$callId"]
                    """
            }

            "CalendarEvent/set" ->
                responses += """["CalendarEvent/set", ${server.onSet!!(arguments)}, "$callId"]"""

            else -> error("The fake calendar server was asked for $name.")
        }
    }

    return """{"sessionState":"fixed","methodResponses":[${responses.joinToString(",")}]}"""
}

private fun FakeEvent.toJson(): String = buildString {
    append("""{"id":"$id","uid":"$id@plmail","calendarId":"$calendarId",""")
    append(""""title":"$title","start":"$start","duration":"$duration",""")
    timeZone?.let { append(""""timeZone":"$it",""") }
    if (showWithoutTime) append(""""showWithoutTime":true,""")
    overrides?.let { append(""""recurrenceOverrides":$it,""") }
    append(""""status":"confirmed","sequence":0,"isRecurring":$isRecurring}""")
}

/**
 * The `CalendarEvent/set` result of a create that worked.
 *
 * [isRecurring] is echoed rather than inferred from what was sent, because that is what the server
 * does: a rule it cannot convert is stored verbatim and expands to one occurrence.
 */
internal fun createdEvent(id: String, isRecurring: Boolean = false): String =
    """
    {"accountId":"$TEST_ACCOUNT_ID","oldState":"fixed","newState":"fixed",
     "created":{"c1":{"id":"$id","uid":"$id@plmail","calendarId":"$TEST_CALENDAR_ID",
                      "isRecurring":$isRecurring,"sequence":0}},
     "notCreated":{},"updated":{},"notUpdated":{},"destroyed":[],"notDestroyed":{}}
    """

/** A per-object refusal, which is how this surface reports one — not a method-level error. */
internal fun refusedCreate(type: String, description: String): String =
    """
    {"accountId":"$TEST_ACCOUNT_ID","oldState":"fixed","newState":"fixed",
     "created":{},"notCreated":{"c1":{"type":"$type","description":"$description"}},
     "updated":{},"notUpdated":{},"destroyed":[],"notDestroyed":{}}
    """

internal fun refusedDestroy(id: String, type: String, description: String): String =
    """
    {"accountId":"$TEST_ACCOUNT_ID","oldState":"fixed","newState":"fixed",
     "created":{},"notCreated":{},"updated":{},"notUpdated":{},
     "destroyed":[],"notDestroyed":{"$id":{"type":"$type","description":"$description"}}}
    """

internal fun refusedUpdate(id: String, type: String, description: String): String =
    """
    {"accountId":"$TEST_ACCOUNT_ID","oldState":"fixed","newState":"fixed",
     "created":{},"notCreated":{},"updated":{},
     "notUpdated":{"$id":{"type":"$type","description":"$description"}},
     "destroyed":[],"notDestroyed":{}}
    """

internal fun destroyed(id: String): String =
    """
    {"accountId":"$TEST_ACCOUNT_ID","oldState":"fixed","newState":"fixed",
     "created":{},"notCreated":{},"updated":{},"notUpdated":{},
     "destroyed":["$id"],"notDestroyed":{}}
    """

internal fun updated(id: String): String =
    """
    {"accountId":"$TEST_ACCOUNT_ID","oldState":"fixed","newState":"fixed",
     "created":{},"notCreated":{},"updated":{"$id":null},"notUpdated":{},
     "destroyed":[],"notDestroyed":{}}
    """

/**
 * One writable calendar and one the user may only read.
 *
 * The read-only one is here because the seeded server has none — every calendar it serves reports
 * `mayAddItems: true` — so the `forbidden` a write on one raises is unexercised against real data
 * and would otherwise be unexercised here too.
 */
private val CALENDARS_RESULT =
    """
    {"accountId":"$TEST_ACCOUNT_ID","state":"fixed","list":[
      {"id":"$TEST_CALENDAR_ID","name":"Personal","color":"#2563eb","sortOrder":0,
       "isVisible":true,"isDefault":true,"timeZone":"Europe/Berlin","role":"default",
       "isSynced":false,
       "myRights":{"mayReadItems":true,"mayAddItems":true,"mayUpdateAll":true,
                   "mayRemoveItems":true,"mayDelete":false}},
      {"id":"10599","name":"Feiertage","color":"#16a34a","sortOrder":1,
       "isVisible":true,"isDefault":false,"timeZone":"Europe/Berlin","role":"account",
       "isSynced":true,
       "myRights":{"mayReadItems":true,"mayAddItems":false,"mayUpdateAll":false,
                   "mayRemoveItems":false,"mayDelete":false}}],
     "notFound":[]}
    """

/**
 * The session, with the calendars capability where [calendarAccount] says there is one.
 *
 * `maxCallsInRequest` is 32, as the real server publishes, because it is what decides how many
 * one-day probes travel together — a fake that omitted it would take RFC 8620's default of 16 and
 * quietly halve the batch the tests are measuring.
 */
private fun calendarSession(calendarAccount: String?): String {
    val capability =
        if (calendarAccount == null) ""
        else
            """
            "urn:plmail:params:jmap:calendars":{
              "maxEventsInGet":100,"maxEventsInSet":500,"mayCreateCalendar":false,
              "materialisedHorizon":{"past":"-1 year","future":"+2 years"}},
            """

    val primary =
        if (calendarAccount == null) ""
        else ""","urn:plmail:params:jmap:calendars":"$calendarAccount""""

    return """
        {
          "capabilities": {
            "urn:ietf:params:jmap:core": {"maxCallsInRequest": 32, "maxConcurrentRequests": 4},
            ${if (calendarAccount == null) "" else "\"urn:plmail:params:jmap:calendars\": {},"}
            "urn:ietf:params:jmap:mail": {}
          },
          "accounts": {
            "$TEST_ACCOUNT_ID": {
              "name": "someone@example.com",
              "accountCapabilities": {$capability "urn:ietf:params:jmap:mail": {}}
            }
          },
          "primaryAccounts": {"urn:ietf:params:jmap:mail": "$TEST_ACCOUNT_ID"$primary},
          "username": "someone@example.com",
          "apiUrl": "$TEST_SERVER/jmap/api",
          "downloadUrl": "$TEST_SERVER/jmap/download",
          "uploadUrl": "$TEST_SERVER/jmap/upload"
        }
        """
}

/**
 * A real [CalendarRepository] over a real database, answering out of [transport].
 *
 * Everything below the client is a fake for the same reason `syncStack` fakes it: none of these
 * tests are about credentials or about DataStore, and every one of them is about what ends up in
 * three Room tables.
 */
internal suspend fun calendarStack(
    database: PlMailDatabase,
    transport: JmapTransport,
    clock: Clock = testClock,
): CalendarRepository {
    val preferences = InMemoryPreferences()
    val credentials = CredentialStore(preferences, PlainCipher)

    credentials.save(
        ServerConnection(
            address = (ServerAddress.parse(TEST_SERVER) as ParsedAddress.Valid).address,
            credential = Credential.AppPassword("plmail_" + "a".repeat(64)),
            username = "someone@example.com",
        )
    )

    val transports =
        object : TransportFactory {
            override fun create(address: ServerAddress, pinned: KeyFingerprint?): JmapTransport =
                transport

            override fun createStreaming(
                address: ServerAddress,
                pinned: KeyFingerprint?,
            ): StreamingTransport = error("no stream is opened on this path")
        }

    return CalendarRepository(
        database = database,
        clients = AccountClients(credentials, transports),
        credentials = credentials,
        clock = clock,
    )
}
