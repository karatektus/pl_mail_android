package de.plmail.feature.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.plmail.core.data.CalendarLauncherIcon
import de.plmail.core.data.CalendarRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Whether the launcher is showing a calendar icon, and whether it may be offered at all.
 *
 * **Nothing here is stored.** [isOn] is a cache of one binder call, refreshed on demand, and the
 * authority stays with `PackageManager` throughout — see [CalendarLauncherIcon] for why a DataStore
 * copy would be a second answer rather than a faster one. The state is held as a plain
 * `mutableStateOf` and not a flow because there is nothing to collect: the system publishes no
 * signal when a component's enabled state changes, so the honest shape is a value plus a [refresh]
 * the screen calls when it comes back into view.
 *
 * [hasCalendar] is the same question the drawer's Calendar row asks, from the same source. An
 * instance whose server publishes no calendar is a supported instance rather than a broken one, and
 * offering it a second launcher icon for a calendar it does not have would be worse than the drawer
 * row it already hides.
 */
@HiltViewModel
class CalendarLauncherViewModel
@Inject
constructor(private val icon: CalendarLauncherIcon, calendars: CalendarRepository) : ViewModel() {

    val hasCalendar: StateFlow<Boolean> =
        calendars
            .isAvailable()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                // False first, for the reason MainViewModel gives: the control
                // appears once there is evidence for it, rather than flashing in
                // and out of the screen at every launch.
                initialValue = false,
            )

    /**
     * Read once at construction rather than on first composition.
     *
     * The switch is drawn on the first frame it is on screen, and a value that arrived a frame
     * later would draw it off and then animate it on for a user who had already turned it on. The
     * call is a binder round trip with nothing of ours behind it, which is cheap enough to make in
     * a constructor.
     */
    var isOn by mutableStateOf(icon.isEnabled())
        private set

    /**
     * Re-asks the system.
     *
     * Called when the screen resumes, because the icon can be gone without this app having been the
     * one to remove it: a user who disabled the component from a system settings screen, or a
     * restore that did not carry the override, would otherwise leave this switch claiming an icon
     * that is not on the home screen.
     */
    fun refresh() {
        isOn = icon.isEnabled()
    }

    /**
     * Writes and re-reads, rather than assuming the write took.
     *
     * The same rule the appearance settings follow — every control writes immediately and there is
     * no staging state — but with one difference that matters here: what is written goes to the
     * system, so the value shown afterwards is what the system says rather than what was asked for.
     */
    fun setEnabled(enabled: Boolean) {
        icon.setEnabled(enabled)
        refresh()
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
