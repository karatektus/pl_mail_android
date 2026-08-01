package de.plmail.core.designsystem

import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The palette, checked rather than claimed.
 *
 * A theme that fails contrast is not finished, and contrast is exactly the property that decays
 * silently: someone warms a grey by two points to make a mock look better and a whole scheme drops
 * below AA with nothing on screen looking different to the person who changed it. These are the
 * pairs the app actually draws, so a change that breaks one fails the build rather than shipping.
 *
 * Ratios are computed from the WCAG 2.1 relative-luminance formula, not approximated — the sRGB
 * transfer curve is not linear and eyeballing it is how a #999 on #FFF ends up in a product.
 */
class PaletteContrastTest {

    /**
     * Every scheme the app can resolve, through the same resolver the app uses.
     *
     * Built from [PlMailThemeChoice.entries] rather than listed, because a listed set is a set
     * somebody adds a theme to and forgets — and the theme that never reaches this test is exactly
     * the one that ships below AA. `SYSTEM` resolves to both of its schemes, which is why it is
     * asked twice.
     */
    private val schemes =
        PlMailThemeChoice.entries
            .flatMap { choice ->
                when (choice) {
                    PlMailThemeChoice.SYSTEM ->
                        listOf(
                            "system-light" to paletteFor(choice, isDark = false),
                            "system-dark" to paletteFor(choice, isDark = true),
                        )
                    else ->
                        listOf(choice.name.lowercase() to paletteFor(choice, choice.isDark(false)))
                }
            }
            .toMap()

    @Test
    fun `body text clears AA on every surface it is drawn on`() {
        schemes.forEach { (name, colors) ->
            listOf(colors.surface, colors.raised, colors.sunken).forEach { background ->
                assertAtLeast(
                    ratio = contrast(colors.ink, background),
                    minimum = 7.0,
                    what = "$name ink",
                )
                assertAtLeast(contrast(colors.inkSoft, background), 4.5, "$name inkSoft")
            }
        }
    }

    @Test
    fun `metadata clears AA, and furniture clears the large-text floor`() {
        // inkMuted carries dates and counts, which are small: full 4.5.
        // inkFaint is placeholders and disabled affordances, where 3.0 is the
        // documented floor — but it must not be below it, or a placeholder
        // becomes invisible rather than quiet.
        schemes.forEach { (name, colors) ->
            assertAtLeast(contrast(colors.inkMuted, colors.surface), 4.5, "$name inkMuted")
            assertAtLeast(contrast(colors.inkFaint, colors.surface), 3.0, "$name inkFaint")
        }
    }

    @Test
    fun `the accent is legible as text and as a fill`() {
        schemes.forEach { (name, colors) ->
            assertAtLeast(contrast(colors.accent, colors.surface), 4.5, "$name accent on surface")
            assertAtLeast(contrast(colors.onAccent, colors.accent), 4.5, "$name onAccent")
            assertAtLeast(
                contrast(colors.accent, colors.accentSoft),
                4.5,
                "$name accent on its own tint",
            )
        }
    }

    @Test
    fun `every status colour is legible on the page and on its own tint`() {
        schemes.forEach { (name, colors) ->
            listOf(
                    "danger" to (colors.danger to colors.dangerSoft),
                    "warning" to (colors.warning to colors.warningSoft),
                    "success" to (colors.success to colors.successSoft),
                    "info" to (colors.info to colors.infoSoft),
                )
                .forEach { (label, pair) ->
                    val (ink, tint) = pair

                    assertAtLeast(contrast(ink, colors.surface), 4.5, "$name $label on surface")
                    assertAtLeast(contrast(ink, tint), 4.5, "$name $label on tint")
                }
        }
    }

    @Test
    fun `a snackbar is legible over the app`() {
        schemes.forEach { (name, colors) ->
            assertAtLeast(
                contrast(colors.inverseInk, colors.inverseSurface),
                7.0,
                "$name inverse",
            )
        }
    }

    @Test
    fun `every avatar takes its scheme's label colour`() {
        // The row draws one initial on all eight, so all eight have to take the
        // same ink. The first version hardcoded white and produced eight
        // unreadable avatars in dark mode, where the ramp has to be bright to
        // be visible at all against a near-black page.
        schemes.forEach { (name, colors) ->
            colors.avatars.forEachIndexed { index, avatar ->
                assertAtLeast(contrast(colors.onAvatar, avatar), 4.5, "$name avatar $index")
                assertAtLeast(contrast(avatar, colors.surface), 1.7, "$name avatar $index on page")
            }
        }
    }

    @Test
    fun `every label colour is legible in every theme`() {
        // The sweep `docs/REMAINING.md` said adopting Mailbox.color would need,
        // and the reason the server's vocabulary is tokens rather than hex: nine
        // values times six schemes is fifty-four pairs, and a hex colour picked
        // on the web would have been one fixed answer for all of them.
        //
        // 4.5:1 rather than the 3.0 a non-text mark could get away with, because
        // one of the two uses *is* text: the chip draws the colour as its label
        // at 13sp. The sidebar's 24dp glyph is the easier case and takes the
        // same number rather than a second rule.
        //
        // Both `surface` and `sunken`, because which of the two is the harder
        // background flips with the scheme. A light theme's sunken is darker
        // than its page, so a deep colour has less contrast there; a dark
        // theme's sunken is darker still, so a bright colour has more. Asserting
        // both means neither ramp has to know which way round its scheme is.
        schemes.forEach { (name, colors) ->
            PlMailLabelColor.entries.forEach { token ->
                val color = colors.labelColor(token)

                assertAtLeast(
                    contrast(color, colors.surface),
                    4.5,
                    "$name ${token.wire} on surface",
                )
                assertAtLeast(contrast(color, colors.sunken), 4.5, "$name ${token.wire} on sunken")
            }
        }
    }

    @Test
    fun `label colours are distinguishable from one another`() {
        // Nine chips the user is meant to tell apart. Two that resolve to the
        // same colour would be a picker offering a choice that is not one --
        // and it is an easy mistake to make while tuning a ramp for contrast,
        // because the constraint pushes every value toward the same lightness.
        //
        // Deliberately weak: it asserts no two are *identical*, not that any
        // pair is comfortably apart. Orange and amber are close at the depth AA
        // needs on a cream page and the web has the same two tokens with the
        // same problem; a threshold here would either fail honestly-chosen
        // colours or be low enough to prove nothing.
        schemes.forEach { (name, colors) ->
            val resolved = PlMailLabelColor.entries.map { colors.labelColor(it) }

            assertEquals(
                PlMailLabelColor.entries.size,
                resolved.toSet().size,
                "$name has two label tokens resolving to one colour",
            )
        }
    }

    @Test
    fun `an unknown colour token draws neutral rather than failing`() {
        // A newer server may grow a tenth token. Null means "no colour chosen",
        // which the chip already knows how to draw -- substituting grey would
        // claim the user had chosen grey, and throwing would take the sidebar
        // down over a colour.
        assertEquals(null, PlMailLabelColor.fromWire("chartreuse"))
        assertEquals(null, PlMailLabelColor.fromWire(null))
        assertEquals(PlMailLabelColor.VIOLET, PlMailLabelColor.fromWire("violet"))
    }

    @Test
    fun `the hairline is a hairline rather than a rule`() {
        // Visible but quiet: too strong and forty rows read as a table. The
        // upper bound is as much the point as the lower one.
        schemes.forEach { (name, colors) ->
            val ratio = contrast(colors.line, colors.surface)

            assertTrue(ratio > 1.1, "$name line is invisible against the surface ($ratio)")
            assertTrue(ratio < 2.0, "$name line reads as a rule rather than a hairline ($ratio)")
        }
    }

    @Test
    fun `the app's own neutrals are warm rather than blue-grey`() {
        // The decision the whole palette rests on, and the easiest to undo by
        // accident: a neutral picked from a screenshot or generated by a tool
        // comes back blue, and the app stops looking like itself for a reason
        // nobody can name.
        //
        // Scoped to the two schemes that *are* the app's look. Nord, Dusk and
        // Solar are named palettes somebody chose on purpose -- Nord is
        // literally a published one -- and correcting their blues toward warm
        // would hand back a theme that is not the one asked for. That is not an
        // exemption from the rule; it is the rule applying to the thing it is
        // about, which is what plMail looks like when nobody has chosen.
        mapOf("light" to Palette.Light, "dark" to Palette.Dark).forEach { (name, colors) ->
            listOf("surface" to colors.surface, "ink" to colors.ink, "line" to colors.line)
                .forEach { (label, color) ->
                    assertTrue(
                        color.red >= color.blue,
                        "$name $label is cooler than neutral (r=${color.red}, b=${color.blue})",
                    )
                }
        }
    }

    @Test
    fun `a theme the app does not know resolves to system rather than failing`() {
        // `Appearance` is not exposed yet, and when it is the server will be
        // able to send `paper`, which this app deliberately does not have. A
        // sync must not fail over a colour scheme.
        assertEquals(PlMailThemeChoice.SYSTEM, PlMailThemeChoice.fromWire("paper"))
        assertEquals(PlMailThemeChoice.SYSTEM, PlMailThemeChoice.fromWire(null))
        assertEquals(PlMailThemeChoice.NORD, PlMailThemeChoice.fromWire("nord"))
    }

    @Test
    fun `only system asks what the system is doing`() {
        // The bug this prevents is a picker where choosing Solar on a phone in
        // dark mode gives you Dark: every value except SYSTEM has already
        // answered the question, and consulting the platform again would
        // override the user's own choice with the platform's.
        PlMailThemeChoice.entries
            .filterNot { it == PlMailThemeChoice.SYSTEM }
            .forEach { choice ->
                assertEquals(
                    choice.isDark(systemIsDark = false),
                    choice.isDark(systemIsDark = true),
                    "$choice changed with the system",
                )
                assertEquals(
                    paletteFor(choice, isDark = false),
                    paletteFor(choice, isDark = true),
                    "$choice resolved to a different palette",
                )
            }
    }

    @Test
    fun `reduced transparency is what the pane alpha collapses to`() {
        assertEquals(1f, PlMailSurfaces.Opaque.alpha)
    }

    @Test
    fun `radius applies to panes and never to controls`() {
        // Boxed and flat disagree about panes on purpose and must agree about
        // controls: a theme that rounds buttons produces pill-shaped chips in
        // one layout and square ones in the other, which reads as a bug.
        val flat = radiiFor(PlMailLayout.FLAT)
        val boxed = radiiFor(PlMailLayout.BOXED)

        assertEquals(flat.control, boxed.control)
        assertTrue(boxed.pane > flat.pane)
    }

    @Test
    fun `reduced motion means no duration at all`() {
        assertTrue(PlMailMotion.Reduced.isReduced)
        assertEquals(0, PlMailMotion.Reduced.normal)
        assertTrue(!PlMailMotion.Standard.isReduced)
    }

    @Test
    fun `every density keeps a 48dp touch target`() {
        // Density shrinks padding, never the thing a finger has to hit.
        PlMailDensity.entries.forEach { density ->
            assertEquals(48f, spacingFor(density).touchTarget.value, "$density touch target")
        }
    }

    @Test
    fun `the avatar index is never negative`() {
        // abs(Int.MIN_VALUE) is Int.MIN_VALUE. An address that hashes to it
        // would crash the row it appears in, once in four billion, with a
        // stack trace pointing at a list rather than at a hash.
        val hostile = generateSequence(0) { it + 1 }.map { "user$it@example.org" }.take(5_000)

        (hostile + sequenceOf("", "  ", "ünïcödé@example.org")).forEach { seed ->
            val index = avatarIndex(seed, 8)

            assertTrue(index in 0 until 8, "\"$seed\" produced index $index")
        }
    }

    private fun assertAtLeast(ratio: Double, minimum: Double, what: String) {
        assertTrue(ratio >= minimum, "$what is $ratio:1, below the $minimum:1 floor")
    }

    /** WCAG 2.1 §1.4.3 contrast ratio. */
    private fun contrast(a: Color, b: Color): Double {
        val first = luminance(a)
        val second = luminance(b)
        val lighter = maxOf(first, second)
        val darker = minOf(first, second)

        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun luminance(color: Color): Double {
        fun channel(value: Float): Double {
            val v = value.toDouble()

            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }

        return 0.2126 * channel(color.red) +
            0.7152 * channel(color.green) +
            0.0722 * channel(color.blue)
    }
}
