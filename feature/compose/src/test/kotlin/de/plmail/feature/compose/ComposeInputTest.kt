package de.plmail.feature.compose

import androidx.compose.runtime.saveable.SaverScope
import de.plmail.core.data.ComposeDraft
import de.plmail.jmap.mail.EmailAddress
import java.text.DecimalFormatSymbols
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The composer's quiet failures.
 *
 * Address parsing is the one worth the most: an address the field silently declines to turn into a
 * chip is an address the message does not go to, and nothing on screen says so. The rest guard
 * things that only show up after the fact — a composer that reopens empty after a rotation, a quote
 * appended twice, a send that goes out with the body of the previous keystroke.
 */
class ComposeInputTest {

    // ------------------------------------------------------------- addresses

    @Test
    fun `a bare address becomes a recipient`() {
        val parsed = "anna@example.org".parseAddresses()

        assertEquals(listOf("anna@example.org"), parsed.map { it.email })
        assertNull(parsed.single().name)
    }

    @Test
    fun `the Name angle-address form is what pasting from another client gives you`() {
        val parsed = "Anna Meyer <anna@example.org>".parseAddresses()

        assertEquals("Anna Meyer", parsed.single().name)
        assertEquals("anna@example.org", parsed.single().email)
    }

    @Test
    fun `a quoted display name loses its quotes`() {
        val parsed = "\"Meyer, Anna\" <anna@example.org>".parseAddresses()

        assertEquals("Meyer, Anna", parsed.single().name)
    }

    @Test
    fun `a comma inside a quoted name does not split the address`() {
        // "Meyer, Anna" <a@x> is how half of Europe's corporate directories
        // spell a name. Splitting on the comma leaves "Anna" <a@x> and a
        // fragment with no @, which is dropped -- so the recipient vanishes.
        val parsed = "\"Meyer, Anna\" <anna@example.org>, bob@x.test".parseAddresses()

        assertEquals(2, parsed.size)
        assertEquals("Meyer, Anna", parsed.first().name)
        assertEquals("bob@x.test", parsed.last().email)
    }

    @Test
    fun `a pasted list becomes several recipients`() {
        // Landing as one unusable string is the common failure; the address line
        // then holds something that is not an address and the send is rejected
        // minutes later by the mail server.
        val parsed = "a@x.test, b@y.test; c@z.test".parseAddresses()

        assertEquals(listOf("a@x.test", "b@y.test", "c@z.test"), parsed.map { it.email })
    }

    @Test
    fun `something that is not an address is dropped rather than sent`() {
        // The server would reject it, but only after the composer has closed --
        // at which point the failure names a submission the user has stopped
        // thinking about.
        assertTrue("anna".parseAddresses().isEmpty())
        assertTrue("@example.org".parseAddresses().isEmpty())
        assertTrue("anna@".parseAddresses().isEmpty())
        assertTrue("a@b@c".parseAddresses().isEmpty())
    }

    @Test
    fun `whitespace around a pasted address is not part of it`() {
        assertEquals("a@x.test", "  a@x.test  ".parseAddresses().single().email)
    }

    @Test
    fun `a shared entry with a space in it is refused, not split into a stranger`() {
        // Share intents and mailto: links are written by programs, one entry per
        // comma. `anna lists@example.org` is a broken `anna+lists@…`; splitting it
        // on the space would write to lists@example.org, somebody else. This used
        // to be accepted whole, as one address with a space in it.
        assertTrue("anna lists@example.org".parseAddresses().isEmpty())
    }

    @Test
    fun `mailto in front of an address is not part of it`() {
        // Accepted before as the address `mailto:jo@jo.fr`, which no server takes.
        assertEquals(listOf("jo@jo.fr"), "mailto:jo@jo.fr".parseAddresses().map { it.email })
        assertEquals(listOf("jo@jo.fr"), "<MAILTO:jo@jo.fr>".parseAddresses().map { it.email })
    }

    // ------------------------------------------------- pasting into a line

    /** What pasting [text] into an empty line holding [existing] does. */
    private fun paste(text: String, existing: List<EmailAddress> = emptyList()) =
        recipientEdit(previous = "", value = text, existing = existing)

    private fun RecipientEdit.chipped(): List<String?> =
        assertIs<RecipientEdit.Commit>(this).added.map { it.email }

    @Test
    fun `addresses separated by spaces are one recipient each`() {
        // The web composer's own example (pl_mail#379). Before, the whole line
        // was one entry with two @ in it, refused without a word.
        assertEquals(listOf("jo@jo.fr", "jo2@jo.fr"), paste("jo@jo.fr jo2@jo.fr").chipped())
    }

    @Test
    fun `a tab separates as a space does`() {
        assertEquals(listOf("a@x.test", "b@y.test"), paste("a@x.test\tb@y.test").chipped())
    }

    @Test
    fun `a name with a space in it before an address stays one recipient`() {
        // Did not match `^(.*?)<([^>]+)>$` as one entry, so both were dropped.
        val edit = assertIs<RecipientEdit.Commit>(paste("Jean Dupont <jd@x.fr> jo@jo.fr"))

        assertEquals(listOf("jd@x.fr", "jo@jo.fr"), edit.added.map { it.email })
        assertEquals(listOf("Jean Dupont", null), edit.added.map { it.name })
    }

    @Test
    fun `an address before a spaced name is not swallowed into the name`() {
        // Gave one recipient, jd@x.fr, named "jo@jo.fr Jean Dupont": jo@jo.fr was
        // never written to. A name has no @ outside quotes, which is what stops it.
        val edit = assertIs<RecipientEdit.Commit>(paste("jo@jo.fr Jean Dupont <jd@x.fr>"))

        assertEquals(listOf("jo@jo.fr", "jd@x.fr"), edit.added.map { it.email })
        assertEquals(listOf(null, "Jean Dupont"), edit.added.map { it.name })
    }

    @Test
    fun `semicolons, commas, spaces and new lines mix`() {
        assertEquals(
            listOf("a@x.test", "b@y.test", "c@z.test", "d@w.test", "e@v.test"),
            paste("a@x.test; b@y.test,c@z.test d@w.test\ne@v.test").chipped(),
        )
    }

    @Test
    fun `a quoted name keeps its comma among other addresses`() {
        val edit = assertIs<RecipientEdit.Commit>(paste("\"Doe, Jane\" <jane@doe.fr>, jo@jo.fr"))

        assertEquals(listOf("jane@doe.fr", "jo@jo.fr"), edit.added.map { it.email })
        assertEquals("Doe, Jane", edit.added.first().name)
    }

    @Test
    fun `mailto is dropped from pasted addresses`() {
        assertEquals(
            listOf("jo@jo.fr", "ann@x.test"),
            paste("mailto:jo@jo.fr <mailto:ann@x.test>").chipped(),
        )
    }

    @Test
    fun `an address already on the line, in any case, is not added again`() {
        val edit =
            paste(
                "JO@jo.fr b@y.test B@Y.test",
                existing = listOf(EmailAddress(name = "Jo", email = "jo@jo.fr")),
            )

        assertEquals(listOf("b@y.test"), edit.chipped())
    }

    @Test
    fun `a piece that is not an address stays in the field and is reported`() {
        // `bob` used to vanish without a word, and the mail went without him.
        val edit = assertIs<RecipientEdit.Commit>(paste("a@x.test, bob, c@y.test"))

        assertEquals(listOf("a@x.test", "c@y.test"), edit.added.map { it.email })
        assertEquals("bob", edit.remaining)
        assertEquals(listOf("bob"), edit.rejected)
    }

    @Test
    fun `an address in brackets is not taken with its brackets`() {
        // It used to chip "(jane@doe.fr)", which failed only at submission.
        val edit = assertIs<RecipientEdit.Commit>(paste("Jane Doe (jane@doe.fr), a@x.test"))

        assertEquals(listOf("a@x.test"), edit.chipped())
        assertEquals(listOf("Jane", "Doe", "(jane@doe.fr)"), edit.rejected)
    }

    @Test
    fun `the full stop of a sentence is not part of the address`() {
        val edit = assertIs<RecipientEdit.Commit>(paste("a@x.test jo@x.fr."))

        assertEquals(listOf("a@x.test"), edit.chipped())
        assertEquals(listOf("jo@x.fr."), edit.rejected)
    }

    @Test
    fun `a pasted name with no address in it is a search, not a mistake`() {
        assertEquals(RecipientEdit.Typing("Jean Dupont"), paste("Jean Dupont"))
    }

    @Test
    fun `a single pasted address stays text, as before, until the line is left`() {
        // So the suggestions still open on it.
        assertEquals(RecipientEdit.Typing("jo@jo.fr"), paste("jo@jo.fr"))
        // With a separator after it, it was a chip before and still is.
        assertEquals(listOf("jo@jo.fr"), paste("jo@jo.fr ").chipped())
    }

    // ------------------------------------------------- typing into a line

    @Test
    fun `a space typed after a first name keeps the space`() {
        // `commit("Jean ")` found no address and returned without updating the
        // field, so the space was lost and "Jean Dupont" could not be searched.
        assertEquals(
            RecipientEdit.Typing("Jean "),
            recipientEdit(previous = "Jean", value = "Jean ", existing = emptyList()),
        )
    }

    @Test
    fun `a typed character inside an address does not commit it`() {
        // `jo@x` is already an address; chipping it would cut `jo@x.fr` short.
        assertEquals(
            RecipientEdit.Typing("jo@x."),
            recipientEdit(previous = "jo@x", value = "jo@x.", existing = emptyList()),
        )
    }

    @Test
    fun `a typed separator commits what comes before it`() {
        assertEquals(
            listOf("jo@x.fr"),
            recipientEdit(previous = "jo@x.fr", value = "jo@x.fr,", existing = emptyList())
                .chipped(),
        )
    }

    @Test
    fun `a space inside an angle-address being typed does not commit`() {
        assertEquals(
            RecipientEdit.Typing("Jean <jd@x.fr "),
            recipientEdit(
                previous = "Jean <jd@x.fr",
                value = "Jean <jd@x.fr ",
                existing = emptyList(),
            ),
        )
    }

    @Test
    fun `deleting never commits`() {
        assertEquals(
            RecipientEdit.Typing("jo@x.fr "),
            recipientEdit(previous = "jo@x.fr b", value = "jo@x.fr ", existing = emptyList()),
        )
    }

    @Test
    fun `Enter on a name with no address leaves it exactly as typed`() {
        // Re-joining its pieces would have turned it into "Jean; Dupont".
        assertEquals(
            RecipientEdit.Typing("Jean Dupont"),
            "Jean Dupont".commitRecipients(emptyList()),
        )
    }

    // ----------------------------------------------------------- the quote

    @Test
    fun `the quote is appended to the body, never left out`() {
        // The quote lives outside the editor, so it exists only in the state and
        // has to be joined back on at exactly one place. Forgetting it sends a
        // reply with no context, which reads as rudeness rather than a bug.
        val draft = ComposeDraft(accountKey = "s/1", identityId = "1", bodyHtml = "<p>Yes</p>")

        assertEquals(
            "<p>Yes</p><blockquote>old</blockquote>",
            draft.withQuote("<blockquote>old</blockquote>").bodyHtml,
        )
    }

    @Test
    fun `a removed quote adds nothing`() {
        val draft = ComposeDraft(accountKey = "s/1", identityId = "1", bodyHtml = "<p>Yes</p>")

        assertEquals("<p>Yes</p>", draft.withQuote("").bodyHtml)
    }

    @Test
    fun `a reply that has only its quote counts as empty`() {
        // Otherwise opening a reply, thinking better of it and going back leaves
        // a draft in the list for every message anyone ever glanced at.
        val quote = "<blockquote>old</blockquote>"
        val draft =
            ComposeDraft(
                accountKey = "s/1",
                identityId = "1",
                to = emptyList(),
                bodyHtml = quote,
            )

        assertTrue(draft.isEmpty(quote))
        assertTrue(draft.copy(bodyHtml = "<p></p>$quote").isEmpty(quote))
        assertTrue(!draft.copy(bodyHtml = "<p>Yes</p>$quote").isEmpty(quote))
    }

    // ------------------------------------------------------- the signature

    /**
     * The bug in #3: the sign-off was put into a body the editor then overwrote, so the phone sent
     * unsigned mail. It is held beside the draft now, and this is where it goes back on.
     */
    @Test
    fun `the signature goes under what was typed and above the quote`() {
        val draft = ComposeDraft(accountKey = "s/1", identityId = "1", bodyHtml = "<p>Yes</p>")

        val sent = draft.outgoing("<p>Anna</p>", "<blockquote>old</blockquote>").bodyHtml

        assertEquals(
            "<p>Yes</p><p><br></p>" +
                "<div class=\"pl-signature\" data-pl-signature><p>Anna</p></div>" +
                "<blockquote>old</blockquote>",
            sent,
        )
    }

    @Test
    fun `a removed signature adds nothing`() {
        val draft = ComposeDraft(accountKey = "s/1", identityId = "1", bodyHtml = "<p>Yes</p>")

        assertEquals("<p>Yes</p>", draft.outgoing("", "").bodyHtml)
    }

    @Test
    fun `a body that still carries a signature block is not signed twice`() {
        val signed = "<p>Yes</p><div class=\"pl-signature\" data-pl-signature><p>Old</p></div>"
        val draft = ComposeDraft(accountKey = "s/1", identityId = "1", bodyHtml = signed)

        val sent = draft.outgoing("<p>Anna</p>", "").bodyHtml

        assertEquals(1, Regex("data-pl-signature").findAll(sent).count(), sent)
        assertTrue(sent.contains("<p>Anna</p>") && !sent.contains("<p>Old</p>"), sent)
    }

    // ------------------------------------------------------------ the saver

    @Test
    fun `an open composer survives a rotation`() {
        // The request is the only thing tying the screen to what it was opened
        // for. Losing it means a rotation mid-reply reopens a blank message,
        // with the reply headers gone and the thread broken if it is then sent.
        listOf(
                ComposeRequest.New,
                ComposeRequest.Reply("server/1", "42", all = true),
                ComposeRequest.Reply("server/1", "42", all = false),
                ComposeRequest.Forward("server/1", "42"),
                ComposeRequest.Edit("server/1", "42"),
            )
            .forEach { request -> assertEquals(request, request.roundTrip()) }
    }

    @Test
    fun `nothing open restores as nothing open`() {
        assertNull(null.roundTrip())
    }

    @Test
    fun `reply-all survives as reply-all`() {
        // Restoring it as a plain reply would drop everyone who was copied in,
        // silently, at the moment the screen redraws.
        val restored = ComposeRequest.Reply("server/1", "42", all = true).roundTrip()

        assertIs<ComposeRequest.Reply>(restored)
        assertTrue(restored.all)
    }

    @Test
    fun `a share survives being saved and restored whole`() {
        // The one request whose restore has real consequences. Everything else
        // in this saver is an id the server can be asked about again; a share
        // exists only here and in the files it points at, so a field lost in the
        // bundle is a field nobody can get back. Process death mid-share is the
        // ordinary case, not the rare one: the share sheet has just handed the
        // foreground to an app that was not running a second ago.
        val request =
            ComposeRequest.Share(
                to = listOf("anna@example.org", "bruno@example.org"),
                cc = listOf("carla@example.net"),
                bcc = listOf("dana@example.net"),
                subject = "Photos",
                text = "Here they are",
                attachments = listOf("/data/cache/shared/one", "/data/cache/shared/two"),
                tooLarge = listOf("holiday.mp4"),
                unreadable = listOf("locked.pdf"),
            )

        assertEquals(request, request.roundTrip())
    }

    @Test
    fun `an empty share restores as an empty share rather than as nothing`() {
        assertEquals(ComposeRequest.Share(), ComposeRequest.Share().roundTrip())
    }

    // ------------------------------------------------------------ formatting

    @Test
    fun `the quote preview shows text rather than markup`() {
        val preview = "<p>Hello &amp; welcome</p><blockquote>a<br>b</blockquote>".strippedOfTags()

        assertEquals("Hello & welcome\na\nb", preview)
    }

    @Test
    fun `an attachment shows a size a person can read`() {
        assertEquals("512 B", 512L.asFileSize())
        assertEquals("2 KB", 2_048L.asFileSize())
        // The decimal mark is the reader's: "1.5 MB" in English and "1,5 MB"
        // in German, and the suite runs on machines set to either.
        val mark = DecimalFormatSymbols.getInstance().decimalSeparator

        assertEquals("1${mark}5 MB", (1_536L * 1_024).asFileSize())
    }

    @Test
    fun `an offset date parses as well as a Z one`() {
        // A server behind a proxy that rewrites dates hands back the offset
        // form. Dropping it leaves the reply's attribution line with no date.
        assertEquals(
            "2026-07-31T16:55:43Z".toInstantOrNull(),
            "2026-07-31T18:55:43+02:00".toInstantOrNull(),
        )
    }

    private fun ComposeRequest?.roundTrip(): ComposeRequest? {
        val scope = SaverScope { true }

        return with(ComposeRequestSaver) { scope.save(this@roundTrip) }
            ?.let(ComposeRequestSaver::restore)
    }
}
