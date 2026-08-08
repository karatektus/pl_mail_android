package de.plmail.core.data

import de.plmail.core.database.EmailBodyEntity
import de.plmail.core.database.EmailEntity
import de.plmail.core.database.PlMailDatabase
import de.plmail.core.database.StoreKey
import de.plmail.jmap.client.HttpResponse
import de.plmail.jmap.testing.RecordingTransport
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The body prefetch, over a real database and a real request.
 *
 * Two of the three tests here are about the *marker* rather than the download, because that is the
 * part that is not obvious: `toBodyEntity` stores nothing for a message with neither text nor html,
 * which is correct — an unfetched body must stay distinguishable from an empty one — and leaves the
 * genuinely empty message answering "missing a body" forever. Unnoticed, that is a permanent
 * occupant of a fifty-message budget and one `Email/get` on every open of the thread it is in.
 */
@RunWith(RobolectricTestRunner::class)
// sdk = 36 for the reason the other Robolectric suites in this module give: a
// library module inherits compileSdk 37, which Robolectric has no image for.
@Config(sdk = [36])
class BodyPrefetcherTest {

    private lateinit var database: PlMailDatabase

    @Before
    fun open() {
        database = inMemoryDatabase()
    }

    @After
    fun close() {
        database.close()
    }

    @Test
    fun `a cached message with no body gets one, asking for the body values`() = runTest {
        database.seedAccount()
        database.seedMessage("m1")

        val transport = serving(bodyOf("m1", text = "Seeded body."))
        bodyPrefetcher(database, transport).prefetch(testAccountKey)

        assertEquals(
            "Seeded body.",
            database.emails().body(StoreKey.objectKey(testAccountKey, "m1"))?.textBody,
        )

        val request = transport.requests.last { it.url.endsWith("/jmap/api") }.body!!.decodeToString()
        assertTrue(request.contains("\"fetchTextBodyValues\":true"), request)
        // The capitalisation trap: the camel-cased spelling is silently ignored
        // and comes back with empty body values and nothing to debug.
        assertTrue(request.contains("\"fetchHTMLBodyValues\":true"), request)
    }

    /**
     * The whole reason the marker exists.
     *
     * A message with no text and no html stores no body row, so a second run finds it "missing"
     * again — and would go on finding it, every fifteen minutes, forever.
     */
    @Test
    fun `a message that genuinely has no body is not asked for twice`() = runTest {
        database.seedAccount()
        database.seedMessage("m1")

        val transport = serving(bodyOf("m1", text = null))
        val prefetcher = bodyPrefetcher(database, transport)

        prefetcher.prefetch(testAccountKey)

        val marker = database.emails().body(StoreKey.objectKey(testAccountKey, "m1"))
        assertNotNull(marker, "an empty body is recorded as fetched rather than left absent")
        assertEquals("", marker.textBody)

        val asked = transport.requests.count { it.url.endsWith("/jmap/api") }
        prefetcher.prefetch(testAccountKey)

        assertEquals(
            asked,
            transport.requests.count { it.url.endsWith("/jmap/api") },
            "the second run has nothing missing a body, so it asks nothing",
        )
    }

    /**
     * Pruning is by last read, and spares what the user has claimed.
     *
     * Flagged mail and drafts are exempt unconditionally: both are things somebody said they were
     * coming back to, and a draft is the one row here whose body is not simply re-fetchable.
     */
    @Test
    fun `pruning drops stale bodies but keeps flagged ones`() = runTest {
        database.seedAccount()
        database.seedMessage("old")
        database.seedMessage("kept", isFlagged = true)

        val ancient = System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000
        listOf("old", "kept").forEach {
            database
                .emails()
                .upsertBody(
                    EmailBodyEntity(
                        uid = StoreKey.objectKey(testAccountKey, it),
                        textBody = "x",
                        fetchedAt = ancient,
                    )
                )
        }

        bodyPrefetcher(database, serving("{}")).prune()

        assertNull(database.emails().body(StoreKey.objectKey(testAccountKey, "old")))
        assertNotNull(database.emails().body(StoreKey.objectKey(testAccountKey, "kept")))
    }

    // -- fixtures ------------------------------------------------------------

    private suspend fun PlMailDatabase.seedMessage(
        emailId: String,
        isFlagged: Boolean = false,
        receivedAt: Long = 5_000,
    ) {
        emails()
            .upsert(
                listOf(
                    EmailEntity(
                        uid = StoreKey.objectKey(testAccountKey, emailId),
                        accountKey = testAccountKey,
                        emailId = emailId,
                        threadId = "t-$emailId",
                        receivedAt = receivedAt,
                        isFlagged = isFlagged,
                    )
                )
            )
    }

    /** An `Email/get` answer, with or without anything in it to store. */
    private fun bodyOf(emailId: String, text: String?): String {
        val parts =
            if (text == null) """"textBody":[],"htmlBody":[],"bodyValues":{}"""
            else
                """"textBody":[{"partId":"1","type":"text/plain"}],"htmlBody":[],
                   "bodyValues":{"1":{"value":${'"'}$text${'"'}}}"""

        return """
            {"sessionState":"s","methodResponses":[
              ["Email/get",{"accountId":"$TEST_ACCOUNT_ID","state":"s1","list":[
                {"id":"$emailId","threadId":"t-$emailId","receivedAt":"2026-01-01T00:00:00Z",
                 $parts}],"notFound":[]},"c0"]]}
            """
    }

    private fun serving(api: String): RecordingTransport = RecordingTransport { request ->
        HttpResponse(
            status = 200,
            headers = mapOf("Content-Type" to "application/json"),
            body =
                (if (request.url.endsWith("/.well-known/jmap")) TEST_SESSION else api)
                    .encodeToByteArray(),
        )
    }
}
