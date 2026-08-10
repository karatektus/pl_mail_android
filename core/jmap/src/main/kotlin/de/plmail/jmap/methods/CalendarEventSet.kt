package de.plmail.jmap.methods

import de.plmail.jmap.calendar.RecurrenceRule
import de.plmail.jmap.protocol.AccountId
import de.plmail.jmap.protocol.CalendarEventId
import de.plmail.jmap.protocol.CalendarId
import de.plmail.jmap.protocol.JmapMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `CalendarEvent/set` — create, change and destroy event series.
 *
 * **There is no `ifInState`, deliberately.** The server refuses one with `invalidArguments`, and it
 * is right to: the state is the constant `"fixed"`, so a guard built on it can never fail and would
 * read as conflict detection while providing none. Two clients editing the same event take turns,
 * and the client has to reconcile by re-fetching rather than by asking the server to arbitrate.
 *
 * Destroying an event is a real delete, unlike `Email/set` where "destroy" means Trash — there is
 * no calendar bin to recover from, so a destructive control here needs its own confirmation rather
 * than borrowing mail's undo.
 */
class CalendarEventSet(
    private val accountId: AccountId,
    private val create: Map<String, NewCalendarEvent> = emptyMap(),
    private val update: Map<CalendarEventId, CalendarEventPatch> = emptyMap(),
    private val destroy: List<CalendarEventId> = emptyList(),
) : JmapMethod<CalendarEventSetResult> {

    override val name = "CalendarEvent/set"

    override fun arguments(): JsonObject = buildJsonObject {
        put("accountId", accountId.value)

        if (create.isNotEmpty()) {
            put("create", buildJsonObject { create.forEach { (id, e) -> put(id, e.toJson()) } })
        }

        if (update.isNotEmpty()) {
            put(
                "update",
                buildJsonObject { update.forEach { (id, patch) -> put(id.value, patch.toJson()) } },
            )
        }

        if (destroy.isNotEmpty()) {
            put("destroy", buildJsonArray { destroy.forEach { add(it.value) } })
        }
    }

    override fun decode(json: Json, arguments: JsonObject): CalendarEventSetResult =
        json.decodeFromJsonElement(CalendarEventSetResult.serializer(), arguments)
}

/**
 * Which zone an event's [NewCalendarEvent.start] is read in.
 *
 * Three states on the wire and only two of them are values, which is why this is a type rather than
 * a `String?`. Omitting the property means "the calendar's zone"; sending JSON null means
 * *floating* — the same wall-clock time wherever the reader is — and the two are different events.
 * Modelling both as null would make a birthday move when the user travels, or fail to.
 *
 * Omission is expressed by passing no zone at all, so this deliberately has no third case: on a
 * patch, an absent property already means "leave it alone" and there is no spelling for going back
 * to inheriting.
 */
sealed interface EventTimeZone {
    /** The same wall-clock time in every zone. Sent as JSON null. */
    data object Floating : EventTimeZone

    /** An IANA zone name. */
    data class Zone(val name: String) : EventTimeZone

    fun toJson(): JsonElement =
        when (this) {
            Floating -> JsonNull
            is Zone -> JsonPrimitive(name)
        }
}

/**
 * An event being created.
 *
 * The properties here are **exactly** what the server accepts, and that is the whole design.
 * Anything else — `participants`, `alerts`, `privacy`, `links` — comes back as a per-object
 * `invalidProperties` naming the offenders, after the user has typed the event. Making the refused
 * set unrepresentable moves that from runtime to compile time, the same way `EmailFilter` does for
 * `unsupportedFilter`.
 */
data class NewCalendarEvent(
    val calendarId: CalendarId,
    val title: String,
    /**
     * A JSCalendar LocalDateTime: `2026-08-03T10:00:00`, **no offset and no trailing `Z`**.
     *
     * Formatting an instant here is the single easiest way to get this wrong, and the server does
     * say so — `"start" must be a JSCalendar LocalDateTime (Y-m-d\TH:i:s), with no offset and no
     * trailing Z.` — but only after the round trip.
     */
    val start: String,
    /** ISO 8601. `PT1H`, `P1D`. */
    val duration: String,
    val description: String? = null,
    /** Null means the calendar's own zone; see [EventTimeZone]. */
    val timeZone: EventTimeZone? = null,
    val showWithoutTime: Boolean = false,
    /**
     * A place, as a plain label.
     *
     * One, not a list, and a name rather than a structure: the server stores at most one Location
     * and keeps only `@type` and `name` from it. Offering coordinates would be offering something
     * that silently does not survive the save.
     */
    val location: String? = null,
    /** `confirmed`, `tentative` or `cancelled`. */
    val status: String = STATUS_CONFIRMED,
    /** At most one; the server refuses a second. */
    val recurrenceRule: RecurrenceRule? = null,
    val recurrenceOverrides: Map<String, RecurrenceOverride> = emptyMap(),
    /**
     * Settable **on create only** and immutable afterwards.
     *
     * Left null for anything this app originates — the server mints a stable `<hex>@plmail`. Supply
     * one only when re-creating an event that came from elsewhere and has to keep its identity
     * across servers.
     */
    val uid: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("@type", "Event")
        put("calendarId", calendarId.value)
        uid?.let { put("uid", it) }
        put("title", title)
        description?.let { put("description", it) }
        put("start", start)
        put("duration", duration)
        timeZone?.let { put("timeZone", it.toJson()) }
        if (showWithoutTime) put("showWithoutTime", true)
        // Omitted rather than sent as null when there is no place: on a create
        // the two mean the same thing, and an omitted key cannot be mistaken for
        // a client trying to clear something.
        location?.let { put("locations", locationJson(it)) }
        put("status", status)
        putRecurrence(recurrenceRule, recurrenceOverrides)
    }

    companion object {
        const val STATUS_CONFIRMED = "confirmed"
        const val STATUS_TENTATIVE = "tentative"
        const val STATUS_CANCELLED = "cancelled"
    }
}

/**
 * A change to an existing event.
 *
 * **Whole properties only.** A key containing a `/` is a JSON-pointer patch, which `Email/set`
 * accepts and this method refuses with `invalidPatch` — "Patch paths are not supported; send the
 * whole property." So there is no per-key merge here, and in particular [recurrenceOverrides]
 * replaces the entire map: a client that sends one override drops every other exception on the
 * series. Read them, change the one, send them all back.
 */
class CalendarEventPatch private constructor(private val fields: Map<String, JsonElement>) {

    fun toJson(): JsonObject = JsonObject(fields)

    val isEmpty: Boolean
        get() = fields.isEmpty()

    class Builder {
        private val fields = mutableMapOf<String, JsonElement>()

        fun title(value: String) = apply { fields["title"] = JsonPrimitive(value) }

        /** Null clears it. An empty string would be stored as an empty description instead. */
        fun description(value: String?) = apply {
            fields["description"] =
                value?.takeIf { it.isNotBlank() }?.let(::JsonPrimitive) ?: JsonNull
        }

        /** A LocalDateTime, never an instant — see [NewCalendarEvent.start]. */
        fun start(value: String) = apply { fields["start"] = JsonPrimitive(value) }

        fun duration(value: String) = apply { fields["duration"] = JsonPrimitive(value) }

        fun timeZone(zone: EventTimeZone) = apply { fields["timeZone"] = zone.toJson() }

        fun showWithoutTime(value: Boolean) = apply {
            fields["showWithoutTime"] = JsonPrimitive(value)
        }

        /** Null takes the place off. */
        fun location(name: String?) = apply { fields["locations"] = locationJson(name) }

        fun status(value: String) = apply { fields["status"] = JsonPrimitive(value) }

        /** Moving an event between calendars is a plain property change. */
        fun calendarId(id: CalendarId) = apply { fields["calendarId"] = JsonPrimitive(id.value) }

        /** Null stops the event recurring. */
        fun recurrenceRule(rule: RecurrenceRule?) = apply {
            fields["recurrenceRules"] =
                rule?.let { buildJsonArray { add(it.toJson()) } } ?: JsonNull
        }

        /** Replaces the whole map. Send every override the series should keep. */
        fun recurrenceOverrides(overrides: Map<String, RecurrenceOverride>) = apply {
            fields["recurrenceOverrides"] = buildJsonObject {
                overrides.forEach { (recurrenceId, override) ->
                    put(recurrenceId, override.toJson())
                }
            }
        }

        fun build(): CalendarEventPatch {
            // A key with a slash in it is a JSON pointer, which this method
            // refuses outright. Nothing above can produce one today; the check
            // is here so a builder method added later cannot quietly reintroduce
            // the mail-side habit.
            val pointer = fields.keys.firstOrNull { it.contains('/') }
            require(pointer == null) {
                "CalendarEvent/set refuses patch paths with invalidPatch — '$pointer' has to be " +
                    "sent as a whole property."
            }

            return CalendarEventPatch(fields.toMap())
        }
    }

    companion object {
        fun build(block: Builder.() -> Unit): CalendarEventPatch = Builder().apply(block).build()
    }
}

/**
 * One occurrence of a recurring series, changed or removed.
 *
 * Keyed in [NewCalendarEvent.recurrenceOverrides] by the occurrence's **original** start, so moving
 * an occurrence keeps the key it had before the move — the key identifies which occurrence, the
 * `start` inside says where it went.
 */
class RecurrenceOverride private constructor(private val fields: Map<String, JsonElement>) {

    fun toJson(): JsonObject = JsonObject(fields)

    class Builder {
        private val fields = mutableMapOf<String, JsonElement>()

        fun title(value: String) = apply { fields["title"] = JsonPrimitive(value) }

        fun description(value: String) = apply { fields["description"] = JsonPrimitive(value) }

        /** Where this occurrence moved to. A LocalDateTime, as everywhere else. */
        fun start(value: String) = apply { fields["start"] = JsonPrimitive(value) }

        fun duration(value: String) = apply { fields["duration"] = JsonPrimitive(value) }

        fun status(value: String) = apply { fields["status"] = JsonPrimitive(value) }

        /** Null takes the place off. */
        fun location(name: String?) = apply { fields["locations"] = locationJson(name) }

        fun build() = RecurrenceOverride(fields.toMap())
    }

    companion object {
        fun build(block: Builder.() -> Unit): RecurrenceOverride = Builder().apply(block).build()

        /**
         * Deletes a single occurrence.
         *
         * The only way to do it: there is no id for one occurrence, so "cancel this Tuesday" is an
         * override on the series rather than a destroy. Note it round-trips as `{"excluded": true}`
         * — the occurrence is gone from `CalendarEvent/query` but the exclusion is still in the
         * object.
         */
        val EXCLUDED = RecurrenceOverride(mapOf("excluded" to JsonPrimitive(true)))
    }
}

@Serializable
data class CalendarEventSetResult(
    val accountId: String = "",
    /** Both are `"fixed"` — the same constant, before and after a change that did happen. */
    val oldState: String? = null,
    val newState: String = "",
    /** Keyed by the creation id the client chose. Echoes only what the server decided. */
    val created: Map<String, CreatedCalendarEvent> = emptyMap(),
    val notCreated: Map<String, SetError> = emptyMap(),
    /** Values are always null; a successful update reports nothing but its own key. */
    val updated: Map<String, JsonElement?> = emptyMap(),
    val notUpdated: Map<String, SetError> = emptyMap(),
    val destroyed: List<CalendarEventId> = emptyList(),
    val notDestroyed: Map<String, SetError> = emptyMap(),
) {
    val hasFailures: Boolean
        get() = notCreated.isNotEmpty() || notUpdated.isNotEmpty() || notDestroyed.isNotEmpty()

    fun firstFailure(): SetError? =
        notCreated.values.firstOrNull()
            ?: notUpdated.values.firstOrNull()
            ?: notDestroyed.values.firstOrNull()
}

/**
 * What the server decided about a created event.
 *
 * [isRecurring] is here rather than inferred because it is derived server-side: a rule plMail
 * cannot convert is stored verbatim and expands to one occurrence, so a create carrying a
 * recurrence rule can come back `isRecurring: false`. That is the answer, not a bug to work around.
 */
@Serializable
data class CreatedCalendarEvent(
    val id: CalendarEventId,
    /**
     * JMAP's own uid for the event just created, where the server echoed one.
     *
     * Nullable rather than defaulted to the empty string, because the cache reconciles a created
     * row against later refreshes on this value and "" is not an identity — it is the absence of
     * one, and one that would compare equal to every other absence.
     */
    val uid: String? = null,
    val calendarId: CalendarId? = null,
    val isRecurring: Boolean = false,
    val sequence: Int = 0,
)

/** The write shape of a rule, which is not what `@Serializable` would emit for the read one. */
internal fun RecurrenceRule.toJson(): JsonObject = buildJsonObject {
    put("@type", "RecurrenceRule")
    frequency?.let { put("frequency", it) }
    interval?.let { put("interval", it) }
    count?.let { put("count", it) }
    until?.let { put("until", it) }

    if (byDay.isNotEmpty()) {
        put(
            "byDay",
            buildJsonArray {
                byDay.forEach { day ->
                    add(
                        buildJsonObject {
                            put("@type", "NDay")
                            // Lowercase two-letter codes -- `mo`, not `MO`. The
                            // iCalendar spelling is upper case and JSCalendar's
                            // is not, which is a difference an importer written
                            // against RFC 5545 gets wrong silently.
                            put("day", day.day)
                            day.nthOfPeriod?.let { put("nthOfPeriod", it) }
                        }
                    )
                }
            },
        )
    }

    if (byMonthDay.isNotEmpty()) {
        put("byMonthDay", buildJsonArray { byMonthDay.forEach { add(it) } })
    }
}

private fun JsonObjectBuilder.putRecurrence(
    rule: RecurrenceRule?,
    overrides: Map<String, RecurrenceOverride>,
) {
    rule?.let { put("recurrenceRules", buildJsonArray { add(it.toJson()) }) }

    if (overrides.isNotEmpty()) {
        put(
            "recurrenceOverrides",
            buildJsonObject { overrides.forEach { (id, o) -> put(id, o.toJson()) } },
        )
    }
}

/**
 * The map form a single place takes: an arbitrary key over an object carrying `@type` and `name`.
 *
 * The key is the server's own choice on the way back ("1"), and nothing reads it — a client that
 * tried to address a location by key would be addressing something it did not assign. A name of
 * null is JSON null, which is how a place is removed.
 */
private fun locationJson(name: String?): JsonElement =
    name?.let {
        buildJsonObject {
            put(
                "1",
                buildJsonObject {
                    put("@type", "Location")
                    put("name", it)
                },
            )
        }
    } ?: JsonNull
