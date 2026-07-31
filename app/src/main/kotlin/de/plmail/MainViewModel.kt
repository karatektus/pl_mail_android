package de.plmail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.plmail.core.datastore.CredentialStore
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Whether the app has a server to talk to.
 *
 * [Unknown] is a real state rather than a null: reading the store is asynchronous, and collapsing
 * "not read yet" into "no connection" would show onboarding for one frame on every cold launch of
 * an app that is perfectly well configured.
 */
sealed interface ConnectionState {
    data object Unknown : ConnectionState

    data object None : ConnectionState

    data class Connected(val username: String) : ConnectionState
}

@HiltViewModel
class MainViewModel @Inject constructor(credentials: CredentialStore) : ViewModel() {

    val connection: StateFlow<ConnectionState> =
        credentials.connection
            .map { stored ->
                if (stored == null) ConnectionState.None
                else ConnectionState.Connected(stored.username)
            }
            .stateIn(
                scope = viewModelScope,
                // Kept alive briefly across a rotation so the store is not
                // re-read, and the launch decision not re-made, every time the
                // configuration changes.
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = ConnectionState.Unknown,
            )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
