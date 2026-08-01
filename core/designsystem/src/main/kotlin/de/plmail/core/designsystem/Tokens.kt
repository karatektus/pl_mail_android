package de.plmail.core.designsystem

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The colours a screen is allowed to know about.
 *
 * Semantic, never descriptive: `line`, not `grey200`. A screen that asks for "the hairline between
 * rows" keeps working when a theme decides that hairline is warmer, lighter or absent; a screen
 * that asks for grey200 has to be found and edited, and the one nobody finds is the bug.
 *
 * The ink scale is four steps and they are a **hierarchy**, not shades. `ink` is what the message
 * is, `inkSoft` is what it is about, `inkMuted` is metadata, `inkFaint` is furniture. Choosing by
 * meaning rather than by how dark it should look is what keeps a list legible when the theme
 * changes underneath it.
 */
@Immutable
data class PlMailColors(
    val isDark: Boolean,
    /** The page. Everything sits on this unless it is deliberately lifted or inset. */
    val surface: Color,
    /** Lifted above the page — a card, a sheet, a row that is its own object. */
    val raised: Color,
    /** Inset into the page — a quoted block, a code sample, a search field's well. */
    val sunken: Color,
    /** Pressed, hovered, or selected without being an accent. */
    val hover: Color,
    /** The hairline. This product separates with a line and a surface shift, never a shadow. */
    val line: Color,
    /** A line that has to be seen — a field's border, a divider carrying structure. */
    val lineStrong: Color,
    val ink: Color,
    val inkSoft: Color,
    val inkMuted: Color,
    val inkFaint: Color,
    /** The one accent. Rare by design: an active item, a primary action, a link. */
    val accent: Color,
    val accentHover: Color,
    /** A tint, for the few backgrounds that must read as accented without being filled. */
    val accentSoft: Color,
    val onAccent: Color,
    val fieldSurface: Color,
    val fieldLine: Color,
    val fieldPlaceholder: Color,
    val danger: Color,
    val dangerSoft: Color,
    val warning: Color,
    val warningSoft: Color,
    val success: Color,
    val successSoft: Color,
    val info: Color,
    val infoSoft: Color,
    /** For things that sit *over* the app: snackbars, tooltips. */
    val inverseSurface: Color,
    val inverseInk: Color,
    val inverseAccent: Color,
) {
    /**
     * The avatar ramp for this scheme. Indexed by a hash of an address, never of a display name.
     */
    val avatars: List<Color>
        get() = if (isDark) Palette.DarkAvatars else Palette.LightAvatars

    /**
     * The initial drawn on an avatar.
     *
     * It flips with the scheme, because the ramps do. The light ramp is deep enough to take white;
     * the dark ramp is bright, because a dark circle on a dark page disappears — so its label has
     * to be dark. Hardcoding white was the first version and it produced eight unreadable avatars
     * in dark mode, which reads as a rendering bug rather than a colour choice.
     */
    val onAvatar: Color
        get() = if (isDark) Palette.DarkAvatarInk else Palette.LightAvatarInk
}

/**
 * Spacing, as a scale rather than as numbers at call sites.
 *
 * Scaled by the chosen [PlMailDensity], which is why a screen must never write `16.dp` for a gap: a
 * hardcoded gap does not respond to the density setting, so a compact layout ends up compact
 * everywhere except the three places somebody typed a number.
 */
@Immutable
data class PlMailSpacing(
    val hair: Dp,
    val tiny: Dp,
    val small: Dp,
    val medium: Dp,
    val large: Dp,
    val xLarge: Dp,
    val xxLarge: Dp,
    /** The horizontal inset content keeps from the edge of the screen. */
    val gutter: Dp,
    /** The smallest a tappable thing may be. Never scaled below this, whatever the density. */
    val touchTarget: Dp = 48.dp,
)

/**
 * Corner radii.
 *
 * **Radius applies to panes, not controls.** A sheet, a card and a dialog take [pane], which a
 * theme may make sharp or generous; a button, a chip and a field keep [control] whatever the theme
 * says. Letting the theme round controls too produces either pill-shaped cards or square buttons,
 * and both look like a mistake rather than a choice.
 */
@Immutable
data class PlMailRadii(
    val pane: Dp,
    val control: Dp,
    val small: Dp,
    /**
     * For the one control that floats over content.
     *
     * Fixed, and larger than [control], because a 56dp square with a 10dp radius reads as a
     * misplaced card rather than as a button. It is deliberately not [pane]: the flat layout sets
     * that to zero, and a square floating button is exactly what this avoids.
     */
    val floating: Dp,
)

/**
 * Durations and easing.
 *
 * Short and purposeful: nothing here overshoots, and [instant] is what every duration collapses to
 * when the system reports that animations are turned off. Honouring that is not politeness — for
 * some people motion is the difference between usable and unusable, and Android already asks them.
 */
@Immutable
data class PlMailMotion(val fast: Int, val normal: Int, val slow: Int, val easing: Easing) {

    val isReduced: Boolean
        get() = fast == 0

    companion object {
        val Standard = PlMailMotion(fast = 120, normal = 200, slow = 320, easing = EmphasisEasing)

        /** Every duration zero. Transitions still happen; they just arrive already finished. */
        val Reduced = PlMailMotion(fast = 0, normal = 0, slow = 0, easing = EmphasisEasing)
    }
}

/** A restrained ease-out. Quick to start, settles without bouncing. */
private val EmphasisEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** How tightly the app packs. Chosen by the user in settings; M10 wires the choice. */
enum class PlMailDensity(internal val scale: Float, internal val rowHeight: Dp) {
    COMPACT(0.85f, 64.dp),
    COMFORTABLE(1f, 76.dp),
    SPACIOUS(1.2f, 88.dp),
}

/**
 * Flat or boxed, the product's second appearance axis.
 *
 * Flat separates with hairlines on the page itself; boxed lifts content onto [PlMailColors.raised]
 * panes with a radius. Both are supported by the same tokens, which is the point — a screen asks
 * for "the container a list row lives in" and the layout decides what that is.
 */
enum class PlMailLayout {
    FLAT,
    BOXED,
}

/** Which colours to resolve. The other four the plan names arrive with M10's settings screen. */
enum class PlMailThemeChoice {
    SYSTEM,
    LIGHT,
    DARK,
}

/** Everything a screen can ask the theme for. */
@Immutable
data class PlMailThemeValues(
    val colors: PlMailColors,
    val spacing: PlMailSpacing,
    val radii: PlMailRadii,
    val motion: PlMailMotion,
    val density: PlMailDensity,
    val layout: PlMailLayout,
)

internal fun spacingFor(density: PlMailDensity): PlMailSpacing {
    val scale = density.scale

    return PlMailSpacing(
        hair = 1.dp,
        tiny = (4 * scale).dp,
        small = (8 * scale).dp,
        medium = (12 * scale).dp,
        large = (16 * scale).dp,
        xLarge = (24 * scale).dp,
        xxLarge = (32 * scale).dp,
        // The gutter scales less than the rest: content pinned to the edge of a
        // phone reads as broken however compact the user asked for, and 20dp is
        // already the floor rather than a preference.
        gutter = (20 * (1f + (scale - 1f) / 2f)).dp,
    )
}

internal fun radiiFor(layout: PlMailLayout): PlMailRadii =
    PlMailRadii(
        pane = if (layout == PlMailLayout.BOXED) 16.dp else 0.dp,
        // Fixed, in both layouts. See the type's own note.
        control = 10.dp,
        small = 6.dp,
        floating = 18.dp,
    )

/** Font sizes, kept here so "body is at least 16sp" is one number rather than a habit. */
internal object TypeScale {
    val display = 30.sp
    val title = 22.sp
    val section = 17.sp
    val body = 16.sp
    val secondary = 15.sp
    val label = 13.sp
    val caption = 12.sp
}
