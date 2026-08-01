package de.plmail.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.plmail.core.designsystem.PaneTone
import de.plmail.core.designsystem.PlMailAppearance
import de.plmail.core.designsystem.PlMailDensity
import de.plmail.core.designsystem.PlMailLayout
import de.plmail.core.designsystem.PlMailPane
import de.plmail.core.designsystem.PlMailTheme
import de.plmail.core.designsystem.PlMailThemeChoice
import de.plmail.core.designsystem.swatch

/**
 * What the app looks like.
 *
 * **The screen is its own preview.** Every control writes immediately and the whole app — including
 * this screen — re-themes under the finger, so there is no apply button, no staging state and no
 * "that is not what it looked like in the picker". It is also the reason the theme swatches are
 * drawn from the palettes rather than from static colours: a swatch that is painted rather than
 * sampled is a swatch that can be wrong about the theme it offers.
 *
 * Two axes and three knobs, in the order somebody actually decides them: the palette first, then
 * how it is painted, then how tightly it packs, then the accessibility overrides that outrank all
 * of it. The overrides sit last and are phrased as what they *do* rather than as what they are for
 * — "Panes stay solid" rather than "reduce transparency" — because the person who needs one is
 * looking for the effect.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(onBack: () -> Unit, viewModel: AppearanceViewModel = hiltViewModel()) {
    val appearance by viewModel.appearance.collectAsStateWithLifecycle()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = PlMailTheme.colors.surface,
        topBar = {
            TopAppBar(
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = PlMailTheme.colors.surface,
                        scrolledContainerColor = PlMailTheme.colors.surface,
                        titleContentColor = PlMailTheme.colors.ink,
                        navigationIconContentColor = PlMailTheme.colors.inkSoft,
                        actionIconContentColor = PlMailTheme.colors.inkSoft,
                    ),
                title = { Text(stringResource(R.string.appearance_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { insets ->
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .padding(insets)
                    .verticalScroll(rememberScrollState())
                    .padding(
                        horizontal = PlMailTheme.spacing.gutter,
                        vertical = PlMailTheme.spacing.medium,
                    ),
            verticalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.large),
        ) {
            Themes(appearance, onChoose = viewModel::choose)
            DynamicColour(appearance, onChange = viewModel::setDynamicColor)
            Layouts(appearance, onChoose = viewModel::choose)
            Densities(appearance, onChoose = viewModel::choose)
            Accessibility(
                appearance = appearance,
                onReduceTransparency = viewModel::setReduceTransparency,
                onPaneAlpha = viewModel::setPaneAlpha,
            )
        }
    }
}

@Composable
private fun Themes(appearance: PlMailAppearance, onChoose: (PlMailThemeChoice) -> Unit) {
    Section(stringResource(R.string.appearance_theme)) {
        Column(
            // One group for the screen reader, so it announces "2 of 6" rather
            // than six unrelated buttons that happen to be near each other.
            modifier = Modifier.selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.tiny),
        ) {
            PlMailThemeChoice.entries.forEach { choice ->
                ThemeRow(
                    choice = choice,
                    isChosen = choice == appearance.theme,
                    // Greyed rather than hidden while the wallpaper is
                    // driving the colours: the choice still matters, because
                    // dynamic colour needs light or dark decided and takes it
                    // from here. Hiding the list would make "System" look like
                    // something Material You had replaced.
                    isDimmed = appearance.dynamicColor,
                    onChoose = { onChoose(choice) },
                )
            }
        }
    }
}

@Composable
private fun ThemeRow(
    choice: PlMailThemeChoice,
    isChosen: Boolean,
    isDimmed: Boolean,
    onChoose: () -> Unit,
) {
    val theme = PlMailTheme.values
    val name = stringResource(choice.label())

    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(theme.radii.control))
                .clickable(role = Role.RadioButton, onClick = onChoose)
                .background(if (isChosen) theme.colors.accentSoft else theme.colors.surface)
                .border(
                    width = theme.spacing.hair,
                    color = if (isChosen) theme.colors.accent else theme.colors.line,
                    shape = RoundedCornerShape(theme.radii.control),
                )
                .padding(theme.spacing.small)
                .semantics { contentDescription = name },
        horizontalArrangement = Arrangement.spacedBy(theme.spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Swatch(choice = choice, isDimmed = isDimmed)

        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (isChosen) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isChosen) theme.colors.ink else theme.colors.inkSoft,
        )
    }
}

/**
 * Three dots of the theme it offers: page, ink, accent.
 *
 * Sampled from the palette rather than painted, so a swatch cannot disagree with the theme behind
 * it. `System` is drawn as its two schemes side by side, because that is what it is — a swatch
 * showing only the current one would look identical to Light or Dark and offer no reason to pick
 * it.
 */
@Composable
private fun Swatch(choice: PlMailThemeChoice, isDimmed: Boolean) {
    val theme = PlMailTheme.values
    val colors = choice.swatch()

    Row(
        modifier =
            Modifier.clip(RoundedCornerShape(theme.radii.control))
                // `lineStrong`, not `line`. The hairline is tuned to be barely
                // visible against the page, which is right between list rows and
                // wrong here: a light theme's swatch *is* nearly the page
                // colour, so without a border it reads as two dots floating in
                // the row rather than as a sample of a surface.
                .border(
                    width = theme.spacing.hair,
                    color = theme.colors.lineStrong,
                    shape = RoundedCornerShape(theme.radii.control),
                )
    ) {
        colors.forEach { column ->
            Column(
                modifier =
                    Modifier.size(width = SWATCH_WIDTH, height = SWATCH_HEIGHT)
                        .background(if (isDimmed) column.page.dim() else column.page),
                verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Dot(if (isDimmed) column.ink.dim() else column.ink)
                Dot(if (isDimmed) column.accent.dim() else column.accent)
            }
        }
    }
}

@Composable
private fun Dot(color: Color) {
    Box(modifier = Modifier.size(9.dp).background(color, CircleShape))
}

/**
 * Big enough that the page colour is the thing you see first.
 *
 * The first version was the two dots and their padding, about 18dp square, and on a light theme the
 * page it was sampling was within a couple of points of the row behind it — so Light, Solar and
 * System all read as dots on the settings screen's own background rather than as three different
 * papers. Half the information in a swatch is the surface.
 */
private val SWATCH_WIDTH = 26.dp

private val SWATCH_HEIGHT = 34.dp

/** Halfway to invisible, for a control that is still the answer to something and not in charge. */
private fun Color.dim(): Color = copy(alpha = 0.35f)

@Composable
private fun DynamicColour(appearance: PlMailAppearance, onChange: (Boolean) -> Unit) {
    Toggle(
        title = stringResource(R.string.appearance_dynamic),
        body = stringResource(R.string.appearance_dynamic_body),
        isOn = appearance.dynamicColor,
        onChange = onChange,
    )
}

@Composable
private fun Layouts(appearance: PlMailAppearance, onChoose: (PlMailLayout) -> Unit) {
    Section(stringResource(R.string.appearance_layout)) {
        Choices(
            options = PlMailLayout.entries,
            chosen = appearance.layout,
            label = { stringResource(it.label()) },
            onChoose = onChoose,
        )

        Text(
            text = stringResource(R.string.appearance_layout_body),
            style = MaterialTheme.typography.bodySmall,
            color = PlMailTheme.colors.inkMuted,
        )
    }
}

@Composable
private fun Densities(appearance: PlMailAppearance, onChoose: (PlMailDensity) -> Unit) {
    Section(stringResource(R.string.appearance_density)) {
        Choices(
            options = PlMailDensity.entries,
            chosen = appearance.density,
            label = { stringResource(it.label()) },
            onChoose = onChoose,
        )
    }
}

@Composable
private fun Accessibility(
    appearance: PlMailAppearance,
    onReduceTransparency: (Boolean) -> Unit,
    onPaneAlpha: (Float) -> Unit,
) {
    Section(stringResource(R.string.appearance_accessibility)) {
        Toggle(
            title = stringResource(R.string.appearance_solid_panes),
            // Says what Android cannot: there is no system reduce-transparency
            // setting to inherit, unlike reduced motion, which the app already
            // honours without asking. So this switch exists and the motion one
            // does not, and the body explains that rather than leaving it as an
            // apparent inconsistency.
            body = stringResource(R.string.appearance_solid_panes_body),
            isOn = appearance.reduceTransparency,
            onChange = onReduceTransparency,
        )

        // Only where it does something. Pane translucency applies to the boxed
        // layout, because flat has no pane to see through -- a slider that
        // moves and changes nothing is worse than one that is not there.
        if (appearance.layout == PlMailLayout.BOXED && !appearance.reduceTransparency) {
            PaneAlpha(appearance, onPaneAlpha)
        }
    }
}

@Composable
private fun PaneAlpha(appearance: PlMailAppearance, onChange: (Float) -> Unit) {
    val theme = PlMailTheme.values

    // Local while dragging, committed on release. A Slider emits continuously
    // and every commit rewrites the whole preferences file -- the same file
    // holding the credential and the push subscription id. One drag would
    // otherwise be several hundred writes.
    var dragging by
        remember(appearance.surfaces.alpha) {
            mutableFloatStateOf(appearance.surfaces.alpha)
        }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.appearance_pane_alpha),
                style = MaterialTheme.typography.bodyMedium,
                color = theme.colors.inkSoft,
            )
            Text(
                text = "${(dragging * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = theme.colors.inkMuted,
            )
        }

        Slider(
            value = dragging,
            onValueChange = { dragging = it },
            onValueChangeFinished = { onChange(dragging) },
            valueRange = PlMailAppearance.MIN_PANE_ALPHA..1f,
            colors =
                SliderDefaults.colors(
                    thumbColor = theme.colors.accent,
                    activeTrackColor = theme.colors.accent,
                    inactiveTrackColor = theme.colors.line,
                ),
        )

        PlMailPane(modifier = Modifier.fillMaxWidth(), tone = PaneTone.RAISED) {
            Text(
                text = stringResource(R.string.appearance_pane_sample),
                style = MaterialTheme.typography.bodySmall,
                color = theme.colors.inkMuted,
                modifier = Modifier.padding(theme.spacing.small),
            )
        }
    }
}

/**
 * A row of mutually exclusive options.
 *
 * Not Material's `SegmentedButton`: it takes its shape from the shape scale, which in this design
 * system is the *pane* radius, so a boxed layout would round a control the tokens say must never be
 * rounded. Written out instead, against `radii.control`, which is the rule rather than an exception
 * to it.
 */
@Composable
private fun <T> Choices(
    options: List<T>,
    chosen: T,
    label: @Composable (T) -> String,
    onChoose: (T) -> Unit,
) {
    val theme = PlMailTheme.values

    Row(
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(theme.spacing.tiny),
    ) {
        options.forEach { option ->
            val isChosen = option == chosen
            val text = label(option)

            Box(
                modifier =
                    Modifier.weight(1f)
                        .clip(RoundedCornerShape(theme.radii.control))
                        .clickable(role = Role.RadioButton, onClick = { onChoose(option) })
                        .background(if (isChosen) theme.colors.accentSoft else theme.colors.surface)
                        .border(
                            width = theme.spacing.hair,
                            color = if (isChosen) theme.colors.accent else theme.colors.line,
                            shape = RoundedCornerShape(theme.radii.control),
                        )
                        // A control the user taps, so it clears the touch
                        // target whatever the density scale did to the padding
                        // around it.
                        .heightIn(min = theme.spacing.touchTarget)
                        .padding(theme.spacing.small),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isChosen) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isChosen) theme.colors.accent else theme.colors.inkSoft,
                )
            }
        }
    }
}

@Composable
private fun Toggle(title: String, body: String, isOn: Boolean, onChange: (Boolean) -> Unit) {
    val theme = PlMailTheme.values

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(theme.spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = theme.colors.ink,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = theme.colors.inkMuted,
            )
        }

        Switch(
            checked = isOn,
            onCheckedChange = onChange,
            colors =
                SwitchDefaults.colors(
                    checkedThumbColor = theme.colors.onAccent,
                    checkedTrackColor = theme.colors.accent,
                    uncheckedThumbColor = theme.colors.inkFaint,
                    uncheckedTrackColor = theme.colors.sunken,
                    uncheckedBorderColor = theme.colors.line,
                ),
        )
    }
}

private fun PlMailThemeChoice.label(): Int =
    when (this) {
        PlMailThemeChoice.SYSTEM -> R.string.theme_system
        PlMailThemeChoice.LIGHT -> R.string.theme_light
        PlMailThemeChoice.DARK -> R.string.theme_dark
        PlMailThemeChoice.NORD -> R.string.theme_nord
        PlMailThemeChoice.DUSK -> R.string.theme_dusk
        PlMailThemeChoice.SOLAR -> R.string.theme_solar
    }

private fun PlMailLayout.label(): Int =
    when (this) {
        PlMailLayout.FLAT -> R.string.layout_flat
        PlMailLayout.BOXED -> R.string.layout_boxed
    }

private fun PlMailDensity.label(): Int =
    when (this) {
        PlMailDensity.COMFORTABLE -> R.string.density_comfortable
        PlMailDensity.COSY -> R.string.density_cosy
        PlMailDensity.COMPACT -> R.string.density_compact
    }
