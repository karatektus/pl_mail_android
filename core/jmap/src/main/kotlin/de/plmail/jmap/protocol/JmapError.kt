package de.plmail.jmap.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Everything that can go wrong, as one hierarchy.
 *
 * JMAP fails at three different levels and they are genuinely different things, so flattening them
 * into one "request failed" would throw away the only information a caller can act on:
 *
 * - **Request level** — `application/problem+json` with an HTTP status. The whole request was
 *   rejected; no method ran. [NotAuthenticated] and [RequestRejected].
 * - **Method level** — HTTP 200, but one entry in `methodResponses` is an `error` invocation. Other
 *   calls in the same batch may have succeeded. [MethodFailed].
 * - **Transport level** — nothing came back, or what came back was not JMAP. [Unreachable],
 *   [MalformedResponse], [UnexpectedStatus].
 *
 * The distinction is load-bearing for a self-hosted product: "your app password was revoked" and
 * "your NAS is rebooting" want completely different UI, and only one of them should stop the client
 * retrying.
 */
sealed class JmapError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /**
     * 401. The credential is gone — revoked, or the server no longer knows it.
     *
     * Never retry silently. The only correct response is to tell the user and send them back
     * through pairing; a client that retries turns a revoked credential into a login loop against
     * someone's home server.
     */
    class NotAuthenticated(val detail: String?) :
        JmapError(detail ?: "The app password was rejected.")

    /** A request-level `problem+json`: malformed, unknown capability, too large. */
    class RequestRejected(val type: String, val status: Int, val detail: String?) :
        JmapError(detail ?: "The server rejected the request ($type, HTTP $status).")

    /**
     * One method call failed while the batch as a whole succeeded.
     *
     * [type] is the JMAP error type. Note two of them are frequently indistinguishable: an unknown
     * keyword and unsupported `anchor` paging both come back as a bare `unsupportedFilter` with no
     * description, so the type alone will not tell a query builder which mistake it made.
     */
    class MethodFailed(val type: String, val callId: String, val description: String?) :
        JmapError(description ?: "$type (in call $callId)")

    /** The server could not be reached at all. */
    class Unreachable(val host: String, cause: Throwable?) :
        JmapError("Could not reach $host.", cause)

    /** Something answered, but it was not JMAP. */
    class MalformedResponse(val reason: String) : JmapError(reason)

    class UnexpectedStatus(val status: Int) : JmapError("The server answered with HTTP $status.")

    /**
     * The server's TLS certificate is not trusted and no pin matches.
     *
     * Carries the fingerprint so onboarding can show it and let the user decide once — this is the
     * expected path for a NAS with a self-signed certificate, not an exceptional one.
     */
    class UntrustedCertificate(val host: String, val fingerprint: String) :
        JmapError("The certificate for $host is not trusted.")

    /**
     * A pinned server presented a *different* key.
     *
     * Deliberately separate from [UntrustedCertificate] and deliberately terminal: this must never
     * degrade back into a prompt, because a prompt is exactly what an attacker substituting a
     * server needs.
     */
    class CertificateChanged(val host: String, val expected: String, val actual: String) :
        JmapError("The certificate for $host changed. Expected $expected, got $actual.")

    /**
     * Whether the client's stored state token is too old to answer from.
     *
     * The one condition that justifies discarding a sync cursor and paging afresh; everything else
     * is recoverable by asking again later.
     */
    val requiresResync: Boolean
        get() = this is MethodFailed && type == "cannotCalculateChanges"
}

/** The `application/problem+json` body a request-level failure carries. */
@Serializable
internal data class ProblemDocument(
    val type: String = "about:blank",
    val status: Int = 0,
    val detail: String? = null,
    @SerialName("title") val title: String? = null,
)
