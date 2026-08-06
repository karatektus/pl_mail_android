package de.plmail.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.plmail.core.data.PushChoice
import de.plmail.core.data.PushTransportManager
import de.plmail.core.data.PushTransportState
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class PushViewModel @Inject constructor(private val transports: PushTransportManager) :
    ViewModel() {

    /**
     * Whether a switch is in flight, held apart from the manager's own state.
     *
     * It is a property of *this screen* — somebody tapped a row a moment ago — rather than of the
     * registration, and folding it in would make it survive leaving the screen and coming back.
     */
    private val switching = MutableStateFlow(false)

    val state: StateFlow<PushTransportState> =
        combine(transports.transports, switching) { state, isSwitching ->
                state.copy(isSwitching = isSwitching)
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = PushTransportState(),
            )

    /**
     * Moves this device to [choice].
     *
     * On the ViewModel's scope rather than a screen-bound one: leaving the screen mid-switch must
     * not abandon a subscription half created, and the registration is written into the store
     * either way.
     *
     * Returning here does **not** mean the switch is finished. The server sends a verification push
     * to the address just registered and delivers nothing until the app echoes the code back, so
     * the screen goes on saying "waiting for confirmation" until that arrives — see
     * [PushTransportState.isAwaitingVerification].
     */
    fun choose(choice: PushChoice) {
        if (switching.value) return

        switching.update { true }

        viewModelScope.launch {
            runCatching { transports.choose(choice) }

            switching.update { false }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
