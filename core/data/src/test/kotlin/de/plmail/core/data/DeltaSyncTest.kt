package de.plmail.core.data

import de.plmail.core.database.PlMailDatabase
import de.plmail.jmap.client.HttpResponse
import de.plmail.jmap.testing.RecordingTransport
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A delta sync, end to end and on the wire.
 *
 * Most of this file is protocol behaviour, because two of those behaviours are counter-intuitive
 * enough that the fixtures document them and a client written to the obvious reading is silently
 * wrong. The first test, though, is the whole bug this suite exists for, stated once: a sync that
 * asked `Email/changes` correctly, received a genuinely new message, stored it, summarised its
 * conversation — and left every list in the app exactly as it was, because the lists read
 * `feed_entries` and nothing on this path had ever written that table. Nothing failed. The mail was
 * on the device and invisible, which is the same screen as a sync that never ran.
 *
 * That one runs `DeltaSync` itself against Room, under Robolectric, which is why the class carries
 * a runner. It could not have been written against a fake database: the assertion *is* what is in
 * the table.
 */
@RunWith(RobolectricTestRunner::class)
// sdk = 36 for the reason `core/ui`'s screenshot tests give: a library module
// declares no targetSdk, so it inherits compileSdk 37 and Robolectric has no
// Android 37 to emulate. 36 is what :app targets anyway.
@Config(sdk = [36])
class DeltaSyncTest {

    private lateinit var database: PlMailDatabase

    @Before
    fun open() {
        database = inMemoryDatabase()
    }

    @After
    fun close() {
        database.close()
    }

    /**
     * The bug, and the fix, in one assertion.
     *
     * A message arrives on the server. `Email/changes` reports it, `Email/get` hydrates it,
     * `storeEmails` writes it and its conversation — and the inbox has to gain a row, keyed exactly
     * as the pager keys one, or none of the rest of it happened as far as anybody holding the phone
     * is concerned.
     */
    @Test
    fun `a changed conversation reaches the inbox the app actually draws`() = runTest {
        database.seedAccount(emailState = "s5")
        database.seedInbox()
        // The inbox has been paged, which is what makes it a list the
        // projection may write into.
        database.seedCursor(Feed.UNIFIED_INBOX.id)

        val sync = syncStack(database, arrivingMessage())
        val result = sync.sync(testAccountKey)

        assertEquals(SyncResult.Updated(fetched = 1, destroyed = 0), result)
        assertEquals(
            listOf("unified.inbox#https://nas.local/13#t1"),
            database.entryIds(Feed.UNIFIED_INBOX.id),
        )
        assertEquals(
            "s6",
            database.accounts().byUid(testAccountKey)?.emailState,
            "and the cursor moved, so the next sync resumes rather than replays",
        )
    }

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

    /**
     * A server with one new message in the inbox, answering both halves of a sync.
     *
     * Routed on what was asked rather than on call order, because the hydration is a second request
     * and a transport keyed on position would answer the wrong one the day a round trip is added or
     * removed.
     */
    private fun arrivingMessage(): RecordingTransport = RecordingTransport { request ->
        val asked = request.body?.decodeToString().orEmpty()

        val body =
            when {
                request.url.endsWith("/.well-known/jmap") -> TEST_SESSION
                asked.contains("Email/changes") ->
                    """
                    {"sessionState":"s","methodResponses":[
                      ["Email/changes",
                       {"accountId":"$TEST_ACCOUNT_ID","oldState":"s5","newState":"s6",
                        "hasMoreChanges":false,
                        "created":["10"],"updated":[],"destroyed":[]},"c0"]]}
                    """
                else ->
                    // The conversation travels with the message, back-referenced
                    // off the get, exactly as a page does: snooze belongs to the
                    // thread and a sync that fetched messages alone would rebuild
                    // the row without it.
                    """
                    {"sessionState":"s","methodResponses":[
                      ["Email/get",
                       {"accountId":"$TEST_ACCOUNT_ID","state":"s6","list":[
                         {"id":"10","threadId":"t1","receivedAt":"2026-08-01T10:00:00Z",
                          "preview":"p","mailboxIds":{"$INBOX_MAILBOX_ID":true}}]},"c0"],
                      ["Thread/get",
                       {"accountId":"$TEST_ACCOUNT_ID","state":"t1","list":[
                         {"id":"t1","emailIds":["10"]}]},"c1"]]}
                    """
            }

        HttpResponse(200, mapOf("Content-Type" to "application/json"), body.encodeToByteArray())
    }
}
