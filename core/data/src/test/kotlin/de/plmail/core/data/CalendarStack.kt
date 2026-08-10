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
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The calendar the fake server serves, and the one every fixture below files events into. */
internal const val TEST_CALENDAR_ID = "10542"

/** The seeded calendar's zone, the fixtures' zone, and the test device's. */
internal val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")

internal val testCalendarKey: String = "$testAccountKey#$TEST_CALENDAR_ID"

/**
 * Midweek of the seeded week, so a window either side of it is inside the materialised horizon.
 *
 * Fixed rather than `LocalDate.now()`: the repository clamps the window it asks about against today
 * and reports what it had to leave out, and a suite that took the real date would start saying
 * something different on a day nobody chose.
 *
 * **In Europe/Berlin rather than UTC**, and that is load-bearing rather than local colour: the
 * repository converts every query window out of the clock's zone, so a suite running at UTC would
 * be a suite in which the conversion is the identity — which is exactly the machine on which the
 * defect these tests exist for cannot be reproduced. The seeded events are a German user's, and so
 * is the device holding them.
 */
internal val testClock: Clock = Clock.fixed(Instant.parse("2026-08-05T12:00:00Z"), BERLIN)

/** A phone in the seeded week's own zone, at [instant]. */
internal fun berlinClock(instant: String = "2026-08-05T12:00:00Z"): Clock =
    Clock.fixed(Instant.parse(instant), BERLIN)

/**
 * A calendar server that expands recurrences, because the real one does.
 *
 * The truth it holds is a set of materialised days per event, exactly as
 * `calendar_event_occurrence` is a table of them — never a rule. Both answers are derived from it:
 * a **collapsed** `CalendarEvent/query` returns one id per series overlapping the window, and an
 * **expanded** one returns one id per occurrence, ordered by the occurrence's start, with overrides
 * applied and excluded and cancelled occurrences left out. A test can therefore move one occurrence
 * and watch the placement follow, which is the only thing this repository is really about.
 *
 * **Occurrence ids here are opaque tokens — `o1`, `o2` — and that is deliberate.** The real server
 * builds them as `<seriesId>_<recurrenceId>`, which `:core:jmap`'s fixtures pin; this fake builds
 * them from nothing at all, so any code that reads a date or a series id back out of an id fails
 * here rather than on somebody's phone the day the format changes.
 *
 * **It compares in UTC, because the real server does.** `CalendarEventQueryRunner::run()` puts
 * `after` and `before` through `utcDate()` — parse, then `setTimezone('UTC')` — and matches them
 * against `calendar_event_occurrence.span`, which holds UTC instants, with Postgres' half-open
 * `tsrange` overlap. So each fixture day here is turned into the instant the server would have
 * stored for it: through the event's own zone where it has one, and as a bare wall clock where it
 * does not, which is what the server writes for a floating or all-day event.
 *
 * This mattered more than any assertion in the suite. The fake previously compared the naive
 * LocalDateTime strings, which mirrored the client's own mistake back at it: every test passed
 * while an event at 01:00 Berlin disappeared off a real phone. A fake that shares the client's
 * misunderstanding cannot fail for it.
 */
internal class FakeCalendarServer(
    /** Null publishes no calendars capability at all, which is the no-calendar-account case. */
    var calendarAccount: String? = TEST_ACCOUNT_ID,
    var events: MutableList<FakeEvent> = mutableListOf(),
    /** The `CalendarEvent/set` result arguments, when a test exercises a write. */
    var onSet: ((JsonObject) -> String)? = null,
    /** Refuses an expanded query with the horizon error, whatever the window. */
    var refuseExpansion: Boolean = false,
) {
    /**
     * Every window a `CalendarEvent/query` asked about, in order, **as it arrived on the wire**.
     *
     * In UTC, therefore, rather than in the local days the caller was thinking in — which is the
     * point: this is the record of what the server was actually asked, and a test that wants to pin
     * a boundary has to say which instant it means.
     */
    val windows = mutableListOf<Pair<LocalDateTime, LocalDateTime>>()

    /** How many of those asked for occurrences rather than series. */
    var expandedQueries = 0
        private set

    var collapsedQueries = 0
        private set

    /**
     * The id this server hands out for one occurrence, minted on first sight and stable after.
     *
     * Carries neither the series id nor the date on purpose. See the class comment.
     */
    private val instanceIds = mutableMapOf<Pair<String, LocalDateTime>, String>()

    internal fun instanceId(eventId: String, recurrenceId: LocalDateTime): String =
        instanceIds.getOrPut(eventId to recurrenceId) { "o${instanceIds.size + 1}" }

    internal fun recordWindow(from: LocalDateTime, to: LocalDateTime, expanded: Boolean) {
        windows += from to to

        if (expanded) expandedQueries++ else collapsedQueries++
    }
}

/** One event, and the days the server has materialised occurrences of it on. */
internal data class FakeEvent(
    val id: String,
    val title: String,
    val start: String,
    val days: Set<LocalDate>,
    val duration: String = "PT1H",
    val isRecurring: Boolean = false,
    val timeZone: String? = "Europe/Berlin",
    val showWithoutTime: Boolean = false,
    /**
     * Raw JSON for `recurrenceOverrides`, or null.
     *
     * Held as the server publishes it — a patch keyed by the occurrence's original start — and
     * *applied by this fake*, because applying it is the server's job now. The client sees only the
     * resolved occurrence; the map still reaches it on the series object, where it is what a future
     * per-occurrence edit patches.
     */
    val overrides: String? = null,
    val calendarId: String = TEST_CALENDAR_ID,
    /**
     * JMAP's `uid`, which is **not** the id and does not move when the id does.
     *
     * Defaulted to `<id>@plmail`, which is the shape plMail mints, so every fixture that does not
     * care reads as it always did. A test sets it explicitly for the one case where the two
     * identities come apart: a provider mirror re-importing the event it has just pushed, which
     * keeps the uid and hands out a row id of its own.
     */
    val uid: String? = null,
)

/** A one-off on one day, placed from its own start. */
internal fun oneOff(
    id: String,
    title: String,
    start: String,
    duration: String = "PT1H",
    showWithoutTime: Boolean = false,
    timeZone: String? = "Europe/Berlin",
    uid: String? = null,
    calendarId: String = TEST_CALENDAR_ID,
): FakeEvent =
    FakeEvent(
        id = id,
        title = title,
        start = start,
        days = setOf(LocalDateTime.parse(start).toLocalDate()),
        duration = duration,
        showWithoutTime = showWithoutTime,
        timeZone = timeZone,
        uid = uid,
        calendarId = calendarId,
    )

/** A series the server has materialised on [days], with no rule the client is ever shown. */
internal fun recurring(
    id: String,
    title: String,
    start: String,
    days: Set<LocalDate>,
    duration: String = "PT15M",
    overrides: String? = null,
    timeZone: String? = "Europe/Berlin",
    showWithoutTime: Boolean = false,
): FakeEvent =
    FakeEvent(
        id = id,
        title = title,
        start = start,
        days = days,
        duration = duration,
        isRecurring = true,
        overrides = overrides,
        timeZone = timeZone,
        showWithoutTime = showWithoutTime,
    )

/**
 * The transport in front of [server].
 *
 * Answers each call in a batch by name and resolves `#ids` against the call it names, which is what
 * makes a request carrying *two* queries and two gets testable: the collapsed pair and the expanded
 * pair travel together, and a get that took "whatever the last query said" would answer one of them
 * with the other's ids.
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

    // What each query answered, by its call id, so a back-referenced get can be
    // resolved the way the server resolves it.
    val queried = mutableMapOf<String, List<String>>()

    calls.forEach { call ->
        val entry = call.jsonArray
        val name = entry[0].jsonPrimitive.content
        val arguments = entry[1].jsonObject
        val callId = entry[2].jsonPrimitive.content

        when (name) {
            "Calendar/get" -> responses += """["Calendar/get", $CALENDARS_RESULT, "$callId"]"""

            "CalendarEvent/query" -> {
                val expand = arguments["expandRecurrences"]?.jsonPrimitive?.booleanOrNull ?: false
                val filter = arguments["filter"]!!.jsonObject
                val from = LocalDateTime.parse(filter["after"]!!.jsonPrimitive.content)
                val to = LocalDateTime.parse(filter["before"]!!.jsonPrimitive.content)

                server.recordWindow(from, to, expanded = expand)

                if (expand && server.refuseExpansion) {
                    responses += """["error", {"type":"cannotCalculateOccurrences"}, "$callId"]"""

                    return@forEach
                }

                val matched =
                    if (expand) expandedIds(server, from, to) else seriesIds(server, from, to)

                val position = arguments["position"]?.jsonPrimitive?.content?.toInt() ?: 0
                val limit = arguments["limit"]?.jsonPrimitive?.content?.toInt() ?: matched.size
                val page = matched.drop(position).take(limit)

                queried[callId] = page

                val ids = page.joinToString(",") { "\"$it\"" }

                responses +=
                    """
                    ["CalendarEvent/query",
                     {"accountId":"$TEST_ACCOUNT_ID","queryState":"fixed",
                      "canCalculateChanges":false,"position":$position,"ids":[$ids],
                      "total":${matched.size},"limit":$limit},"$callId"]
                    """
            }

            "CalendarEvent/get" -> {
                val reference =
                    arguments["#ids"]?.jsonObject?.get("resultOf")?.jsonPrimitive?.content

                // A back-reference to a call that failed is an
                // `invalidResultReference`, not an empty get -- which matters
                // here because the refused-expansion case is exactly that, and a
                // fake answering an empty list would let a client through that
                // the server would have stopped.
                if (reference != null && reference !in queried) {
                    responses += """["error", {"type":"invalidResultReference"}, "$callId"]"""

                    return@forEach
                }

                val wanted =
                    reference?.let { queried.getValue(it) }
                        ?: arguments["ids"]!!.jsonArray.map { it.jsonPrimitive.content }

                val list = wanted.mapNotNull { server.objectFor(it) }.joinToString(",")

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

/** One series id per event overlapping the window, ordered by its first occurrence in it. */
private fun seriesIds(
    server: FakeCalendarServer,
    from: LocalDateTime,
    to: LocalDateTime,
): List<String> =
    server.events
        .mapNotNull { event ->
            event.visibleIn(from, to).minByOrNull { it.startUtc(event) }?.let { event to it }
        }
        .sortedBy { (event, first) -> first.startUtc(event) }
        .map { (event, _) -> event.id }

/**
 * One id per occurrence, ordered by the occurrence's own start.
 *
 * A moved override therefore sorts at its moved time, and a one-off keeps its plain series id — its
 * single occurrence *is* the event, which is why an account with nothing recurring in the window
 * answers an expanded query exactly as it answers a collapsed one.
 */
private fun expandedIds(
    server: FakeCalendarServer,
    from: LocalDateTime,
    to: LocalDateTime,
): List<String> =
    server.events
        .flatMap { event -> event.visibleIn(from, to).map { event to it } }
        .sortedBy { (event, instance) -> instance.startUtc(event) }
        .map { (event, instance) ->
            if (event.isRecurring) server.instanceId(event.id, instance.recurrenceId) else event.id
        }

/** The object behind one id, series or occurrence, or null for an id this server never issued. */
private fun FakeCalendarServer.objectFor(id: String): String? {
    events
        .firstOrNull { it.id == id }
        ?.let { series ->
            return series.toJson()
        }

    events.forEach { event ->
        event.instances().forEach { instance ->
            if (instanceId(event.id, instance.recurrenceId) == id) {
                return event.toJson(instance, id)
            }
        }
    }

    return null
}

/**
 * One materialised occurrence, after its override.
 *
 * [recurrenceId] is the occurrence's **original** start and is what an override is keyed by;
 * [start] is where it actually is.
 */
private data class FakeInstance(
    val recurrenceId: LocalDateTime,
    val start: LocalDateTime,
    val duration: String,
    val title: String,
    /** `status: cancelled` — the row survives and resolves, but leaves the query. */
    val cancelled: Boolean,
)

/** Every occurrence this event has, excluded ones already gone. */
private fun FakeEvent.instances(): List<FakeInstance> {
    val time = LocalDateTime.parse(start).toLocalTime()
    val patches = overrides?.let { Json.parseToJsonElement(it).jsonObject }

    return days.sorted().mapNotNull { day ->
        val recurrenceId = LocalDateTime.of(day, time)
        val patch = patches?.get(recurrenceId.toWire())?.jsonObject

        if (patch?.get("excluded")?.jsonPrimitive?.booleanOrNull == true) return@mapNotNull null

        FakeInstance(
            recurrenceId = recurrenceId,
            start =
                patch?.get("start")?.let { LocalDateTime.parse(it.jsonPrimitive.content) }
                    ?: recurrenceId,
            duration = patch?.get("duration")?.jsonPrimitive?.content ?: duration,
            title = patch?.get("title")?.jsonPrimitive?.content ?: title,
            cancelled = patch?.get("status")?.jsonPrimitive?.content == "cancelled",
        )
    }
}

/** The occurrences a query answers with: overlapping the window, and not called off. */
private fun FakeEvent.visibleIn(from: LocalDateTime, to: LocalDateTime): List<FakeInstance> {
    val length = Duration.parse(duration)

    return instances()
        .filterNot { it.cancelled }
        .filter { instance ->
            val starts = instance.startUtc(this)

            starts < to && starts.plus(length) > from
        }
}

/**
 * This occurrence's start as the instant the server stored.
 *
 * An event with a zone is an instant, so its wall clock goes through that zone; one without is a
 * wall clock the server keeps verbatim in a UTC column, which is what it writes for a floating or
 * all-day event.
 */
private fun FakeInstance.startUtc(event: FakeEvent): LocalDateTime =
    if (event.timeZone == null) start
    else
        start
            .atZone(ZoneId.of(event.timeZone))
            .withZoneSameInstant(ZoneOffset.UTC)
            .toLocalDateTime()

/** The series object: its own start, its own title, and the override map published whole. */
private fun FakeEvent.toJson(): String = buildString {
    append("""{"id":"$id","uid":"${uid ?: "$id@plmail"}","calendarId":"$calendarId",""")
    append(""""title":"$title","start":"$start","duration":"$duration",""")
    timeZone?.let { append(""""timeZone":"$it",""") }
    if (showWithoutTime) append(""""showWithoutTime":true,""")
    overrides?.let { append(""""recurrenceOverrides":$it,""") }
    append(""""status":"confirmed","sequence":0,"isRecurring":$isRecurring}""")
}

/**
 * One occurrence as `CalendarEvent/get` resolves it.
 *
 * The series with the override merged in, its own `start` and `duration`, `seriesId` and
 * `recurrenceId` — and `recurrenceRules`/`recurrenceOverrides` explicitly **null**, as the draft
 * requires, which is also a decoding case worth exercising: an absent key and a null one are not
 * the same thing to a deserialiser.
 */
private fun FakeEvent.toJson(instance: FakeInstance, occurrenceId: String): String = buildString {
    append("""{"id":"$occurrenceId","seriesId":"$id","uid":"${uid ?: "$id@plmail"}",""")
    append(""""calendarId":"$calendarId","title":"${instance.title}",""")
    append(""""start":"${instance.start.toWire()}","duration":"${instance.duration}",""")
    append(""""recurrenceId":"${instance.recurrenceId.toWire()}",""")
    timeZone?.let { append(""""timeZone":"$it",""") }
    if (showWithoutTime) append(""""showWithoutTime":true,""")
    append(""""recurrenceRules":null,"recurrenceOverrides":null,""")
    append(
        """"status":"${if (instance.cancelled) "cancelled" else "confirmed"}",""" +
            """"sequence":0,"isRecurring":true}"""
    )
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
 * `maxCallsInRequest` is 32, as the real server publishes. A refresh needs five of them —
 * `Calendar/get`, two queries and two back-referenced gets — so an instance publishing RFC 8620's
 * default of 16 has room to spare, and a fake that omitted the value would silently be testing that
 * case instead of this one.
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
    credentials: CredentialStore = calendarCredentials(),
    /**
     * Whether the credential is already stored.
     *
     * False is a freshly installed, unpaired app — the state the drawer's calendar row was decided
     * in on a real device, and the only one in which "is there a calendar" can be watched change.
     */
    paired: Boolean = true,
): CalendarRepository {
    if (paired) credentials.pairWithTestServer()

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

/** An empty credential store, so a test can watch one gain a connection. */
internal fun calendarCredentials(): CredentialStore =
    CredentialStore(InMemoryPreferences(), PlainCipher)

/** What pairing writes: the address, the minted app password and the username it belongs to. */
internal suspend fun CredentialStore.pairWithTestServer() {
    save(
        ServerConnection(
            address = (ServerAddress.parse(TEST_SERVER) as ParsedAddress.Valid).address,
            credential = Credential.AppPassword("plmail_" + "a".repeat(64)),
            username = "someone@example.com",
        )
    )
}
