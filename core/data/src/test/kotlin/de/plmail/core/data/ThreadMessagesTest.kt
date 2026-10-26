package de.plmail.core.data

import de.plmail.core.database.EmailEntity
import de.plmail.core.database.MailboxEntity
import de.plmail.core.database.PlMailDatabase
import de.plmail.core.database.StoreKey
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Which of a conversation's messages the reader is given.
 *
 * The bug this pins: a draft deleted from the reader went to Trash, stayed in its conversation —
 * membership does not depend on the mailbox — and was drawn again after the next sync, as though
 * the delete had done nothing.
 */
@RunWith(RobolectricTestRunner::class)
// sdk = 36 for the reason FeedProjectionTest gives.
@Config(sdk = [36])
class ThreadMessagesTest {

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
    fun `a message in Trash is left out of its conversation`() = runTest {
        seed(email("1", mailboxIds = "inbox"), email("2", mailboxIds = "drafts,trash"))

        val shown = MailRepository(database).messagesInThread(testAccountKey, "t1")

        assertEquals(listOf("1"), shown.map { it.emailId })
    }

    @Test
    fun `a conversation entirely in Trash is shown whole`() = runTest {
        seed(email("1", mailboxIds = "trash"), email("2", mailboxIds = "trash"))

        val shown = MailRepository(database).messagesInThread(testAccountKey, "t1")

        assertEquals(listOf("1", "2"), shown.map { it.emailId }.sorted())
    }

    private suspend fun seed(vararg emails: EmailEntity) {
        database
            .mailboxes()
            .upsert(
                listOf(
                    MailboxEntity(
                        uid = StoreKey.objectKey(testAccountKey, "trash"),
                        accountKey = testAccountKey,
                        mailboxId = "trash",
                        name = "Trash",
                        role = "trash",
                    )
                )
            )
        database.emails().upsert(emails.toList())
    }

    private fun email(id: String, mailboxIds: String) =
        EmailEntity(
            uid = StoreKey.objectKey(testAccountKey, id),
            accountKey = testAccountKey,
            emailId = id,
            threadId = "t1",
            receivedAt = id.toLong(),
            mailboxIds = mailboxIds,
        )
}
