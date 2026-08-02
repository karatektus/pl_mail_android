package de.plmail.core.data

import de.plmail.jmap.methods.NewPushSubscription
import de.plmail.jmap.methods.PushSubscriptionGet
import de.plmail.jmap.methods.PushSubscriptionSet
import de.plmail.jmap.methods.PushVerification
import de.plmail.jmap.methods.StateChange
import de.plmail.jmap.protocol.MethodResults
import de.plmail.jmap.protocol.RequestBuilder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.jsonObject

/**
 * An endpoint a push transport has obtained, in the shape JMAP needs.
 *
 * RFC 8030 terms, because that is what `PushSubscription` speaks: a URL the server can POST to, and
 * the two keys it encrypts to. A transport that cannot produce a URL — FCM, whose registration is a
 * token addressed through Google's API rather than a place the server can reach — cannot be
 * described here, and needs the server to grow a sender of its own.
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
 * Registers this device for push and completes the handshake.
 *
 * The handshake is the part worth being careful about. On create the server immediately POSTs a
 * `PushVerification` to the endpoint; the client reads the code and echoes it back through an
 * update. **Until that lands the subscription receives nothing — silently, and forever.** It is
 * what stops the endpoint being an open relay: without it, anyone with an account could register a
 * stranger's URL and have the server POST to it on every state change.
 *
 * So this is deliberately two-phase, and the second phase arrives as a *push*. There is no
 * synchronous way to finish registering.
 */
@Singleton
class PushRepository
@Inject
constructor(private val clients: AccountClients, private val changes: StateChangeApplier) {

    /**
     * Registers [registration], replacing any previous subscription for this device.
     *
     * Returns the subscription id, or null when there is no server to register with. The
     * subscription is **not yet live** — it becomes live when the verification push is answered.
     */
    suspend fun subscribe(registration: PushRegistration, deviceClientId: String): String? {
        val client = clients.current() ?: return null

        val request = RequestBuilder()
        val set =
            request.add(
                PushSubscriptionSet(
                    create =
                        mapOf(
                            CREATE_ID to
                                NewPushSubscription(
                                    deviceClientId = deviceClientId,
                                    url = registration.endpoint,
                                    p256dh = registration.p256dh,
                                    auth = registration.auth,
                                )
                        )
                )
            )

        val result = client.send(request).result(set)

        return result.created[CREATE_ID]?.id
    }

    /**
     * Handles a delivered push.
     *
     * Both cases arrive on the same endpoint and are told apart by shape, because the server does
     * not label them: a verification carries a `verificationCode`, a state change carries
     * `changed`.
     */
    suspend fun handle(payload: ByteArray): PushPayload {
        val parsed = parse(payload)

        when (parsed) {
            is PushPayload.Verification -> verify(parsed)

            is PushPayload.Changed ->
                // A push is a trigger, never the news itself. It says a state
                // token moved; what moved is Email/changes' job to find out --
                // and that is the same sentence an EventSource `state` event
                // carries, so both are answered by one class rather than by two
                // that can drift apart.
                changes.apply(parsed.accounts)

            PushPayload.Unrecognised -> Unit
        }

        return parsed
    }

    /** Echoes the code back, which is what makes the subscription start delivering. */
    private suspend fun verify(verification: PushPayload.Verification) {
        val client = clients.current() ?: return
        val request = RequestBuilder()

        request.add(PushSubscriptionSet.verify(verification.subscriptionId, verification.code))

        client.send(request)
    }

    /** Removes this device's subscription, e.g. when the last account is signed out. */
    suspend fun unsubscribe(subscriptionId: String) {
        val client = clients.current() ?: return
        val request = RequestBuilder()

        request.add(PushSubscriptionSet(destroy = listOf(subscriptionId)))
        client.send(request)
    }

    /**
     * Whether a subscription is registered *and* verified.
     *
     * A subscription still carrying a `verificationCode` is registered and receiving nothing, which
     * is the single most likely reason push "does not work" — worth being able to state plainly on
     * a diagnostics screen rather than leaving someone to guess.
     */
    suspend fun isLive(subscriptionId: String): Boolean {
        val client = clients.current() ?: return false
        val request = RequestBuilder()
        val get = request.add(PushSubscriptionGet(ids = listOf(subscriptionId)))

        return client.send(request).result(get).list.singleOrNull()?.verificationCode == null
    }

    private fun parse(payload: ByteArray): PushPayload =
        try {
            val json = MethodResults.JMAP_JSON.parseToJsonElement(payload.decodeToString())
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
            // is as likely to be noise as a bug.
            PushPayload.Unrecognised
        } catch (malformed: IllegalArgumentException) {
            PushPayload.Unrecognised
        }

    private companion object {
        /** The creation id; only ever one subscription per device. */
        const val CREATE_ID = "device"
    }
}
