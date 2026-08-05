package de.plmail.feature.calendar

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import de.plmail.core.database.AgendaRow
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
                AgendaScreen(
                    state = state,
                    onBack = {},
                    onRefresh = {},
                    onOpen = {},
                    onNew = {},
                )
            }
        }
    }

    private fun state(days: List<AgendaDay>, status: CalendarStatus = settled()) =
        CalendarState(days = days, calendars = emptyList(), status = status)

    private fun settled() = CalendarStatus(hasSettled = true)

    private fun twoDays() =
        listOf(
            AgendaDay(
                date = LocalDate.parse("2026-08-06"),
                rows =
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
                    ),
            ),
            AgendaDay(
                date = LocalDate.parse("2026-08-09"),
                rows =
                    listOf(
                        row(
                            title = "Zahnarzt",
                            start = "2026-08-09T11:45:00",
                            end = "2026-08-09T12:15:00",
                            calendarName = "Persönlich",
                            color = "#a855f7",
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
        )
}
