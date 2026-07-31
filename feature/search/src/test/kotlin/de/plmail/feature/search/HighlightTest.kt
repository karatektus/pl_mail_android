package de.plmail.feature.search

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Snippet parsing, including the case that would be an injection if it were done the obvious way.
 *
 * The server sends HTML: `<mark>` around hits, everything else escaped. Unescaping before splitting
 * — the natural order to write — lets a subject containing the literal text `<mark>` produce a real
 * highlight, so the order here is asserted rather than assumed.
 */
class HighlightTest {

    private val bold = SpanStyle(fontWeight = FontWeight.Bold)

    @Test
    fun `marked runs are styled and the rest is not`() {
        val rendered = Highlight.render("Q3 <mark>report</mark> attached", bold)

        assertEquals("Q3 report attached", rendered.text)

        val styled = rendered.spanStyles.single()
        assertEquals("report", rendered.text.substring(styled.start, styled.end))
    }

    @Test
    fun `entities are resolved, so the reader never sees an escape`() {
        val rendered = Highlight.render("Ann &amp; co &lt;3 &quot;hi&quot; it&#039;s", bold)

        assertEquals("""Ann & co <3 "hi" it's""", rendered.text)
    }

    /**
     * The injection case.
     *
     * A subject that literally contains `<mark>` arrives escaped as `&lt;mark&gt;`. Split first and
     * it stays text; unescape first and it becomes a tag this parser would then honour, letting a
     * sender decide what appears highlighted in someone else's search results.
     */
    @Test
    fun `an escaped mark tag stays text and highlights nothing`() {
        val rendered = Highlight.render("subject with &lt;mark&gt;fake&lt;/mark&gt; in it", bold)

        assertEquals("subject with <mark>fake</mark> in it", rendered.text)
        assertTrue(rendered.spanStyles.isEmpty(), "the sender does not get to choose the highlight")
    }

    @Test
    fun `an ampersand entity is resolved last, so escaped entities survive`() {
        // htmlspecialchars turns a literal "&lt;" into "&amp;lt;". Resolving
        // &amp; first would yield "&lt;" and then "<" -- the character the user
        // had deliberately written out.
        assertEquals("&lt;", Highlight.render("&amp;lt;", bold).text)
    }

    @Test
    fun `several hits are all styled`() {
        val rendered = Highlight.render("<mark>a</mark> and <mark>b</mark>", bold)

        assertEquals("a and b", rendered.text)
        assertEquals(2, rendered.spanStyles.size)
    }

    /**
     * A truncated snippet must not lose its tail.
     *
     * `ts_headline` fragments text, so an unterminated tag is a plausible shape rather than a
     * corrupt one, and dropping what follows would silently shorten a result.
     */
    @Test
    fun `an unterminated mark keeps the rest of the text`() {
        val rendered = Highlight.render("start <mark>rest of it", bold)

        assertEquals("start rest of it", rendered.text)
        assertEquals(1, rendered.spanStyles.size)
    }

    @Test
    fun `text with no marks renders as itself`() {
        // The stopword case: the search matched, nothing is highlightable.
        val rendered = Highlight.render("nothing to point at", bold)

        assertEquals("nothing to point at", rendered.text)
        assertTrue(rendered.spanStyles.isEmpty())
    }

    @Test
    fun `hasMark distinguishes a highlight from mere text`() {
        assertTrue(Highlight.hasMark("a <mark>b</mark>"))
        assertFalse(Highlight.hasMark("a b"))
        assertFalse(Highlight.hasMark(null))
    }
}
