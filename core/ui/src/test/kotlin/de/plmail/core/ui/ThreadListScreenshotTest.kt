package de.plmail.core.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import de.plmail.core.database.ThreadEntity
import de.plmail.core.designsystem.PlMailDivider
import de.plmail.core.designsystem.PlMailTheme
import de.plmail.core.designsystem.PlMailThemeChoice
import java.time.ZonedDateTime
import java.util.TimeZone
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A screenful of rows, which is the only scale some decisions are visible at.
 *
 * `ThreadRowScreenshotTest` guards one row at a time and is the right tool for weight, ellipsis and
 * the affordance column. It cannot see the question this file exists for: **how often a colour
 * appears**. The accent in this palette is deliberately rationed — an active navigation item, a
 * link, the compose button's glyph — and a mark that is correct once is a mark that can still be
 * wrong fourteen times. An unread dot was removed from the row for exactly that reason, and the
 * accent date it left behind inherited the same risk rather than being freed of it.
 *
 * So the two cases here are the two densities of unread that actually occur:
 *
 * - **[aFreshlySeededInbox]** — everything unread. A new account, a first sync, or a morning after
 *   a holiday. This is where any per-unread-row mark is at its loudest.
 * - **[anOrdinaryMorning]** — a few unread among mail already read, which is the steady state.
 *
 * Both in both schemes, so a decision made about light cannot quietly break dark.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// See ThreadRowScreenshotTest for why the sdk is pinned to 36 rather than
// inherited: a library module has no targetSdk, so it would otherwise ask
// Robolectric for an Android 37 that does not exist.
@Config(sdk = [36], qualifiers = "w411dp-h891dp-normal-long-notround-any-420dpi")
class ThreadListScreenshotTest {

    @get:Rule val compose = createComposeRule()

    @Before
    fun pinTheZone() {
        // The date column renders in the default zone, so without this the
        // checked-in image only matches on the machine that recorded it.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @Test
    fun aFreshlySeededInbox() {
        capture("all-unread", inbox.map { it.copy(isUnread = true) })
    }

    @Test
    fun anOrdinaryMorning() {
        capture("mixed", inbox)
    }

    /**
     * Fourteen conversations, which is roughly a phone screen and a bit.
     *
     * Deliberately varied rather than generated: real senders differ in name length, real subjects
     * are sometimes short, and the avatar ramp only shows itself as a ramp when the addresses
     * hashing into it are genuinely different. A list of "Sender 1..14" hides all three.
     */
    private val inbox =
        listOf(
            thread(
                "Ada Lovelace",
                "ada@analyticalengine.org",
                "The quarterly figures",
                "Attached is the full breakdown, and a note about next quarter.",
                daysAgo = 0,
                isUnread = true,
                hasAttachment = true,
            ),
            thread(
                "Deutsche Bahn",
                "noreply@bahn.de",
                "Ihre Fahrkarte für den 14. August",
                "Guten Tag, anbei finden Sie Ihre Buchungsbestätigung.",
                daysAgo = 0,
                isUnread = true,
            ),
            thread(
                "Grace Hopper, Alan Turing",
                "grace@navy.mil",
                "Re: the compiler question",
                "I think we are talking past each other here. Let me try again.",
                daysAgo = 0,
                messageCount = 6,
            ),
            thread(
                "Katherine Johnson",
                "kjohnson@nasa.gov",
                "Trajectory review, Thursday",
                "Can we move it half an hour later? I have a conflict at two.",
                daysAgo = 1,
                isUnread = true,
                isFlagged = true,
            ),
            thread(
                "Hausverwaltung Meier",
                "verwaltung@meier-immobilien.de",
                "Nebenkostenabrechnung 2025",
                "Sehr geehrte Mieterinnen und Mieter, anbei die Abrechnung.",
                daysAgo = 1,
                hasAttachment = true,
            ),
            thread(
                "Margaret Hamilton",
                "mh@mit.edu",
                "Priority display, again",
                "It happened on the descent. I have the logs if you want them.",
                daysAgo = 1,
            ),
            thread(
                "GitHub",
                "notifications@github.com",
                "[plmail] CI failed on main",
                "The build failed for commit a7c7edc. See the run for details.",
                daysAgo = 2,
                isUnread = true,
            ),
            thread(
                "Barbara Liskov",
                "liskov@csail.mit.edu",
                "Substitution, and the paper",
                "Happy to read a draft. Send it whenever it is ready.",
                daysAgo = 2,
                messageCount = 3,
            ),
            thread(
                "Radu",
                "radu@example.com",
                null,
                "sent from my phone",
                daysAgo = 3,
            ),
            thread(
                "Sophie Wilson",
                "sophie@acorn.co.uk",
                "Re: Re: Fwd: the instruction set and everything that came with it",
                "Attached is the full breakdown, plus the appendix nobody asked for.",
                daysAgo = 4,
                hasAttachment = true,
                isFlagged = true,
            ),
            thread(
                "Finanzamt München",
                "kein-antwort@finanzamt.bayern.de",
                "Steuerbescheid 2024",
                "Ihr Bescheid steht zum Abruf bereit.",
                daysAgo = 6,
                isUnread = true,
            ),
            thread(
                "Karen Spärck Jones",
                "ksj@cl.cam.ac.uk",
                "Weighting",
                "The rare term matters more. That is the whole idea.",
                daysAgo = 9,
            ),
            thread(
                "Stadtwerke",
                "service@stadtwerke.example",
                "Ihre Jahresabrechnung",
                "Sie haben ein Guthaben von 84,20 EUR.",
                daysAgo = 21,
            ),
            thread(
                "Frances Allen",
                "fallen@ibm.com",
                "Optimising compilers reading group",
                "Next Tuesday, same room. Bring the Fortran examples.",
                daysAgo = 40,
                messageCount = 12,
            ),
        )

    /**
     * Which rows carry chips, by index into [inbox].
     *
     * A few rather than all of them, because the question this file exists to answer is *how often*
     * a mark appears — and a list where every row is labelled is a list nobody has, while a list
     * where none is answers nothing. Four of fourteen is roughly what a mailbox with a handful of
     * rules looks like, and it includes one row that overflows the cap.
     */
    private val labelled =
        mapOf(
            0 to (listOf("Arbeit") to 0),
            4 to (listOf("Wohnung") to 0),
            7 to (listOf("Arbeit", "Lesen") to 0),
            // One name and a counter, because that is what `rowLabels` hands the
            // row once a conversation carries more labels than fit: the counter
            // takes one of the two chip slots rather than sitting after them.
            10 to (listOf("Steuer") to 3),
        )

    private fun capture(name: String, threads: List<ThreadEntity>) {
        val scheme = mutableStateOf(PlMailThemeChoice.LIGHT)

        compose.setContent { List(threads, scheme.value) }

        listOf(PlMailThemeChoice.LIGHT, PlMailThemeChoice.DARK).forEach { choice ->
            scheme.value = choice
            compose.waitForIdle()

            compose
                .onRoot()
                .captureRoboImage(
                    "src/test/screenshots/thread-list-$name-${choice.name.lowercase()}.png"
                )
        }
    }

    @Composable
    private fun List(threads: kotlin.collections.List<ThreadEntity>, scheme: PlMailThemeChoice) {
        PlMailTheme(theme = scheme, reduceMotion = true) {
            Surface(modifier = Modifier.width(411.dp), color = PlMailTheme.colors.surface) {
                // A plain Column rather than a LazyColumn: everything has to be
                // composed for the capture, and a lazy list would render only
                // what fits the viewport — which is the opposite of the point.
                Column {
                    threads.forEachIndexed { index, thread ->
                        // Dividers between rows only, matching the list itself:
                        // a hairline under the last row promises another one.
                        if (index > 0) PlMailDivider(startIndent = 72.dp)

                        val chips = labelled[index]

                        ThreadRow(
                            thread = thread,
                            onClick = {},
                            today = NOW.toLocalDate(),
                            labels = chips?.first.orEmpty(),
                            hiddenLabels = chips?.second ?: 0,
                        )
                    }
                }
            }
        }
    }

    private fun thread(
        participants: String,
        address: String,
        subject: String?,
        snippet: String,
        daysAgo: Long,
        isUnread: Boolean = false,
        isFlagged: Boolean = false,
        hasAttachment: Boolean = false,
        messageCount: Int = 1,
    ): ThreadEntity =
        ThreadEntity(
            uid = "https://nas.local/13#$address",
            accountKey = "https://nas.local/13",
            threadId = address,
            // Relative to a fixed "now" rather than the real clock, so the date
            // column exercises all three of its branches — time, weekday, date —
            // without the baseline changing overnight.
            latestReceivedAt =
                NOW.minusDays(daysAgo).minusHours(daysAgo * 2).toInstant().toEpochMilli(),
            subject = subject,
            participantsSummary = participants,
            participantsAddress = address,
            snippet = snippet,
            messageCount = messageCount,
            isUnread = isUnread,
            isFlagged = isFlagged,
            hasAttachment = hasAttachment,
        )

    private companion object {
        val NOW: ZonedDateTime = ZonedDateTime.parse("2026-08-01T11:40:00Z")
    }
}
