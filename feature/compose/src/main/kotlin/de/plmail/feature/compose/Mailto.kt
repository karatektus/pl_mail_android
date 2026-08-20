package de.plmail.feature.compose

import java.io.ByteArrayOutputStream

/**
 * `mailto:` links, read as a message to open the composer on.
 *
 * The grammar is RFC 6068's and it is smaller than it looks: everything between the scheme and a
 * `?` is a comma-separated list of addresses, and everything after it is `name=value` pairs joined
 * by `&`. Of the header names a link may carry, five are worth acting on — `to`, `cc`, `bcc`,
 * `subject` and `body` — and the rest are ignored here for the reason every other mail client
 * ignores them: a link that can set `From` or `Reply-To` is a link that can compose mail in
 * somebody else's name from a page the user merely visited.
 *
 * **A string rather than a `Uri`.** The caller has one and hands over `toString()`. That keeps this
 * file free of Android, so the parsing is covered by a plain JVM test rather than by Robolectric —
 * which matters, because the parsing is the half that breaks and a suite that needs an
 * emulator-shaped runtime is a suite that gets thinned out.
 *
 * **`+` is a plus sign, never a space.** RFC 6068 percent-encodes and says nothing about form
 * encoding, and the decisive case is local: `anna+lists@example.org` is how a good part of this
 * app's audience filters their own mail, and `URLDecoder` would hand back `anna lists@example.org`
 * — an address with a space in it, which [parseAddresses] then drops on the floor. A space that
 * survives as a literal `+` in a subject line is a cosmetic loss; a recipient that silently
 * disappears is not.
 */
internal object Mailto {

    /** The message this link asks for, or null when [uri] is not a `mailto:` at all. */
    fun parse(uri: String?): SharedMessage? {
        val text = uri?.trim().orEmpty()
        if (!text.startsWith(SCHEME, ignoreCase = true)) return null

        val rest = text.substring(SCHEME.length)
        val mark = rest.indexOf('?')
        val head = if (mark < 0) rest else rest.substring(0, mark)
        val fields = if (mark < 0) "" else rest.substring(mark + 1)

        // `mailto:` with nothing after it is a legal link and means "a blank
        // message", which is what an empty list produces here.
        val to = head.splitAddresses().toMutableList()
        val cc = mutableListOf<String>()
        val bcc = mutableListOf<String>()
        var subject = ""
        var body = ""

        fields.split('&').forEach { field ->
            if (field.isEmpty()) return@forEach

            val equals = field.indexOf('=')
            // Case-insensitive, because links in the wild are written by hand
            // and `?Subject=` is at least as common as `?subject=`.
            val name =
                (if (equals < 0) field else field.substring(0, equals))
                    .decodePercent()
                    .trim()
                    .lowercase()
            val value = if (equals < 0) "" else field.substring(equals + 1)

            when (name) {
                // Added to the ones before the `?` rather than replacing them:
                // RFC 6068 allows both spellings and a link may use either or
                // both, so the two lists are one list.
                "to" -> to += value.splitAddresses()
                "cc" -> cc += value.splitAddresses()
                "bcc" -> bcc += value.splitAddresses()
                "subject" -> subject = value.decodePercent()
                "body" -> body = value.decodePercent()
                else -> Unit
            }
        }

        return SharedMessage(to = to, cc = cc, bcc = bcc, subject = subject, text = body)
    }

    private const val SCHEME = "mailto:"
}

/**
 * Splits a recipient list, then decodes what it split.
 *
 * That order, and it is the whole reason this is not one line. Decoding first would let a `%2C`
 * inside a quoted display name become a separator and cut one recipient into two halves, neither of
 * which has an `@` and both of which are then dropped. Splitting first leaves a percent-encoded
 * comma inside the address it belongs to.
 */
private fun String.splitAddresses(): List<String> =
    split(',').map { it.decodePercent().trim() }.filter { it.isNotEmpty() }

/**
 * Percent-decoding, over bytes rather than over characters.
 *
 * A run of escapes is gathered before any of it is decoded, because one character can be up to four
 * of them: `%C3%A4` is a single `ä`, and decoding each escape on its own produces two replacement
 * marks instead. Every German subject line in a `mailto:` comes through here.
 *
 * A `%` not followed by two hex digits is kept as itself. A hand-written link with a per cent sign
 * in the subject is far likelier than a truncated escape, and the alternative — throwing — would
 * turn a cosmetic defect in somebody else's HTML into a composer that refuses to open.
 */
internal fun String.decodePercent(): String {
    if ('%' !in this) return this

    val out = StringBuilder(length)
    val bytes = ByteArrayOutputStream(LONGEST_CHARACTER)
    var index = 0

    while (index < length) {
        if (this[index] != '%') {
            out.append(this[index])
            index++
            continue
        }

        val high = hexAt(index + 1)
        val low = hexAt(index + 2)

        if (high < 0 || low < 0) {
            out.append('%')
            index++
            continue
        }

        bytes.write((high shl 4) or low)
        index += 3

        while (index < length && this[index] == '%') {
            val nextHigh = hexAt(index + 1)
            val nextLow = hexAt(index + 2)
            if (nextHigh < 0 || nextLow < 0) break

            bytes.write((nextHigh shl 4) or nextLow)
            index += 3
        }

        // Malformed UTF-8 becomes U+FFFD rather than an exception, which is what
        // this constructor does and what is wanted here: a link from a broken
        // encoder still opens a composer, with one visibly wrong character in it.
        out.append(String(bytes.toByteArray(), Charsets.UTF_8))
        bytes.reset()
    }

    return out.toString()
}

/**
 * The hex value of the character at [index], or -1 for anything else, the end of the string
 * included.
 */
private fun String.hexAt(index: Int): Int =
    if (index >= length) -1 else Character.digit(this[index], 16)

/** Enough for one four-byte character before the buffer has to grow. */
private const val LONGEST_CHARACTER = 4
