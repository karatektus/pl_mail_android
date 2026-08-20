package de.plmail.core.designsystem

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * How small the density ladder is allowed to make a thing you have to hit.
 *
 * This exists because the test that looks like it covers this does not. `SidebarReachabilityTest`
 * renders the drawer and asserts every row is at least as tall as its density claims to draw —
 * which catches a row that lays out wrong, and cannot catch a ladder that is simply set too low,
 * because it takes the floor *from* the ladder. Lowering `sidebarRowHeight` to 36dp left it green.
 * Found by trying to make it fail, which is the only way that class of hole shows up.
 *
 * So the number itself is pinned here, separately, against a constant this file owns.
 *
 * **40dp, not 48.** [PlMailSpacing.touchTarget] is 48dp and says "never scaled below this, whatever
 * the density" — and for buttons, icons and chips that is enforced with no exception. A full-width
 * navigation row is the one shape that gets to go under it: it spans the whole drawer, it is
 * reached with a thumb in a list that is scrolled rather than aimed at, and its neighbours are the
 * same kind of target so a mis-tap is recoverable rather than destructive. Gmail's own compact
 * drawer sits at about 40dp. Eight under the guideline is the most this product is willing to be,
 * and the argument for it is on [PlMailDensity.sidebarRowHeight].
 *
 * If a future density wants to be denser than this, that is a product conversation and a change to
 * [ROW_FLOOR] — not a quiet edit to the enum.
 */
class DensityFloorTest {

    @Test
    fun `no density packs a navigation row below the floor`() {
        PlMailDensity.entries.forEach { density ->
            assertTrue(
                density.sidebarRowHeight >= ROW_FLOOR,
                "${density.wire} draws ${density.sidebarRowHeight} rows, under the ${ROW_FLOOR} " +
                    "floor a full-width navigation row is allowed",
            )
        }
    }

    /**
     * The loosest density is still the strict answer.
     *
     * Comfortable has no reason to spend the exception: somebody who has not asked for a denser app
     * should get the guideline, not eight dp under it.
     */
    @Test
    fun `the comfortable density meets the guideline outright`() {
        assertTrue(
            PlMailDensity.COMFORTABLE.sidebarRowHeight >= TOUCH_TARGET,
            "the default density should need no accessibility exception at all",
        )
    }

    /** The glyph inside a row may shrink freely — it is not the target, the row is. */
    @Test
    fun `the ladder is monotonic, so denser is never taller`() {
        val heights = PlMailDensity.entries.map { it.sidebarRowHeight }

        assertTrue(
            heights == heights.sortedDescending(),
            "the enum is ordered loosest first, so its row heights must descend: $heights",
        )
    }

    private companion object {
        val ROW_FLOOR = 40.dp
        val TOUCH_TARGET = 48.dp
    }
}
