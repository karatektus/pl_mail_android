package de.plmail.feature.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.plmail.core.database.AgendaRow
import de.plmail.core.designsystem.PaneTone
import de.plmail.core.designsystem.PlMailBanner
import de.plmail.core.designsystem.PlMailDivider
import de.plmail.core.designsystem.PlMailEmptyState
import de.plmail.core.designsystem.PlMailTheme
import kotlinx.coroutines.launch

/**
 * What is coming up, as a rolling list from today.
 *
 * The web's agenda, on a phone: thirty days, grouped by day, with the empty days left out — which
 * is the whole difference between an agenda and a month grid. There is no Previous and no Next,
 * unlike the web's toolbar, because those step by a day through a *rolling* list and scrolling
 * already does that here.
 *
 * The rows are the cache, and they stay on screen through every failure below. A calendar that
 * could not be refreshed is still a correct account of what the phone last heard, and blanking it
 * would throw away the only thing that is still true.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgendaScreen(
    state: CalendarState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpen: (AgendaRow) -> Unit,
    onNew: () -> Unit,
) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // On open, and never on a timer. This surface has no delta and no push, so a
    // refresh is the whole windowed query again -- against a machine with one
    // PHP worker pool. The two moments that justify it are somebody opening the
    // calendar and somebody pulling on it.
    LaunchedEffect(Unit) { onRefresh() }

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
                    // Today is the top of this list rather than a date to
                    // navigate to: the window starts there, so the control has
                    // one thing to do and does it without a round trip.
                    IconButton(onClick = { scope.launch { list.animateScrollToItem(0) } }) {
                        Icon(
                            imageVector = Icons.Outlined.Today,
                            contentDescription = stringResource(R.string.calendar_today),
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
            Banners(state.status)

            PullToRefreshBox(
                isRefreshing = state.status.isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                if (state.days.isEmpty()) {
                    // "Nothing coming up" and "not asked yet" are different
                    // answers, and showing the first while the first refresh is
                    // in flight tells somebody their month is empty when it is
                    // not.
                    if (state.status.hasSettled) {
                        PlMailEmptyState(
                            icon = Icons.Outlined.CalendarMonth,
                            title = stringResource(R.string.calendar_empty),
                            body = stringResource(R.string.calendar_empty_body),
                        )
                    } else {
                        Loading()
                    }

                    return@PullToRefreshBox
                }

                // Where a row's title starts, computed rather than written
                // down: the gutter and the gaps either side of the dot are
                // density-scaled tokens, so a constant would line the hairline
                // up with the title in one density and nowhere else.
                val textInset =
                    PlMailTheme.spacing.gutter +
                        TIME_COLUMN +
                        PlMailTheme.spacing.medium +
                        DOT +
                        PlMailTheme.spacing.medium

                LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                    state.days.forEach { day ->
                        item(key = day.date.toString()) { DayHeader(day) }

                        day.rows.forEachIndexed { index, row ->
                            item(key = "${day.date}/${row.eventKey}/$index") {
                                EventRow(row = row, onClick = { onOpen(row) })

                                // Between rows of one day, never after the last:
                                // the day header below is already the separator,
                                // and a hairline above it makes the header look
                                // like a row of the day before.
                                if (index < day.rows.lastIndex) {
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
        }
    }
}

/**
 * What the last refresh could not do, above the list rather than instead of it.
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
                ),
    )
}

/**
 * One occurrence.
 *
 * The dot is the calendar's own hex colour and is the single thing on this screen that does not
 * come from a token — see [calendarColor]. Everything else is the ink scale doing what it is for:
 * the title is what the event *is*, the time and the place are what it is about.
 *
 * The whole row carries one sentence for TalkBack rather than four stops, because four stops per
 * event is a screenful of fragments — "09:00", "Standup", "Kitchen", "Work" — that have to be
 * reassembled by the listener.
 */
@Composable
private fun EventRow(row: AgendaRow, onClick: () -> Unit) {
    val theme = PlMailTheme.values
    val allDay = stringResource(R.string.calendar_all_day)
    val time = startTimeOf(row)?.format(CLOCK) ?: allDay

    // Read out of composition. A semantics block is not a composable scope and
    // cannot reach a string resource from inside itself.
    val sentence =
        row.location
            ?.takeIf { it.isNotBlank() }
            ?.let {
                stringResource(
                    R.string.calendar_row_a11y_located,
                    time,
                    row.title,
                    it,
                    row.calendarName.orEmpty(),
                )
            }
            ?: stringResource(
                R.string.calendar_row_a11y,
                time,
                row.title,
                row.calendarName.orEmpty(),
            )

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

        Dot(color = calendarColor(row.calendarColor) ?: theme.colors.inkFaint)

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

/** The calendar's colour, as the smallest mark that can carry one. */
@Composable
private fun Dot(color: Color) {
    Box(modifier = Modifier.size(DOT).background(color = color, shape = CircleShape))
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
 * How wide the time column is.
 *
 * Sized for the German "Ganztägig" rather than for "09:00", because the word that has to fit is the
 * longest one, and a column that fits the times and ellipsises the word says nothing at all on the
 * rows that carry it.
 */
private val TIME_COLUMN = 72.dp

/** The colour dot. Small: it identifies a calendar, it is not a second accent. */
private val DOT = 10.dp

/** Keyed, so the footer is not confused with a row when the list grows under it. */
private const val HORIZON = "horizon"
