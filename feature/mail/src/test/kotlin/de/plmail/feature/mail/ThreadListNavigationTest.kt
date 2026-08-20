package de.plmail.feature.mail

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import de.plmail.core.data.CategoryArrivals
import de.plmail.core.data.MailCategory
import de.plmail.core.database.ThreadEntity
import de.plmail.core.designsystem.PlMailTheme
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tapping a new-mail bundle is a *navigation*, and this is the test that says so.
 *
 * It is here because of a specific report: tapping "Promotions — 4 new" loaded the promotions into
 * the list the user was already on. The rows changed; nothing else did. The drawer went on
 * highlighting Primary, back left the app instead of returning, and the only way to get Primary's
 * own mail back was to navigate somewhere else and come back.
 *
 * The cause was one line — the bundle called `viewModel.show(...)`, which moves the pager and
 * nothing else. Which list is showing is the shell's state, and the shell is what has to be told.
 * So what is pinned here is not "a callback fires" for its own sake: it is that the list reports
 * the tap **outward** rather than resolving it itself, which is the property that was missing.
 */
@RunWith(RobolectricTestRunner::class)
// sdk = 36 for the reason the other Robolectric suites in this module give: a
// library module inherits compileSdk 37 and Robolectric has no Android 37.
@Config(sdk = [36], qualifiers = "w411dp-h891dp-normal-long-notround-any-420dpi")
class ThreadListNavigationTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun `tapping a bundle asks to go there rather than changing the list underneath`() {
        val opened = mutableListOf<MailCategory>()

        compose.setContent {
            PlMailTheme {
                ThreadList(
                    threads =
                        flowOf(
                                PagingData.from(
                                    emptyList<ThreadEntity>(),
                                    sourceLoadStates = settled,
                                )
                            )
                            .collectAsLazyPagingItems(),
                    listState = rememberLazyListState(),
                    rowsInFeed = 0,
                    labels = emptyList(),
                    viewing = null,
                    isSyncing = false,
                    selection = emptySet(),
                    arrivals = arrivals,
                    isMerged = false,
                    badgedNew = emptySet(),
                    onOpenCategory = { opened += it },
                    onShown = {},
                    onThreadSelected = {},
                    onToggleSelected = {},
                    onAction = { _, _ -> },
                )
            }
        }

        // By its spoken description, which is the whole row: `CategoryBundleRow`
        // collapses its glyph, count and two text runs into one node so TalkBack
        // reads a sentence rather than four fragments.
        compose.onNodeWithContentDescription("Promotions", substring = true).performClick()

        assertEquals(listOf(MailCategory.PROMOTIONS), opened)
    }

    /**
     * Loaded, and finished loading. The default leaves the list believing a refresh is in flight.
     */
    private val settled =
        LoadStates(
            refresh = LoadState.NotLoading(endOfPaginationReached = true),
            prepend = LoadState.NotLoading(endOfPaginationReached = true),
            append = LoadState.NotLoading(endOfPaginationReached = true),
        )

    private val arrivals =
        listOf(
            CategoryArrivals(
                category = MailCategory.PROMOTIONS,
                count = 4,
                senders = listOf("Rail Europe"),
                moreSenders = 0,
            )
        )
}
