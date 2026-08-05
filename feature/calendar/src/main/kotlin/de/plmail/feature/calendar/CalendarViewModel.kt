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
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
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

/** Rows, calendars and status as one value, so a screen reads one flow. */
data class CalendarState(
    val days: List<AgendaDay> = emptyList(),
    val calendars: List<CalendarEntity> = emptyList(),
    val status: CalendarStatus = CalendarStatus(),
)

/**
 * The agenda: a rolling month from today, out of the cache, refreshed only while somebody is
 * looking.
 *
 * **Never on a timer**, and that is the repository's rule carried up rather than a choice made
 * here. This surface has no delta and no push — the state is the constant `"fixed"` — so refreshing
 * means re-running the whole windowed query, and the audience runs this on a Raspberry Pi with a
 * single PHP worker pool. Opening the calendar and pulling on it are the two moments a person is
 * waiting for an answer; anything else is traffic nobody asked for.
 *
 * The window drawn and the window refreshed are deliberately the same one. `CalendarRepository`
 * offers an unbounded agenda as well, and using it here would draw days beyond the refreshed window
 * out of whatever some earlier window happened to leave in the cache — rows nothing has re-run and
 * nothing will correct.
 */
@HiltViewModel
class CalendarViewModel
@Inject
constructor(
    private val calendar: CalendarRepository,
    private val clock: Clock,
    connectivity: Connectivity,
    accounts: AccountsRepository,
) : ViewModel() {

    /**
     * The span on screen, and the span asked for.
     *
     * Recomputed on every refresh rather than fixed at construction, so an app left open overnight
     * moves to the new today when it is next pulled instead of drawing yesterday for ever.
     */
    private val window = MutableStateFlow(rollingMonth())

    private val status = MutableStateFlow(CalendarStatus())

    /** Where the list starts, so the Today control knows what it is scrolling back to. */
    val today: LocalDate
        get() = window.value.from

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<CalendarState> =
        combine(
                window.flatMapLatest { calendar.occurrences(it) },
                calendar.calendars(),
                status,
                connectivity.isOnline,
                accounts.serverHost,
            ) { rows, calendars, refreshStatus, online, host ->
                CalendarState(
                    days = groupByDay(rows),
                    calendars = calendars,
                    // The host comes from the stored connection rather than from a
                    // failure, for the reason the mail list's does: a phone with no
                    // network never makes a failed request, so there is nothing to
                    // read a hostname out of at the moment one is needed.
                    status = refreshStatus.copy(isOffline = !online, host = host),
                )
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = CalendarState(),
            )

    /**
     * Re-runs the visible window against the server.
     *
     * Guarded rather than restarted: a second pull while the first is in flight would ask a machine
     * that is already busy for the same month again, and up to thirty-one day probes with it.
     */
    fun refresh() {
        if (status.value.isRefreshing) return

        status.update { it.copy(isRefreshing = true) }
        window.value = rollingMonth()

        viewModelScope.launch {
            val outcome = calendar.refresh(window.value)

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
                            // Otherwise the stored address stands, which is what
                            // the combine above keeps current.
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
                    // Not an error and not worth a banner: an instance without
                    // the vendor extension is a supported instance. The entry
                    // into this screen is hidden in that case anyway, so this is
                    // the state of a server that lost its calendar between the
                    // drawer being drawn and the screen being opened.
                    CalendarRefresh.NoCalendarAccount ->
                        it.copy(isRefreshing = false, hasSettled = true)
                }
            }
        }
    }

    /** Today plus the month the web's agenda covers. */
    private fun rollingMonth(): CalendarWindow {
        val from = LocalDate.now(clock)

        return CalendarWindow(from = from, to = from.plusDays(AGENDA_DAYS))
    }

    private companion object {
        /**
         * Thirty days, which is what the web's agenda covers.
         *
         * Matching it rather than choosing a number: the two surfaces answering "what is coming up"
         * differently is the kind of disagreement a user reads as one of them being broken.
         */
        const val AGENDA_DAYS = 30L

        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
