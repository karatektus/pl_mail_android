package de.plmail.feature.calendar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.plmail.core.designsystem.PaneTone
import de.plmail.core.designsystem.PlMailBanner
import de.plmail.core.designsystem.PlMailDivider
import de.plmail.core.designsystem.PlMailEmptyState
import de.plmail.core.designsystem.PlMailTheme
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * The calendar, in whichever of its four views the user is in.
 *
 * **One screen rather than four**, because the top bar, the banners, the pull-to-refresh and the
 * horizon footer are the same in all of them and the day they were written four times is the day
 * one of them stopped saying "there may be more".
 *
 * The chrome above the calendar is now **two rows**, and losing the third is the point: the
 * switcher used to have a row to itself and has moved into the bar as `ViewMenu`, which is a touch
 * target of height given back to the one thing this screen is for.
 *
 * - **The app bar** keeps what was already there — back, the calendar's name, Today, New — plus the
 *   view menu. **Today is a word rather than an icon**, and that is the defect this whole feature
 *   started from: `Icons.Outlined.Today` draws a calendar page, people read it as "switch view",
 *   and there was no switcher to find. The web's toolbar has spelled it "Today" all along. It also
 *   does more than it used to — it means "show me now" in whatever view is open, rather than
 *   "scroll this list to the top", which was the only thing there was to mean.
 * - **The pager**, on paged views only: chevrons either side of where you are. The agenda has none,
 *   because it is a rolling list from today and Previous on it would be a control that scrolls.
 *
 * **Swiping pages the grid views**, which is the gesture anybody arriving from another calendar app
 * will try first. It is deliberately not on the agenda: that list scrolls vertically inside a
 * pull-to-refresh, and a third gesture over the same surface is one arbitration too many.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CalendarBoard(
    state: CalendarState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onWindowShown: () -> Unit,
    onChoose: (CalendarViewMode) -> Unit,
    onPage: (Boolean) -> Unit,
    onToday: () -> Unit,
    onOpen: (EventCluster) -> Unit,
    onOpenDay: (LocalDate) -> Unit,
    onNew: () -> Unit,
    onCreateAt: (LocalDateTime) -> Unit,
) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val formats = rememberCalendarFormats()

    // On open, and whenever what is on screen changes -- and never on a timer.
    // This surface has no delta and no push, so a refresh is the whole windowed
    // query again, against a machine with one PHP worker pool.
    //
    // `onWindowShown` rather than `onRefresh`: the first frame carries a
    // placeholder window, so this effect legitimately fires twice for what turns
    // out to be one span, and the ViewModel is where "has this been asked
    // already" is known. A pull still goes to `onRefresh`, which always asks.
    LaunchedEffect(state.window) { onWindowShown() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = PlMailTheme.colors.surface,
        topBar = {
            TopAppBar(
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = PlMailTheme.colors.surface,
                        scrolledContainerColor = PlMailTheme.colors.surface,
                        titleContentColor = PlMailTheme.colors.ink,
                        navigationIconContentColor = PlMailTheme.colors.inkSoft,
                        actionIconContentColor = PlMailTheme.colors.inkSoft,
                    ),
                title = { Text(stringResource(R.string.calendar_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.calendar_back),
                        )
                    }
                },
                actions = {
                    // First, and left of Today: it is the only action here that
                    // changes what the rest of the bar means -- Today and New
                    // both act *within* whatever view this one has chosen.
                    ViewMenu(chosen = state.view, onChoose = onChoose)

                    // A word rather than an icon. The icon this replaced was
                    // being read as a view switcher, and now that there really
                    // is one beside it the word is what keeps the two apart.
                    TextButton(
                        onClick = {
                            onToday()

                            // The agenda already starts at today, so "show me
                            // now" there is a scroll rather than a window.
                            if (state.view == CalendarViewMode.AGENDA) {
                                scope.launch { list.animateScrollToItem(0) }
                            }
                        }
                    ) {
                        Text(
                            text = stringResource(R.string.calendar_today),
                            style = MaterialTheme.typography.labelLarge,
                            color = PlMailTheme.colors.accent,
                        )
                    }

                    IconButton(onClick = onNew) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = stringResource(R.string.calendar_new_event),
                        )
                    }
                },
            )
        },
    ) { insets ->
        Column(modifier = Modifier.fillMaxSize().padding(insets)) {
            if (state.view.isPaged) {
                Pager(
                    heading = state.view.heading(state.anchor, state.firstDayOfWeek, formats),
                    onPage = onPage,
                )
            }

            Banners(state.status)

            PullToRefreshBox(
                isRefreshing = state.status.isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                when (state.view) {
                    CalendarViewMode.AGENDA ->
                        AgendaList(state = state, list = list, onOpen = onOpen)
                    CalendarViewMode.DAY,
                    CalendarViewMode.WEEK ->
                        TimeGrid(
                            days = remember(state) { state.grids() },
                            today = state.today,
                            now = state.now,
                            formats = formats,
                            onOpen = onOpen,
                            onCreateAt = onCreateAt,
                            modifier = Modifier.pageOnSwipe(onPage),
                        )
                    CalendarViewMode.MONTH ->
                        MonthGrid(
                            days = remember(state) { state.columns() },
                            today = state.today,
                            anchorMonth = state.anchor.monthValue,
                            weekday = formats.weekday,
                            onOpenDay = onOpenDay,
                            onCreateAt = onCreateAt,
                            modifier = Modifier.pageOnSwipe(onPage),
                        )
                }
            }
        }
    }
}

/**
 * A horizontal swipe steps the view.
 *
 * On the drag's **end** rather than as it moves, deliberately: a window is a request, and paging on
 * every few pixels would fire one per frame at a Raspberry Pi. There is no rubber-band follow for
 * the same reason the now line does not tick — the honest thing to animate would be the next
 * window, and the next window is not on the device yet.
 *
 * The threshold is a fraction of the screen rather than a fixed dp, so a tablet does not page on
 * what is a twitch at its width.
 */
@Composable
private fun Modifier.pageOnSwipe(onPage: (Boolean) -> Unit): Modifier {
    val density = LocalDensity.current
    // The container's own width rather than `Configuration.screenWidthDp`,
    // which is the whole display and is wrong in a split screen and on a
    // tablet's list/detail pane -- the two places a "fraction of the width"
    // threshold most needs to be a fraction of the *right* width.
    val width = LocalWindowInfo.current.containerSize.width
    val threshold = with(density) { width.toDp().value } * SWIPE_FRACTION

    return pointerInput(threshold) {
        var travelled = 0f

        detectHorizontalDragGestures(
            onDragStart = { travelled = 0f },
            onDragEnd = {
                val dp = with(density) { travelled.toDp().value }

                // Left means forward, which is the direction the content moves
                // rather than the direction the finger did -- the same way every
                // pager on this platform reads a swipe.
                if (abs(dp) >= threshold) onPage(dp < 0)
            },
            onHorizontalDrag = { _, delta -> travelled += delta },
        )
    }
}

/** How much of the screen a swipe has to cross before it counts as a page. */
private const val SWIPE_FRACTION = 0.2f

/** Where you are, with a step either side of it. */
@Composable
private fun Pager(heading: String, onPage: (Boolean) -> Unit) {
    val theme = PlMailTheme.values

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = theme.spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onPage(false) }) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.calendar_previous),
                tint = theme.colors.inkSoft,
            )
        }

        Text(
            text = heading,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = theme.colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            // A heading for TalkBack, so the rotor can reach "which week am I
            // looking at" without walking the whole grid.
            modifier = Modifier.weight(1f).semantics { heading() },
        )

        IconButton(onClick = { onPage(true) }) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = stringResource(R.string.calendar_next),
                tint = theme.colors.inkSoft,
            )
        }
    }
}

/**
 * What is coming up, as a rolling list from today.
 *
 * The web's agenda, on a phone: thirty days, grouped by day, with the empty days left out — which
 * is the whole difference between an agenda and a month grid.
 *
 * The rows are the cache, and they stay on screen through every failure above. A calendar that
 * could not be refreshed is still a correct account of what the phone last heard, and blanking it
 * would throw away the only thing that is still true.
 */
@Composable
private fun AgendaList(
    state: CalendarState,
    list: LazyListState,
    onOpen: (EventCluster) -> Unit,
) {
    if (state.days.isEmpty()) {
        // "Nothing coming up" and "not asked yet" are different answers, and
        // showing the first while the first refresh is in flight tells somebody
        // their month is empty when it is not.
        if (state.status.hasSettled) {
            PlMailEmptyState(
                icon = Icons.Outlined.CalendarMonth,
                title = stringResource(R.string.calendar_empty),
                body = stringResource(R.string.calendar_empty_body),
            )
        } else {
            Loading()
        }

        return
    }

    // Where a row's title starts, computed rather than written down: the gutter
    // and the gaps either side of the dot are density-scaled tokens, so a
    // constant would line the hairline up with the title in one density and
    // nowhere else.
    val textInset =
        PlMailTheme.spacing.gutter +
            TIME_COLUMN +
            PlMailTheme.spacing.medium +
            DOT_SIZE +
            PlMailTheme.spacing.medium

    LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
        state.days.forEach { day ->
            item(key = day.date.toString()) { DayHeader(day) }

            day.clusters.forEachIndexed { index, cluster ->
                item(key = "${day.date}/${cluster.primary.eventKey}/$index") {
                    AgendaRowItem(cluster = cluster, onClick = { onOpen(cluster) })

                    // Between rows of one day, never after the last: the day
                    // header below is already the separator, and a hairline
                    // above it makes the header look like a row of the day
                    // before.
                    if (index < day.clusters.lastIndex) {
                        PlMailDivider(startIndent = textInset)
                    }
                }
            }
        }

        if (state.status.mayBeIncomplete) {
            item(key = HORIZON) { HorizonNote(state.status.horizon) }
        }
    }
}

/**
 * What the last refresh could not do, above the view rather than instead of it.
 *
 * Offline first and alone, because it explains the other: with no network the server was never
 * going to answer, and two banners saying so is two copies of one fact.
 */
@Composable
private fun Banners(status: CalendarStatus) {
    val padding =
        Modifier.padding(
            horizontal = PlMailTheme.spacing.medium,
            vertical = PlMailTheme.spacing.small,
        )

    when {
        status.isOffline ->
            PlMailBanner(
                text = stringResource(R.string.calendar_offline),
                tone = PaneTone.WARNING,
                modifier = padding,
            )
        status.isUnreachable ->
            PlMailBanner(
                // The host by name. "Could not reach the server" sends somebody
                // to check their wifi; naming the machine tells them which box
                // to go and look at.
                text =
                    status.host?.let { stringResource(R.string.calendar_unreachable, it) }
                        ?: stringResource(R.string.calendar_unreachable_unnamed),
                tone = PaneTone.WARNING,
                modifier = padding,
            )
        status.refusal != null ->
            PlMailBanner(
                // The server's own words. Several of this surface's refusals
                // carry nothing but a type, and "invalidArguments" on screen is
                // at least a string somebody can search for.
                text = stringResource(R.string.calendar_refresh_refused, status.refusal),
                tone = PaneTone.DANGER,
                modifier = padding,
            )
    }
}

@Composable
private fun DayHeader(day: AgendaDay) {
    Text(
        text = day.date.format(DAY_HEADER),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = PlMailTheme.colors.ink,
        modifier =
            Modifier.fillMaxWidth()
                .padding(
                    start = PlMailTheme.spacing.gutter,
                    end = PlMailTheme.spacing.gutter,
                    top = PlMailTheme.spacing.large,
                    bottom = PlMailTheme.spacing.small,
                )
                .semantics { heading() },
    )
}

/**
 * One row of the agenda.
 *
 * The dot is the calendar's own hex colour and is the single thing on this screen that does not
 * come from a token — see [calendarColor] — and it is a *pie* when the meeting is held on more than
 * one calendar, which is the same affordance every other view uses. Everything else is the ink
 * scale doing what it is for: the title is what the event *is*, the time and the place are what it
 * is about.
 *
 * The whole row carries one sentence for TalkBack rather than four stops, because four stops per
 * event is a screenful of fragments — "09:00", "Standup", "Küche", "Arbeit" — that have to be
 * reassembled by the listener.
 */
@Composable
private fun AgendaRowItem(cluster: EventCluster, onClick: () -> Unit) {
    val theme = PlMailTheme.values
    val row = cluster.primary
    val allDay = stringResource(R.string.calendar_all_day)
    val time = startTimeOf(row)?.format(CLOCK) ?: allDay
    val sentence = cluster.a11ySentence()

    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clickable(onClick = onClick)
                // The whole row is one target and one description. 48dp is the
                // floor whatever the density says -- `touchTarget` is the one
                // spacing token that does not scale.
                .heightIn(min = theme.spacing.touchTarget)
                .padding(horizontal = theme.spacing.gutter, vertical = theme.spacing.small)
                .clearAndSetSemantics { contentDescription = sentence },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(theme.spacing.medium),
    ) {
        Text(
            text = time,
            style = MaterialTheme.typography.bodyMedium,
            color = theme.colors.inkMuted,
            maxLines = 2,
            // Fixed, so every row's title starts at the same edge. "Ganztägig"
            // is nearly twice "All day" and a column sized to the content would
            // move the whole list sideways on a German phone.
            modifier = Modifier.width(TIME_COLUMN),
        )

        CalendarDot(colors = cluster.dotColors())

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.bodyLarge,
                color = theme.colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            row.location
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.colors.inkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
        }
    }
}

/**
 * What the server does not promise, said quietly.
 *
 * The client cannot say more than "there may be more": `materialisedHorizon` is published as PHP
 * relative-date expressions, which are opaque strings nothing here may parse. So the server's own
 * words are quoted where it gave any, and a generic line stands in where it did not — saying "there
 * may be more" about a full month is a smaller lie than saying nothing about an empty one.
 */
@Composable
private fun HorizonNote(horizon: String?) {
    Text(
        text =
            horizon?.let { stringResource(R.string.calendar_horizon, it) }
                ?: stringResource(R.string.calendar_horizon_generic),
        style = MaterialTheme.typography.bodySmall,
        color = PlMailTheme.colors.inkFaint,
        textAlign = TextAlign.Center,
        modifier =
            Modifier.fillMaxWidth()
                .padding(
                    horizontal = PlMailTheme.spacing.gutter,
                    vertical = PlMailTheme.spacing.xLarge,
                ),
    )
}

@Composable
private fun Loading() {
    Column(
        modifier = Modifier.fillMaxSize().padding(PlMailTheme.spacing.xxLarge),
        verticalArrangement =
            Arrangement.spacedBy(PlMailTheme.spacing.small, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.calendar_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = PlMailTheme.colors.inkMuted,
        )
    }
}

/**
 * How wide the agenda's time column is.
 *
 * Sized for the German "Ganztägig" rather than for "09:00", because the word that has to fit is the
 * longest one, and a column that fits the times and ellipsises the word says nothing at all on the
 * rows that carry it.
 */
private val TIME_COLUMN = 72.dp

/** Keyed, so the footer is not confused with a row when the list grows under it. */
private const val HORIZON = "horizon"
