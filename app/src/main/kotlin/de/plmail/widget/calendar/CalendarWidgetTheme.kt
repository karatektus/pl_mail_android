package de.plmail.widget.calendar

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.Color
import androidx.glance.color.ColorProvider
import de.plmail.CalendarActivity

/**
 * Shared look and tap targets for the two calendar widgets.
 *
 * The card is a pale cream in the light theme, after the refreshed Google Calendar widgets the user
 * pointed at — calm rather than branded, so it sits on a wallpaper rather than shouting over it.
 * Both widgets open [CalendarActivity] for every tap: it is `singleTask`, so a tap can never stack
 * a second calendar, and there is no create-event deep link to aim the + button at yet (see
 * todo.md's reminder-tap item) — it opens the calendar, where the editor is one tap away.
 */
internal fun openCalendarIntent(context: Context): Intent =
    Intent(context, CalendarActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

internal object WidgetColors {
    val card = ColorProvider(day = Color(0xFFF6F1E3), night = Color(0xFF1F1F23))
    val onCard = ColorProvider(day = Color(0xFF1F1F1F), night = Color(0xFFE8EAED))
    /** The day and month bands across the agenda: a step darker than the card. */
    val band = ColorProvider(day = Color(0xFFE6DCC4), night = Color(0xFF141417))
    val muted = ColorProvider(day = Color(0xFF5F6368), night = Color(0xFF9AA0A6))
    val today = ColorProvider(day = Color(0xFF0B57D0), night = Color(0xFFA8C7FA))
    val onToday = ColorProvider(day = Color(0xFFFFFFFF), night = Color(0xFF062E6F))
    val onSolid = ColorProvider(day = Color(0xFFFFFFFF), night = Color(0xFFFFFFFF))
    val fallbackEvent = ColorProvider(day = Color(0xFF5F6368), night = Color(0xFF9AA0A6))
}
