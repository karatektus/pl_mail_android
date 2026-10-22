package de.plmail.core.data

/**
 * Something that refreshes in the background when the server says it changed, or on the periodic
 * sync: the calendar, and the home-screen widgets that draw it.
 *
 * In `:core:data` as an interface because the widgets live in `:app`, which this module cannot name
 * — the same shape as [ReminderListener].
 */
interface BackgroundRefresh {
    /** [types] holds what changed, e.g. [CALENDAR]. */
    suspend fun refresh(types: Set<String>)

    companion object {
        /** The JMAP type a calendar change is announced as. */
        const val CALENDAR = "CalendarEvent"

        val ALL: Set<String> = setOf(CALENDAR)
    }
}
