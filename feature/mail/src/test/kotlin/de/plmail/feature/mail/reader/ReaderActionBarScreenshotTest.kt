package de.plmail.feature.mail.reader

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import de.plmail.core.designsystem.PlMailTheme
import de.plmail.core.designsystem.PlMailThemeChoice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The bar at the foot of the reader, which is now the only place a message is answered from.
 *
 * Worth a baseline for one reason: the reply text links under the message body were removed, and
 * the capability they carried alone — reply-all — moved into this bar's overflow. "Reply all is
 * still reachable" is a claim about a control that is only drawn for some messages, and the way it
 * would go wrong is not a compile error. It is an overflow that never appears, or one that appears
 * on every message including the ones with a single recipient.
 *
 * Two cases and no more, because those are the two states the bar has. The message body above it is
 * a WebView, which Robolectric cannot render at all — see `MessageDocumentTest` for where the body
 * is guarded instead.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// sdk = 36 for the reason the other Robolectric suites here give: a library
// module inherits compileSdk 37 and Robolectric has no Android 37 to emulate.
@Config(sdk = [36], qualifiers = "w411dp-h891dp-normal-long-notround-any-420dpi")
class ReaderActionBarScreenshotTest {

    @get:Rule val compose = createComposeRule()

    /**
     * A thread with more than one recipient: reply, forward, and the overflow that holds reply-all.
     *
     * The overflow is what this baseline exists for. It is the whole of the move — if it is not in
     * this picture, a reply-all is not reachable anywhere in the app.
     */
    @Test
    fun withReplyAll() {
        capture("reader-actions-reply-all", canReplyAll = true)
    }

    /**
     * The same bar in German, which is where its labels stopped fitting.
     *
     * "Weiterleiten" is twelve characters against "Forward"'s seven, and Material's own 24dp of
     * content padding at each end of a tonal button was enough to push it over: the shipped app
     * drew "Weiterleit…" with empty padding either side of it. English fits with or without that
     * padding, so an English-only baseline could not have caught it and did not.
     *
     * Robolectric resolves resources against the qualifier, so this is the real `values-de` string
     * measured by the real button rather than a German-looking placeholder.
     */
    @Test
    @Config(qualifiers = "de-rDE-w411dp-h891dp-normal-long-notround-any-420dpi")
    fun inGerman() {
        capture("reader-actions-german", canReplyAll = true)
    }

    /**
     * A message from one person to one person, where reply-all would reach nobody new.
     *
     * No overflow at all rather than a greyed one. A control that sends to exactly the same people
     * under a different name teaches people not to trust the difference.
     */
    @Test
    fun withoutReplyAll() {
        capture("reader-actions", canReplyAll = false)
    }

    /**
     * Renders the bar in both schemes.
     *
     * The scheme is state inside one composition rather than two `setContent` calls, following
     * [de.plmail.feature.mail.SidebarScreenshotTest]: the rule allows exactly one.
     */
    private fun capture(name: String, canReplyAll: Boolean) {
        val scheme = mutableStateOf(PlMailThemeChoice.LIGHT)

        compose.setContent {
            PlMailTheme(theme = scheme.value) {
                // A phone's width, and only as tall as the bar. Captured against
                // a Surface rather than free-floating, because the divider along
                // its top edge is the thing that makes it read as part of the
                // page rather than as a floating toolbar, and a divider needs
                // something above it to divide from.
                Surface(modifier = Modifier.width(411.dp)) {
                    ReaderActionBar(
                        canReplyAll = canReplyAll,
                        onReply = {},
                        onReplyAll = {},
                        onForward = {},
                    )
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
