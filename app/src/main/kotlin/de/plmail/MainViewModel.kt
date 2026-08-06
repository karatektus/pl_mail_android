package de.plmail

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.plmail.core.data.CalendarRepository
import de.plmail.core.data.PushTransportManager
import de.plmail.core.data.SyncWorker
import de.plmail.core.datastore.CredentialStore
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
    calendar: CalendarRepository,
    private val pushTransports: PushTransportManager,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    /**
     * Whether this install has a calendar, which decides whether the drawer offers one.
     *
     * False to start with rather than true: the entry appears once there is evidence for it. A row
     * that flashes in and out at every launch is worse than one that arrives a frame late — and an
     * instance without the vendor calendar extension is a supported instance, not a broken one, so
     * "no calendar" has to look like a product with no calendar rather than like a feature that
     * failed to load.
     */
    val hasCalendar: StateFlow<Boolean> =
        calendar
            .isAvailable()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = false,
            )

    val connection: StateFlow<ConnectionState> =
        credentials.connection
            .map { stored ->
                // Scheduled from the presence of a connection rather than from
                // onboarding finishing: a credential that stopped being
                // readable should stop the background sync too, and that is a
                // change in the store rather than a screen anyone visits.
                if (stored == null) {
                    SyncWorker.cancel(context)

                    // Both transports released, and the local registration
                    // forgotten. Routed through the manager rather than
                    // unregistering the distributor here, because there are two
                    // of them now and a sign-out that tidied up one would leave
                    // a phone holding a Firebase token for a mailbox it is no
                    // longer signed into.
                    tidyPush { pushTransports.signedOut() }

                    ConnectionState.None
                } else {
                    SyncWorker.schedule(context)

                    // Push is an upgrade, never a requirement. Re-applying the
                    // user's stored choice is attempted and allowed to fail: a
                    // device with no distributor and no Play services keeps the
                    // fifteen-minute sync, which is why that sync is scheduled
                    // first and unconditionally.
                    //
                    // Idempotent by design -- this flow re-emits -- so a device
                    // already registered and verified on the transport it chose
                    // is left alone rather than re-registered on every launch.
                    tidyPush { pushTransports.reapply() }

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

    /**
     * Runs a push-registration change **beside** the connection state rather than in front of it.
     *
     * Load-bearing, and the reason this is not just a call in the `map` above. Registering talks to
     * the server: a create, and possibly a Firebase round trip before it. Awaiting that inside the
     * transform would hold [connection] on `Unknown` for its whole duration — so a cold launch
     * would show the splash until a NAS that is still waking up answered, and a launch with no
     * network would show it until the request timed out. The old code got away with a call here
     * because enabling UnifiedPush was a local, instant operation; this one is not.
     *
     * Failures are swallowed on purpose. Nothing here is something the user asked for, the
     * fifteen-minute sync is already scheduled, and the settings screen states the registration's
     * real condition rather than inferring it from whether this happened to work.
     */
    private fun tidyPush(block: suspend () -> Unit) {
        viewModelScope.launch { runCatching { block() } }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
