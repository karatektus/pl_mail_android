package de.plmail.feature.mail

import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import de.plmail.core.data.Label
import de.plmail.core.data.LabelBinding
import de.plmail.core.data.MailCategory
import de.plmail.core.data.MailView
import de.plmail.core.data.SidebarSections
import de.plmail.core.designsystem.PlMailDensity
import de.plmail.core.designsystem.PlMailTheme
import de.plmail.core.ui.testing.assertEveryControlIsReachable
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every control in the drawer can be hit and can be named, at all three densities.
 *
 * Written after an audit of the semantics tree found two real faults that nothing else would have
 * caught: the compact drawer's rows were 36dp — twelve under the guideline, with no argument
 * anywhere for why — and the "New label" button was 40dp at *every* density, because that is
 * Material's own `TextButton` height and nobody had looked.
 *
 * The rows keep a bounded exception, argued at length on `PlMailDensity.sidebarRowHeight`: they are
 * the full width of the drawer, in a list that is scrolled rather than aimed at. It is passed in
 * per density rather than hardcoded, so raising the ladder cannot silently widen the exception —
 * the test asks for exactly the height that density claims to draw, and a row shorter than its own
 * density's floor fails here.
 *
 * Buttons get no exception at all, which is the point: the audit's second finding was a button.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-normal-long-notround-any-420dpi")
class SidebarReachabilityTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun `every drawer control is reachable and named, at every density`() {
        val chosen = mutableStateOf(PlMailDensity.COMFORTABLE)
        var density: Density? = null

        compose.setContent {
            density = LocalDensity.current

            PlMailTheme(sidebarDensity = chosen.value) {
                Surface {
                    LabelSidebar(
                        sections = sections,
                        showCategories = true,
                        populatedCategories = MailCategory.entries.toSet(),
                        newCategories = setOf(MailCategory.PROMOTIONS),
                        selected = MailView.START,
                        onSelect = {},
                        onCreate = {},
                        isEditing = false,
                        onEditingChange = {},
                        onImportantChange = { _, _ -> },
                        onCalendar = {},
                        onPush = {},
                        onNotifications = {},
                        onDiagnostics = {},
                        onAppearance = {},
                        onAccounts = {},
                        modifier = Modifier,
                    )
                }
            }
        }

        PlMailDensity.entries.forEach { packed ->
            chosen.value = packed
            compose.waitForIdle()

            compose
                .onRoot()
                .assertEveryControlIsReachable(density!!, rowsMayBe = packed.sidebarRowHeight)
        }
    }

    /**
     * The same drawer with the stars out, because that mode adds a control to every row.
     *
     * A row in editing mode is still a row, so it keeps the exception; what this is really checking
     * is that the star did not become a separate, smaller target of its own.
     */
    @Test
    fun `the rearranging mode adds no control too small to hit`() {
        var density: Density? = null

        compose.setContent {
            density = LocalDensity.current

            PlMailTheme(sidebarDensity = PlMailDensity.COMPACT) {
                Surface {
                    LabelSidebar(
                        sections = sections,
                        showCategories = true,
                        populatedCategories = MailCategory.entries.toSet(),
                        newCategories = emptySet(),
                        selected = MailView.START,
                        onSelect = {},
                        onCreate = {},
                        isEditing = true,
                        onEditingChange = {},
                        onImportantChange = { _, _ -> },
                        onCalendar = {},
                        onPush = {},
                        onNotifications = {},
                        onDiagnostics = {},
                        onAppearance = {},
                        onAccounts = {},
                        modifier = Modifier,
                    )
                }
            }
        }

        compose
            .onRoot()
            .assertEveryControlIsReachable(
                density!!,
                rowsMayBe = PlMailDensity.COMPACT.sidebarRowHeight,
            )
    }

    private val sections =
        SidebarSections(
            inbox = label("1", "Inbox", role = "inbox"),
            important =
                listOf(label("2", "Sent", role = "sent"), label("3", "Trash", role = "trash")),
            other = listOf(label("9", "Steuer"), label("10", "Wohnung")),
        )

    private fun label(key: String, name: String, role: String? = null) =
        Label(
            key = key,
            name = name,
            path = name,
            role = role,
            color = null,
            unreadThreads = 3,
            totalThreads = 3,
            mayRename = role == null,
            mayDelete = role == null,
            bindings = listOf(LabelBinding("a", key)),
        )
}
