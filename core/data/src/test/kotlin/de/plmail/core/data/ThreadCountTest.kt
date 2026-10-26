package de.plmail.core.data

import de.plmail.core.database.MailboxEntity
import de.plmail.core.database.PlMailDatabase
import de.plmail.core.database.StoreKey
import de.plmail.core.database.ThreadEntity
import de.plmail.jmap.mail.Email
import de.plmail.jmap.mail.MailThread
import de.plmail.jmap.protocol.EmailId
import de.plmail.jmap.protocol.ThreadId
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * How many messages a conversation's row says it holds, in Trash and everywhere else.
 *
 * The bug this pins: six drafts deleted from a two-message conversation, and its row read "8" in
 * the inbox and in Trash alike — the deleted ones counted where they are not, the kept ones
 * likewise.
 */
@RunWith(RobolectricTestRunner::class)
// sdk = 36 for the reason FeedProjectionTest gives.
@Config(sdk = [36])
class ThreadCountTest {

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
    fun `deleted messages count in Trash and nowhere else, cached or not`() = runTest {
        seedMailboxes()
        val thread = MailThread(ThreadId("t1"), (1..8).map { EmailId("m$it") })
        val members = (1..8).map { n -> email("m$n", if (n <= 2) "inbox" else "trash") }

        // The list caches only the newest message; the other seven arrive as mailboxes alone.
        MailRepository(database)
            .storeEmails(
                testAccountKey,
                listOf(email("m1", "inbox")),
                listOf(thread),
                fetchedAt = 0,
                members = members,
            )

        val row = database.threads().byUid(StoreKey.objectKey(testAccountKey, "t1"))!!
        assertEquals(2, row.messagesShown(inBin = false))
        assertEquals(6, row.messagesShown(inBin = true))
    }

    /**
     * Its date, likewise. A draft deleted this morning dated a conversation last answered on Sunday
     * as this morning's in the inbox — and only sometimes, depending on which sync wrote its row.
     */
    @Test
    fun `deleted messages date the conversation in Trash and nowhere else`() = runTest {
        seedMailboxes()
        val thread = MailThread(ThreadId("t1"), (1..3).map { EmailId("m$it") })
        val members =
            listOf(
                email("m1", "inbox", at = "2026-10-25T15:17:00Z"),
                email("m2", "trash", at = "2026-10-25T21:48:00Z"),
                email("m3", "trash", at = "2026-10-26T06:28:00Z"),
            )

        MailRepository(database)
            .storeEmails(
                testAccountKey,
                listOf(members.first()),
                listOf(thread),
                fetchedAt = 0,
                members = members,
            )

        val row = database.threads().byUid(StoreKey.objectKey(testAccountKey, "t1"))!!
        assertEquals(millis("2026-10-25T15:17:00Z"), row.receivedShown(inBin = false))
        assertEquals(millis("2026-10-26T06:28:00Z"), row.receivedShown(inBin = true))
    }

    @Test
    fun `a conversation entirely in Trash keeps a date everywhere`() = runTest {
        seedMailboxes()
        val only = email("m1", "trash", at = "2026-10-26T06:28:00Z")

        MailRepository(database)
            .storeEmails(
                testAccountKey,
                listOf(only),
                listOf(MailThread(ThreadId("t1"), listOf(EmailId("m1")))),
                fetchedAt = 0,
                members = listOf(only),
            )

        val row = database.threads().byUid(StoreKey.objectKey(testAccountKey, "t1"))!!
        assertEquals(millis("2026-10-26T06:28:00Z"), row.receivedShown(inBin = false))
    }

    @Test
    fun `a message put back in the inbox with the bin still on counts as kept`() = runTest {
        seedMailboxes()
        val thread = MailThread(ThreadId("t1"), listOf(EmailId("m1"), EmailId("m2")))

        MailRepository(database)
            .storeEmails(
                testAccountKey,
                listOf(email("m1", "inbox", "trash")),
                listOf(thread),
                fetchedAt = 0,
                members = listOf(email("m1", "inbox", "trash"), email("m2", "trash")),
            )

        val row = database.threads().byUid(StoreKey.objectKey(testAccountKey, "t1"))!!
        assertEquals(1, row.messagesShown(inBin = false))
        assertEquals(1, row.messagesShown(inBin = true))
    }

    @Test
    fun `a count that has not caught up shows the whole conversation rather than none`() {
        val row =
            ThreadEntity(uid = "u", accountKey = testAccountKey, threadId = "t1", messageCount = 3)

        assertEquals(3, row.messagesShown(inBin = true))
    }

    private suspend fun seedMailboxes() {
        database
            .mailboxes()
            .upsert(
                listOf("trash", "inbox").map { role ->
                    MailboxEntity(
                        uid = StoreKey.objectKey(testAccountKey, role),
                        accountKey = testAccountKey,
                        mailboxId = role,
                        name = role,
                        role = role,
                    )
                }
            )
    }

    private fun email(id: String, vararg mailboxes: String, at: String = "2026-10-26T08:00:00Z") =
        Email(
            id = EmailId(id),
            threadId = ThreadId("t1"),
            receivedAt = at,
            mailboxIds = mailboxes.associateWith { true },
        )

    private fun millis(at: String) = java.time.Instant.parse(at).toEpochMilli()
}
