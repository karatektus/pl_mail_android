package de.plmail.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.plmail.core.data.AppearanceRepository
import de.plmail.core.designsystem.PlMailAppearance
import de.plmail.core.designsystem.PlMailDensity
import de.plmail.core.designsystem.PlMailLayout
import de.plmail.core.designsystem.PlMailThemeChoice
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
     * credential and the push subscription id, and now several hundred `Appearance/set` calls at a
     * server that advertises four concurrent requests. This app has had one write storm already, in
     * push registration; the shape of the mistake is worth recognising the second time.
     */
    fun setPaneAlpha(alpha: Float) {
        viewModelScope.launch { appearances.setPaneAlpha(alpha) }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
