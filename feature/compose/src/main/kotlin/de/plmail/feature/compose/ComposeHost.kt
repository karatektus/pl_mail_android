package de.plmail.feature.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.window.core.layout.WindowSizeClass
import de.plmail.core.designsystem.LocalPlMailTheme

/**
 * The composer, presented the way the window can afford.
 *
 * On a phone writing a message is the only thing happening, so it takes the screen. On a tablet it
 * is one thing happening *while* a mailbox is on screen — the message being answered is usually the
 * one still visible behind — and taking the whole 1280dp to hold four address fields wastes the
 * screen and loses the context in one move. So: a dialog where there is room, a screen where there
 * is not.
 *
 * The background is a slot rather than something drawn by the caller around this, and that is
 * deliberate. In the dialog case it must still be composed, because it is the point. In the
 * full-screen case it must **not** be: an opaque surface drawn over a live mail list looks right
 * and reads wrong, since everything behind is still in the same window and TalkBack walks straight
 * through the composer into the list underneath it. A real [Dialog] gets that right for free — it
 * is its own window — and the only way to get it right for the full-screen case is not to compose
 * the thing behind at all.
 */
@Composable
fun ComposeHost(request: ComposeRequest?, onClose: () -> Unit, behind: @Composable () -> Unit) {
    if (request == null) {
        behind()
        return
    }

    if (!hasRoomForADialog()) {
        ComposeScreen(request = request, onClose = onClose)
        return
    }

    behind()

    val theme = LocalPlMailTheme.current
    // The floating radius, not the pane radius. A dialog is the other thing in
    // this app that sits over content with a scrim behind it, and the flat
    // layout deliberately takes the pane radius to zero -- correct for a pane
    // in the page, wrong for a window over one. See PlMailRadii.
    val shape = RoundedCornerShape(theme.radii.floating)

    Dialog(
        onDismissRequest = onClose,
        properties =
            DialogProperties(
                // The composer is not a confirmation, and a stray tap on the
                // mail list behind must not put away half a written message --
                // even though closing saves it, finding your way back to a draft
                // is a worse outcome than an explicit close button.
                dismissOnClickOutside = false,
                // Sized against the window rather than to Material's dialog
                // width, which is built for a paragraph and an OK button.
                usePlatformDefaultWidth = false,
                // Compose handles the insets rather than the platform panning
                // the window, and the keyboard is why. Left to the platform, a
                // focused recipient field slides the whole dialog upward: it
                // clips its own title against the status bar and *still* leaves
                // the send button behind the keys, because a fixed-height pane
                // cannot get out of the way of something 300dp tall. Handled
                // here, the pane simply becomes shorter and everything in it
                // stays reachable.
                decorFitsSystemWindows = false,
            ),
    ) {
        Box(
            // safeDrawing rather than systemBars: it is the one that also covers
            // the display cutout and the keyboard, which are exactly the two
            // this pane has to survive. Applying it here consumes it, so nothing
            // inside pays for it twice.
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(MARGIN),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier =
                    Modifier.sizeIn(maxWidth = MAX_WIDTH, maxHeight = MAX_HEIGHT)
                        .fillMaxSize()
                        .clip(shape)
                        .background(theme.colors.surface, shape)
                        // A hairline, not a shadow, and unconditionally rather
                        // than only in the boxed layout: this pane floats over
                        // another one, and without an edge a surface-coloured
                        // dialog on a surface-coloured list has nothing to say
                        // where it stops.
                        .border(theme.spacing.hair, theme.colors.line, shape)
            ) {
                ComposeScreen(
                    request = request,
                    onClose = onClose,
                    // Zero, and this is the whole reason the parameter exists.
                    // The dialog is its own window, so the system-bar insets are
                    // reported inside it too -- and the pane above has already
                    // moved clear of them. Letting the app bar apply them again
                    // is exactly the doubled inset that put 80dp of dead space
                    // above the inbox title, reproduced one level down.
                    contentInsets = WindowInsets(0),
                )
            }
        }
    }
}

@Composable
private fun hasRoomForADialog(): Boolean =
    currentWindowAdaptiveInfoV2().windowSizeClass.hasRoomForADialog()

/**
 * Whether the window can hold a dialog without it becoming the screen in all but name.
 *
 * **Both axes**, which is the part worth stating, because the width-only version of this is what
 * everybody writes first. A large phone in landscape is over 900dp wide and under 450dp tall: it
 * passes a width test comfortably and then presents a dialog with no room for the message, with the
 * keyboard about to take half of what is left. Medium in both directions is the smallest window
 * where a composer floating over a mailbox beats one that owns the screen.
 */
internal fun WindowSizeClass.hasRoomForADialog(): Boolean =
    isAtLeastBreakpoint(
        widthDpBreakpoint = WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND,
        heightDpBreakpoint = WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND,
    )

/** How far the pane stays off the edge of the safe area, so the mailbox behind stays legible. */
private val MARGIN = 32.dp

/**
 * The widest and tallest the pane gets, whatever the screen.
 *
 * A composer is a column of short fields over a body: stretched across a 1280dp tablet the address
 * lines become a metre of empty rule, and the measure of the message itself goes past the point
 * where the eye can find the start of the next line.
 */
private val MAX_WIDTH = 720.dp
private val MAX_HEIGHT = 880.dp
