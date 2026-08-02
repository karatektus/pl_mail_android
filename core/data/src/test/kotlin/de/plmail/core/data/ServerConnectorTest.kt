package de.plmail.core.data

import de.plmail.jmap.client.Credential
import de.plmail.jmap.client.HttpResponse
import de.plmail.jmap.client.JmapTransport
import de.plmail.jmap.client.KeyFingerprint
import de.plmail.jmap.client.PairingInvitation
import de.plmail.jmap.client.ParsedAddress
import de.plmail.jmap.client.ServerAddress
import de.plmail.jmap.client.StreamingTransport
import de.plmail.jmap.protocol.JmapError
import de.plmail.jmap.testing.RecordingTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/**
 * The whole of onboarding's networking, on the JVM.
 *
 * That it runs here at all is the point of [TransportFactory]: accepting a certificate means
 * building a new OkHttp client around a new trust manager, so a connector that constructed its own
 * transport could only be exercised against a real TLS server with a real self-signed certificate.
 *
 * The case that matters most is the middle one. A NAS with a self-signed certificate is the
 * *expected* first contact for this product, so "not trusted" has to arrive as a question carrying
 * a fingerprint rather than as a failure — while a certificate that *changed* stays a failure,
 * because a prompt there is what an attacker substituting a server is hoping for.
 */
class ServerConnectorTest {

    private val secret = "plmail_" + "7c".repeat(32)
    private val credential = Credential.AppPassword(secret)
    private val fingerprint = "ab".repeat(32)

    private val session =
        """
        {
          "capabilities": {"urn:ietf:params:jmap:core": {}, "urn:ietf:params:jmap:mail": {}},
          "accounts": {"13": {"name": "someone@example.com"}, "14": {"name": "work@example.com"}},
          "username": "someone@example.com",
          "apiUrl": "https://nas.local/jmap/api",
          "downloadUrl": "https://nas.local/jmap/download",
          "uploadUrl": "https://nas.local/jmap/upload"
        }
        """

    @Test
    fun `a working credential yields the session and the accounts it reaches`() = runTest {
        val connector = connector { RecordingTransport.alwaysReturning(session) }

        val outcome = connector.verify(address(), credential)

        assertIs<ConnectionAttempt.Connected>(outcome)
        assertEquals("someone@example.com", outcome.server.username)
        assertEquals(
            listOf("someone@example.com", "work@example.com"),
            outcome.server.accountNames,
        )
        assertNull(outcome.server.pinnedKey)
    }

    @Test
    fun `an untrusted certificate becomes a question, not a failure`() = runTest {
        val connector = connector {
            RecordingTransport { throw JmapError.UntrustedCertificate("nas.local", fingerprint) }
        }

        val outcome = connector.verify(address(), credential)

        assertIs<ConnectionAttempt.NeedsTrust>(outcome)
        assertEquals("nas.local", outcome.host)
        assertEquals(fingerprint, outcome.fingerprint.hex)
    }

    @Test
    fun `accepting the key retries with it pinned and connects`() = runTest {
        // The factory is what receives the pin, so this also checks that the
        // accepted fingerprint actually reaches the transport rather than being
        // stored and forgotten.
        var sawPin: KeyFingerprint? = null
        val connector =
            ServerConnector(
                transports =
                    factory { _, pinned ->
                        sawPin = pinned
                        if (pinned == null) {
                            RecordingTransport {
                                throw JmapError.UntrustedCertificate("nas.local", fingerprint)
                            }
                        } else {
                            RecordingTransport.alwaysReturning(session)
                        }
                    },
                deviceName = "Pixel",
            )

        val asked = connector.verify(address(), credential)
        assertIs<ConnectionAttempt.NeedsTrust>(asked)

        val outcome = connector.verify(address(), credential, pinned = asked.fingerprint)

        assertIs<ConnectionAttempt.Connected>(outcome)
        assertEquals(asked.fingerprint, sawPin)
        assertEquals(asked.fingerprint, outcome.server.pinnedKey)
    }

    /**
     * Once something is pinned, a refusal is terminal.
     *
     * Re-asking would mean the pin protects nothing: a user who accepted key A would be offered key
     * B with the same wording, which is the whole attack.
     */
    @Test
    fun `an untrusted certificate with a pin already held is refused rather than re-asked`() =
        runTest {
            val connector = connector {
                RecordingTransport {
                    throw JmapError.UntrustedCertificate("nas.local", "cd".repeat(32))
                }
            }

            val outcome =
                connector.verify(address(), credential, pinned = KeyFingerprint.parse(fingerprint))

            assertIs<ConnectionAttempt.Refused>(outcome)
        }

    @Test
    fun `a changed certificate is never a question`() = runTest {
        val connector = connector {
            RecordingTransport {
                throw JmapError.CertificateChanged("nas.local", fingerprint, "cd".repeat(32))
            }
        }

        val outcome = connector.verify(address(), credential)

        assertIs<ConnectionAttempt.Refused>(outcome)
        assertIs<JmapError.CertificateChanged>(outcome.error)
    }

    @Test
    fun `a revoked credential is refused with the server's reason`() = runTest {
        val connector = connector {
            RecordingTransport.alwaysReturning(
                """{"type":"about:blank","status":401,"detail":"Unknown app password."}""",
                status = 401,
            )
        }

        val outcome = connector.verify(address(), credential)

        assertIs<ConnectionAttempt.Refused>(outcome)
        assertIs<JmapError.NotAuthenticated>(outcome.error)
    }

    @Test
    fun `an unreachable server is refused rather than crashing`() = runTest {
        val connector = connector {
            RecordingTransport { throw java.io.IOException("connection refused") }
        }

        val outcome = connector.verify(address(), credential)

        assertIs<ConnectionAttempt.Refused>(outcome)
        assertIs<JmapError.Unreachable>(outcome.error)
    }

    /**
     * Redemption mints a credential and burns the code doing it.
     *
     * A client that stopped at redemption would leave someone holding a dead code and a token it
     * had never authenticated with, discovering the problem on the next launch with re-pairing as
     * the only way out. So pairing verifies before it reports success.
     */
    @Test
    fun `pairing redeems the code and then proves the credential works`() = runTest {
        val transport = RecordingTransport { request ->
            val body =
                if (request.url.endsWith("/device/pair")) {
                    """{"secret":"$secret","username":"someone@example.com"}"""
                } else {
                    session
                }

            HttpResponse(200, mapOf("Content-Type" to "application/json"), body.encodeToByteArray())
        }

        val outcome = ServerConnector(factory { _, _ -> transport }, "Pixel 8").pair(invitation())

        assertIs<ConnectionAttempt.Connected>(outcome)
        assertEquals(secret, outcome.server.credential.secret)
        assertEquals("someone@example.com", outcome.server.username)

        val paths = transport.requests.map { it.url }
        assertEquals("https://nas.local/device/pair", paths.first())
        assertEquals(2, paths.size, "pairing should redeem and then verify: $paths")
    }

    @Test
    fun `pairing with an expired code is refused before anything is stored`() = runTest {
        val connector = connector {
            RecordingTransport.alwaysReturning(
                """{"type":"notFound","status":404,"detail":"That pairing code is not valid."}""",
                status = 404,
            )
        }

        val outcome = connector.pair(invitation())

        assertIs<ConnectionAttempt.Refused>(outcome)
        assertIs<JmapError.RequestRejected>(outcome.error)
    }

    @Test
    fun `pairing against an untrusted certificate asks before redeeming`() = runTest {
        val connector = connector {
            RecordingTransport { throw JmapError.UntrustedCertificate("nas.local", fingerprint) }
        }

        assertIs<ConnectionAttempt.NeedsTrust>(connector.pair(invitation()))
    }

    private fun connector(transport: () -> JmapTransport): ServerConnector =
        ServerConnector(factory { _, _ -> transport() }, deviceName = "Pixel")

    /**
     * A factory around one transport.
     *
     * Spelled out rather than passed as a lambda because [TransportFactory] grew a second method
     * and stopped being a `fun interface`. That method throws here rather than answering with the
     * same transport: nothing in onboarding opens an event stream, and a fake that quietly obliged
     * would let one of these tests keep passing while the connector did something no server on this
     * path is ever asked to do.
     */
    private fun factory(
        create: (ServerAddress, KeyFingerprint?) -> JmapTransport
    ): TransportFactory =
        object : TransportFactory {
            override fun create(address: ServerAddress, pinned: KeyFingerprint?): JmapTransport =
                create(address, pinned)

            override fun createStreaming(
                address: ServerAddress,
                pinned: KeyFingerprint?,
            ): StreamingTransport = error("Onboarding opens no event stream.")
        }

    private fun address(text: String = "https://nas.local"): ServerAddress {
        val parsed = ServerAddress.parse(text)
        check(parsed is ParsedAddress.Valid) { "test address “$text” does not parse" }

        return parsed.address
    }

    private fun invitation(): PairingInvitation = PairingInvitation(address(), "pairing-code")
}
