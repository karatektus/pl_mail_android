package de.plmail.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.plmail.core.database.EmailEntity
import de.plmail.core.database.MailboxEntity
import de.plmail.core.database.PlMailDatabase
import de.plmail.core.database.StoreKey
import de.plmail.jmap.client.HttpResponse
import de.plmail.jmap.testing.RecordingTransport
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a conversation-wide action tells the server, asserted on the wire.
 *
 * Both cases were found on a phone as a loop: a conversation swiped to the bin came back a few
 * seconds later, was swiped away again by itself, and after an undo sat in the inbox and the bin at
 * once.
 */
@RunWith(RobolectricTestRunner::class)
// sdk = 36 for the reason FeedProjectionTest gives.
@Config(sdk = [36])
class ConversationActionsTest {

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
     * The list caches one message per conversation. Trashing by the cache moved that one, the rest
     * stayed in the inbox, and the next sync listed the conversation again.
     */
    @Test
    fun `trashing a conversation moves the messages this phone has not cached as well`() = runTest {
        seed()
        val transport = serving()

        val outcome =
            actions(transport).apply(MailAction.Trash, listOf(ActionTarget(testAccountKey, "t1")))

        assertTrue(outcome is ActionOutcome.Applied, "the server accepted it: $outcome")

        val set = sent(transport).last { it.contains("\"Email/set\"") }
        listOf("m1", "m2", "m3").forEach { assertTrue(set.contains("\"$it\""), "$it in $set") }
    }

    /** A conversation the phone holds whole needs no lookup before the change goes out. */
    @Test
    fun `a conversation held whole is trashed in one request`() = runTest {
        seed(messageCount = 1)
        val transport = serving()

        actions(transport).apply(MailAction.Trash, listOf(ActionTarget(testAccountKey, "t1")))

        val asked = sent(transport)
        assertTrue(asked.none { it.contains("\"Thread/get\"") }, asked.toString())
        assertTrue(asked.single().contains("\"m1\""), asked.toString())
    }

    /** Undoing a trash. Adding Inbox alone left the messages in the bin as well. */
    @Test
    fun `back to the inbox takes the bin, spam and archive off`() = runTest {
        seed()
        val transport = serving()

        actions(transport)
            .apply(MailAction.MoveToInbox, listOf(ActionTarget(testAccountKey, "t1", "m1")))

        val set = sent(transport).last { it.contains("\"Email/set\"") }
        assertTrue(set.contains("\"mailboxIds/$INBOX_MAILBOX_ID\":true"), set)
        listOf(TRASH, JUNK, ARCHIVE).forEach {
            assertTrue(set.contains("\"mailboxIds/$it\":null"), "$it removed: $set")
        }
    }

    private suspend fun seed(messageCount: Int = 3) {
        database.seedAccount()
        database.seedInbox()
        // Three, as the page that listed it counted: more than the cache holds.
        database.seedThread("t1", isInInbox = true, messageCount = messageCount)
        database
            .mailboxes()
            .upsert(
                listOf(TRASH to "trash", JUNK to "junk", ARCHIVE to "archive").map { (id, role) ->
                    MailboxEntity(
                        uid = StoreKey.objectKey(testAccountKey, id),
                        accountKey = testAccountKey,
                        mailboxId = id,
                        name = role,
                        role = role,
                    )
                }
            )
        // Only the newest message, as the list leaves the cache.
        database
            .emails()
            .upsert(
                listOf(
                    EmailEntity(
                        uid = StoreKey.objectKey(testAccountKey, "m1"),
                        accountKey = testAccountKey,
                        emailId = "m1",
                        threadId = "t1",
                        receivedAt = 5_000,
                    )
                )
            )
    }

    private suspend fun actions(transport: RecordingTransport): MailActions =
        MailActions(
            database = database,
            clients = testClients(transport),
            mail = MailRepository(database),
            outbox = emptyOutbox(),
            context = ApplicationProvider.getApplicationContext<Context>(),
        )

    private fun sent(transport: RecordingTransport): List<String> =
        transport.requests
            .filter { it.url.endsWith("/jmap/api") }
            .map { it.body!!.decodeToString() }

    /** The server: three messages in the conversation, and every change accepted. */
    private fun serving(): RecordingTransport = RecordingTransport { request ->
        val body = request.body?.decodeToString().orEmpty()

        val answer =
            when {
                request.url.endsWith("/.well-known/jmap") -> TEST_SESSION
                body.contains("\"Thread/get\"") ->
                    """
                    {"sessionState":"s","methodResponses":[
                      ["Thread/get",{"accountId":"$TEST_ACCOUNT_ID","state":"t",
                        "list":[{"id":"t1","emailIds":["m1","m2","m3"]}],"notFound":[]},"c0"]]}
                    """
                else ->
                    """
                    {"sessionState":"s","methodResponses":[
                      ["Email/set",{"accountId":"$TEST_ACCOUNT_ID","newState":"s2",
                        "updated":{"m1":null,"m2":null,"m3":null},
                        "destroyed":["m1","m2","m3"],"notUpdated":{},"notDestroyed":{}},"c0"]]}
                    """
            }

        HttpResponse(
            status = 200,
            headers = mapOf("Content-Type" to "application/json"),
            body = answer.encodeToByteArray(),
        )
    }

    private companion object {
        const val TRASH = "8"
        const val JUNK = "9"
        const val ARCHIVE = "10"
    }
}
