package de.plmail.feature.mail.reader

/**
 * Turns every remote picture in a message into a placeholder that keeps the picture's own space.
 *
 * The blocking itself is not done here and never was — `MessageClient` in [MessageWebView] refuses
 * the request, and that is the security boundary. This is the *appearance* of a message whose
 * pictures were refused, and it exists because refusing a request leaves an `<img>` with no bytes:
 * a zero-height sliver where a banner was, so a catalogue reads as a blank page and tapping "Show
 * pictures" re-lays the whole message out under the reader's thumb.
 *
 * **The shape is copied from the web client on purpose.** `RemoteContentBlocker` there does the
 * same two things — substitute a 1×1 transparent GIF for the `src`, and mark the element with
 * `data-plmail-blocked` — and [MessageDocument] then styles that marker with the web's own rule.
 * The GIF is what makes this predictable: an element whose resource *loaded* has no broken-image
 * state to render and no user-agent-specific idea of how big a broken image should be, so the only
 * thing deciding the box is the box we write.
 *
 * What is deliberately *not* copied is the web's handling of `url()` inside a `style` attribute. It
 * parks those references so the browser stops fetching them; here the interceptor already refuses
 * them, and the web draws no placeholder on them either — its rule is `img[data-plmail-blocked]`,
 * nothing wider.
 */
internal object BlockedImages {

    /**
     * The attribute [MessageDocument]'s stylesheet hangs the placeholder's look off.
     *
     * The same name the web client uses, so the two stylesheets are literally the same rule. It is
     * dropped from the input first — a sender who wrote it themselves would otherwise get a hatched
     * box over a picture that was never blocked.
     */
    const val MARKER = "data-plmail-blocked"

    /**
     * Rewrites the remote `<img>` elements of [html] into placeholders.
     *
     * Only `http:`, `https:` and protocol-relative sources are touched. A `cid:` part was
     * downloaded with the message and a `data:` URI carries its own bytes; neither reaches the
     * network, so neither is blocked and neither may be replaced by a grey box.
     */
    fun mark(html: String): String {
        val out = StringBuilder(html.length)
        var index = 0

        while (true) {
            val start = html.indexOf("<img", index, ignoreCase = true)

            if (start < 0) break

            val opened = start + TAG_NAME_LENGTH
            val after = html.getOrNull(opened)
            val end = if (after == null) -1 else endOfTag(html, opened)

            // "<imgx" is a different element and a "<img" running off the end of
            // the string is not a tag at all. Both are copied through rather than
            // skipped, or the text disappears from the message.
            if (
                after == null || end < 0 || !(after.isWhitespace() || after == '>' || after == '/')
            ) {
                out.append(html, index, opened)
                index = opened
                continue
            }

            out.append(html, index, start)
            out.append(placeholder(html.substring(opened, end)))
            index = end + 1
        }

        out.append(html, index, html.length)

        return out.toString()
    }

    /**
     * Where the tag opened at [from] closes.
     *
     * Scanned rather than matched with `[^>]*>`, because a `>` inside a quoted attribute value —
     * `alt="1 > 0"`, and more often a tracking URL — would end the tag early and everything after
     * it would be emitted into the document as text.
     */
    private fun endOfTag(html: String, from: Int): Int {
        var quote: Char? = null

        for (index in from until html.length) {
            val character = html[index]

            when {
                quote != null -> if (character == quote) quote = null
                character == '"' || character == '\'' -> quote = character
                character == '>' -> return index
            }
        }

        return -1
    }

    /**
     * One `<img>`, rebuilt as a placeholder — or handed back unchanged if it was never blocked.
     *
     * [interior] is everything between `<img` and the closing `>`. The tag is rebuilt from its
     * parsed attributes rather than patched in place: `src`, `srcset`, `style`, `alt` and the
     * marker all have to be replaced or added, and five overlapping substitutions on one string is
     * where the corner cases live.
     */
    private fun placeholder(interior: String): String {
        val attributes = attributesOf(interior)
        val source = attributes.valueOf("src")?.trim().orEmpty()

        if (!isRemote(source)) return "<img$interior>"

        val style = attributes.valueOf("style").orEmpty()
        val box = box(declared(style, attributes, WIDTH), declared(style, attributes, HEIGHT))

        val rebuilt =
            attributes.filterNot { it.name in REPLACED } +
                // The sender's own declarations are kept and ours appended after
                // them, so a picture's margins and alignment survive. The box
                // carries !important and wins the two properties it cares about
                // wherever the two collide.
                Attribute(
                    "style",
                    if (style.isBlank()) box else style.trimEnd(';', ' ') + ";" + box,
                ) +
                // Parked, not dropped. A browser prefers a srcset candidate over
                // src, so leaving it live would put the remote URL back in front
                // of the placeholder just drawn -- and the interceptor refusing
                // it would paint a broken image over the box.
                listOfNotNull(attributes.valueOf("srcset")?.let { Attribute(PARKED_SRCSET, it) }) +
                // The host is the one thing about a picture a reader could
                // actually judge before deciding to load it. With the GIF loading
                // successfully this text is never painted -- it is what a screen
                // reader announces instead of "unlabelled image".
                Attribute(
                    "alt",
                    attributes.valueOf("alt")?.takeIf { it.isNotBlank() }
                        ?: hostOf(source).orEmpty(),
                ) +
                Attribute("src", PIXEL) +
                Attribute(MARKER, "1")

        return rebuilt.joinToString(separator = " ", prefix = "<img ", postfix = ">") {
            """${it.name}="${it.value.replace("\"", "&quot;")}""""
        }
    }

    /**
     * The inline style that gives a placeholder the space its picture would have taken.
     *
     * **This is the "correct dimensions" half of the job, and the reason it is written per element
     * rather than left to the stylesheet.** CSS cannot read `width="600"` into a length, and
     * [MessageDocument]'s fitting rules release every image's width and height with `!important` so
     * that fixed-width tables can reflow — which for a placeholder means the box it was given
     * collapses again. An inline `!important` declaration outranks any selector of the same origin,
     * so this is what puts the size back for these elements alone, without loosening the fitting
     * rules for anything else.
     *
     * With both dimensions known the height is expressed as an **aspect ratio** rather than a pixel
     * count, and that is the difference between holding the layout and only appearing to. The
     * stylesheet caps every image at the pane's width, so a 600px-wide banner on a 380px phone is
     * drawn at 380 — and a placeholder with a hard `height: 200px` would then be the wrong shape,
     * so allowing pictures would still jump the message. A ratio shrinks with the cap and lands on
     * exactly the height the real picture will occupy.
     *
     * A dimension declared as zero is honoured as zero rather than ratioed, both because
     * `aspect-ratio: 600 / 0` is not a ratio and because a picture sized to nothing was meant to be
     * invisible.
     */
    private fun box(width: Int?, height: Int?): String =
        when {
            width == null && height == null -> fixed(FALLBACK_PX, FALLBACK_PX)

            // One dimension known: the other is a guess whatever is done with it,
            // so it gets the placeholder box rather than a ratio invented from
            // nothing. A wide strip is at least the right width.
            width == null -> fixed(FALLBACK_PX, height)
            height == null -> fixed(width, FALLBACK_PX)

            width > 0 && height > 0 ->
                "width:${width}px!important;height:auto!important;aspect-ratio:$width/$height;"

            else -> fixed(width, height)
        }

    private fun fixed(width: Int?, height: Int?): String =
        "width:${width}px!important;height:${height}px!important;"

    /**
     * The length the message declares along [axis], in CSS pixels, or null where it declares none.
     *
     * The inline style wins over the attribute, because that is the order a browser resolves them
     * in: a `width` attribute is a presentational hint and any real declaration outranks it.
     *
     * Percentages are read as *not declared*. `width="100%"` says how wide the picture will be but
     * nothing at all about how tall, and a placeholder stretched across the pane at some invented
     * height would jump by more than the collapsed sliver it replaced.
     */
    private fun declared(style: String, attributes: List<Attribute>, axis: Axis): Int? =
        axis.inStyle.find(style)?.groupValues?.get(1)?.let(::lengthOf)
            ?: attributes.valueOf(axis.attribute)?.let(::lengthOf)

    /** `600`, `600px` and `600.5px`; not `100%`, not `auto`, not a negative. */
    private fun lengthOf(value: String): Int? =
        LENGTH.matchEntire(value.trim())?.groupValues?.get(1)?.toIntOrNull()

    /**
     * Whether [source] would go to the network.
     *
     * Protocol-relative first, because `//tracker.example/pixel.gif` is a real spelling in mail and
     * it is the one that looks least like a URL.
     */
    private fun isRemote(source: String): Boolean =
        source.startsWith("//") ||
            source.startsWith("http://", ignoreCase = true) ||
            source.startsWith("https://", ignoreCase = true)

    /** The host of [url], for the alt text. Userinfo and port are dropped; they name nobody. */
    private fun hostOf(url: String): String? =
        url.substringAfter("//", missingDelimiterValue = "")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('@')
            .substringBefore(':')
            .takeIf { it.isNotBlank() }

    /** One attribute, with its value already unquoted. */
    private data class Attribute(val name: String, val value: String)

    private fun List<Attribute>.valueOf(name: String): String? = firstOrNull {
        it.name == name
    }
        ?.value

    /**
     * The attributes of a tag's interior, lower-cased by name.
     *
     * Anything between attributes that is not one — a stray self-closing slash, most often — simply
     * does not match, which is the wanted behaviour: `<img src="x" />` and `<img src=x/>` both come
     * out as the same single attribute.
     */
    private fun attributesOf(interior: String): List<Attribute> =
        ATTRIBUTE.findAll(interior)
            .map { match ->
                Attribute(
                    name = match.groupValues[1].lowercase(),
                    value = match.groupValues[2].trim('"', '\''),
                )
            }
            .toList()

    /** One of the two dimensions, with the two spellings a message can declare it in. */
    private class Axis(val attribute: String, val inStyle: Regex)

    /**
     * A 1×1 transparent GIF, byte for byte the web client's `RemoteContentBlocker::PLACEHOLDER`.
     *
     * A `data:` URI, so it makes no request and reaches the network under no circumstances.
     */
    private const val PIXEL =
        "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7"

    /**
     * The box a picture gets when the message declared no size for it, in CSS pixels.
     *
     * **This is the whole judgement in this file, and it is a compromise between two failures.** A
     * message that declares nothing tells us nothing, and the same silence covers a 600px hero
     * banner and a tracking pixel — so whatever is chosen is wrong for one of them. Large enough
     * and every undeclared tracker becomes a grey slab in the middle of somebody's mail; small
     * enough and a real picture collapses to a speck, which is the collapse this file exists to
     * stop.
     *
     * 48 is picked because it is unmistakably a deliberate placeholder rather than a rendering
     * fault, and because a tracker that failed to declare its size then costs one small square
     * instead of a paragraph of grey. Trackers overwhelmingly *do* declare `width="1" height="1"` —
     * being invisible is the point of one — and a declared size is honoured exactly, with no floor
     * under it, so the common tracker stays the 1×1 nothing it asked to be. That is the one place
     * this departs from the web, which floors every placeholder at 12px and so repaints a tracking
     * pixel into something visible; a mail client should not make a tracker *more* prominent than
     * its sender intended it to be.
     */
    private const val FALLBACK_PX = 48

    private const val TAG_NAME_LENGTH = 4
    private const val PARKED_SRCSET = "data-plmail-srcset"

    /** Attributes this file writes itself, so a copy arriving from the sender is dropped first. */
    private val REPLACED = setOf("src", "srcset", "style", "alt", MARKER, PARKED_SRCSET)

    private val ATTRIBUTE =
        Regex("""([A-Za-z_:][-.\w:]*)\s*(?:=\s*("[^"]*"|'[^']*'|[^\s"'=<>]+))?""")

    private val LENGTH = Regex("""(\d+)(?:\.\d+)?\s*(?:px)?""", RegexOption.IGNORE_CASE)

    // Anchored on the start of a declaration, so `max-width` and `min-height`
    // are not read as the properties they end with -- a newsletter capping its
    // hero at `max-width: 640px` declares nothing about that picture's size.
    private val WIDTH =
        Axis("width", Regex("""(?:^|;)\s*width\s*:\s*([^;]+)""", RegexOption.IGNORE_CASE))
    private val HEIGHT =
        Axis("height", Regex("""(?:^|;)\s*height\s*:\s*([^;]+)""", RegexOption.IGNORE_CASE))
}
