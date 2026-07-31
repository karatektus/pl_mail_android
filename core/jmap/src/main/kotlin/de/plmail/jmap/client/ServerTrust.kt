package de.plmail.jmap.client

import de.plmail.jmap.protocol.JmapError
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PublicKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.jvm.JvmInline
import okhttp3.OkHttpClient

/**
 * A SHA-256 fingerprint of a server's TLS public key.
 *
 * The *key* — the certificate's SubjectPublicKeyInfo — and not the whole certificate. A renewal
 * that keeps the same key, which is exactly what `certbot renew` does by default, would otherwise
 * be indistinguishable from an attacker swapping the server out, and asking the user to re-accept
 * every ninety days teaches them to tap "trust" without reading it.
 *
 * Carried as canonical lowercase hex rather than a `ByteArray` because equality *is* this type's
 * job and `ByteArray` compares by identity: a pin check written against one would compile, read
 * correctly, and refuse every connection including the one the user pinned thirty seconds ago.
 */
@JvmInline
value class KeyFingerprint private constructor(val hex: String) {

    /**
     * Uppercase hex in space-separated pairs, the way every other tool prints a fingerprint, so a
     * user can hold it up against `openssl` output on the server without transcribing anything.
     */
    val display: String
        get() = hex.uppercase().chunked(2).joinToString(" ")

    override fun toString(): String = display

    companion object {
        /** SHA-256, so 32 bytes; anything else was never one of ours. */
        private const val LENGTH = 32

        private const val HEX_DIGITS = "0123456789abcdef"

        /**
         * Reads back a stored pin, or a fingerprint a user pasted.
         *
         * Accepts the compact storage form, the spaced [display] form and `openssl`'s
         * colon-separated one, in either case: the string coming back is very often the one we just
         * showed someone, and a parser that only understood its own storage format would reject the
         * app's own output.
         *
         * Null rather than an exception, and null for a *short* input too — a truncated pin that
         * still parsed would silently compare fewer bytes than it looks like it does.
         */
        fun parse(text: String): KeyFingerprint? {
            val compact = text.filterNot { it.isWhitespace() || it == ':' }.lowercase()
            if (compact.length != LENGTH * 2) return null

            // Explicitly ASCII: `Char.isDigit` is true for Unicode digits such
            // as Arabic-Indic ٠١٢, which would sail through here and produce a
            // "hex" string nothing downstream can read.
            if (!compact.all { it in '0'..'9' || it in 'a'..'f' }) return null

            return KeyFingerprint(compact)
        }

        /**
         * The fingerprint of the key inside a certificate.
         *
         * Note this reads only the public key, never the certificate body, so two certificates
         * issued a renewal apart over one key fingerprint identically.
         */
        fun of(certificate: X509Certificate): KeyFingerprint? = of(certificate.publicKey)

        /**
         * Null when the key has no encoded form. The JCA allows a `PublicKey` living in hardware to
         * answer null there, and a key that cannot be fingerprinted is a key that can never be
         * pinned — a refusal, not a crash, and the reason there is no `!!` here.
         */
        fun of(key: PublicKey): KeyFingerprint? {
            // `PublicKey.getEncoded` is the DER SubjectPublicKeyInfo, the same
            // bytes `openssl pkey -pubin -outform der` writes, so a fingerprint
            // taken here and one taken with a shell on the server agree.
            val spki: ByteArray = key.encoded ?: return null

            return KeyFingerprint(MessageDigest.getInstance("SHA-256").digest(spki).toHex())
        }

        private fun ByteArray.toHex(): String =
            buildString(size * 2) {
                for (byte in this@toHex) {
                    val value = byte.toInt() and 0xFF
                    append(HEX_DIGITS[value ushr 4])
                    append(HEX_DIGITS[value and 0x0F])
                }
            }
    }
}

/**
 * What to do about a certificate, as four named cases rather than a boolean.
 *
 * The difference between [NotPinned] and [PinMismatch] is the entire security property: the first
 * is an invitation to ask the user, the second must never become one. A `Boolean` return would
 * collapse them into "refused", and every call site would then have to re-derive which one it had
 * been — with the dangerous answer being the easy one to get wrong.
 */
sealed interface TrustDecision {

    /** Cases that connect. */
    sealed interface Allow : TrustDecision

    /** Cases that refuse, each carrying the error the user should be shown. */
    sealed interface Refuse : TrustDecision {
        fun asError(host: String): JmapError
    }

    /** The platform trusted the chain on its own. There is nothing to ask. */
    data object PlatformTrusted : Allow

    /** Not trusted by the platform, but this is the key the user accepted. */
    data class PinMatched(val fingerprint: KeyFingerprint) : Allow

    /**
     * Not trusted, and nothing pinned yet — the expected first contact with someone's NAS. The
     * fingerprint travels on the error so onboarding can show it and offer to accept it once.
     */
    data class NotPinned(val presented: KeyFingerprint) : Refuse {
        override fun asError(host: String): JmapError =
            JmapError.UntrustedCertificate(host, presented.hex)
    }

    /**
     * A pinned server presented a different key.
     *
     * Terminal, and deliberately a different case from [NotPinned] rather than a variation of it:
     * whatever the UI does with an untrusted certificate it must not do here, because "the key
     * changed, accept it?" is precisely the question an attacker substituting a server needs asked.
     */
    data class PinMismatch(val expected: KeyFingerprint, val presented: KeyFingerprint) : Refuse {
        override fun asError(host: String): JmapError =
            JmapError.CertificateChanged(host, expected.hex, presented.hex)
    }
}

/**
 * Trust-on-first-use for one server.
 *
 * plMail runs on machines people own: a NAS with a self-signed certificate, a Tailscale node, a box
 * behind a private CA. Refusing all of them makes the app useless for its actual audience, and the
 * usual shortcut — a `network_security_config` with `cleartextTrafficPermitted` and a
 * trust-anything manager — turns validation off for every connection forever, including the ones
 * that were fine.
 *
 * So: trust-on-first-use, pinned to one key.
 * 1. Platform evaluation runs first. If it passes there is nothing to ask about.
 * 2. If it fails and nothing is pinned, the connection is refused and the fingerprint reported, so
 *    onboarding can show it and let the user decide once.
 * 3. If it fails and the fingerprint matches the pin, it is allowed.
 * 4. If it fails and the fingerprint *differs*, it is refused — permanently, and loudly.
 *
 * Not OkHttp's `CertificatePinner`, which solves the opposite problem: it runs *after* the trust
 * manager has already accepted a chain, so it can only narrow public trust, never stand in for it.
 * It has no way to say "this key, even though the platform will not vouch for it", which is the
 * whole requirement.
 *
 * Immutable, and holds no record of what it last saw. iOS keeps the rejected fingerprint in a
 * mutex-guarded field for onboarding to read; here it rides on the thrown error instead, which
 * removes the shared mutable state and with it any chance of onboarding pinning a fingerprint that
 * came from a different connection attempt than the one the user is looking at.
 *
 * Scope: this pins the *key*, not the name. OkHttp's hostname verifier still runs, so a self-signed
 * certificate must carry a SAN covering the host it is reached at — deliberately unchanged, because
 * the certificate plMail's installer generates does, and relaxing name checks here would widen the
 * hole beyond the one key the user actually agreed to.
 */
class ServerTrust(val host: String, val pinned: KeyFingerprint? = null) {

    /**
     * The entire policy, as a pure function of the platform's verdict and the key on the wire.
     *
     * Pure so the four cases can be tested exhaustively without a socket, a certificate authority
     * or a clock; [trustManager] is a thin adapter that supplies the two arguments and turns the
     * answer into an exception.
     */
    fun decide(platformTrusted: Boolean, presented: KeyFingerprint): TrustDecision =
        when {
            // Platform trust wins outright, even over a pin that disagrees. The
            // pin is a fallback for keys the platform will not vouch for, not a
            // replacement for it: someone who pins a self-signed certificate
            // today and puts the same box behind Let's Encrypt tomorrow arrives
            // with a new key *and* a real chain, and treating that as case four
            // would lock them out of their own server with a terminal error.
            platformTrusted -> TrustDecision.PlatformTrusted
            pinned == null -> TrustDecision.NotPinned(presented)
            pinned == presented -> TrustDecision.PinMatched(presented)
            // Only a pin that exists and disagrees reaches here. Nothing below
            // this line may turn it back into a question.
            else -> TrustDecision.PinMismatch(expected = pinned, presented = presented)
        }

    /**
     * This policy as something the JSSE can install.
     *
     * [platform] is injectable for two reasons: tests need a trust manager whose verdict they
     * choose, and an app that ships its own additional roots can hand one in instead of being
     * forced to fork this.
     */
    fun trustManager(platform: X509TrustManager = platformTrustManager()): X509TrustManager =
        PinningTrustManager(this, platform)

    companion object {
        /**
         * Digs the trust failure out of whatever the socket layer wrapped it in.
         *
         * A refusal leaves here as a [CertificateException] (see [UntrustedServerException]) and
         * reaches the caller as an `SSLHandshakeException` with two or three layers of cause on
         * top, so the transport cannot simply catch the typed error. This walks down to it. Null
         * means the handshake failed for some reason that was not ours — an unreachable host, a
         * protocol mismatch — which is a different error and must not be reported as a certificate
         * problem.
         */
        fun trustFailure(failure: Throwable?): JmapError? =
            generateSequence(failure) { it.cause }
                .filterIsInstance<UntrustedServerException>()
                .firstOrNull()
                ?.error
    }
}

/**
 * The [CertificateException] the handshake needs, carrying the [JmapError] the user needs.
 *
 * The JSSE's contract with a trust manager is a `CertificateException`: that is what the TLS engine
 * catches to send a `certificate_unknown` alert and fail the handshake cleanly. Throwing a bare
 * [JmapError] instead would escape that catch entirely and travel up through OkHttp's
 * `IOException`-shaped retry path as an unrelated crash, with the connection left un-torn-down. So
 * the typed error rides along as the cause and [ServerTrust.trustFailure] recovers it.
 */
class UntrustedServerException internal constructor(val error: JmapError) :
    CertificateException(error.message, error)

/**
 * Installs [trust] on a client.
 *
 * The trust manager is passed to OkHttp *as well as* being wired into the socket factory, because
 * OkHttp cannot recover one from the other: given only a factory it goes looking for the platform's
 * default trust manager to build its certificate chain cleaner from, which would evaluate chains by
 * the platform's rules and quietly undo the fallback this class exists to provide.
 */
fun OkHttpClient.Builder.serverTrust(trust: ServerTrust): OkHttpClient.Builder {
    val trustManager = trust.trustManager()
    val context = SSLContext.getInstance("TLS")
    context.init(null, arrayOf<TrustManager>(trustManager), null)

    return sslSocketFactory(context.socketFactory, trustManager)
}

/**
 * The adapter between [ServerTrust.decide] and the JSSE.
 *
 * Deliberately holds no policy of its own — it collects the two inputs, asks, and translates.
 * Everything worth testing is in [ServerTrust.decide].
 */
private class PinningTrustManager(
    private val trust: ServerTrust,
    private val platform: X509TrustManager,
) : X509TrustManager {

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        // Index zero is the peer's own certificate by the JSSE's contract, and
        // it is that key we pin rather than anything above it: pinning an issuer
        // would accept every host that issuer ever signs, which for a private CA
        // is a much larger promise than the user made.
        val leaf =
            chain.firstOrNull() ?: throw CertificateException("The server sent no certificate.")

        val presented =
            KeyFingerprint.of(leaf)
                ?: throw CertificateException(
                    "The certificate's public key has no encoded form, so it can never be pinned."
                )

        val decision = trust.decide(platform.trusts(chain, authType), presented)

        when (decision) {
            is TrustDecision.Allow -> Unit
            is TrustDecision.Refuse -> throw UntrustedServerException(decision.asError(trust.host))
        }
    }

    // Delegated rather than stubbed out to accept anything. Nothing here makes
    // server-side connections today, but a trust manager that waved client
    // certificates through would be a live foot-gun the day something does.
    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
        platform.checkClientTrusted(chain, authType)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = platform.acceptedIssuers
}

/**
 * Whether the platform accepts this chain unaided.
 *
 * Every reason it can refuse — unknown issuer, expired, broken signature — collapses to `false` and
 * falls through to the pin. That is intentional for this audience: a home server whose self-signed
 * certificate lapsed last month is the common case, not an attack, and the pin is a statement about
 * the key rather than about the paperwork around it.
 */
private fun X509TrustManager.trusts(
    chain: Array<out X509Certificate>,
    authType: String,
): Boolean =
    try {
        checkServerTrusted(chain, authType)
        true
    } catch (refused: CertificateException) {
        false
    }

/**
 * The platform's own trust store: Android's system CAs, or the JDK's `cacerts`.
 *
 * `init(null)` is the documented way to ask for that default rather than supply a store. The
 * alternative — shipping a CA bundle inside the app — would mean every root the OS adds or
 * distrusts waits on a release of ours.
 */
private fun platformTrustManager(): X509TrustManager {
    val algorithm = TrustManagerFactory.getDefaultAlgorithm()
    val factory = TrustManagerFactory.getInstance(algorithm)

    // The cast picks the KeyStore overload; a bare `null` is ambiguous against
    // the ManagerFactoryParameters one.
    factory.init(null as KeyStore?)

    return factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
        ?: throw IllegalStateException(
            "$algorithm produced no X509TrustManager, so there is no platform trust to fall back on."
        )
}
