package de.plmail.widget.calendar

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.toColorInt
import androidx.glance.color.ColorProvider
import androidx.glance.unit.ColorProvider
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import de.plmail.core.data.CalendarRepository
import de.plmail.core.database.AgendaRow
import de.plmail.core.database.CalendarEventDao
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.flow.first

/**
 * Reads the calendar cache for the home-screen widgets.
 *
 * A widget update runs in this process but outside any activity: the launcher asks Glance, Glance
 * starts the process if it is not running, and there is no Hilt-injected component on that path.
 * The [EntryPoint] is how a widget reaches the same [CalendarEventDao] the calendar screens use. It
 * mirrors what PushWorker does for the same reason.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface CalendarWidgetDependencies {
    fun calendarEvents(): CalendarEventDao

    fun calendarRepository(): CalendarRepository
}

internal fun widgetCalendarRepository(context: Context): CalendarRepository =
    EntryPointAccessors.fromApplication(context, CalendarWidgetDependencies::class.java)
        .calendarRepository()

internal suspend fun widgetEventDao(context: Context): CalendarEventDao =
    EntryPointAccessors.fromApplication(context, CalendarWidgetDependencies::class.java)
        .calendarEvents()

/** The wire's "cancelled", matching `STATUS_CANCELLED` in :feature:calendar. */
private const val STATUS_CANCELLED = "cancelled"

/**
 * One row per meeting: hidden calendars are the user's explicit "not this one", and a cancelled
 * series is shown struck-through in the app — a widget has no room for that nuance, so it is left
 * out rather than drawn as if it were happening.
 */
private fun List<AgendaRow>.forWidget(): List<AgendaRow> = filter {
    it.calendarIsVisible != false && it.status != STATUS_CANCELLED
}
    .distinctBy { it.occurrenceKey() }

/**
 * One row per occurrence: the same event on the same day at the same time, as when one meeting sits
 * in two calendars. Keyed by uid alone this kept only the first occurrence of a repeating event,
 * and with blank uids only the first event of all, so the agenda showed a single item.
 */
private fun AgendaRow.occurrenceKey(): String {
    val id = eventUid?.takeIf { it.isNotBlank() } ?: eventKey
    return "$id@$date@${startLocal.orEmpty()}"
}

/** Everything in [month] for the month grid, earliest first. */
internal suspend fun loadMonthRows(month: YearMonth, context: Context): List<AgendaRow> =
    widgetEventDao(context)
        .observeBetween(month.atDay(1).toString(), month.plusMonths(1).atDay(1).toString())
        .first()
        .forWidget()

/** Everything from [from] onwards for the agenda, earliest first. */
internal suspend fun loadAgendaRows(
    from: LocalDate,
    limit: Int,
    context: Context,
): List<AgendaRow> =
    widgetEventDao(context).observeAgenda(from.toString(), limit).first().forWidget()

/** "10:00" in the device's own time pattern, or null for an all-day event. */
internal fun AgendaRow.shortTime(): String? {
    if (isAllDay) return null
    val time =
        startLocal?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }?.toLocalTime()
            ?: return null
    return time.format(
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault())
    )
}

/** The device's own short time pattern, resolved per call so a locale change is picked up. */
private fun clockFormat(): DateTimeFormatter =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault())

private fun String?.toLdtOrNull(): LocalDateTime? =
    this?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }

/**
 * "10:00 – 11:00" in the device's time pattern, [allDay] for an all-day event, and just the start
 * when there is no usable end. This is what Google Calendar's agenda prints under a title.
 */
internal fun AgendaRow.timeRange(allDay: String): String {
    if (isAllDay) return allDay
    val fmt = clockFormat()
    val start = startLocal.toLdtOrNull()?.toLocalTime() ?: return ""
    val end = endLocal.toLdtOrNull()?.toLocalTime()
    if (end == null || end == start) return start.format(fmt)
    return "${start.format(fmt)} – ${end.format(fmt)}"
}

/** True once a timed event has finished, so today's agenda can dim what is already over. */
internal fun AgendaRow.isPast(now: LocalDateTime): Boolean {
    if (isAllDay) return false
    val end = endLocal.toLdtOrNull() ?: startLocal.toLdtOrNull() ?: return false
    return end.isBefore(now)
}

/** Soft tint of the calendar's color, for a timed event's chip on the month grid. */
internal fun AgendaRow.chipTint(): ColorProvider {
    val base = colorOrNull() ?: Color(0xFF9AA0A6)
    return ColorProvider(day = base.copy(alpha = 0.30f), night = base.copy(alpha = 0.55f))
}

/** The calendar's color as a Compose color, or null when it is missing or unparsable. */
internal fun AgendaRow.colorOrNull(): Color? = calendarColor?.let {
    runCatching { Color(it.toColorInt()) }.getOrNull()
}

/** The calendar's color as a Glance [ColorProvider], falling back to muted gray. */
internal fun AgendaRow.glanceColor(): ColorProvider {
    val base = colorOrNull()
    return if (base != null) ColorProvider(day = base, night = base) else WidgetColors.fallbackEvent
}
