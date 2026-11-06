package de.plmail.feature.mail

import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import de.plmail.core.data.Label
import de.plmail.core.data.LabelBinding
import de.plmail.core.data.MailCategory
import de.plmail.core.data.MailInbox
import de.plmail.core.data.MailTab
import de.plmail.core.data.MailView
import de.plmail.core.data.SidebarSections
import de.plmail.core.designsystem.PlMailDensity
import de.plmail.core.designsystem.PlMailTheme
import de.plmail.core.ui.testing.assertEveryControlIsReachable
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The drawer with several unified inboxes (pl_mail#265): one row each, the open one's tabs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-normal-long-notround-any-420dpi")
class SidebarInboxesTest {

    @get:Rule val compose = createComposeRule()

    private var chosen: MailView? = null
    private var density: Density? = null

    @Test
    fun `each inbox is a row, named by the reader or by the app`() {
        show(selected = MailView.Category(MailCategory.PRIMARY, "1"))

        compose.onNodeWithText("Work").assertIsDisplayed()
        compose.onNodeWithText("Other").assertIsDisplayed()
        compose.onNodeWithText("4").assertIsDisplayed()
    }

    @Test
    fun `the open inbox lists its other tabs, and primary is the inbox's own row`() {
        show(selected = MailView.Category(MailCategory.PRIMARY, "1"))

        compose.onNodeWithText("Social").assertIsDisplayed()
        compose.onNodeWithText("Primary").assertDoesNotExist()
        // Promotions holds mail only in Other, which is not the inbox open.
        compose.onNodeWithText("Promotions").assertDoesNotExist()
    }

    @Test
    fun `a tab opens in its inbox and an inbox opens on its primary`() {
        show(selected = MailView.Category(MailCategory.PRIMARY, "1"))

        compose.onNodeWithText("Social").performClick()
        assertEquals(MailView.Category(MailCategory.SOCIAL, "1"), chosen)

        compose.onNodeWithText("Other").performClick()
        assertEquals(MailView.Category(MailCategory.PRIMARY, "3"), chosen)
    }

    @Test
    fun `the inbox rows are reachable and named`() {
        show(selected = MailView.Category(MailCategory.SOCIAL, "1"))

        compose
            .onRoot()
            .assertEveryControlIsReachable(
                density!!,
                rowsMayBe = PlMailDensity.COMFORTABLE.sidebarRowHeight,
            )
    }

    private fun show(selected: MailView) {
        compose.setContent {
            density = LocalDensity.current

            PlMailTheme {
                Surface {
                    LabelSidebar(
                        sections = sections,
                        showCategories = true,
                        tabs = MailTab.BUILT_IN,
                        inboxes = inboxes,
                        populatedTabs = MailCategory.entries.mapTo(mutableSetOf()) { it.wire },
                        newTabs = emptySet(),
                        selected = selected,
                        onSelect = { chosen = it },
                        onCreate = {},
                        isEditing = false,
                        onEditingChange = {},
                        onImportantChange = { _, _ -> },
                        onCalendar = null,
                        onPush = {},
                        onNotifications = {},
                        onDiagnostics = {},
                        onAppearance = {},
                        onGestures = {},
                        onAccounts = {},
                        modifier = Modifier,
                    )
                }
            }
        }
    }

    private val inboxes =
        listOf(
            SidebarInbox(
                inbox = MailInbox("1", "Work", "briefcase", isOther = false, setOf("a")),
                unread = 4,
                populatedTabs = setOf(MailCategory.PRIMARY.wire, MailCategory.SOCIAL.wire),
                newTabs = emptySet(),
            ),
            SidebarInbox(
                inbox = MailInbox("3", null, "boxes-stacked", isOther = true, setOf("b")),
                unread = 0,
                populatedTabs = setOf(MailCategory.PRIMARY.wire, MailCategory.PROMOTIONS.wire),
                newTabs = setOf(MailCategory.PROMOTIONS.wire),
            ),
        )

    private val sections =
        SidebarSections(
            inbox = label("1", "Inbox", role = "inbox"),
            important = listOf(label("2", "Sent", role = "sent")),
            other = listOf(label("9", "Steuer")),
        )

    private fun label(key: String, name: String, role: String? = null) =
        Label(
            key = key,
            name = name,
            path = name,
            role = role,
            color = null,
            unreadThreads = 0,
            totalThreads = 0,
            mayRename = role == null,
            mayDelete = role == null,
            bindings = listOf(LabelBinding("a", key)),
        )
}
