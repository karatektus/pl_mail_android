package de.plmail.core.data

import de.plmail.core.database.EmailBodyEntity
import de.plmail.core.database.EmailEntity
import de.plmail.core.database.PlMailDatabase
import de.plmail.core.database.StoreKey
import de.plmail.core.datastore.CredentialStore
import de.plmail.core.datastore.ServerConnection
import de.plmail.jmap.client.Credential
import de.plmail.jmap.client.HttpResponse
import de.plmail.jmap.client.ParsedAddress
import de.plmail.jmap.client.ServerAddress
import de.plmail.jmap.testing.RecordingTransport
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Opening a conversation downloads it as the server holds it (#62).
 *
 * A list page is collapsed to one message per conversation, so a thread paged in from the list
 * arrives as its newest message alone — and until the reader asked the conversation what it holds,
 * that was all it ever showed.
 */
@RunWith(RobolectricTestRunner::class)
// sdk = 36 for the reason the other Robolectric suites in this module give: a
// library module inherits compileSdk 37, which Robolectric has no image for.
@Config(sdk = [36])
class MessageLoaderTest {

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
    fun `the messages the device has never seen are fetched when the thread is opened`() = runTest {
        database.seedAccount()
        database.cache("m3", seen = false)

        val transport = server(members = listOf("m1", "m2", "m3"))
        loader(transport).loadBodies(testAccountKey, "t1")

        val stored = database.emails().inThread(testAccountKey, "t1").associateBy { it.emailId }
        assertEquals(setOf("m1", "m2", "m3"), stored.keys)
        assertEquals("Body of m1.", database.emails().body(uidOf("m1"))?.textBody)

        // Asked what it holds and sent it in the one request, not one after
        // the other.
        assertEquals(1, apiRequests(transport).size)
    }

    /** Fetching is not reading: a message nobody has looked at yet stays unread. */
    @Test
    fun `fetched messages keep the read state the server reports`() = runTest {
        database.seedAccount()
        database.cache("m3", seen = true)

        loader(server(members = listOf("m1", "m2", "m3"), seen = setOf("m1", "m3")))
            .loadBodies(testAccountKey, "t1")

        val stored = database.emails().inThread(testAccountKey, "t1").associateBy { it.emailId }
        assertTrue(stored.getValue("m1").isSeen)
        assertFalse(stored.getValue("m2").isSeen)
    }

    /** A conversation the device already holds whole opens without the server. */
    @Test
    fun `a thread held whole with its bodies sends nothing`() = runTest {
        database.seedAccount()
        database.seedThread("t1", messageCount = 2)
        database.cache("m1", seen = true)
        database.cache("m2", seen = true)

        val transport = server(members = listOf("m1", "m2"))
        loader(transport).loadBodies(testAccountKey, "t1")

        assertEquals(emptyList(), apiRequests(transport))
    }

    /** Held whole but for one body: that body alone, without asking what the thread holds. */
    @Test
    fun `a thread held whole asks only for the bodies it lacks`() = runTest {
        database.seedAccount()
        database.seedThread("t1", messageCount = 2)
        database.cache("m1", seen = true)
        database.cache("m2", seen = true, withBody = false)

        val transport = server(members = listOf("m1", "m2"))
        loader(transport).loadBodies(testAccountKey, "t1")

        val asked = apiRequests(transport).single()
        assertFalse(asked.contains("Thread/get"), asked)
        assertTrue(asked.contains("\"m2\"") && !asked.contains("\"m1\""), asked)
        assertEquals("Body of m2.", database.emails().body(uidOf("m2"))?.textBody)
    }

    /** Fewer messages held than the server counted: the thread is asked again. */
    @Test
    fun `a thread the device holds part of is fetched whole`() = runTest {
        database.seedAccount()
        database.seedThread("t1", messageCount = 3)
        database.cache("m3", seen = true)

        loader(server(members = listOf("m1", "m2", "m3"))).loadBodies(testAccountKey, "t1")

        assertEquals(3, database.emails().inThread(testAccountKey, "t1").size)
    }

    /** The rows on screen, downloaded ahead: every conversation's missing bodies in one request. */
    @Test
    fun `prefetching several conversations asks once`() = runTest {
        database.seedAccount()
        database.seedThread("t1")
        database.seedThread("t2")
        database.cache("m1", seen = true, withBody = false)
        database.cache("m2", seen = true, withBody = false, threadId = "t2")

        val transport = server(members = listOf("m1"))
        loader(transport).prefetch(testAccountKey, listOf("t1", "t2"))

        val asked = apiRequests(transport).single()
        assertTrue(asked.contains("\"m1\"") && asked.contains("\"m2\""), asked)
        assertFalse(asked.contains("Thread/get"), asked)
    }

    /** A conversation the server does not find: the bodies are all there is to fetch. */
    @Test
    fun `a thread the server does not find still gets its missing bodies`() = runTest {
        database.seedAccount()
        database.cache("m1", seen = true, withBody = false)

        val transport = server(members = null)
        loader(transport).loadBodies(testAccountKey, "t1")

        assertEquals("Body of m1.", database.emails().body(uidOf("m1"))?.textBody)
    }

    private fun uidOf(emailId: String) = StoreKey.objectKey(testAccountKey, emailId)

    private suspend fun PlMailDatabase.cache(
        emailId: String,
        seen: Boolean,
        withBody: Boolean = true,
        threadId: String = "t1",
    ) {
        emails()
            .upsert(
                listOf(
                    EmailEntity(
                        uid = uidOf(emailId),
                        accountKey = testAccountKey,
                        emailId = emailId,
                        threadId = threadId,
                        receivedAt = 5_000,
                        isSeen = seen,
                    )
                )
            )
        if (withBody) {
            emails()
                .upsertBody(
                    EmailBodyEntity(uid = uidOf(emailId), textBody = "cached", fetchedAt = 1L)
                )
        }
    }

    private fun apiRequests(transport: RecordingTransport): List<String> =
        transport.requests
            .filter { it.url.endsWith("/jmap/api") }
            .mapNotNull { it.body?.decodeToString() }

    /**
     * A server holding conversation t1 with [members], or not holding it at all when null, and
     * answering `Email/get` for whichever ids it is asked, by name or by reference to the thread.
     */
    private fun server(members: List<String>?, seen: Set<String> = emptySet()) =
        RecordingTransport { request ->
            val asked = request.body?.decodeToString().orEmpty()

            val responses = mutableListOf<String>()
            if (asked.contains("Thread/get")) {
                val list = members?.let { ids ->
                    """{"id":"t1","emailIds":[${ids.joinToString(",") { "\"$it\"" }}]}"""
                }
                responses +=
                    """
                    ["Thread/get",{"accountId":"$TEST_ACCOUNT_ID","state":"h1",
                      "list":[${list.orEmpty()}],
                      "notFound":[${if (list == null) "\"t1\"" else ""}]},"c0"]
                    """
            }
            if (asked.contains("Email/get")) {
                val ids =
                    if (asked.contains("\"#ids\"")) members.orEmpty()
                    else
                        Regex("\"ids\":\\[([^]]*)]").find(asked)!!.groupValues[1].split(",").map {
                            it.trim('"')
                        }
                val list =
                    ids.joinToString(",") { id ->
                        val keywords = if (id in seen) """{"${'$'}seen":true}""" else "{}"
                        """
                        {"id":"$id","threadId":"t1","receivedAt":"2026-08-01T10:00:00Z",
                         "keywords":$keywords,"mailboxIds":{"$INBOX_MAILBOX_ID":true},
                         "textBody":[{"partId":"1","type":"text/plain"}],
                         "bodyValues":{"1":{"value":"Body of $id."}}}
                        """
                    }
                responses +=
                    """
                    ["Email/get",{"accountId":"$TEST_ACCOUNT_ID","state":"e1",
                      "list":[$list],"notFound":[]},"c${responses.size}"]
                    """
            }

            val body =
                if (request.url.endsWith("/.well-known/jmap")) TEST_SESSION
                else """{"sessionState":"s","methodResponses":[${responses.joinToString(",")}]}"""

            HttpResponse(200, mapOf("Content-Type" to "application/json"), body.encodeToByteArray())
        }

    private suspend fun loader(transport: RecordingTransport): MessageLoader {
        val credentials = CredentialStore(InMemoryPreferences(), PlainCipher)
        credentials.save(
            ServerConnection(
                address = (ServerAddress.parse(TEST_SERVER) as ParsedAddress.Valid).address,
                credential = Credential.AppPassword("plmail_" + "a".repeat(64)),
                username = "someone@example.com",
            )
        )

        return MessageLoader(
            database = database,
            credentials = credentials,
            clients = AccountClients(credentials, fixedTransports(transport)),
            mail = MailRepository(database),
        )
    }
}
