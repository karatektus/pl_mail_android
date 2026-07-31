package de.plmail.feature.search

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/**
 * A server snippet, turned into text Compose can draw.
 *
 * `SearchSnippet/get` returns HTML: `<mark>` around each hit and **everything else escaped**. That
 * escaping is the reason this is a parser rather than a `replace`. A subject reading `Q3
 * <b>final</b> — Ann & co` arrives as `Q3 &lt;b&gt;final&lt;/b&gt; — Ann &amp; co`, and text
 * rendered without unescaping shows the entities to the user; text rendered by stripping tags
 * without unescaping *first* would let a crafted subject inject its own `<mark>`.
 *
 * So the order is fixed and load-bearing: split on the marker tags, then unescape each piece. Never
 * the other way round.
 */
object Highlight {

    /**
     * Renders [html] with [style] applied to the marked runs.
     *
     * Anything that is not a `<mark>` pair is left as literal text, entities resolved. A snippet
     * with no marks is still perfectly good text — that is the stopword case, where the search
     * matched but there is nothing to point at.
     */
    fun render(html: String, style: SpanStyle): AnnotatedString = buildAnnotatedString {
        var index = 0

        while (index < html.length) {
            val open = html.indexOf(OPEN, index)

            if (open < 0) {
                append(unescape(html.substring(index)))
                break
            }

            append(unescape(html.substring(index, open)))

            val contentStart = open + OPEN.length
            val close = html.indexOf(CLOSE, contentStart)

            if (close < 0) {
                // An unterminated mark: the rest is still the message, and
                // dropping it would silently truncate a result. Highlight it and
                // stop.
                withStyle(style) { append(unescape(html.substring(contentStart))) }
                break
            }

            withStyle(style) { append(unescape(html.substring(contentStart, close))) }

            index = close + CLOSE.length
        }
    }

    /** Whether anything was actually highlighted, as opposed to merely returned. */
    fun hasMark(html: String?): Boolean = html?.contains(OPEN) == true

    /**
     * The five entities `htmlspecialchars` produces, and no others.
     *
     * Deliberately not a general HTML entity table: this input comes from one place — PHP's
     * `htmlspecialchars` via `ts_headline` — and decoding entities it never emits would mean
     * turning text the user actually typed into something else. `&copy;` in a subject is five
     * characters someone wrote.
     *
     * `&amp;` is resolved last, so `&amp;lt;` yields the literal `&lt;` rather than `<`.
     */
    private fun unescape(raw: String): String =
        raw.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&#39;", "'")
            .replace("&amp;", "&")

    private const val OPEN = "<mark>"
    private const val CLOSE = "</mark>"
}
