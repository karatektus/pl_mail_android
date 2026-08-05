package de.plmail.jmap.calendar

import de.plmail.jmap.protocol.CalendarId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The whole filter vocabulary `CalendarEvent/query` has: three conditions and no operators.
 *
 * A data class rather than the sealed hierarchy [de.plmail.jmap.mail.EmailFilter] uses, because
 * there is nothing to compose — `FilterOperator` is refused outright, so AND/OR/NOT cannot be
 * expressed and neither can a second calendar.
 *
 * The window is mandatory and its absence is reported as a bare `invalidArguments` that does not
 * say which end is missing, which is why [after] and [before] are constructor arguments: there
 * would be nothing in the response to debug from.
 *
 * One thing the filter cannot tell you: occurrences are materialised server-side only within the
 * account's `materialisedHorizon`. A window outside it is answered from a partial index and comes
 * back looking like a quiet month rather than like an incomplete answer.
 */
data class CalendarEventFilter(
    /**
     * Inclusive. A JSCalendar LocalDateTime — `2026-08-01T00:00:00`, no offset and no trailing `Z`
     * — matched against each occurrence's local start, not against an instant.
     */
    val after: String,
    /** Exclusive, same spelling. */
    val before: String,
    /** Optional. There is no "in any of these calendars" — filter one, or filter none. */
    val inCalendar: CalendarId? = null,
) {
    init {
        require(after.isNotBlank() && before.isNotBlank()) {
            "CalendarEvent/query needs both ends of the window; the server answers a missing one " +
                "with a bare invalidArguments that does not say which."
        }
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("after", after)
        put("before", before)
        inCalendar?.let { put("inCalendar", it.value) }
    }
}
