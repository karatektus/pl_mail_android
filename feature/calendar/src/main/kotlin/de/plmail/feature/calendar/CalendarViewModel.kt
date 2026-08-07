package de.plmail.feature.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.plmail.core.data.AccountsRepository
import de.plmail.core.data.CalendarRefresh
import de.plmail.core.data.CalendarRepository
import de.plmail.core.data.CalendarWindow
import de.plmail.core.data.Connectivity
import de.plmail.core.database.CalendarEntity
import de.plmail.core.datastore.CalendarPrefsStore
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.WeekFields
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the last refresh left behind.
 *
 * Held apart from the rows, because the rows are the cache and survive every one of these: a
 * calendar that could not be reached still has a month on the phone, and blanking it would be
 * throwing away the only thing that is still true.
 */
data class CalendarStatus(
    val isRefreshing: Boolean = false,
    /** The device has no network. A different sentence from the server not answering. */
    val isOffline: Boolean = false,
    /** The server as stored, so a banner can name the machine somebody has to go and look at. */
    val host: String? = null,
    /** Nothing answered the last refresh. */
    val isUnreachable: Boolean = false,
    /** The server answered, and the answer was no. Its own words. */
    val refusal: String? = null,
    /** See [CalendarRefresh.Refreshed.mayBeIncomplete] — "there may be more", never "there is". */
    val mayBeIncomplete: Boolean = false,
    /** The server's own words for how far it materialises. Opaque: display, never parse. */
    val horizon: String? = null,
    /** Whether a refresh has ever finished, so "nothing on" can be told from "not asked yet". */
    val hasSettled: Boolean = false,
)

/** Rows, calendars, where the user is and status as one value, so a screen reads one flow. */
data class CalendarState(
    /** The days something is on, in order. The agenda's list, and the month's source. */
    val days: List<AgendaDay> = emptyList(),
    val calendars: List<CalendarEntity> = emptyList(),
    val status: CalendarStatus = CalendarStatus(),
    val view: CalendarViewMode = CalendarViewMode.Default,
    /** The day the current view is centred on. Today, until the user pages away. */
    val anchor: LocalDate = UNSET_DAY,
    val today: LocalDate = UNSET_DAY,
    /** The device's clock at the moment this state was built. What draws the now line. */
    val now: LocalTime = LocalTime.MIDNIGHT,
    val firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
) {
    /** The span on screen. One window, one request — see [CalendarViewMode.window]. */
    val window: CalendarWindow
        get() = view.window(anchor, firstDayOfWeek)

    /**
     * Every day the current view draws, empty ones included, each with its clusters.
     *
     * A grid draws its empty cells — that is the difference between a grid and an agenda — so the
     * columns come from the window rather than from what the query answered.
     */
    fun columns(): List<MonthDay> {
        val byDate = days.associateBy { it.date }

        return window.days().map { MonthDay(it, byDate[it]?.clusters.orEmpty()) }
    }

    /** The same days, laid out on an hour axis. */
    fun grids(): List<DayGrid> = columns().map { placeDay(it.date, it.clusters) }

    private companion object {
        /**
         * What the anchor is before the clock has been read, which is the first frame and nothing
         * else.
         *
         * A real date rather than `LocalDate.MIN`, because this value is briefly *drawn*: a grid
         * headed "1 January -999999999" for one frame is a rendering fault a user would report,
         * while an epoch date reads as a calendar that has not loaded yet. It is never persisted
         * and never sent — the first emission of `state` replaces it with `LocalDate.now(clock)`.
         *
         * Not `LocalDate.EPOCH`, which is API 34 against a minSdk of 31.
         */
        val UNSET_DAY: LocalDate = LocalDate.of(1970, 1, 1)
    }
}

/**
 * The calendar: one window out of the cache, refreshed only while somebody is looking.
 *
 * **Never on a timer**, and that is the repository's rule carried up rather than a choice made
 * here. This surface has no delta and no push — the state is the constant `"fixed"` — so refreshing
 * means re-running the whole windowed query, and the audience runs this on a Raspberry Pi with a
 * single PHP worker pool. Opening the calendar, changing what is on screen, and pulling on it are
 * the moments a person is waiting for an answer; anything else is traffic nobody asked for.
 *
 * **The window drawn and the window refreshed are the same one**, in every view.
 * `CalendarRepository` offers an unbounded agenda as well, and using it here would draw days beyond
 * the refreshed window out of whatever some earlier window happened to leave in the cache — rows
 * nothing has re-run and nothing will correct.
 *
 * **A view change or a page is one window and therefore one request**, whatever recurs in it. That
 * is the whole return on the `expandRecurrences` adoption and it is why the week is a seven-day
 * window rather than seven day-windows: the difference on a domestic uplink is one round trip
 * against seven.
 */
@HiltViewModel
class CalendarViewModel
@Inject
constructor(
    private val calendar: CalendarRepository,
    private val clock: Clock,
    private val prefs: CalendarPrefsStore,
    connectivity: Connectivity,
    accounts: AccountsRepository,
) : ViewModel() {

    /**
     * Where the week starts, from the device's locale.
     *
     * The **platform's** answer rather than the web's hardcoded Monday, and the distinction is
     * worth being explicit about: this is presentation, not a claim about when a meeting is. A
     * phone set to en-US draws Sunday-first because that is what every other calendar on that phone
     * does, and no event moves as a result — which is the only kind of disagreement between the two
     * surfaces this project refuses.
     */
    private val firstDayOfWeek: DayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek

    /**
     * The chosen view, and where it is anchored.
     *
     * One flow rather than two, because a change to either is a change of window and the refresh
     * has to see them together — separate flows would refresh the old view's window against the new
     * anchor for one frame, which is one wasted round trip per tap on a machine that has four.
     */
    private val place = MutableStateFlow(Place(CalendarViewMode.Default, LocalDate.now(clock)))

    private val status = MutableStateFlow(CalendarStatus())

    /** What is being refreshed, so a second request for the same window costs nothing. */
    private var refreshing: CalendarWindow? = null

    /**
     * The last window a refresh actually completed for.
     *
     * What stops a cold open costing two round trips. The screen asks for a refresh whenever the
     * window on it changes, and on the first frame that window is the placeholder one — so the ask
     * arrives twice in quick succession for what is, by the time the state has resolved, the same
     * span. Only the most recent window is remembered rather than a set: paging away and back
     * should re-ask, because there is no delta on this surface and the cache is only as current as
     * its last answer.
     */
    private var refreshed: CalendarWindow? = null

    private var refreshJob: Job? = null

    /** Whether the stored choice has been read. Until it has, nothing is written back. */
    private var restored = false

    init {
        // The stored view, applied once. Collected rather than read once because
        // DataStore's first emission is a file read: a `first()` here would make
        // the ViewModel's construction suspend, and the screen would have
        // nothing to draw while it did.
        viewModelScope.launch {
            val stored = CalendarViewMode.fromWire(prefs.view.first())

            restored = true
            place.update { it.copy(view = stored) }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<CalendarState> =
        combine(
                place
                    .map { it.window(firstDayOfWeek) }
                    .distinctUntilChanged()
                    .flatMapLatest { calendar.occurrences(it) },
                calendar.calendars(),
                status,
                connectivity.isOnline,
                combine(accounts.serverHost, place) { host, at -> host to at },
            ) { rows, calendars, refreshStatus, online, (host, at) ->
                CalendarState(
                    days = groupByDay(rows),
                    calendars = calendars,
                    // The host comes from the stored connection rather than from a
                    // failure, for the reason the mail list's does: a phone with no
                    // network never makes a failed request, so there is nothing to
                    // read a hostname out of at the moment one is needed.
                    status = refreshStatus.copy(isOffline = !online, host = host),
                    view = at.view,
                    anchor = at.anchor,
                    today = LocalDate.now(clock),
                    now = LocalTime.now(clock),
                    firstDayOfWeek = firstDayOfWeek,
                )
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = CalendarState(firstDayOfWeek = firstDayOfWeek),
            )

    /**
     * Chooses a view, and remembers it.
     *
     * The anchor is **kept**, so switching from a week to a month lands on the month containing the
     * week that was on screen rather than snapping back to today — which is what every calendar on
     * every platform does, and the only behaviour that makes the switcher usable for navigating.
     * The one exception is the agenda, which is a rolling list from its own first day: an agenda
     * anchored on a fortnight ago would open on a fortnight ago, and its Today control is a scroll
     * rather than a jump.
     */
    fun choose(view: CalendarViewMode) {
        if (place.value.view == view) return

        place.update {
            Place(
                view = view,
                anchor = if (view == CalendarViewMode.AGENDA) LocalDate.now(clock) else it.anchor,
            )
        }

        // Never before the stored value has been read: an early write would
        // persist the default over whatever the user chose last time, which is
        // the one thing a restore must not race.
        if (restored) viewModelScope.launch { prefs.setView(view.wire) }
    }

    /** One step forward or back, in whatever unit the current view steps by. */
    fun page(forward: Boolean) {
        place.update { it.copy(anchor = it.view.step(it.anchor, forward)) }
    }

    /**
     * Back to today, in the view the user is in.
     *
     * That is the whole difference from what the Today control used to do: it scrolled an agenda to
     * its top and could not do anything else, because there was nothing else to be in. It now means
     * the same thing in four views — "show me now" — and the agenda's case is still a scroll, which
     * the screen does, because a rolling list anchored on today is already there.
     */
    fun goToToday() {
        place.update { it.copy(anchor = LocalDate.now(clock)) }
    }

    /** Opens a day, which is what a month cell's tap means. */
    fun openDay(date: LocalDate) {
        place.value = Place(view = CalendarViewMode.DAY, anchor = date)

        if (restored) viewModelScope.launch { prefs.setView(CalendarViewMode.DAY.wire) }
    }

    /**
     * Asks the server about the visible window if it has not already been asked.
     *
     * What the screen calls when the window changes — opening the calendar, switching view, paging.
     * Distinct from [refresh], which is what a **pull** calls: a pull means "ask again", and this
     * means "make sure you have asked". Collapsing the two would either cost two round trips on
     * every cold open — the first frame carries a placeholder window, so the screen's effect fires
     * twice for what turns out to be one span — or make pull-to-refresh do nothing at all on the
     * window already on screen.
     */
    fun refreshIfNeeded() {
        if (refreshed == place.value.window(firstDayOfWeek)) return

        refresh()
    }

    /**
     * Re-runs the visible window against the server.
     *
     * Guarded on the **window** rather than on "a refresh is happening": the old guard dropped the
     * refresh a page or a view change asks for, because one was already in flight for the window
     * the user has just left. A second ask for the *same* window is still refused — that is a pull
     * on a machine already answering the question.
     */
    fun refresh() {
        val window = place.value.window(firstDayOfWeek)

        if (refreshing == window) return

        refreshing = window
        refreshJob?.cancel()
        status.update { it.copy(isRefreshing = true) }

        refreshJob = viewModelScope.launch {
            val outcome = calendar.refresh(window)

            refreshing = null
            // Recorded whatever the outcome, including a refusal and an
            // unreachable server. "Has it been asked" is the question this
            // answers, and re-asking a machine that is off the network on every
            // recomposition is the loop this whole class is written to avoid --
            // the pull gesture is what a user who wants another attempt has, and
            // the banner is what tells them to use it.
            refreshed = window
            status.update {
                when (outcome) {
                    is CalendarRefresh.Refreshed ->
                        it.copy(
                            isRefreshing = false,
                            isUnreachable = false,
                            refusal = null,
                            mayBeIncomplete = outcome.mayBeIncomplete,
                            horizon = outcome.horizon.future.takeIf { words -> words.isNotBlank() },
                            hasSettled = true,
                        )
                    is CalendarRefresh.Unreachable ->
                        it.copy(
                            isRefreshing = false,
                            isUnreachable = true,
                            refusal = null,
                            // The host the transport knew, where it knew one.
                            // Otherwise the stored address stands, which is
                            // what the combine above keeps current.
                            host = outcome.host ?: it.host,
                            hasSettled = true,
                        )
                    is CalendarRefresh.Rejected ->
                        it.copy(
                            isRefreshing = false,
                            isUnreachable = false,
                            refusal = outcome.reason,
                            hasSettled = true,
                        )
                    // Not an error and not worth a banner: an instance
                    // without the vendor extension is a supported instance.
                    // The entry into this screen is hidden in that case
                    // anyway, so this is the state of a server that lost its
                    // calendar between the drawer being drawn and the screen
                    // being opened.
                    CalendarRefresh.NoCalendarAccount ->
                        it.copy(isRefreshing = false, hasSettled = true)
                }
            }
        }
    }

    /** Which view, anchored where. */
    private data class Place(val view: CalendarViewMode, val anchor: LocalDate) {
        fun window(firstDayOfWeek: DayOfWeek): CalendarWindow = view.window(anchor, firstDayOfWeek)
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
