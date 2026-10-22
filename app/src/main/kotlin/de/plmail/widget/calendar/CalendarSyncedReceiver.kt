package de.plmail.widget.calendar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import de.plmail.feature.calendar.ACTION_CALENDAR_SYNCED
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Turns "a calendar sync rewrote the cache" into a widget re-render.
 *
 * The broadcast comes from `:feature:calendar` ([ACTION_CALENDAR_SYNCED]) rather than a direct call
 * because that module must not depend on `:app`, where the widgets live. `exported=false`: only
 * this app can send it — it is an internal nudge, not a public API.
 */
class CalendarSyncedReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CALENDAR_SYNCED) return

        val pending = goAsync()
        scope.launch {
            try {
                CalendarWidgetUpdater.updateAll(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }
}
