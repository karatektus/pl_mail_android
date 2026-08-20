package de.plmail.feature.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import de.plmail.core.data.ComposeDraft
import de.plmail.core.data.SendIdentity
import de.plmail.core.designsystem.PlMailDivider
import de.plmail.core.designsystem.PlMailTheme
import de.plmail.core.designsystem.PlMailThemeChoice
import de.plmail.jmap.mail.EmailAddress
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the composer's header and its contact list actually look like.
 *
 * Four things were reported against the shipped app and none of them could be checked by reading a
 * diff: a header that took four rows of a screen with the keyboard on it, a Cc/Bcc affordance on a
 * row of its own, and a suggestion list that grew the page instead of floating over it. Each has a
 * capture here, in both schemes.
 *
 * Under Robolectric rather than on a device, for the reason `SidebarScreenshotTest` gives: a
 * screenshot suite that needs an emulator is one that runs on no build at all.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// sdk = 36 for the reason the other Robolectric suites here give: a library
// module inherits compileSdk 37 and Robolectric has no Android 37 to emulate.
@Config(sdk = [36], qualifiers = "w411dp-h891dp-normal-long-notround-any-420dpi")
class ComposerScreenshotTest {

    @get:Rule val compose = createComposeRule()

    /**
     * The header open, which is how every composer starts.
     *
     * The Cc/Bcc button is the thing to look at: it is at the end of the To line rather than on a
     * row under it, and the chips it shares the field with are on their own rows above.
     */
    @Test
    fun headerExpanded() {
        capture("header-expanded", height = 300.dp) { Header(isExpanded = true) }
    }

    /**
     * The same header folded, which is what it wears while the message is being written.
     *
     * One line where there were four, and it has to stay honest: the sending address, two
     * recipients, the count of the ones that did not fit, and the subject.
     */
    @Test
    fun headerCollapsed() {
        capture("header-collapsed", height = 120.dp) { Header(isExpanded = false) }
    }

    /**
     * The To line at a width where the chips have to wrap.
     *
     * The question this answers is the one the trailing-slot decision was made against: whether a
     * narrow window makes the Cc/Bcc button eat into the recipients. It does not — the chips are a
     * `FlowRow` above the input and wrap onto as many rows as they need, and the button rides the
     * input row only.
     */
    @Test
    @Config(qualifiers = "w320dp-h891dp-normal-long-notround-any-420dpi")
    fun toLineWhenNarrow() {
        // Through the whole header rather than the field alone, because the
        // button being measured is the real one -- a bare Text stood in for it
        // in the first version of this test and overflowed the end of the row,
        // which said nothing about the component that actually ships.
        capture("to-line-narrow", width = 320.dp, height = 320.dp) {
            Header(isExpanded = true, state = crowded)
        }
    }

    /**
     * The contact list open over a message.
     *
     * Captured with [captureScreenRoboImage] rather than through `onRoot`, and that is the whole
     * point of this case: the list is a [androidx.compose.ui.window.Popup] now, which is a second
     * window, and `onRoot` walks only the first one. A capture that missed it would be a screenshot
     * of the composer with no suggestions in it, passing.
     *
     * Driven by actually typing into the field rather than by setting a flag, because the thing
     * being checked is that the list appears where the cursor is and covers the text under it
     * instead of pushing it down the page.
     */
    // captureScreenRoboImage is the only API that reaches a second window, and
    // it is still marked experimental. The alternative is not capturing the
    // popup, which would make this test a picture of the bug being fixed.
    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun suggestionsOverContent() {
        val scheme = mutableStateOf(PlMailThemeChoice.LIGHT)

        compose.setContent {
            PlMailTheme(theme = scheme.value) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column {
                        RecipientField(
                            label = "To",
                            addresses = listOf(everyone.first()),
                            onChanged = {},
                            suggestions = matches,
                            onQueryChanged = {},
                        )

                        PlMailDivider()

                        // Something for the list to be drawn over. Whether the
                        // page moves under it is the defect being guarded.
                        Text(
                            text = LOREM,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        )
                    }
                }
            }
        }

        compose.onNode(hasSetTextAction()).performTextInput("an")
        compose.waitForIdle()

        listOf(PlMailThemeChoice.LIGHT, PlMailThemeChoice.DARK).forEach { choice ->
            scheme.value = choice
            compose.waitForIdle()

            captureScreenRoboImage(
                "src/test/screenshots/suggestions-${choice.name.lowercase()}.png"
            )
        }
    }

    // -- plumbing ------------------------------------------------------------

    /**
     * Renders a case in both schemes.
     *
     * The scheme is state inside one composition rather than two `setContent` calls: the rule
     * allows exactly one, and recomposing is in any case closer to what somebody switching themes
     * actually does. The same helper `SidebarScreenshotTest` uses, for the same reasons.
     */
    private fun capture(
        name: String,
        width: Dp = 411.dp,
        height: Dp = 400.dp,
        content: @Composable () -> Unit,
    ) {
        val scheme = mutableStateOf(PlMailThemeChoice.LIGHT)

        compose.setContent {
            PlMailTheme(theme = scheme.value) {
                Surface(modifier = Modifier.width(width).height(height)) { content() }
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
    private fun Header(isExpanded: Boolean, state: ComposeUiState = draft) {
        ComposeHeader(
            state = state,
            isExpanded = isExpanded,
            onExpandedChange = {},
            onIdentity = {},
            onTo = {},
            onCc = {},
            onBcc = {},
            onSubject = {},
            onShowCopyFields = {},
            onQueryChanged = {},
        )
    }

    // -- fixtures ------------------------------------------------------------

    private val everyone =
        listOf(
            EmailAddress(name = "Katrin Vogel", email = "katrin@example.org"),
            EmailAddress(name = "Anna Meyer", email = "anna@example.org"),
            EmailAddress(name = null, email = "buchhaltung@hausverwaltung.example"),
        )

    private val identity =
        SendIdentity(
            accountKey = "https://nas.local/1",
            accountName = "NAS",
            identityId = "i1",
            name = "Jan Karatektus",
            email = "jan@plmail.example",
        )

    private val draft =
        ComposeUiState(
            draft =
                ComposeDraft(
                    accountKey = identity.accountKey,
                    identityId = identity.identityId,
                    to = everyone.take(2),
                    cc = everyone.drop(2),
                    subject = "Re: die Nebenkostenabrechnung 2025",
                ),
            identities = listOf(identity),
            isLoading = false,
        )

    /** Everybody on the To line, so a narrow window has to wrap them. */
    private val crowded = draft.copy(draft = draft.draft.copy(to = everyone, cc = emptyList()))

    private val matches =
        listOf(
            EmailAddress(name = "Anna Meyer", email = "anna@example.org"),
            EmailAddress(name = "Anna Schmidt", email = "a.schmidt@example.org"),
            EmailAddress(name = "Hausverwaltung Anders", email = "info@anders.example"),
            EmailAddress(name = null, email = "anmeldung@buergeramt.example"),
            EmailAddress(name = "Andrea Vogel", email = "andrea@example.org"),
            EmailAddress(name = "Anton Weiß", email = "anton@example.org"),
            EmailAddress(name = "Susanne Anders", email = "susanne@anders.example"),
            EmailAddress(name = "Bank Andechs", email = "service@andechs.example"),
        )

    private companion object {
        /** Long enough to still be visible below the list that covers the top of it. */
        const val LOREM =
            "Hallo Katrin,\n\nvielen Dank für die Unterlagen. Ich habe die Belege " +
                "durchgesehen und melde mich am Montag mit den offenen Punkten. Die " +
                "Abrechnung für die Heizung fehlt noch, die kommt direkt von der " +
                "Hausverwaltung — ich habe dort schon nachgefragt und warte auf Antwort. " +
                "Ansonsten sieht alles so aus, wie wir es besprochen hatten.\n\n" +
                "Viele Grüße\nJan"
    }
}
