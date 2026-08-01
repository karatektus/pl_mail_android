package de.plmail.core.data

import de.plmail.jmap.client.Credential
import de.plmail.jmap.client.HttpResponse
import de.plmail.jmap.client.JmapClient
import de.plmail.jmap.mail.Email
import de.plmail.jmap.mail.MailThread
import de.plmail.jmap.protocol.AccountId
import de.plmail.jmap.testing.RecordingTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * One account's paging, against canned wire responses.
 *
 * Two of these encode server behaviour that has already cost time on the iOS client and is
 * documented in the fixtures: `Email/get` does not preserve the order it was asked in, and `before`
 * is a strict `<` at one-second granularity. Both produce lists that look almost right.
 */
class AccountPagerTest {

    private val account = AccountId("13")

    private val session =
        """
        {
          "capabilities": {"urn:ietf:params:jmap:core": {}},
          "accounts": {"13": {"name": "someone@example.com"}},
          "username": "someone@example.com",
          "apiUrl": "https://nas.local/jmap/api",
          "downloadUrl": "https://nas.local/jmap/download",
          "uploadUrl": "https://nas.local/jmap/upload"
        }
        """

    /**
     * The order trap, as a test.
     *
     * The query answers newest-first `[5,1,2,3,4]`; the get answers in repository order
     * `[1,2,3,4,5]`. A pager rendering `list` straight through sorts the inbox by database id.
     */
    @Test
    fun `rows follow the query's order, not the order Email-get replied in`() = runTest {
        val transport = transport(queryIds = listOf("5", "1"), getOrder = listOf("1", "5"))

        val page = pager(transport).page(atOrBefore = null, alreadyEmitted = emptySet(), limit = 10)

        assertEquals(listOf("5", "1"), page.rows.map { it.id })
    }

    @Test
    fun `the query and the get travel in one request, back-referenced`() = runTest {
        val transport = transport(queryIds = listOf("1"), getOrder = listOf("1"))

        pager(transport).page(null, emptySet(), 10)

        // Discovery, then exactly one API call carrying both methods.
        val api = transport.requests.filter { it.url.endsWith("/jmap/api") }
        assertEquals(1, api.size, "a page must not cost two round trips")

        val body = api.single().body!!.decodeToString()
        assertTrue(body.contains("\"Email/query\""))
        assertTrue(body.contains("\"Email/get\""))
        assertTrue(
            body.contains("\"#ids\""),
            "the get must reference the query rather than re-list",
        )
        assertTrue(body.contains("\"collapseThreads\":true"), "a thread list, not a message list")
    }

    /**
     * The boundary window.
     *
     * A cursor at 16:55:43 must ask for `before: 16:55:44` so the messages sharing 43 are still in
     * range; the caller subtracts the ones already shown.
     */
    @Test
    fun `the cursor opens the window to the next second`() = runTest {
        val transport = transport(queryIds = listOf("1"), getOrder = listOf("1"))
        val cursor = java.time.Instant.parse("2026-07-31T16:55:43Z").toEpochMilli()

        pager(transport).page(atOrBefore = cursor, alreadyEmitted = setOf("9"), limit = 10)

        val body = transport.requests.last { it.url.endsWith("/jmap/api") }.body!!.decodeToString()
        assertTrue(
            body.contains("\"before\":\"2026-07-31T16:55:44Z\""),
            "expected an inclusive window, got: $body",
        )
    }

    @Test
    fun `already-emitted rows are dropped and the page is widened to compensate`() = runTest {
        val transport = transport(queryIds = listOf("9", "1"), getOrder = listOf("9", "1"))

        val page = pager(transport).page(atOrBefore = 1_000, alreadyEmitted = setOf("9"), limit = 1)

        assertEquals(listOf("1"), page.rows.map { it.id })

        // limit + |alreadyEmitted|, or the discarded boundary row would make a
        // full page look short and end the list early.
        val body = transport.requests.last { it.url.endsWith("/jmap/api") }.body!!.decodeToString()
        assertTrue(body.contains("\"limit\":2"), "expected a widened limit, got: $body")
    }

    @Test
    fun `a full page is not the end, a short one is`() = runTest {
        val full = transport(queryIds = listOf("3", "2"), getOrder = listOf("3", "2"))
        assertFalse(pager(full).page(null, emptySet(), 2).isExhausted)

        val short = transport(queryIds = listOf("3"), getOrder = listOf("3"))
        assertTrue(pager(short).page(null, emptySet(), 2).isExhausted)
    }

    /**
     * The over-fetch is what stops boundary filtering from shortening a page.
     *
     * Asking for `limit` alone would return a page whose boundary rows are then discarded, leaving
     * fewer rows than asked for -- which the feed reads as the end of the list. Widening the ask by
     * exactly the number of ids being discarded makes a full page still full afterwards, so this
     * asserts the property rather than the arithmetic.
     */
    @Test
    fun `boundary filtering never shortens a full page`() = runTest {
        val emitted = setOf("9", "8")
        val served = listOf("9", "8", "7", "6")
        val transport = transport(queryIds = served, getOrder = served)

        val page = pager(transport).page(atOrBefore = 1_000, alreadyEmitted = emitted, limit = 2)

        assertEquals(listOf("7", "6"), page.rows.map { it.id })
        assertFalse(page.isExhausted, "a full page is never the end")
    }

    @Test
    fun `messages are handed to the cache before they are returned`() = runTest {
        val stored = mutableListOf<String>()
        val transport = transport(queryIds = listOf("2", "1"), getOrder = listOf("1", "2"))

        pager(transport) { emails, _, _ -> stored += emails.map { it.id.value } }
            .page(null, emptySet(), 10)

        // In query order, so what is written matches what is drawn.
        assertEquals(listOf("2", "1"), stored)
    }

    @Test
    fun `a message with an unparseable date is cached but kept out of the feed`() = runTest {
        val transport =
            transport(
                queryIds = listOf("2", "1"),
                getOrder = listOf("2", "1"),
                dates = mapOf("2" to "not a date"),
            )

        val stored = mutableListOf<String>()
        val page =
            pager(transport) { emails, _, _ -> stored += emails.map { it.id.value } }
                .page(null, emptySet(), 10)

        assertEquals(listOf("1"), page.rows.map { it.id }, "it cannot be placed in a dated list")
        assertEquals(listOf("2", "1"), stored, "but the reader can still open it")
    }

    /**
     * The cursor, as a test.
     *
     * This shipped broken and looked healthy from every angle: the state was never reported, so the
     * account had no cursor, so `Email/changes` had nothing to resume from and every push woke the
     * app to do nothing. Nothing failed — push and the periodic sync were simply inert. A page is
     * the only place the state can be learned, so it is asserted at the only place it is produced.
     */
    @Test
    fun `a page reports the state it was read at, which is what changes resumes from`() = runTest {
        val transport = transport(queryIds = listOf("1"), getOrder = listOf("1"))

        var seen: String? = null
        pager(transport) { _, _, state -> seen = state }.page(null, emptySet(), 10)

        assertEquals("e", seen, "without this there is no cursor and delta sync never runs")
    }

    /**
     * The conversation, not just its messages.
     *
     * Snooze is the one thing on a list row that `Email/get` cannot answer — it belongs to the
     * thread — so a page that fetches messages alone rebuilds the row with the snooze time missing
     * and quietly destroys the only local record of when the mail is due back. That is exactly how
     * undoing a snooze came to restore "not snoozed" no matter how the undo was written: the value
     * it was supposed to put back had already been overwritten by the next page load.
     */
    // The back-reference has to be the starred threadId path off the **get**,
    // not off the query: a query answers message ids, and this call needs thread
    // ids. (Spelled out here rather than in the KDoc above, because the path
    // contains a star-slash and would close the comment.)
    @Test
    fun `a page carries the conversations its messages belong to, with their snooze times`() =
        runTest {
            val transport =
                transport(
                    queryIds = listOf("1", "2"),
                    getOrder = listOf("1", "2"),
                    snoozed = mapOf("2" to "2026-08-03T08:00:00Z"),
                )

            var threads = emptyList<MailThread>()
            pager(transport) { _, fetched, _ -> threads = fetched }.page(null, emptySet(), 10)

            assertEquals(
                mapOf("t1" to null, "t2" to "2026-08-03T08:00:00Z"),
                threads.associate { it.id.value to it.snoozedUntil },
            )

            val sent = transport.lastBody.orEmpty()

            assertTrue(
                sent.contains("\"path\":\"/list/*/threadId\""),
                "the thread ids come from the get's answer, not from the query's",
            )
        }

    // -- helpers -----------------------------------------------------------

    private fun pager(
        transport: RecordingTransport,
        onPage: suspend (List<Email>, List<MailThread>, String) -> Unit = { _, _, _ -> },
    ) =
        AccountPager(
            accountKey = "https://nas.local/13",
            accountId = account,
            client =
                JmapClient(
                    discoveryUrl = "https://nas.local/.well-known/jmap",
                    credential = Credential.AppPassword("plmail_" + "a".repeat(64)),
                    transport = transport,
                ),
            onPage = onPage,
        )

    /** Answers discovery, then a batch whose `Email/get` deliberately replies in [getOrder]. */
    private fun transport(
        queryIds: List<String>,
        getOrder: List<String>,
        dates: Map<String, String> = emptyMap(),
        snoozed: Map<String, String> = emptyMap(),
    ): RecordingTransport = RecordingTransport { request ->
        val body =
            if (request.url.endsWith("/.well-known/jmap")) {
                session
            } else {
                val ids = queryIds.joinToString(",") { "\"$it\"" }
                // Reversed, because the real server does: Thread/get reorders
                // as Email/get does, and not into id order either.
                val threads =
                    getOrder.reversed().joinToString(",") { id ->
                        val until = snoozed[id]?.let { "\"$it\"" } ?: "null"
                        """{"id":"t$id","emailIds":["$id"],"snoozedUntil":$until}"""
                    }
                val list =
                    getOrder.joinToString(",") { id ->
                        val received = dates[id] ?: "2026-07-01T10:00:0${id.take(1)}Z"
                        """{"id":"$id","threadId":"t$id","receivedAt":"$received","preview":"p$id"}"""
                    }

                """
                {
                  "sessionState": "s",
                  "methodResponses": [
                    ["Email/query", {"accountId":"13","queryState":"q","ids":[$ids]}, "c0"],
                    ["Email/get", {"accountId":"13","state":"e","list":[$list]}, "c1"],
                    ["Thread/get", {"accountId":"13","state":"t","list":[$threads]}, "c2"]
                  ]
                }
                """
            }

        HttpResponse(200, mapOf("Content-Type" to "application/json"), body.encodeToByteArray())
    }
}
