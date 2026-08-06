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
 * **An EventSource stream, only where a push subscription is not already doing the job.** A device
 * with a working subscription receives pushes in the foreground exactly as it does in the
 * background — the UnifiedPush receiver is a `BroadcastReceiver` and the Firebase service a
 * `Service`, and neither has an opinion about what is on screen — so a stream there buys nothing at
 * all and costs what [EventSourceClient]'s own documentation warns about: the server is frequently
 * a home NAS, every open stream occupies a FrankenPHP worker for its entire life, and once they are
 * all taken the machine stops answering ordinary requests, including the web UI its owner would use
 * to work out why.
 *
 * A device on [PushChoice.PULL] streams while it is visible, and that is the whole shape of that
 * option: no subscription, no third party told when mail arrives, and instant delivery for as long
 * as somebody is actually looking.
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
    /** Every delivery goes through here, whichever way in it came. See [stream]. */
    private val pushes: PushRepository,
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

                if (pushIsSilent()) stream()
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
     * Whether no push subscription is currently a working channel on this device.
     *
     * **Verification is part of the question now, and it was not before.** A subscription that has
     * never been past the handshake receives nothing — silently, forever — so a device holding one
     * and no stream is a device that finds out about mail every fifteen minutes while believing it
     * is on push. That is exactly the failure the app used to be unable to see, and it is free to
     * check here: the handshake is recorded locally, at the moment this device echoed the code, so
     * asking costs no round trip.
     *
     * For Web Push the distributor is asked as well, and neither half implies the other. A
     * subscription with no distributor behind it is what a device is left holding after the
     * distributor app is uninstalled — the registration looks fine and nothing will ever be
     * delivered again. Firebase has no local counterpart to check: the service is part of this
     * build, and whether Google can reach the device is not a question the device can answer.
     */
    private suspend fun pushIsSilent(): Boolean {
        val state = pushState.state.first()

        if (!state.isLive) return true

        return when (PushChoice.of(state.transport)) {
            PushChoice.FCM -> false
            // Null is a subscription of unknown kind, which on a server
            // predating the transport field means Web Push -- the same default
            // PushSubscriptionTransport takes, and for the same reason.
            PushChoice.WEB_PUSH,
            null -> push.distributor() == null
            PushChoice.PULL -> true
        }
    }

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
            // Through the same door every push comes through, rather than
            // straight into the applier. Two reasons, and the second is the
            // load-bearing one: the delivery is recorded in the received-push
            // log, so a user comparing what the server sent against what the
            // phone got can see that this one arrived down an open stream and
            // not through their subscription -- which is the difference between
            // "push works" and "push works while I am looking at it".
            .collect { pushes.delivered(it.changed, PushDelivery.STREAM) }
    }
}
