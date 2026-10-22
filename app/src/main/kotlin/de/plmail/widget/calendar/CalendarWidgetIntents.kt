package de.plmail.widget.calendar

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import de.plmail.CalendarActivity
import de.plmail.feature.calendar.CalendarOpenRequest
import java.time.LocalDate

/**
 * The calendar widgets' taps: open the calendar on a day, or on one event of that day.
 *
 * Each target carries its own data URI as well as its extras — PendingIntents that differ only in
 * extras are one PendingIntent to Android, and every chip would open the first event.
 */
object CalendarWidgetIntents {
    private const val EXTRA_DATE = "de.plmail.widget.calendar.DATE"
    private const val EXTRA_EVENT = "de.plmail.widget.calendar.EVENT"

    fun day(context: Context, date: LocalDate): Intent = build(context, date, null)

    fun event(context: Context, date: LocalDate, eventKey: String): Intent =
        build(context, date, eventKey)

    private fun build(context: Context, date: LocalDate, eventKey: String?): Intent =
        Intent(context, CalendarActivity::class.java)
            .setData("plmail-calendar://open/$date/${eventKey.orEmpty()}".toUri())
            .putExtra(EXTRA_DATE, date.toString())
            .putExtra(EXTRA_EVENT, eventKey)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun read(intent: Intent?): CalendarOpenRequest? {
        val date =
            intent?.getStringExtra(EXTRA_DATE)?.let {
                runCatching { LocalDate.parse(it) }.getOrNull()
            } ?: return null
        return CalendarOpenRequest(date, intent.getStringExtra(EXTRA_EVENT))
    }
}
