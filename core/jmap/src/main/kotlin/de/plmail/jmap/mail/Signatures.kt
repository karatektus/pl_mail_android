package de.plmail.jmap.mail

/**
 * The sign-off block, and the surgery that swaps it without touching what someone has written.
 *
 * plMail's server stores a signature per sending address and now publishes it on `Identity` as
 * [Identity.htmlSignature] — but **it does not append it to anything**. `MessageSendService` never
 * sees it and `JmapDraftWriter` never inserts it: the web's composer puts the block into the body
 * at draft time, and a JMAP client that does not do the same sends unsigned mail from an account
 * whose browser signs every message. So this is not decoration; it is the client's half of a
 * feature the server considers finished.
 *
 * ## The marker, and why it is the web's exactly
 *
 * `<div class="pl-signature" data-pl-signature>…</div>` is what `SignatureProvider::block()` emits
 * and what `compose_controller.js` scopes its swap to. Matching it byte for byte is what lets a
 * draft started on the phone be re-opened in the browser and still have its From menu swap the
 * signature rather than append a second one — and the reverse, which is the case this module is
 * actually tested against.
 *
 * ## Why a scanner rather than a regular expression
 *
 * A signature is arbitrary user HTML and routinely contains `<div>`s — a table of contact details,
 * a logo wrapped for alignment. A regex ending at the first `</div>` therefore cuts a signature in
 * half and leaves the tail of the previous one welded to the front of the new one, which is exactly
 * the class of bug that makes people stop trusting the From menu. [replaceSignature] counts depth
 * instead.
 */
object Signatures {

    /** The attribute the block is found by, and the one the web scopes its own swap to. */
    private const val MARKER = "data-pl-signature"

    private const val OPEN = "<div class=\"pl-signature\" $MARKER>"
    private const val CLOSE = "</div>"

    /**
     * Wraps a signature in the marker, or answers empty for an address that signs with nothing.
     *
     * "Signs with nothing" is a real state rather than a missing one — plMail distinguishes an
     * alias that inherits the account's signature from one explicitly set to none — so a blank
     * value must produce no block at all rather than an empty one. An empty marked block would be
     * swapped for the next address's signature quite happily, but it would also be a stray `div` in
     * every message the user sends from that address.
     */
    fun block(html: String?): String = if (html.isNullOrBlank()) "" else OPEN + html + CLOSE

    /**
     * Puts [signatureHtml] where the body's signature is, or at the end if it has none.
     *
     * The whole contract in one sentence: **everything outside the block is returned untouched.**
     * Changing the From address must never cost somebody a paragraph they have already typed, and
     * that is the only reason the block carries a marker at all.
     *
     * Appending to the end rather than at the caret, unlike the web's toolbar button. There is no
     * caret to respect here — this runs when a draft opens and when the sender changes, never in
     * response to somebody pointing at a spot — and the end of the body is above the quote, which
     * is where a sign-off belongs on a reply.
     *
     * A body with no signature and an empty new signature is returned as-is rather than gaining a
     * separator, which is what makes this safe to call unconditionally on every From change.
     */
    fun replaceSignature(bodyHtml: String, signatureHtml: String?): String {
        val block = block(signatureHtml)
        val existing = findBlock(bodyHtml)

        if (existing != null) {
            // Removing rather than replacing when the new address signs with
            // nothing: splicing "" in would leave the separator behind it, and
            // a message that ends in a stray blank line is the visible half of
            // this going wrong.
            return bodyHtml.substring(0, existing.first) + block + bodyHtml.substring(existing.last)
        }

        if (block.isEmpty()) return bodyHtml

        return if (bodyHtml.isBlank()) block else bodyHtml + SEPARATOR + block
    }

    /** The signature currently in this body, unwrapped, or null when there is none. */
    fun signatureIn(bodyHtml: String): String? {
        val found = findBlock(bodyHtml) ?: return null

        return bodyHtml.substring(found.first + OPEN.length, found.last - CLOSE.length)
    }

    /**
     * The half-open range the block occupies, or null.
     *
     * Anchored on the opening tag this object writes rather than on the marker alone. A body that
     * arrived from somewhere spelling the attributes in another order simply reads as having no
     * block, and the signature is appended — one duplicated sign-off in a corner nobody has
     * produced yet, rather than a splice at an offset computed from a tag this code did not
     * recognise.
     */
    private fun findBlock(bodyHtml: String): IntRange? {
        val start = bodyHtml.indexOf(OPEN)
        if (start < 0) return null

        var depth = 1
        var cursor = start + OPEN.length

        while (depth > 0) {
            val nextOpen = bodyHtml.indexOf(DIV_OPEN, cursor)
            val nextClose = bodyHtml.indexOf(CLOSE, cursor)

            // Unbalanced markup: the block was opened and never closed. Treated
            // as "no block", so the caller appends instead of splicing on an
            // end offset that does not exist.
            if (nextClose < 0) return null

            if (nextOpen in 0 until nextClose) {
                depth++
                cursor = nextOpen + DIV_OPEN.length
            } else {
                depth--
                cursor = nextClose + CLOSE.length
            }
        }

        return start..cursor
    }

    private const val DIV_OPEN = "<div"

    /**
     * What goes between the body and the signature when one is appended.
     *
     * An empty paragraph rather than a `<br>`, because the editor's own unit is the paragraph:
     * appending a bare break puts the sign-off inside whatever block the user's last sentence is
     * in, and pressing Enter afterwards then splits the signature rather than the sentence.
     */
    private const val SEPARATOR = "<p><br></p>"
}
