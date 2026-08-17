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
 * The typeface the *interface* is drawn in, and never the one a message is composed in.
 *
 * The server's own `FontFamily`, by name and in its order. The two are a pair of settings that
 * share a word and nothing else: the composer's font picker writes `font-family` into the message
 * HTML and travels to the recipient, and this one never leaves the app's chrome.
 *
 * **Every value is a face the device already has.** Nothing here downloads a webfont — a phone on a
 * plane has to be able to draw its own settings screen — which is why the list is four rather than
 * forty, and why [GROTESK] is honest about being Android's own sans rather than the Helvetica the
 * web's stack asks for first. That substitution is the whole difference between the two platforms
 * here and it is not worth a warning: a grotesk is a grotesk.
 */
enum class PlMailFontFamily(val wire: String) {
    SYSTEM("system"),
    GROTESK("grotesk"),
    SERIF("serif"),
    MONOSPACE("monospace");

    companion object {
        fun fromWire(value: String?): PlMailFontFamily =
            entries.firstOrNull { it.wire == value } ?: SYSTEM
    }
}

/**
 * How loudly the list says a row is unread.
 *
 * The server's `UnreadEmphasis`, and **the bold weight is not part of it at any setting** — that is
 * the signal which survives a colour-blind reader and a photograph behind a translucent pane, so it
 * stays. What varies there is a tint behind the row (a multiplier of 0, 1 and 1.6 on the theme's
 * own unread alpha) and an accent bar down the leading edge, 3px at [STRONG] and absent otherwise.
 *
 * **Android's three settings are deliberately not symmetric about the middle one, and that
 * asymmetry is forced.** This row has never had a tint — unread here is carried by weight *and* a
 * step up the ink scale, which is a different mechanism the web does not use — so there is no `1.0`
 * tint for [STANDARD] to be the identity of. What [STANDARD] is the identity of is today's
 * rendering, exactly and pixel for pixel, because an existing install must not change appearance
 * the day this setting arrives. That leaves [STRONG] to add something (the bar, plus a neutral lift
 * toward `raised` that stands in for the web's tint) and [SUBTLE] to take something away — and the
 * only thing available to take is the ink promotion, since the weight is not on the table. So
 * Subtle is a read row's colours at an unread row's weight, which is quieter than Standard without
 * being silent.
 */
enum class PlMailUnreadEmphasis(val wire: String) {
    SUBTLE("subtle"),
    STANDARD("standard"),
    STRONG("strong");

    companion object {
        fun fromWire(value: String?): PlMailUnreadEmphasis =
            entries.firstOrNull { it.wire == value } ?: STANDARD
    }
}

/**
 * The three surfaces that may pack at their own density.
 *
 * Named after what the user is looking at rather than after a composable, because that is what the
 * setting says: the folder list, the message list, the message itself. Which composable draws each
 * of them is `feature/mail`'s business and changes; the surfaces do not.
 */
enum class PlMailSurfaceKind {
    SIDEBAR,
    LIST,
    READING,
}

/**
 * How the conversation list draws a row, as the four settings that are about the list and not about
 * the app.
 *
 * Grouped rather than spread across [PlMailThemeValues] because `ThreadRow` reads all four together
 * and nothing else reads any of them — and because a row is the one composable in this app measured
 * fifty at a time, where one lookup beating four is worth the type.
 *
 * [previewLines] is 0, 1 or 2: none, one clipped line, two wrapped ones. Zero does not remove the
 * line the preview shares with the label chips — the chips keep it, and the row keeps its height,
 * which is the property `ThreadRowLayoutTest` exists to hold.
 */
@Immutable
data class PlMailListStyle(
    val avatars: Boolean = true,
    val previewLines: Int = 1,
    val unreadEmphasis: PlMailUnreadEmphasis = PlMailUnreadEmphasis.STANDARD,
    /**
     * Whether a row may mark which account it arrived in.
     *
     * A permission rather than an instruction: the mark only means anything in a list showing more
     * than one account at once, and the row cannot know whether it is in one. See `ThreadRow`.
     */
    val accountCorner: Boolean = true,
)

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
    val syncWithServer: Boolean = true,
    val fontFamily: PlMailFontFamily = PlMailFontFamily.SYSTEM,
    val fontScale: Float = 1f,
    val list: PlMailListStyle = PlMailListStyle(),
    /**
     * A surface's own density, or null to follow [density].
     *
     * Null is the value the server holds and not an absence, which is why these are nullable enums
     * rather than being defaulted to [density] here: the appearance screen has to be able to draw
     * "Follow the overall density" as the selected option, and a resolver that had already
     * substituted the global one could not tell that state from a deliberate match.
     */
    val sidebarDensity: PlMailDensity? = null,
    val listDensity: PlMailDensity? = null,
    val readingDensity: PlMailDensity? = null,
) {
    /** What a surface actually packs at: its own answer, or the app-wide one. */
    fun densityFor(kind: PlMailSurfaceKind): PlMailDensity =
        when (kind) {
            PlMailSurfaceKind.SIDEBAR -> sidebarDensity
            PlMailSurfaceKind.LIST -> listDensity
            PlMailSurfaceKind.READING -> readingDensity
        } ?: density

    companion object {
        /**
         * The floor on pane translucency, and the reason it is not zero.
         *
         * Below about half, text on a pane is read against whatever is behind it rather than
         * against the pane, and none of the contrast the palette guarantees still holds. A slider
         * reaching zero can make the app unreadable and then hide the screen that would undo it.
         */
        const val MIN_PANE_ALPHA = 0.5f

        /**
         * The bounds on the app's own type scale, matching the server's published `fontScale`
         * range.
         *
         * This multiplies whatever the user has already set system-wide rather than replacing it,
         * so the two compound: a phone at 130% with this at 150% is a list row that can no longer
         * hold a subject. Android's own accessibility setting is the one meant to go large; this
         * exists to close the gap between it and the browser.
         */
        const val MIN_FONT_SCALE = 0.875f

        const val MAX_FONT_SCALE = 1.25f

        const val MIN_PREVIEW_LINES = 0

        const val MAX_PREVIEW_LINES = 2

        @Suppress("LongParameterList")
        fun of(
            theme: String?,
            layout: String?,
            density: String?,
            dynamicColor: Boolean,
            reduceTransparency: Boolean,
            paneAlpha: String?,
            syncWithServer: Boolean = true,
            accountCorner: Boolean? = null,
            listAvatars: Boolean? = null,
            previewLines: Int? = null,
            unreadEmphasis: String? = null,
            fontFamily: String? = null,
            fontScale: Float? = null,
            sidebarDensity: String? = null,
            listDensity: String? = null,
            readingDensity: String? = null,
        ): PlMailAppearance =
            PlMailAppearance(
                theme = PlMailThemeChoice.fromWire(theme),
                layout = PlMailLayout.fromWire(layout),
                density = PlMailDensity.fromWire(density),
                dynamicColor = dynamicColor,
                reduceTransparency = reduceTransparency,
                surfaces =
                    PlMailSurfaces(paneAlpha?.toFloatOrNull()?.coerceIn(MIN_PANE_ALPHA, 1f) ?: 1f),
                syncWithServer = syncWithServer,
                fontFamily = PlMailFontFamily.fromWire(fontFamily),
                // Clamped again here, after the store and before the server. Not
                // belt and braces: this resolver is also what a first paint from
                // the session hint goes through, so it sees numbers no local
                // control has ever bounded.
                fontScale = fontScale?.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE) ?: 1f,
                list =
                    PlMailListStyle(
                        avatars = listAvatars ?: true,
                        previewLines =
                            previewLines?.coerceIn(MIN_PREVIEW_LINES, MAX_PREVIEW_LINES) ?: 1,
                        unreadEmphasis = PlMailUnreadEmphasis.fromWire(unreadEmphasis),
                        accountCorner = accountCorner ?: true,
                    ),
                // `fromWire` is not reachable here, and that is the point: it
                // folds an unknown value into COMFORTABLE, which would turn "this
                // surface follows the overall density" into "this surface is
                // pinned to Comfortable" -- the one per-surface state that is not
                // an override, silently becoming one.
                sidebarDensity = sidebarDensity?.let(::densityOrNull),
                listDensity = listDensity?.let(::densityOrNull),
                readingDensity = readingDensity?.let(::densityOrNull),
            )

        private fun densityOrNull(wire: String): PlMailDensity? =
            PlMailDensity.entries.firstOrNull { it.wire == wire }
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
    val list: PlMailListStyle = PlMailListStyle(),
    /**
     * The per-surface densities, carried here rather than resolved away.
     *
     * [PlMailSurface] is what turns one of them into the [spacing] a subtree sees, and it needs the
     * unresolved answer to know whether there is anything to change.
     */
    val sidebarDensity: PlMailDensity? = null,
    val listDensity: PlMailDensity? = null,
    val readingDensity: PlMailDensity? = null,
) {
    /** See [PlMailAppearance.densityFor]. */
    fun densityFor(kind: PlMailSurfaceKind): PlMailDensity =
        when (kind) {
            PlMailSurfaceKind.SIDEBAR -> sidebarDensity
            PlMailSurfaceKind.LIST -> listDensity
            PlMailSurfaceKind.READING -> readingDensity
        } ?: density
}

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
