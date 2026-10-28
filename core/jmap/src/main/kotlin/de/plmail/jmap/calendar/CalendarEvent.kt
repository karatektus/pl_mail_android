package de.plmail.jmap.calendar

import de.plmail.jmap.protocol.CalendarEventId
import de.plmail.jmap.protocol.CalendarId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * One event, as JSCalendar (RFC 8984) plus plMail's envelope — a series, or **one occurrence of
 * one**.
 *
 * Which of the two it is, is [baseEventId]: present means this object came back for an occurrence
 * id, and then [start], [duration] and [status] are that occurrence's own, its override already
 * merged in, with [recurrenceRule] and [recurrenceOverrides] nulled by the server. Absent means the
 * series, and a one-off event is always the series — its single occurrence *is* the event, so an
 * expanded query names it by its plain id.
 *
 * Every property but [id] is optional, and for the same reason [de.plmail.jmap.mail.Email]'s are:
 * `CalendarEvent/get` returns exactly the properties asked for, so a `properties` filter comes back
 * without `uid` or even `@type`.
 *
 * This is a *subset* of what the server stores. Events carry arbitrary JSCalendar — an import can
 * bring participants, alerts, links and anything else the format defines — and unknown keys are
 * tolerated rather than rejected by `MethodResults.JMAP_JSON`. Nothing here may be treated as the
 * whole object; in particular a round trip through this type is lossy and must never be written
 * back.
 *
 * Spelled in the IETF draft's names (`calendarIds`, `recurrenceRule`, `baseEventId`), which is what
 * the server answers under `urn:ietf:params:jmap:calendars`. A server old enough to speak only
 * plMail's vendor URN is read into the same shape by [CalendarDialect.PLMAIL].
 */
@Serializable
data class CalendarEvent(
    val id: CalendarEventId,
    /** Always `"Event"` when present. Absent whenever a `properties` filter did not ask for it. */
    @SerialName("@type") val type: String? = null,
    /** Stable across servers; the id is not. `<hex>@plmail` for events this product created. */
    val uid: String? = null,
    /**
     * The calendars this event is in, as the draft's id-to-true map.
     *
     * Exactly one on plMail, which files an event in one calendar and says so
     * (`maxCalendarsPerEvent: 1`); read it through [calendarId].
     */
    val calendarIds: Map<String, Boolean> = emptyMap(),
    val title: String? = null,
    val description: String? = null,
    /**
     * A JSCalendar **LocalDateTime**: `2026-08-03T10:00:00`, with no offset and no trailing `Z`.
     *
     * Not an instant. It means "10:00 in [timeZone]", or 10:00 wherever the device is when the zone
     * is absent — which is why parsing this with anything that assumes UTC silently shifts every
     * event by the reader's own offset. The server refuses to store any other spelling.
     */
    val start: String? = null,
    /** ISO 8601, e.g. `PT15M`, `P1D`. */
    val duration: String? = null,
    /**
     * An IANA zone name, or **absent entirely**.
     *
     * Absent is the ordinary case rather than an edge: an all-day event carries no zone at all, so
     * this must stay nullable rather than defaulting to the calendar's. A floating event means the
     * same wall-clock time everywhere, and substituting a zone for it turns a birthday into
     * something that moves when the user travels.
     */
    val timeZone: String? = null,
    /** True on an all-day event. Present only when true. */
    val showWithoutTime: Boolean = false,
    val status: String? = null,
    /**
     * Published but **not writable**. `CalendarEvent/set` answers `invalidProperties` for it, so a
     * client offering a privacy control would have to hide it again at the moment of saving.
     */
    val privacy: String? = null,
    /**
     * At most one, keyed by an arbitrary string, and each carrying only `@type` and `name` — plMail
     * stores a place as a label rather than as coordinates.
     */
    val locations: Map<String, EventLocation> = emptyMap(),
    /** The draft's one rule — plMail never stored a second. */
    val recurrenceRule: RecurrenceRule? = null,
    /**
     * Per-occurrence patches, keyed by the occurrence's original start (a LocalDateTime).
     *
     * Left as raw JSON rather than typed, because an override is a *patch* over the series and may
     * name any writable property, including JSON-pointer paths into one. Decoding it into a fixed
     * shape would silently drop whatever this client's version has not heard of, and the loss would
     * only show when the value was written back.
     */
    val recurrenceOverrides: Map<String, JsonObject> = emptyMap(),
    val sequence: Int = 0,
    /** UTC instants, unlike [start] — these are `Z`-suffixed. */
    val created: String? = null,
    val updated: String? = null,
    /**
     * The server's own answer to whether this event recurs — `plmail.org:isRecurring`, a vendor
     * property (RFC 8984 §3.3) beside the draft's, or `isRecurring` under plMail's vendor URN. Read
     * [isRecurring].
     */
    @SerialName(IS_RECURRING) val publishedRecurring: Boolean? = null,
    /**
     * The only way back from an occurrence to something writable — the draft's name for what
     * plMail's vendor shape called `seriesId`.
     *
     * Present exactly when [id] names one occurrence rather than a series. `CalendarEvent/set`
     * **refuses an occurrence id by name** — `invalidArguments`, pointing at this property — so
     * every write goes to this id with a `recurrenceOverrides` patch keyed by [recurrenceId].
     *
     * This is also the only sanctioned way to learn which series an occurrence belongs to. The id
     * happens to be built from the series id and the occurrence's original start, and reading it
     * back out is client-side expansion through the back door: the draft calls the id opaque, the
     * separator is plMail's own choice, and a client that parsed it would be deciding for itself
     * where an occurrence goes.
     */
    val baseEventId: CalendarEventId? = null,
    /**
     * The occurrence's **original** start, as a LocalDateTime — never where an override moved it.
     *
     * The key a `recurrenceOverrides` patch has to be filed under, which is what makes it worth
     * keeping beside [start]. Read in [recurrenceIdTimeZone] where the server sends one — plMail's
     * own server does not, under the draft's URN.
     */
    val recurrenceId: String? = null,
    val recurrenceIdTimeZone: String? = null,
) {
    /** The one calendar this event is in. See [calendarIds]. */
    val calendarId: CalendarId?
        get() = calendarIds.entries.firstOrNull { it.value }?.key?.let(::CalendarId)

    /**
     * Whether this event repeats.
     *
     * The server says so outright, derived server-side, and that answer wins: a rule plMail cannot
     * convert is stored verbatim and expands to a single occurrence, so an event can carry a rule
     * and still not recur. The draft has no property for it, so plMail publishes one of its own
     * under that URN — [publishedRecurring]. Only a server that publishes neither leaves the rule
     * as the answer, and that is wrong only on those imported events, where it errs towards
     * treating a one-off as a series.
     */
    val isRecurring: Boolean
        get() = publishedRecurring ?: (recurrenceRule != null)

    companion object {
        /** plMail's vendor property for [isRecurring], under the draft's URN. */
        const val IS_RECURRING = "plmail.org:isRecurring"
    }

    /** Whether this object is one occurrence of a series rather than the series itself. */
    val isOccurrence: Boolean
        get() = baseEventId != null

    /**
     * The id an edit is addressed to: the series', which for a one-off is its own.
     *
     * Never derived from [id]. See [baseEventId] for why that matters.
     */
    val writableId: CalendarEventId
        get() = baseEventId ?: id
}

@Serializable
data class EventLocation(
    @SerialName("@type") val type: String? = null,
    val name: String? = null,
)

@Serializable
data class RecurrenceRule(
    @SerialName("@type") val type: String? = null,
    /** `daily`, `weekly`, `monthly`, `yearly`. */
    val frequency: String? = null,
    val interval: Int? = null,
    val count: Int? = null,
    /** A LocalDateTime, like [CalendarEvent.start], not an instant. */
    val until: String? = null,
    val byDay: List<NDay> = emptyList(),
    val byMonthDay: List<Int> = emptyList(),
)

/** Two-letter lowercase day codes — `mo`, `tu`, … — not the upper-case iCalendar spelling. */
@Serializable
data class NDay(
    @SerialName("@type") val type: String? = null,
    val day: String = "",
    val nthOfPeriod: Int? = null,
)
