package de.plmail.jmap.methods

import de.plmail.jmap.protocol.JmapMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `PushSubscription/get` and `/set` (RFC 8620 §7.2). **No `accountId`** — these are per
 * authenticated user, not per mail account.
 *
 * ## Two transports, one object
 *
 * A create carrying `url` and `keys` is a **Web Push** subscription; a create carrying `fcmToken`
 * is a **Firebase** one, and `fcmToken` is plMail's extension of the RFC's object. Everything else
 * — `deviceClientId`, `types`, `expires`, the handshake — is identical, and the two shapes are
 * **exclusive**: a create carrying both is refused with `invalidProperties` rather than resolved by
 * precedence. That is why [NewPushSubscription] is a sealed pair rather than one class with
 * nullable fields; the illegal request is not expressible here.
 *
 * `deviceClientId` is stable per device and re-registering **replaces** the row, so a phone that
 * moved from a UnifiedPush distributor to Firebase has one subscription rather than two.
 *
 * ## The verification handshake is the whole point
 *
 * On create the server immediately sends a `PushVerification` object to the address given — POSTed
 * to the endpoint for Web Push, delivered as an ordinary FCM data message for Firebase, identical
 * JSON either way. The client reads the code out of it and echoes it back through an update.
 * **Until it does, the subscription receives nothing** — silently, and forever.
 *
 * That is what stops this endpoint being an open relay: without it anyone with an account could
 * register a stranger's address and have the server deliver to it on every state change. Budget for
 * the round trip in onboarding rather than treating it as an error path.
 *
 * ## What arrives
 *
 * Only `{"@type":"StateChange","changed":{"1":{"Email":"9"}}}` — deliberately tiny. **JMAP never
 * pushes mail content, only the news that a state token moved.** A push is a trigger for
 * `Email/changes`, not a message.
 */
class PushSubscriptionGet(private val ids: List<String>? = null) :
    JmapMethod<PushSubscriptionGetResult> {

    override val name = "PushSubscription/get"

    override fun arguments(): JsonObject = buildJsonObject {
        if (ids == null) {
            put("ids", JsonNull)
        } else {
            put("ids", buildJsonArray { ids.forEach { add(it) } })
        }
    }

    override fun decode(json: Json, arguments: JsonObject): PushSubscriptionGetResult =
        json.decodeFromJsonElement(PushSubscriptionGetResult.serializer(), arguments)
}

class PushSubscriptionSet(
    private val create: Map<String, NewPushSubscription> = emptyMap(),
    private val update: Map<String, PushSubscriptionPatch> = emptyMap(),
    private val destroy: List<String> = emptyList(),
) : JmapMethod<PushSubscriptionSetResult> {

    override val name = "PushSubscription/set"

    override fun arguments(): JsonObject = buildJsonObject {
        if (create.isNotEmpty()) {
            put("create", buildJsonObject { create.forEach { (id, s) -> put(id, s.toJson()) } })
        }

        if (update.isNotEmpty()) {
            put("update", buildJsonObject { update.forEach { (id, p) -> put(id, p.toJson()) } })
        }

        if (destroy.isNotEmpty()) {
            put("destroy", buildJsonArray { destroy.forEach { add(it) } })
        }
    }

    override fun decode(json: Json, arguments: JsonObject): PushSubscriptionSetResult =
        json.decodeFromJsonElement(PushSubscriptionSetResult.serializer(), arguments)

    companion object {
        /** Answers the server's verification push. Nothing is delivered until this lands. */
        fun verify(subscriptionId: String, code: String) =
            PushSubscriptionSet(
                update = mapOf(subscriptionId to PushSubscriptionPatch.verificationCode(code))
            )

        /**
         * Tells the server this device's FCM registration token has changed.
         *
         * The one address property an update may change, because Android reissues tokens on its own
         * schedule and refusing rotation would mean a device going permanently silent for doing
         * something normal. It **re-arms the handshake**: a fresh `PushVerification` goes to the
         * new token and nothing is delivered until that is answered too.
         */
        fun rotateFcmToken(subscriptionId: String, token: String) =
            PushSubscriptionSet(
                update = mapOf(subscriptionId to PushSubscriptionPatch.fcmToken(token))
            )
    }
}

/**
 * An address to deliver to: one shape or the other, never both.
 *
 * Sealed rather than one class with nullable `url` and `fcmToken`, because the server refuses a
 * create carrying both — and a request the protocol layer cannot express is one no caller can send
 * by accident.
 */
sealed interface NewPushSubscription {

    val deviceClientId: String

    /** Null means every type. `Identity` is never pushed regardless. */
    val types: List<String>?

    val expires: String?

    fun toJson(): JsonObject

    /**
     * An RFC 8030 endpoint the server can POST to, and the keys it encrypts to.
     *
     * On Android these values come from a UnifiedPush distributor: RFC 8030 needs a *push service*
     * that owns the endpoint URL and holds the connection, browsers ship one and native apps do
     * not. The connector generates the P-256 keypair and decrypts the RFC 8291 `aes128gcm`
     * payloads, which is exactly what the server sends — so no server change is needed to support
     * it.
     */
    data class WebPush(
        override val deviceClientId: String,
        val url: String,
        /** Client P-256 ECDH public key, base64url. */
        val p256dh: String,
        /** Client auth secret, base64url. */
        val auth: String,
        override val types: List<String>? = DEFAULT_TYPES,
        override val expires: String? = null,
    ) : NewPushSubscription {

        override fun toJson(): JsonObject = buildJsonObject {
            put("deviceClientId", deviceClientId)
            put("url", url)
            put(
                "keys",
                buildJsonObject {
                    put("p256dh", p256dh)
                    put("auth", auth)
                },
            )
            put("types", encodeTypes(types))
            expires?.let { put("expires", it) }
        }
    }

    /**
     * A Firebase registration token.
     *
     * No URL and no keys: FCM is not a place the server can POST to, it is an API the server calls
     * with a token that addresses one install of one app on one device. The server refuses this
     * shape outright (`forbidden`) on an instance where Firebase is unconfigured or switched off,
     * so check the session's `fcm` first — that refusal is a backstop, not the check.
     */
    data class Fcm(
        override val deviceClientId: String,
        val fcmToken: String,
        override val types: List<String>? = DEFAULT_TYPES,
        override val expires: String? = null,
    ) : NewPushSubscription {

        override fun toJson(): JsonObject = buildJsonObject {
            put("deviceClientId", deviceClientId)
            put("fcmToken", fcmToken)
            put("types", encodeTypes(types))
            expires?.let { put("expires", it) }
        }
    }

    companion object {
        /**
         * The types worth waking a phone for.
         *
         * `Identity` is excluded because it changes only when the user edits their own sending
         * addresses — which they just did, in this app.
         */
        val DEFAULT_TYPES = listOf("Email", "Mailbox", "Thread", "EmailSubmission")

        private fun encodeTypes(types: List<String>?): JsonElement =
            types?.let { list -> buildJsonArray { list.forEach { add(it) } } } ?: JsonNull
    }
}

class PushSubscriptionPatch private constructor(private val fields: Map<String, JsonElement>) {

    fun toJson(): JsonObject = JsonObject(fields)

    companion object {
        fun verificationCode(code: String) =
            PushSubscriptionPatch(mapOf("verificationCode" to JsonPrimitive(code)))

        fun fcmToken(token: String) =
            PushSubscriptionPatch(mapOf("fcmToken" to JsonPrimitive(token)))
    }
}

@Serializable
data class PushSubscriptionGetResult(
    val state: String = "",
    val list: List<PushSubscriptionInfo> = emptyList(),
    val notFound: List<String> = emptyList(),
)

/**
 * What a `PushSubscription/get` returns, which is **deliberately narrower than the stored object**.
 *
 * Neither `keys` nor `fcmToken` nor `verificationCode` is ever echoed back: each of them is the
 * address of, or the key to, one device, and returning one would let anyone who can read a single
 * response forge pushes to that phone. So this object cannot answer "am I verified?" — see
 * [PushSubscriptionTransport] and `PushRepository` for what can.
 */
@Serializable
data class PushSubscriptionInfo(
    val id: String = "",
    val deviceClientId: String = "",
    /**
     * Which kind this turned out to be, as a plMail extension: `webpush` or `fcm`.
     *
     * Read-only, and needed precisely because `deviceClientId` is stable and a create *replaces*
     * the row. Absent on an instance predating the FCM work, where `webpush` is the only answer
     * there was.
     */
    val transport: String? = null,
    /**
     * Null on an FCM subscription — there is no URL, and one would be a value a client might POST
     * to.
     */
    val url: String? = null,
    val types: List<String>? = null,
    val expires: String? = null,
) {
    /** The transport, defaulting to Web Push on a server that does not name one. */
    val transportKind: PushSubscriptionTransport
        get() = PushSubscriptionTransport.of(transport)
}

/** The two kinds of subscription the server distinguishes. */
enum class PushSubscriptionTransport(val wire: String) {
    WEB_PUSH("webpush"),
    FCM("fcm");

    companion object {
        /**
         * Web Push for anything unrecognised, including null.
         *
         * An instance older than the FCM work names no transport and only ever had one, so the
         * default is a true statement about it rather than a guess.
         */
        fun of(wire: String?): PushSubscriptionTransport =
            entries.firstOrNull { it.wire == wire } ?: WEB_PUSH
    }
}

@Serializable
data class PushSubscriptionSetResult(
    val created: Map<String, CreatedPushSubscription> = emptyMap(),
    val notCreated: Map<String, SetError> = emptyMap(),
    val updated: Map<String, JsonElement?> = emptyMap(),
    val notUpdated: Map<String, SetError> = emptyMap(),
    val destroyed: List<String> = emptyList(),
    val notDestroyed: Map<String, SetError> = emptyMap(),
)

@Serializable data class CreatedPushSubscription(val id: String = "", val expires: String? = null)

/** What the server POSTs to the push endpoint to prove the client can read it. */
@Serializable
data class PushVerification(
    val pushSubscriptionId: String = "",
    val verificationCode: String = "",
)

/** What every later push carries — a trigger, never content. */
@Serializable
data class StateChange(
    /** Per account id, the object types whose state token moved. */
    val changed: Map<String, Map<String, String>> = emptyMap()
)
