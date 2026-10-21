package de.plmail.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.apache.james.mime4j.codec.DecodeMonitor
import org.apache.james.mime4j.dom.BinaryBody
import org.apache.james.mime4j.dom.Body
import org.apache.james.mime4j.dom.Entity
import org.apache.james.mime4j.dom.Message
import org.apache.james.mime4j.dom.Multipart
import org.apache.james.mime4j.dom.SingleBody
import org.apache.james.mime4j.dom.TextBody
import org.apache.james.mime4j.message.DefaultMessageBuilder
import org.apache.james.mime4j.stream.MimeConfig
import org.bouncycastle.openpgp.PGPException
import org.bouncycastle.openpgp.api.OpenPGPCertificate
import org.pgpainless.PGPainless
import org.pgpainless.decryption_verification.ConsumerOptions
import org.pgpainless.decryption_verification.MessageMetadata

/**
 * An end-to-end encrypted message, opened on the device (#9, phase 2).
 *
 * The same reading the web app does (`assets/crypto/message.js` in the server repository), in the
 * same order:
 * 1. Find the ciphertext in the message as it arrived: the armored `encrypted.asc` part of a
 *    PGP/MIME message, or the armor in the body of an inline one.
 * 2. Decrypt it with every address key the device can open — old and replaced ones too, since mail
 *    encrypted to a key that has since been renewed is still the person's mail.
 * 3. Check the signature inside, against the person's own keys and the sender's contact keys.
 * 4. For PGP/MIME, parse the decrypted MIME entity: its text and HTML, its parts, and the subject
 *    the sender protected (RFC 9788) behind a placeholder like `...` on the outside.
 *
 * Nothing here sanitises HTML. [OpenedMessage.html] is exactly what the sender wrote; the caller
 * must clean it before it goes anywhere near a renderer, because — unlike every other body the app
 * shows — no server has looked at it.
 */
object EncryptedMessages {

    private const val BEGIN = "-----BEGIN PGP MESSAGE-----"
    private const val END = "-----END PGP MESSAGE-----"

    /**
     * Decrypts [raw], the message exactly as it arrived (the Email's blob), or throws
     * [DecryptionException].
     *
     * [verifiers] are the certificates a signature inside may be checked against: the person's own
     * address keys and whatever contact keys the server holds for the sender.
     */
    fun open(
        raw: ByteArray,
        keys: List<UnlockedAddressKey>,
        verifiers: List<OpenPGPCertificate> = emptyList(),
    ): OpenedMessage {
        val outer = parse(raw)
        val ciphertext = ciphertextOf(outer) ?: throw DecryptionException(Reason.NO_CIPHERTEXT)

        if (keys.isEmpty()) throw DecryptionException(Reason.NO_KEY)

        val (plaintext, metadata) = decrypt(ciphertext.armor, keys, verifiers)
        val signature = signatureOf(metadata, verifiers)

        if (ciphertext.isInline) {
            // OpenPGP's text mode canonicalises line endings to CRLF on the
            // wire; the reader wants them as typed.
            val text = String(plaintext, Charsets.UTF_8).replace("\r\n", "\n")

            return OpenedMessage(
                subject = null,
                html = null,
                text = text,
                parts = emptyList(),
                signature = signature,
                mime = inlineEntity(text),
            )
        }

        var inner = parse(plaintext)

        // RFC 3156 signed-then-encrypted, as Enigmail and older Thunderbird
        // write it: a multipart/signed entity inside the encryption. Read like
        // the web app reads it -- the content shown, its signature reported as
        // not checked rather than claimed either way.
        val innerSigned = inner.isMimeType("multipart/signed")
        val content: Entity =
            if (innerSigned) {
                (inner.body as? Multipart)?.bodyParts?.firstOrNull() ?: inner
            } else {
                inner
            }

        val collected = Collected()
        collect(content, collected)

        return OpenedMessage(
            subject = inner.subject?.trim()?.takeIf { it.isNotEmpty() },
            html = collected.html,
            text = collected.text,
            parts = collected.parts,
            signature = if (innerSigned) InnerSignature.Unchecked else signature,
            mime = plaintext,
        )
    }

    /**
     * Inline PGP decrypts to text, not to a message. For the server it is wrapped in the one MIME
     * entity that says so — byte for byte the web app's `inlineEntity` — so a decrypted copy handed
     * over from this device and one from a browser are the same thing.
     */
    private fun inlineEntity(text: String): ByteArray =
        ("Content-Type: text/plain; charset=utf-8\r\nContent-Transfer-Encoding: 8bit\r\n\r\n" +
                text.replace(Regex("\r\n|\r|\n"), "\r\n"))
            .toByteArray(Charsets.UTF_8)

    /** Whether [subject] is one of the placeholders an encrypting client puts on the outside. */
    fun isPlaceholderSubject(subject: String?): Boolean =
        subject != null && PLACEHOLDER.matches(subject)

    private val PLACEHOLDER =
        Regex("""^\s*(\.\.\.|\[\.\.\.]|encrypted message)\s*$""", RegexOption.IGNORE_CASE)

    // ------------------------------------------------------------- ciphertext

    private class Ciphertext(val armor: ByteArray, val isInline: Boolean)

    /**
     * The armored ciphertext, wherever the sender put it.
     *
     * PGP/MIME first: the second part of a `multipart/encrypted` message. Then any part whose text
     * carries the armor at the start of a line, which is inline PGP — in a text part, or in an HTML
     * one whose markup has to be taken off the armor first.
     */
    private fun ciphertextOf(message: Message): Ciphertext? {
        if (message.isMimeType("multipart/encrypted")) {
            val parts = (message.body as? Multipart)?.bodyParts.orEmpty()
            parts
                .firstOrNull { !it.isMimeType("application/pgp-encrypted") }
                ?.let { bytes(it.body) }
                ?.let { armorIn(String(it, Charsets.ISO_8859_1)) }
                ?.let {
                    return Ciphertext(it, isInline = false)
                }
        }

        return leaves(message).firstNotNullOfOrNull { leaf ->
            val text = text(leaf) ?: return@firstNotNullOfOrNull null
            val plain = if (leaf.isMimeType("text/html")) unmarkup(text) else text
            armorIn(plain)?.let { Ciphertext(it, isInline = true) }
        }
    }

    private fun armorIn(text: String): ByteArray? {
        val start = text.indexOf(BEGIN)
        if (start < 0) return null
        val end = text.indexOf(END, start)
        if (end < 0) return null

        return text.substring(start, end + END.length).toByteArray(Charsets.ISO_8859_1)
    }

    /** Enough of HTML undone that an armor block pasted into a rich-text editor reads as one. */
    private fun unmarkup(html: String): String =
        html
            .replace(Regex("(?i)<br\\s*/?>|</p>|</div>"), "\n")
            .replace(Regex("<[^>]*>"), "")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")

    // ------------------------------------------------------------- decryption

    private fun decrypt(
        armor: ByteArray,
        keys: List<UnlockedAddressKey>,
        verifiers: List<OpenPGPCertificate>,
    ): Pair<ByteArray, MessageMetadata> {
        val api = PGPainless.getInstance()
        val options = ConsumerOptions.get(api)
        keys.forEach { options.addDecryptionKey(it.key, it.protector) }
        if (verifiers.isNotEmpty()) options.addVerificationCerts(verifiers)

        return try {
            val stream =
                api.processMessage().onInputStream(ByteArrayInputStream(armor)).withOptions(options)
            val plaintext = ByteArrayOutputStream()
            stream.use { it.copyTo(plaintext) }

            plaintext.toByteArray() to stream.metadata
        } catch (noKey: PGPException) {
            throw DecryptionException(Reason.NO_KEY, noKey)
        } catch (unreadable: IOException) {
            throw DecryptionException(Reason.MALFORMED, unreadable)
        } catch (other: RuntimeException) {
            // PGPainless raises its own unchecked exceptions for a message
            // nothing here can open ("missing decryption method") as well as
            // for a broken one; either way, this device cannot read it.
            throw DecryptionException(Reason.NO_KEY, other)
        }
    }

    /**
     * The signature inside the encryption, as the evidence stands — whose key it was made with and
     * whether that key is one the caller supplied. What that *means* (the person's own key, a
     * pinned contact, a key that changed) is decided with the contact keys' status, by the caller.
     */
    private fun signatureOf(
        metadata: MessageMetadata,
        verifiers: List<OpenPGPCertificate>,
    ): InnerSignature {
        metadata.verifiedSignatures.firstOrNull()?.let { verified ->
            return InnerSignature.Verified(verified.signingKey.certificateFingerprint.hex())
        }

        val rejected = metadata.rejectedSignatures.firstOrNull() ?: return InnerSignature.None
        val signature = rejected.signature
        val known = verifiers.firstOrNull { cert ->
            cert.keys.any { it.pgpPublicKey.keyID == signature.keyID }
        }

        return if (known == null) {
            InnerSignature.UnknownKey(keyId = "%016x".format(signature.keyID))
        } else {
            InnerSignature.Invalid(
                known.primaryKey.pgpPublicKey.fingerprint.joinToString("") { "%02x".format(it) }
            )
        }
    }

    private fun org.pgpainless.key.OpenPgpFingerprint.hex(): String = toString().lowercase()

    // ------------------------------------------------------------------- MIME

    private fun parse(bytes: ByteArray): Message {
        val builder =
            DefaultMessageBuilder().apply {
                setMimeEntityConfig(
                    MimeConfig.Builder()
                        .setMaxLineLen(-1)
                        .setMaxHeaderLen(-1)
                        .setMaxHeaderCount(-1)
                        .build()
                )
                setDecodeMonitor(DecodeMonitor.SILENT)
            }

        return try {
            builder.parseMessage(ByteArrayInputStream(bytes))
        } catch (unparseable: IOException) {
            throw DecryptionException(Reason.MALFORMED, unparseable)
        }
    }

    private class Collected {
        var html: String? = null
        var text: String? = null
        val parts = mutableListOf<OpenedPart>()
    }

    /**
     * Walks the decrypted entity: the first HTML and the first plain text that are the message
     * itself, and every other leaf as a part.
     */
    private fun collect(entity: Entity, into: Collected) {
        val body = entity.body

        if (body is Multipart) {
            body.bodyParts.forEach { collect(it, into) }
            return
        }

        val isAttachment = entity.dispositionType.equals("attachment", ignoreCase = true)

        if (!isAttachment && entity.isMimeType("text/html") && into.html == null) {
            into.html = text(entity)
            return
        }
        if (!isAttachment && entity.isMimeType("text/plain") && into.text == null) {
            into.text = text(entity)
            return
        }
        // The signature of an RFC 3156 entity, if one reached this far, is
        // not content.
        if (entity.isMimeType("application/pgp-signature")) return

        val cid =
            entity.header
                ?.getField("Content-ID")
                ?.body
                ?.trim()
                ?.removePrefix("<")
                ?.removeSuffix(">")

        into.parts +=
            OpenedPart(
                name = entity.filename,
                type = entity.mimeType ?: "application/octet-stream",
                cid = cid?.takeIf { it.isNotEmpty() },
                isInline =
                    entity.dispositionType.equals("inline", ignoreCase = true) ||
                        (entity.dispositionType == null && cid != null),
                bytes = bytes(body) ?: ByteArray(0),
            )
    }

    private fun leaves(entity: Entity): List<Entity> =
        (entity.body as? Multipart)?.bodyParts?.flatMap(::leaves) ?: listOf(entity)

    private fun Entity.isMimeType(type: String): Boolean = mimeType.equals(type, ignoreCase = true)

    private fun text(entity: Entity): String? =
        when (val body = entity.body) {
            is TextBody -> body.reader.use { it.readText() }
            is BinaryBody -> bytes(body)?.toString(Charsets.UTF_8)
            else -> null
        }

    private fun bytes(body: Body?): ByteArray? =
        (body as? SingleBody)?.inputStream?.use { it.readBytes() }
}

/** What opening an encrypted message produced. Held in memory; never written down decrypted. */
class OpenedMessage(
    /** The subject protected inside (RFC 9788), or null when the inside carries none. */
    val subject: String?,
    /** The sender's HTML exactly as written. Not sanitised: see [EncryptedMessages]. */
    val html: String?,
    val text: String?,
    val parts: List<OpenedPart>,
    val signature: InnerSignature,
    /**
     * The decrypted message whole, as a MIME entity: what an account that keeps decrypted mail on
     * the server is handed (`DecryptedMail/store`). For inline PGP, the text wrapped as the web app
     * wraps it.
     */
    val mime: ByteArray = ByteArray(0),
) {
    override fun toString(): String =
        "OpenedMessage(html=${html != null}, text=${text != null}, parts=${parts.size}, $signature)"
}

/** A part of a decrypted message: an attachment, or an image the HTML refers to by `cid:`. */
class OpenedPart(
    val name: String?,
    val type: String,
    val cid: String?,
    val isInline: Boolean,
    val bytes: ByteArray,
) {
    override fun toString(): String = "OpenedPart($name, $type, ${bytes.size} bytes)"
}

/** The signature inside the encryption, before anybody has decided what to make of it. */
sealed interface InnerSignature {
    data object None : InnerSignature

    /** It checks out against the key with this primary fingerprint (lower-case hex). */
    data class Verified(val fingerprint: String) : InnerSignature

    /** Made with a key none of the supplied certificates holds. */
    data class UnknownKey(val keyId: String) : InnerSignature

    /** Made with a key that was supplied, and it does not match. */
    data class Invalid(val fingerprint: String) : InnerSignature

    /** RFC 3156 signed inside the encryption; shown, and not checked. */
    data object Unchecked : InnerSignature
}

class DecryptionException(val reason: Reason, cause: Throwable? = null) :
    Exception(reason.name, cause)

enum class Reason {
    /** Nothing in the message looks like OpenPGP ciphertext. */
    NO_CIPHERTEXT,

    /** None of the device's keys opens it — or it has none. */
    NO_KEY,

    /** The ciphertext or the MIME inside it could not be read. */
    MALFORMED,
}
