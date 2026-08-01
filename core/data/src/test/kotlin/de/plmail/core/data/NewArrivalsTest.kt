package de.plmail.core.data

import de.plmail.core.database.StoreKey
import de.plmail.jmap.mail.Email
import de.plmail.jmap.protocol.EmailId
import de.plmail.jmap.protocol.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a sync is allowed to interrupt somebody for.
 *
 * Every filter here fails in the same direction — towards *more* notifications, at three in the
 * morning, for mail the user has already dealt with — and none of them throws when it is wrong.
 * That is the whole reason this is a pure function with a test rather than four lines inside the
 * sync loop.
 */
class NewArrivalsTest {

    private val account = "https://nas.local/13"
    private val inbox = "1"

    @Test
    fun `unread mail in the inbox is announced`() {
        val arrivals =
            newArrivals(
                emails = listOf(email("5", subject = "Hello")),
                accountKey = account,
                accountName = "someone@example.com",
                inboxMailboxId = inbox,
                known = emptySet(),
            )

        val message = arrivals.single()

        assertEquals("5", message.emailId)
        assertEquals("t5", message.threadId)
        assertEquals("Ada Lovelace", message.sender)
        assertEquals("Hello", message.subject)
        assertEquals("someone@example.com", message.accountName)
    }

    /**
     * "New" means new to this device, not new to the server.
     *
     * A cursor that is discarded and rebuilt re-fetches everything it already had, and a server
     * that reports a touched row as created does the same for one message. Both would announce old
     * mail, and the second is the reason this does not simply trust `Email/changes`'s `created`.
     */
    @Test
    fun `mail the cache already holds is not new, however the server describes it`() {
        val arrivals =
            newArrivals(
                emails = listOf(email("5"), email("6")),
                accountKey = account,
                accountName = "a",
                inboxMailboxId = inbox,
                known = setOf(StoreKey.objectKey(account, "5")),
            )

        assertEquals(listOf("6"), arrivals.map { it.emailId })
    }

    /** Read elsewhere is dealt with. Announcing it announces the user's own past. */
    @Test
    fun `mail already read is not announced`() {
        val arrivals =
            newArrivals(
                emails = listOf(email("5", seen = true)),
                accountKey = account,
                accountName = "a",
                inboxMailboxId = inbox,
                known = emptySet(),
            )

        assertTrue(arrivals.isEmpty())
    }

    /**
     * Sent mail, drafts, and anything a server-side rule has already filed.
     *
     * All three arrive through `Email/changes` exactly like inbox mail, and all three are changes
     * worth syncing that nobody wants a buzz for. The user's own sent message is the one that
     * really stings.
     */
    @Test
    fun `mail outside the inbox is synced but not announced`() {
        val arrivals =
            newArrivals(
                emails = listOf(email("5", mailbox = "3")),
                accountKey = account,
                accountName = "a",
                inboxMailboxId = inbox,
                known = emptySet(),
            )

        assertTrue(arrivals.isEmpty())
    }

    /** A binding set to `false` is not membership; JMAP sends the key either way. */
    @Test
    fun `a mailbox binding turned off does not count as being in the inbox`() {
        val arrivals =
            newArrivals(
                emails = listOf(email("5").copy(mailboxIds = mapOf(inbox to false))),
                accountKey = account,
                accountName = "a",
                inboxMailboxId = inbox,
                known = emptySet(),
            )

        assertTrue(arrivals.isEmpty())
    }

    /**
     * A message the server has not threaded is a conversation of one.
     *
     * The notification is keyed on the thread, so a null there would key every unthreaded message
     * to the same notification and each would replace the last.
     */
    @Test
    fun `an unthreaded message falls back to its own id`() {
        val arrivals =
            newArrivals(
                emails = listOf(email("5").copy(threadId = null)),
                accountKey = account,
                accountName = "a",
                inboxMailboxId = inbox,
                known = emptySet(),
            )

        assertEquals("5", arrivals.single().threadId)
    }

    private fun email(
        id: String,
        subject: String? = "The quarterly figures",
        seen: Boolean = false,
        mailbox: String = "1",
    ): Email =
        Email(
            id = EmailId(id),
            threadId = ThreadId("t$id"),
            subject = subject,
            from =
                listOf(
                    de.plmail.jmap.mail.EmailAddress(name = "Ada Lovelace", email = "ada@x.test")
                ),
            receivedAt = "2026-08-01T09:05:00Z",
            preview = "Attached is the full breakdown.",
            mailboxIds = mapOf(mailbox to true),
            keywords = if (seen) mapOf("\$seen" to true) else emptyMap(),
        )
}
