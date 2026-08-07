package de.plmail.feature.mail

import android.content.Context
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import de.plmail.core.designsystem.PlMailTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The mail list's top bar, on a phone.
 *
 * The calendar used to live only in the navigation drawer, which put the app's second whole feature
 * area behind a swipe, a scroll past every label the user has, and a tap. This pins the second way
 * in: an icon button in the top bar, **immediately left of Search**.
 *
 * The position is asserted from the laid-out bounds rather than from the order of the source, which
 * is the only version of the claim worth making — "left of Search" is what a thumb finds, and a
 * `RowScope` whose arrangement changed would keep the source order and move the button.
 *
 * A narrow portrait qualifier on purpose. A phone is the window this control was asked for and the
 * one where a bar with three actions in it has least room; the tablet draws the same bar with the
 * sidebar permanently beside it.
 */
@RunWith(RobolectricTestRunner::class)
// sdk = 36 explicitly, as this module's screenshot suite does: a library module
// declares no targetSdk and inherits compileSdk 37, which Robolectric has no
// Android to emulate.
@Config(sdk = [36], qualifiers = "w411dp-h891dp-normal-long-notround-any-420dpi")
class MailListActionsTest {

    @get:Rule val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** The one word both entries use, so TalkBack names one destination. */
    private val calendar = context.getString(R.string.calendar)
    private val search = context.getString(R.string.search)

    @Test
    fun `the calendar sits immediately left of search`() {
        bar(onCalendar = {})

        compose.onNodeWithContentDescription(calendar).assertIsDisplayed()

        val calendarBounds =
            compose.onNodeWithContentDescription(calendar).getUnclippedBoundsInRoot()
        val searchBounds = compose.onNodeWithContentDescription(search).getUnclippedBoundsInRoot()

        // Left of it, and *immediately* left of it: nothing is laid out between
        // the two, so the calendar's right edge is where search begins.
        assert(calendarBounds.right <= searchBounds.left) {
            "Calendar ($calendarBounds) is not left of search ($searchBounds)"
        }
        assert(searchBounds.left - calendarBounds.right < IMMEDIATELY) {
            "Something is laid out between calendar ($calendarBounds) and search ($searchBounds)"
        }
    }

    @Test
    fun `tapping the calendar opens it`() {
        var opened = 0

        bar(onCalendar = { opened += 1 })

        compose.onNodeWithContentDescription(calendar).assertHasClickAction().performClick()

        // Once per tap. The destination is a flag in :app rather than a back
        // stack entry, so a second delivery would be a second flip of it.
        assertEquals(1, opened)
    }

    /**
     * A server publishing no calendars capability has nothing for the button to open, and the
     * drawer's entry is hidden on exactly the same condition. A disabled control here would say the
     * feature is somewhere else rather than absent.
     */
    @Test
    fun `no calendar means no button`() {
        bar(onCalendar = null)

        compose.onNodeWithContentDescription(calendar).assertDoesNotExist()
        compose.onNodeWithContentDescription(search).assertIsDisplayed()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun bar(onCalendar: (() -> Unit)?) {
        compose.setContent {
            // reduceMotion, because the alternative is asking a Robolectric
            // ContentResolver for a system setting it has no answer for.
            PlMailTheme(reduceMotion = true) {
                TopAppBar(
                    title = { Text("Inbox") },
                    actions = {
                        MailListActions(
                            onCalendar = onCalendar,
                            onSearch = {},
                            // No label, so the overflow is not drawn: the inbox
                            // is the list this bar is over nearly all the time,
                            // and it is the case where the two buttons are
                            // adjacent to the bar's own end padding.
                            label = null,
                            onEditLabel = {},
                        )
                    },
                )
            }
        }
    }

    private companion object {
        /**
         * How much space between two icon buttons still counts as "immediately".
         *
         * `IconButton` is 48dp of touch target around a 24dp glyph, and Material's action row puts
         * no gap of its own between them — so anything under a whole button's width means nothing
         * was inserted. Generous on purpose: this is guarding against a third control appearing
         * between them, not against a spacing token being retuned.
         */
        val IMMEDIATELY = 48.dp
    }
}
