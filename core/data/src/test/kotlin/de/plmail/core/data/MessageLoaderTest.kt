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

        // Only what was missing: m3 is cached, body and all.
        val asked = emailGets(transport).single()
        assertTrue(asked.contains("\"m1\"") && asked.contains("\"m2\""), asked)
        assertFalse(asked.contains("\"m3\""), asked)
    }

    /** Fetching is not reading: a message nobody has looked at yet stays unread. */
    @Test
    fun `fetched messages keep the read state the server reports`() = runTest {
        database.seedAccount()
        database.cache("m3", seen = true)

        loader(server(members = listOf("m1", "m2", "m3"), seen = setOf("m1")))
            .loadBodies(testAccountKey, "t1")

        val stored = database.emails().inThread(testAccountKey, "t1").associateBy { it.emailId }
        assertTrue(stored.getValue("m1").isSeen)
        assertFalse(stored.getValue("m2").isSeen)
    }

    /** A conversation the device already holds whole costs the one question and nothing else. */
    @Test
    fun `a complete thread with its bodies cached sends no Email get`() = runTest {
        database.seedAccount()
        database.cache("m1", seen = true)
        database.cache("m2", seen = true)

        val transport = server(members = listOf("m1", "m2"))
        loader(transport).loadBodies(testAccountKey, "t1")

        assertEquals(emptyList(), emailGets(transport))
    }

    /** A conversation the server no longer has: the bodies are all there is to fetch. */
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
    ) {
        emails()
            .upsert(
                listOf(
                    EmailEntity(
                        uid = uidOf(emailId),
                        accountKey = testAccountKey,
                        emailId = emailId,
                        threadId = "t1",
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

    private fun emailGets(transport: RecordingTransport): List<String> =
        transport.requests
            .mapNotNull { it.body?.decodeToString() }
            .filter { it.contains("Email/get") }

    /**
     * A server holding conversation t1 with [members], or not holding it at all when null, and
     * answering `Email/get` for whichever ids it is asked.
     */
    private fun server(members: List<String>?, seen: Set<String> = emptySet()) =
        RecordingTransport { request ->
            val asked = request.body?.decodeToString().orEmpty()

            val body =
                when {
                    request.url.endsWith("/.well-known/jmap") -> TEST_SESSION
                    asked.contains("Thread/get") -> {
                        val list = members?.let { ids ->
                            """{"id":"t1","emailIds":[${ids.joinToString(",") { "\"$it\"" }}]}"""
                        }
                        """
                        {"sessionState":"s","methodResponses":[
                          ["Thread/get",{"accountId":"$TEST_ACCOUNT_ID","state":"h1",
                            "list":[${list.orEmpty()}],
                            "notFound":[${if (list == null) "\"t1\"" else ""}]},"c0"]]}
                        """
                    }
                    else -> {
                        val ids =
                            Regex("\"ids\":\\[([^]]*)]")
                                .find(asked)!!
                                .groupValues[1]
                                .split(",")
                                .map { it.trim('"') }
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
                        """
                        {"sessionState":"s","methodResponses":[
                          ["Email/get",{"accountId":"$TEST_ACCOUNT_ID","state":"e1",
                            "list":[$list],"notFound":[]},"c0"]]}
                        """
                    }
                }

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
