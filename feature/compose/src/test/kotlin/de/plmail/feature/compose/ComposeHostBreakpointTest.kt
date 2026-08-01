package de.plmail.feature.compose

import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.computeWindowSizeClass
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which windows get the composer as a dialog.
 *
 * The case worth a test is the phone held sideways. It is wide — wider than a tablet in portrait —
 * so the obvious width-only breakpoint says "dialog", and the result is a floating pane a couple of
 * hundred dp tall with the keyboard about to cover most of it. Everything else here is a boundary
 * around that one judgement.
 */
class ComposeHostBreakpointTest {

    @Test
    fun `a phone in portrait writes full screen`() {
        assertFalse(windowOf(412, 915).hasRoomForADialog())
    }

    @Test
    fun `a phone in landscape writes full screen, however wide it is`() {
        // 915dp wide clears the medium *and* expanded width breakpoints. Only
        // the height keeps this honest.
        assertFalse(windowOf(915, 412).hasRoomForADialog())
    }

    @Test
    fun `a tablet gets the dialog in both orientations`() {
        assertTrue(windowOf(800, 1280).hasRoomForADialog())
        assertTrue(windowOf(1280, 800).hasRoomForADialog())
    }

    /**
     * A folding phone opened out, which is the awkward middle. 673 × 841 is close to Pixel Fold's
     * inner display and is exactly the size where "is this a tablet" has no obvious answer — it has
     * the room, so it gets the dialog.
     */
    @Test
    fun `an unfolded foldable has the room`() {
        assertTrue(windowOf(673, 841).hasRoomForADialog())
    }

    /** A split-screen window on a tablet, which is where the width runs out first. */
    @Test
    fun `half a tablet does not`() {
        assertFalse(windowOf(500, 800).hasRoomForADialog())
    }
}

/**
 * The size class a window of this many dp would be given.
 *
 * Through the same breakpoint set the runtime uses rather than by constructing a `WindowSizeClass`
 * from the numbers directly — a hand-built one would agree with the production code by construction
 * and prove nothing about where the boundaries actually fall.
 */
private fun windowOf(widthDp: Int, heightDp: Int): WindowSizeClass =
    WindowSizeClass.BREAKPOINTS_V1.computeWindowSizeClass(widthDp, heightDp)
