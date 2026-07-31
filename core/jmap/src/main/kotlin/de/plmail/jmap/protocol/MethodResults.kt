package de.plmail.jmap.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The answers to one batch, addressable by the handle that asked the question.
 *
 * A batch is **partially failable**: HTTP 200 with one `error` entry among five successes is the
 * normal shape, not an anomaly. So this deliberately does not throw on construction. A caller
 * decides per call whether a failure is fatal — the sidebar can render even when one account's
 * mailbox list failed, and one unreachable account must never blank a unified inbox.
 */
class MethodResults(val responses: List<Invocation>, val sessionState: String) {

    /** The answer to one call, or the failure that replaced it. */
    fun <R> result(handle: MethodHandle<R>): R {
        failure(handle)?.let { throw it }

        val invocation =
            responses.firstOrNull { it.callId == handle.callId }
                ?: throw JmapError.MalformedResponse(
                    "The server answered without a result for ${handle.method.name} " +
                        "(call ${handle.callId})."
                )

        return handle.method.decode(JMAP_JSON, invocation.arguments)
    }

    fun <R> resultOrNull(handle: MethodHandle<R>): R? = runCatching { result(handle) }.getOrNull()

    /** The failure for one call, if it failed. Does not throw. */
    fun failure(handle: MethodHandle<*>): JmapError.MethodFailed? {
        val invocation =
            responses.firstOrNull { it.callId == handle.callId && it.isError } ?: return null

        return JmapError.MethodFailed(
            type = invocation.arguments["type"]?.jsonPrimitive?.content ?: "unknownMethod",
            callId = handle.callId,
            description = invocation.arguments["description"]?.jsonPrimitive?.content,
        )
    }

    companion object {
        /**
         * Lenient about unknown keys, and it must be.
         *
         * The server adds properties — `labelId` on Mailbox and `snoozedUntil` on Thread are
         * already two extensions past RFC 8621 — and a client that refuses to parse a response
         * containing a field it has not heard of breaks on every server upgrade. This is the more
         * conservative choice, not the lazier one.
         */
        val JMAP_JSON = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
            isLenient = false
        }

        /**
         * Parses a `/jmap/api` response body.
         *
         * Handles the level confusion at the boundary so nothing above has to: a request-level
         * `problem+json` arrives with a non-2xx status and no `methodResponses` at all, while a
         * method-level error arrives inside a perfectly successful 200.
         */
        fun decode(body: ByteArray, status: Int): MethodResults {
            val text = body.decodeToString()

            if (status !in 200..299) throw problemFrom(text, status)

            val root = runCatching {
                JMAP_JSON.parseToJsonElement(text).jsonObject
            }
                .getOrElse {
                    throw JmapError.MalformedResponse(
                        "The server answered HTTP $status with something that is not JSON."
                    )
                }

            val entries =
                root["methodResponses"]?.let { it as? JsonArray }
                    ?: throw JmapError.MalformedResponse(
                        "The response has no methodResponses array, so it is not a JMAP response."
                    )

            return MethodResults(
                responses = entries.map(::parseInvocation),
                sessionState = root["sessionState"]?.jsonPrimitive?.content.orEmpty(),
            )
        }

        internal fun problemFrom(text: String, status: Int): JmapError {
            val problem = runCatching {
                JMAP_JSON.decodeFromString<ProblemDocument>(text)
            }
                .getOrNull()

            return when {
                status == 401 -> JmapError.NotAuthenticated(problem?.detail)
                problem != null && problem.type != "about:blank" ->
                    JmapError.RequestRejected(problem.type, status, problem.detail)
                else -> JmapError.UnexpectedStatus(status)
            }
        }

        private fun parseInvocation(element: kotlinx.serialization.json.JsonElement): Invocation {
            val triple =
                (element as? JsonArray)?.takeIf { it.size >= 3 }
                    ?: throw JmapError.MalformedResponse(
                        "A method response was not a [name, arguments, callId] triple."
                    )

            return Invocation(
                name = triple[0].jsonPrimitive.content,
                arguments = triple[1].jsonObject,
                callId = triple[2].jsonPrimitive.content,
            )
        }
    }
}
