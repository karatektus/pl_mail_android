package de.plmail

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the app's own icons look like, which is the one thing about this app nobody sees while
 * working on it.
 *
 * The launcher draws them, no screen does, and there is no test anywhere that opens them — which is
 * how both icons went on wearing **Google's literal brand hexes** for a whole release line after
 * the web deliberately dropped that colourway, and how the mail mark's geometry drifted to a
 * superseded version of the letters without anybody noticing. Neither is the kind of mistake review
 * catches: the diff of a vector drawable is a wall of coordinates.
 *
 * Both are drawn here the way a launcher composes them — the background layer, the foreground over
 * it, the whole thing masked — rather than as bare foregrounds, because the safe-zone arithmetic in
 * those files is a claim about exactly that, and a foreground on its own would keep the claim
 * untested. The circle is the harshest common mask; the squircle is the usual one.
 *
 * ## The colourways, and why four of thirty-two
 *
 * The mail icon now follows the logo colourway the user picked on the web, which means thirty-one
 * more generated foregrounds — every one of them the same seven strokes with different paint.
 * Thirty -two baselines would be thirty-two PNGs to re-approve every time the mark moves, for a
 * generator that gets all of them identically right or identically wrong.
 *
 * So four, chosen to be different **in kind** rather than merely different in hue, because the ways
 * this can go wrong are structural:
 *
 * - `product-blue` is flat: one colour for all seven strokes. If the generator ever mispaired the
 *   list with the paths, this is the one baseline that could not show it — which is exactly why it
 *   is here as the control that the mark is still the mark.
 * - `petrol-copper` is a duotone, four strokes then three. It is the case that catches a colour
 *   list applied in the wrong **order**: the split lands between the p and the l, so a reversed
 *   list is an obviously wrong picture rather than a subtly wrong one.
 * - `aurora` is a seven-step sweep, green through to indigo. Every stroke is a different colour, so
 *   this is the baseline where an off-by-one in the substitution shows.
 * - `ink` is near-black, a single very dark colour on the off-white tile. It is the contrast case:
 *   it is what proves the fixed light background is the right one to have painted for, and it is
 *   the colourway a dark-variant mistake would have made invisible.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// sdk = 36 for the reason every Robolectric suite here gives: the app compiles
// against 37 and Robolectric has no image for it.
@Config(sdk = [36], qualifiers = "w411dp-h891dp-normal-long-notround-any-420dpi")
class LauncherIconScreenshotTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun mail() {
        capture("icon-mail", R.drawable.ic_launcher_foreground)
    }

    @Test
    fun calendar() {
        capture("icon-calendar", R.drawable.ic_launcher_calendar_foreground)
    }

    /** Flat: one colour, seven strokes. The control. See the class docblock. */
    @Test
    fun `mail in product blue`() {
        capture("icon-mail-product-blue", R.drawable.ic_launcher_product_blue_foreground)
    }

    /** Duotone, and the split falls between the two letters. Catches a reversed list. */
    @Test
    fun `mail in petrol copper`() {
        capture("icon-mail-petrol-copper", R.drawable.ic_launcher_petrol_copper_foreground)
    }

    /** Seven distinct colours in a sweep. Catches an off-by-one in the substitution. */
    @Test
    fun `mail in aurora`() {
        capture("icon-mail-aurora", R.drawable.ic_launcher_aurora_foreground)
    }

    /** Near-ink on off-white: the contrast case, and the one a dark variant would have lost. */
    @Test
    fun `mail in ink`() {
        capture("icon-mail-ink", R.drawable.ic_launcher_ink_foreground)
    }

    /**
     * The themed layer, on the flat grey the platform would tint.
     *
     * Its whole job is to be the same letters as the coloured mark. Drawn beside it in review, a
     * difference is obvious; described in a comment, it is not.
     */
    @Test
    fun monochrome() {
        capture(
            "icon-monochrome",
            R.drawable.ic_launcher_monochrome,
            background = Color(0xFFE0E0E0),
        )
    }

    private fun capture(name: String, foreground: Int, background: Color? = null) {
        compose.setContent {
            Surface(color = Color(0xFF9E9E9E)) {
                Row {
                    Icon(foreground, background, CircleShape)
                    Icon(foreground, background, RoundedCornerShape(percent = 25))
                }
            }
        }

        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Composable
    private fun Icon(foreground: Int, background: Color?, mask: Shape) {
        // 108dp of canvas showing 72dp of icon is the platform's own ratio, and
        // it is what makes the safe circle mean anything: a mask crops to the
        // middle two thirds.
        Box(
            modifier =
                Modifier.padding(8.dp)
                    .size(108.dp)
                    .clip(mask)
                    .background(background ?: colorResource(R.color.ic_launcher_background))
        ) {
            Image(
                painter = painterResource(foreground),
                contentDescription = null,
                modifier = Modifier.size(108.dp),
            )
        }
    }
}
