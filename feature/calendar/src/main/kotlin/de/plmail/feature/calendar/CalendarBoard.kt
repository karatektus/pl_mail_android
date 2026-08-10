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
import androidx.compose.ui.unit.sp
import de.plmail.core.designsystem.PaneTone
import de.plmail.core.designsystem.PlMailBanner
import de.plmail.core.designsystem.PlMailDivider
import de.plmail.core.designsystem.PlMailEmptyState
import de.plmail.core.designsystem.PlMailTheme
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
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
                        AgendaList(
                            days = state.days,
                            status = state.status,
                            today = state.today,
                            formats = formats,
                            list = list,
                            onOpen = onOpen,
                        )
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
                        // The legend is inside the swipe rather than beside it:
                        // a strip along the bottom of a paged view that does
                        // not page with it is a dead zone exactly where a thumb
                        // rests.
                        Column(modifier = Modifier.fillMaxSize().pageOnSwipe(onPage)) {
                            MonthGrid(
                                days = remember(state) { state.columns() },
                                today = state.today,
                                anchorMonth = state.anchor.monthValue,
                                weekday = formats.weekday,
                                onOpenDay = onOpenDay,
                                onCreateAt = onCreateAt,
                                modifier = Modifier.weight(1f),
                            )

                            MonthLegend(state.calendars)
                        }
                    CalendarViewMode.MONTH_AGENDA ->
                        MonthAgendaPane(
                            state = state,
                            onOpen = onOpen,
                            onCreateAt = onCreateAt,
                            onPage = onPage,
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
internal fun Modifier.pageOnSwipe(onPage: (Boolean) -> Unit): Modifier {
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
 * What is coming up, as a list of days with the empty ones left out.
 *
 * The web's agenda, on a phone: thirty days, grouped by day, with the empty days left out — which
 * is the whole difference between an agenda and a month grid.
 *
 * **Handed its days rather than reading them off the state**, because the mixture view draws this
 * same list under a compact grid over a *month's* window. One list composable for both, so the day
 * header, the hairline inset, the horizon footer and the "not asked yet" case cannot come to differ
 * between the two places an agenda appears. The window it is showing is the caller's business; the
 * only thing that changes with it is [emptyBody], because "the next 30 days are clear" is a
 * sentence about the agenda's window and would be a small lie under a month.
 *
 * The rows are the cache, and they stay on screen through every failure above. A calendar that
 * could not be refreshed is still a correct account of what the phone last heard, and blanking it
 * would throw away the only thing that is still true.
 */
@Composable
internal fun AgendaList(
    days: List<AgendaDay>,
    status: CalendarStatus,
    today: LocalDate,
    formats: CalendarFormats,
    list: LazyListState,
    onOpen: (EventCluster) -> Unit,
    modifier: Modifier = Modifier,
    emptyBody: String = stringResource(R.string.calendar_empty_body),
) {
    if (days.isEmpty()) {
        // "Nothing coming up" and "not asked yet" are different answers, and
        // showing the first while the first refresh is in flight tells somebody
        // their month is empty when it is not.
        if (status.hasSettled) {
            PlMailEmptyState(
                icon = Icons.Outlined.CalendarMonth,
                title = stringResource(R.string.calendar_empty),
                body = emptyBody,
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

    // One item per day header and one per cluster, in that order, which is the
    // shape `agendaAnchorIndex` counts to place a selected day. The two have to
    // stay in step: an item added here and not counted there scrolls the
    // mixture view to the wrong row.
    LazyColumn(state = list, modifier = modifier.fillMaxSize()) {
        days.forEach { day ->
            item(key = day.date.toString()) { DayHeader(day, today, formats) }

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

        if (status.mayBeIncomplete) {
            item(key = HORIZON) { HorizonNote(status.horizon) }
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

/**
 * Which day the rows under it are on: the day named, then dated.
 *
 * **"Today" and "Tomorrow" in place of the weekday, and in the accent**, because those are the two
 * days anybody opening a calendar is looking for and counting rows to find Thursday is what the
 * header exists to prevent. The date stays beside the word rather than being replaced by it:
 * "Today" alone is a header that stops being true if the phone is left open overnight, and it is
 * the one line on the screen that has to survive being read at two in the morning.
 *
 * The weekday is upper-cased against the **device's** locale, not the root one — `uppercase()` with
 * no argument mangles a Turkish dotted i, which is the bug that ships because nobody testing in
 * German or English can see it.
 */
@Composable
private fun DayHeader(day: AgendaDay, today: LocalDate, formats: CalendarFormats) {
    val theme = PlMailTheme.values
    val isToday = day.date == today
    val isTomorrow = day.date == today.plusDays(1)

    val word =
        when {
            isToday -> stringResource(R.string.calendar_today)
            isTomorrow -> stringResource(R.string.calendar_tomorrow)
            else -> day.date.format(formats.weekdayFull)
        }

    Row(
        modifier =
            Modifier.fillMaxWidth()
                .padding(
                    start = theme.spacing.gutter,
                    end = theme.spacing.gutter,
                    top = theme.spacing.large,
                    bottom = theme.spacing.small,
                )
                // One heading, read as one phrase. Two Texts is two stops for a
                // listener walking the rotor through a month of days.
                .semantics(mergeDescendants = true) { heading() },
        horizontalArrangement = Arrangement.spacedBy(theme.spacing.small),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = word.uppercase(Locale.getDefault()),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = HEADER_TRACKING,
            color = if (isToday) theme.colors.accent else theme.colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Text(
            text = day.date.format(formats.date),
            style = MaterialTheme.typography.bodySmall,
            color = theme.colors.inkFaint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Small caps want their letters opened out, or they read as one word. */
private val HEADER_TRACKING = 0.06.sp

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
    val start = startTimeOf(row)?.format(CLOCK)
    val end = endTimeOf(row)?.format(CLOCK)

    // The span rather than the start alone: "9:00" says when to be somewhere
    // and a list of them says nothing about whether the afternoon is free,
    // which is the question an agenda is read to answer. A zero-length event --
    // a real thing on this server -- keeps its single time rather than being
    // drawn as a range from a moment to itself.
    val time =
        when {
            start == null -> allDay
            end == null || end == start -> start
            else -> stringResource(R.string.calendar_time_range, start, end)
        }

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
            style = MaterialTheme.typography.bodySmall,
            color = theme.colors.inkMuted,
            // Two lines, because a range is two clocks and a 12-hour locale
            // spells both of them with a meridiem: "8:00 AM – 9:30 AM" does not
            // fit this column on one line and must not be ellipsised into a
            // half-truth about when something ends.
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
                fontWeight = FontWeight.Medium,
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
