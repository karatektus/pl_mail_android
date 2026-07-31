package de.plmail.core.data

import de.plmail.jmap.client.Credential
import de.plmail.jmap.client.DevicePairingClient
import de.plmail.jmap.client.JmapClient
import de.plmail.jmap.client.JmapTransport
import de.plmail.jmap.client.KeyFingerprint
import de.plmail.jmap.client.PairingInvitation
import de.plmail.jmap.client.ServerAddress
import de.plmail.jmap.protocol.JmapError
import de.plmail.jmap.protocol.Session

/**
 * Builds a transport for one server.
 *
 * An interface because the pin is part of the client's TLS configuration rather than a header:
 * accepting a key means building a *new* OkHttp client around a new trust manager, so "retry with
 * this fingerprint" cannot be expressed by passing an argument to a request. It also keeps OkHttp
 * out of the connector, so the whole onboarding path is testable on the JVM.
 */
fun interface TransportFactory {
    fun create(address: ServerAddress, pinned: KeyFingerprint?): JmapTransport
}

/** A server that answered, with the credential that worked and the key that was accepted. */
data class VerifiedServer(
    val address: ServerAddress,
    val credential: Credential.AppPassword,
    val pinnedKey: KeyFingerprint?,
    val session: Session,
) {
    /** The address the server says the credential belongs to. */
    val username: String
        get() = session.username

    /**
     * The mailboxes this one credential reaches, in a stable order.
     *
     * Shown back before anything is saved, because "was that `nas.local` or the other `nas.local`"
     * is a real question for someone with two boxes, and the account list is the only thing on the
     * screen that can answer it.
     */
    val accountNames: List<String>
        get() = session.accountIds.mapNotNull { session.account(it)?.name }
}

/**
 * What one attempt to reach a server produced.
 *
 * Three cases rather than a success/failure pair, because the middle one is not a failure: a NAS
 * with a self-signed certificate is the expected first contact for this product, and the only thing
 * standing between the user and their mail is a question nobody has asked them yet.
 */
sealed interface ConnectionAttempt {

    data class Connected(val server: VerifiedServer) : ConnectionAttempt

    /**
     * The platform would not vouch for the certificate and nothing is pinned yet.
     *
     * Carries the fingerprint so the screen can show it in a form the user can compare against
     * `openssl` output on the server itself.
     */
    data class NeedsTrust(val host: String, val fingerprint: KeyFingerprint) : ConnectionAttempt

    data class Refused(val error: JmapError) : ConnectionAttempt
}

/**
 * Reaching a server for the first time.
 *
 * Both entry points end in the same place — a session fetched with a credential that works —
 * because that is the only evidence worth acting on. Saving an address and a token that have never
 * successfully talked to anything is how a client ends up in a 401 loop against someone's home
 * server with no way to tell them what is wrong.
 */
class ServerConnector(
    private val transports: TransportFactory,
    private val deviceName: String,
) {

    /**
     * Verifies a credential the user pasted, or one already stored.
     *
     * [pinned] is what the user has previously accepted, if anything. Passing it in rather than
     * reading it back here keeps the connector free of storage: onboarding is holding a fingerprint
     * the user just agreed to and has not saved yet, and the retry has to use *that*.
     */
    suspend fun verify(
        address: ServerAddress,
        credential: Credential.AppPassword,
        pinned: KeyFingerprint? = null,
    ): ConnectionAttempt =
        attempt(address, pinned) {
            val client =
                JmapClient(
                    discoveryUrl = address.discoveryUrl,
                    credential = credential,
                    transport = transports.create(address, pinned),
                )

            ConnectionAttempt.Connected(
                VerifiedServer(address, credential, pinned, client.session())
            )
        }

    /**
     * Redeems a scanned or tapped invitation, then verifies what it produced.
     *
     * The verification is not ceremony. Redemption mints a credential server-side and burns the
     * code doing it, so a client that stopped there and only discovered on the next launch that it
     * could not authenticate would leave the user holding a dead code and a useless token, with
     * re-pairing as the only route out.
     */
    suspend fun pair(
        invitation: PairingInvitation,
        pinned: KeyFingerprint? = null,
    ): ConnectionAttempt =
        attempt(invitation.address, pinned) {
            val paired =
                DevicePairingClient(transports.create(invitation.address, pinned))
                    .redeem(invitation, deviceName)

            verify(invitation.address, paired.credential, pinned)
        }

    /**
     * Runs [block], turning a trust refusal into the question it is.
     *
     * [JmapError.UntrustedCertificate] is caught here rather than at each call site because both
     * entry points can raise it — pairing talks to the server before verification does — and a
     * missed case would surface as an unexplained "could not connect" on precisely the setup this
     * product is for. [JmapError.CertificateChanged] is deliberately *not* caught: it stays a
     * failure, because turning it back into a prompt is exactly what an attacker substituting a
     * server needs.
     */
    private suspend fun attempt(
        address: ServerAddress,
        pinned: KeyFingerprint?,
        block: suspend () -> ConnectionAttempt,
    ): ConnectionAttempt =
        try {
            block()
        } catch (untrusted: JmapError.UntrustedCertificate) {
            val presented = KeyFingerprint.parse(untrusted.fingerprint)

            // A fingerprint that will not parse cannot be pinned, so there is
            // nothing to ask: offering "trust this?" for a key we could not
            // store would produce a prompt that silently does nothing.
            if (presented == null || pinned != null) {
                ConnectionAttempt.Refused(untrusted)
            } else {
                ConnectionAttempt.NeedsTrust(untrusted.host, presented)
            }
        } catch (refused: JmapError) {
            ConnectionAttempt.Refused(refused)
        } catch (failed: Exception) {
            ConnectionAttempt.Refused(JmapError.Unreachable(address.host, failed))
        }
}
