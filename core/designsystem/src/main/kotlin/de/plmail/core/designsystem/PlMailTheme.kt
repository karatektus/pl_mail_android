package de.plmail.core.designsystem

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
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

@Composable
fun PlMailTheme(
    theme: PlMailThemeChoice = PlMailThemeChoice.SYSTEM,
    layout: PlMailLayout = PlMailLayout.FLAT,
    density: PlMailDensity = PlMailDensity.COMFORTABLE,
    /**
     * Overrides the reduced-motion check, for tests and screenshots.
     *
     * Null means "ask the system", which is the only correct answer at runtime.
     */
    reduceMotion: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val isDark =
        when (theme) {
            PlMailThemeChoice.SYSTEM -> isSystemInDarkTheme()
            PlMailThemeChoice.LIGHT -> false
            PlMailThemeChoice.DARK -> true
        }

    val colors = if (isDark) Palette.Dark else Palette.Light
    val context = LocalContext.current
    val reduced = reduceMotion ?: context.prefersReducedMotion()

    val values =
        PlMailThemeValues(
            colors = colors,
            spacing = spacingFor(density),
            radii = radiiFor(layout),
            motion = if (reduced) PlMailMotion.Reduced else PlMailMotion.Standard,
            density = density,
            layout = layout,
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
