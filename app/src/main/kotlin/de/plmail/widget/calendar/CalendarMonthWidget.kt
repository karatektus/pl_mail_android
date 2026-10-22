@file:Suppress("NonObservableLocale")

package de.plmail.widget.calendar

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import de.plmail.R
import de.plmail.core.database.AgendaRow
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle as JavaTextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * The month grid on the home screen, after Google Calendar's: title, previous/next month, a +
 * shortcut, and one cell per day holding the day's events as chips in their calendar's color — the
 * title (and the start time when the cell is wide enough), all-day events as solid chips and timed
 * ones as tinted chips. A day with more events than fit ends in a "+N" chip.
 *
 * Resizable both ways. Only the weeks the month needs are drawn (4–6), so cells get as tall as the
 * widget allows; when a cell is too small for text it falls back to one dot per event, and when the
 * widget is short the header goes away to save the room.
 *
 * The shown month is per widget instance, kept in Glance preferences — two month widgets can look
 * at two different months, and a reboot keeps them where they were. The grid reads the calendar
 * cache straight from Room, so it renders even when the app process was not running.
 */
class CalendarMonthWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    /** Told its exact size, so the grid can work out how many event chips each day cell holds. */
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val month = storedMonth(getAppWidgetState(context, PreferencesGlanceStateDefinition, id))
        val rows = runCatching { loadMonthRows(month, context) }.getOrDefault(emptyList())

        provideContent { MonthContent(context, month, rows) }
    }
}

/** The month this widget instance is looking at, defaulting to the current one. */
private fun storedMonth(prefs: Preferences): YearMonth =
    prefs[MonthShiftAction.MonthKey]?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
        ?: YearMonth.now()

/**
 * Steps the widget's own month forward or back. Public with no constructor arguments because Glance
 * instantiates the callback itself when the button is tapped.
 */
class MonthShiftAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val delta = parameters[DeltaKey] ?: return
        updateAppWidgetState(context, glanceId) { prefs ->
            prefs[MonthKey] = storedMonth(prefs).plusMonths(delta.toLong()).toString()
        }
        CalendarMonthWidget().update(context, glanceId)
    }

    companion object {
        val MonthKey = stringPreferencesKey("widget_month")
        val DeltaKey = ActionParameters.Key<Int>("month_delta")
    }
}

private fun monthTitle(month: YearMonth, locale: Locale): String {
    val now = YearMonth.now()
    val pattern = if (month.year == now.year) "MMMM" else "MMMM yyyy"
    return month.atDay(1).format(java.time.format.DateTimeFormatter.ofPattern(pattern, locale))
}

private fun GlanceModifier.headerButton(): GlanceModifier =
    padding(horizontal = 10.dp, vertical = 6.dp)

@Composable
private fun MonthContent(
    context: Context,
    month: YearMonth,
    rows: List<AgendaRow>,
) {
    val locale = Locale.getDefault()
    val openCalendar = actionStartActivity(openCalendarIntent(context))
    val firstDayOfWeek = WeekFields.of(locale).firstDayOfWeek
    val today = LocalDate.now()
    val byDate = rows.groupBy { it.date }
    val prevMonth =
        actionRunCallback<MonthShiftAction>(actionParametersOf(MonthShiftAction.DeltaKey to -1))
    val nextMonth =
        actionRunCallback<MonthShiftAction>(actionParametersOf(MonthShiftAction.DeltaKey to 1))

    val offset = (month.atDay(1).dayOfWeek.value - firstDayOfWeek.value + 7) % 7
    val weeks = (offset + month.lengthOfMonth() + 6) / 7
    val cells: List<LocalDate?> =
        (0 until weeks * 7).map { i ->
            val day = i - offset + 1
            if (day in 1..month.lengthOfMonth()) month.atDay(day) else null
        }

    // Space budget, in dp, to decide how many chips a day cell can hold.
    val size = LocalSize.current
    val showHeader = size.height >= 150.dp
    val pad = if (size.width < 150.dp || size.height < 150.dp) 6.dp else 10.dp
    val headerH = if (showHeader) 34f else 0f
    val gridH = size.height.value - pad.value * 2 - headerH - WEEKDAY_ROW_H
    val cellH = gridH / weeks
    val cellW = (size.width.value - pad.value * 2) / 7f
    val maxChips = ((cellH - NUMBER_H) / CHIP_H).toInt().coerceIn(0, 5)
    val chips = maxChips >= 1 && cellW >= 34f
    val showTime = cellW >= 80f
    val allDayLabel = context.getString(R.string.calendar_widget_all_day)

    val headerStyle =
        TextStyle(color = WidgetColors.onCard, fontSize = 18.sp, fontWeight = FontWeight.Medium)
    val navStyle =
        TextStyle(color = WidgetColors.muted, fontSize = 18.sp, fontWeight = FontWeight.Medium)

    Column(
        modifier =
            GlanceModifier.fillMaxSize()
                .appWidgetBackground()
                .background(WidgetColors.card)
                .padding(pad)
    ) {
        if (showHeader) {
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.Vertical.CenterVertically,
            ) {
                Text(
                    text = monthTitle(month, locale),
                    modifier = GlanceModifier.defaultWeight().clickable(openCalendar),
                    style = headerStyle,
                )
                Text(
                    text = "‹",
                    modifier = GlanceModifier.headerButton().clickable(prevMonth),
                    style = navStyle,
                )
                Text(
                    text = "›",
                    modifier = GlanceModifier.headerButton().clickable(nextMonth),
                    style = navStyle,
                )
                Text(
                    text = "+",
                    modifier = GlanceModifier.headerButton().clickable(openCalendar),
                    style = navStyle,
                )
            }
            Spacer(modifier = GlanceModifier.height(4.dp))
        }

        Row(modifier = GlanceModifier.fillMaxWidth()) {
            var day = firstDayOfWeek
            repeat(7) {
                Text(
                    text = day.getDisplayName(JavaTextStyle.NARROW, locale),
                    modifier = GlanceModifier.defaultWeight(),
                    style =
                        TextStyle(
                            color = WidgetColors.muted,
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                        ),
                )
                day = day.plus(1)
            }
        }

        cells.chunked(7).forEach { week ->
            Row(
                modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                verticalAlignment = Alignment.Vertical.Top,
            ) {
                week.forEach { date ->
                    if (date == null) {
                        Spacer(modifier = GlanceModifier.defaultWeight())
                    } else {
                        DayCell(
                            date = date,
                            isToday = date == today,
                            events = byDate[date.toString()].orEmpty(),
                            chips = chips,
                            maxChips = maxChips,
                            showTime = showTime,
                            allDayLabel = allDayLabel,
                            openCalendar = openCalendar,
                            modifier = GlanceModifier.defaultWeight().fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}

private const val WEEKDAY_ROW_H = 14f
private const val NUMBER_H = 22f
private const val CHIP_H = 16f

@Composable
private fun DayCell(
    date: LocalDate,
    isToday: Boolean,
    events: List<AgendaRow>,
    chips: Boolean,
    maxChips: Int,
    showTime: Boolean,
    allDayLabel: String,
    openCalendar: Action,
    modifier: GlanceModifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 1.dp).clickable(openCalendar),
        horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
    ) {
        Box(
            modifier =
                if (isToday) {
                    GlanceModifier.size(20.dp).background(WidgetColors.today).cornerRadius(10.dp)
                } else {
                    GlanceModifier.size(20.dp)
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = date.dayOfMonth.toString(),
                style =
                    TextStyle(
                        color = if (isToday) WidgetColors.onToday else WidgetColors.onCard,
                        fontSize = 12.sp,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                    ),
            )
        }

        if (events.isEmpty()) return@Column

        if (!chips) {
            Row(
                horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
                modifier = GlanceModifier.height(8.dp),
            ) {
                events.take(3).forEach { row ->
                    Text(
                        text = "•",
                        style = TextStyle(color = row.glanceColor(), fontSize = 8.sp),
                    )
                }
            }
            return@Column
        }

        // With room for only one chip, show the first event rather than a bare "+N".
        val overflow = events.size > maxChips && maxChips >= 2
        val visible = if (overflow) events.take(maxChips - 1) else events.take(maxChips)
        visible.forEach { row ->
            val label =
                if (showTime && !row.isAllDay) "${row.shortTime().orEmpty()} ${row.title}".trim()
                else row.title
            Box(modifier = GlanceModifier.fillMaxWidth().padding(bottom = 1.dp)) {
                Text(
                    text = label,
                    maxLines = 1,
                    modifier =
                        GlanceModifier.fillMaxWidth()
                            .padding(horizontal = 2.dp, vertical = 1.dp)
                            .background(if (row.isAllDay) row.glanceColor() else row.chipTint())
                            .cornerRadius(3.dp),
                    style =
                        TextStyle(
                            color = if (row.isAllDay) WidgetColors.onSolid else WidgetColors.onCard,
                            fontSize = 10.sp,
                        ),
                )
            }
        }
        if (overflow) {
            Text(
                text = "+${events.size - visible.size}",
                maxLines = 1,
                style = TextStyle(color = WidgetColors.muted, fontSize = 10.sp),
            )
        }
    }
}
