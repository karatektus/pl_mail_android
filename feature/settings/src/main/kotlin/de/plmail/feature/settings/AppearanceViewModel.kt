package de.plmail.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.plmail.core.data.AppearanceRepository
import de.plmail.core.datastore.DensityOverride
import de.plmail.core.designsystem.PlMailAppearance
import de.plmail.core.designsystem.PlMailDensity
import de.plmail.core.designsystem.PlMailFontFamily
import de.plmail.core.designsystem.PlMailLayout
import de.plmail.core.designsystem.PlMailThemeChoice
import de.plmail.core.designsystem.PlMailUnreadEmphasis
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The appearance settings, read and written.
 *
 * Every setter writes and returns; nothing is held locally and applied on leaving. That is what
 * makes the screen its own preview — the whole app is already re-themed under the sheet by the time
 * the finger lifts — and it is also why there is no "apply" button to get wrong.
 *
 * The repository is the single source, and its source in turn is the server's `Appearance` with
 * anything this device has changed since laid on top. `PlMailAppearance.of(...)` below is unchanged
 * from when the values came out of DataStore alone: the plan promised that swap would touch the
 * resolver's *source* and nothing else, and this is the line where that turned out to be true.
 */
@HiltViewModel
class AppearanceViewModel @Inject constructor(private val appearances: AppearanceRepository) :
    ViewModel() {

    val appearance: StateFlow<PlMailAppearance> =
        appearances.settings
            .map { settings ->
                PlMailAppearance.of(
                    theme = settings.theme,
                    layout = settings.layout,
                    density = settings.density,
                    dynamicColor = settings.dynamicColor,
                    reduceTransparency = settings.reduceTransparency,
                    paneAlpha = settings.paneAlpha,
                    syncWithServer = settings.syncWithServer,
                    accountCorner = settings.accountCorner,
                    listAvatars = settings.listAvatars,
                    previewLines = settings.previewLines,
                    unreadEmphasis = settings.unreadEmphasis,
                    fontFamily = settings.fontFamily,
                    fontScale = settings.fontScale,
                    sidebarDensity = settings.sidebarDensity,
                    listDensity = settings.listDensity,
                    readingDensity = settings.readingDensity,
                )
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                // The defaults rather than a nullable state: this screen is
                // drawn inside the theme it is editing, so there is no frame in
                // which "not read yet" could be shown as anything else.
                initialValue = PlMailAppearance(),
            )

    fun choose(theme: PlMailThemeChoice) {
        viewModelScope.launch { appearances.setTheme(theme.wire) }
    }

    fun choose(layout: PlMailLayout) {
        viewModelScope.launch { appearances.setLayout(layout.wire) }
    }

    fun choose(density: PlMailDensity) {
        viewModelScope.launch { appearances.setDensity(density.wire) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { appearances.setDynamicColor(enabled) }
    }

    fun setReduceTransparency(enabled: Boolean) {
        viewModelScope.launch { appearances.setReduceTransparency(enabled) }
    }

    /**
     * Written on release rather than on every pixel of the drag.
     *
     * A slider emits continuously, and DataStore serialises the whole preferences file on each
     * write — one drag across the track is several hundred rewrites of the file that also holds the
     * credential and the push subscription id. This app has had one write storm already, in push
     * registration; the shape of the mistake is worth recognising the second time. (It used to be
     * several hundred `Appearance/set` calls as well. The app no longer writes an appearance to the
     * server at all, which retires that half of the hazard rather than managing it.)
     */
    fun setPaneAlpha(alpha: Float) {
        viewModelScope.launch { appearances.setPaneAlpha(alpha) }
    }

    /** Written on release, for the reason [setPaneAlpha] gives — and this one also re-lays out. */
    fun setFontScale(scale: Float) {
        viewModelScope.launch { appearances.setFontScale(scale) }
    }

    fun setAccountCorner(shown: Boolean) {
        viewModelScope.launch { appearances.setAccountCorner(shown) }
    }

    fun setListAvatars(shown: Boolean) {
        viewModelScope.launch { appearances.setListAvatars(shown) }
    }

    fun setPreviewLines(lines: Int) {
        viewModelScope.launch { appearances.setPreviewLines(lines) }
    }

    fun choose(emphasis: PlMailUnreadEmphasis) {
        viewModelScope.launch { appearances.setUnreadEmphasis(emphasis.wire) }
    }

    fun choose(family: PlMailFontFamily) {
        viewModelScope.launch { appearances.setFontFamily(family.wire) }
    }

    /**
     * The three per-surface densities, and the reason they take a nullable enum.
     *
     * Null is the option "follow the overall density", which is a value the user picks rather than
     * the absence of one — and the only way back from an override. It travels as a
     * [DensityOverride] with a null `wire` all the way to an explicit JSON null in the patch; the
     * moment anything on that path writes `?:`, the control stops doing anything at all.
     */
    fun chooseSidebarDensity(density: PlMailDensity?) {
        viewModelScope.launch { appearances.setSidebarDensity(DensityOverride(density?.wire)) }
    }

    fun chooseListDensity(density: PlMailDensity?) {
        viewModelScope.launch { appearances.setListDensity(DensityOverride(density?.wire)) }
    }

    fun chooseReadingDensity(density: PlMailDensity?) {
        viewModelScope.launch { appearances.setReadingDensity(DensityOverride(density?.wire)) }
    }

    /**
     * Whether this phone keeps inheriting the account's appearance.
     *
     * The one control on this screen whose effect is not visible in the preview it sits inside:
     * turning it off changes nothing on screen, because the appearance the phone is already wearing
     * is exactly the one it keeps. What changes is everything after — see
     * [AppearanceRepository.setSyncWithServer]. Note it governs one direction only: nothing on this
     * screen is ever sent to the server in either position.
     */
    fun setSyncWithServer(enabled: Boolean) {
        viewModelScope.launch { appearances.setSyncWithServer(enabled) }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
