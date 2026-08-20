package de.plmail.feature.mail.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The document that reaches the WebView.
 *
 * These are string assertions over a stylesheet, which is normally a poor kind of test — but the
 * previous version of this class shows why they are worth writing carefully rather than not at all.
 * It asserted that `max-width: 100% !important` was present and called the case "wide content
 * cannot force a horizontal scroll". It passed for the whole time a receipt's amounts were being
 * clipped off the right-hand edge of the pane, because `max-width` is not what fits a message: a
 * table cell with a specified width raises its column's *minimum*, and no `max-width` can shrink a
 * box below its minimum. So each case below names the failure it would catch rather than the
 * declaration it happens to find.
 *
 * The second half of the class is the placeholder drawn where a remote picture was refused. Those
 * cases are string assertions for a better reason than convenience: the placeholder is markup this
 * code writes, a WebView is the one thing Robolectric cannot render, and the document is the only
 * place the rule exists to be checked.
 */
class MessageDocumentTest {

    private val body = "<p>Hello</p><img src=\"cid:logo\">"

    /** A palette with values nothing else in the document could coincidentally contain. */
    private val palette =
        MessagePalette(
            paper = "#101112",
            ink = "#131415",
            inkMuted = "#161718",
            link = "#191A1B",
            line = "#1C1D1E",
            isDark = true,
        )

    /**
     * The document as the reader actually first draws it — pictures blocked, because that is the
     * state a message is opened in and the only one every case below has to hold in.
     */
    private fun wrap(style: MessageRenderStyle) =
        MessageDocument.wrap(body, style, palette, RemoteImages.BLOCKED)

    /** A blocked body, for the cases that are about the placeholder rather than about colour. */
    private fun blocked(html: String) =
        MessageDocument.wrap(html, MessageRenderStyle.ORIGINAL, palette, RemoteImages.BLOCKED)

    /**
     * The rule the reported bug turned on.
     *
     * A receipt whose columns are `<td width="380">` is pinned at 760px by the table algorithm and
     * overflows a phone whatever else is in the stylesheet — labels render, the amounts beside them
     * do not. Releasing the specified widths is the only thing that lets it reflow.
     */
    @Test
    fun `table cells cannot pin a table wider than the pane`() {
        assertTrue(
            wrap(MessageRenderStyle.ORIGINAL).contains("td, th, col, colgroup { width: auto"),
            "cell widths are not released, so a fixed-width table will clip its right-hand column",
        )
    }

    /**
     * The image cap has to be a length, not a percentage.
     *
     * A percentage `max-width` is ignored while an element's intrinsic contribution is computed, so
     * a 2000px banner still sizes the table around it. `vw` resolves at that moment and a
     * percentage does not, which is the entire reason this rule looks unusual.
     */
    @Test
    fun `images are capped in viewport units`() {
        assertTrue(
            wrap(MessageRenderStyle.ORIGINAL).contains("max-width: min(100%, calc(100vw -"),
            "a percentage-only cap does not constrain a wide image's intrinsic width",
        )
    }

    /**
     * Capping a picture is not the same as resizing it, and the reset used to do both.
     *
     * `width: auto !important` sat beside the cap. `auto` does not mean "as wide as it may be" — it
     * means *discard the width the sender declared and use the file's own*, so a newsletter sizing
     * a row of icons `width="16"` off 48px assets had every one of them drawn at 48px. Marketing
     * mail sizes images that way constantly, which is why this was reported as "images look too
     * large, on many emails" rather than against one message.
     *
     * Asserted against the image rule rather than against the whole sheet, because tables and cells
     * legitimately keep a `width: auto` of their own a few lines above.
     */
    @Test
    fun `the reset caps an image without discarding the width the sender declared`() {
        val css = wrap(MessageRenderStyle.ORIGINAL)
        val rule = css.substringAfter("img, picture, video, svg").substringBefore("}")

        assertTrue(rule.contains("max-width"), "the cap is what stops a wide image scrolling")
        assertFalse(
            rule.contains("width: auto"),
            "a declared width has to survive, or every deliberately small image is drawn at its " +
                "file's own size",
        )
    }

    /**
     * The wrapper's padding and the width an image may reach are one number twice.
     *
     * Drift between them is invisible in review and shows up as a sliver of the sender's background
     * on the right of every message with a full-width image — read out of the CSS here rather than
     * asserted as a literal, so the test fails on disagreement rather than on a value changing.
     */
    @Test
    fun `the inset and the viewport cap agree`() {
        val css = wrap(MessageRenderStyle.ORIGINAL)

        val padding = Regex("padding: (\\d+)px").find(css)?.groupValues?.get(1)?.toInt()
        val subtracted = Regex("calc\\(100vw - (\\d+)px\\)").find(css)?.groupValues?.get(1)?.toInt()

        assertEquals(padding?.times(2), subtracted, "the wrapper inset and the vw cap have drifted")
    }

    /**
     * The page must never scroll sideways; the message may.
     *
     * `overflow` on `body` propagates to the viewport, so putting the scroller there would drag the
     * whole page — and with it the reader's vertical gesture — rather than the message.
     */
    @Test
    fun `the message scrolls horizontally, not the page`() {
        val css = wrap(MessageRenderStyle.ORIGINAL)

        assertTrue(css.contains("<body><div id=\"plmail-message-root\">$body</div></body>"))
        assertTrue(css.contains("#plmail-message-root {"))
        assertTrue(css.substringAfter("#plmail-message-root {").contains("overflow-x: auto"))
        assertFalse(
            Regex("^\\s*(html|body)[^{]*\\{[^}]*overflow", RegexOption.MULTILINE)
                .containsMatchIn(css),
            "overflow on html or body propagates to the viewport: $css",
        )
    }

    @Test
    fun `inversion inverts imagery back`() {
        val html = wrap(MessageRenderStyle.DARK_INVERTED)

        assertTrue(html.contains("#plmail-message-root { filter: invert(1) hue-rotate(180deg)"))
        assertTrue(
            html.contains("img, picture, video, svg, [style*=\"background-image\"]"),
            "imagery is not inverted back: $html",
        )
    }

    /**
     * Nothing in an adapted document may be a colour the theme does not have.
     *
     * The first version painted `#121212` and `#e6e6e6`, which are Material's near-black and
     * near-white and belong to none of this app's six themes — so a message adapted under Nord sat
     * on a grey rectangle inside a blue-grey app, and the strip of it showing through the
     * document's padding read as a black band down the edge of the message.
     */
    @Test
    fun `an adapted message uses the theme's own colours`() {
        val restyled = wrap(MessageRenderStyle.DARK_RESTYLED)

        assertFalse(restyled.contains("invert("), "restyling must not invert: $restyled")
        assertTrue(restyled.contains(palette.ink))
        assertTrue(restyled.contains(palette.link))
        assertTrue(restyled.contains(palette.line))

        // Every style that *adapts* anything. ORIGINAL is excluded deliberately
        // and is the one place a literal colour is still right: it paints the
        // white sheet the sender wrote against, which is not a theme decision.
        listOf(
                MessageRenderStyle.DARK_RESTYLED,
                MessageRenderStyle.DARK_INVERTED,
                MessageRenderStyle.DARK_NATIVE,
            )
            .forEach { style ->
                assertFalse(
                    Regex("#(121212|e6e6e6|9ecbff|3a3a3a|b8b8b8|ffffff)", RegexOption.IGNORE_CASE)
                        .containsMatchIn(wrap(style)),
                    "$style still carries a hardcoded colour",
                )
            }
    }

    /**
     * The adapted styles paint no background at all, and that is the point.
     *
     * The reader draws the card and the WebView is transparent, so an unpainted document takes the
     * card's colour by construction. Painting one here would be a second copy of the theme's paper
     * that has to keep agreeing with the first, and the way that fails is a seam exactly where the
     * sender's own background stops.
     */
    @Test
    fun `an adapted message paints no paper of its own`() {
        listOf(MessageRenderStyle.DARK_RESTYLED, MessageRenderStyle.DARK_INVERTED).forEach { style
            ->
            assertFalse(
                Regex("#plmail-message-root[^}]*background:").containsMatchIn(wrap(style)),
                "$style paints its own paper and will not match the card behind it",
            )
        }
    }

    @Test
    fun `the original is left alone`() {
        val html =
            MessageDocument.wrap(
                body,
                MessageRenderStyle.ORIGINAL,
                palette.copy(isDark = false),
                RemoteImages.BLOCKED,
            )

        assertFalse(html.contains("invert("))
        assertFalse(html.contains("color-scheme"))
        assertFalse(html.contains(palette.ink), "the original must not be recoloured")
        assertFalse(
            Regex("#plmail-message-root[^}]*background:").containsMatchIn(html),
            "a light theme must not paint paper the sender did not ask for",
        )
    }

    /**
     * "Show original" has to show something.
     *
     * It is the escape hatch from adaptation, and in a dark theme it is the *only* way this style
     * is reached — so a message that declares no background of its own would otherwise render the
     * user agent's near-black default text straight onto the card, which under Nord is a dark
     * blue-grey. The message would be there, be correct, and be unreadable.
     */
    @Test
    fun `asking for the original in the dark puts it on paper`() {
        assertTrue(
            Regex("#plmail-message-root \\{ background: #ffffff")
                .containsMatchIn(wrap(MessageRenderStyle.ORIGINAL)),
            "the unadapted message has no paper and will be black on a dark card",
        )
    }

    /**
     * `color-scheme` only where the message asked for it.
     *
     * Setting it on a message with no dark mode recolours form controls and scrollbars against a
     * body that stayed white.
     */
    @Test
    fun `the colour scheme is declared only for a self-darkening message`() {
        assertTrue(wrap(MessageRenderStyle.DARK_NATIVE).contains("color-scheme: dark"))
        assertFalse(wrap(MessageRenderStyle.DARK_RESTYLED).contains("color-scheme"))
    }

    @Test
    fun `the body is embedded rather than escaped`() {
        // Escaping it would render the mail as source code. The server
        // sanitises; the WebView sandbox is what makes that safe.
        assertTrue(wrap(MessageRenderStyle.ORIGINAL).contains(body))
    }

    // -- blocked pictures ----------------------------------------------------
    //
    // Everything below is about the placeholder drawn where a remote picture was
    // not loaded, and nearly every case is one of the ways its *size* can be
    // wrong. Right look and wrong size is the worse of the two failures: the
    // message reads correctly until the pictures are allowed, and then reflows
    // out from under the reader's thumb.

    /**
     * The rule the whole feature turns on.
     *
     * A ratio rather than `height: 200px`, and this is the assertion worth keeping if every other
     * one here were deleted. The stylesheet caps every picture at the pane's width, so on a phone
     * this box is drawn narrower than the 600 it asked for — a fixed height would then be the wrong
     * shape, and allowing pictures would still move everything below it.
     */
    @Test
    fun `a picture that declared its size keeps exactly that size`() {
        val html =
            blocked("""<img src="https://cdn.example.com/hero.png" width="600" height="200">""")

        assertTrue(html.contains("width:600px!important"), html)
        assertTrue(html.contains("height:auto!important"), html)
        assertTrue(html.contains("aspect-ratio:600/200"), html)
    }

    @Test
    fun `an inline style outranks the width attribute, as it would in a browser`() {
        val html =
            blocked(
                """<img src="https://cdn.example.com/a.png" width="600" """ +
                    """style="width:320px;height:100px">"""
            )

        assertTrue(html.contains("aspect-ratio:320/100"), html)
        assertFalse(html.contains("aspect-ratio:600"), html)
    }

    /**
     * A percentage is not a size this can hold.
     *
     * `width="100%"` says how wide the picture will be and nothing whatsoever about how tall, so it
     * is read as "declared nothing". A placeholder stretched across the pane at an invented height
     * would jump the layout by more than the collapsed sliver it replaced.
     */
    @Test
    fun `a percentage width is not a declared size`() {
        val html = blocked("""<img src="https://cdn.example.com/a.png" width="100%">""")

        assertTrue(html.contains("width:48px!important;height:48px!important"), html)
    }

    @Test
    fun `a picture that declared nothing gets a box that is obviously a placeholder`() {
        val html = blocked("""<img src="https://cdn.example.com/mystery.png">""")

        assertTrue(html.contains("width:48px!important;height:48px!important"), html)
    }

    /**
     * A tracking pixel stays a tracking pixel.
     *
     * 1×1 with no floor under it, which is the one place this departs from the web client — its
     * rule floors every placeholder at 12px. Repainting an invisible tracker as a visible box makes
     * it more prominent than its own sender intended, and scatters grey specks through every
     * newsletter that carries one.
     */
    @Test
    fun `a tracking pixel is not inflated into something visible`() {
        val html =
            blocked("""<img src="https://track.example.com/open.gif" width="1" height="1">""")

        assertTrue(html.contains("width:1px!important"), html)
        assertTrue(html.contains("aspect-ratio:1/1"), html)
        assertFalse(html.contains("48px"), html)
    }

    /**
     * The sender's URL does not travel into the document at all.
     *
     * Dropped rather than parked on a data attribute, which is where the web client differs — it
     * needs the URL in place to un-block without a round trip, and this reader rebuilds the whole
     * document from the original body instead. A URL that is not in the document cannot be fetched
     * by anything that goes wrong downstream of here.
     */
    @Test
    fun `a blocked picture carries no remote URL`() {
        val html = blocked("""<img src="https://track.example.com/open.gif?id=abcdef">""")

        assertFalse(html.contains("track.example.com/open.gif"), html)
        assertTrue(html.contains("""src="data:image/gif;base64,"""), html)
        assertTrue(html.contains("""${BlockedImages.MARKER}="1""""), html)
    }

    @Test
    fun `srcset is parked, so the browser cannot prefer it over the placeholder`() {
        val html =
            blocked(
                """<img src="https://cdn.example.com/a.png" """ +
                    """srcset="https://cdn.example.com/a@2x.png 2x">"""
            )

        assertTrue(html.contains("""data-plmail-srcset="https://cdn.example.com/a@2x.png 2x""""))
        assertFalse(html.contains(""" srcset="""), html)
    }

    /**
     * The host becomes the alt text, and a real alt is left alone.
     *
     * Never painted, because the substituted pixel loads successfully — this is what a screen
     * reader announces instead of "unlabelled image", and the host is the one thing about a picture
     * somebody could judge before deciding to load it.
     */
    @Test
    fun `a blocked picture is named for its host unless the sender named it better`() {
        assertTrue(
            blocked("""<img src="https://cdn.shop.example/x.png">""")
                .contains("""alt="cdn.shop.example""""),
            "a blocked picture with no alt announces as nothing at all",
        )

        assertTrue(
            blocked("""<img src="https://cdn.shop.example/x.png" alt="Autumn sale">""")
                .contains("""alt="Autumn sale""""),
            "the sender said something better than a hostname",
        )
    }

    /**
     * Pictures that never reach the network are not blocked.
     *
     * A `cid:` part came down with the message and a `data:` URI carries its own bytes; neither
     * tells a sender anything. Hatching them would hide an inline signature logo behind a privacy
     * warning that does not apply to it.
     */
    @Test
    fun `local pictures are left exactly as they arrived`() {
        val local =
            """<img src="cid:logo@example" width="80"><img src="data:image/png;base64,AAAA">"""

        assertTrue(blocked(local).contains(local), blocked(local))
    }

    /**
     * A `>` inside an attribute does not cut the tag in half.
     *
     * The reason the tag is scanned rather than matched with `[^>]*>`: a comparison in an alt text,
     * or far more often one inside a tracking URL, would end the tag early and spill the rest of
     * its attributes into the message as visible text.
     */
    @Test
    fun `an angle bracket inside an attribute does not end the tag`() {
        val html = blocked("""<img src="https://x.example/a.png" alt="1 > 0" width="10">""")

        assertTrue(html.contains("""alt="1 > 0""""), html)
        assertTrue(html.contains("width:10px!important"), html)
    }

    @Test
    fun `text around a picture survives the rewrite`() {
        // The rewriter walks the body copying everything it is not interested
        // in, and "everything it is not interested in" is the whole message.
        val html = blocked("""<p>Before</p><img src="https://x.example/a.png"><p>After</p>""")

        assertTrue(html.contains("<p>Before</p>"), html)
        assertTrue(html.contains("<p>After</p>"), html)
    }

    /**
     * Allowing pictures rewrites nothing.
     *
     * The privacy control must not turn into an editor: once the user has said yes, what they see
     * is what was sent. The placeholder's stylesheet goes with it too — left in, the rule would be
     * inert, but it would also be the first thing to blame when a picture that *did* load turned up
     * with an outline around it.
     */
    @Test
    fun `allowing pictures rewrites nothing`() {
        val sent = """<p>Hello</p><img src="https://cdn.example.com/hero.png" width="600">"""
        val html =
            MessageDocument.wrap(sent, MessageRenderStyle.ORIGINAL, palette, RemoteImages.ALLOWED)

        assertTrue(html.contains(sent), html)
        assertFalse(html.contains(BlockedImages.MARKER), html)
        assertFalse(html.contains("repeating-linear-gradient"), html)
    }

    /**
     * The placeholder is drawn with the web client's own rule.
     *
     * Byte for byte out of `templates/mail/_message_body.html.twig`. The whole request was that the
     * two clients show an unloaded picture the same way, so if this ever has to change it has to
     * change in both or the same message looks like two different messages.
     */
    @Test
    fun `the placeholder matches the web client`() {
        val html = blocked("""<img src="https://cdn.example.com/hero.png">""")

        assertTrue(
            html.contains(
                "background: repeating-linear-gradient(135deg, #f4f4f5 0 6px, #e4e4e7 6px 12px);"
            ),
            html,
        )
        assertTrue(html.contains("outline: 1px dashed #a1a1aa;"), html)
        assertTrue(html.contains("outline-offset: -1px;"), html)
    }
}
