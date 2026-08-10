package de.plmail.feature.calendar

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import de.plmail.core.database.AgendaRow
import de.plmail.core.database.CalendarEntity
import de.plmail.core.designsystem.PlMailTheme
import de.plmail.core.designsystem.PlMailThemeChoice
import java.time.LocalDate
import java.util.TimeZone
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the agenda looks like, guarded on every build.
 *
 * Under Robolectric rather than on a device, for the reason `ThreadRowScreenshotTest` gives: a
 * screen's appearance is worth checking on every commit, and a suite that needs an emulator is one
 * that runs on none of them.
 *
 * The cases are the ones with a decision behind them. A day carrying an all-day row *and* a timed
 * one, because all-day sorts first and has a word where the others have a clock. Two calendars in
 * one list, because the dot is the single colour on this screen that is not a design token and the
 * whole point of it is telling two calendars apart. The empty state, the offline banner, and the
 * horizon footer, because each of those is a sentence the product had to choose.
 *
 * **Every case is captured in both schemes.** A colour that was only ever looked at in one of them
 * is exactly how a design system's promise breaks — and the calendar dot, being a literal hex value
 * the *user* picked, is the one mark here that cannot be checked by the palette's contrast sweep.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// sdk = 36 explicitly, as the other screenshot suites do: a library module
// declares no targetSdk and inherits compileSdk 37, which Robolectric has no
// Android to emulate.
@Config(sdk = [36], qualifiers = "w411dp-h891dp-normal-long-notround-any-420dpi")
class AgendaScreenshotTest {

    @get:Rule val compose = createComposeRule()

    @Before
    fun pinTheZone() {
        // The day header and the clock are drawn from stored wall-clock values,
        // so nothing here converts -- but a machine in another zone would still
        // resolve the platform's own formatter differently, and a baseline that
        // only matches where it was recorded is worse than none.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    /** A day with both kinds of row on it, and two calendars to tell apart. */
    @Test
    fun agenda() {
        capture("agenda", state(days = twoDays()))
    }

    @Test
    fun nothingComingUp() {
        capture("empty", state(days = emptyList(), status = settled()))
    }

    /**
     * The rows stay. Offline is a state this app is expected to be usable in, and the calendar on
     * the phone is still a correct account of what it last heard.
     */
    @Test
    fun offline() {
        capture(
            "offline",
            state(days = twoDays(), status = settled().copy(isOffline = true, host = "nas.local")),
        )
    }

    /** The one thing the client cannot promise, said quietly under the last row. */
    @Test
    fun beyondTheHorizon() {
        capture(
            "horizon",
            state(
                days = twoDays(),
                status = settled().copy(mayBeIncomplete = true, horizon = "+2 years"),
            ),
        )
    }

    /**
     * The day grid, with the two things that only exist there.
     *
     * An all-day row in the band above the axis, and two meetings at once drawn side by side —
     * which is the whole of `DayGridLayout` made visible. The now line is on it too, because the
     * state says today is the day being drawn.
     */
    @Test
    fun day() {
        capture("day", state(days = twoDays()).copy(view = CalendarViewMode.DAY))
    }

    /**
     * The week, at the width this repo tests German on.
     *
     * The case the file's own header calls tight and does not solve: seven columns sharing 411dp
     * minus an hour gutter. It is captured precisely so that "tight" stays a known quantity rather
     * than a thing somebody discovers on a device.
     */
    @Test
    fun week() {
        capture("week", state(days = twoDays()).copy(view = CalendarViewMode.WEEK))
    }

    /**
     * The same week for somebody who has turned the system font up.
     *
     * The claim `EventBlock` and `allDayChipHeight` make is that a block divides its own height by
     * a line that grew, so a large-text phone gets **fewer lines, not smaller ones** — a title and
     * no clock where somebody else gets a title and a clock. That is a promise about layout rather
     * than about colour, which is why this is the one case captured in a single scheme: a second
     * copy of it in the dark palette would guard nothing the light one does not.
     */
    @Test
    fun weekAtLargeText() {
        val state = state(days = twoDays()).copy(view = CalendarViewMode.WEEK)

        compose.setContent {
            val density = LocalDensity.current

            CompositionLocalProvider(
                LocalDensity provides Density(density.density, LARGE_FONT_SCALE)
            ) {
                Screen(state, PlMailThemeChoice.LIGHT)
            }
        }

        compose.onRoot().captureRoboImage("src/test/screenshots/agenda-week-large-text-light.png")
    }

    /** The month: six weeks that do not reflow, and cells whose meetings are titled chips. */
    @Test
    fun month() {
        capture("month", state(days = twoDays()).copy(view = CalendarViewMode.MONTH))
    }

    /**
     * The case the chips exist for and the case they cannot cover, in one grid.
     *
     * A day with more meetings than the cell has room for, so the "+n" chip is in the baseline
     * rather than being a thing somebody discovers on a busy Tuesday — and beside it a day with an
     * all-day row, whose chip says the word where the others say a clock. The German titles are the
     * point of capturing it at 411dp: "Quarterly figures" fits where "Vierteljahreszahlen" does
     * not, and the ellipsis is what this view promises to do about that.
     */
    @Test
    fun monthOverflowing() {
        capture("month-full", state(days = busyDay()).copy(view = CalendarViewMode.MONTH))
    }

    /**
     * The mixture: a compact grid of dots over the month's own agenda.
     *
     * Both halves in one frame, because the thing worth guarding is the *split* — a grid that grew
     * to eat the list, or a list squeezed to two rows, is the failure this view has, and neither is
     * visible in a test of either half alone.
     */
    @Test
    fun monthAgenda() {
        capture("month-agenda", state(days = twoDays()).copy(view = CalendarViewMode.MONTH_AGENDA))
    }

    private fun capture(name: String, state: CalendarState) {
        // The scheme is state inside one composition rather than two calls to
        // setContent: the rule allows exactly one per test.
        val scheme = mutableStateOf(PlMailThemeChoice.LIGHT)

        compose.setContent { Screen(state, scheme.value) }

        listOf(PlMailThemeChoice.LIGHT, PlMailThemeChoice.DARK).forEach { choice ->
            scheme.value = choice
            compose.waitForIdle()

            compose
                .onRoot()
                .captureRoboImage(
                    "src/test/screenshots/agenda-$name-${choice.name.lowercase()}.png"
                )
        }
    }

    @Composable
    private fun Screen(state: CalendarState, scheme: PlMailThemeChoice) {
        // reduceMotion, because the alternative is asking a Robolectric
        // ContentResolver for a system setting it has no answer for -- and a
        // screenshot has nothing to animate anyway.
        PlMailTheme(theme = scheme, reduceMotion = true) {
            Surface(modifier = Modifier.fillMaxSize(), color = PlMailTheme.colors.surface) {
                CalendarBoard(
                    state = state,
                    onBack = {},
                    onRefresh = {},
                    onWindowShown = {},
                    onChoose = {},
                    onPage = {},
                    onToday = {},
                    onOpen = {},
                    onOpenDay = {},
                    onNew = {},
                    onCreateAt = {},
                )
            }
        }
    }

    private fun state(days: List<AgendaDay>, status: CalendarStatus = settled()) =
        CalendarState(
            days = days,
            // Named and coloured, because the month's legend is built from
            // these and an empty list would capture a baseline of the one case
            // where it draws nothing. The hidden one is here on purpose: it
            // must not appear in the legend, since none of its events are in
            // the grid either.
            calendars =
                listOf(
                    calendar("Arbeit", "#3b82f6"),
                    calendar("Persönlich", "#a855f7"),
                    calendar("Gesundheit", "#10b981"),
                    calendar("Feiertage", "#f59e0b", isVisible = false),
                ),
            status = status,
            view = CalendarViewMode.AGENDA,
            anchor = LocalDate.parse("2026-08-06"),
            today = LocalDate.parse("2026-08-06"),
            now = java.time.LocalTime.of(10, 30),
        )

    private fun settled() = CalendarStatus(hasSettled = true)

    private fun calendar(name: String, color: String, isVisible: Boolean = true) =
        CalendarEntity(
            uid = "https://nas.local/13#$name",
            accountKey = "https://nas.local/13",
            calendarId = name,
            name = name,
            color = color,
            isVisible = isVisible,
        )

    private fun twoDays() =
        listOf(
            AgendaDay(
                date = LocalDate.parse("2026-08-06"),
                clusters =
                    clusterRows(
                        listOf(
                            // All-day first, as the DAO orders it: these have no
                            // time to sort by, and interleaving them with timed
                            // events by a start of 00:00 puts a festival above an
                            // 08:00 meeting for a reason that reads as a bug.
                            row(
                                title = "Sommerfest der Nachbarschaft",
                                isAllDay = true,
                                calendarName = "Persönlich",
                                color = "#a855f7",
                            ),
                            row(
                                title = "Standup",
                                start = "2026-08-06T09:00:00",
                                end = "2026-08-06T09:15:00",
                                location = "Küche",
                            ),
                            row(
                                title = "Quarterly figures, and everything that came with them",
                                start = "2026-08-06T14:30:00",
                                end = "2026-08-06T16:00:00",
                            ),
                            // One meeting held on two calendars, which the server
                            // deliberately keeps as two rows -- this is the pair
                            // that used to draw twice. Through `clusterRows` so the
                            // baseline is of the collapse rather than of a hand-made
                            // cluster, and the multicolour dot is the visible proof.
                            row(
                                title = "Elternabend",
                                start = "2026-08-06T18:00:00",
                                end = "2026-08-06T19:30:00",
                                uid = "elternabend@plmail",
                            ),
                            row(
                                title = "Elternabend",
                                start = "2026-08-06T18:00:00",
                                end = "2026-08-06T19:30:00",
                                calendarName = "Persönlich",
                                color = "#a855f7",
                                uid = "elternabend@plmail",
                            ),
                        )
                    ),
            ),
            AgendaDay(
                date = LocalDate.parse("2026-08-09"),
                clusters =
                    clusterRows(
                        listOf(
                            row(
                                title = "Zahnarzt",
                                start = "2026-08-09T11:45:00",
                                end = "2026-08-09T12:15:00",
                                calendarName = "Persönlich",
                                color = "#a855f7",
                            )
                        )
                    ),
            ),
        )

    /** One day carrying more than any cell can hold, plus an all-day row on the day after. */
    private fun busyDay() =
        listOf(
            AgendaDay(
                date = LocalDate.parse("2026-08-06"),
                clusters =
                    clusterRows(
                        listOf(
                            row(title = "Standup", start = "2026-08-06T09:00:00"),
                            row(title = "Vierteljahreszahlen", start = "2026-08-06T10:00:00"),
                            row(
                                title = "Elternabend",
                                start = "2026-08-06T11:00:00",
                                calendarName = "Persönlich",
                                color = "#a855f7",
                            ),
                            row(title = "Zahnarzt", start = "2026-08-06T14:00:00"),
                            row(title = "Retrospektive", start = "2026-08-06T16:00:00"),
                        )
                    ),
            ),
            AgendaDay(
                date = LocalDate.parse("2026-08-07"),
                clusters =
                    clusterRows(
                        listOf(
                            row(
                                title = "Sommerfest der Nachbarschaft",
                                isAllDay = true,
                                calendarName = "Persönlich",
                                color = "#a855f7",
                            )
                        )
                    ),
            ),
        )

    private fun row(
        title: String,
        start: String? = null,
        end: String? = null,
        isAllDay: Boolean = false,
        location: String? = null,
        calendarName: String = "Arbeit",
        color: String = "#3b82f6",
        uid: String? = null,
    ) =
        AgendaRow(
            date = start?.substringBefore('T') ?: "2026-08-06",
            startLocal = start,
            endLocal = end,
            zoneId = "Europe/Berlin",
            isAllDay = isAllDay,
            eventKey = "https://nas.local/13#$title",
            eventId = title,
            title = title,
            location = location,
            description = null,
            status = "confirmed",
            isRecurring = false,
            calendarKey = "https://nas.local/13#$calendarName",
            calendarName = calendarName,
            calendarColor = color,
            calendarIsVisible = true,
            // Null unless a case is about the collapse, so nothing merges by
            // accident.
            eventUid = uid,
        )

    private companion object {
        /** Android's "Large" text setting, which is the one a lot of people actually run. */
        const val LARGE_FONT_SCALE = 1.5f
    }
}
