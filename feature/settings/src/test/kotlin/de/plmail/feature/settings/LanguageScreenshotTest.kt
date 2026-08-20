package de.plmail.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import de.plmail.core.data.AppLanguage
import de.plmail.core.designsystem.PlMailTheme
import de.plmail.core.designsystem.PlMailThemeChoice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the language picker actually looks like, in both schemes.
 *
 * **The third case is why this suite exists.** A stored language this build no longer ships draws
 * the row with nothing selected, which is the truthful picture and is also indistinguishable, in a
 * diff, from a control that failed to render. Nobody will reach that state by tapping, so a picture
 * is the only way to know it reads as "none of these" rather than as a bug — and it is the state a
 * future change is most likely to break without any test noticing.
 *
 * The section is captured through [LanguageSection] rather than through `AppearanceScreen`, which
 * needs a Hilt activity and a view model that talks to `LocaleManager`. What is being guarded is
 * the drawing, and the drawing is a function of three arguments.
 *
 * Under Robolectric rather than on a device, for the reason the suites in `:core:ui` and
 * `:feature:mail` give: a screenshot suite that needs an emulator is one that runs on no build.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// sdk = 36 for the reason the other Robolectric suites here give: a library
// module inherits compileSdk 37 and Robolectric has no Android 37 to emulate.
@Config(sdk = [36], qualifiers = "w411dp-h891dp-normal-long-notround-any-420dpi")
class LanguageScreenshotTest {

    @get:Rule val compose = createComposeRule()

    /** The ordinary case: no override, and Android's own language screen to point at. */
    @Test
    fun followingTheSystem() {
        capture("language-system") {
            LanguageSection(chosen = AppLanguage.SYSTEM, hasSystemEntry = true, onChoose = {})
        }
    }

    /** A language chosen, which is what the picker looks like for most of its life. */
    @Test
    fun chosen() {
        capture("language-german") {
            LanguageSection(chosen = AppLanguage.GERMAN, hasSystemEntry = true, onChoose = {})
        }
    }

    /**
     * API 31 and 32, where the second supporting line is not drawn.
     *
     * Android has no per-app language screen below 33, so the sentence naming one is left out — and
     * the section has to still read as finished without it rather than as a paragraph cut short.
     */
    @Test
    fun withoutASystemEntry() {
        capture("language-no-system-entry", height = 190.dp) {
            LanguageSection(chosen = AppLanguage.ENGLISH, hasSystemEntry = false, onChoose = {})
        }
    }

    /**
     * A language this build does not ship, which draws nothing selected.
     *
     * See [AppLanguage.of] for why this is the truth rather than a gap, and this capture for
     * whether it looks like one.
     */
    @Test
    fun setToALanguageTheAppDoesNotShip() {
        capture("language-unknown") {
            LanguageSection(chosen = null, hasSystemEntry = true, onChoose = {})
        }
    }

    /**
     * Renders a case in both schemes.
     *
     * The scheme is state inside one composition rather than two `setContent` calls: the rule
     * allows exactly one, and recomposing is in any case closer to what somebody switching themes
     * does.
     */
    private fun capture(
        name: String,
        width: Dp = 411.dp,
        height: Dp = 270.dp,
        content: @Composable () -> Unit,
    ) {
        val scheme = mutableStateOf(PlMailThemeChoice.LIGHT)

        compose.setContent {
            PlMailTheme(theme = scheme.value) {
                Surface(modifier = Modifier.width(width).height(height)) {
                    // The screen's own gutter, so the row is measured against
                    // the width it actually gets rather than the full display.
                    // A three-option row is exactly where a label first wraps.
                    Box(modifier = Modifier.padding(PlMailTheme.spacing.gutter)) { content() }
                }
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
}
