package de.plmail.jmap.methods

import de.plmail.jmap.calendar.Calendar
import de.plmail.jmap.calendar.CalendarEvent
import de.plmail.jmap.calendar.CalendarEventFilter
import de.plmail.jmap.protocol.AccountId
import de.plmail.jmap.protocol.CalendarEventId
import de.plmail.jmap.protocol.CalendarId
import de.plmail.jmap.protocol.JmapMethod
import de.plmail.jmap.protocol.ResultReference
import de.plmail.jmap.protocol.backReference
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `Calendar/get` — and it is the entire Calendar surface.
 *
 * There is no query, no changes and no set. The state is the literal string `"fixed"`, so there is
 * nothing to compare against and no delta to ask for: refreshing the calendar list means fetching
 * it again.
 */
class CalendarGet(private val accountId: AccountId, private val ids: List<CalendarId>? = null) :
    JmapMethod<CalendarGetResult> {

    override val name = "Calendar/get"

    override fun arguments(): JsonObject = buildJsonObject {
        put("accountId", accountId.value)

        // Explicit null rather than omitted, as with Mailbox/get: JMAP
        // distinguishes "all of them" from "none of them", and only the array
        // form says the latter.
        if (ids == null) {
            put("ids", JsonNull)
        } else {
            put("ids", buildJsonArray { ids.forEach { add(it.value) } })
        }
    }

    override fun decode(json: Json, arguments: JsonObject): CalendarGetResult =
        json.decodeFromJsonElement(CalendarGetResult.serializer(), arguments)
}

@Serializable
data class CalendarGetResult(
    val accountId: String = "",
    /** Always `"fixed"`. Opaque, and it never changes — do not store it as a sync cursor. */
    val state: String = "",
    val list: List<Calendar> = emptyList(),
    val notFound: List<CalendarId> = emptyList(),
) {
    /**
     * The default calendar, which is where an event goes when the user has not chosen.
     *
     * By the flag rather than by `role == "default"`: the role vocabulary is open and a second role
     * meaning the same thing would silently take over.
     */
    fun default(): Calendar? = list.firstOrNull { it.isDefault }

    /** What a picker may offer. Writability is [Calendar.myRights] and nothing else. */
    fun writable(): List<Calendar> = list.filter { it.myRights.mayAddItems }
}

/**
 * `CalendarEvent/query` — which event *series* fall in a window, ordered by first occurrence.
 *
 * The window is mandatory, which is why it is a constructor parameter of [CalendarEventFilter]
 * rather than an optional condition: omitting either end is a method-level `invalidArguments` with
 * no description saying which. Sorting is refused outright (`unsupportedSort`) and so is `anchor`
 * paging (`unsupportedFilter`), so the only paging is [position] with [limit].
 *
 * `canCalculateChanges` is false and `queryState` is `"fixed"`, so there is no delta path here
 * either — a calendar that has been left open re-runs the query.
 */
class CalendarEventQuery(
    private val accountId: AccountId,
    private val filter: CalendarEventFilter,
    private val position: Int = 0,
    private val limit: Int? = null,
) : JmapMethod<CalendarEventQueryResult> {

    init {
        require(position >= 0) {
            "CalendarEvent/query rejects a negative position; page forward with position + limit."
        }
    }

    override val name = "CalendarEvent/query"

    override fun arguments(): JsonObject = buildJsonObject {
        put("accountId", accountId.value)
        put("filter", filter.toJson())
        put("position", position)
        limit?.let { put("limit", it) }
    }

    override fun decode(json: Json, arguments: JsonObject): CalendarEventQueryResult =
        json.decodeFromJsonElement(CalendarEventQueryResult.serializer(), arguments)
}

@Serializable
data class CalendarEventQueryResult(
    val accountId: String = "",
    /** Always `"fixed"`. */
    val queryState: String = "",
    /** Always false; there is no `CalendarEvent/queryChanges`. */
    val canCalculateChanges: Boolean = false,
    val position: Int = 0,
    /**
     * Series ids, ordered by each series' **first occurrence inside the window** — not by id and
     * not by the series' own start, which for a recurring event is often long before the window.
     */
    val ids: List<CalendarEventId> = emptyList(),
    /** Always present, unlike `Mailbox/query`. */
    val total: Int? = null,
    /** Echoes the requested limit, not the server's 500 cap. */
    val limit: Int? = null,
)

/**
 * `CalendarEvent/get`.
 *
 * Pair it with [CalendarEventQuery] in one request through [byReference]. Note the id ceiling is
 * the account's `maxEventsInGet` — **100**, not core's `maxObjectsInGet` of 500 — because expanding
 * a recurring series costs far more than reading a row.
 */
class CalendarEventGet(
    private val accountId: AccountId,
    private val ids: List<CalendarEventId>? = null,
    private val idsReference: ResultReference? = null,
    private val properties: List<String>? = null,
) : JmapMethod<CalendarEventGetResult> {

    init {
        require(ids == null || ids.size <= MAX_EVENTS_IN_GET) {
            "CalendarEvent/get takes at most $MAX_EVENTS_IN_GET ids per call — the account's " +
                "maxEventsInGet, which is a fifth of core's maxObjectsInGet. Chunk the ids."
        }
    }

    override val name = "CalendarEvent/get"

    override fun arguments(): JsonObject = buildJsonObject {
        put("accountId", accountId.value)

        when {
            idsReference != null -> backReference("ids", idsReference)
            ids != null -> put("ids", buildJsonArray { ids.forEach { add(it.value) } })
        }

        properties?.let { put("properties", buildJsonArray { it.forEach { p -> add(p) } }) }
    }

    override fun decode(json: Json, arguments: JsonObject): CalendarEventGetResult =
        json.decodeFromJsonElement(CalendarEventGetResult.serializer(), arguments)

    companion object {
        /**
         * The account capability's value, as a floor to chunk by when no session is to hand.
         *
         * Read `Session.calendars(id).maxEventsInGet` where one is available — an instance
         * configured for more should not be second-guessed by its own client.
         */
        const val MAX_EVENTS_IN_GET = 100

        /** Enough to draw a month grid without pulling descriptions and rules for every cell. */
        val AGENDA_PROPERTIES =
            listOf(
                "id",
                "uid",
                "calendarId",
                "title",
                "start",
                "duration",
                "timeZone",
                "showWithoutTime",
                "status",
                "isRecurring",
            )

        fun byReference(
            accountId: AccountId,
            queryReference: ResultReference,
            properties: List<String>? = null,
        ) =
            CalendarEventGet(
                accountId = accountId,
                idsReference = queryReference,
                properties = properties,
            )
    }
}

@Serializable
data class CalendarEventGetResult(
    val accountId: String = "",
    /** Always `"fixed"`. */
    val state: String = "",
    val list: List<CalendarEvent> = emptyList(),
    val notFound: List<CalendarEventId> = emptyList(),
) {
    /**
     * The events in the order asked for.
     *
     * `CalendarEvent/get` **does** preserve the requested order today, which is the opposite of
     * `Email/get` and `Thread/get` — see `EmailGetResult.ordered`. That is not something to build
     * on: the two mail gets reorder for reasons internal to the repository, the same repository
     * pattern is underneath this one, and a query paired with a get renders in whatever `list`
     * says. Going through here costs one map and removes the question.
     */
    fun ordered(ids: List<CalendarEventId>): List<CalendarEvent> {
        val byId = list.associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }
}
