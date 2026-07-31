package de.plmail.jmap.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Assembles one batch of method calls.
 *
 * Batching is not an optimisation here. The server may be a Raspberry Pi on a domestic uplink, and
 * the session advertises `maxConcurrentRequests: 4`, so round trips are the scarce resource —
 * pairing a query with the `Email/get` that consumes it, in one request, is the difference the user
 * actually feels.
 *
 * Call ids are generated rather than caller-supplied. They exist only to match answers to
 * questions, and letting callers name them invites two calls sharing one id, which the server will
 * happily accept and answer ambiguously.
 */
class RequestBuilder(
    private val using: List<String> = Capability.USING_MAIL,
    private val maxCallsInRequest: Int = DEFAULT_MAX_CALLS,
) {
    private val calls = mutableListOf<JsonArray>()
    private var nextCallId = 0

    val isEmpty: Boolean
        get() = calls.isEmpty()

    val size: Int
        get() = calls.size

    fun <R> add(method: JmapMethod<R>): MethodHandle<R> {
        check(calls.size < maxCallsInRequest) {
            "This request already holds $maxCallsInRequest calls, which is what this server " +
                "accepts. Split it rather than letting the server reject the whole batch."
        }

        val callId = "c${nextCallId++}"

        calls += buildJsonArray {
            add(method.name)
            add(method.arguments())
            add(callId)
        }

        return MethodHandle(method, callId)
    }

    fun build(): JsonObject = buildJsonObject {
        put("using", buildJsonArray { using.forEach { add(it) } })
        put("methodCalls", JsonArray(calls))
    }

    companion object {
        /** RFC 8620's own default, used only until a session says otherwise. */
        const val DEFAULT_MAX_CALLS = 16
    }
}

/**
 * Marks an argument as a back-reference to an earlier call's result.
 *
 * Use inside a method's `arguments()`:
 * ```
 * buildJsonObject {
 *     put("accountId", accountId.value)
 *     backReference("ids", queryHandle.reference("/ids"))
 * }
 * ```
 *
 * The `#` prefix on the argument name is what tells the server this is a reference rather than a
 * literal, and forgetting it is not an error the server reports — it simply sees an unknown
 * argument named `ids` holding an object, and answers as though it were never sent.
 */
fun JsonObjectBuilder.backReference(argumentName: String, reference: ResultReference) {
    putJsonObject("#$argumentName") {
        put("resultOf", reference.resultOf)
        put("name", reference.name)
        put("path", reference.path)
    }
}
