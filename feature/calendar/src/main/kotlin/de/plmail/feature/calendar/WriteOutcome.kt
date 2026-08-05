package de.plmail.feature.calendar

import de.plmail.core.data.CalendarWriteResult

/**
 * How a save or a delete ended, in the terms the failures actually differ in.
 *
 * The distinction is the repository's and it is load-bearing: a refusal is an answer and is shown
 * in the server's own words, a transport failure is not and names the host. Neither is retried and
 * neither is queued — replaying a refusal produces a loop that terminates never, and this surface
 * has no `ifInState` on which to build a conflict story for a queue. Both have already been taken
 * back off the cache by the time one of these arrives, which is why the screens report rather than
 * offer an undo.
 */
sealed interface WriteOutcome {
    data object Saved : WriteOutcome

    data object Deleted : WriteOutcome

    /** The server answered, and the answer was no. [reason] is its own words. */
    data class Refused(val reason: String, val isForbidden: Boolean) : WriteOutcome

    /** Nothing answered. */
    data class Unreachable(val host: String?) : WriteOutcome

    /** The server stopped offering a calendar between the screen opening and the write. */
    data object NoCalendar : WriteOutcome
}

internal fun CalendarWriteResult.asOutcome(success: WriteOutcome): WriteOutcome =
    when (this) {
        is CalendarWriteResult.Applied -> success
        is CalendarWriteResult.Rejected -> WriteOutcome.Refused(reason, isForbidden)
        is CalendarWriteResult.Unreachable -> WriteOutcome.Unreachable(host)
        CalendarWriteResult.NoCalendarAccount -> WriteOutcome.NoCalendar
    }
