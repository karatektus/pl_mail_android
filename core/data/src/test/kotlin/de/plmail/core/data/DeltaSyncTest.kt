package de.plmail.core.data

import de.plmail.jmap.client.HttpResponse
import de.plmail.jmap.client.JmapTransport
import de.plmail.jmap.testing.RecordingTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The wire shape of a delta sync, without a database.
 *
 * `DeltaSync` itself needs Room and is covered on-device; what is tested here is the protocol
 * behaviour it depends on, because two of those behaviours are counter-intuitive enough that the
 * fixtures document them and a client written to the obvious reading is silently wrong.
 */
class DeltaSyncTest {

    private val session =
        """
        {
          "capabilities": {"urn:ietf:params:jmap:core": {}},
          "accounts": {"1": {"name": "someone@example.com"}},
          "username": "someone@example.com",
          "apiUrl": "https://nas.local/jmap/api",
          "downloadUrl": "https://nas.local/jmap/download",
          "uploadUrl": "https://nas.local/jmap/upload"
        }
        """

    /**
     * A first sync cannot come from `Email/changes`.
     *
     * From state `"0"` the server answers with empty arrays — truthfully, because nothing has
     * *changed* since the beginning of time. A client that treats that as "up to date" shows an
     * empty inbox and never recovers, because every later sync starts from the same place.
     */
    @Test
    fun `changes from the initial state reports nothing about existing mail`() = runTest {
        val transport =
            answering(
                """
                {"sessionState":"s","methodResponses":[
                  ["Email/changes",
                   {"accountId":"1","oldState":"0","newState":"0","hasMoreChanges":false,
                    "created":[],"updated":[],"destroyed":[]},"c0"]]}
                """
            )

        val result = changes(transport, since = "0")

        assertTrue(result.isEmpty, "the server reports no changes, not the whole mailbox")
        assertEquals("0", result.newState)
    }

    @Test
    fun `created and updated are both re-fetched`() = runTest {
        val transport =
            answering(
                """
                {"sessionState":"s","methodResponses":[
                  ["Email/changes",
                   {"accountId":"1","oldState":"5","newState":"7","hasMoreChanges":false,
                    "created":["10"],"updated":["3","4"],"destroyed":["1"]},"c0"]]}
                """
            )

        val result = changes(transport, since = "5")

        // Only the reason differs: both need their row rewritten.
        assertEquals(listOf("10", "3", "4"), result.changed.map { it.value })
        assertEquals(listOf("1"), result.destroyed.map { it.value })
    }

    @Test
    fun `the request asks for the server's maximum, not an invented one`() = runTest {
        val transport =
            answering(
                """
                {"sessionState":"s","methodResponses":[
                  ["Email/changes",
                   {"accountId":"1","oldState":"5","newState":"5","hasMoreChanges":false,
                    "created":[],"updated":[],"destroyed":[]},"c0"]]}
                """
            )

        changes(transport, since = "5")

        val body = transport.requests.last { it.url.endsWith("/jmap/api") }.body!!.decodeToString()
        assertTrue(body.contains("\"maxChanges\":256"), body)
        assertTrue(body.contains("\"sinceState\":\"5\""), body)
    }

    /**
     * `hasMoreChanges` is the loop condition, and the new state is where the next round starts.
     *
     * Re-sending the *old* state would loop forever over the same 256 changes — a client that
     * appears to sync continuously and never finishes.
     */
    @Test
    fun `a partial answer advances the cursor for the next round`() = runTest {
        val transport =
            answering(
                """
                {"sessionState":"s","methodResponses":[
                  ["Email/changes",
                   {"accountId":"1","oldState":"5","newState":"6","hasMoreChanges":true,
                    "created":["1"],"updated":[],"destroyed":[]},"c0"]]}
                """
            )

        val result = changes(transport, since = "5")

        assertTrue(result.hasMoreChanges)
        assertEquals("6", result.newState, "the next round must start from the new state")
    }

    /**
     * `cannotCalculateChanges` is the only error that justifies discarding a cursor.
     *
     * `JmapError.requiresResync` is what distinguishes it from every other method failure, which
     * are all recoverable by asking again later.
     */
    @Test
    fun `only cannotCalculateChanges asks for a re-page`() {
        val stale =
            de.plmail.jmap.protocol.JmapError.MethodFailed("cannotCalculateChanges", "c0", null)
        val busy = de.plmail.jmap.protocol.JmapError.MethodFailed("serverUnavailable", "c0", null)

        assertTrue(stale.requiresResync)
        assertTrue(!busy.requiresResync)
    }

    private suspend fun changes(
        transport: RecordingTransport,
        since: String,
    ): de.plmail.jmap.methods.EmailChangesResult {
        val client =
            de.plmail.jmap.client.JmapClient(
                discoveryUrl = "https://nas.local/.well-known/jmap",
                credential =
                    de.plmail.jmap.client.Credential.AppPassword("plmail_" + "a".repeat(64)),
                transport = transport,
            )

        val request = de.plmail.jmap.protocol.RequestBuilder()
        val handle =
            request.add(
                de.plmail.jmap.methods.EmailChanges(
                    de.plmail.jmap.protocol.AccountId("1"),
                    de.plmail.jmap.protocol.StateToken(since),
                )
            )

        return client.send(request).result(handle)
    }

    private fun answering(api: String): RecordingTransport = RecordingTransport { request ->
        val body = if (request.url.endsWith("/.well-known/jmap")) session else api

        HttpResponse(200, mapOf("Content-Type" to "application/json"), body.encodeToByteArray())
    }

    private fun unused(): JmapTransport = JmapTransport { error("unused") }
}
