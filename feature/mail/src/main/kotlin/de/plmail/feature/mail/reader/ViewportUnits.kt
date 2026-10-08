package de.plmail.feature.mail.reader

/**
 * Takes a sender's viewport-height units back out of a message.
 *
 * **The loop this breaks.** The reader sizes each message's WebView to the height its content
 * reports. Inside that WebView "the viewport" is the view — so a wrapper declared `min-height:
 * 100vh` with a footer under it measures one view plus a footer, the view grows to fit, the wrapper
 * is a view tall again, and the message runs away down the page a footer at a time, pushing reply
 * and forward below a stretch of empty space with no end. The web reader had exactly this and fixed
 * it on 2026-10-02 (`templates/mail/_frame_script.js`); this is the same rule.
 *
 * Nothing sized by the viewport's height can mean anything in a view sized by its content, so the
 * declaration goes and the element takes its own height. `vw` stays: the width is the pane's and
 * does not follow the content, and the fitting rules in [MessageDocument] are written in it.
 *
 * **In the markup rather than in a stylesheet**, because the declarations are inline — the mail's
 * design survives the sanitiser as `style` attributes — and no rule can remove an inline
 * declaration by its *value*, only override a property by name, which would flatten every
 * `min-height` in the message to catch the few written in `vh`. And not in script, as the web does
 * it, because this WebView runs none.
 *
 * Declaration by declaration, in `style` attributes and in `<style>` elements. Nothing else in the
 * body is touched: a paragraph that says "100vh" is text.
 *
 * Not checked against a real WebView: there was no device to reproduce the loop on when this was
 * written. It is a pure rewrite of markup, so what the tests pin is exactly what it does — and
 * removing a declaration can only ever leave an element at its natural height.
 */
internal object ViewportUnits {

    /**
     * A number followed by `vh`, `vb`, `vmin` or `vmax`, in any of the small/large/dynamic forms.
     */
    private val VIEWPORT_UNIT =
        Regex("""[\d.](?:[sld]?v(?:h|b|min|max))\b""", RegexOption.IGNORE_CASE)

    /**
     * A `style` attribute, with either quote. Group 2 is the quote and group 3 the declarations.
     */
    private val STYLE_ATTRIBUTE =
        Regex(
            """(\sstyle\s*=\s*)(["'])(.*?)\2""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )

    /** A `<style>` element. Group 2 is the stylesheet. */
    private val STYLE_ELEMENT =
        Regex(
            """(<style\b[^>]*>)(.*?)(</style\s*>)""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )

    /** One declaration inside a rule: everything up to the next `;` or the end of the block. */
    private val DECLARATION = Regex("""[^;{}]+:[^;{}]+;?""")

    fun drop(html: String): String {
        // The common case by a wide margin, and it should cost one scan.
        if (!VIEWPORT_UNIT.containsMatchIn(html)) return html

        val inline =
            STYLE_ATTRIBUTE.replace(html) { match ->
                val (prefix, quote, declarations) = match.destructured

                prefix + quote + declarations.withoutViewportUnits() + quote
            }

        return STYLE_ELEMENT.replace(inline) { match ->
            val (open, sheet, close) = match.destructured

            open +
                DECLARATION.replace(sheet) { it.value.takeUnless(::usesViewport).orEmpty() } +
                close
        }
    }

    private fun String.withoutViewportUnits(): String =
        split(';').filterNot(::usesViewport).joinToString(";")

    private fun usesViewport(declaration: String): Boolean =
        // The value only. A property is never named for a unit, but a custom
        // one could be, and `--10vh-gap: 4px` is not sized by anything.
        VIEWPORT_UNIT.containsMatchIn(declaration.substringAfter(':', missingDelimiterValue = ""))
}
