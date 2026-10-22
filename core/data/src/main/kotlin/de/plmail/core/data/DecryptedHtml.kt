package de.plmail.core.data

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist

/**
 * Cleans the HTML of a decrypted message before the reader shows it (#9).
 *
 * Every other body the app shows is the server's sanitised HTML; the reader relies on that and
 * inserts it as it is. A decrypted body is the one exception — no server has seen it — so it gets
 * the same treatment the web app gives it (`assets/crypto/message.js`, DOMPurify): the markup mail
 * is made of stays, and nothing that runs, submits, embeds or redirects does.
 *
 * Forbidden, as in the web app: `script`, `iframe`, `frame`, `object`, `embed`, `form`, `input`,
 * `button`, `base`, `meta`, `link`, every event handler, `srcset`. `<style>` blocks go too — jsoup
 * has no safe way to keep them — so a decrypted message keeps its inline styles and loses
 * stylesheet rules, which is the one place it looks plainer than in the browser.
 *
 * This is a second wall, not the only one. The reader's WebView runs no JavaScript, opens no file
 * or content URL, and loads nothing remote until the person allows pictures — which applies to a
 * decrypted message exactly as to any other.
 */
object DecryptedHtml {

    fun sanitize(html: String): String {
        val dirty = Jsoup.parseBodyFragment(html)
        val clean: Document = Cleaner(SAFELIST).clean(dirty)

        return clean.body().html()
    }

    /**
     * The text alternative of an HTML body, for the `text/plain` part of an encrypted message — the
     * server derives it for every other message, but cannot see inside this one. Line breaks where
     * the HTML breaks lines, a blank line between blocks, entities decoded.
     */
    fun toText(html: String): String {
        val document = Jsoup.parseBodyFragment(html)
        document.outputSettings().prettyPrint(false)
        document.select("br").after(BREAK)
        document.select(BLOCKS).before(BREAK + BREAK)

        val text =
            org.jsoup.parser.Parser.unescapeEntities(
                Jsoup.clean(
                    document.body().html(),
                    "",
                    Safelist.none(),
                    Document.OutputSettings().prettyPrint(false),
                ),
                false,
            )

        return text
            .replace(BREAK, "\n")
            .replace('\u00A0', ' ')
            .lines()
            .joinToString("\n") { it.trimEnd() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    /** A private-use character no body contains: where a line ends, while jsoup strips the tags. */
    private const val BREAK = "\uE000"
    private const val BLOCKS = "p, div, li, tr, blockquote, h1, h2, h3, h4, h5, h6, pre, table"

    /** Plain text as HTML the reader can show: escaped, never interpreted. */
    fun fromText(text: String): String =
        "<pre>" +
            text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;") +
            "</pre>"

    private val PRESENTATION =
        arrayOf(
            "style",
            "class",
            "align",
            "valign",
            "bgcolor",
            "border",
            "cellpadding",
            "cellspacing",
            "width",
            "height",
            "color",
            "face",
            "size",
            "dir",
            "lang",
            "title",
        )

    private val SAFELIST: Safelist =
        Safelist.relaxed()
            .addTags(
                "font",
                "center",
                "span",
                "div",
                "hr",
                "small",
                "big",
                "s",
                "strike",
                "u",
                "tt",
                "address",
            )
            .addAttributes(":all", *PRESENTATION)
            .addAttributes("img", "alt", "src", "width", "height")
            .addAttributes("a", "href", "name")
            // The marker plMail puts on a sign-off, so a reopened encrypted
            // draft's signature is swapped rather than doubled; and the cite
            // a quote is marked with.
            .addAttributes("div", "data-pl-signature")
            .addAttributes("blockquote", "type")
            .removeProtocols("a", "href", "ftp")
            .addProtocols("a", "href", "http", "https", "mailto", "tel")
            // `cid:` is how the HTML names an image inside the same message;
            // `data:` is how a sender inlines one without a part. Remote
            // pictures stay `http(s)` and stay blocked until allowed.
            .addProtocols("img", "src", "http", "https", "cid", "data")
            .preserveRelativeLinks(false)
}
