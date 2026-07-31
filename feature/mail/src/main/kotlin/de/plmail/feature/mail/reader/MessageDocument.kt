package de.plmail.feature.mail.reader

/**
 * The HTML actually handed to a WebView, wrapped and restyled for one [MessageRenderStyle].
 *
 * The message body is embedded rather than injected after load, because a script-free WebView
 * cannot be styled after the fact and, more importantly, restyling on load is what produces the
 * white flash: the document paints once as sent and then again as transformed.
 */
object MessageDocument {

    /**
     * Wraps [body] for [style].
     *
     * [body] is the server's sanitised HTML. It is never escaped here — escaping it would render
     * the mail as source code — which is precisely why the WebView it goes into must have
     * JavaScript disabled and no file access. The sanitising is the server's job and the sandbox is
     * ours.
     */
    fun wrap(body: String, style: MessageRenderStyle): String =
        """
        <!doctype html>
        <html${schemeAttribute(style)}>
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>${css(style)}</style>
        </head>
        <body>$body</body>
        </html>
        """
            .trimIndent()

    /**
     * `color-scheme` tells a message that declared `prefers-color-scheme` which one applies.
     *
     * Only for [MessageRenderStyle.DARK_NATIVE]: setting it on a message that has no dark mode
     * would recolour form controls and scrollbars against an unchanged white body.
     */
    private fun schemeAttribute(style: MessageRenderStyle): String =
        if (style == MessageRenderStyle.DARK_NATIVE) " style=\"color-scheme: dark\"" else ""

    private fun css(style: MessageRenderStyle): String =
        BASE +
            when (style) {
                MessageRenderStyle.ORIGINAL,
                MessageRenderStyle.DARK_NATIVE -> ""
                MessageRenderStyle.DARK_RESTYLED -> RESTYLED
                MessageRenderStyle.DARK_INVERTED -> INVERTED
            }

    /**
     * Shared rules, none of them about colour.
     *
     * `max-width: 100%` on imagery is what stops a 2000px newsletter header forcing the whole
     * document into a horizontal scroll — the single most common rendering complaint about mail on
     * a phone. `word-break` covers the other one: an unbroken tracking URL.
     */
    private val BASE =
        """
        html, body { margin: 0; padding: 12px; }
        body { font-family: sans-serif; line-height: 1.4; overflow-wrap: anywhere; }
        img, picture, video, svg, table { max-width: 100% !important; height: auto; }
        pre { white-space: pre-wrap; word-break: break-word; }
        """
            .trimIndent()

    /**
     * A dark surface applied directly, for messages that brought no colours.
     *
     * `!important` on the body pair only. Anything more would fight the message's own rules, and a
     * message with no colours has none to fight — the specificity is there to beat the user agent's
     * white default, not the sender.
     */
    private val RESTYLED =
        """
        html, body { background: #121212 !important; color: #e6e6e6 !important; }
        a { color: #9ecbff; }
        blockquote { border-left: 3px solid #3a3a3a; margin-left: 0; padding-left: 12px; color: #b8b8b8; }
        """
            .trimIndent()

    /**
     * Invert everything, then invert the imagery back.
     *
     * The second rule is the one everyone forgets, and without it every photograph, logo and
     * screenshot in the message renders as a negative — which is far more obviously broken than the
     * white background it was meant to fix.
     *
     * `hue-rotate(180deg)` after the inversion is what keeps colours recognisable rather than
     * complementary: a plain `invert(1)` turns a blue link orange.
     */
    private val INVERTED =
        """
        html { background: #121212; }
        body { filter: invert(1) hue-rotate(180deg); background: #ffffff; }
        img, picture, video, svg, [style*="background-image"] {
            filter: invert(1) hue-rotate(180deg);
        }
        """
            .trimIndent()
}
