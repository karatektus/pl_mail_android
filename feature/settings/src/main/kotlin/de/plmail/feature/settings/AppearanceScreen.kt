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
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.plmail.core.designsystem.PaneTone
import de.plmail.core.designsystem.PlMailAppearance
import de.plmail.core.designsystem.PlMailDensity
import de.plmail.core.designsystem.PlMailFontFamily
import de.plmail.core.designsystem.PlMailLayout
import de.plmail.core.designsystem.PlMailPane
import de.plmail.core.designsystem.PlMailTheme
import de.plmail.core.designsystem.PlMailThemeChoice
import de.plmail.core.designsystem.PlMailUnreadEmphasis
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
 * In the order somebody actually decides them: whether the phone follows the browser at all, then
 * the palette, then how it is painted, then how tightly it packs, then the type, then the list, and
 * last the accessibility overrides that outrank all of it. The overrides are phrased as what they
 * *do* rather than as what they are for — "Panes stay solid" rather than "reduce transparency" —
 * because the person who needs one is looking for the effect.
 *
 * **[MatchTheWeb] is first because it changes what everything under it means**, not because it is
 * the most important. Every other control on the screen is a shared account preference until that
 * switch is off, at which point the same controls become this device's alone — and nothing about
 * their appearance says so, which is exactly why the switch has to be met before them.
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
            MatchTheWeb(appearance, onChange = viewModel::setSyncWithServer)
            Themes(appearance, onChoose = viewModel::choose)
            DynamicColour(appearance, onChange = viewModel::setDynamicColor)
            Layouts(appearance, onChoose = viewModel::choose)
            Densities(appearance, onChoose = viewModel::choose)
            Surfaces(appearance, viewModel)
            Typography(appearance, onChoose = viewModel::choose, onScale = viewModel::setFontScale)
            MailList(appearance, viewModel)
            Accessibility(
                appearance = appearance,
                onReduceTransparency = viewModel::setReduceTransparency,
                onPaneAlpha = viewModel::setPaneAlpha,
            )
            HomeScreen()
        }
    }
}

/**
 * The calendar's own launcher icon.
 *
 * **Here rather than on a settings screen of its own**, and the choice is worth stating because
 * this is not a theme. There is no general settings screen in this app — the drawer opens Accounts,
 * Appearance, Push and Diagnostics, each of which is one subject — so a screen for one switch would
 * have added a fifth drawer row that says "Miscellaneous". What this switch actually changes is
 * what the product looks like from outside itself, which is the same question as the icon's
 * colours, and it is last on the screen because it is the only control here that leaves the app.
 *
 * Hidden outright where the server publishes no calendar, exactly as the drawer's Calendar row is:
 * a switch offering a home-screen icon for a calendar that does not exist is worse than no switch.
 *
 * Its own view model, and not a field bolted onto [AppearanceViewModel]. Everything else on this
 * screen is a preference that syncs to the server and comes back to every device; this one is a
 * fact held by this phone's package manager, and mixing the two would put a value that cannot sync
 * into the object whose whole contract is that it does.
 */
@Composable
private fun HomeScreen(viewModel: CalendarLauncherViewModel = hiltViewModel()) {
    val hasCalendar by viewModel.hasCalendar.collectAsStateWithLifecycle()

    if (!hasCalendar) return

    // On resume rather than once, because the answer is the system's and the
    // system can have been asked by somebody else while this screen was in the
    // background. See CalendarLauncherViewModel.refresh.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose {}
    }

    Section(stringResource(R.string.appearance_home_screen)) {
        Toggle(
            title = stringResource(R.string.appearance_calendar_icon),
            // Three separate honesties, and every one of them is something a
            // user would otherwise report as a fault: the icon may not appear
            // at once, the calendar becomes a second entry in the recent-apps
            // list, and none of this follows them to another device.
            body = stringResource(R.string.appearance_calendar_icon_body),
            isOn = viewModel.isOn,
            onChange = viewModel::setEnabled,
        )
    }
}

/**
 * Whether this phone wears the account's appearance or one of its own.
 *
 * **First on the screen, because it decides what every control below it means.** Off, the pickers
 * stop being a shared preference and become this device's own; nothing about them changes visibly,
 * which is exactly why the switch has to be read before them rather than found afterwards.
 *
 * The supporting text says the two things a user cannot work out by trying it, and both are things
 * they would otherwise only discover by being surprised. That turning it off leaves the browser
 * alone is a *promise*, not a description — it is the reason `Appearance/set` is never called while
 * the switch is off, rather than being called with the values the phone happened to have. And that
 * turning it back on discards this device's choices is the half nobody expects: a merge would be
 * the intuitive behaviour and there is no correct one, because the phone's month of divergence and
 * the browser's are both deliberate. Saying so beforehand is cheaper than any merge rule.
 */
@Composable
private fun MatchTheWeb(appearance: PlMailAppearance, onChange: (Boolean) -> Unit) {
    Toggle(
        title = stringResource(R.string.appearance_sync),
        body = stringResource(R.string.appearance_sync_body),
        isOn = appearance.syncWithServer,
        onChange = onChange,
    )
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

        // Said out loud now that appearance actually syncs. Someone who frosted
        // their panes in the browser will find this phone drawing them solid,
        // and the difference is worth a sentence rather than a bug report:
        // Compose blurs a composable's own content and has no backdrop filter,
        // so a "frosted" pane here would blur the text on it rather than the
        // list behind it. The value is kept and sent back untouched, so nothing
        // this app does undoes the choice.
        if (appearance.layout == PlMailLayout.BOXED) {
            Text(
                text = stringResource(R.string.appearance_blur_unsupported),
                style = MaterialTheme.typography.bodySmall,
                color = PlMailTheme.colors.inkMuted,
            )
        }
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

/**
 * The three surfaces that may pack tighter or looser than the app does.
 *
 * Each offers a fourth option ahead of the three densities, and it is not a "none": "Follow the
 * overall density" is a value the server stores as null and the only way back from an override.
 * Drawn as a choice rather than as a clear button because that is what it is — somebody who has
 * never touched it is *on* that option, and a control that showed nothing selected until they
 * picked something would be lying about the state.
 *
 * **Two of the three are stored, sent and resolved but not yet drawn anywhere.** `sidebarDensity`
 * and `readingDensity` apply to surfaces `feature/mail` owns and this change could not reach; the
 * list is wired end to end. They are offered here regardless, because the setting is the account's
 * rather than this app's — a phone that hid two of the three would quietly drop them out of a value
 * the browser is still honouring.
 */
@Composable
private fun Surfaces(appearance: PlMailAppearance, viewModel: AppearanceViewModel) {
    Section(stringResource(R.string.appearance_surface_density)) {
        SurfaceDensity(
            label = stringResource(R.string.appearance_surface_sidebar),
            chosen = appearance.sidebarDensity,
            onChoose = viewModel::chooseSidebarDensity,
        )
        SurfaceDensity(
            label = stringResource(R.string.appearance_surface_list),
            chosen = appearance.listDensity,
            onChoose = viewModel::chooseListDensity,
        )
        SurfaceDensity(
            label = stringResource(R.string.appearance_surface_reading),
            chosen = appearance.readingDensity,
            onChoose = viewModel::chooseReadingDensity,
        )

        Text(
            text = stringResource(R.string.appearance_surface_density_body),
            style = MaterialTheme.typography.bodySmall,
            color = PlMailTheme.colors.inkMuted,
        )
    }
}

@Composable
private fun SurfaceDensity(
    label: String,
    chosen: PlMailDensity?,
    onChoose: (PlMailDensity?) -> Unit,
) {
    val follow = stringResource(R.string.density_follow)

    Column(verticalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.tiny)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = PlMailTheme.colors.inkSoft,
        )

        // Null leads rather than trails, because it is the default and the
        // reading order of a row of options is an argument about which one is
        // ordinary. `listOf(null) + entries` rather than a nullable enum with a
        // FOLLOW member: a member would be a fourth density the server has no
        // name for, and the wire value it would need is precisely the absence
        // this list is expressing.
        Choices(
            options = SURFACE_DENSITY_OPTIONS,
            chosen = chosen,
            label = { density -> density?.let { stringResource(it.label()) } ?: follow },
            onChoose = onChoose,
        )
    }
}

@Composable
private fun Typography(
    appearance: PlMailAppearance,
    onChoose: (PlMailFontFamily) -> Unit,
    onScale: (Float) -> Unit,
) {
    Section(stringResource(R.string.appearance_typography)) {
        Choices(
            options = PlMailFontFamily.entries,
            chosen = appearance.fontFamily,
            label = { stringResource(it.label()) },
            onChoose = onChoose,
        )

        Text(
            text = stringResource(R.string.appearance_font_family_body),
            style = MaterialTheme.typography.bodySmall,
            color = PlMailTheme.colors.inkMuted,
        )

        FontScale(appearance, onScale)
    }
}

@Composable
private fun FontScale(appearance: PlMailAppearance, onChange: (Float) -> Unit) {
    val theme = PlMailTheme.values

    // Local while dragging and committed on release, for the reason PaneAlpha
    // gives -- and with more force here, because every commit re-lays out every
    // screen in the app rather than repainting one pane.
    var dragging by remember(appearance.fontScale) { mutableFloatStateOf(appearance.fontScale) }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.appearance_font_scale),
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
            valueRange = PlMailAppearance.MIN_FONT_SCALE..PlMailAppearance.MAX_FONT_SCALE,
            colors =
                SliderDefaults.colors(
                    thumbColor = theme.colors.accent,
                    activeTrackColor = theme.colors.accent,
                    inactiveTrackColor = theme.colors.line,
                ),
        )

        // Said out loud because the two multiply. Somebody who has already set
        // their phone to 130% and then puts this to 125% is asking for 163%, and
        // the first thing they will notice is a list row that no longer holds a
        // subject -- which reads as a bug in the app rather than as arithmetic.
        Text(
            text = stringResource(R.string.appearance_font_scale_body),
            style = MaterialTheme.typography.bodySmall,
            color = theme.colors.inkMuted,
        )
    }
}

@Composable
private fun MailList(appearance: PlMailAppearance, viewModel: AppearanceViewModel) {
    val list = appearance.list

    Section(stringResource(R.string.appearance_list)) {
        Toggle(
            title = stringResource(R.string.appearance_list_avatars),
            body = stringResource(R.string.appearance_list_avatars_body),
            isOn = list.avatars,
            onChange = viewModel::setListAvatars,
        )

        Column(verticalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.tiny)) {
            Text(
                text = stringResource(R.string.appearance_preview_lines),
                style = MaterialTheme.typography.bodyMedium,
                color = PlMailTheme.colors.inkSoft,
            )

            Choices(
                options = PREVIEW_LINE_OPTIONS,
                chosen = list.previewLines,
                label = { stringResource(it.previewLabel()) },
                onChoose = viewModel::setPreviewLines,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.tiny)) {
            Text(
                text = stringResource(R.string.appearance_unread_emphasis),
                style = MaterialTheme.typography.bodyMedium,
                color = PlMailTheme.colors.inkSoft,
            )

            Choices(
                options = PlMailUnreadEmphasis.entries,
                chosen = list.unreadEmphasis,
                label = { stringResource(it.label()) },
                onChoose = viewModel::choose,
            )
        }

        // Named for what it does rather than for the property, because the
        // property's name describes a corner and what the user is choosing is
        // whether rows say which account they came from. The mark only appears
        // in a list showing more than one account, and the body says so: a
        // switch that visibly does nothing on a single-account install is a
        // switch that gets reported as broken.
        Toggle(
            title = stringResource(R.string.appearance_account_corner),
            body = stringResource(R.string.appearance_account_corner_body),
            isOn = list.accountCorner,
            onChange = viewModel::setAccountCorner,
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
            // The title again, on the switch itself. Without it TalkBack
            // announces the row as "Keep panes solid" and then, as a separate
            // stop, "off, switch" -- and somebody who reached the control by
            // swiping to it, or who is scrubbing the screen with a finger, gets
            // only the second half. Naming the switch costs a repetition for
            // anybody reading the screen top to bottom and is the difference
            // between a usable control and a guess for anybody who is not.
            modifier = Modifier.semantics { contentDescription = title },
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

private fun PlMailFontFamily.label(): Int =
    when (this) {
        PlMailFontFamily.SYSTEM -> R.string.font_system
        PlMailFontFamily.GROTESK -> R.string.font_grotesk
        PlMailFontFamily.SERIF -> R.string.font_serif
        PlMailFontFamily.MONOSPACE -> R.string.font_monospace
    }

private fun PlMailUnreadEmphasis.label(): Int =
    when (this) {
        PlMailUnreadEmphasis.SUBTLE -> R.string.emphasis_subtle
        PlMailUnreadEmphasis.STANDARD -> R.string.emphasis_standard
        PlMailUnreadEmphasis.STRONG -> R.string.emphasis_strong
    }

/**
 * A count rather than a name, so the option list is the range and cannot drift from it.
 *
 * The `else` branch is unreachable — the list below is the only source of these — and is here
 * because a `when` over an `Int` has to be exhaustive somehow. Falling to the one-line label rather
 * than throwing: a number this build has never heard of is a newer server's, and an appearance
 * setting is never worth a crash.
 */
private fun Int.previewLabel(): Int =
    when (this) {
        0 -> R.string.preview_none
        2 -> R.string.preview_two
        else -> R.string.preview_one
    }

/** The server's `ranges.previewLines`, as the three options a picker can offer. */
private val PREVIEW_LINE_OPTIONS =
    (PlMailAppearance.MIN_PREVIEW_LINES..PlMailAppearance.MAX_PREVIEW_LINES).toList()

/** "Follow the overall density" first, then the three densities. See [SurfaceDensity]. */
private val SURFACE_DENSITY_OPTIONS: List<PlMailDensity?> =
    listOf<PlMailDensity?>(null) + PlMailDensity.entries
