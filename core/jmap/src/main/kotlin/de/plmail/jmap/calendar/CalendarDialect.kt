package de.plmail.jmap.calendar

import de.plmail.jmap.protocol.Capability
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Which calendars URN a request declares, and so which shape the server answers it in.
 *
 * The app speaks the IETF draft's shape — [CalendarEvent] and every write are spelled in it — and
 * the server answers in it whenever the request declares [Capability.CALENDARS]. [PLMAIL] is for a
 * server released before it did: the same methods under [Capability.PLMAIL_CALENDARS], answering in
 * plMail's earlier shape, which is translated here at the edge and nowhere else. The server does
 * the same thing in reverse (`IetfCalendarShape`), and for the same reason: when the vendor URN
 * goes (pl_mail#409), this enum entry and its three functions go with it and nothing else changes.
 *
 * The two shapes differ in four names:
 * - an event is filed by a `calendarIds` map in the draft and by one `calendarId` in plMail's;
 * - it repeats by one `recurrenceRule` in the draft and by a `recurrenceRules` list in plMail's;
 * - an occurrence names its series by `baseEventId` in the draft and by `seriesId` in plMail's;
 * - whether it recurs is the vendor property `plmail.org:isRecurring` under the draft's URN, which
 *   has nothing of its own to say it with, and plain `isRecurring` in plMail's. See
 *   [CalendarEvent.isRecurring] for why it is asked for at all.
 */
enum class CalendarDialect(val capability: String) {
    IETF(Capability.CALENDARS),
    PLMAIL(Capability.PLMAIL_CALENDARS);

    /**
     * What a calendar request declares.
     *
     * Mail is deliberately absent: a calendar request needs nothing from it, and declaring
     * capabilities a request does not use is how a client ends up broken on an instance where one
     * of them is switched off.
     */
    val using: List<String>
        get() = listOf(Capability.CORE, capability)

    /**
     * The property names a `CalendarEvent/get` asks for, in this dialect's names.
     *
     * Where the rule is asked for, whether the event recurs is asked for beside it: the server's
     * answer to that is better than the rule's — see [CalendarEvent.isRecurring].
     */
    fun properties(names: List<String>): List<String> {
        val asked =
            if (RECURRENCE_RULE in names && IS_RECURRING !in names) names + IS_RECURRING else names

        return when (this) {
            IETF -> asked
            PLMAIL -> asked.map { VENDOR_NAMES[it] ?: it }
        }
    }

    /** An event as the server published it, in the draft's shape. */
    fun read(event: JsonObject): JsonObject =
        when (this) {
            IETF -> event
            PLMAIL ->
                JsonObject(
                    buildMap {
                        event.forEach { (name, value) ->
                            when (name) {
                                VENDOR_CALENDAR_ID -> put(CALENDAR_IDS, calendarIds(value))
                                VENDOR_RECURRENCE_RULES -> put(RECURRENCE_RULE, firstRule(value))
                                VENDOR_SERIES_ID -> put(BASE_EVENT_ID, value)
                                VENDOR_IS_RECURRING -> put(IS_RECURRING, value)
                                else -> put(name, value)
                            }
                        }
                    }
                )
        }

    /** A create or an update patch, spelled in the draft's names, in this dialect's. */
    fun write(event: JsonObject): JsonObject =
        when (this) {
            IETF -> event
            PLMAIL ->
                JsonObject(
                    buildMap {
                        event.forEach { (name, value) ->
                            when (name) {
                                CALENDAR_IDS -> put(VENDOR_CALENDAR_ID, calendarId(value))
                                RECURRENCE_RULE ->
                                    put(
                                        VENDOR_RECURRENCE_RULES,
                                        if (value is JsonObject) JsonArray(listOf(value))
                                        else JsonNull,
                                    )
                                else -> put(name, value)
                            }
                        }
                    }
                )
        }

    companion object {
        /**
         * The dialect a session's calendars are read in, or null when it serves none.
         *
         * The IETF one whenever it is advertised, even beside the vendor one: a server that
         * publishes both answers both, and the IETF one is the one that stays.
         */
        fun of(capabilities: Set<String>): CalendarDialect? =
            when {
                Capability.CALENDARS in capabilities -> IETF
                Capability.PLMAIL_CALENDARS in capabilities -> PLMAIL
                else -> null
            }

        private const val CALENDAR_IDS = "calendarIds"
        private const val RECURRENCE_RULE = "recurrenceRule"
        private const val BASE_EVENT_ID = "baseEventId"
        private const val IS_RECURRING = CalendarEvent.IS_RECURRING

        private const val VENDOR_CALENDAR_ID = "calendarId"
        private const val VENDOR_RECURRENCE_RULES = "recurrenceRules"
        private const val VENDOR_SERIES_ID = "seriesId"
        private const val VENDOR_IS_RECURRING = "isRecurring"

        private val VENDOR_NAMES =
            mapOf(
                CALENDAR_IDS to VENDOR_CALENDAR_ID,
                RECURRENCE_RULE to VENDOR_RECURRENCE_RULES,
                BASE_EVENT_ID to VENDOR_SERIES_ID,
                IS_RECURRING to VENDOR_IS_RECURRING,
            )

        private fun calendarIds(calendarId: JsonElement): JsonElement =
            (calendarId as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.let { JsonObject(mapOf(it.content to JsonPrimitive(true))) }
                ?: JsonObject(emptyMap())

        /**
         * The one calendar a `calendarIds` map names. plMail files an event in exactly one, so
         * there is no second to lose; a map naming none is sent as null for the server to refuse.
         */
        private fun calendarId(calendarIds: JsonElement): JsonElement =
            (calendarIds as? JsonObject)
                ?.entries
                ?.firstOrNull { (it.value as? JsonPrimitive)?.booleanOrNull == true }
                ?.key
                ?.let(::JsonPrimitive) ?: JsonNull

        private fun firstRule(rules: JsonElement): JsonElement =
            (rules as? JsonArray)?.firstOrNull() as? JsonObject ?: JsonNull
    }
}
