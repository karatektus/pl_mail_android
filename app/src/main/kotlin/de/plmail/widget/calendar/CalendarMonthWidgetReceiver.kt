package de.plmail.widget.calendar

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/** The month grid on the home screen. Thin by design: Glance does the RemoteViews work. */
class CalendarMonthWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CalendarMonthWidget()
}
