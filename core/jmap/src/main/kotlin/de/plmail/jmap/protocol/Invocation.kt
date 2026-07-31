package de.plmail.jmap.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * One method call or response: the `[name, arguments, callId]` triple that JMAP uses for both
 * directions (RFC 8620 §3.2).
 */
data class Invocation(val name: String, val arguments: JsonObject, val callId: String) {
    /** An error entry uses the literal name "error" rather than the method's. */
    val isError: Boolean
        get() = name == ERROR_NAME

    companion object {
        const val ERROR_NAME = "error"
    }
}

/**
 * A reference to a previous call's result within the same request (RFC 8620 §3.7).
 *
 * The point is one round trip instead of two: `Email/query` followed by an `Email/get` that names
 * the query's own output, rather than waiting for ids to come back and asking again. Over a home
 * ADSL uplink to a NAS that is the difference between a list that appears and a list that arrives.
 *
 * On the wire the referencing *argument* is renamed with a `#` prefix — `"#ids"` rather than
 * `"ids"` — which is why [RequestBuilder] takes the plain argument name and adds the marker itself.
 */
data class ResultReference(val resultOf: String, val name: String, val path: String)

/**
 * A method the client can call, paired with how to read its answer.
 *
 * Arguments are built as a [JsonObject] rather than through `@Serializable` classes: JMAP arguments
 * are heterogeneous — an argument is either a value or a back-reference, decided per call — and
 * modelling that with a sealed hierarchy per method costs far more than it returns.
 */
interface JmapMethod<out R> {
    val name: String

    fun arguments(): JsonObject

    fun decode(json: Json, arguments: JsonObject): R
}

/** What [RequestBuilder.add] hands back: enough to find and decode one answer. */
data class MethodHandle<out R>(val method: JmapMethod<R>, val callId: String) {
    /**
     * Points a later call at part of this one's result.
     *
     * [path] is a JSON Pointer, with JMAP's extension that a `*` segment maps over an array: a path
     * of list, then star, then threadId collects the thread id of every message the `Email/get`
     * returned. (Spelled out rather than written literally because the pointer contains a star
     * followed by a slash, which ends a block comment.)
     */
    fun reference(path: String): ResultReference = ResultReference(callId, method.name, path)
}
