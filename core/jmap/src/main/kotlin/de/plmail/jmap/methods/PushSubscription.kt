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
 * ## The verification handshake is the whole point
 *
 * On create the server immediately POSTs a `PushVerification` object to the URL given. The client
 * reads the code out of it and echoes it back through an update. **Until it does, the subscription
 * receives nothing** — silently, and forever.
 *
 * That is what stops this endpoint being an open relay: without it anyone with an account could
 * register a stranger's URL and have the server POST to it on every state change. Budget for the
 * round trip in onboarding rather than treating it as an error path.
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
        /** Answers the server's verification POST. Nothing is delivered until this lands. */
        fun verify(subscriptionId: String, code: String) =
            PushSubscriptionSet(
                update = mapOf(subscriptionId to PushSubscriptionPatch.verificationCode(code))
            )
    }
}

/**
 * A push endpoint to deliver to.
 *
 * On Android these values come from a UnifiedPush distributor: RFC 8030 needs a *push service* that
 * owns the endpoint URL and holds the connection, browsers ship one and native apps do not. The
 * connector generates the P-256 keypair and decrypts the RFC 8291 `aes128gcm` payloads, which is
 * exactly what the server sends — so no server change is needed to support it.
 */
data class NewPushSubscription(
    val deviceClientId: String,
    val url: String,
    /** Client P-256 ECDH public key, base64url. */
    val p256dh: String,
    /** Client auth secret, base64url. */
    val auth: String,
    /** Null means every type. `Identity` is never pushed regardless. */
    val types: List<String>? = DEFAULT_TYPES,
    val expires: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("deviceClientId", deviceClientId)
        put("url", url)
        put(
            "keys",
            buildJsonObject {
                put("p256dh", p256dh)
                put("auth", auth)
            },
        )
        put(
            "types",
            types?.let { list -> buildJsonArray { list.forEach { add(it) } } } ?: JsonNull,
        )
        expires?.let { put("expires", it) }
    }

    companion object {
        /**
         * The types worth waking a phone for.
         *
         * `Identity` is excluded because it changes only when the user edits their own sending
         * addresses — which they just did, in this app.
         */
        val DEFAULT_TYPES = listOf("Email", "Mailbox", "Thread", "EmailSubmission")
    }
}

class PushSubscriptionPatch private constructor(private val fields: Map<String, JsonElement>) {

    fun toJson(): JsonObject = JsonObject(fields)

    companion object {
        fun verificationCode(code: String) =
            PushSubscriptionPatch(mapOf("verificationCode" to JsonPrimitive(code)))
    }
}

@Serializable
data class PushSubscriptionGetResult(
    val state: String = "",
    val list: List<PushSubscriptionInfo> = emptyList(),
    val notFound: List<String> = emptyList(),
)

@Serializable
data class PushSubscriptionInfo(
    val id: String = "",
    val deviceClientId: String = "",
    val url: String = "",
    val types: List<String>? = null,
    val expires: String? = null,
    /**
     * Present until the handshake completes. A subscription still carrying one is registered but
     * **receiving nothing**, which is the single most likely reason push "does not work".
     */
    val verificationCode: String? = null,
)

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
