package de.plmail.core.data

import de.plmail.jmap.mail.EmailAddress
import de.plmail.jmap.mail.Signatures
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What counts as a message somebody has written.
 *
 * The whole question is "may this be thrown away", asked when a composer closes, and both wrong
 * answers are bad in ways nobody reports as a bug. Saying yes to a real draft loses somebody's
 * writing. Saying no to an empty one fills Drafts with messages the user never wrote — which is the
 * direction that got worse the moment composers started opening with a signature already in them.
 */
class ComposeDraftEmptinessTest {

    private val signature = "<div class=\"pl-signature\" data-pl-signature><p>Ada</p></div>"

    private fun draft(
        bodyHtml: String = "",
        subject: String = "",
        to: List<EmailAddress> = emptyList(),
    ) =
        ComposeDraft(
            accountKey = "https://nas.local/13",
            identityId = "1",
            to = to,
            subject = subject,
            bodyHtml = bodyHtml,
        )

    @Test
    fun `a composer nobody typed into is empty`() {
        assertTrue(draft().isEmpty(quotedHtml = ""))
    }

    /** A rich-text editor's idea of an empty field is not an empty string. */
    @Test
    fun `the editor's own empty markup is still empty`() {
        assertTrue(draft(bodyHtml = "<p><br></p>").isEmpty(quotedHtml = ""))
    }

    /**
     * **The case the signature feature would have broken.**
     *
     * Every composer now opens with the sending address's sign-off in the body. Without the
     * signature being subtracted here, opening the composer and changing your mind would leave a
     * draft behind on the server containing nothing but your own name — once per open.
     */
    @Test
    fun `a body that is only a signature is still nothing to save`() {
        assertTrue(draft(bodyHtml = signature).isEmpty(quotedHtml = ""))
    }

    @Test
    fun `a signature with the editor's empty paragraph above it is still nothing to save`() {
        assertTrue(draft(bodyHtml = "<p><br></p>$signature").isEmpty(quotedHtml = ""))
    }

    /** And a reply, which carries a quote and a signature from the very first frame. */
    @Test
    fun `a reply nobody has answered yet is nothing to save`() {
        val quote = "<blockquote><p>The engine works.</p></blockquote>"

        assertTrue(draft(bodyHtml = signature + quote).isEmpty(quotedHtml = quote))
    }

    // --- and the other direction, which loses somebody's writing ---------------

    @Test
    fun `one typed sentence above the signature is worth saving`() {
        assertFalse(draft(bodyHtml = "<p>Dear Grace,</p>$signature").isEmpty(quotedHtml = ""))
    }

    @Test
    fun `a subject alone is worth saving`() {
        assertFalse(draft(bodyHtml = signature, subject = "The figures").isEmpty(quotedHtml = ""))
    }

    @Test
    fun `a recipient alone is worth saving`() {
        val addressed = draft(bodyHtml = signature, to = listOf(EmailAddress(email = "a@b.test")))

        assertFalse(addressed.isEmpty(quotedHtml = ""))
    }

    /**
     * A signature that itself contains `div`s, which is what a contact block is.
     *
     * Pinned here as well as in `SignaturesTest` because this is where getting it wrong is
     * expensive: a half-subtracted signature leaves markup behind, reads as content, and saves a
     * draft.
     */
    @Test
    fun `a signature containing divs is subtracted whole`() {
        val nested = Signatures.block("<div><div>Ada</div><div>Analytical Engines Ltd</div></div>")

        assertTrue(draft(bodyHtml = nested).isEmpty(quotedHtml = ""))
    }
}
