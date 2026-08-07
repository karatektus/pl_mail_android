package de.plmail.core.data

import de.plmail.core.datastore.PushState
import de.plmail.core.datastore.PushStateStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The picker's whole state: what may be chosen, what is chosen, and where the switch has got to.
 */
data class PushTransportState(
    val options: List<PushOption> = emptyList(),
    /**
     * What the user asked for. Web Push by default, which is what the app did before there was a
     * choice.
     */
    val choice: PushChoice = PushChoice.WEB_PUSH,
    val push: PushState = PushState(),
    /** True while a switch is in flight. The verification wait is *not* part of this. */
    val isSwitching: Boolean = false,
    /**
     * Set when the app is on FCM and the server has stopped offering it.
     *
     * Its own field rather than an error string, because it is the one failure that happens to a
     * device doing nothing at all — an administrator switched Firebase off — and the app must fall
     * back **visibly**. A silent demotion to pull is a phone that stops ringing for reasons its
     * owner will never find.
     */
    val fellBack: PushUnavailable? = null,
    /** The last switch failure, in the server's or the transport's own words. */
    val lastError: String? = null,
) {
    /** The transport actually registered, which is not always the one chosen. */
    val active: PushChoice?
        get() = if (push.isRegistered) PushChoice.of(push.transport) else null

    /**
     * Registered, and waiting for the verification push that makes it deliver.
     *
     * A real state with a real duration, and the reason the switch is not "done" at the create: the
     * server sends the code to the address just registered and the subscription delivers nothing
     * until it comes back. Surfaced rather than hidden, because it is also where a *broken*
     * transport sits — permanently — and the two are indistinguishable except by how long.
     */
    val isAwaitingVerification: Boolean
        get() = push.isAwaitingVerification
}

/**
 * Moving this device between push transports, and keeping the claim honest while it happens.
 *
 * ## Why a switch is not one call
 *
 * Creating a subscription does not make it deliver. The server sends a `PushVerification` to the
 * address just registered and delivers **nothing** until the client echoes the code back, which
 * arrives as a push and therefore cannot be waited for synchronously. So [choose] gets as far as a
 * registered subscription and the screen says "waiting for confirmation" until
 * [PushRepository.deliver] answers the handshake. Reporting success at the create would be
 * reporting a state that receives no mail.
 *
 * ## Why cleanup is not "destroy then create"
 *
 * `deviceClientId` is stable per device and the server *replaces* the row it matches — including
 * across transports, where it drops the old row and inserts a new one rather than mutating between
 * two shapes. So the correct switch is a plain create with the same device id, and a destroy first
 * would only open a window in which the device has no subscription at all. The destroy is kept for
 * exactly one case: [PushChoice.PULL], which is the absence of a subscription rather than a
 * different one.
 */
@Singleton
class PushTransportManager
@Inject
constructor(
    private val clients: AccountClients,
    private val state: PushStateStore,
    private val push: PushRepository,
    private val webPush: PushTransport,
    private val fcm: FcmSupport,
    private val deviceClientId: DeviceClientId,
) {

    /** One switch at a time. Two in flight would race each other's `deviceClientId` create. */
    private val mutex = Mutex()

    /**
     * What the server publishes, re-read whenever somebody looks.
     *
     * A flow rather than a cached value because the answer changes underneath the app: an
     * administrator switching Firebase on is a session change with no local event behind it. The
     * session itself is cached and single-flighted by `JmapClient`, so opening the screen is not a
     * request per open.
     */
    private val serverSupport: Flow<ServerPushSupport> = flow {
        emit(
            runCatching { ServerPushSupport.from(clients.current()?.session()?.push) }
                .getOrDefault(ServerPushSupport.UNKNOWN)
        )
    }

    val transports: Flow<PushTransportState> =
        combine(state.state, serverSupport) { stored, server ->
            val choice = PushChoice.of(stored.choice) ?: PushChoice.WEB_PUSH

            PushTransportState(
                options = options(server),
                choice = choice,
                push = stored,
                fellBack =
                    // Only when this device is *on* FCM and the server has
                    // stopped offering it. A device that never chose Firebase
                    // does not need to be told the server has none.
                    if (choice == PushChoice.FCM) server.fcmObjection else null,
                lastError = stored.lastError,
            )
        }

    /** Which options are real on this device against this server, and why the others are not. */
    private fun options(server: ServerPushSupport): List<PushOption> =
        listOf(webPushOption(server), fcmOption(server), PushOption(PushChoice.PULL, true))

    private fun webPushOption(server: ServerPushSupport): PushOption =
        when {
            // Asked of the server first: a distributor is useless against an
            // instance with no VAPID key, and telling somebody to install one
            // would send them to the Play Store to fix a server.
            !server.webPush -> PushOption(PushChoice.WEB_PUSH, false, PushUnavailable.NO_VAPID)
            webPush.installed().isEmpty() ->
                PushOption(PushChoice.WEB_PUSH, false, PushUnavailable.NO_DISTRIBUTOR)
            else -> PushOption(PushChoice.WEB_PUSH, true)
        }

    /**
     * Whether Firebase can be offered, asked in the order that costs least — and **without starting
     * anything**.
     *
     * The flavour first: a `foss` build has no Firebase code and that answer can never change, so
     * asking the server would be a round trip to a conclusion already reached. Then the server,
     * because the session is cached. Then the device, through [FcmSupport.probe], which reads
     * whether Play services are usable and does no more than that.
     *
     * **Nothing here initialises Firebase or asks for a token**, and that is the point of `probe`
     * existing beside `prepare`. If drawing this screen started Firebase, then merely *opening
     * settings* would mint a registration token — an identifier Google holds and can route to — for
     * a user who came to choose "pull only". Registration happens in [startFcm], when somebody has
     * actually picked it.
     */
    private fun fcmOption(server: ServerPushSupport): PushOption {
        if (!fcm.isCompiledIn) {
            return PushOption(PushChoice.FCM, false, PushUnavailable.NOT_IN_THIS_BUILD)
        }

        server.fcmObjection?.let {
            return PushOption(PushChoice.FCM, false, it)
        }

        fcm.probe()?.let {
            return PushOption(PushChoice.FCM, false, it)
        }

        return PushOption(PushChoice.FCM, true)
    }

    /**
     * Moves this device to [choice], as far as a create can take it.
     *
     * Returns when the subscription exists — **not** when it delivers. The verification push
     * follows and the state carries [PushTransportState.isAwaitingVerification] until it is
     * answered.
     */
    suspend fun choose(choice: PushChoice): SwitchOutcome = mutex.withLock {
        state.chose(choice.wire)

        val previous = state.state.first()

        when (choice) {
            PushChoice.PULL -> stopPushing(previous)
            PushChoice.WEB_PUSH -> startWebPush()
            PushChoice.FCM -> startFcm()
        }
    }

    /**
     * Re-applies the stored choice.
     *
     * Called whenever a connection appears — launch, or signing in — and it has to be idempotent,
     * because that is a flow that re-emits. A device already registered on the transport it chose
     * and past the handshake is left alone; anything else is registered again, which the server
     * treats as a replace rather than a duplicate.
     */
    suspend fun reapply() {
        // First, and before every early return below, because the device the
        // sweep exists for is one that takes them. A phone that upgraded, moved
        // to Firebase once and has been live ever since never registers again --
        // and it is the phone still receiving over the abandoned `plmail` Web
        // Push row as well as its own. This is the only seam it passes through.
        // Once it succeeds it is a no-op; see PushRepository for why.
        push.sweepLegacySubscriptions(deviceClientId.value)

        val stored = state.state.first()
        val choice = PushChoice.of(stored.choice) ?: PushChoice.WEB_PUSH

        if (choice == PushChoice.PULL) return

        if (stored.isLive && PushChoice.of(stored.transport) == choice) return

        choose(choice)
    }

    /**
     * The user signed out.
     *
     * The registration is dropped locally and the transport released, but the server is
     * deliberately **not** asked to destroy the subscription: signing out is frequently the
     * reaction to a credential that has stopped working, and a destroy that needs the credential
     * would fail at exactly the moment it was needed. The subscription's own address stops
     * resolving — an unregistered FCM token destroys the row server-side on the next send, and an
     * uninstalled distributor's endpoint 410s — so the row does not survive being abandoned.
     */
    suspend fun signedOut() {
        mutex.withLock {
            webPush.unregister()
            runCatching { fcm.release() }
            state.forgotten()
        }
    }

    /**
     * A Web Push endpoint arrived from the distributor.
     *
     * Registered every time it arrives rather than only the first: a distributor may re-issue an
     * endpoint after its own server changes, and a subscription pointing at the old one fails
     * silently forever.
     */
    suspend fun endpointArrived(registration: PushRegistration): SwitchOutcome = mutex.withLock {
        record(
            push.subscribeWebPush(registration, deviceClientId.value),
            transport = PushChoice.WEB_PUSH,
            endpoint = registration.endpoint,
            token = null,
        )
    }

    /**
     * Firebase issued this device a new token.
     *
     * Rotation rather than re-registration where there is already an FCM subscription: the server
     * accepts `fcmToken` on an update precisely because Android reissues tokens on its own
     * schedule, and it re-arms the handshake, so the app treats the result exactly as it treats a
     * create. A device that was not on FCM — a `foss`-style install that just switched, or one
     * whose subscription was destroyed — falls through to a create.
     */
    suspend fun tokenRotated(token: String): SwitchOutcome = mutex.withLock {
        val stored = state.state.first()

        if (stored.fcmToken == token && stored.isLive) return@withLock SwitchOutcome.Unchanged

        val subscriptionId = stored.subscriptionId

        if (subscriptionId != null && PushChoice.of(stored.transport) == PushChoice.FCM) {
            if (push.rotateFcmToken(subscriptionId, token)) {
                // Recorded as a fresh registration, because that is what it
                // is: the server has re-armed the handshake and the
                // subscription is silent again until the new code is
                // echoed back.
                state.registered(
                    subscriptionId = subscriptionId,
                    transport = PushChoice.FCM.wire,
                    endpoint = null,
                    fcmToken = token,
                    at = System.currentTimeMillis(),
                )

                return@withLock SwitchOutcome.AwaitingVerification
            }
        }

        record(
            push.subscribeFcm(token, deviceClientId.value),
            transport = PushChoice.FCM,
            endpoint = null,
            token = token,
        )
    }

    private suspend fun stopPushing(previous: PushState): SwitchOutcome {
        previous.subscriptionId?.let { runCatching { push.unsubscribe(it) } }

        webPush.unregister()
        runCatching { fcm.release() }
        state.cleared()

        return SwitchOutcome.PullOnly
    }

    private suspend fun startWebPush(): SwitchOutcome {
        // Released before the distributor is asked for an endpoint, so a device
        // moving off Firebase gives its token up rather than leaving Google
        // holding a route to a phone that no longer listens.
        runCatching { fcm.release() }

        // The endpoint arrives asynchronously, in the transport's own callback,
        // which then calls back into endpointArrived above. There is no
        // synchronous form of this: RFC 8030 needs a push service and the app
        // is not one.
        return if (webPush.register()) SwitchOutcome.AwaitingEndpoint
        else {
            state.failed(NO_DISTRIBUTOR)
            SwitchOutcome.Failed(PushUnavailable.NO_DISTRIBUTOR, null)
        }
    }

    private suspend fun startFcm(): SwitchOutcome {
        val server = runCatching {
            ServerPushSupport.from(clients.current()?.session()?.push)
        }
            .getOrDefault(ServerPushSupport.UNKNOWN)

        server.fcmObjection?.let {
            return SwitchOutcome.Failed(it, null)
        }

        val config =
            server.fcmConfig
                ?: return SwitchOutcome.Failed(PushUnavailable.SERVER_CONFIG_INCOMPLETE, null)

        val availability = fcm.prepare(config)

        if (availability is FcmAvailability.Unavailable) {
            availability.detail?.let { state.failed(it) }

            return SwitchOutcome.Failed(availability.reason, availability.detail)
        }

        // The distributor is unregistered *after* Firebase is ready rather than
        // before, so a failure to start Firebase leaves the device on the
        // transport it had rather than on none.
        webPush.unregister()

        // The token arrives asynchronously, in the messaging service's
        // registration callback, which calls back into tokenRotated below.
        // Exactly the shape the Web Push path has, and for the same underlying
        // reason: the address of a device is issued by something that is not
        // this process.
        return SwitchOutcome.AwaitingEndpoint
    }

    private suspend fun record(
        outcome: SubscribeOutcome,
        transport: PushChoice,
        endpoint: String?,
        token: String?,
    ): SwitchOutcome =
        when (outcome) {
            is SubscribeOutcome.Registered -> {
                state.registered(
                    subscriptionId = outcome.subscriptionId,
                    transport = transport.wire,
                    endpoint = endpoint,
                    fcmToken = token,
                    at = System.currentTimeMillis(),
                )

                SwitchOutcome.AwaitingVerification
            }

            SubscribeOutcome.NoServer -> {
                state.failed(NO_SERVER)
                SwitchOutcome.Failed(null, NO_SERVER)
            }

            is SubscribeOutcome.Refused -> {
                val detail = "${outcome.type}: ${outcome.description.orEmpty()}".trim()

                state.failed(detail)

                SwitchOutcome.Failed(
                    // `forbidden` on an FCM create is the server saying
                    // Firebase is off, which the session should have said
                    // first. Mapped back onto the reason the screen already
                    // knows how to explain rather than shown as a raw error.
                    if (outcome.type == FORBIDDEN) PushUnavailable.SERVER_DISABLED else null,
                    detail,
                )
            }
        }

    private companion object {
        const val FORBIDDEN = "forbidden"

        /** In the app's own words, not translated: these sit beside a log somebody will grep. */
        const val NO_SERVER = "No server is connected, so nothing was registered."
        const val NO_DISTRIBUTOR = "No push distributor is installed on this device."
    }
}

/** How far [PushTransportManager.choose] got. */
sealed interface SwitchOutcome {
    /** Registered; the verification push is outstanding and nothing is delivered until it lands. */
    data object AwaitingVerification : SwitchOutcome

    /** The distributor was asked for an endpoint; registration follows when it answers. */
    data object AwaitingEndpoint : SwitchOutcome

    /** There is no subscription now, on purpose. */
    data object PullOnly : SwitchOutcome

    /** Already where it was asked to be. */
    data object Unchanged : SwitchOutcome

    data class Failed(val reason: PushUnavailable?, val detail: String?) : SwitchOutcome
}

/**
 * This device's stable identity to the server, as `deviceClientId`.
 *
 * Stable is the whole requirement: the server *replaces* the subscription matching it, which is
 * what makes a transport switch produce one subscription rather than two, and what stops a
 * reinstall accumulating a dead endpoint per install. Provided rather than derived at each call
 * site so there is exactly one definition of it.
 *
 * A `data class` rather than the `value class` this obviously wants to be: an inline class erases
 * to `String` on the JVM, and the constructor Dagger would have to call is mangled and private, so
 * the generated factory does not compile. The wrapper still does its real job, which is stopping
 * this being injectable as a bare `String` beside `@DeviceName`.
 */
data class DeviceClientId(val value: String)
