package de.plmail

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.plmail.core.data.SyncWorker
import de.plmail.core.datastore.CredentialStore
import de.plmail.push.PushSetup
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
class MainViewModel
@Inject
constructor(
    credentials: CredentialStore,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    val connection: StateFlow<ConnectionState> =
        credentials.connection
            .map { stored ->
                // Scheduled from the presence of a connection rather than from
                // onboarding finishing: a credential that stopped being
                // readable should stop the background sync too, and that is a
                // change in the store rather than a screen anyone visits.
                if (stored == null) {
                    SyncWorker.cancel(context)
                    PushSetup.disable(context)
                    ConnectionState.None
                } else {
                    SyncWorker.schedule(context)

                    // Push is an upgrade, never a requirement. Enabling it is
                    // attempted and allowed to fail: a device with no
                    // distributor keeps the fifteen-minute sync, which is why
                    // that sync is scheduled first and unconditionally.
                    PushSetup.enable(context)

                    ConnectionState.Connected(stored.username)
                }
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
