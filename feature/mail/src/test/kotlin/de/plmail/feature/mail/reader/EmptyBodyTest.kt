package de.plmail.feature.mail.reader

import de.plmail.core.database.EmailEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "No body" and "no body *yet*" are two different messages, and the reader has to tell them apart.
 *
 * The distinction survives only in these two fields. An unfetched message has no row in
 * `email_bodies`, so both are null; a fetched one that genuinely has nothing has a row holding the
 * empty string. Collapsing them — which is what returning `<pre></pre>` for the second did — draws
 * an empty card, and an empty card reads as a broken renderer rather than as a fact about the mail.
 */
class EmptyBodyTest {

    @Test
    fun `an unfetched message has no body and says it was never fetched`() {
        val message = message(html = null, text = null)

        assertNull(message.body)
        assertFalse(message.isBodyFetched, "no row in the body table means nothing was asked for")
    }

    /** The marker row the cache writes for mail that genuinely carries no text. */
    @Test
    fun `a message fetched with nothing in it is fetched, and still has no body`() {
        val message = message(html = null, text = "")

        assertNull(message.body, "an empty body is not a document to render")
        assertTrue(message.isBodyFetched, "the empty string is an answer, not the absence of one")
    }

    /**
     * Whitespace counts as nothing too.
     *
     * A `<pre> </pre>` renders a blank card just as convincingly as an empty one, and a server that
     * stores a newline for an empty part is not doing anything unusual.
     */
    @Test
    fun `a body of whitespace is nothing to render`() {
        assertNull(message(html = "   \n ", text = null).body)
        assertNull(message(html = null, text = "\n\n").body)
    }

    @Test
    fun `text is rendered preformatted and html is rendered as it is`() {
        assertEquals("<pre>Hello.</pre>", message(html = null, text = "Hello.").body)
        assertEquals("<p>Hello.</p>", message(html = "<p>Hello.</p>", text = "Hello.").body)
    }

    private fun message(html: String?, text: String?): ReaderMessage =
        ReaderMessage(
            email = EmailEntity(uid = "u1", accountKey = "a1", emailId = "1"),
            html = html,
            text = text,
            isExpanded = true,
        )
}
