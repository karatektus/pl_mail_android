package de.plmail.jmap.client

import de.plmail.jmap.protocol.JmapError
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * A scanned or tapped pairing invitation.
 *
 * The QR never carries the app password — only a code that is dead two minutes later and dead
 * immediately once used. That is what makes it safe to photograph a QR on a laptop screen, and it
 * is why this type holds a [code] rather than a credential.
 */
data class PairingInvitation(val address: ServerAddress, val code: String) {
    /** The code is a short-lived secret; it has no business in a log line. */
    override fun toString(): String = "PairingInvitation($address, …)"
}

/** What reading a `plmail://pair?…` URI produced. */
sealed interface ParsedInvitation {

    data class Valid(val invitation: PairingInvitation) : ParsedInvitation

    /**
     * Not a pairing URI at all.
     *
     * Its own case rather than an error, because the camera hands us every barcode in frame: a wifi
     * QR on the same sheet of paper, a URL on the packaging behind it. The scanner keeps looking
     * rather than showing the user a failure they did not cause.
     */
    data object NotAPairingUri : ParsedInvitation

    /** A `plmail://pair` URI that is missing or malforming what it needs. */
    data class Incomplete(val reason: String) : ParsedInvitation
}

/**
 * Reads the `plmail://pair?host=…&code=…` URI the server generates.
 *
 * A URL rather than bare JSON so one string serves both routes: scanned by the camera when the code
 * is on another screen, and tapped as a deep link when it is on this one — which skips the camera
 * entirely and is the better path whenever it is available.
 */
object PairingUri {

    const val SCHEME = "plmail"
    const val HOST = "pair"

    fun parse(text: String): ParsedInvitation {
        val uri =
            try {
                URI(text.trim())
            } catch (malformed: URISyntaxException) {
                return ParsedInvitation.NotAPairingUri
            }

        // Both checked before anything else: a barcode that is not ours is the
        // ordinary case while scanning, not a problem to report. Only the
        // authority form `plmail://pair?…` is recognised, because that is what
        // DevicePairingService emits -- accepting the opaque `plmail:pair?…`
        // too would mean parsing the query out of the scheme-specific part by
        // hand, for a string nothing produces.
        if (!SCHEME.equals(uri.scheme, ignoreCase = true)) return ParsedInvitation.NotAPairingUri
        if (!HOST.equals(uri.host, ignoreCase = true)) return ParsedInvitation.NotAPairingUri

        val query = uri.rawQuery ?: return ParsedInvitation.Incomplete("no host or code")
        val parameters = query.split('&').mapNotNull(::parameter).toMap()

        val host =
            parameters["host"]?.takeIf { it.isNotBlank() }
                ?: return ParsedInvitation.Incomplete("no host")
        val code =
            parameters["code"]?.takeIf { it.isNotBlank() }
                ?: return ParsedInvitation.Incomplete("no code")

        val address =
            when (val parsed = ServerAddress.parse(host)) {
                is ParsedAddress.Valid -> parsed.address
                else -> return ParsedInvitation.Incomplete("“$host” is not a usable address")
            }

        return ParsedInvitation.Valid(PairingInvitation(address, code))
    }

    /**
     * Splits one `key=value`, decoding percent-escapes.
     *
     * The server `rawurlencode`s both values, and the host in particular arrives as
     * `https%3A%2F%2Fnas.local` — decoded with the wrong routine it becomes `https:/nas.local`,
     * which parses successfully and points nowhere.
     */
    private fun parameter(pair: String): Pair<String, String>? {
        val name = pair.substringBefore('=', missingDelimiterValue = "")
        if (name.isEmpty()) return null

        val value = pair.substringAfter('=', missingDelimiterValue = "")

        return try {
            URLDecoder.decode(name, Charsets.UTF_8) to URLDecoder.decode(value, Charsets.UTF_8)
        } catch (undecodable: IllegalArgumentException) {
            null
        }
    }
}

/** What the server hands back once a code is redeemed. */
data class PairedCredential(val credential: Credential.AppPassword, val username: String)

/**
 * Exchanges a pairing code for an app password.
 *
 * `POST /device/pair` is deliberately unauthenticated — a device that could authenticate would not
 * need to pair — and is gated by the code instead. It is therefore the one request the client makes
 * before it has a credential, which is why this does not go through [JmapClient]: there is no
 * session to discover and nothing to put in an `Authorization` header.
 *
 * It does go through [JmapTransport], so it inherits the same TLS policy as everything else. That
 * matters more here than anywhere: pairing is the moment a self-signed certificate is first seen,
 * and a pairing request that quietly bypassed [ServerTrust] would establish trust in a server the
 * user was never asked about — and then hand it a freshly minted key to their mailbox.
 */
class DevicePairingClient(
    private val transport: JmapTransport,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    /**
     * Redeems [invitation], naming this device in the user's app-password list.
     *
     * [deviceName] is what they will see when deciding which credential to revoke, so it should be
     * the device's own name rather than something invented — someone with four phones has to be
     * able to tell them apart.
     */
    suspend fun redeem(invitation: PairingInvitation, deviceName: String): PairedCredential {
        val body =
            json.encodeToString(
                PairRequest.serializer(),
                PairRequest(code = invitation.code, deviceName = deviceName),
            )

        val response =
            try {
                transport.send(
                    HttpRequest(
                        url = invitation.address.origin + invitation.address.pathPrefix + PATH,
                        method = "POST",
                        headers =
                            mapOf(
                                "Content-Type" to "application/json",
                                "Accept" to "application/json",
                            ),
                        body = body.encodeToByteArray(),
                    )
                )
            } catch (unreachable: JmapError) {
                // Already typed -- a TLS refusal from ServerTrust arrives this
                // way and must reach onboarding intact, since the whole point
                // is to show the user the fingerprint and ask.
                throw unreachable
            } catch (failed: Exception) {
                throw JmapError.Unreachable(invitation.address.host, failed)
            }

        if (!response.isSuccess) throw failure(response)

        return try {
            val paired = json.decodeFromString(PairResponse.serializer(), response.bodyAsText())

            // Checked rather than trusted: a proxy that answers 200 with its own
            // login page decodes into an object with empty fields, and storing
            // that would produce an Authorization header of "Bearer " and a
            // 401 loop the user cannot read.
            if (!Credential.AppPassword.looksValid(paired.secret)) {
                throw JmapError.MalformedResponse(
                    "The server's pairing response did not contain an app password."
                )
            }

            PairedCredential(Credential.AppPassword(paired.secret), paired.username)
        } catch (undecodable: SerializationException) {
            throw JmapError.MalformedResponse("The server's pairing response was not JSON.")
        }
    }

    /**
     * Maps a refusal onto the shared hierarchy.
     *
     * 404 is the interesting one, and it is not "not found" in any useful sense: the server answers
     * it identically for unknown, expired and already-used codes, on purpose, so that a caller
     * cannot learn which codes were once real. So the message has to explain the two-minute window
     * rather than repeat the status — "not found" would send someone hunting for a typo in a code
     * they scanned correctly and simply took too long to use.
     */
    private fun failure(response: HttpResponse): JmapError {
        val problem =
            try {
                json.decodeFromString(PairProblem.serializer(), response.bodyAsText())
            } catch (undecodable: SerializationException) {
                null
            }

        return JmapError.RequestRejected(
            type = problem?.type ?: "unexpectedStatus",
            status = response.status,
            detail = problem?.detail,
        )
    }

    private companion object {
        const val PATH = "/device/pair"
    }
}

@Serializable
private data class PairRequest(val code: String, @SerialName("deviceName") val deviceName: String)

@Serializable private data class PairResponse(val secret: String = "", val username: String = "")

@Serializable
private data class PairProblem(
    val type: String = "about:blank",
    val status: Int = 0,
    val detail: String? = null,
)
