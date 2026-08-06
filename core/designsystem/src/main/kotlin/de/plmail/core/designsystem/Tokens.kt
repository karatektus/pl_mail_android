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

    /**
     * What a label's colour token resolves to in this scheme.
     *
     * The reason the server's vocabulary is tokens rather than hex, made concrete: `blue` is a deep
     * navy on a cream page and a pale sky on a warm near-black, and `#3b82f6` would have been one
     * fixed light-mode blue in both. One ramp per scheme family rather than per theme — Nord, Dusk
     * and Dark share the bright ramp; Light and Solar share the deep one — because what the ramp
     * has to clear is the *page*, and the five schemes differ in hue far more than in how light
     * they are.
     *
     * Every value clears 4.5:1 against both `surface` and `sunken` in every theme, which is one
     * rule covering both of its uses: the chip draws it as text, the sidebar as a 24dp glyph.
     * Pinned by `PaletteContrastTest`, which sweeps nine tokens through six schemes — the sweep
     * `docs/REMAINING.md` said adopting colour would need.
     */
    fun labelColor(color: PlMailLabelColor): Color =
        (if (isDark) Palette.DarkLabels else Palette.LightLabels).getValue(color)
}

/**
 * The colours a label may carry, and plMail's own closed vocabulary.
 *
 * The same nine tokens as `App\Domain\Enum\Mail\LabelColor` on the server, in the same order, with
 * the same wire strings — and this is the only copy of them in the app. `:core:jmap` deliberately
 * carries the raw string uninterpreted, because it is Android-free and cannot turn a token into a
 * colour; the cache stores the raw string for the same reason. So the vocabulary exists once, here,
 * where the resolution happens.
 *
 * [fromWire] returns null for anything it does not know rather than throwing or substituting grey.
 * A newer server may grow a tenth token, and a chip that draws neutral is a chip that still says
 * the label's name — where a substituted grey would claim the user had chosen grey, and a throw
 * would take the sidebar down over a colour.
 */
enum class PlMailLabelColor(val wire: String) {
    GRAY("gray"),
    RED("red"),
    ORANGE("orange"),
    AMBER("amber"),
    GREEN("green"),
    TEAL("teal"),
    BLUE("blue"),
    VIOLET("violet"),
    PINK("pink");

    companion object {
        fun fromWire(value: String?): PlMailLabelColor? = entries.firstOrNull { it.wire == value }
    }
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
 * **Radius applies to panes, not controls.** A sheet, a card and a section of a page take [pane],
 * which a theme may make sharp or generous; a button, a chip and a field keep [control] whatever
 * the theme says. Letting the theme round controls too produces either pill-shaped cards or square
 * buttons, and both look like a mistake rather than a choice.
 */
@Immutable
data class PlMailRadii(
    val pane: Dp,
    val control: Dp,
    val small: Dp,
    /**
     * For anything that floats over the app rather than sitting in it — the compose button, and the
     * composer itself where the window is wide enough to present it as a dialog.
     *
     * Fixed, and larger than [control], because a 56dp square with a 10dp radius reads as a
     * misplaced card rather than as a button. It is deliberately not [pane]: the flat layout sets
     * that to zero, which is right for a pane *in* the page — the flat look separates with
     * hairlines instead of boxes — and wrong for something with a scrim behind it, where a square
     * edge reads as a window that failed to size itself rather than as a deliberate shape.
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

/**
 * How tightly the app packs.
 *
 * The three are plMail's own `Density` — comfortable, cosy, compact — in its order, loosest first,
 * and that is a change from the first version rather than a coincidence. This enum used to be
 * compact / comfortable / spacious, which put the default in the middle and offered a step the
 * server has no name for; a "spacious" the web can never send is a setting that would silently
 * vanish the first time `Appearance` synced. Comfortable is the loosest here for the same reason it
 * is there.
 */
enum class PlMailDensity(val wire: String, internal val scale: Float, internal val rowHeight: Dp) {
    COMFORTABLE("comfortable", 1f, 76.dp),
    COSY("cosy", 0.85f, 68.dp),
    COMPACT("compact", 0.72f, 60.dp);

    companion object {
        fun fromWire(value: String?): PlMailDensity =
            entries.firstOrNull { it.wire == value } ?: COMFORTABLE
    }
}

/**
 * Flat or boxed, the product's second appearance axis.
 *
 * Flat separates with hairlines on the page itself; boxed lifts content onto [PlMailColors.raised]
 * panes with a radius. Both are supported by the same tokens, which is the point — a screen asks
 * for "the container a list row lives in" and the layout decides what that is.
 *
 * Flat first, matching the web's dropdown order and its default.
 */
enum class PlMailLayout(val wire: String) {
    FLAT("flat"),
    BOXED("boxed");

    companion object {
        fun fromWire(value: String?): PlMailLayout =
            entries.firstOrNull { it.wire == value } ?: FLAT
    }
}

/**
 * Which colours to resolve.
 *
 * The names and the order are plMail's own `App\Domain\Enum\Theme\Theme`, so a value that arrives
 * over the wire maps by name and nothing has to be translated. The server carries one more,
 * `paper`; it is not here because the app's own light scheme is already the warm sheet paper exists
 * to be, and two near-identical creams in one picker is a choice nobody can make.
 *
 * **`paper` therefore resolves to [LIGHT], and that is a decision rather than a fallback.** It used
 * to fall through to [SYSTEM] along with every unknown value, which was wrong in a way that only
 * showed once appearance actually synced: somebody who chose a light theme on the web got a phone
 * that went dark at sunset. An unknown value is still [SYSTEM] — a theme a future server adds is a
 * theme this build genuinely has no opinion about — but `paper` is one this build knows and has an
 * answer for.
 *
 * Nothing here can produce the string `paper` again, which is the other half of the decision: the
 * app writes back only the property the user touched, so a `paper` it renders as Light survives
 * every write except an explicit choice of theme. See `AppearanceRepository`.
 */
enum class PlMailThemeChoice(val wire: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    NORD("nord"),
    DUSK("dusk"),
    SOLAR("solar");

    /**
     * Whether this theme is a dark one.
     *
     * [systemIsDark] is only consulted by [SYSTEM]; every other value has already decided, which is
     * the point of choosing one. Taken as a parameter rather than read here so the resolution is
     * testable without a Compose composition.
     */
    fun isDark(systemIsDark: Boolean): Boolean =
        when (this) {
            SYSTEM -> systemIsDark
            LIGHT,
            SOLAR -> false
            DARK,
            NORD,
            DUSK -> true
        }

    companion object {
        /**
         * The server's seventh theme, which this app renders as [LIGHT] and never writes back.
         *
         * Named rather than inlined because two places have to agree about it: the resolver below,
         * and whatever decides that a write must not flatten it.
         */
        const val PAPER_WIRE = "paper"

        fun fromWire(value: String?): PlMailThemeChoice =
            when (value) {
                PAPER_WIRE -> LIGHT
                else -> entries.firstOrNull { it.wire == value } ?: SYSTEM
            }
    }
}

/**
 * How solid a pane is, and the app's answer to the web's `--pane-alpha` / `--pane-blur` knobs.
 *
 * Only [alpha] is real here. Blur is deliberately absent: Compose can blur a composable's *own*
 * content and has no backdrop blur, so a "frosted" pane would blur the text written on it rather
 * than the list behind it — the opposite of what the knob means. Adding a token that could only be
 * implemented wrongly would be worse than not having it, so this carries the one value that can be
 * honoured and `docs/PLAN.md` records why the other is missing.
 *
 * Both collapse to opaque when the user asks for reduced transparency, which on Android has to be
 * an app setting: `Settings.Secure` has no reduce-transparency constant to read — checked against
 * the API 37 stubs — unlike reduced motion, which the platform does express.
 */
@Immutable
@JvmInline
value class PlMailSurfaces(val alpha: Float) {
    companion object {
        /** Opaque. What the product ships as, and what reduced transparency forces. */
        val Opaque = PlMailSurfaces(1f)
    }
}

/**
 * The whole appearance choice, resolved.
 *
 * The one place the stored strings become types, and the one place a future `Appearance` from the
 * server will land — which is why [of] takes loose nullable strings rather than enums. The plan
 * promises that swap touches the resolver and nothing else, and this is the resolver.
 *
 * Every field falls back rather than failing. A theme name this build does not know, a density the
 * web added last week, a preferences file written by a newer version: all of them resolve to the
 * default. Appearance is the one part of an app that must never be able to stop it starting.
 */
@Immutable
data class PlMailAppearance(
    val theme: PlMailThemeChoice = PlMailThemeChoice.SYSTEM,
    val layout: PlMailLayout = PlMailLayout.FLAT,
    val density: PlMailDensity = PlMailDensity.COMFORTABLE,
    val dynamicColor: Boolean = false,
    val reduceTransparency: Boolean = false,
    val surfaces: PlMailSurfaces = PlMailSurfaces.Opaque,
) {
    companion object {
        /**
         * The floor on pane translucency, and the reason it is not zero.
         *
         * Below about half, text on a pane is read against whatever is behind it rather than
         * against the pane, and none of the contrast the palette guarantees still holds. A slider
         * reaching zero can make the app unreadable and then hide the screen that would undo it.
         */
        const val MIN_PANE_ALPHA = 0.5f

        fun of(
            theme: String?,
            layout: String?,
            density: String?,
            dynamicColor: Boolean,
            reduceTransparency: Boolean,
            paneAlpha: String?,
        ): PlMailAppearance =
            PlMailAppearance(
                theme = PlMailThemeChoice.fromWire(theme),
                layout = PlMailLayout.fromWire(layout),
                density = PlMailDensity.fromWire(density),
                dynamicColor = dynamicColor,
                reduceTransparency = reduceTransparency,
                surfaces =
                    PlMailSurfaces(paneAlpha?.toFloatOrNull()?.coerceIn(MIN_PANE_ALPHA, 1f) ?: 1f),
            )
    }
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
    val surfaces: PlMailSurfaces = PlMailSurfaces.Opaque,
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
