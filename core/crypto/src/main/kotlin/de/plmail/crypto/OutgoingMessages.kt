package de.plmail.crypto

import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import org.apache.james.mime4j.codec.EncoderUtil
import org.bouncycastle.openpgp.api.OpenPGPCertificate
import org.pgpainless.PGPainless
import org.pgpainless.algorithm.CompressionAlgorithm
import org.pgpainless.algorithm.DocumentSignatureType
import org.pgpainless.encryption_signing.EncryptionOptions
import org.pgpainless.encryption_signing.ProducerOptions
import org.pgpainless.encryption_signing.SigningOptions

/** An address on an outgoing message. */
data class MailAddress(val email: String, val name: String? = null)

/** A file inside an encrypted message. */
class OutgoingFile(val name: String, val type: String, val bytes: ByteArray)

/** What an encrypted message says, before it is built. */
data class OutgoingContent(
    val from: MailAddress,
    val to: List<MailAddress> = emptyList(),
    val cc: List<MailAddress> = emptyList(),
    val bcc: List<MailAddress> = emptyList(),
    val subject: String = "",
    /** The body as HTML, or null for a text-only message. */
    val html: String? = null,
    /** The body as text; for an HTML message, the text derived from it. */
    val text: String = "",
    val files: List<OutgoingFile> = emptyList(),
    /** With its angle brackets. */
    val messageId: String,
    val date: ZonedDateTime,
    /** Message-IDs, with their angle brackets. */
    val inReplyTo: List<String> = emptyList(),
    val references: List<String> = emptyList(),
)

/**
 * Builds an end-to-end encrypted message on the device (#9, phase 4): the inner MIME entity, signed
 * and encrypted with OpenPGP, inside a PGP/MIME message (RFC 3156) ready for `Email/import`.
 *
 * The inner entity is built the way the web app builds it (`assets/crypto/mime.js`), so a message
 * sent from the phone and one sent from a browser look the same to whoever opens them: CRLF line
 * ends, every leaf base64, `multipart/alternative` for text and HTML, `multipart/mixed` for files,
 * and the protected headers of RFC 9788 at the top — Subject, From, To and Cc, with
 * `protected-headers="v1"` on the first Content-Type. The subject outside is then `...`.
 *
 * The encryption is the web app's too: to every recipient and to the sender's own key, signed with
 * the sender's key, armored, uncompressed. With Bcc recipients the key IDs are hidden, so nobody
 * can tell from the ciphertext who else it was encrypted to.
 */
object OutgoingMessages {

    /** What an encrypting client puts in the outer Subject when the real one is inside. */
    const val PROTECTED_SUBJECT = "..."

    /**
     * The whole message: outer headers and the PGP/MIME body, encrypted to [recipients] and to
     * [signer]'s own key, and signed by [signer].
     */
    fun seal(
        content: OutgoingContent,
        recipients: List<OpenPGPCertificate>,
        signer: UnlockedAddressKey,
        protectSubject: Boolean = true,
    ): ByteArray {
        val armored =
            encrypt(
                entity(content, protectHeaders = protectSubject),
                recipients + signer.key.toCertificate(),
                signer,
                hideRecipients = content.bcc.isNotEmpty(),
            )

        return pgpMime(content, armored, protectSubject)
    }

    // ------------------------------------------------------------- the entity

    /** The inner MIME entity, as the web app's `buildEntity` writes it. */
    internal fun entity(content: OutgoingContent, protectHeaders: Boolean): ByteArray {
        val body = StringBuilder()
        val first = buildBody(content, body)

        val out = StringBuilder()

        if (protectHeaders) {
            // Empty fields left out, as the web app leaves them out.
            if (content.subject.isNotBlank()) header(out, "Subject", encodedText(content.subject))
            header(out, "From", content.from.email.lowercase(Locale.ROOT))
            addressList(content.to.map { it.email.lowercase(Locale.ROOT) })?.let {
                header(out, "To", it)
            }
            addressList(content.cc.map { it.email.lowercase(Locale.ROOT) })?.let {
                header(out, "Cc", it)
            }
        }

        // The first Content-Type line is the entity's own; the protected
        // headers are marked on it.
        val withMarker =
            if (protectHeaders) {
                CONTENT_TYPE_LINE.replaceFirst(first, "$0; protected-headers=\"v1\"")
            } else {
                first
            }

        out.append(withMarker)
        out.append(body)

        return out.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Writes the entity's own headers and its body: the first is returned, so the protected-headers
     * marker can go on it, and the rest is appended to [body].
     */
    private fun buildBody(content: OutgoingContent, body: StringBuilder): String {
        val text = leaf("text/plain; charset=utf-8", content.text.toByteArray(Charsets.UTF_8))
        val readable =
            content.html?.let { html ->
                multipart(
                    "alternative",
                    listOf(
                        text,
                        leaf("text/html; charset=utf-8", html.toByteArray(Charsets.UTF_8)),
                    ),
                )
            } ?: text

        val whole =
            if (content.files.isEmpty()) {
                readable
            } else {
                multipart("mixed", listOf(readable) + content.files.map(::attachment))
            }

        val split = whole.indexOf(CRLF + CRLF)
        body.append(whole, split, whole.length)

        return whole.substring(0, split)
    }

    private fun leaf(type: String, bytes: ByteArray, extra: List<String> = emptyList()): String =
        buildString {
            append("Content-Type: ").append(type).append(CRLF)
            extra.forEach { append(it).append(CRLF) }
            append("Content-Transfer-Encoding: base64").append(CRLF)
            append(CRLF)
            append(base64Lines(bytes))
        }

    private fun attachment(file: OutgoingFile): String {
        val (nameParam, filenameParam) = fileParameters(file.name)

        return leaf(
            "${file.type}; $nameParam",
            file.bytes,
            listOf("Content-Disposition: attachment; $filenameParam"),
        )
    }

    private fun multipart(subtype: String, parts: List<String>): String {
        val boundary = boundary()

        return buildString {
            append("Content-Type: multipart/").append(subtype)
            append("; boundary=\"").append(boundary).append('"').append(CRLF)
            append(CRLF)
            parts.forEach { part ->
                append("--").append(boundary).append(CRLF)
                append(part)
            }
            append("--").append(boundary).append("--").append(CRLF)
        }
    }

    /** `name="…"` and `filename="…"`, or their RFC 2231 forms for a name that is not ASCII. */
    private fun fileParameters(name: String): Pair<String, String> {
        val clean = name.replace(Regex("[\\r\\n\"\\\\]"), "_")

        if (clean.all { it.code in 0x20..0x7e }) {
            return "name=\"$clean\"" to "filename=\"$clean\""
        }

        val encoded = percentEncoded(clean)

        return "name*=utf-8''$encoded" to "filename*=utf-8''$encoded"
    }

    private fun percentEncoded(value: String): String = buildString {
        value.toByteArray(Charsets.UTF_8).forEach { byte ->
            val c = byte.toInt() and 0xff
            if (c.toChar().isLetterOrDigit() && c < 0x80 || c.toChar() in "-._~") {
                append(c.toChar())
            } else {
                append('%').append("%02X".format(c))
            }
        }
    }

    // ---------------------------------------------------------- the encryption

    /** Signs and encrypts [entity], armored, as openpgp.js `encrypt` with a signing key does. */
    internal fun encrypt(
        entity: ByteArray,
        recipients: List<OpenPGPCertificate>,
        signer: UnlockedAddressKey,
        hideRecipients: Boolean,
    ): String {
        val api = PGPainless.getInstance()
        val encryption = EncryptionOptions.encryptCommunications(api)

        recipients
            .distinctBy { it.keyIdentifier }
            .forEach { certificate ->
                if (hideRecipients) encryption.addHiddenRecipient(certificate)
                else encryption.addRecipient(certificate)
            }

        val signing =
            SigningOptions.get(api)
                .addInlineSignature(
                    signer.protector,
                    signer.key,
                    DocumentSignatureType.BINARY_DOCUMENT,
                )

        val options =
            ProducerOptions.signAndEncrypt(encryption, signing)
                .setAsciiArmor(true)
                .setHideArmorHeaders(true)
                // As openpgp.js writes it: the entity is base64 already, and
                // compression has been a side channel before.
                .overrideCompressionAlgorithm(CompressionAlgorithm.UNCOMPRESSED)

        val out = ByteArrayOutputStream()
        api.generateMessage().onOutputStream(out).withOptions(options).use { it.write(entity) }

        return out.toString(Charsets.UTF_8.name())
    }

    // ---------------------------------------------------------- the envelope

    /**
     * The message around the ciphertext: headers plMail takes the draft's addresses, subject and
     * threading from, and the two parts of RFC 3156 §4.
     */
    private fun pgpMime(
        content: OutgoingContent,
        armored: String,
        protectSubject: Boolean,
    ): ByteArray {
        val boundary = boundary()
        val out = outerHeaders(content, protectSubject)

        header(
            out,
            "Content-Type",
            "multipart/encrypted; protocol=\"application/pgp-encrypted\";$CRLF boundary=\"$boundary\"",
        )
        out.append(CRLF)
        out.append("This is an OpenPGP/MIME encrypted message (RFC 4880 and 3156)").append(CRLF)
        out.append("--").append(boundary).append(CRLF)
        out.append("Content-Type: application/pgp-encrypted").append(CRLF)
        out.append("Content-Description: PGP/MIME version identification").append(CRLF)
        out.append(CRLF)
        out.append("Version: 1").append(CRLF)
        out.append(CRLF)
        out.append("--").append(boundary).append(CRLF)
        out.append("Content-Type: application/octet-stream; name=\"encrypted.asc\"").append(CRLF)
        out.append("Content-Description: OpenPGP encrypted message").append(CRLF)
        out.append("Content-Disposition: inline; filename=\"encrypted.asc\"").append(CRLF)
        out.append(CRLF)
        out.append(armored.replace(Regex("\r\n|\r|\n"), CRLF).trimEnd()).append(CRLF)
        out.append(CRLF)
        out.append("--").append(boundary).append("--").append(CRLF)

        return out.toString().toByteArray(Charsets.UTF_8)
    }

    // ---------------------------------------------------------------- S/MIME

    /**
     * The whole message for S/MIME (#47), as the web app's `smimeEncryptEntity` builds it: the
     * inner entity signed — `multipart/signed`, SHA-256 with RSA, the signer named by issuer and
     * serial, its certificate and chain carried — and that signed entity enveloped with AES-256-CBC
     * and RSA PKCS#1 v1.5 key transport, to every recipient's certificate and the sender's own.
     * What Outlook and Thunderbird read.
     */
    fun sealSmime(
        content: OutgoingContent,
        recipients: List<String>,
        signer: UnlockedSmimeKey,
        protectSubject: Boolean = true,
    ): ByteArray {
        val signed = SmimeOutgoing.sign(entity(content, protectHeaders = protectSubject), signer)
        val envelope = SmimeOutgoing.envelope(signed, recipients, signer)

        val out = outerHeaders(content, protectSubject)
        header(
            out,
            "Content-Type",
            "application/pkcs7-mime; smime-type=enveloped-data;$CRLF name=\"smime.p7m\"",
        )
        header(out, "Content-Transfer-Encoding", "base64")
        header(out, "Content-Disposition", "attachment; filename=\"smime.p7m\"")
        header(out, "Content-Description", "S/MIME Encrypted Message")
        out.append(CRLF)
        out.append(base64Lines(envelope))

        return out.toString().toByteArray(Charsets.UTF_8)
    }

    /** The headers plMail files a draft by: addresses, subject, date and threading. */
    private fun outerHeaders(content: OutgoingContent, protectSubject: Boolean): StringBuilder {
        val out = StringBuilder()

        header(out, "From", address(content.from))
        addressList(content.to.map(::address))?.let { header(out, "To", it) }
        addressList(content.cc.map(::address))?.let { header(out, "Cc", it) }
        addressList(content.bcc.map(::address))?.let { header(out, "Bcc", it) }
        when {
            protectSubject -> header(out, "Subject", PROTECTED_SUBJECT)
            content.subject.isNotBlank() -> header(out, "Subject", encodedText(content.subject))
        }
        header(out, "Date", DateTimeFormatter.RFC_1123_DATE_TIME.format(content.date))
        header(out, "Message-ID", content.messageId)
        if (content.inReplyTo.isNotEmpty()) {
            header(out, "In-Reply-To", content.inReplyTo.joinToString(" "))
        }
        if (content.references.isNotEmpty()) {
            header(out, "References", folded(content.references, " "))
        }
        header(out, "MIME-Version", "1.0")

        return out
    }

    // ---------------------------------------------------------------- helpers

    private fun header(out: StringBuilder, name: String, value: String) {
        out.append(name).append(": ").append(value).append(CRLF)
    }

    private fun address(address: MailAddress): String {
        val email = address.email.trim()
        val name = address.name?.trim()?.takeIf { it.isNotEmpty() && it != email }

        return if (name == null) email else EncoderUtil.encodeAddressDisplayName(name) + " <$email>"
    }

    private fun addressList(addresses: List<String>): String? =
        addresses.takeIf { it.isNotEmpty() }?.let { folded(it, ", ") }

    /** Joins [items], starting a new line before one that would run past 76 characters. */
    private fun folded(items: List<String>, separator: String): String = buildString {
        var line = 0
        items.forEachIndexed { index, item ->
            if (index > 0) {
                if (line + separator.length + item.length > FOLD_AT) {
                    append(separator.trimEnd()).append(CRLF).append(' ')
                    line = 1
                } else {
                    append(separator)
                    line += separator.length
                }
            }
            append(item)
            line += item.length
        }
    }

    /** RFC 2047 B encoded words for text that is not plain ASCII, folded between them. */
    internal fun encodedText(text: String): String {
        val clean = text.replace(Regex("[\\r\\n]+"), " ")
        if (clean.all { it.code in 0x20..0x7e }) return clean

        val words = mutableListOf<String>()
        val chunk = StringBuilder()
        var bytes = 0
        var index = 0

        while (index < clean.length) {
            val codePoint = clean.codePointAt(index)
            val char = String(Character.toChars(codePoint))
            val size = char.toByteArray(Charsets.UTF_8).size

            if (bytes + size > WORD_BYTES && chunk.isNotEmpty()) {
                words += word(chunk.toString())
                chunk.clear()
                bytes = 0
            }

            chunk.append(char)
            bytes += size
            index += Character.charCount(codePoint)
        }
        if (chunk.isNotEmpty()) words += word(chunk.toString())

        return words.joinToString("$CRLF ")
    }

    private fun word(text: String): String =
        "=?utf-8?B?" + Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8)) + "?="

    internal fun base64Lines(bytes: ByteArray): String =
        Base64.getMimeEncoder(LINE, CRLF.toByteArray()).encodeToString(bytes).let {
            if (it.isEmpty()) "" else it + CRLF
        }

    internal fun boundary(): String {
        val bytes = ByteArray(16).also { random.nextBytes(it) }

        return "----=_plmail_" + bytes.joinToString("") { "%02x".format(it) }
    }

    /** A Message-ID at the sender's domain, as the server mints them. */
    fun messageId(from: String): String {
        val domain = from.substringAfterLast('@', "plmail.invalid").ifBlank { "plmail.invalid" }
        val bytes = ByteArray(16).also { random.nextBytes(it) }

        return "<" + bytes.joinToString("") { "%02x".format(it) } + "@" + domain + ">"
    }

    private val random = SecureRandom()

    private val CONTENT_TYPE_LINE = Regex("^Content-Type: [^\r\n]*")

    private const val CRLF = "\r\n"
    private const val LINE = 76
    private const val FOLD_AT = 76

    /** UTF-8 bytes per encoded word: 45 bytes is 60 base64 characters, 72 with the frame. */
    private const val WORD_BYTES = 45
}
