package de.plmail.jmap.methods

import de.plmail.jmap.calendar.Calendar
import de.plmail.jmap.calendar.CalendarEvent
import de.plmail.jmap.calendar.CalendarEventFilter
import de.plmail.jmap.protocol.AccountId
import de.plmail.jmap.protocol.CalendarEventId
import de.plmail.jmap.protocol.CalendarId
import de.plmail.jmap.protocol.JmapMethod
import de.plmail.jmap.protocol.ResultReference
import de.plmail.jmap.protocol.StateToken
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
 * `Calendar/get` — the calendars themselves, not what is in them.
 *
 * There is still no `Calendar/set`: the two provisioned roles come from the server's own
 * provisioner and a mirrored one from the subscribe flow, neither of which a JMAP create could
 * stand in for.
 *
 * **There is now a `Calendar/changes`, and [state] is a real cursor.** This doc used to say the
 * state was the literal `"fixed"` and must not be stored, which was true of the server it was
 * written against and stopped being true on 2026-08-27. A fixed state is a standing claim that
 * nothing has changed, and a client is entitled to believe it — renaming a calendar left the old
 * name in the sidebar until something unrelated forced a reload. See [CalendarChanges].
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
    /** Opaque, and a real cursor: store it and hand it back to [CalendarChanges]. */
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
    /** Opaque, and a real cursor: store it and hand it back to [CalendarEventChanges]. */
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

/**
 * `Calendar/changes` — which calendars were added, renamed or removed.
 *
 * Calendar ids, not event ids. An event moving between calendars is not a change here: both
 * collections still exist and neither was renamed.
 *
 * The gap this closes is small in bytes and not small in correctness. `Calendar/get` returns every
 * calendar a user has, and there are a handful, so re-running it is cheap — that argument is why
 * this landed after [CalendarEventChanges] rather than with it. It is also beside the point: while
 * the state was fixed it was a standing claim that nothing had changed, and a client that believed
 * it kept drawing a calendar's old name until something unrelated forced a reload.
 *
 * **Every failure mode is one recovery.** The server raises `cannotCalculateChanges` for a missing
 * token, an unrecognised one, one ahead of the log, and one older than retained history. A client
 * cannot tell those apart and does not need to: all four mean the cursor is worthless and the
 * answer is a full [CalendarGet]. [de.plmail.jmap.protocol.JmapError.requiresResync] is the test,
 * and it is the same one the mail delta sync uses.
 */
class CalendarChanges(
    private val accountId: AccountId,
    private val sinceState: StateToken,
    private val maxChanges: Int = MAX_CHANGES,
) : JmapMethod<CalendarChangesResult> {

    override val name = "Calendar/changes"

    override fun arguments(): JsonObject = buildJsonObject {
        put("accountId", accountId.value)
        put("sinceState", sinceState.value)
        put("maxChanges", maxChanges)
    }

    override fun decode(json: Json, arguments: JsonObject): CalendarChangesResult =
        json.decodeFromJsonElement(CalendarChangesResult.serializer(), arguments)

    companion object {
        /** The server's own page size, matching `Email/changes`. */
        const val MAX_CHANGES = 256
    }
}

@Serializable
data class CalendarChangesResult(
    val accountId: String = "",
    val oldState: String = "",
    val newState: String = "",
    val hasMoreChanges: Boolean = false,
    val created: List<CalendarId> = emptyList(),
    val updated: List<CalendarId> = emptyList(),
    val destroyed: List<CalendarId> = emptyList(),
) {
    val isEmpty: Boolean
        get() = created.isEmpty() && updated.isEmpty() && destroyed.isEmpty()
}

/**
 * `CalendarEvent/changes` — which events moved, without asking what is in the window.
 *
 * This is the one that pays for itself. A calendar refresh is otherwise two windowed queries and
 * two paged gets *every time the screen is opened*, whether or not a single event moved — and the
 * common case, by a wide margin, is that none did. Asking here first turns that into one request
 * that answers "nothing" and stops.
 *
 * **It does not replace the window fetch, and cannot.** A delta reports the events that changed, in
 * event-id terms, for the whole account; the calendar screen draws *occurrences inside a date
 * range*. Those are different questions, and only the query answers the second — a month the device
 * has never held has no changes to report because nothing about it changed, and it is still empty.
 * So the delta's job is narrow and worth stating: it decides whether a window the cache already
 * holds needs re-fetching. First visits, and any window the cache has not seen, go straight to the
 * query as before.
 *
 * Asking from [StateToken.INITIAL] is not a way to enumerate a calendar, for the same reason
 * `Email/changes` is not: there have been no *changes* since the beginning of the log.
 */
class CalendarEventChanges(
    private val accountId: AccountId,
    private val sinceState: StateToken,
    private val maxChanges: Int = MAX_CHANGES,
) : JmapMethod<CalendarEventChangesResult> {

    override val name = "CalendarEvent/changes"

    override fun arguments(): JsonObject = buildJsonObject {
        put("accountId", accountId.value)
        put("sinceState", sinceState.value)
        put("maxChanges", maxChanges)
    }

    override fun decode(json: Json, arguments: JsonObject): CalendarEventChangesResult =
        json.decodeFromJsonElement(CalendarEventChangesResult.serializer(), arguments)

    companion object {
        const val MAX_CHANGES = 256
    }
}

@Serializable
data class CalendarEventChangesResult(
    val accountId: String = "",
    val oldState: String = "",
    val newState: String = "",
    val hasMoreChanges: Boolean = false,
    val created: List<CalendarEventId> = emptyList(),
    val updated: List<CalendarEventId> = emptyList(),
    val destroyed: List<CalendarEventId> = emptyList(),
) {
    /**
     * Whether anything at all moved.
     *
     * The only question the repository asks of this. Which events changed is deliberately not used
     * to fetch them individually: an occurrence inside a window is not addressable by series id,
     * and a recurrence whose rule changed alters occurrences whose ids never appear in a delta. So
     * a non-empty answer re-runs the window, and an empty one skips it.
     */
    val isEmpty: Boolean
        get() = created.isEmpty() && updated.isEmpty() && destroyed.isEmpty()
}
