package de.plmail.jmap.client

import de.plmail.jmap.Fixture
import de.plmail.jmap.methods.EmailGet
import de.plmail.jmap.methods.EmailQuery
import de.plmail.jmap.protocol.AccountId
import de.plmail.jmap.protocol.JmapError
import de.plmail.jmap.protocol.RequestBuilder
import de.plmail.jmap.testing.GatedTransport
import de.plmail.jmap.testing.RecordingTransport
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest

class JmapClientTest {

    private val credential = Credential.AppPassword("plmail_${"ab".repeat(32)}")
    private val account = AccountId("1")

    private fun client(transport: JmapTransport) =
        JmapClient(
            discoveryUrl = "https://nas.local/.well-known/jmap",
            credential = credential,
            transport = transport,
        )

    @Test
    fun `discovers the session once and reuses it`() = runTest {
        val transport = RecordingTransport.alwaysReturning(Fixture.read("session.json"))
        val client = client(transport)

        client.session()
        client.session()
        client.session()

        assertEquals(1, transport.calls, "the session must be fetched once, not per caller")
    }

    @Test
    fun `single-flights concurrent session requests`() = runTest {
        // At launch the sidebar, the feed and every account's syncer ask at the
        // same moment. Ten callers must cause one request, not ten — against a
        // home server the difference is a stall.
        val gated = GatedTransport {
            HttpResponse(200, body = Fixture.read("session.json").encodeToByteArray())
        }
        val client = client(gated)

        val waiting = List(10) { async { client.session() } }

        // All ten are queued behind one outstanding request.
        while (gated.outstanding() == 0) kotlinx.coroutines.yield()
        assertEquals(1, gated.calls)

        gated.releaseAll()
        val sessions = waiting.awaitAll()

        assertEquals(1, gated.calls)
        assertTrue(sessions.all { it.username == "e2e@plmail.test" })
    }

    @Test
    fun `a failed discovery does not poison later attempts`() = runTest {
        var failNext = true

        val transport = RecordingTransport { _ ->
            if (failNext) {
                failNext = false
                HttpResponse(503, body = ByteArray(0))
            } else {
                HttpResponse(200, body = Fixture.read("session.json").encodeToByteArray())
            }
        }
        val client = client(transport)

        assertFailsWith<JmapError.UnexpectedStatus> { client.session() }

        // The next caller must retry rather than inherit the cached failure —
        // a NAS that was rebooting is the common case, not a permanent state.
        assertEquals("e2e@plmail.test", client.session().username)
    }

    @Test
    fun `a 401 is reported as authentication rather than a bad status`() = runTest {
        val transport =
            RecordingTransport.alwaysReturning(Fixture.read("error-401.json"), status = 401)

        val error = assertFailsWith<JmapError.NotAuthenticated> { client(transport).session() }

        assertEquals("Invalid or revoked app password.", error.detail)
    }

    @Test
    fun `a non-session response names the address rather than blaming JSON`() = runTest {
        // Pointing the app at a web server that is not plMail is a thing users
        // will do. The error has to say which address failed.
        val transport = RecordingTransport.alwaysReturning("<html>Hello</html>")

        val error = assertFailsWith<JmapError.MalformedResponse> { client(transport).session() }

        assertContains(error.reason, "nas.local")
    }

    @Test
    fun `sends the credential on every request`() = runTest {
        val transport =
            RecordingTransport.routing(
                ".well-known" to Fixture.read("session.json"),
                "/jmap/api" to Fixture.read("email-query.json"),
            )
        val client = client(transport)

        val builder = RequestBuilder()
        builder.add(EmailQuery(account))
        client.send(builder)

        assertTrue(
            transport.requests.all {
                it.headers["Authorization"] == "Bearer ${credential.secret}"
            }
        )
    }

    @Test
    fun `posts the api call to the url the session gave, not one we built`() = runTest {
        val transport =
            RecordingTransport.routing(
                ".well-known" to Fixture.read("session.json"),
                "/jmap/api" to Fixture.read("email-query.json"),
            )
        val client = client(transport)

        val builder = RequestBuilder()
        builder.add(EmailQuery(account))
        client.send(builder)

        // The session says 127.0.0.1:8002 even though discovery went to
        // nas.local — the server generates these from the request Host header,
        // and honouring them is what makes one credential work from an
        // emulator and a phone alike.
        assertEquals("http://127.0.0.1:8002/jmap/api", transport.requests.last().url)
    }

    @Test
    fun `serialises accountId as a string`() = runTest {
        // An integer is rejected with invalidArguments and no description
        // saying which argument was wrong — an expensive afternoon.
        val transport =
            RecordingTransport.routing(
                ".well-known" to Fixture.read("session.json"),
                "/jmap/api" to Fixture.read("email-query.json"),
            )
        val client = client(transport)

        val builder = RequestBuilder()
        builder.add(EmailQuery(account))
        client.send(builder)

        assertContains(transport.lastBody.orEmpty(), "\"accountId\":\"1\"")
    }

    @Test
    fun `writes back-references with the hash prefix the server requires`() = runTest {
        val transport =
            RecordingTransport.routing(
                ".well-known" to Fixture.read("session.json"),
                "/jmap/api" to Fixture.read("email-query-get-backref.json"),
            )
        val client = client(transport)

        val builder = RequestBuilder()
        val query = builder.add(EmailQuery(account))
        builder.add(EmailGet.byReference(account, query.reference("/ids")))
        client.send(builder)

        val body = transport.lastBody.orEmpty()

        // Without the '#' the server sees an unknown argument holding an
        // object and answers as though it were never sent — no error anywhere.
        assertContains(body, "\"#ids\"")
        assertContains(body, "\"resultOf\":\"c0\"")
        assertContains(body, "\"path\":\"/ids\"")
    }

    @Test
    fun `spells fetchHTMLBodyValues the way the server reads it`() = runTest {
        // fetchHtmlBodyValues is silently ignored: an unrecognised argument is
        // simply absent, so the wrong spelling returns empty bodyValues with
        // no error to debug.
        val transport =
            RecordingTransport.routing(
                ".well-known" to Fixture.read("session.json"),
                "/jmap/api" to Fixture.read("email-get-bodies.json"),
            )
        val client = client(transport)

        val builder = RequestBuilder()
        builder.add(
            EmailGet(
                accountId = account,
                ids = listOf(de.plmail.jmap.protocol.EmailId("1")),
                properties = EmailGet.READER_PROPERTIES,
                fetchTextBodyValues = true,
                fetchHtmlBodyValues = true,
            )
        )
        client.send(builder)

        assertContains(transport.lastBody.orEmpty(), "fetchHTMLBodyValues")
        assertTrue(!transport.lastBody.orEmpty().contains("fetchHtmlBodyValues"))
    }

    @Test
    fun `refuses to send an empty batch`() = runTest {
        val transport = RecordingTransport.alwaysReturning(Fixture.read("session.json"))

        assertFailsWith<IllegalArgumentException> { client(transport).send(RequestBuilder()) }
    }
}
