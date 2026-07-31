package de.plmail.jmap.client

import de.plmail.jmap.protocol.JmapError
import de.plmail.jmap.testing.RecordingTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Pairing, which is the one request made before there is a credential.
 *
 * The URI cases matter more than they look. The server `rawurlencode`s the host, so it arrives as
 * `https%3A%2F%2Fnas.local`; decoded with the wrong routine that becomes `https:/nas.local`, which
 * parses without complaint and points nowhere. And the scanner sees every barcode in frame, so "not
 * ours" has to be an ordinary answer rather than an error shown to someone who did nothing wrong.
 */
class DevicePairingTest {

    private val secret = "plmail_" + "9f".repeat(32)

    // Exactly what DevicePairingService::pairingUri emits.
    private fun uriFor(host: String, code: String): String =
        "plmail://pair?host=${host.encoded()}&code=${code.encoded()}"

    private fun String.encoded(): String =
        java.net.URLEncoder.encode(this, Charsets.UTF_8).replace("+", "%20")

    @Test
    fun `a pairing uri yields the address and the code`() {
        val result = PairingUri.parse(uriFor("https://nas.local", "abc-123_XYZ"))

        assertIs<ParsedInvitation.Valid>(result)
        assertEquals("https://nas.local", result.invitation.address.display)
        assertEquals("abc-123_XYZ", result.invitation.code)
    }

    @Test
    fun `the percent-encoded host survives decoding`() {
        // The case a naive decoder turns into "https:/nas.local".
        val result = PairingUri.parse("plmail://pair?host=https%3A%2F%2Fnas.local%3A8443&code=xyz")

        assertIs<ParsedInvitation.Valid>(result)
        assertEquals("https://nas.local:8443", result.invitation.address.display)
        assertEquals(
            "https://nas.local:8443/.well-known/jmap",
            result.invitation.address.discoveryUrl,
        )
    }

    @Test
    fun `a cleartext pairing host is carried through rather than refused`() {
        // Onboarding warns about it; the parser is not the place to decide.
        val result = PairingUri.parse(uriFor("http://10.0.2.2:8002", "xyz"))

        assertIs<ParsedInvitation.Valid>(result)
        assertTrue(result.invitation.address.isCleartext)
    }

    @Test
    fun `another app's barcode is not an error`() {
        // What the camera hands us constantly: a wifi QR, a URL on the packaging.
        assertIs<ParsedInvitation.NotAPairingUri>(PairingUri.parse("https://example.com"))
        assertIs<ParsedInvitation.NotAPairingUri>(
            PairingUri.parse("WIFI:S:somenetwork;T:WPA;P:hunter2;;")
        )
        assertIs<ParsedInvitation.NotAPairingUri>(PairingUri.parse("plmail://open?thread=4"))
        assertIs<ParsedInvitation.NotAPairingUri>(PairingUri.parse(""))
    }

    @Test
    fun `a pairing uri missing what it needs says so`() {
        assertIs<ParsedInvitation.Incomplete>(
            PairingUri.parse("plmail://pair?host=https%3A%2F%2Fa")
        )
        assertIs<ParsedInvitation.Incomplete>(PairingUri.parse("plmail://pair?code=xyz"))
        assertIs<ParsedInvitation.Incomplete>(PairingUri.parse("plmail://pair"))
        assertIs<ParsedInvitation.Incomplete>(
            PairingUri.parse("plmail://pair?host=not%20an%20address&code=xyz")
        )
    }

    @Test
    fun `the code never appears in a string representation`() {
        val result = PairingUri.parse(uriFor("https://nas.local", "s3cr3t-code"))
        assertIs<ParsedInvitation.Valid>(result)

        assertTrue(
            !result.invitation.toString().contains("s3cr3t-code"),
            "the pairing code reached toString(): ${result.invitation}",
        )
    }

    @Test
    fun `redeeming posts the code and returns the minted credential`() = runTest {
        val transport =
            RecordingTransport.alwaysReturning(
                """{"secret":"$secret","username":"someone@example.com"}"""
            )

        val paired =
            DevicePairingClient(transport)
                .redeem(invitation("https://nas.local", "abc"), deviceName = "Pixel 8")

        assertEquals(secret, paired.credential.secret)
        assertEquals("someone@example.com", paired.username)

        val request = transport.requests.single()
        assertEquals("https://nas.local/device/pair", request.url)
        assertEquals("POST", request.method)
        assertTrue(transport.lastBody!!.contains("\"code\":\"abc\""))
        assertTrue(transport.lastBody!!.contains("\"deviceName\":\"Pixel 8\""))
    }

    @Test
    fun `a reverse-proxy path prefix is kept on the pairing endpoint`() = runTest {
        val transport =
            RecordingTransport.alwaysReturning("""{"secret":"$secret","username":"a@b"}""")

        DevicePairingClient(transport)
            .redeem(invitation("https://example.com/mail", "abc"), deviceName = "Pixel")

        assertEquals("https://example.com/mail/device/pair", transport.requests.single().url)
    }

    /**
     * The server answers 404 identically for unknown, expired and already-used codes, on purpose.
     * The message therefore has to explain the two-minute window rather than repeat the status —
     * "not found" sends someone hunting for a typo in a code they scanned correctly.
     */
    @Test
    fun `an expired code surfaces the server's explanation`() = runTest {
        val detail = "That pairing code is not valid. Codes expire after two minutes and work once."
        val transport =
            RecordingTransport.alwaysReturning(
                """{"type":"notFound","status":404,"detail":"$detail"}""",
                status = 404,
            )

        val failure =
            assertFailsWith<JmapError.RequestRejected> {
                DevicePairingClient(transport)
                    .redeem(invitation("https://nas.local", "stale"), deviceName = "Pixel")
            }

        assertEquals(404, failure.status)
        assertEquals("notFound", failure.type)
        assertEquals(detail, failure.detail)
    }

    /**
     * A proxy or captive portal answering 200 with its own page.
     *
     * Storing what that decodes to would produce an `Authorization: Bearer ` header and a 401 loop
     * the user cannot read, so it is rejected here where the cause is still visible.
     */
    @Test
    fun `a 200 that is not a pairing response is refused`() = runTest {
        val notPairing = RecordingTransport.alwaysReturning("""{"login":"please sign in"}""")

        assertFailsWith<JmapError.MalformedResponse> {
            DevicePairingClient(notPairing)
                .redeem(invitation("https://nas.local", "abc"), deviceName = "Pixel")
        }

        val notJson = RecordingTransport.alwaysReturning("<!doctype html><title>Sign in</title>")

        assertFailsWith<JmapError.MalformedResponse> {
            DevicePairingClient(notJson)
                .redeem(invitation("https://nas.local", "abc"), deviceName = "Pixel")
        }
    }

    @Test
    fun `a secret that is not an app password is refused`() = runTest {
        val transport =
            RecordingTransport.alwaysReturning("""{"secret":"nonsense","username":"a@b"}""")

        assertFailsWith<JmapError.MalformedResponse> {
            DevicePairingClient(transport)
                .redeem(invitation("https://nas.local", "abc"), deviceName = "Pixel")
        }
    }

    /**
     * A TLS refusal has to arrive at onboarding intact.
     *
     * Pairing is where a self-signed certificate is first seen, and the whole flow depends on the
     * fingerprint reaching the screen that asks the user about it. Wrapping it in Unreachable would
     * turn "is this your server's key?" into "could not connect".
     */
    @Test
    fun `an untrusted certificate is not flattened into unreachable`() = runTest {
        val refusing = RecordingTransport {
            throw JmapError.UntrustedCertificate("nas.local", "ab".repeat(32))
        }

        val failure =
            assertFailsWith<JmapError.UntrustedCertificate> {
                DevicePairingClient(refusing)
                    .redeem(invitation("https://nas.local", "abc"), deviceName = "Pixel")
            }

        assertEquals("ab".repeat(32), failure.fingerprint)
    }

    @Test
    fun `a dead server is unreachable rather than an opaque crash`() = runTest {
        val down = RecordingTransport { throw java.io.IOException("connection refused") }

        val failure =
            assertFailsWith<JmapError.Unreachable> {
                DevicePairingClient(down)
                    .redeem(invitation("https://nas.local", "abc"), deviceName = "Pixel")
            }

        assertEquals("nas.local", failure.host)
    }

    private fun invitation(host: String, code: String): PairingInvitation {
        val parsed = ServerAddress.parse(host)
        check(parsed is ParsedAddress.Valid) { "test host “$host” does not parse" }

        return PairingInvitation(parsed.address, code)
    }
}
