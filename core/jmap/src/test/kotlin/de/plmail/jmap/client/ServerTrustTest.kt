package de.plmail.jmap.client

import de.plmail.jmap.protocol.JmapError
import java.io.IOException
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Principal
import java.security.PublicKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.X509TrustManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import okhttp3.OkHttpClient

/**
 * No real certificates are generated here.
 *
 * Signing a genuine X.509 structure needs a certificate builder, and neither okhttp-tls nor
 * BouncyCastle is on this module's classpath — pulling one in to produce bytes that nothing under
 * test ever reads would buy a dependency and no coverage. So the *keys* are real (generated here,
 * real DER SubjectPublicKeyInfo, real digests) and only the certificate envelope is a stub, one
 * that throws from every member except `getPublicKey`. That stub is load-bearing rather than lazy:
 * an implementation that fingerprinted the certificate body instead of the key fails these tests
 * loudly instead of passing them quietly, which is the property the ninety-day renewal case depends
 * on.
 *
 * The policy itself is tested as the pure function it is, over every combination of (platform
 * verdict, pin, presented key); the [X509TrustManager] adapter is then tested on top of a platform
 * trust manager whose verdict the test chooses, so no socket, CA or clock is involved anywhere.
 */
class ServerTrustTest {

    private val host = "nas.example"

    private val serverKey = freshKey()
    private val otherKey = freshKey()

    private val serverFingerprint = fingerprintOf(serverKey)
    private val otherFingerprint = fingerprintOf(otherKey)

    private val certificate = StubCertificate(serverKey)

    // ---- fingerprints -------------------------------------------------------

    @Test
    fun `shows uppercase pairs and stores compact lowercase hex`() {
        val stored = "0123456789abcdef".repeat(4)
        val fingerprint = assertNotNull(KeyFingerprint.parse(stored))

        assertEquals(stored, fingerprint.hex)
        assertEquals("01 23 45 67 89 AB CD EF ".repeat(4).trim(), fingerprint.display)
        assertEquals(fingerprint.display, fingerprint.toString())
    }

    @Test
    fun `parses back every form it hands out`() {
        val stored = "0123456789abcdef".repeat(4)
        val fingerprint = assertNotNull(KeyFingerprint.parse(stored))

        assertEquals(fingerprint, KeyFingerprint.parse(fingerprint.hex))
        // The string onboarding showed the user is the string they paste back.
        assertEquals(fingerprint, KeyFingerprint.parse(fingerprint.display))
        assertEquals(fingerprint, KeyFingerprint.parse(stored.uppercase()))
        // And the colon-separated form openssl prints.
        assertEquals(fingerprint, KeyFingerprint.parse(stored.chunked(2).joinToString(":")))
    }

    @Test
    fun `refuses anything that is not a whole sha-256`() {
        val stored = "0123456789abcdef".repeat(4)

        assertNull(KeyFingerprint.parse(""))
        // A short pin that still parsed would compare fewer bytes than it looks
        // like it does, which is the kind of weakening nobody sees in a diff.
        assertNull(KeyFingerprint.parse(stored.drop(2)), "truncated")
        assertNull(KeyFingerprint.parse(stored + "00"), "over-long")
        assertNull(KeyFingerprint.parse("z".repeat(64)), "not hex at all")
        // `Char.isDigit` is true for these, so a hex parser written with it
        // accepts 64 Arabic-Indic digits and stores them as somebody's pin.
        assertNull(KeyFingerprint.parse("٠".repeat(64)), "unicode digits are not hex")
    }

    @Test
    fun `digests the DER SubjectPublicKeyInfo, so openssl on the server agrees`() {
        val expected =
            MessageDigest.getInstance("SHA-256").digest(serverKey.encoded).joinToString("") {
                "%02x".format(it)
            }

        assertEquals(expected, serverFingerprint.hex)
    }

    @Test
    fun `a renewal that keeps the key keeps the pin`() {
        // Two certificates over one key: what `certbot renew` writes every
        // ninety days, and what re-prompting the user would train them through.
        val issued = StubCertificate(serverKey)
        val renewed = StubCertificate(serverKey)

        assertEquals(serverFingerprint, KeyFingerprint.of(issued))
        assertEquals(KeyFingerprint.of(issued), KeyFingerprint.of(renewed))
    }

    @Test
    fun `different keys fingerprint differently`() {
        assertNotEquals(serverFingerprint, otherFingerprint)
        assertNotEquals(KeyFingerprint.of(StubCertificate(serverKey)), otherFingerprint)
    }

    // ---- the policy, exhaustively -------------------------------------------

    @Test
    fun `platform trust connects, whatever is pinned`() {
        // Rule one, including the case that matters most: a pin that disagrees
        // does not override it, so moving the box behind a real certificate
        // does not lock the user out with yesterday's self-signed key.
        for (pin in listOf(null, serverFingerprint, otherFingerprint)) {
            val decision =
                ServerTrust(host, pin).decide(platformTrusted = true, presented = serverFingerprint)

            assertEquals(TrustDecision.PlatformTrusted, decision, "pinned = $pin")
        }
    }

    @Test
    fun `an untrusted server with nothing pinned is refused, with its fingerprint`() {
        val decision =
            ServerTrust(host).decide(platformTrusted = false, presented = serverFingerprint)

        assertEquals(TrustDecision.NotPinned(serverFingerprint), decision)

        val error = decision.errorFor<JmapError.UntrustedCertificate>(host)
        assertEquals(host, error.host)
        // What onboarding is handed has to be storable as a pin unchanged.
        assertEquals(serverFingerprint, KeyFingerprint.parse(error.fingerprint))
    }

    @Test
    fun `an untrusted server whose key is pinned connects`() {
        val decision =
            ServerTrust(host, serverFingerprint)
                .decide(platformTrusted = false, presented = serverFingerprint)

        assertEquals(TrustDecision.PinMatched(serverFingerprint), decision)
    }

    @Test
    fun `a pinned server presenting another key is a change, never a prompt`() {
        val decision =
            ServerTrust(host, serverFingerprint)
                .decide(platformTrusted = false, presented = otherFingerprint)

        assertEquals(TrustDecision.PinMismatch(serverFingerprint, otherFingerprint), decision)

        val error = decision.errorFor<JmapError.CertificateChanged>(host)
        assertEquals(host, error.host)
        assertEquals(serverFingerprint.hex, error.expected)
        assertEquals(otherFingerprint.hex, error.actual)

        // The property the design rests on: this must never arrive as the case
        // the UI knows how to put a "trust it" button next to.
        assertFalse(
            decision is TrustDecision.NotPinned,
            "a changed key must not present as first contact",
        )
    }

    // ---- the adapter --------------------------------------------------------

    @Test
    fun `the trust manager connects when the platform is satisfied`() {
        trustManager(pinned = otherFingerprint, platformTrusts = true)
            .checkServerTrusted(arrayOf(certificate), AUTH_TYPE)
    }

    @Test
    fun `the trust manager connects to an untrusted server whose key is pinned`() {
        trustManager(pinned = serverFingerprint, platformTrusts = false)
            .checkServerTrusted(arrayOf(certificate), AUTH_TYPE)
    }

    @Test
    fun `the trust manager refuses an unpinned untrusted server, reporting the key`() {
        val manager = trustManager(pinned = null, platformTrusts = false)

        val failure =
            assertFailsWith<CertificateException> {
                manager.checkServerTrusted(arrayOf(certificate), AUTH_TYPE)
            }

        val error = refusalIn<JmapError.UntrustedCertificate>(failure)
        assertEquals(host, error.host)
        assertEquals(serverFingerprint, KeyFingerprint.parse(error.fingerprint))
    }

    @Test
    fun `the trust manager refuses a changed key, and not as an invitation`() {
        val manager = trustManager(pinned = otherFingerprint, platformTrusts = false)

        val failure =
            assertFailsWith<CertificateException> {
                manager.checkServerTrusted(arrayOf(certificate), AUTH_TYPE)
            }

        val error = refusalIn<JmapError.CertificateChanged>(failure)
        assertEquals(otherFingerprint.hex, error.expected)
        assertEquals(serverFingerprint.hex, error.actual)
        assertFalse(
            ServerTrust.trustFailure(failure) is JmapError.UntrustedCertificate,
            "a changed key must never come back as the acceptable-once error",
        )
    }

    @Test
    fun `an empty chain is refused outright`() {
        val manager = trustManager(pinned = serverFingerprint, platformTrusts = false)

        val failure =
            assertFailsWith<CertificateException> {
                manager.checkServerTrusted(emptyArray(), AUTH_TYPE)
            }

        // Nothing to fingerprint means nothing onboarding could offer to pin,
        // so this stays an ordinary TLS failure rather than an invitation.
        assertNull(ServerTrust.trustFailure(failure))
    }

    @Test
    fun `the typed error survives however the socket layer wraps it`() {
        val manager = trustManager(pinned = null, platformTrusts = false)

        val refusal =
            assertFailsWith<CertificateException> {
                manager.checkServerTrusted(arrayOf(certificate), AUTH_TYPE)
            }
        // What the transport actually catches is a handshake exception with the
        // refusal buried underneath it, never the refusal itself.
        val wrapped = SSLHandshakeException("handshake_failure").initCause(refusal)

        val error = refusalIn<JmapError.UntrustedCertificate>(wrapped)
        assertEquals(serverFingerprint, KeyFingerprint.parse(error.fingerprint))
        assertNull(
            ServerTrust.trustFailure(IOException("connection reset")),
            "an unreachable host is not a certificate problem",
        )
    }

    // ---- the plumbing -------------------------------------------------------

    @Test
    fun `falls back on the platform's own trust store by default`() {
        // Cheap guard on the JCA wiring, which otherwise only fails during a
        // handshake — somewhere no unit test reaches.
        val manager = ServerTrust(host, serverFingerprint).trustManager()

        assertTrue(manager.acceptedIssuers.isNotEmpty(), "the platform roots should be behind it")
    }

    @Test
    fun `installs on an OkHttpClient builder with its trust manager`() {
        // OkHttp has to be handed the trust manager as well as the factory; if
        // it were left to find one, it would find the platform's.
        val client =
            OkHttpClient.Builder().serverTrust(ServerTrust(host, serverFingerprint)).build()

        assertNotNull(client.sslSocketFactory)
    }

    private fun trustManager(pinned: KeyFingerprint?, platformTrusts: Boolean): X509TrustManager =
        ServerTrust(host, pinned).trustManager(StubPlatform(platformTrusts))
}

private const val AUTH_TYPE = "ECDHE_RSA"

private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(256) }

private fun freshKey(): PublicKey = keys.generateKeyPair().public

private fun fingerprintOf(key: PublicKey): KeyFingerprint =
    assertNotNull(KeyFingerprint.of(key), "a generated EC key always has an encoded form")

private inline fun <reified T : JmapError> TrustDecision.errorFor(host: String): T {
    val refusal = this as? TrustDecision.Refuse ?: fail("expected a refusal, got $this")

    return refusal.asError(host).let {
        it as? T ?: fail("expected ${T::class.simpleName}, got $it")
    }
}

private inline fun <reified T : JmapError> refusalIn(failure: Throwable): T {
    val error = ServerTrust.trustFailure(failure) ?: fail("no trust failure inside $failure")

    return error as? T ?: fail("expected ${T::class.simpleName}, got $error")
}

/** A platform trust manager whose verdict the test picks. */
private class StubPlatform(private val trusts: Boolean) : X509TrustManager {

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        if (!trusts) throw CertificateException("No trust anchor for this chain.")
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * A certificate that is real exactly where it matters.
 *
 * Every member but `getPublicKey` throws, deliberately: the code under test is supposed to look at
 * the key and nothing else, and a stub that returned empty bytes instead would let a body-digesting
 * implementation pass.
 */
@Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
private class StubCertificate(private val key: PublicKey) : X509Certificate() {

    override fun getPublicKey(): PublicKey = key

    override fun toString(): String = "StubCertificate(${key.algorithm})"

    override fun getEncoded(): ByteArray = unread()

    override fun getTBSCertificate(): ByteArray = unread()

    override fun getSignature(): ByteArray = unread()

    override fun getSigAlgName(): String = unread()

    override fun getSigAlgOID(): String = unread()

    override fun getSigAlgParams(): ByteArray = unread()

    override fun getVersion(): Int = unread()

    override fun getSerialNumber(): BigInteger = unread()

    override fun getIssuerDN(): Principal = unread()

    override fun getSubjectDN(): Principal = unread()

    override fun getNotBefore(): Date = unread()

    override fun getNotAfter(): Date = unread()

    override fun getIssuerUniqueID(): BooleanArray = unread()

    override fun getSubjectUniqueID(): BooleanArray = unread()

    override fun getKeyUsage(): BooleanArray = unread()

    override fun getBasicConstraints(): Int = unread()

    override fun checkValidity() = unread()

    override fun checkValidity(date: Date) = unread()

    override fun verify(publicKey: PublicKey) = unread()

    override fun verify(publicKey: PublicKey, sigProvider: String) = unread()

    override fun hasUnsupportedCriticalExtension(): Boolean = unread()

    override fun getCriticalExtensionOIDs(): Set<String> = unread()

    override fun getNonCriticalExtensionOIDs(): Set<String> = unread()

    override fun getExtensionValue(oid: String): ByteArray = unread()

    private fun unread(): Nothing =
        throw UnsupportedOperationException("Nothing under test reads the certificate body.")
}
