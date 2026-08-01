package de.plmail.core.notifications

import de.plmail.core.data.NewMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * What the shade says, and which notification each thing lands on.
 *
 * Both halves are here because both fail the same way: silently, and only once there are two of
 * them. A wrong id shows up as an action operating on the wrong conversation; a wrong count shows
 * up as a summary that disagrees with the rows underneath it. Neither throws, and neither is
 * visible with one message on screen, which is the state everything gets tested in by hand.
 */
class SummaryContentTest {

    /**
     * The id is the conversation's, not the message's.
     *
     * A reply arriving in a thread the user has already been told about must replace that
     * notification rather than stack beside it — otherwise a nine-message thread is nine rows in
     * the shade for one conversation.
     */
    @Test
    fun `two messages in one conversation share a notification`() {
        assertEquals(
            notificationId(message(threadId = "t1", emailId = "1")),
            notificationId(message(threadId = "t1", emailId = "2")),
        )
    }

    @Test
    fun `two conversations do not`() {
        assertNotEquals(
            notificationId(message(threadId = "t1")),
            notificationId(message(threadId = "t2")),
        )
    }

    /**
     * The same conversation in two accounts is two notifications.
     *
     * JMAP ids are unique only within an account, so `t1` in one mailbox and `t1` in another are
     * unrelated conversations — and a shared notification id would have one account's mail silently
     * replace the other's.
     */
    @Test
    fun `the same thread id in two accounts is two notifications`() {
        assertNotEquals(
            notificationId(message(accountKey = "https://a/1", threadId = "t1")),
            notificationId(message(accountKey = "https://b/1", threadId = "t1")),
        )
    }

    @Test
    fun `the summary never collides with a conversation, or with another account`() {
        assertNotEquals(
            summaryId("https://a/1"),
            notificationId(message(accountKey = "https://a/1")),
        )
        assertNotEquals(summaryId("https://a/1"), summaryId("https://b/1"))
        assertNotEquals(groupKey("https://a/1"), groupKey("https://b/1"))
    }

    @Test
    fun `each arriving conversation gets a line`() {
        val content =
            summaryContent(
                arriving =
                    listOf(
                        message(threadId = "t1", sender = "Ada", subject = "Figures"),
                        message(threadId = "t2", sender = "Grace", subject = "Compilers"),
                    ),
                alreadyShowing = emptyList(),
                maxLines = 5,
            )

        assertEquals(listOf("Ada  Figures", "Grace  Compilers"), content.lines)
        assertEquals(0, content.overflow)
        assertEquals(2, content.total)
    }

    /**
     * The trap this file exists for.
     *
     * Mail already in the shade counts, but a conversation being *replaced* by this batch must not
     * be counted on both sides. Adding the batch size to the number of posted children is the
     * obvious arithmetic and it says "3" over two rows.
     */
    @Test
    fun `a reply to a conversation already showing is not counted twice`() {
        val reply = message(threadId = "t1", sender = "Ada", subject = "Re: Figures")

        val content =
            summaryContent(
                arriving = listOf(reply),
                // Its own notification, plus one for an unrelated conversation
                // the user has not dealt with.
                alreadyShowing = listOf(notificationId(reply), notificationId(message("t9"))),
                maxLines = 5,
            )

        assertEquals(2, content.total, "one replaced conversation and one carried over")
        assertEquals(1, content.overflow, "the carried-over one has no line to show")
    }

    /** Mail from an earlier arrival is counted even though nothing here knows what it said. */
    @Test
    fun `conversations still in the shade are counted without lines`() {
        val content =
            summaryContent(
                arriving = listOf(message(threadId = "t1")),
                alreadyShowing = listOf(1, 2, 3),
                maxLines = 5,
            )

        assertEquals(4, content.total)
        assertEquals(1, content.lines.size)
        assertEquals(3, content.overflow)
    }

    @Test
    fun `more arrivals than fit become overflow rather than lines`() {
        val content =
            summaryContent(
                arriving = (1..9).map { message(threadId = "t$it", subject = "Subject $it") },
                alreadyShowing = emptyList(),
                maxLines = 5,
            )

        assertEquals(5, content.lines.size)
        assertEquals(4, content.overflow)
        assertEquals(9, content.total)
    }

    /**
     * A message with no subject still gets a line.
     *
     * Mail genuinely arrives without one, and a blank entry in an InboxStyle draws as an empty row
     * — which reads as a rendering failure rather than as a message from someone who was in a
     * hurry.
     */
    @Test
    fun `a missing subject leaves the sender rather than an empty line`() {
        val content =
            summaryContent(
                arriving = listOf(message(threadId = "t1", sender = "Ada", subject = null)),
                alreadyShowing = emptyList(),
                maxLines = 5,
            )

        assertEquals(listOf("Ada"), content.lines)
    }

    /** Two messages of one conversation in a single batch are one line, not two identical ones. */
    @Test
    fun `a batch carrying one conversation twice writes one line`() {
        val content =
            summaryContent(
                arriving =
                    listOf(
                        message(threadId = "t1", emailId = "1", subject = "Figures"),
                        message(threadId = "t1", emailId = "2", subject = "Re: Figures"),
                    ),
                alreadyShowing = emptyList(),
                maxLines = 5,
            )

        assertEquals(1, content.lines.size)
        assertEquals(1, content.total)
        assertTrue(content.lines.single().endsWith("Figures"))
    }

    private fun message(
        threadId: String = "t1",
        emailId: String = "1",
        accountKey: String = "https://nas.local/13",
        sender: String = "Ada Lovelace",
        subject: String? = "The quarterly figures",
    ) =
        NewMessage(
            accountKey = accountKey,
            accountName = "someone@example.com",
            emailId = emailId,
            threadId = threadId,
            sender = sender,
            subject = subject,
            preview = "Attached is the full breakdown.",
            receivedAt = 1_785_744_000_000,
        )
}
