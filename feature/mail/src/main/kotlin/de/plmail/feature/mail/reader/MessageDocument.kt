package de.plmail.feature.mail.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import de.plmail.core.designsystem.PlMailColors

/**
 * The few colours a message body is allowed to be adapted to, as CSS.
 *
 * Hex strings rather than [Color], because the only consumer is a stylesheet and converting at the
 * call site would put `toArgb` arithmetic in the middle of the CSS. Built from the *resolved* theme
 * — see [of] — so Nord's blue-grey and Dusk's plum are what a message is redrawn onto rather than
 * one hardcoded near-black that belongs to neither.
 */
data class MessagePalette(
    /** What the message's paper is. The card the body sits on, not the page behind it. */
    val paper: String,
    val ink: String,
    val inkMuted: String,
    /** Links. The theme's one accent, so a message's links match the rest of the app. */
    val link: String,
    val line: String,
    /**
     * Whether the resolved theme is a dark one.
     *
     * Only [MessageDocument] reads it, and only to decide whether an *unadapted* message needs
     * paper of its own — see the untouched-original rule there.
     */
    val isDark: Boolean,
) {
    companion object {
        /**
         * The palette for a body drawn on a raised card.
         *
         * [PlMailColors.raised] rather than `surface`, deliberately: the reader draws each message
         * on a card and the WebView is transparent, so the *card* is what shows through wherever
         * the message paints nothing. Handing the document the page colour instead would leave a
         * seam exactly where the sender's own background stops.
         */
        fun of(colors: PlMailColors): MessagePalette =
            MessagePalette(
                paper = colors.raised.css(),
                ink = colors.ink.css(),
                inkMuted = colors.inkMuted.css(),
                link = colors.accent.css(),
                line = colors.line.css(),
                isDark = colors.isDark,
            )

        /** `#rrggbb`. Alpha is dropped rather than emitted: every palette colour is opaque. */
        private fun Color.css(): String = "#%06X".format(toArgb() and 0xFFFFFF)
    }
}

/**
 * The HTML actually handed to a WebView, wrapped and restyled for one [MessageRenderStyle].
 *
 * The message body is embedded rather than injected after load, because a script-free WebView
 * cannot be styled after the fact and, more importantly, restyling on load is what produces the
 * white flash: the document paints once as sent and then again as transformed.
 */
object MessageDocument {

    /**
     * The element the sender's markup is placed in, and the reason there is one at all.
     *
     * Everything that keeps a hostile message inside the pane hangs off this wrapper: it is the
     * horizontal scroll container, the containing block for absolutely positioned content, and the
     * element the dark filter is applied to. None of those can be attached to `body` instead —
     * `overflow` on `body` propagates to the viewport, which would make the *page* scroll sideways,
     * and a `filter` on `body` with a transparent background paints nothing to invert.
     *
     * The name is deliberately unlikely to appear in mail. The server's sanitiser keeps `id`
     * attributes, so a sender who happened to use the same one would be styled as the wrapper.
     */
    private const val ROOT = "plmail-message-root"

    /**
     * The inset between the pane's edge and the message, in CSS pixels.
     *
     * One constant because it is used twice and the two uses have to agree: it is the wrapper's
     * padding, and it is subtracted from `100vw` to give the width a replaced element may reach.
     * Written out separately they drift, and the symptom is an image that overflows by exactly the
     * padding — a hairline of the sender's background peeking out on the right of every newsletter,
     * which nobody would ever trace back to a stylesheet.
     */
    private const val INSET_PX = 12
    private const val INSET_BOTH_PX = INSET_PX * 2

    /**
     * Wraps [body] for [style], adapting to [palette] where the style adapts anything at all.
     *
     * [body] is the server's sanitised HTML. It is never escaped here — escaping it would render
     * the mail as source code — which is precisely why the WebView it goes into must have
     * JavaScript disabled and no file access. The sanitising is the server's job and the sandbox is
     * ours.
     *
     * @param remoteImages whether the user has allowed this message its pictures. Blocked is the
     *   only state that changes the markup: the pictures are rewritten into placeholders before
     *   they are wrapped, so that a message drawn without them is the same shape as the one drawn
     *   with them. See [BlockedImages].
     */
    fun wrap(
        body: String,
        style: MessageRenderStyle,
        palette: MessagePalette,
        remoteImages: RemoteImages,
    ): String {
        val isBlocking = remoteImages == RemoteImages.BLOCKED
        val content = if (isBlocking) BlockedImages.mark(body) else body

        return """
        <!doctype html>
        <html${schemeAttribute(style)}>
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>${css(style, palette, isBlocking)}</style>
        </head>
        <body><div id="$ROOT">$content</div></body>
        </html>
        """
            .trimIndent()
    }

    /**
     * `color-scheme` tells a message that declared `prefers-color-scheme` which one applies.
     *
     * Only for [MessageRenderStyle.DARK_NATIVE]: setting it on a message that has no dark mode
     * would recolour form controls and scrollbars against an unchanged white body.
     */
    private fun schemeAttribute(style: MessageRenderStyle): String =
        if (style == MessageRenderStyle.DARK_NATIVE) " style=\"color-scheme: dark\"" else ""

    private fun css(
        style: MessageRenderStyle,
        palette: MessagePalette,
        isBlocking: Boolean,
    ): String =
        listOf(
                base(),
                if (isBlocking) BLOCKED_IMAGES else "",
                when (style) {
                    MessageRenderStyle.ORIGINAL -> untouched(palette)
                    MessageRenderStyle.DARK_NATIVE -> ""
                    MessageRenderStyle.DARK_RESTYLED -> restyled(palette)
                    MessageRenderStyle.DARK_INVERTED -> INVERTED
                },
            )
            .filter { it.isNotEmpty() }
            .joinToString(separator = "\n")

    /**
     * The message exactly as sent — and, in a dark theme, on the paper it was written for.
     *
     * Nothing at all in a light theme, which is the rule this file has always had: the user's theme
     * is not the sender's problem.
     *
     * In a dark one, this style is only ever reached because the user asked for "show original",
     * and the escape hatch has to actually show them something. Left unpainted, a message that
     * declares no background of its own renders in the user agent's near-black default text on the
     * card behind it — which under Nord is a dark blue-grey, and the message is very nearly
     * invisible. That is worse than the adaptation it was an escape from. A message asked for as
     * sent gets the white sheet mail is written against; every colour in it is then the sender's
     * own, which is the whole point of the control.
     */
    private fun untouched(palette: MessagePalette): String =
        if (!palette.isDark) {
            ""
        } else {
            """
            #$ROOT { background: #ffffff; }
            """
                .trimIndent()
        }

    /**
     * Shared rules, none of them about colour. This is where the fitting happens.
     *
     * **The chosen answer is: fit by reflow, and scroll horizontally only for what cannot reflow.**
     * The alternative — laying the message out at its authored width and zooming the whole page out
     * to fit — is what a wide-viewport WebView does, and on a 411dp phone an 840px receipt lands at
     * roughly half scale, which turns 15px type into 7px. A message nobody can read is not a
     * message that fits.
     *
     * Three rules do the fitting and each one was arrived at by watching the receipt fail without
     * it. `max-width: 100%` alone — which is what this file used to carry — fixes none of them.
     *
     * **Table cells.** Per CSS 2.1 §17.5.2.2 a column with a *specified* width takes that width as
     * its **minimum**, not its preference, and `max-width` can never shrink a box below its minimum
     * content width. So a receipt whose two columns are `width="380"` pins its table at 760px
     * whatever else the stylesheet says, the table overflows, and everything past the right edge is
     * simply gone — labels on screen, amounts not, which is exactly what was reported. Releasing
     * the cell widths is what lets the table reflow.
     *
     * **Images.** A percentage `max-width` is ignored while an element's intrinsic contribution is
     * being computed, because there is no definite width to resolve it against yet. A 2000px banner
     * therefore contributes 2000px to its table's minimum however many `max-width: 100%` rules
     * apply to it — the table is sized wide first and the image is shrunk into it afterwards. `vw`
     * is not a percentage and does resolve, so the cap has to be expressed in those units to be
     * seen at the moment it matters. `min()` keeps the ordinary containing-block cap for the layout
     * pass, where a nested cell may be much narrower than the viewport.
     *
     * **Tables that asked for a width.** Once the two rules above land, a table shrink-to-fits and
     * its columns sit at their content widths — which puts a right-aligned amount hard against the
     * label to its left, reading as a bug rather than as a receipt. A table that declared a width
     * wanted to be wide, so it is given all the width it can have; one that declared nothing is
     * left alone, because a small table wrapping a button must not stretch across the phone.
     *
     * `overflow-wrap: anywhere` is load-bearing here and not only for tracking URLs — unlike
     * `break-word` it lowers the *minimum content width*, which is what gives the reflow above
     * somewhere to go.
     *
     * **Images are capped, never resized.** This rule carried a `width: auto !important` beside the
     * cap, and that was a bug rather than belt and braces: `auto` does not mean "as wide as it may
     * be", it means *discard the width the sender declared and use the file's own*. A newsletter
     * that sizes a row of icons `width="16"` off 48px assets had every one of them drawn at 48px,
     * and marketing mail sizes images that way constantly — which is why the report was "images
     * look too large, on many emails" rather than about one message.
     *
     * `max-width` alone does the job it was there for. A declared width is honoured until it
     * exceeds the viewport, and then the cap wins, because a cap always outranks a used width.
     * `height: auto !important` stays and is what keeps a capped image undistorted: the height is
     * recomputed from the width the cap allowed, against the file's own aspect ratio. Where nothing
     * was capped it resolves back to the declared height, so the ordinary case is untouched.
     *
     * ## The cap is written twice, in two different forms, and that is not an oversight
     *
     * Blocks and tables take `calc(100vw - Npx)`. Images take `min(100%, calc(100vw - Npx))`. Each
     * form is wrong in the other's place, and both were measured rather than reasoned about — see
     * below for how.
     *
     * A percentage is **indefinite during intrinsic sizing**. So `min(100%, …)` on a `<div>` that
     * asked for 640px caps what is *painted* at the viewport, and leaves the element still
     * contributing 640px to its ancestors' min-content width. A shrink-to-fit table around it grows
     * to 640, and the message scrolls sideways with every block inside it neatly capped. That is
     * exactly the shape of the bug this fixed: a table declaring `min-width: 600px` — `min-width`
     * outranks `max-width`, and nothing here was clearing it — sitting inside a page where every
     * individual element looked contained.
     *
     * The pure viewport cap has no percentage in it, resolves during intrinsic sizing, and stops
     * the ancestor growing. Applied to an **image**, though, it is worse than useless: an image in
     * a 120px column is capped at the viewport rather than at its column, so it bursts out of the
     * cell, and the few pixels of table chrome around it push the total past the viewport.
     * Measured: a 600px image in a 120px column renders 302px wide under `min(100%, …)` and 476px
     * under the pure cap, overflowing by 3px. Images keep the percentage; boxes do not.
     *
     * **How to check this rather than argue about it.** Android's WebView is Chromium, so headless
     * Chrome at a phone width is a faithful proxy. Write a hostile body to a file, run it through
     * [wrap], append a script that reports `#$ROOT`'s `scrollWidth` against its `clientWidth`, and
     * `google-chrome --headless --window-size=411,891 --dump-dom` it. **Measure `#$ROOT`, not the
     * document** — the root is the scroll container, so a document-level measurement reports no
     * overflow while the message scrolls sideways underneath. The fixture behind this note put
     * 664px of content in a 500px container before these rules and exactly 500 after.
     *
     * ## Every rule is scoped under `#$ROOT`, and that is about the cascade rather than tidiness
     *
     * `!important` does not settle a fight between two author stylesheets — specificity does, and
     * only then source order. A bare `div { min-width: 0 !important }` is specificity (0,0,1), so a
     * newsletter's own `.card { min-width: 560px !important }` at (0,1,0) beats it outright. Mail
     * carries `!important` in `<style>` blocks constantly, and the sender's block is injected into
     * the body, which is *later* in document order than this one — so ties go to them as well.
     *
     * Prefixing with the root's id makes every rule here (1,0,1), which no class-based selector can
     * reach. Measured: that fixture scrolled to 572px in a 411px viewport before the prefix and
     * fits exactly after. `:is()` keeps the lists readable and contributes only its own highest
     * argument to the specificity, so the id is doing all the work.
     *
     * ## Text that will not wrap
     *
     * Capping a box does nothing about text inside it that refuses to wrap. This started as
     * `[style*="nowrap"]`, which only reaches an inline style — and a class in the sender's own
     * `<style>` block went straight past it, worth 48px of sideways scroll on its own.
     *
     * So it is `#$ROOT *`, which is the bluntest rule in the file and is meant to be. The cost is
     * real: a sender who wrote `nowrap` to hold "1.234,56 €" together loses that, and gets a price
     * broken across two lines. The alternative is the whole message scrolling sideways, which is
     * the thing this reader is not allowed to do. `pre`, `code`, `samp`, `kbd` and `textarea` are
     * put back to `pre-wrap` at (1,0,1) so they win on specificity rather than on source order —
     * and that matters more than it looks, because a plain-text message *is* a `<pre>`
     * (`ReaderViewModel.renderable`). Verified: an indented plain-text mail keeps its indentation
     * and a 96-character unbreakable URL inside it still does not scroll the message.
     *
     * `overflow-x: auto` on the wrapper is the safety net for what is genuinely left: an absolutely
     * positioned element at `left: 900px`, a `<table>` whose cells carry unbreakable content wider
     * than the pane. It should now be rare rather than routine. A scroll container clips its own
     * overflow, so nothing escapes to the viewport and **the page itself never scrolls sideways** —
     * the property that keeps the reader's vertical drag working. `position: relative` is what puts
     * positioned descendants inside that container rather than letting them escape to the initial
     * containing block.
     */
    private fun base(): String =
        """
        html, body { margin: 0; padding: 0; background: transparent; }
        #$ROOT {
            padding: ${INSET_PX}px;
            font-family: sans-serif;
            line-height: 1.4;
            overflow-wrap: anywhere;
            position: relative;
            overflow-x: auto;
        }
        #$ROOT :is(table, tbody, thead, tfoot, tr) {
            min-width: 0 !important;
            max-width: calc(100vw - ${INSET_BOTH_PX}px) !important;
        }
        #$ROOT table { width: auto !important; table-layout: auto !important; }
        #$ROOT :is(table[width], table[style*="width"]) { width: 100% !important; }
        #$ROOT :is(td, th, col, colgroup) { width: auto !important; min-width: 0 !important; }
        #$ROOT :is(img, picture, video, svg, canvas, iframe, object, embed) {
            max-width: min(100%, calc(100vw - ${INSET_BOTH_PX}px)) !important;
        }
        #$ROOT :is(img, picture, video) { height: auto !important; }
        #$ROOT :is(div, p, blockquote, pre, section, article, header, footer, main, aside,
        ul, ol, dl, h1, h2, h3, h4, h5, h6, figure, form, fieldset) {
            min-width: 0 !important;
            max-width: calc(100vw - ${INSET_BOTH_PX}px) !important;
        }
        #$ROOT * { white-space: normal !important; }
        #$ROOT :is(pre, code, samp, kbd, textarea) {
            white-space: pre-wrap !important;
            word-break: break-word;
        }
        """
            .trimIndent()

    /**
     * What a picture the user has not allowed looks like.
     *
     * **Lifted from the web client, and that is the point of it.** The same rule lives in
     * `templates/mail/_message_body.html.twig` as `img[data-plmail-blocked]` — the hatch, the
     * dashed outline, the inset offset and the three greys are its values, not new ones — so the
     * same message read on a phone and in a browser shows the same thing in the same places. The
     * marker attribute is put on by [BlockedImages], which is the Android half of the web's
     * `RemoteContentBlocker`.
     *
     * The web's `min-width` and `min-height` are the one thing left out. They are a floor under a
     * placeholder whose only size is the substituted pixel's; here the size comes from what the
     * message declared, and a floor would inflate a `1×1` tracking pixel into a visible box — see
     * [BlockedImages] for why that is the wrong way to be wrong.
     *
     * The **selector** is prefixed with the root's id where the web's is not, and that is the one
     * deliberate divergence. `img[data-plmail-blocked]` is specificity (0,1,1), which a
     * newsletter's own `.hero img { … !important }` matches and then wins on source order, because
     * the sender's `<style>` block is injected after this one. The web renders inside an iframe and
     * has no such fight. The declarations — the values that make the two surfaces agree — are
     * untouched.
     *
     * Deliberately unthemed, again matching the web. Under [MessageRenderStyle.DARK_INVERTED] the
     * hatch is inverted with the document and then inverted back by the imagery rule below, so it
     * lands on these greys either way — a placeholder that changed colour with the render strategy
     * would read as part of the sender's design rather than as the app saying "not loaded".
     */
    private val BLOCKED_IMAGES =
        """
        #$ROOT img[${BlockedImages.MARKER}] {
            background: repeating-linear-gradient(135deg, #f4f4f5 0 6px, #e4e4e7 6px 12px);
            outline: 1px dashed #a1a1aa;
            outline-offset: -1px;
        }
        """
            .trimIndent()

    /**
     * Light text on the theme's own paper, for messages that brought no colours.
     *
     * The best-looking outcome, and the one that needs the least. Note there is **no background
     * rule**: the WebView is transparent and the reader draws the card behind it, so leaving the
     * document unpainted is what guarantees the message's paper and the card are the same colour by
     * construction rather than by two constants agreeing. The previous version painted `#121212`
     * here, which was a third colour belonging to no theme and showed as a band wherever the
     * sender's own background stopped.
     *
     * `!important` on the ink only. A message with no colours has none to fight; the specificity is
     * there to beat the user agent's black default, not the sender.
     */
    private fun restyled(palette: MessagePalette): String =
        """
        #$ROOT { color: ${palette.ink} !important; }
        a { color: ${palette.link}; }
        blockquote {
            border-left: 3px solid ${palette.line};
            margin-left: 0;
            padding-left: 12px;
            color: ${palette.inkMuted};
        }
        """
            .trimIndent()

    /**
     * Invert everything the sender painted, then invert the imagery back.
     *
     * The second rule is the one everyone forgets, and without it every photograph, logo and
     * screenshot in the message renders as a negative — which is far more obviously broken than the
     * white background it was meant to fix.
     *
     * `hue-rotate(180deg)` after the inversion is what keeps colours recognisable rather than
     * complementary: a plain `invert(1)` turns a blue link orange.
     *
     * The filter goes on the wrapper and **the wrapper is not given a background**, which is the
     * change that themes this style. Painting the body white before inverting it, as the first
     * version did, made every dark theme's message paper pure black regardless of which theme was
     * chosen. Left transparent, only what the sender actually painted is inverted and the card
     * behind shows through unfiltered — so the surround is Nord's blue-grey under Nord and Dusk's
     * plum under Dusk.
     */
    private val INVERTED =
        """
        #$ROOT { filter: invert(1) hue-rotate(180deg); }
        img, picture, video, svg, [style*="background-image"] {
            filter: invert(1) hue-rotate(180deg);
        }
        """
            .trimIndent()
}
