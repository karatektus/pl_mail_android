package de.plmail.core.data

import de.plmail.core.datastore.PushStateStore
import de.plmail.jmap.client.JmapClient
import de.plmail.jmap.methods.NewPushSubscription
import de.plmail.jmap.methods.PushSubscriptionGet
import de.plmail.jmap.methods.PushSubscriptionInfo
import de.plmail.jmap.methods.PushSubscriptionSet
import de.plmail.jmap.methods.PushVerification
import de.plmail.jmap.methods.SetError
import de.plmail.jmap.methods.StateChange
import de.plmail.jmap.protocol.MethodResults
import de.plmail.jmap.protocol.RequestBuilder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.jsonObject

/**
 * An endpoint a Web Push transport has obtained, in the shape JMAP needs.
 *
 * RFC 8030 terms, because that is what `PushSubscription` speaks: a URL the server can POST to, and
 * the two keys it encrypts to. FCM cannot be described here — its registration is a token addressed
 * through Google's API rather than a place the server can reach — which is why it registers through
 * [PushRepository.subscribeFcm] and its own create shape instead.
 */
data class PushRegistration(val endpoint: String, val p256dh: String, val auth: String)

/** What a delivered push turned out to be. */
sealed interface PushPayload {
    /** The handshake. Until this is echoed back, the subscription receives nothing. */
    data class Verification(val subscriptionId: String, val code: String) : PushPayload

    /** The ordinary case: a state token moved. Never content. */
    data class Changed(val accounts: Map<String, Map<String, String>>) : PushPayload

    /** Something else arrived on our endpoint. Ignored rather than trusted. */
    data object Unrecognised : PushPayload
}

/**
 * What one attempt to register produced.
 *
 * Three cases and not a nullable id, because the middle one is the reason FCM needs a settings
 * screen at all: `forbidden` from a server whose administrator has not switched Firebase on is a
 * sentence somebody can act on, and collapsing it into "registration failed" strands them.
 */
sealed interface SubscribeOutcome {
    /** Registered. **Not yet live** — that waits on the verification push. */
    data class Registered(val subscriptionId: String) : SubscribeOutcome

    /** There is no server to register with. Not an error; the app spends its first launch here. */
    data object NoServer : SubscribeOutcome

    /** The server refused, in its own words. `forbidden` is the FCM-is-switched-off case. */
    data class Refused(val type: String, val description: String?) : SubscribeOutcome
}

/**
 * Registers this device for push, completes the handshake, and is the one door every delivery comes
 * through.
 *
 * ## The handshake
 *
 * On create the server immediately sends a `PushVerification` to the address given — POSTed to the
 * endpoint for Web Push, delivered as a data message for FCM. The client reads the code and echoes
 * it back through an update. **Until that lands the subscription receives nothing — silently, and
 * forever.** It is what stops the endpoint being an open relay: without it, anyone with an account
 * could register a stranger's address and have the server deliver to it on every state change.
 *
 * So registration is two-phase, the second phase arrives as a *push*, and there is no synchronous
 * way to finish it. Switching transports has to budget for that round trip rather than reporting
 * success at the create.
 *
 * ## One door
 *
 * [deliver] is called by every transport — the UnifiedPush receiver, the Firebase service, and the
 * EventSource stream through [delivered]. All three record into the same [PushLog] and hand every
 * state change to the same [StateChangeApplier]. Forking that per transport is how two paths come
 * to disagree about when a sync is worth making, on a product where one of them runs only while the
 * app is on screen and the other only while it is not.
 */
@Singleton
class PushRepository
@Inject
constructor(
    private val clients: AccountClients,
    private val changes: StateChangeApplier,
    private val log: PushLog,
    private val state: PushStateStore,
) {

    /**
     * Registers a Web Push endpoint, replacing any previous subscription for this device.
     *
     * `deviceClientId` is stable per device and the server *replaces* the row it matches, so a
     * phone moving here from Firebase ends with one subscription rather than two.
     */
    suspend fun subscribeWebPush(
        registration: PushRegistration,
        deviceClientId: String,
    ): SubscribeOutcome =
        create(
            NewPushSubscription.WebPush(
                deviceClientId = deviceClientId,
                url = registration.endpoint,
                p256dh = registration.p256dh,
                auth = registration.auth,
            )
        )

    /**
     * Registers a Firebase token, replacing any previous subscription for this device.
     *
     * The server refuses this shape with `forbidden` on an instance where Firebase is unconfigured
     * or switched off. That refusal is a backstop rather than the check — the session's `fcm` says
     * the same thing earlier and without a round trip — but it is the one that catches an
     * administrator switching Firebase off while a phone is registered against it.
     */
    suspend fun subscribeFcm(token: String, deviceClientId: String): SubscribeOutcome =
        create(NewPushSubscription.Fcm(deviceClientId = deviceClientId, fcmToken = token))

    /**
     * Tells the server the device's FCM token has changed.
     *
     * Android reissues registration tokens on its own schedule and a subscription pointing at the
     * old one goes silent without saying so. The update **re-arms the handshake** — `verified` goes
     * back to false server-side and a fresh `PushVerification` is sent to the new token — so the
     * caller must treat this exactly as it treats a create.
     */
    suspend fun rotateFcmToken(subscriptionId: String, token: String): Boolean {
        val client = clients.current() ?: return false
        val request = RequestBuilder()
        val set = request.add(PushSubscriptionSet.rotateFcmToken(subscriptionId, token))

        val result = client.send(request).result(set)

        return result.notUpdated.isEmpty()
    }

    /**
     * Handles a delivered push, whatever carried it.
     *
     * Recorded **before** it is interpreted and whatever it turns out to say. A payload the client
     * cannot parse still proves the chain works end to end, so logging only the ones it understood
     * would hide a client bug behind "push is not working".
     */
    suspend fun deliver(payload: String, via: PushDelivery): PushPayload {
        val parsed = parse(payload)
        val now = System.currentTimeMillis()

        state.received(now, via.wire)

        when (parsed) {
            is PushPayload.Verification -> {
                val answered = verify(parsed)

                log.record(
                    ReceivedPush(
                        at = now,
                        transport = via.wire,
                        type = VERIFICATION_TYPE,
                        note = if (answered) NOTE_VERIFIED else NOTE_VERIFY_FAILED,
                    )
                )
            }

            is PushPayload.Changed -> {
                log.record(
                    ReceivedPush(
                        at = now,
                        transport = via.wire,
                        type = STATE_CHANGE_TYPE,
                        changed = parsed.accounts.mapValues { (_, types) -> types.keys.sorted() },
                    )
                )

                // A push is a trigger, never the news itself. It says a state
                // token moved; what moved is Email/changes' job to find out --
                // and that is the same sentence an EventSource `state` event
                // carries, so both are answered by one class rather than by two
                // that can drift apart.
                changes.apply(parsed.accounts)
            }

            PushPayload.Unrecognised ->
                log.record(
                    ReceivedPush(
                        at = now,
                        transport = via.wire,
                        type = UNKNOWN_TYPE,
                        note = NOTE_UNPARSEABLE,
                    )
                )
        }

        return parsed
    }

    /** The bytes overload, for a transport that hands over an undecoded payload. */
    suspend fun deliver(payload: ByteArray, via: PushDelivery): PushPayload =
        deliver(payload.decodeToString(), via)

    /**
     * A state change that arrived down the EventSource stream.
     *
     * Logged and applied by the same two calls [deliver] makes, rather than by a second copy of the
     * decision. The stream is not a push and is recorded as itself precisely so a user watching the
     * log can see that it was the open app, not the subscription, doing the work.
     */
    suspend fun delivered(changed: Map<String, Map<String, String>>, via: PushDelivery) {
        val now = System.currentTimeMillis()

        state.received(now, via.wire)

        log.record(
            ReceivedPush(
                at = now,
                transport = via.wire,
                type = STATE_CHANGE_TYPE,
                changed = changed.mapValues { (_, types) -> types.keys.sorted() },
            )
        )

        changes.apply(changed)
    }

    /**
     * Echoes the code back, which is what makes the subscription start delivering.
     *
     * The local record is written from the same place, because this device is the only thing in the
     * system that knows the handshake completed: `PushSubscription/get` never returns
     * `verificationCode` to anyone, by design.
     */
    private suspend fun verify(verification: PushPayload.Verification): Boolean {
        val client = clients.current() ?: return false
        val request = RequestBuilder()
        val set =
            request.add(PushSubscriptionSet.verify(verification.subscriptionId, verification.code))

        val accepted = runCatching {
            client.send(request).result(set).notUpdated.isEmpty()
        }
            .getOrDefault(false)

        if (accepted) state.verified(System.currentTimeMillis())

        return accepted
    }

    /** Removes this device's subscription, e.g. when the transport changes to pull-only. */
    suspend fun unsubscribe(subscriptionId: String) {
        val client = clients.current() ?: return
        val request = RequestBuilder()

        request.add(PushSubscriptionSet(destroy = listOf(subscriptionId)))
        client.send(request)
    }

    /**
     * What the server holds for one subscription id, or null when it holds nothing.
     *
     * **This cannot tell you whether the subscription is verified**, and no `/get` can: `keys`,
     * `fcmToken` and `verificationCode` are all write-only, because echoing any of them would let
     * whoever could read one response deliver to that device. The app used to read
     * `verificationCode` back and call a null one verified, which made the check structurally
     * incapable of returning false — it lives in [PushStateStore] now, recorded at the moment the
     * code was echoed.
     *
     * What this *can* prove is that the row still exists and which transport it ended up on — which
     * is the check that catches a token Firebase reported as `UNREGISTERED`, because that destroys
     * the subscription server-side exactly as a 410 does for Web Push.
     */
    suspend fun describe(subscriptionId: String): PushSubscriptionInfo? {
        val client = clients.current() ?: return null
        val request = RequestBuilder()
        val get = request.add(PushSubscriptionGet(ids = listOf(subscriptionId)))

        return client.send(request).result(get).list.singleOrNull()
    }

    private suspend fun create(subscription: NewPushSubscription): SubscribeOutcome {
        val client = clients.current() ?: return SubscribeOutcome.NoServer

        // Before registering under this device's own id, and never instead of
        // it. See the method for what it removes and why it cannot remove
        // anything a working install depends on.
        sweepLegacySubscriptions(client, deviceClientId = subscription.deviceClientId)

        val request = RequestBuilder()
        val set = request.add(PushSubscriptionSet(create = mapOf(CREATE_ID to subscription)))

        val result = client.send(request).result(set)

        result.created[CREATE_ID]?.let {
            return SubscribeOutcome.Registered(it.id)
        }

        val refusal: SetError? = result.notCreated[CREATE_ID]

        return SubscribeOutcome.Refused(
            type = refusal?.type.orEmpty().ifBlank { UNKNOWN_REFUSAL },
            description = refusal?.description,
        )
    }

    /**
     * Destroys the subscription left behind by the builds whose `deviceClientId` was the constant
     * `"plmail"`. Once per install, and never in place of the registration that follows it.
     *
     * ## Why there is an orphan at all
     *
     * The server's radio semantics are *per device id*: re-registering **replaces** the row
     * matching `deviceClientId`, which is what makes moving from a distributor to Firebase produce
     * one subscription rather than two. Until recently that id was the literal `"plmail"` on every
     * install. Fixing it to a per-install hash also moved this device out from under its own old
     * row — so the phone now holds a Firebase subscription under `plmail-<hash>` while the server
     * still holds a Web Push one under `plmail`, and delivers to **both**. The transport picker
     * cannot help: it enforces one-of by relying on the replace, and the replace only ever looks at
     * one id.
     *
     * ## Why destroying it is safe
     *
     * Every install of the broken builds shared that one id and therefore replaced each other's
     * rows anyway — whichever registered last owned it and the rest were already silent. So no
     * working setup can depend on the `plmail` row: destroying it removes either a duplicate of
     * this device's real subscription or a corpse belonging to an install that lost the race years
     * ago. The one install for which it is *not* a corpse is one still running the old scheme, and
     * that install is asking for `plmail` here — which is why [deviceClientId] is checked first.
     *
     * That check is not theoretical: `DeviceClientId` falls back to the bare constant on a device
     * that answers neither `ANDROID_ID` nor `Build.MODEL`, and such a device destroying the row it
     * is about to create under is a phone that deletes its own push on every registration.
     *
     * ## Why a failure is not the caller's problem
     *
     * A server that refuses the destroy is a server the device still has to register with. The
     * refusal goes in the push log — where the user is already looking when push misbehaves — and
     * the flag stays unset, so the next registration tries again.
     *
     * ## Why this is also called from outside a registration
     *
     * [create] is not enough on its own, and the device it misses is exactly the one this was
     * written for. A phone that upgraded, re-registered once, and has been happily live on Firebase
     * ever since never creates again: `PushTransportManager.reapply` returns early on a live
     * subscription and `tokenRotated` answers `Unchanged` for a token that has not moved. It would
     * go on receiving over both transports forever. So the sweep is also made at push-state
     * initialisation, where a device that is doing nothing at all still passes through.
     */
    suspend fun sweepLegacySubscriptions(deviceClientId: String) {
        // Null on a device that is not paired yet, which is where the app spends
        // its first launch. The flag stays unset and the next connection tries.
        val client = clients.current() ?: return

        sweepLegacySubscriptions(client, deviceClientId)
    }

    private suspend fun sweepLegacySubscriptions(client: JmapClient, deviceClientId: String) {
        if (deviceClientId == LEGACY_DEVICE_CLIENT_ID) return
        if (state.state.first().hasSweptLegacySubscriptions) return

        val refusal = runCatching {
            destroyLegacySubscriptions(client)
        }
            .getOrElse { failure -> failure.message ?: failure::class.simpleName.orEmpty() }

        if (refusal == null) {
            state.sweptLegacySubscriptions()
            return
        }

        log.record(
            ReceivedPush(
                at = System.currentTimeMillis(),
                transport = LOCAL_EVENT,
                type = LEGACY_SWEEP_TYPE,
                note = "$NOTE_SWEEP_REFUSED $refusal",
            )
        )
    }

    /**
     * Null when there is nothing left to remove; the refusal, in the server's words, when there is.
     */
    private suspend fun destroyLegacySubscriptions(client: JmapClient): String? {
        val lookup = RequestBuilder()
        // Every subscription this user has, not this device's: the whole point
        // is the row registered under an id this device no longer uses, so
        // there is nothing to ask for by id.
        val all = lookup.add(PushSubscriptionGet())

        val legacy =
            client
                .send(lookup)
                .result(all)
                .list
                .filter { it.deviceClientId == LEGACY_DEVICE_CLIENT_ID }
                .map { it.id }

        // The ordinary case on a clean install, and on every device that has
        // already been swept by a build that then failed to record it.
        if (legacy.isEmpty()) return null

        val request = RequestBuilder()
        val set = request.add(PushSubscriptionSet(destroy = legacy))
        val result = client.send(request).result(set)

        // A row that is not there is the outcome this wanted. Racing another
        // install of the old build, or running twice because the flag never
        // landed, both end here -- and neither is worth a line in the user's
        // log.
        val refused = result.notDestroyed.filterValues { it.type != NOT_FOUND }

        if (refused.isNotEmpty()) {
            return refused.entries.joinToString { (id, error) ->
                "$id: ${error.type} ${error.description.orEmpty()}".trim()
            }
        }

        log.record(
            ReceivedPush(
                at = System.currentTimeMillis(),
                transport = LOCAL_EVENT,
                type = LEGACY_SWEEP_TYPE,
                note = "$NOTE_SWEPT ${result.destroyed.ifEmpty { legacy }.joinToString()}",
            )
        )

        return null
    }

    private fun parse(payload: String): PushPayload =
        try {
            val json = MethodResults.JMAP_JSON.parseToJsonElement(payload)
            val fields = json.jsonObject

            when {
                fields.containsKey("verificationCode") -> {
                    val verification =
                        MethodResults.JMAP_JSON.decodeFromJsonElement(
                            PushVerification.serializer(),
                            json,
                        )

                    PushPayload.Verification(
                        verification.pushSubscriptionId,
                        verification.verificationCode,
                    )
                }

                fields.containsKey("changed") ->
                    PushPayload.Changed(
                        MethodResults.JMAP_JSON.decodeFromJsonElement(
                                StateChange.serializer(),
                                json,
                            )
                            .changed
                    )

                else -> PushPayload.Unrecognised
            }
        } catch (malformed: SerializationException) {
            // Anything unreadable is ignored rather than retried. The endpoint
            // is reachable by whoever holds its URL, so an unparseable payload
            // is as likely to be noise as a bug -- and the log above records
            // that one arrived either way.
            PushPayload.Unrecognised
        } catch (malformed: IllegalArgumentException) {
            PushPayload.Unrecognised
        }

    private companion object {
        /** The creation id; only ever one subscription per device. */
        const val CREATE_ID = "device"

        const val STATE_CHANGE_TYPE = "StateChange"
        const val VERIFICATION_TYPE = "PushVerification"
        const val UNKNOWN_TYPE = "unknown"
        const val UNKNOWN_REFUSAL = "unknownError"

        /**
         * The `deviceClientId` every install of the broken builds registered under.
         *
         * The same string as `PushSetup.INSTANCE` in `:app`, which is where the live id is built
         * and which this module cannot see. Written out rather than shared because it is a *wire
         * value already in server tables*: it must not follow the app's constant if that ever
         * changes again, and it is a literal in the request this sends either way.
         */
        const val LEGACY_DEVICE_CLIENT_ID = "plmail"

        /** RFC 8620's `SetError` for a destroy naming a row that is not there. */
        const val NOT_FOUND = "notFound"

        /**
         * The badge for a log line that is not a delivery.
         *
         * No [PushDelivery] matches, on purpose — nothing arrived. The screen draws an unrecognised
         * transport in its faintest colour and prints the wire string, which is exactly the
         * treatment a note about the app's own housekeeping should get beside real deliveries.
         */
        const val LOCAL_EVENT = "local"

        const val LEGACY_SWEEP_TYPE = "LegacySubscriptionSweep"

        /**
         * Notes in the app's own words, not translated. They sit beside a server log the reader is
         * going to grep, and a translated string is one they cannot search for.
         */
        const val NOTE_VERIFIED = "Verification code echoed back; the subscription is now live."
        const val NOTE_VERIFY_FAILED = "Verification code could not be echoed back."
        const val NOTE_UNPARSEABLE = "Payload could not be parsed; delivery itself worked."
        const val NOTE_SWEPT =
            "Destroyed the pre-upgrade subscription registered under deviceClientId " +
                "\"$LEGACY_DEVICE_CLIENT_ID\", which was delivering alongside this device's own:"
        const val NOTE_SWEEP_REFUSED =
            "Could not remove the pre-upgrade \"$LEGACY_DEVICE_CLIENT_ID\" subscription; " +
                "registration went ahead and this will be retried. The server said:"
    }
}
