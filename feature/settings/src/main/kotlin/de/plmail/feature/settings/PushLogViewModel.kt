package de.plmail.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.plmail.core.data.PushLog
import de.plmail.core.data.PushTransportManager
import de.plmail.core.data.PushTransportState
import de.plmail.core.data.ReceivedPush
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The log and the registration it is evidence about, drawn on one screen. */
data class PushLogState(
    val entries: List<ReceivedPush> = emptyList(),
    val transports: PushTransportState = PushTransportState(),
)

@HiltViewModel
class PushLogViewModel
@Inject
constructor(private val log: PushLog, transports: PushTransportManager) : ViewModel() {

    val state: StateFlow<PushLogState> =
        combine(log.entries, transports.transports) { entries, transportState ->
                PushLogState(entries = entries, transports = transportState)
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = PushLogState(),
            )

    /**
     * Empties the log.
     *
     * Offered because a log you cannot reset is a log you cannot use: the way somebody actually
     * tests this is to clear it, send themselves a message, and see whether exactly one line
     * appears.
     */
    fun clear() {
        viewModelScope.launch { log.clear() }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
