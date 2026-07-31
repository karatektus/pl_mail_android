package de.plmail.feature.compose

import androidx.compose.runtime.saveable.SaverScope
import de.plmail.core.data.ComposeDraft
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

    // ------------------------------------------------------------ formatting

    @Test
    fun `the quote preview shows text rather than markup`() {
        val preview = "<p>Hello &amp; welcome</p><blockquote>a<br>b</blockquote>".strippedOfTags()

        assertEquals("Hello & welcome\n\na\nb", preview)
    }

    @Test
    fun `an attachment shows a size a person can read`() {
        assertEquals("512 B", 512L.asFileSize())
        assertEquals("2 KB", 2_048L.asFileSize())
        assertEquals("1.5 MB", (1_536L * 1_024).asFileSize())
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
