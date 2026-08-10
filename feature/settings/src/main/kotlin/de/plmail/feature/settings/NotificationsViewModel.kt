package de.plmail.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.plmail.core.data.NotifiableScopes
import de.plmail.core.data.NotificationSettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Which lists of mail may interrupt, read and written.
 *
 * Every switch writes and returns, exactly as [AppearanceViewModel] does: nothing is held locally
 * and applied on leaving, so there is no "apply" button to get wrong and no state to lose if the
 * screen is backed out of. The repository is the single source and the sync path reads the same
 * store, so a switch is true for the next sync rather than for the next launch.
 */
@HiltViewModel
class NotificationsViewModel
@Inject
constructor(private val settings: NotificationSettingsRepository) : ViewModel() {

    val scopes: StateFlow<NotifiableScopes> =
        settings.scopes.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            // Empty rather than a loading flag. The list is read from the local
            // mailbox table and arrives in the same frame in practice; the one
            // state worth drawing is "this device has no labels yet", and an
            // empty list is already that.
            initialValue = NotifiableScopes(),
        )

    fun setEnabled(key: String, enabled: Boolean) {
        viewModelScope.launch { settings.setEnabled(key, enabled) }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
