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
 * `CalendarEvent/query` — what falls in a window, ordered by start.
 *
 * **[expandRecurrences] switches the unit from the series to the occurrence.** False (or absent,
 * which is what this sends) answers one id per *series* overlapping the window, ordered by each
 * series' first occurrence inside it. True answers one id per *occurrence*, ordered by the
 * occurrence's start — a moved override sorting at its moved time, an excluded one absent — with
 * `position`, `limit` and `total` counting occurrences rather than series. A one-off keeps its
 * plain series id either way, so a window with nothing recurring in it answers both identically.
 *
 * An occurrence id is **opaque**. It looks like `42_20260304T090000Z` and it must be handed
 * straight back to [CalendarEventGet]; the series it belongs to is that object's `seriesId` and
 * never something read out of the id.
 *
 * Expanding is refused with `cannotCalculateOccurrences` when the window reaches past the account's
 * `materialisedHorizon` — collapsed, an overrunning window is merely thin, but the expanded answer
 * *is* the list of occurrences, so a series that stops at the horizon would come back as a series
 * that ends. Clamp the window rather than discovering this per request.
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
    private val expandRecurrences: Boolean = false,
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

        // Omitted rather than sent as false. The two are documented to behave
        // identically, so sending the argument only when it is wanted keeps a
        // collapsed query byte-for-byte what an instance without this feature
        // has always been asked.
        if (expandRecurrences) put("expandRecurrences", true)
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
     * Ids, ordered by start — not by id, and not by the series' own start, which for a recurring
     * event is often long before the window.
     *
     * What one names depends on the query: a **series**, placed at its first occurrence inside the
     * window, or — with `expandRecurrences` — one **occurrence**, placed at its own start. An
     * occurrence id is opaque and belongs in a `CalendarEvent/get`, nowhere else.
     */
    val ids: List<CalendarEventId> = emptyList(),
    /** Always present, unlike `Mailbox/query`. Counts whatever [ids] names. */
    val total: Int? = null,
    /** Echoes the requested limit, not the server's 500 cap. */
    val limit: Int? = null,
)

/**
 * `CalendarEvent/get`, which resolves a series id and an occurrence id alike.
 *
 * Pair it with [CalendarEventQuery] in one request through [byReference] — the pairing works
 * unchanged for an expanded query, which is what makes a month one round trip. An occurrence id
 * answers the series with its override merged in, plus `seriesId`, `recurrenceId` and the
 * occurrence's own `start`/`duration`; `recurrenceRules` and `recurrenceOverrides` come back null.
 *
 * Note the id ceiling is the account's `maxEventsInGet` — **100**, not core's `maxObjectsInGet` of
 * 500 — because expanding a recurring series costs far more than reading a row. Expanded, that
 * ceiling counts occurrences, which a busy month reaches far sooner than it reaches 100 series.
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

        /**
         * Enough to *place* one occurrence, and nothing a series row already holds.
         *
         * The list an expanded query's back-referenced get asks for. A month of a busy calendar is
         * a lot of occurrences, and each one is the whole series repeated — description, location,
         * participants — so asking for the fields a day view draws from the series would move that
         * text once per occurrence over a domestic uplink.
         *
         * `seriesId` earns its place twice over: it is the row an occurrence hangs off and the only
         * id `CalendarEvent/set` will accept back.
         */
        val OCCURRENCE_PROPERTIES =
            listOf(
                "id",
                "seriesId",
                "recurrenceId",
                "title",
                "start",
                "duration",
                "timeZone",
                "showWithoutTime",
                "status",
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
