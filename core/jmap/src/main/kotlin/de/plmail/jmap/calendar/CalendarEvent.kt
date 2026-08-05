package de.plmail.jmap.calendar

import de.plmail.jmap.protocol.CalendarEventId
import de.plmail.jmap.protocol.CalendarId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * One event *series*, as JSCalendar (RFC 8984) plus plMail's envelope.
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
 */
@Serializable
data class CalendarEvent(
    val id: CalendarEventId,
    /** Always `"Event"` when present. Absent whenever a `properties` filter did not ask for it. */
    @SerialName("@type") val type: String? = null,
    /** Stable across servers; the id is not. `<hex>@plmail` for events this product created. */
    val uid: String? = null,
    val calendarId: CalendarId? = null,
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
    /** At most one rule; the server refuses a second. */
    val recurrenceRules: List<RecurrenceRule> = emptyList(),
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
     * Derived server-side, and **not** the same question as `recurrenceRules.isNotEmpty()`.
     *
     * A rule plMail cannot convert is stored verbatim and expands to a single occurrence, so an
     * event can carry a rule and still not recur. Read this rather than inferring it; the two
     * disagree exactly on the imported events a user is most likely to notice.
     */
    val isRecurring: Boolean = false,
    /** plMail's extension. May be null; the vocabulary is open. */
    val kind: String? = null,
    /** plMail's extension: where the event came from. Seen: `manual`. */
    val source: String? = null,
)

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
