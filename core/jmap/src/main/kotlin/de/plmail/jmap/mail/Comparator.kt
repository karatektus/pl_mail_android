package de.plmail.jmap.mail

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A sort key. The server accepts exactly these five properties for `Email/query`; anything else is
 * refused rather than ignored.
 */
enum class SortProperty(val wire: String) {
    RECEIVED_AT("receivedAt"),
    FROM("from"),
    TO("to"),
    SUBJECT("subject"),
    SIZE("size"),
}

data class Comparator(val property: SortProperty, val isAscending: Boolean = false) {
    fun toJson(): JsonObject = buildJsonObject {
        put("property", property.wire)
        put("isAscending", isAscending)
    }

    companion object {
        /** Newest first — the order every mail list wants. */
        val NEWEST_FIRST = Comparator(SortProperty.RECEIVED_AT, isAscending = false)
    }
}
