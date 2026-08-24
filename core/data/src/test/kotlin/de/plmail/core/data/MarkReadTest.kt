package de.plmail.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.plmail.core.database.EmailEntity
import de.plmail.core.database.PlMailDatabase
import de.plmail.core.database.StoreKey
import de.plmail.jmap.client.HttpResponse
import de.plmail.jmap.testing.RecordingTransport
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Reading a message has to reach the server, and must not reach the rest of the conversation.
 *
 * Both halves are one bug seen from opposite ends. The reader used to mark a message read in the
 * cache and stop there, so the next pull-to-refresh read `$seen` back from a server that had never
 * been told and the conversation the user had just finished reading turned unread again under their
 * thumb. The obvious repair — send the existing conversation-wide `MarkRead` — swaps that for the
 * worse one: opening a thread would retire the unread marker on every message below the fold, which
 * is the single mistake in a mail client a user cannot undo, because afterwards they do not know
 * what they missed.
 *
 * So the scope is asserted on the wire rather than only in the database. What the server is told is
 * the part no local assertion can see, and it is the half that was missing.
 */
@RunWith(RobolectricTestRunner::class)
// sdk = 36 for the reason the other Robolectric suites in this module give: a
// library module inherits compileSdk 37, which Robolectric has no image for.
@Config(sdk = [36])
class MarkReadTest {

    private lateinit var database: PlMailDatabase

    @Before
    fun open() {
        database = inMemoryDatabase()
    }

    @After
    fun close() {
        database.close()
    }

    /** The regression: the change leaves the phone. */
    @Test
    fun `marking one message read tells the server about that message`() = runTest {
        seedConversation()

        val transport = serving(setUpdating("m1"))
        val outcome =
            actions(transport)
                .apply(
                    MailAction.MarkRead(seen = true),
                    listOf(ActionTarget(testAccountKey, "t1", emailId = "m1")),
                )

        assertTrue(outcome is ActionOutcome.Applied, "the server accepted it: $outcome")

        val sent = transport.requests.last { it.url.endsWith("/jmap/api") }.body!!.decodeToString()
        assertTrue(sent.contains("\"Email/set\""), sent)
        // The patch, spelled the way the server reads it: a keyword *patch*
        // key, not a replacement `keywords` map, which would drop every other
        // flag the message carries.
        assertTrue(sent.contains("\"keywords/\$seen\":true"), sent)
        assertTrue(sent.contains("\"m1\""), sent)
        assertFalse(sent.contains("\"m2\""), "only the message that was read: $sent")
    }

    /**
     * The other end, which is why the target carries a message id at all.
     *
     * A thread of three opened to read the newest has two the user has still never seen. Marking
     * the conversation would clear both, on screen and on the server, for a gesture that was
     * "scroll one message into view".
     */
    @Test
    fun `the rest of the conversation is left unread, and so is the conversation`() = runTest {
        seedConversation()

        actions(serving(setUpdating("m1")))
            .apply(
                MailAction.MarkRead(seen = true),
                listOf(ActionTarget(testAccountKey, "t1", emailId = "m1")),
            )

        assertTrue(seen("m1"), "the message that was displayed is read")
        assertFalse(seen("m2"), "the one below it is not")

        val thread = database.threads().byUid(StoreKey.objectKey(testAccountKey, "t1"))
        assertNotNull(thread)
        assertTrue(thread.isUnread, "a conversation with an unread message left in it is unread")
    }

    /** And once the last one is read, the conversation stops being unread without being told. */
    @Test
    fun `reading the last unread message settles the conversation`() = runTest {
        seedConversation()

        val actions = actions(serving(setUpdating("m1", "m2")))

        listOf("m1", "m2").forEach {
            actions.apply(
                MailAction.MarkRead(seen = true),
                listOf(ActionTarget(testAccountKey, "t1", emailId = it)),
            )
        }

        val thread = database.threads().byUid(StoreKey.objectKey(testAccountKey, "t1"))
        assertNotNull(thread)
        assertFalse(thread.isUnread)
    }

    /**
     * The default is still the conversation, and every existing caller relies on it.
     *
     * Swiping "mark read" over a list row knows a thread id and nothing else, so a target with no
     * message id has to keep meaning "all of them" — on the wire as well as in the cache.
     */
    @Test
    fun `a target with no message id still means the whole conversation`() = runTest {
        seedConversation()

        val transport = serving(setUpdating("m1", "m2"))
        actions(transport)
            .apply(MailAction.MarkRead(seen = true), listOf(ActionTarget(testAccountKey, "t1")))

        val sent = transport.requests.last { it.url.endsWith("/jmap/api") }.body!!.decodeToString()
        assertTrue(sent.contains("\"m1\""), sent)
        assertTrue(sent.contains("\"m2\""), sent)

        assertTrue(seen("m1"))
        assertTrue(seen("m2"))
        assertEquals(
            false,
            database.threads().byUid(StoreKey.objectKey(testAccountKey, "t1"))?.isUnread,
        )
    }

    // -- fixtures ------------------------------------------------------------

    private suspend fun seedConversation() {
        database.seedAccount()
        database.seedThread("t1", isUnread = true, isInInbox = true)

        database
            .emails()
            .upsert(
                listOf("m1", "m2").map {
                    EmailEntity(
                        uid = StoreKey.objectKey(testAccountKey, it),
                        accountKey = testAccountKey,
                        emailId = it,
                        threadId = "t1",
                        receivedAt = 5_000,
                        isSeen = false,
                    )
                }
            )
    }

    private suspend fun seen(emailId: String): Boolean =
        database.emails().byUid(StoreKey.objectKey(testAccountKey, emailId))?.isSeen == true

    private suspend fun actions(transport: RecordingTransport): MailActions =
        MailActions(
            database = database,
            clients = testClients(transport),
            mail = MailRepository(database),
            outbox = emptyOutbox(),
            context = ApplicationProvider.getApplicationContext<Context>(),
        )

    /** An `Email/set` that accepted every id it was given. */
    private fun setUpdating(vararg ids: String): String {
        val updated = ids.joinToString(",") { "\"$it\":null" }

        return """
            {"sessionState":"s","methodResponses":[
              ["Email/set",{"accountId":"$TEST_ACCOUNT_ID","newState":"s2",
                "updated":{$updated},"notUpdated":{}},"c0"]]}
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
