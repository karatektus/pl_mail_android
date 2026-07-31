package de.plmail.jmap.mail

import de.plmail.jmap.protocol.EmailId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The failures a composer cannot see.
 *
 * Every case here produces a draft that looks perfectly correct on screen and is wrong in the
 * recipient's client: a reply that starts a new conversation, a reply-all that mails the sender
 * their own words back, a subject that grows a prefix every round. None of them is reported by the
 * server, because none of them is invalid.
 */
class DraftComposerTest {

    private val me = setOf("me@plmail.test")

    private fun email(
        from: List<EmailAddress> = listOf(EmailAddress("Anna", "anna@example.org")),
        to: List<EmailAddress> = listOf(EmailAddress("Me", "me@plmail.test")),
        cc: List<EmailAddress> = emptyList(),
        replyTo: List<EmailAddress> = emptyList(),
        subject: String? = "Roof repairs",
        messageId: List<String>? = listOf("m3@example.org"),
        references: List<String>? = listOf("m1@example.org", "m2@example.org"),
        html: String? = "<p>Original</p>",
        text: String? = null,
    ): Email =
        Email(
            id = EmailId("1"),
            from = from,
            to = to,
            cc = cc,
            replyTo = replyTo,
            subject = subject,
            messageId = messageId,
            references = references,
            htmlBody = if (html == null) emptyList() else listOf(EmailBodyPart(partId = "html")),
            textBody = if (text == null) emptyList() else listOf(EmailBodyPart(partId = "text")),
            bodyValues =
                buildMap {
                    html?.let { put("html", EmailBodyValue(it)) }
                    text?.let { put("text", EmailBodyValue(it)) }
                },
        )

    // ---------------------------------------------------------------- threading

    @Test
    fun `a reply threads against the message it answers`() {
        // The single most visible way a compose screen can be subtly wrong: omit
        // these and the reply arrives as a brand-new conversation.
        val draft = DraftComposer.reply(email(), DraftComposer.ReplyMode.REPLY, me, "Anna wrote:")

        assertEquals(listOf("m3@example.org"), draft.inReplyTo)
        assertEquals(
            listOf("m1@example.org", "m2@example.org", "m3@example.org"),
            draft.references,
        )
    }

    @Test
    fun `References ends with the message being answered, never before it`() {
        // Order is the header's meaning. A chain whose last entry is not the
        // parent tells the receiving client to file the reply somewhere else in
        // the tree, which looks like the thread rearranging itself.
        val draft = DraftComposer.reply(email(), DraftComposer.ReplyMode.REPLY, me, "")

        assertEquals("m3@example.org", draft.references?.last())
    }

    @Test
    fun `a message with no Message-ID cannot be threaded against`() {
        // Replying to an unsent draft. Inventing an id here would thread the
        // reply against a message that will never exist.
        val draft =
            DraftComposer.reply(
                email(messageId = null, references = null),
                DraftComposer.ReplyMode.REPLY,
                me,
                "",
            )

        assertNull(draft.inReplyTo)
        assertNull(draft.references)
    }

    @Test
    fun `angle brackets are stripped from a Message-ID`() {
        // The server emits bare ids, but a message synced from elsewhere can
        // carry the header form. Sending "<x@y>" back produces "<<x@y>>".
        val draft =
            DraftComposer.reply(
                email(messageId = listOf("<m9@example.org>"), references = null),
                DraftComposer.ReplyMode.REPLY,
                me,
                "",
            )

        assertEquals(listOf("m9@example.org"), draft.inReplyTo)
    }

    @Test
    fun `a long chain keeps its root and its most recent entries`() {
        // Trimming from the end would orphan the reply from the conversation.
        // The first id is what threading algorithms use to find the root.
        val long = (1..40).map { "m$it@example.org" }
        val draft =
            DraftComposer.reply(
                email(references = long, messageId = listOf("m41@example.org")),
                DraftComposer.ReplyMode.REPLY,
                me,
                "",
            )

        val references = draft.references.orEmpty()

        assertEquals(20, references.size)
        assertEquals("m1@example.org", references.first())
        assertEquals("m41@example.org", references.last())
    }

    @Test
    fun `a forward carries no reply headers`() {
        // Carrying In-Reply-To into a forward files it inside the thread it was
        // taken out of, in the recipient's mailbox, where the sender never sees
        // what happened.
        val draft = DraftComposer.forward(email(), labels(), "1 May 2026")

        assertNull(draft.inReplyTo)
        assertNull(draft.references)
        assertTrue(draft.to.isEmpty())
    }

    // ---------------------------------------------------------------- recipients

    @Test
    fun `a plain reply answers the sender alone`() {
        val original =
            email(
                to = listOf(EmailAddress("Me", "me@plmail.test"), EmailAddress(null, "bob@x.test")),
                cc = listOf(EmailAddress(null, "carol@x.test")),
            )

        val draft = DraftComposer.reply(original, DraftComposer.ReplyMode.REPLY, me, "")

        assertEquals(listOf("anna@example.org"), draft.to.map { it.email })
        assertTrue(draft.cc.isEmpty())
    }

    @Test
    fun `reply-all never copies the user in on their own reply`() {
        val original =
            email(
                to = listOf(EmailAddress("Me", "me@plmail.test"), EmailAddress(null, "bob@x.test")),
                cc = listOf(EmailAddress(null, "carol@x.test")),
            )

        val draft = DraftComposer.reply(original, DraftComposer.ReplyMode.REPLY_ALL, me, "")

        assertEquals(listOf("anna@example.org"), draft.to.map { it.email })
        assertEquals(listOf("bob@x.test", "carol@x.test"), draft.cc.map { it.email })
        assertFalse(draft.cc.any { it.email == "me@plmail.test" })
    }

    @Test
    fun `reply-all matches an address whatever its case`() {
        // "Me@PlMail.test" and "me@plmail.test" are one mailbox. Comparing
        // literally puts the user on their own reply, which is the report every
        // client eventually gets.
        val original =
            email(to = listOf(EmailAddress("Me", "Me@PlMail.TEST"), EmailAddress(null, "b@x.test")))

        val draft = DraftComposer.reply(original, DraftComposer.ReplyMode.REPLY_ALL, me, "")

        assertEquals(listOf("b@x.test"), draft.cc.map { it.email })
    }

    @Test
    fun `nobody appears in both To and Cc`() {
        // The sender was also on the Cc line. Listing them twice sends two
        // copies and looks like a bug in the sender's client.
        val original =
            email(
                from = listOf(EmailAddress("Anna", "anna@example.org")),
                cc = listOf(EmailAddress(null, "anna@example.org")),
            )

        val draft = DraftComposer.reply(original, DraftComposer.ReplyMode.REPLY_ALL, me, "")

        assertEquals(listOf("anna@example.org"), draft.to.map { it.email })
        assertTrue(draft.cc.isEmpty())
    }

    @Test
    fun `Reply-To wins over From when the sender set one`() {
        // That is the whole purpose of the header, and mailing lists depend on
        // it: answering From instead mails the list's software, not the list.
        val original =
            email(
                from = listOf(EmailAddress("List", "bounce@list.test")),
                replyTo = listOf(EmailAddress("List", "talk@list.test")),
            )

        val draft = DraftComposer.reply(original, DraftComposer.ReplyMode.REPLY, me, "")

        assertEquals(listOf("talk@list.test"), draft.to.map { it.email })
    }

    // ---------------------------------------------------------------- subjects

    @Test
    fun `a reply prefix is added exactly once`() {
        assertEquals("Re: Roof repairs", DraftComposer.replySubject("Roof repairs"))
        assertEquals("Re: Roof repairs", DraftComposer.replySubject("Re: Roof repairs"))
        assertEquals("Re: Roof repairs", DraftComposer.replySubject("RE: re: Roof repairs"))
    }

    @Test
    fun `German prefixes are recognised, because this app ships in German`() {
        // A thread with a German Outlook in it otherwise becomes
        // "Re: AW: Re: AW: …" and the subject outgrows the window.
        assertEquals("Re: Dachreparatur", DraftComposer.replySubject("AW: Dachreparatur"))
        assertEquals("Re: Dachreparatur", DraftComposer.replySubject("AW: Re: Dachreparatur"))
        assertEquals("Fwd: Dachreparatur", DraftComposer.forwardSubject("WG: Dachreparatur"))
    }

    @Test
    fun `replying to a forward replaces the prefix rather than stacking it`() {
        assertEquals("Re: Roof repairs", DraftComposer.replySubject("Fwd: Roof repairs"))
        assertEquals("Fwd: Roof repairs", DraftComposer.forwardSubject("Re: Roof repairs"))
    }

    @Test
    fun `Outlook's numbered prefix is stripped too`() {
        assertEquals(
            "Roof repairs",
            DraftComposer.replySubject("Re[2]: Roof repairs").removePrefix("Re: "),
        )
    }

    @Test
    fun `an empty subject still gets a prefix`() {
        // "(no subject)" is a rendering fallback, never a subject to send.
        assertEquals("Re:", DraftComposer.replySubject(null))
        assertEquals("Re:", DraftComposer.replySubject("   "))
    }

    // ---------------------------------------------------------------- quoting

    @Test
    fun `a plain-text original is escaped before it is quoted`() {
        // The composer renders this string locally, long before the server has
        // sanitised anything. A message whose text contains markup would
        // otherwise start executing inside the reply being written.
        val draft =
            DraftComposer.reply(
                email(html = null, text = "1 < 2 & <script>alert(1)</script>"),
                DraftComposer.ReplyMode.REPLY,
                me,
                "",
            )

        assertTrue(draft.quotedHtml.contains("&lt;script&gt;"))
        assertFalse(draft.quotedHtml.contains("<script>"))
    }

    @Test
    fun `the attribution line is escaped as well`() {
        // It is built from a display name, which is attacker-controlled text
        // that arrived in a header.
        val draft =
            DraftComposer.reply(
                email(),
                DraftComposer.ReplyMode.REPLY,
                me,
                "On 1 May, <img src=x onerror=alert(1)> wrote:",
            )

        assertFalse(draft.quotedHtml.contains("<img"))
        assertTrue(draft.quotedHtml.contains("&lt;img"))
    }

    @Test
    fun `an HTML original is quoted as it stands`() {
        // Already sanitised by the server on the way in, and re-escaping it
        // would show the recipient the source of their own message.
        val draft = DraftComposer.reply(email(), DraftComposer.ReplyMode.REPLY, me, "")

        assertTrue(draft.quotedHtml.contains("<blockquote"))
        assertTrue(draft.quotedHtml.contains("<p>Original</p>"))
    }

    @Test
    fun `a forward names the original's headers`() {
        val original =
            email(cc = listOf(EmailAddress("Carol", "carol@x.test")), subject = "Roof repairs")

        val block = DraftComposer.forward(original, labels(), "1 May 2026").quotedHtml

        assertTrue(block.contains("Anna &lt;anna@example.org&gt;"))
        assertTrue(block.contains("1 May 2026"))
        assertTrue(block.contains("Roof repairs"))
        assertTrue(block.contains("Carol &lt;carol@x.test&gt;"))
    }

    private fun labels() =
        DraftComposer.ForwardLabels(
            heading = "Forwarded message",
            from = "From",
            date = "Date",
            subject = "Subject",
            to = "To",
            cc = "Cc",
        )
}
