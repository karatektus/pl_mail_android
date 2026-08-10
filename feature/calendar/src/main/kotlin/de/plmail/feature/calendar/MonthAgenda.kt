package de.plmail.feature.calendar

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import de.plmail.core.designsystem.PlMailDivider
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The month and its agenda at once: a grid you scan over a list you read.
 *
 * **The view the phone needed and the browser did not.** A full-screen month cell here holds two or
 * three titled chips before it starts counting, which is enough to recognise a day and not enough
 * to plan one; the answer is not to cram the cell but to stop asking it to do both jobs. So the
 * grid above keeps only the job it is good at — *which* days are busy, in the calendar's own
 * colours — and the list below does the reading.
 *
 * **The grid gets [COMPACT_GRID_WEIGHT] of the height and the list gets the rest.** Weights rather
 * than a fraction of the screen: this pane is already inside the app bar, the pager and whatever
 * banners the last refresh left behind, and a grid sized to 40% of the *display* would be a
 * different share of what is actually left on a phone that is offline.
 *
 * **Selecting a day scrolls the list to it rather than filtering to it.** A filtered list is a day
 * view with extra steps, and this app has one of those; scrolling keeps the fortnight either side
 * of the selection one flick away, which is the question somebody in this view is usually asking. A
 * day with nothing on it has no header to scroll to, so the list lands on the next day that has one
 * — see [agendaAnchorIndex] — which is the honest answer to "show me from here".
 *
 * **The list covers the six weeks the grid draws**, spill-in days included, rather than the
 * calendar month. The two halves of one screen disagreeing about what "this month" means — a chip
 * visible in the grid with no row under it — is the kind of small lie that costs a user their trust
 * in both halves.
 */
@Composable
internal fun MonthAgendaPane(
    state: CalendarState,
    onOpen: (EventCluster) -> Unit,
    onCreateAt: (LocalDateTime) -> Unit,
    onPage: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val formats = rememberCalendarFormats()
    val list = rememberLazyListState()

    // Re-initialised when the month changes, deliberately: a selection is a
    // statement about a day on screen, and carrying "the 12th" onto a month
    // whose 12th the user has never looked at would scroll the list somewhere
    // nobody pointed at.
    var selected by
        rememberSaveable(state.anchor.month, state.anchor.year) {
            mutableStateOf<LocalDate?>(null)
        }

    // Today until somebody points at a day, so opening the month lands on now
    // rather than on the 27th of last month -- which is where a list of six
    // weeks starts.
    val target = selected ?: state.today

    // Keyed on the count as well as the day: a refresh that changes what is in
    // the list changes where the selected day sits in it, and an anchor that
    // only ever fired on the tap would leave the list pointing at the wrong row
    // afterwards.
    LaunchedEffect(target, state.days.size) {
        agendaAnchorIndex(state.days, target)?.let { list.animateScrollToItem(it) }
    }

    Column(modifier = modifier.fillMaxSize()) {
        MonthGrid(
            days = remember(state) { state.columns() },
            today = state.today,
            anchorMonth = state.anchor.monthValue,
            weekday = formats.weekday,
            // A tap selects rather than opening the day, because the day's
            // events are already on this screen: opening Day view from here
            // would be a control that throws away the half of the layout the
            // user chose this view for.
            onOpenDay = { selected = it },
            onCreateAt = onCreateAt,
            modifier = Modifier.weight(COMPACT_GRID_WEIGHT).pageOnSwipe(onPage),
            style = MonthCellStyle.DOTS,
            selected = selected,
        )

        PlMailDivider()

        AgendaList(
            days = state.days,
            status = state.status,
            list = list,
            onOpen = onOpen,
            // "The next 30 days are clear" is the agenda's sentence and it is
            // not true here: this list is a month, and a month that is empty is
            // empty for six weeks rather than for thirty days.
            emptyBody = stringResource(R.string.calendar_month_empty_body),
            modifier = Modifier.weight(1f - COMPACT_GRID_WEIGHT),
        )
    }
}

/**
 * Which item in the agenda list is where [date] begins.
 *
 * The list is a flat sequence of items — one header per day, then one row per cluster on it — so an
 * index into it is arithmetic over the days before the one wanted, and getting it wrong lands the
 * selection somewhere in the middle of the previous Tuesday.
 *
 * **The first day on or after [date] rather than [date] itself**, because the agenda leaves empty
 * days out entirely: that is what makes it an agenda. Selecting a quiet Thursday therefore scrolls
 * to the Friday, which is what "show me from here" means in a list that has nothing to show for
 * Thursday. Null when nothing is on from that day onwards, and the caller does not scroll — a list
 * jerking to its own end because somebody tapped an empty cell says the app did something, when the
 * truth is that there was nothing to do.
 */
internal fun agendaAnchorIndex(days: List<AgendaDay>, date: LocalDate): Int? {
    var index = 0

    days.forEach { day ->
        if (!day.date.isBefore(date)) return index

        // The header, plus a row per cluster. Clusters rather than rows: one
        // meeting stored on two calendars is one item in that list, and
        // counting it twice would put every later day's index out by one.
        index += HEADER_ITEM + day.clusters.size
    }

    return null
}

/** Every day contributes its header before its rows. */
private const val HEADER_ITEM = 1

/**
 * How much of the pane the compact grid takes.
 *
 * Six week rows at this share are about 50dp each on a 891dp phone, which is a 22dp date, a row of
 * dots, and the padding that keeps them apart. Less and the dots start colliding with the date;
 * much more and the list under it is showing four rows, at which point the pane is a month view
 * with a footer rather than two halves of one screen.
 */
private const val COMPACT_GRID_WEIGHT = 0.42f
