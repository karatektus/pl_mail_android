package de.plmail.widget.calendar

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager

/**
 * Re-renders every placed calendar widget from the current cache.
 *
 * Called after a calendar sync rewrites the cache (via [CalendarSyncedReceiver]) and never on a
 * timer of our own — the appwidget-provider XMLs carry an `updatePeriodMillis` for the rest, so an
 * agenda left overnight still rolls "today" forward without the app being open.
 */
object CalendarWidgetUpdater {

    suspend fun updateAll(context: Context) {
        val manager = GlanceAppWidgetManager(context)

        manager.getGlanceIds(CalendarMonthWidget::class.java).forEach { id ->
            runCatching { CalendarMonthWidget().update(context, id) }
        }
        manager.getGlanceIds(CalendarAgendaWidget::class.java).forEach { id ->
            runCatching { CalendarAgendaWidget().update(context, id) }
        }
    }
}
