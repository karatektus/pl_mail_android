package de.plmail.core.designsystem

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The theme, as the app's own tokens rather than as Material's.
 *
 * Two things are provided at once and both matter.
 *
 * [LocalPlMailTheme] is what screens read. It is semantic all the way down — `surface`, `ink`,
 * `accent`, `spacing.large` — so a theme change is a change to one file and no screen is touched.
 *
 * A derived Material [ColorScheme] is provided underneath it, because the app is built out of
 * Material components and a `TextField`, a `Snackbar` or a `TopAppBar` reads `MaterialTheme` and
 * nothing else. Without the bridge, converting a screen would mean replacing every Material
 * component in it; with the bridge, an unconverted screen is *already* wearing the palette and
 * conversion is about spacing and hierarchy rather than about colour. That is what makes it
 * possible to move the whole app over without a flag day.
 *
 * The mapping is deliberately not one-to-one. Material's `primary` is a filled-button colour and
 * this product's accent is meant to be rare, so `primaryContainer` maps to the *soft* tint rather
 * than to the accent itself — otherwise every Material container in the app becomes a green block.
 */
val LocalPlMailTheme =
    staticCompositionLocalOf<PlMailThemeValues> {
        error("PlMailTheme is missing. Wrap the content in PlMailTheme { }.")
    }

object PlMailTheme {

    /**
     * Every group at once.
     *
     * For a composable that reads several of them — a screen touching colours, spacing and radii in
     * the same block reads better holding one value than repeating the CompositionLocal lookup six
     * times.
     */
    val values: PlMailThemeValues
        @Composable @ReadOnlyComposable get() = LocalPlMailTheme.current

    val colors: PlMailColors
        @Composable @ReadOnlyComposable get() = LocalPlMailTheme.current.colors

    val spacing: PlMailSpacing
        @Composable @ReadOnlyComposable get() = LocalPlMailTheme.current.spacing

    val radii: PlMailRadii
        @Composable @ReadOnlyComposable get() = LocalPlMailTheme.current.radii

    val motion: PlMailMotion
        @Composable @ReadOnlyComposable get() = LocalPlMailTheme.current.motion

    val density: PlMailDensity
        @Composable @ReadOnlyComposable get() = LocalPlMailTheme.current.density

    val layout: PlMailLayout
        @Composable @ReadOnlyComposable get() = LocalPlMailTheme.current.layout
}

/**
 * The theme, from one resolved appearance.
 *
 * What the app itself calls. The parameter list below is for tests and screenshots, which vary one
 * axis at a time and would otherwise have to build a whole appearance to change a scheme.
 */
@Composable
fun PlMailTheme(
    appearance: PlMailAppearance,
    reduceMotion: Boolean? = null,
    content: @Composable () -> Unit,
) {
    PlMailTheme(
        theme = appearance.theme,
        layout = appearance.layout,
        density = appearance.density,
        dynamicColor = appearance.dynamicColor,
        reduceMotion = reduceMotion,
        reduceTransparency = appearance.reduceTransparency,
        surfaces = appearance.surfaces,
        content = content,
    )
}

@Composable
fun PlMailTheme(
    theme: PlMailThemeChoice = PlMailThemeChoice.SYSTEM,
    layout: PlMailLayout = PlMailLayout.FLAT,
    density: PlMailDensity = PlMailDensity.COMFORTABLE,
    /**
     * Material You, as a switch beside the theme rather than as a seventh theme.
     *
     * It is a different *kind* of answer: the other six say what the app looks like, and this says
     * "take it from the wallpaper" — which still needs light or dark to be decided, and therefore
     * still needs [theme]. A picker mixing the two would make "Nord" and "from my wallpaper"
     * mutually exclusive for no reason a user could name.
     *
     * No version guard, and that is worth stating rather than leaving to be rediscovered: dynamic
     * colour needs API 31 and this app's `minSdk` **is** 31, so every device that can install it
     * can do this. The guard would be dead code that looks like caution.
     */
    dynamicColor: Boolean = false,
    /**
     * Overrides the reduced-motion check, for tests and screenshots.
     *
     * Null means "ask the system", which is the only correct answer at runtime.
     */
    reduceMotion: Boolean? = null,
    /** Forces panes opaque whatever [surfaces] asks for. See [PlMailSurfaces]. */
    reduceTransparency: Boolean = false,
    surfaces: PlMailSurfaces = PlMailSurfaces.Opaque,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val isDark = theme.isDark(systemIsDark = isSystemInDarkTheme())

    val colors =
        if (dynamicColor) {
            // Remembered on the scheme rather than recomputed every
            // recomposition: reading the dynamic scheme walks the system's
            // colour resources, and this sits above every screen in the app.
            val scheme =
                if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

            remember(scheme, isDark) { scheme.asPlMailColors(isDark) }
        } else {
            paletteFor(theme, isDark)
        }

    val reduced = reduceMotion ?: context.prefersReducedMotion()

    val values =
        PlMailThemeValues(
            colors = colors,
            spacing = spacingFor(density),
            radii = radiiFor(layout),
            motion = if (reduced) PlMailMotion.Reduced else PlMailMotion.Standard,
            density = density,
            layout = layout,
            surfaces = if (reduceTransparency) PlMailSurfaces.Opaque else surfaces,
        )

    CompositionLocalProvider(LocalPlMailTheme provides values) {
        MaterialTheme(
            colorScheme = colors.asMaterialScheme(),
            typography = plMailTypography(),
            shapes = shapesFor(values.radii),
            content = content,
        )
    }
}

/**
 * The palette a choice resolves to.
 *
 * `SYSTEM` is the only value that needs [isDark] told to it; the rest carry their own scheme, which
 * is why choosing Nord on a phone in light mode gives Nord rather than a light approximation of it.
 *
 * Public rather than private so tests can sweep every theme through the same resolver the app uses
 * instead of listing the palettes again and quietly missing one — `PaletteContrastTest` here, and
 * the reader's `MessagePaletteTest`, which checks that a message adapted for the dark lands on the
 * chosen theme's paper rather than on one hardcoded near-black. [Palette] itself stays internal:
 * this is the way in, and it is the only one, so nothing outside can name a colour.
 */
fun paletteFor(theme: PlMailThemeChoice, isDark: Boolean): PlMailColors =
    when (theme) {
        PlMailThemeChoice.NORD -> Palette.Nord
        PlMailThemeChoice.DUSK -> Palette.Dusk
        PlMailThemeChoice.SOLAR -> Palette.Solar
        PlMailThemeChoice.LIGHT -> Palette.Light
        PlMailThemeChoice.DARK -> Palette.Dark
        PlMailThemeChoice.SYSTEM -> if (isDark) Palette.Dark else Palette.Light
    }

/** Three colours that stand for a theme: the page, its ink, its accent. */
@Immutable data class PlMailSwatch(val page: Color, val ink: Color, val accent: Color)

/**
 * What a theme looks like, for a picker.
 *
 * **Sampled from the palette, never painted.** A hand-written swatch is a second copy of a theme's
 * identity that has to be kept in agreement with the first one forever, and the way it fails is a
 * picker offering a colour the theme does not have — which is the one thing a picker exists not to
 * do. plMail's own `Theme::swatch()` is a hardcoded array for exactly this reason, and it is the
 * kind of thing worth not copying.
 *
 * `SYSTEM` returns **two** columns, because it is the only choice that is not one appearance: a
 * single column would show whichever scheme the phone is in and be pixel-identical to Light or
 * Dark, giving no reason to pick it.
 */
fun PlMailThemeChoice.swatch(): List<PlMailSwatch> =
    when (this) {
        PlMailThemeChoice.SYSTEM ->
            listOf(
                paletteFor(this, isDark = false).swatch(),
                paletteFor(this, isDark = true).swatch(),
            )
        else -> listOf(paletteFor(this, isDark(systemIsDark = false)).swatch())
    }

private fun PlMailColors.swatch(): PlMailSwatch =
    PlMailSwatch(page = surface, ink = ink, accent = accent)

/**
 * The wallpaper's colours, expressed as this app's tokens.
 *
 * Mapping the whole token set rather than only the accent is the point. Material You is not "your
 * wallpaper decides the buttons" — it is a tinted set of *neutrals*, and an app that takes the
 * primary and keeps its own greys gets the one part of it that reads as a mismatch.
 *
 * Two mappings are worth explaining because the obvious version is wrong.
 *
 * `sunken` is not `surfaceContainerHighest` in both schemes. Sunken means *inset into the page*,
 * which in a light scheme is darker than the surface and in a dark scheme is darker still — and
 * Material's container ramp runs from dim to bright in light and the other way in dark. Taking one
 * end of it unconditionally produces a search field that is inset in one scheme and raised in the
 * other.
 *
 * `inkSoft` and `inkFaint` are interpolated rather than taken from tokens, because Material has
 * exactly two on-surface tones and this product's hierarchy has four. Blending toward the surface
 * is what a fifth tone would be; the alternative is two pairs of identical-looking steps, which
 * collapses the hierarchy the ink scale exists to carry.
 *
 * **This is the one scheme `PaletteContrastTest` cannot cover**, because the values come from a
 * wallpaper the test has never seen. Material's own tonal spacing is what guarantees the pairs
 * here, which is a reason to map onto its pairs — `onPrimary` on `primary`, `onSurface` on
 * `surface` — rather than to invent combinations it never checked.
 */
private fun ColorScheme.asPlMailColors(isDark: Boolean): PlMailColors =
    PlMailColors(
        isDark = isDark,
        surface = surface,
        raised = if (isDark) surfaceContainer else surfaceContainerLowest,
        sunken = if (isDark) surfaceContainerLowest else surfaceContainerHigh,
        hover = surfaceContainerHighest,
        line = outlineVariant,
        lineStrong = outline,
        ink = onSurface,
        inkSoft = lerp(onSurface, surface, 0.15f),
        inkMuted = onSurfaceVariant,
        inkFaint = lerp(onSurfaceVariant, surface, 0.3f),
        accent = primary,
        accentHover = lerp(primary, onSurface, 0.2f),
        accentSoft = primaryContainer,
        onAccent = onPrimary,
        fieldSurface = if (isDark) surfaceContainer else surfaceContainerLowest,
        fieldLine = outline,
        fieldPlaceholder = onSurfaceVariant,
        danger = error,
        dangerSoft = errorContainer,
        // Material has no warning, success or info role, so these stay the
        // app's own. Deriving them from the wallpaper would mean inventing
        // three hues with no tonal guarantee behind them, and "success" coming
        // out the same colour as "danger" on a red wallpaper is a real
        // outcome rather than a theoretical one.
        warning = if (isDark) Palette.Dark.warning else Palette.Light.warning,
        warningSoft = if (isDark) Palette.Dark.warningSoft else Palette.Light.warningSoft,
        success = if (isDark) Palette.Dark.success else Palette.Light.success,
        successSoft = if (isDark) Palette.Dark.successSoft else Palette.Light.successSoft,
        info = if (isDark) Palette.Dark.info else Palette.Light.info,
        infoSoft = if (isDark) Palette.Dark.infoSoft else Palette.Light.infoSoft,
        inverseSurface = inverseSurface,
        inverseInk = inverseOnSurface,
        inverseAccent = inversePrimary,
    )

/**
 * Whether the system has been asked to keep animation short.
 *
 * `ANIMATOR_DURATION_SCALE` is the setting Android's own "Remove animations" accessibility toggle
 * writes, and reading it is the only way to honour a preference the user has already expressed
 * once. Failures default to *not* reduced: a device that will not answer is far more likely to be
 * an unusual ROM than a user who asked for stillness.
 */
private fun Context.prefersReducedMotion(): Boolean = runCatching {
    Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}
    .getOrDefault(false)

/**
 * The Material scheme the app's components see.
 *
 * `surfaceContainer*` all resolve to real tokens rather than to Material's tonal elevation
 * overlays, which is what stops a Material 3 component quietly tinting itself with a colour that is
 * not in this palette. Elevation is not how this product separates things; a line and a surface
 * shift are.
 */
internal fun PlMailColors.asMaterialScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()

    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentSoft,
        onPrimaryContainer = accent,
        // Secondary is what Material reaches for on chips and selected states.
        // Pointed at the neutral surfaces on purpose: a second colour would
        // undo the decision to have exactly one accent.
        secondary = inkSoft,
        onSecondary = surface,
        secondaryContainer = hover,
        onSecondaryContainer = ink,
        tertiary = accent,
        onTertiary = onAccent,
        tertiaryContainer = accentSoft,
        onTertiaryContainer = accent,
        background = surface,
        onBackground = ink,
        surface = surface,
        onSurface = ink,
        surfaceVariant = sunken,
        onSurfaceVariant = inkMuted,
        surfaceContainerLowest = surface,
        surfaceContainerLow = surface,
        surfaceContainer = raised,
        surfaceContainerHigh = raised,
        surfaceContainerHighest = hover,
        surfaceTint = accent,
        inverseSurface = inverseSurface,
        inverseOnSurface = inverseInk,
        inversePrimary = inverseAccent,
        error = danger,
        onError = if (isDark) Color_onDarkError else Color_onLightError,
        errorContainer = dangerSoft,
        onErrorContainer = danger,
        outline = lineStrong,
        outlineVariant = line,
        scrim = ScrimColor,
    )
}

private val Color_onLightError = androidx.compose.ui.graphics.Color(0xFFFFFFFF)
private val Color_onDarkError = androidx.compose.ui.graphics.Color(0xFF2A0F0D)
private val ScrimColor = androidx.compose.ui.graphics.Color(0x99000000)

/**
 * Type, as a hierarchy built from weight and colour rather than from size.
 *
 * A list row distinguishes sender, subject and preview by making the sender heavier and the preview
 * quieter, at nearly the same size. Doing it with size instead produces a row where the least
 * important line is the smallest and therefore the hardest to read, which is exactly backwards for
 * a preview.
 *
 * Line heights are generous — 1.4 or better on anything that is a sentence. Mail is prose, and
 * prose set at 1.2 reads as a form.
 *
 * **No colour is baked into any style, and that is load-bearing.** A colour on a `TextStyle` beats
 * `LocalContentColor`, so a scale carrying `ink` paints ink everywhere — including inside the
 * components that deliberately invert it. The symptom was a snackbar that rendered as a blank white
 * bar: near-white ink on the near-white inverse surface, with the text present in the semantics
 * tree and simply not visible. Leaving the colour unspecified lets each surface answer for its own
 * contents, which is what `contentColorFor` exists to do.
 */
internal fun plMailTypography(): Typography {
    val base = Typography()
    val family = FontFamily.SansSerif

    fun style(
        size: androidx.compose.ui.unit.TextUnit,
        weight: FontWeight,
        lineHeight: Float,
        letterSpacing: Float = 0f,
    ) =
        TextStyle(
            fontFamily = family,
            fontSize = size,
            fontWeight = weight,
            lineHeight = (size.value * lineHeight).sp,
            letterSpacing = letterSpacing.sp,
        )

    return base.copy(
        headlineLarge = style(TypeScale.display, FontWeight.SemiBold, 1.25f, (-0.5f)),
        headlineMedium = style(TypeScale.title, FontWeight.SemiBold, 1.3f, (-0.3f)),
        titleLarge = style(TypeScale.title, FontWeight.SemiBold, 1.3f, (-0.3f)),
        titleMedium = style(TypeScale.section, FontWeight.Medium, 1.35f),
        titleSmall = style(TypeScale.body, FontWeight.Medium, 1.4f),
        bodyLarge = style(TypeScale.body, FontWeight.Normal, 1.5f),
        bodyMedium = style(TypeScale.secondary, FontWeight.Normal, 1.45f),
        bodySmall = style(TypeScale.label, FontWeight.Normal, 1.4f),
        labelLarge = style(TypeScale.secondary, FontWeight.Medium, 1.3f),
        labelMedium = style(TypeScale.label, FontWeight.Medium, 1.3f),
        labelSmall = style(TypeScale.caption, FontWeight.Medium, 1.3f, 0.2f),
    )
}

/**
 * Material's shape scale, aimed at the pane radius.
 *
 * `extraSmall` and `small` stay at the control radius whatever the layout, because Material uses
 * them for buttons, chips and text fields — see [PlMailRadii].
 */
internal fun shapesFor(radii: PlMailRadii): Shapes =
    Shapes(
        extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(radii.small),
        small = androidx.compose.foundation.shape.RoundedCornerShape(radii.control),
        medium =
            androidx.compose.foundation.shape.RoundedCornerShape(
                if (radii.pane > radii.control) radii.pane else radii.control
            ),
        large = androidx.compose.foundation.shape.RoundedCornerShape(radii.pane),
        extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(radii.pane),
    )
