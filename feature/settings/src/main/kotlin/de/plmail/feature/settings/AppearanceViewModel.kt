package de.plmail.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.plmail.core.datastore.AppearanceStore
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
 * the finger lifts — and it is also why there is no "apply" button to get wrong. The store is the
 * single source, so a second window or a process death mid-choice cannot disagree with it.
 */
@HiltViewModel
class AppearanceViewModel @Inject constructor(private val store: AppearanceStore) : ViewModel() {

    val appearance: StateFlow<PlMailAppearance> =
        store.appearance
            .map { stored ->
                PlMailAppearance.of(
                    theme = stored.theme,
                    layout = stored.layout,
                    density = stored.density,
                    dynamicColor = stored.dynamicColor,
                    reduceTransparency = stored.reduceTransparency,
                    paneAlpha = stored.paneAlpha,
                )
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                // The defaults rather than a nullable state: this screen is
                // drawn inside the theme it is editing, so there is no frame in
                // which "not read yet" could be shown as anything else.
                initialValue = PlMailAppearance(),
            )

    fun choose(theme: PlMailThemeChoice) {
        viewModelScope.launch { store.setTheme(theme.wire) }
    }

    fun choose(layout: PlMailLayout) {
        viewModelScope.launch { store.setLayout(layout.wire) }
    }

    fun choose(density: PlMailDensity) {
        viewModelScope.launch { store.setDensity(density.wire) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { store.setDynamicColor(enabled) }
    }

    fun setReduceTransparency(enabled: Boolean) {
        viewModelScope.launch { store.setReduceTransparency(enabled) }
    }

    /**
     * Written on release rather than on every pixel of the drag.
     *
     * A slider emits continuously, and DataStore serialises the whole preferences file on each
     * write — one drag across the track is several hundred rewrites of the file that also holds the
     * credential and the push subscription id. This app has had one write storm already, in push
     * registration; the shape of the mistake is worth recognising the second time.
     */
    fun setPaneAlpha(alpha: Float) {
        viewModelScope.launch { store.setPaneAlpha(alpha) }
    }
}
