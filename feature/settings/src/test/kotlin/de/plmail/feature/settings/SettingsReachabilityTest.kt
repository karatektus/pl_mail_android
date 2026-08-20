package de.plmail.feature.settings

import androidx.compose.material3.Surface
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import de.plmail.core.data.AppLanguage
import de.plmail.core.designsystem.PlMailDensity
import de.plmail.core.designsystem.PlMailTheme
import de.plmail.core.ui.testing.assertEveryControlIsReachable
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The settings screen's controls can be hit and can be named.
 *
 * `LanguageSection` stands in for the whole screen deliberately rather than as a shortcut: the
 * appearance page is built from one segmented-selector pattern repeated — theme, layout, density,
 * per-surface density and language are all the same control with different options in it. Auditing
 * one of them at the tightest density audits the shape they share.
 *
 * **No row exception here.** The drawer's full-width navigation rows get one, argued on
 * `PlMailDensity.sidebarRowHeight`; a settings control is a button sitting next to other buttons,
 * which is exactly the case `PlMailSpacing.touchTarget` exists for. If this starts failing, the fix
 * is the control, not the assertion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-normal-long-notround-any-420dpi")
class SettingsReachabilityTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun `the segmented selector is reachable at the tightest density`() {
        var density: Density? = null

        compose.setContent {
            density = LocalDensity.current

            // Compact, because that is where a control is smallest and where a
            // reachability problem would first appear.
            PlMailTheme(density = PlMailDensity.COMPACT) {
                Surface {
                    LanguageSection(
                        chosen = AppLanguage.GERMAN,
                        hasSystemEntry = true,
                        onChoose = {},
                    )
                }
            }
        }

        compose.onRoot().assertEveryControlIsReachable(density!!, rowsMayBe = null)
    }
}
