@file:Suppress("NonObservableLocale")

package de.plmail.widget.calendar

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import de.plmail.R
import de.plmail.core.data.CalendarWindow
import de.plmail.core.database.AgendaRow
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The agenda on the home screen, laid out after the owner's own sketch: today's date across the top
 * ("MON OCT 19", "MON 19" when narrow), then every event as its own rounded card in its calendar's
 * color. The first card of each later day carries the day ("TUE 20"); today's do not, the header
 * already says it. Each card shows the time ("All day" for an all-day event), the title in large
 * bold type, and at the top right small icons: a bell when it has reminders, the repeat arrows when
 * it repeats, a link when it was taken from an email. Text and icons are black or white, whichever
 * reads on the card's color.
 *
 * The days are a scrolling list, as in Google Calendar's own agenda widget: the widget fills
 * whatever height it really has and scrolls for the rest. It used to work out from the size the
 * launcher reported how many days would fit — and launchers that report far less than the widget's
 * real height left it showing a single event. Below [COMPACT_BELOW] it drops the month title and
 * the time line and shows `time title` on one line per event.
 *
 * Reads the cache straight from Room, so it renders even when the app process was not running.
 */
class CalendarAgendaWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val today = LocalDate.now()
        val now = LocalDateTime.now()
        var rows = runCatching {
            loadAgendaRows(today, AGENDA_LIMIT, context)
        }
            .getOrDefault(emptyList())
        // An empty cache (a fresh install, or an update that rebuilt it) would show "Nothing
        // coming up" until the app next synced; fetch the next weeks first, briefly.
        if (rows.isEmpty()) {
            withTimeoutOrNull(REFRESH_TIMEOUT_MS) {
                runCatching {
                    widgetCalendarRepository(context)
                        .refresh(CalendarWindow(today.minusDays(1), today.plusDays(DAYS_AHEAD)))
                }
            }
            rows =
                runCatching { loadAgendaRows(today, AGENDA_LIMIT, context) }
                    .getOrDefault(emptyList())
        }

        provideContent { AgendaContent(context, today, now, rows) }
    }

    companion object {
        /** More than any size can draw; the height decides how many are shown. */
        private const val AGENDA_LIMIT = 150
        private const val REFRESH_TIMEOUT_MS = 10_000L
    }
}

/** Below this height the widget switches to the one-line-per-event strip. */
private val COMPACT_BELOW = 120.dp

/** Narrower than this, the header leaves out the month: "MON 19". */
private val WIDE_HEADER_FROM = 220.dp

/** How far ahead, and how many events, the list goes: well past a week, never unbounded. */
private const val DAYS_AHEAD = 31L
private const val MAX_EVENTS = 80

/** One card: an event, and whether it is the first of a later day (so it carries the day). */
private class Card(
    val date: LocalDate,
    val row: AgendaRow,
    val showDay: Boolean,
    val past: Boolean,
    val key: Long,
)

/** Every event in the next [DAYS_AHEAD] days, one card each, earliest first. */
private fun cardsOf(rows: List<AgendaRow>, today: LocalDate, now: LocalDateTime): List<Card> {
    val cards = mutableListOf<Card>()
    val last = today.plusDays(DAYS_AHEAD)
    rows
        .take(MAX_EVENTS)
        .groupBy { it.date }
        .forEach { (dateString, dayRows) ->
            val date = runCatching { LocalDate.parse(dateString) }.getOrNull() ?: return@forEach
            if (date.isAfter(last)) return@forEach
            dayRows.forEachIndexed { index, row ->
                cards +=
                    Card(
                        date = date,
                        row = row,
                        showDay = index == 0 && date != today,
                        past = date == today && row.isPast(now),
                        key = date.toEpochDay() * 1000 + index,
                    )
            }
        }
    return cards
}

@Composable
private fun AgendaContent(
    context: Context,
    today: LocalDate,
    now: LocalDateTime,
    rows: List<AgendaRow>,
) {
    val locale = Locale.getDefault()
    val openCalendar = actionStartActivity(openCalendarIntent(context))
    val size = LocalSize.current
    val compact = size.height < COMPACT_BELOW
    val labels =
        CardLabels(
            allDay = context.getString(R.string.calendar_widget_all_day),
            reminder = context.getString(R.string.calendar_widget_reminder),
            repeats = context.getString(R.string.calendar_widget_repeats),
            linked = context.getString(R.string.calendar_widget_linked),
        )

    val cards = cardsOf(rows, today, now)

    Column(
        modifier =
            GlanceModifier.fillMaxSize()
                .appWidgetBackground()
                .background(WidgetColors.card)
                .cornerRadius(20.dp)
                .padding(horizontal = 6.dp, vertical = if (compact) 4.dp else 8.dp)
    ) {
        if (!compact) {
            Text(
                text = headerDate(today, locale, short = size.width < WIDE_HEADER_FROM),
                maxLines = 1,
                modifier =
                    GlanceModifier.fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .clickable(openCalendar),
                style =
                    TextStyle(
                        color = WidgetColors.onCard,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    ),
            )
            Spacer(modifier = GlanceModifier.height(6.dp))
        }

        if (cards.isEmpty()) {
            Text(
                text = context.getString(R.string.calendar_widget_empty),
                modifier = GlanceModifier.padding(horizontal = 8.dp).clickable(openCalendar),
                style = TextStyle(color = WidgetColors.muted, fontSize = 13.sp),
            )
        } else {
            LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                items(cards, itemId = { it.key }) { card ->
                    EventCard(
                        card,
                        compact,
                        labels,
                        locale,
                        actionStartActivity(
                            CalendarWidgetIntents.event(context, card.date, card.row.eventKey)
                        ),
                    )
                }
            }
        }
    }
}

/** The words a card needs, read once per render. */
private class CardLabels(
    val allDay: String,
    val reminder: String,
    val repeats: String,
    val linked: String,
)

/** "MON OCT 19", or "MON 19" on a narrow widget, in the device's language. */
private fun headerDate(date: LocalDate, locale: Locale, short: Boolean): String =
    date
        .format(DateTimeFormatter.ofPattern(if (short) "EEE d" else "EEE MMM d", locale))
        .uppercase(locale)

/** "TUE 20", in the device's language. */
private fun dayLabel(date: LocalDate, locale: Locale): String =
    date.format(DateTimeFormatter.ofPattern("EEE d", locale)).uppercase(locale)

/** Black or white, whichever reads on [background]. */
private fun readableOn(background: Color): ColorProvider {
    val ink = if (background.luminance() > 0.5f) Color.Black else Color.White
    return ColorProvider(day = ink, night = ink)
}

/**
 * One event as a rounded card in its calendar's color: the day (first card of a later day) with the
 * icons at the right, the time, and the title large and bold. A card already over is a soft tint.
 * In the compact strip it is one line: `TUE 20 · 10:00 Title`.
 */
@Composable
private fun EventCard(
    card: Card,
    compact: Boolean,
    labels: CardLabels,
    locale: Locale,
    open: Action,
) {
    val row = card.row
    val base = row.colorOrNull()
    val fill = if (card.past) row.chipTint() else row.glanceColor()
    val ink =
        when {
            card.past -> WidgetColors.muted
            base != null -> readableOn(base)
            else -> WidgetColors.onSolid
        }
    val time = row.timeRange(labels.allDay)
    val day = if (card.showDay) dayLabel(card.date, locale) else null

    val gap = if (compact) 1.dp else 3.dp
    Column(modifier = GlanceModifier.fillMaxWidth().padding(vertical = gap)) {
        Column(
            modifier =
                GlanceModifier.fillMaxWidth()
                    .background(fill)
                    .cornerRadius(if (compact) 6.dp else 14.dp)
                    .padding(horizontal = 12.dp, vertical = if (compact) 2.dp else 8.dp)
                    .clickable(open)
        ) {
            if (compact) {
                Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
                    Text(
                        text =
                            listOfNotNull(day, if (row.isAllDay) labels.allDay else row.shortTime())
                                .joinToString(" · "),
                        maxLines = 1,
                        style = TextStyle(color = ink, fontSize = 11.sp),
                    )
                    Spacer(modifier = GlanceModifier.width(6.dp))
                    Text(
                        text = row.title,
                        maxLines = 1,
                        style =
                            TextStyle(color = ink, fontSize = 12.sp, fontWeight = FontWeight.Bold),
                    )
                }
                return@Column
            }

            // Top line: the day when this card carries it, otherwise the time — with the icons.
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.Vertical.CenterVertically,
            ) {
                Text(
                    text = day ?: time,
                    maxLines = 1,
                    modifier = GlanceModifier.defaultWeight(),
                    style =
                        if (day != null) {
                            TextStyle(color = ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        } else {
                            TextStyle(color = ink, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        },
                )
                if (row.hasAlerts) StatusIcon(R.drawable.ic_widget_bell, labels.reminder, ink)
                if (row.isRecurring) StatusIcon(R.drawable.ic_widget_repeat, labels.repeats, ink)
                if (row.isLinked) StatusIcon(R.drawable.ic_widget_link, labels.linked, ink)
            }
            if (day != null && time.isNotEmpty()) {
                Text(
                    text = time,
                    maxLines = 1,
                    style =
                        TextStyle(color = ink, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                )
            }
            Text(
                text = row.title,
                maxLines = 2,
                style = TextStyle(color = ink, fontSize = 18.sp, fontWeight = FontWeight.Bold),
            )
        }
    }
}

/** A small icon in the card's ink: bell, repeat or link. */
@Composable
private fun StatusIcon(icon: Int, description: String, ink: ColorProvider) {
    Image(
        provider = ImageProvider(icon),
        contentDescription = description,
        colorFilter = ColorFilter.tint(ink),
        modifier = GlanceModifier.size(20.dp).padding(start = 4.dp),
    )
}
