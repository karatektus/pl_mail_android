package de.plmail.feature.calendar

import de.plmail.core.data.EventDraft
import de.plmail.core.database.CalendarEventEntity
import de.plmail.jmap.calendar.RecurrenceRule
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * How often an event comes round, as the editor offers it.
 *
 * Five choices, matching the web's dropdown exactly. More elaborate rules — every second Tuesday,
 * weekdays only — are **not written by this product on either surface**, and are carried faithfully
 * when they arrive from an invitation, an import or a connected calendar. Which is why this only
 * ever reaches a *create*: see [EventDraft.recurrenceRule].
 */
enum class Repeat(val frequency: String?) {
    NEVER(null),
    DAILY("daily"),
    WEEKLY("weekly"),
    MONTHLY("monthly"),
    YEARLY("yearly");

    fun toRule(): RecurrenceRule? = frequency?.let { RecurrenceRule(frequency = it) }
}

/**
 * The editor's fields, as one value.
 *
 * Date and time are separate fields rather than one `LocalDateTime` because the two pickers are
 * separate controls and the all-day toggle uses only half of each — an all-day event has dates and
 * no times, and a state that carried a joined value would have to invent a time for it and then
 * remember to ignore it.
 */
data class EventFormState(
    val title: String = "",
    val startDate: LocalDate,
    val startTime: LocalTime,
    val endDate: LocalDate,
    val endTime: LocalTime,
    val isAllDay: Boolean = false,
    /** The `calendars` row this lands on, as its uid. Null before the list has been read. */
    val calendarKey: String? = null,
    val repeat: Repeat = Repeat.NEVER,
    val location: String = "",
    val description: String = "",
) {

    val start: LocalDateTime
        get() = if (isAllDay) startDate.atStartOfDay() else LocalDateTime.of(startDate, startTime)

    val end: LocalDateTime
        get() = if (isAllDay) endDate.atStartOfDay() else LocalDateTime.of(endDate, endTime)

    /**
     * Whether the times are the wrong way round.
     *
     * Two rules rather than one, because "after" means something different on each side of the
     * all-day toggle. A timed event has to *end* after it starts; an all-day event covers whole
     * days, so a start and end on the same day is one day long and perfectly ordinary — comparing
     * the two midnights would refuse the commonest all-day event there is.
     */
    val endsBeforeStart: Boolean
        get() = if (isAllDay) endDate.isBefore(startDate) else !end.isAfter(start)

    /** How many whole days an all-day event covers, both ends included. */
    private val allDayLength: Duration
        get() = Duration.ofDays(ChronoUnit.DAYS.between(startDate, endDate) + 1)

    /**
     * What goes on the wire.
     *
     * [untitled] is the localised word, passed in rather than read here: JMAP refuses an empty
     * title outright, and the web stores the word *Untitled* rather than refusing the save.
     * Matching it means an event created on a phone and one created in a browser are the same
     * event, which they would not be if one of them were called "" and the other could not exist.
     *
     * **[recurrenceRule] is only ever set on a create**, and the caller decides by passing
     * [isCreating]. An update that sent one would be sending a rule derived from a dropdown rather
     * than from the event: the cache stores *whether* a series recurs and not what by, so an
     * elaborate imported rule would be flattened to "every week" by somebody correcting a typo in
     * the title — or, worse, cleared entirely, because null on that property means "stop
     * recurring".
     *
     * The time zone is deliberately left null, which means the calendar's own. That is what the web
     * does, and it is the only answer this editor can honestly give: it offers no zone control, so
     * sending the *device's* zone would be recording a decision the user never made — and would
     * make the same event read differently on the two surfaces the first time somebody travels.
     */
    fun toDraft(untitled: String, isCreating: Boolean): EventDraft =
        EventDraft(
            title = title.trim().ifBlank { untitled },
            start = start,
            duration = if (isAllDay) allDayLength else Duration.between(start, end),
            isAllDay = isAllDay,
            timeZone = null,
            location = location.trim().takeIf { it.isNotBlank() },
            description = description.trim().takeIf { it.isNotBlank() },
            recurrenceRule = if (isCreating) repeat.toRule() else null,
        )

    companion object {

        /**
         * A new event, starting at the next whole hour and lasting one.
         *
         * The next hour rather than now: an editor opened at 14:07 that proposes 14:07 asks the
         * user to correct a time nobody would ever choose, and every calendar on every platform
         * rounds for the same reason.
         */
        fun forNewEvent(clock: Clock): EventFormState {
            val at = LocalDateTime.now(clock).truncatedTo(ChronoUnit.HOURS).plusHours(1)

            return EventFormState(
                startDate = at.toLocalDate(),
                startTime = at.toLocalTime(),
                endDate = at.plusHours(1).toLocalDate(),
                endTime = at.plusHours(1).toLocalTime(),
            )
        }

        /**
         * An existing event, from the **series** row rather than from the occurrence that was
         * tapped.
         *
         * That is the whole reason the editor asks the repository for an event instead of taking
         * the agenda row it came from. An update sends whole properties, so a form seeded from one
         * occurrence of a weekly standup would send that Tuesday as the series' start — and saving
         * a corrected spelling would drag every other occurrence onto the week the user happened to
         * be looking at.
         */
        fun of(event: CalendarEventEntity, clock: Clock): EventFormState {
            val fallback = forNewEvent(clock)
            val start = event.start.toLocalDateTimeOrNull() ?: fallback.start
            val end = start.plusWireDuration(event.duration) ?: start.plusHours(1)

            return EventFormState(
                title = event.title,
                startDate = start.toLocalDate(),
                startTime = start.toLocalTime(),
                // An all-day event's stored duration counts whole days from
                // midnight, so its end lands on the midnight *after* the last
                // day it covers. Drawing that date in the "Ends" field would say
                // a one-day event lasts two.
                endDate = if (event.isAllDay) end.toLocalDate().minusDays(1) else end.toLocalDate(),
                endTime = end.toLocalTime(),
                isAllDay = event.isAllDay,
                calendarKey = event.calendarKey,
                // Left at "does not repeat" and never drawn for an event that
                // does: the cache holds whether a series recurs and not the rule
                // it recurs by, so any value here would be a guess, and the
                // editor shows a read-only line instead of the dropdown.
                repeat = Repeat.NEVER,
                location = event.location.orEmpty(),
                description = event.description.orEmpty(),
            )
        }
    }
}

/**
 * ISO 8601, and quietly null for a shape `Duration` cannot hold.
 *
 * `Duration.parse` takes `PT15M` and `P1D` and not `P1M` — a month is not a fixed number of
 * seconds. An event carrying one opens with an hour's default rather than refusing to open, because
 * a form that cannot be reached is worse than one whose end has to be corrected.
 */
private fun LocalDateTime.plusWireDuration(iso: String?): LocalDateTime? = iso?.let {
    runCatching { plus(Duration.parse(it)) }.getOrNull()
}
