package de.plmail.feature.mail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.github.takahirom.roborazzi.captureRoboImage
import de.plmail.core.data.CategoryArrivals
import de.plmail.core.data.Label
import de.plmail.core.data.LabelBinding
import de.plmail.core.data.MailCategory
import de.plmail.core.data.MailView
import de.plmail.core.data.SidebarSections
import de.plmail.core.database.ThreadEntity
import de.plmail.core.designsystem.PlMailDensity
import de.plmail.core.designsystem.PlMailTheme
import de.plmail.core.designsystem.PlMailThemeChoice
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.TimeZone
import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the navigation actually looks like, guarded on every build.
 *
 * The three things this suite exists to make visible are the three that were reported as missing
 * and could not be checked by reading a diff: the new-mail dots, the category bundles above
 * Primary, and a drawer that has groups in it rather than one flat column.
 *
 * **The density cases are the point of the fourth test.** "The sidebar shows less than Gmail's at
 * the same setting" is a claim about a number, and the number was Material's —
 * `NavigationDrawerItem` is a fixed 56dp whatever any density says, so the setting moved the gaps
 * between rows and nothing else. Rendering all three side by side is what makes a regression there
 * obvious rather than arguable.
 *
 * Under Robolectric rather than on a device, for the reason `ThreadRowScreenshotTest` gives: a
 * screenshot suite that needs an emulator is one that runs on no build at all.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// sdk = 36 for the reason the other Robolectric suites here give: a library
// module inherits compileSdk 37 and Robolectric has no Android 37 to emulate.
@Config(sdk = [36], qualifiers = "w411dp-h891dp-normal-long-notround-any-420dpi")
class SidebarScreenshotTest {

    @get:Rule val compose = createComposeRule()

    @Before
    fun pinTheZone() {
        // The row's date column renders in the default zone, so without this the
        // same instant is 09:05 in London and 11:05 in Berlin and the baseline
        // only matches wherever it was recorded.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    /**
     * The drawer as somebody with a classifying server and a few labels sees it.
     *
     * Promotions and Updates carry the dot; Primary never does, because its mail is the list
     * underneath and a bundle saying "3 new in Primary" over three unread rows is the app talking
     * to itself.
     */
    @Test
    fun sidebar() {
        capture("sidebar") { Sidebar() }
    }

    /** The same drawer with the star toggles out, which is how the middle group is rearranged. */
    @Test
    fun sidebarEditing() {
        capture("sidebar-editing") { Sidebar(isEditing = true) }
    }

    /**
     * A plMail that classifies nothing, where the Inbox row is the top group on its own.
     *
     * Five dead category rows would be worse than none: they would say the server has a feature it
     * does not have, and the only way to find out is to open each one.
     */
    @Test
    fun sidebarWithoutCategories() {
        capture("sidebar-no-categories") { Sidebar(showCategories = false) }
    }

    /** The three densities beside each other, which is the whole of the "it is too big" report. */
    @Test
    // A wider display than the suite's phone: `onRoot` captures the screen, so
    // three 280dp drawers on a 411dp one are three drawers squeezed into 137dp
    // each -- which would make this a screenshot of the ellipsis rules.
    @Config(qualifiers = "w900dp-h891dp-normal-long-notround-any-420dpi")
    fun sidebarDensities() {
        capture("sidebar-densities", width = 900.dp) {
            Row {
                PlMailDensity.entries.forEach { density ->
                    Surface(modifier = Modifier.width(280.dp)) {
                        PlMailTheme(theme = it, sidebarDensity = density) { Sidebar() }
                    }
                }
            }
        }
    }

    /**
     * The bundles **where they actually are**: the first rows of Primary, in its own scroller.
     *
     * Captured through the real [ThreadList] rather than by drawing the rows into a column, and
     * that is the whole point of this case. A [CategoryBundleRow] rendered on its own looks
     * free-floating and answers none of the questions worth asking about it — whether it reads as a
     * row of mail or as a banner bolted over the list, whether the rule under it separates two
     * things or cuts one in half, and whether the mail underneath still looks like the thing the
     * screen is for.
     */
    @Test
    fun primaryWithBundles() {
        capture("primary-with-bundles", width = 411.dp, height = 760.dp) {
            ThreadList(
                threads = pagedThreads(),
                rowsInFeed = mail.size,
                labels = sections.other,
                viewing = null,
                isSyncing = false,
                selection = emptySet(),
                arrivals = arrivals,
                isMerged = false,
                badgedNew = badgedNew,
                listState = rememberLazyListState(),
                onOpenCategory = {},
                onShown = {},
                onThreadSelected = {},
                onToggleSelected = {},
                onAction = { _, _ -> },
            )
        }
    }

    /**
     * The same list with no mail under the bundles, which is not a rare state.
     *
     * A well-triaged inbox is empty most of the time, and that is exactly when somebody most needs
     * telling where their mail went. The empty state must not draw over this — see [ThreadList].
     */
    @Test
    fun emptyPrimaryWithBundles() {
        capture("primary-empty-with-bundles", width = 411.dp, height = 400.dp) {
            ThreadList(
                threads = pagedThreads(emptyList()),
                rowsInFeed = 0,
                labels = emptyList(),
                viewing = null,
                isSyncing = false,
                selection = emptySet(),
                arrivals = arrivals,
                isMerged = false,
                badgedNew = badgedNew,
                listState = rememberLazyListState(),
                onOpenCategory = {},
                onShown = {},
                onThreadSelected = {},
                onToggleSelected = {},
                onAction = { _, _ -> },
            )
        }
    }

    /** The bundle row on its own, for the detail the list-level capture is too busy to show. */
    @Test
    fun categoryBundles() {
        // At the width of a phone's list pane rather than the drawer's: these
        // are rows of mail, and a sender line measured against 280dp would be
        // cut where nobody's is.
        capture("category-bundles", width = 411.dp, height = 220.dp) {
            Column {
                arrivals.forEach { CategoryBundleRow(arrivals = it, onClick = {}) }
            }
        }
    }

    // -- plumbing ------------------------------------------------------------

    /**
     * Renders a case in both schemes.
     *
     * The scheme is state inside one composition rather than two `setContent` calls: the rule
     * allows exactly one, and recomposing is in any case closer to what somebody switching themes
     * actually does.
     *
     * The lambda takes the scheme so a case can wrap its own [PlMailTheme] — which the density row
     * has to, since it draws three of them at once.
     */
    private fun capture(
        name: String,
        width: Dp = 280.dp,
        height: Dp = 760.dp,
        content: @Composable (PlMailThemeChoice) -> Unit,
    ) {
        val scheme = mutableStateOf(PlMailThemeChoice.LIGHT)

        compose.setContent {
            PlMailTheme(theme = scheme.value) {
                Surface(modifier = Modifier.width(width).height(height)) { content(scheme.value) }
            }
        }

        listOf(PlMailThemeChoice.LIGHT, PlMailThemeChoice.DARK).forEach { choice ->
            scheme.value = choice
            compose.waitForIdle()

            compose
                .onRoot()
                .captureRoboImage("src/test/screenshots/$name-${choice.name.lowercase()}.png")
        }
    }

    @Composable
    private fun Sidebar(showCategories: Boolean = true, isEditing: Boolean = false) {
        LabelSidebar(
            sections = sections,
            showCategories = showCategories,
            populatedCategories =
                setOf(
                    MailCategory.PRIMARY,
                    MailCategory.SOCIAL,
                    MailCategory.PROMOTIONS,
                    MailCategory.UPDATES,
                ),
            newCategories = setOf(MailCategory.PROMOTIONS, MailCategory.UPDATES),
            selected = MailView.START,
            onSelect = {},
            onCreate = {},
            isEditing = isEditing,
            onEditingChange = {},
            onImportantChange = { _, _ -> },
            onCalendar = {},
            onPush = {},
            onNotifications = {},
            onDiagnostics = {},
            onAppearance = {},
            onAccounts = {},
            modifier = Modifier.fillMaxWidth(),
        )
    }

    /**
     * A page of mail that is already loaded, with no spinner behind it.
     *
     * `PagingData.from` with explicit `NotLoading` states: the default leaves the list believing a
     * refresh is in flight, and the screenshot is then of a progress indicator.
     */
    @Composable
    private fun pagedThreads(rows: List<ThreadEntity> = mail): LazyPagingItems<ThreadEntity> =
        flowOf(
                PagingData.from(
                    rows,
                    sourceLoadStates =
                        LoadStates(
                            refresh = LoadState.NotLoading(endOfPaginationReached = true),
                            prepend = LoadState.NotLoading(endOfPaginationReached = true),
                            append = LoadState.NotLoading(endOfPaginationReached = true),
                        ),
                )
            )
            .collectAsLazyPagingItems()

    // -- fixtures ------------------------------------------------------------

    private val mail =
        listOf(
            thread(
                "1",
                sender = "Katrin Vogel",
                subject = "Re: die Nebenkostenabrechnung",
                snippet = "Ich habe die Belege angehängt, der Rest kommt Montag.",
                isUnread = true,
                minutesAgo = 40,
            ),
            thread(
                "2",
                sender = "Finanzamt Mitte",
                subject = "Ihr Steuerbescheid 2025",
                snippet = "Sehr geehrte Damen und Herren, anbei erhalten Sie …",
                isUnread = true,
                minutesAgo = 190,
                hasAttachment = true,
            ),
            thread(
                "3",
                sender = "Ada Lovelace, Charles Babbage",
                subject = "the analytical engine, again",
                snippet = "It can do anything we know how to order it to perform.",
                messageCount = 4,
                minutesAgo = 400,
            ),
            thread(
                "4",
                sender = "Hausverwaltung",
                subject = "Wartung der Heizung am Dienstag",
                snippet = "Zwischen 8 und 12 Uhr, bitte jemanden zu Hause lassen.",
                isFlagged = true,
                minutesAgo = 500,
            ),
        )

    private fun thread(
        id: String,
        sender: String,
        subject: String,
        snippet: String,
        isUnread: Boolean = false,
        isFlagged: Boolean = false,
        hasAttachment: Boolean = false,
        messageCount: Int = 1,
        minutesAgo: Long = 60,
    ) =
        ThreadEntity(
            uid = "https://nas.local/1#$id",
            accountKey = "https://nas.local/1",
            threadId = id,
            latestReceivedAt = capturedAt - minutesAgo * 60_000,
            subject = subject,
            participantsSummary = sender,
            snippet = snippet,
            messageCount = messageCount,
            isUnread = isUnread,
            isFlagged = isFlagged,
            hasAttachment = hasAttachment,
        )

    private val sections =
        SidebarSections(
            inbox = label("1", "Inbox", role = "inbox", unread = 12),
            important =
                listOf(
                    label("7", "Starred", role = "flagged"),
                    label("4", "Trash", role = "trash"),
                    label("5", "Spam", role = "junk", unread = 3),
                    label("2", "Sent", role = "sent"),
                    label("6", "Archive", role = "archive"),
                ),
            other =
                listOf(
                    label("3", "Drafts", role = "drafts", unread = 2),
                    label("8", "Snoozed", role = "snoozed"),
                    label("9", "Steuer", color = "amber", unread = 4),
                    label("10", "Wohnung", color = "blue"),
                    label("11", "Nebenkosten", path = "Wohnung/Nebenkosten", color = "blue"),
                ),
        )

    /** The two rows the badge is drawn on, so the capture shows it beside a row without one. */
    private val badgedNew = setOf("https://nas.local/1#1", "https://nas.local/1#2")

    private val arrivals =
        listOf(
            CategoryArrivals(
                category = MailCategory.PROMOTIONS,
                count = 4,
                senders = listOf("Rail Europe", "Duolingo", "Thalia"),
                moreSenders = 1,
            ),
            CategoryArrivals(
                category = MailCategory.UPDATES,
                count = 2,
                senders = listOf("GitHub", "Deutsche Bahn"),
                moreSenders = 0,
            ),
        )

    private fun label(
        key: String,
        name: String,
        path: String = name,
        role: String? = null,
        color: String? = null,
        unread: Int = 0,
    ) =
        Label(
            key = key,
            name = name,
            path = path,
            role = role,
            color = color,
            unreadThreads = unread,
            totalThreads = unread,
            mayRename = role == null,
            mayDelete = role == null,
            bindings = listOf(LabelBinding("https://nas.local/1", key)),
        )

    private companion object {
        /**
         * Nine this morning, whenever this morning is — and every fixture row is *within* today.
         *
         * The obvious thing is a fixed epoch, and it is wrong here. `ThreadRow`'s date column is
         * relative: a time for today, a weekday name for this week, a date beyond that. So a
         * baseline recorded against a frozen instant renders "08:20" on the day it was recorded and
         * "Tue" a day later — a screenshot test that fails on a calendar boundary rather than on a
         * change to the app. This suite caught itself doing exactly that.
         *
         * Anchoring to the current date fixes the drift, but only if no row crosses midnight: a row
         * twenty-five hours old renders as a weekday name, and *which* name depends on the day the
         * test runs. Hence 09:00 and every `minutesAgo` under nine hours, so every row is a time.
         *
         * The zone is pinned to UTC in [pinTheZone] for the same family of reasons — otherwise the
         * same instant is 09:05 in London and 11:05 in Berlin.
         */
        val capturedAt: Long = LocalDate.now().atTime(9, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    }
}
