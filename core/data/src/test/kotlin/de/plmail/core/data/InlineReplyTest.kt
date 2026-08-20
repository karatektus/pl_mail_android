package de.plmail.core.data

import de.plmail.jmap.mail.Email
import de.plmail.jmap.mail.EmailAddress
import de.plmail.jmap.mail.EmailBodyPart
import de.plmail.jmap.mail.EmailBodyValue
import de.plmail.jmap.methods.SendHold
import de.plmail.jmap.methods.SubmissionRecord
import de.plmail.jmap.protocol.EmailId
import de.plmail.jmap.protocol.JmapError
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * A reply typed in the notification shade, and the four ways it can quietly go wrong.
 *
 * Nothing here is observable from the phone until it is too late. A reply that went out with a
 * quote nobody read, or as somebody else's address, or that was dropped because the send failed and
 * the result was thrown away — each of those is discovered in the recipient's mailbox or not at
 * all. So the send path is real: a real [SendQueue] over a recording [DraftSender], and the real
 * [de.plmail.jmap.mail.DraftComposer] deciding the headers. What is faked is the two things a JVM
 * test cannot have — the server that hands back the original, and the socket. Faking
 * [InlineReplies] itself would leave every assertion here a statement about the fake.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InlineReplyTest {

    private val anna = EmailAddress("Anna", "anna@example.org")
    private val me = EmailAddress("Me", "me@plmail.test")

    private val myIdentity =
        SendIdentity(
            accountKey = "https://mail.example/a",
            accountName = "Personal",
            identityId = "id-1",
            name = "Me",
            email = "me@plmail.test",
            htmlSignature = "<p>Sent from a phone</p>",
        )

    /** What the server would hand back for the message being answered. */
    private fun original(
        from: List<EmailAddress> = listOf(anna),
        to: List<EmailAddress> = listOf(me),
        cc: List<EmailAddress> = emptyList(),
        subject: String? = "Roof repairs",
    ): Email =
        Email(
            id = EmailId("e-1"),
            from = from,
            to = to,
            cc = cc,
            subject = subject,
            messageId = listOf("m3@example.org"),
            references = listOf("m1@example.org"),
            htmlBody = listOf(EmailBodyPart(partId = "html")),
            bodyValues = mapOf("html" to EmailBodyValue("<p>Can you come on Tuesday?</p>")),
        )

    private class FakeSource(
        val identities: List<SendIdentity>,
        val email: Email?,
        val failure: Throwable? = null,
    ) : ReplySource {
        override suspend fun sendingIdentities() = identities

        override suspend fun original(accountKey: String, emailId: String): Email? {
            failure?.let { throw it }

            return email
        }
    }

    /** The socket, recorded. Nothing here decides anything the tests are about. */
    private class RecordingSender(
        val failOnSave: Throwable? = null,
        val failOnSubmit: Throwable? = null,
    ) : DraftSender {
        val saved = mutableListOf<ComposeDraft>()
        val submitted = mutableListOf<ComposeDraft>()

        override suspend fun save(draft: ComposeDraft): ComposeDraft {
            failOnSave?.let { throw it }
            saved += draft

            return draft.copy(emailId = "draft-1")
        }

        override suspend fun submit(draft: ComposeDraft, hold: SendHold?): Submitted {
            failOnSubmit?.let { throw it }
            submitted += draft

            return Submitted(submissionId = draft.emailId.orEmpty())
        }

        override suspend fun cancel(accountKey: String, submissionId: String) =
            CancelOutcome.Cancelled

        override suspend fun releasedAt(
            accountKey: String,
            submissionId: String,
        ): SubmissionRecord? = null

        override suspend fun submissionMode(accountKey: String) = SubmissionMode.SERVER_HOLD
    }

    private fun TestScope.repliesOver(source: ReplySource, sender: DraftSender): InlineReplies {
        val scheduled = scheduledSends()

        return InlineReplies(
            source,
            SendQueue(
                sender,
                scheduled,
                ScheduledSendReconciler(scheduled, NoSubmissions),
                backgroundScope,
            ),
        )
    }

    private fun TestScope.replies(
        identities: List<SendIdentity> = listOf(myIdentity),
        email: Email? = original(),
        fetchFailure: Throwable? = null,
        sender: RecordingSender = RecordingSender(),
    ) = repliesOver(FakeSource(identities, email, fetchFailure), sender)

    // ------------------------------------------------------------------ the send

    @Test
    fun `what was typed is what is sent, addressed and threaded like an in-app reply`() = runTest {
        val sender = RecordingSender()

        val result =
            replies(sender = sender).send(myIdentity.accountKey, "e-1", "Tuesday works, thanks.")

        assertEquals(InlineReplyResult.Sent, result)

        val sent = sender.submitted.single()

        assertTrue(sent.bodyHtml.contains("Tuesday works, thanks."), sent.bodyHtml)
        assertEquals(listOf(anna), sent.to)
        assertEquals("Re: Roof repairs", sent.subject)
        // The half a reply is judged by in somebody else's client: get these
        // wrong and the answer arrives as a brand-new conversation.
        assertEquals(listOf("m3@example.org"), sent.inReplyTo)
        assertEquals(listOf("m1@example.org", "m3@example.org"), sent.references)
    }

    @Test
    fun `the draft reaches Drafts before anything asks for it to leave`() = runTest {
        // SendQueue's one invariant, and the reason the shade reply goes through
        // it rather than submitting on its own: if the send fails, the worst case
        // has to be a message sitting in Drafts rather than one that was nowhere.
        val sender = RecordingSender()

        replies(sender = sender).send(myIdentity.accountKey, "e-1", "Yes")

        assertEquals(1, sender.saved.size)
        assertEquals("draft-1", sender.submitted.single().emailId)
    }

    @Test
    fun `it is a reply, not a reply-all`() = runTest {
        // Everybody copied in on the original getting a copy of "ok, thanks"
        // that the sender never saw addressed to them is the failure this rules
        // out, and it is invisible from the phone that sent it.
        val sender = RecordingSender()

        replies(
                email = original(cc = listOf(EmailAddress("Bob", "bob@example.org"))),
                sender = sender,
            )
            .send(myIdentity.accountKey, "e-1", "ok")

        assertEquals(listOf(anna), sender.submitted.single().to)
        assertTrue(sender.submitted.single().cc.isEmpty())
    }

    // ------------------------------------------------------------------ the body

    @Test
    fun `the original is not quoted`() = runTest {
        // Deliberate, and the reason is that the shade shows no quote and offers
        // no way to take one off. Sending forty lines somebody never saw, on the
        // strength of them typing "ok", is sending something on their behalf
        // that they did not read.
        val sender = RecordingSender()

        replies(sender = sender).send(myIdentity.accountKey, "e-1", "ok")

        val body = sender.submitted.single().bodyHtml

        assertFalse(body.contains("blockquote"), body)
        assertFalse(body.contains("Can you come on Tuesday?"), body)
    }

    @Test
    fun `the signature the user set is still on it`() = runTest {
        // The opposite decision to the quote, and for a reason: a signature is a
        // standing instruction the user gave once, and a reply arriving without
        // the sign-off every other message from that address carries looks like
        // it came from somewhere else.
        val sender = RecordingSender()

        replies(sender = sender).send(myIdentity.accountKey, "e-1", "ok")

        assertTrue(sender.submitted.single().bodyHtml.contains("Sent from a phone"))
    }

    @Test
    fun `markup somebody typed goes out as text, not as markup`() = runTest {
        // "a < b" is ordinary English and would otherwise open a tag that eats
        // the rest of the message.
        val sender = RecordingSender()

        replies(sender = sender).send(myIdentity.accountKey, "e-1", "a < b & <b>not bold</b>")

        val body = sender.submitted.single().bodyHtml

        assertTrue(body.contains("a &lt; b &amp; &lt;b&gt;not bold&lt;/b&gt;"), body)
    }

    @Test
    fun `line breaks survive`() = runTest {
        val sender = RecordingSender()

        replies(sender = sender).send(myIdentity.accountKey, "e-1", "Tuesday.\r\nAnd Thursday.")

        assertTrue(
            sender.submitted.single().bodyHtml.contains("Tuesday.<br>And Thursday."),
            sender.submitted.single().bodyHtml,
        )
    }

    // ----------------------------------------------------------------- nothing typed

    @Test
    fun `an empty reply sends nothing at all`() = runTest {
        val sender = RecordingSender()

        val result = replies(sender = sender).send(myIdentity.accountKey, "e-1", "")

        assertEquals(InlineReplyResult.NothingTyped, result)
        assertTrue(sender.saved.isEmpty())
        assertTrue(sender.submitted.isEmpty())
    }

    @Test
    fun `neither does a reply that is only whitespace`() = runTest {
        // Reachable: the shade's send button is disabled on an empty field but
        // not on a field full of spaces, and a speech recogniser on Wear hands
        // over whatever it heard, which is regularly nothing.
        val sender = RecordingSender()

        val result = replies(sender = sender).send(myIdentity.accountKey, "e-1", "   \n\t  ")

        assertEquals(InlineReplyResult.NothingTyped, result)
        assertTrue(sender.saved.isEmpty())
    }

    // -------------------------------------------------------------------- failures

    @Test
    fun `a send that could not reach the server is reported, never swallowed`() = runTest {
        // The single worst outcome available to this feature is a reply that
        // disappears. Anything other than Sent has to come back as something the
        // caller can put in front of the user.
        val sender = RecordingSender(failOnSubmit = IOException("no route to host"))

        val result = replies(sender = sender).send(myIdentity.accountKey, "e-1", "ok")

        assertEquals(
            InlineReplyResult.NotSent(InlineReplyResult.Reason.OFFLINE),
            result,
        )
    }

    @Test
    fun `an unreachable server is offline, not a refusal`() = runTest {
        // The difference decides whether "try again" is offered, so it must not
        // be collapsed: a retry after a flat network works, and a retry after a
        // refusal is refused again.
        val sender = RecordingSender(failOnSave = JmapError.Unreachable("mail.example", null))

        assertEquals(
            InlineReplyResult.NotSent(InlineReplyResult.Reason.OFFLINE),
            replies(sender = sender).send(myIdentity.accountKey, "e-1", "ok"),
        )
    }

    @Test
    fun `a server that answered no is a refusal, not offline`() = runTest {
        val sender = RecordingSender(failOnSubmit = IllegalStateException("forbiddenFrom"))

        assertEquals(
            InlineReplyResult.NotSent(InlineReplyResult.Reason.REFUSED),
            replies(sender = sender).send(myIdentity.accountKey, "e-1", "ok"),
        )
    }

    @Test
    fun `being offline before the original is fetched is still offline`() = runTest {
        val sender = RecordingSender()

        val result =
            replies(fetchFailure = IOException("no route to host"), sender = sender)
                .send(myIdentity.accountKey, "e-1", "ok")

        assertEquals(InlineReplyResult.NotSent(InlineReplyResult.Reason.OFFLINE), result)
        assertTrue(sender.saved.isEmpty())
    }

    @Test
    fun `an account with no sendable address does not guess one`() = runTest {
        val sender = RecordingSender()

        val result = replies(identities = emptyList(), sender = sender).send("s/1", "e-1", "ok")

        assertEquals(InlineReplyResult.NotSent(InlineReplyResult.Reason.UNANSWERABLE), result)
        assertTrue(sender.saved.isEmpty())
    }

    @Test
    fun `a message with nobody to answer is not sent to nobody`() = runTest {
        // Replying to a message you sent yourself. Every address on it is struck
        // out as one of the user's own, and a submission with no recipients would
        // come back as a server error that reads like the reply failing rather
        // than like there being nobody to reply to.
        val sender = RecordingSender()

        val result =
            replies(email = original(from = listOf(me), to = listOf(me)), sender = sender)
                .send(myIdentity.accountKey, "e-1", "ok")

        assertEquals(InlineReplyResult.NotSent(InlineReplyResult.Reason.UNANSWERABLE), result)
        assertTrue(sender.saved.isEmpty())
    }

    @Test
    fun `a message this device can no longer read is not retryable`() = runTest {
        // The account was disconnected. Offering "try again" for this would be a
        // button that fails identically every time.
        val sender = RecordingSender()

        val result = replies(email = null, sender = sender).send(myIdentity.accountKey, "e-1", "ok")

        assertEquals(InlineReplyResult.NotSent(InlineReplyResult.Reason.UNANSWERABLE), result)
        assertTrue(sender.saved.isEmpty())
    }

    // -------------------------------------------------------------------- identity

    @Test
    fun `the reply is sent from the account the message arrived in`() = runTest {
        // Matching what the composer does when Reply is tapped in the app.
        // Answering a work mail from a private address is a mistake neither
        // surface should make on the user's behalf, and it is one the sender
        // never sees.
        val work =
            myIdentity.copy(
                accountKey = "https://mail.example/work",
                accountName = "Work",
                identityId = "id-2",
                email = "me@work.example",
            )
        val sender = RecordingSender()

        // Deliberately second in the list, so "the first identity" would be the
        // wrong answer and the test would catch it.
        repliesOver(FakeSource(listOf(myIdentity, work), original()), sender)
            .send(work.accountKey, "e-1", "ok")

        assertEquals("id-2", sender.submitted.single().identityId)
        assertEquals(work.accountKey, sender.submitted.single().accountKey)
    }
}
