package de.plmail.core.data

import de.plmail.core.datastore.CredentialStore
import de.plmail.core.datastore.PushStateStore
import de.plmail.jmap.client.EventSourceClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * What keeps the app current while somebody is looking at it.
 *
 * Two things, in this order, and the order is the point.
 *
 * **A catch-up sync of every account, always.** This is the trigger the app never had: mail
 * arriving while the phone was in a pocket was found by the fifteen-minute worker or by a push, and
 * neither of those runs at the moment the user unlocks the screen — so the first thing they saw was
 * whatever was on disk when they last put the phone down. It costs one `Email/changes` per account
 * and it runs on every device, whatever the gate below decides.
 *
 * **An EventSource stream, only where Web Push is not already doing the job.** A device with a
 * working distributor receives pushes in the foreground exactly as it does in the background — the
 * receiver is a `BroadcastReceiver` and has no opinion about what is on screen — so a stream there
 * buys nothing at all and costs what [EventSourceClient]'s own documentation warns about: the
 * server is frequently a home NAS, every open stream occupies a FrankenPHP worker for its entire
 * life, and once they are all taken the machine stops answering ordinary requests, including the
 * web UI its owner would use to work out why.
 *
 * **One stream, not one per account.** `StateChange.changed` is keyed by account id, so a single
 * connection on a single credential already reports every mailbox behind it.
 */
@Singleton
class LiveUpdates
@Inject
constructor(
    private val clients: AccountClients,
    private val credentials: CredentialStore,
    private val transports: TransportFactory,
    private val deltaSync: DeltaSync,
    private val changes: StateChangeApplier,
    private val pushState: PushStateStore,
    private val push: PushTransport,
    /**
     * Appearance has no push and no `/changes`, so foreground is one of exactly two moments it can
     * be noticed at all — the other being the fifteen-minute worker. A theme changed in a browser
     * tab appears when the phone is next unlocked, which is the closest thing to "immediately" that
     * a settings object with no change log can offer.
     */
    private val appearance: AppearanceRepository,
    @param:ApplicationScope private val scope: CoroutineScope,
) {

    private var running: Job? = null

    /**
     * Catches up, and then streams if there is no other channel.
     *
     * Idempotent by the guard rather than by cancel-and-restart: `ProcessLifecycleOwner` debounces
     * a rotation, but a second caller must not cost a second catch-up sync of every account.
     */
    fun start() {
        if (running?.isActive == true) return

        running = scope.launch {
            try {
                // Unconditional, and before the gate is even consulted. This
                // half is the foreground-resume trigger; the stream is an
                // optimisation on top of it.
                deltaSync.syncAll()

                // After the mail, and swallowing its own failures: the point of
                // being here is that the app is visible, and re-theming it is
                // never worth delaying the list that is on screen.
                appearance.refresh()

                if (webPushIsSilent()) stream()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // Swallowed rather than reported, and this is not laziness:
                // nothing here is an operation the user asked for. The
                // catch-up records its own per-account failures, which the
                // diagnostics screen draws, and the stream reconnects on its
                // own. What an escaping throw would do instead is kill the
                // process from a scope with no handler, because somebody
                // unlocked their phone while their NAS was off.
            }
        }
    }

    /** Closes the stream. Called when the app leaves the screen, and it has to be. */
    fun stop() {
        running?.cancel()
        running = null
    }

    /**
     * Whether Web Push is *not* a working channel on this device.
     *
     * Both halves are needed and neither implies the other. A subscription id with no distributor
     * behind it is what a device is left holding after the distributor app is uninstalled — the
     * registration looks fine and nothing will ever be delivered again. A distributor with no
     * subscription is a device that has one installed and has not registered, or whose registration
     * was refused.
     *
     * Registration is deliberately not confirmed against the server here. `PushRepository.isLive`
     * can tell whether a subscription was ever verified, and an unverified one delivers nothing
     * forever — but asking costs a round trip on every foreground, and being wrong in this
     * direction only means one redundant channel rather than none.
     */
    private suspend fun webPushIsSilent(): Boolean =
        !pushState.state.first().isRegistered || push.distributor() == null

    private suspend fun stream() {
        val stored = credentials.connection.first() ?: return
        val client = clients.current() ?: return

        // Asked here rather than left to the client, which *throws* on a server
        // advertising no eventSourceUrl. A plMail behind a proxy that strips it,
        // or one older than the extension, is a configuration this app has to
        // run against rather than crash on -- the fifteen-minute worker and the
        // catch-up above are still doing their job.
        val session = client.session()
        if (session.eventSourceUrl == null) return

        EventSourceClient(
                client = client,
                transport = transports.createStreaming(stored.address, stored.pinnedKey),
                credential = stored.credential,
            )
            .events()
            .collect { changes.apply(it.changed) }
    }
}
