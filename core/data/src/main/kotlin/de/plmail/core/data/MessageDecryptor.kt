package de.plmail.core.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import de.plmail.core.database.EmailEntity
import de.plmail.crypto.DecryptionException
import de.plmail.crypto.EncryptedMessages
import de.plmail.crypto.InnerSignature
import de.plmail.crypto.OpenedMessage
import de.plmail.crypto.OpenedPart
import de.plmail.crypto.Reason
import de.plmail.jmap.methods.ContactKey
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.openpgp.api.OpenPGPCertificate
import org.pgpainless.PGPainless

/**
 * Opens an end-to-end encrypted message for the reader (#9, phase 2).
 *
 * The ciphertext is the message as it arrived — the Email's blob — and is cached like any other
 * blob: it is ciphertext. What comes out is held by the caller in memory and nowhere else; the
 * documented rule for clients is that decrypted text is not written to disk unencrypted, and the
 * database stores only that a message *is* encrypted.
 *
 * The signature inside is judged the way the web app judges it (`signatureOf` in
 * `assets/crypto/message.js`): the person's own key is `valid`; a sender's current contact key is
 * `valid` if the person pinned it and `untrusted` otherwise; any other key the sender is known to
 * have used is `key_changed`; a key nobody knows is `unknown_key`, or `key_changed` when the sender
 * has a current key it is not.
 */
@Singleton
class MessageDecryptor
@Inject
constructor(
    private val blobs: BlobStore,
    private val encryption: EncryptionRepository,
) {

    suspend fun open(email: EmailEntity): DecryptionResult =
        open(email.accountKey, email.blobId, email.fromAddress)

    /**
     * The same, for a message known by its blob and sender rather than by its cached row: the
     * original a reply quotes, a draft being reopened.
     */
    suspend fun open(accountKey: String, blobId: String?, sender: String?): DecryptionResult {
        blobId ?: return DecryptionResult.Failed(DecryptionProblem.NO_SOURCE)
        var hadNoKeys = false

        return try {
            // Interactive: somebody is opening this message, which is the one
            // moment protected keys may ask for the person (phase 5).
            val unlocked = encryption.unlockedKeys(interactive = true)
            val keys = unlocked.addressKeys
            hadNoKeys = unlocked.isEmpty

            val raw =
                blobs.file(accountKey, blobId, name = SOURCE_NAME, type = SOURCE_TYPE).readBytes()
            val contacts = encryption.contactKeys().forAddress(sender.orEmpty())
            val own = keys.map { it.key.toCertificate() }

            val opened =
                withContext(Dispatchers.Default) {
                    EncryptedMessages.open(
                        raw,
                        keys,
                        verifiers = own + contacts.certificates(),
                        smimeKeys = unlocked.smimeKeys,
                    )
                }

            val signature =
                when (val inner = opened.signature) {
                    is InnerSignature.Certificate ->
                        certificateVerdict(
                            inner,
                            sender = sender.orEmpty(),
                            own = unlocked.smimeKeys.map { it.fingerprint },
                        )
                    else -> verdict(inner, ownFingerprints = keys.map { it.fingerprint }, contacts)
                }

            DecryptionResult.Opened(withContext(Dispatchers.Default) { present(opened, signature) })
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: KeysLocked) {
            DecryptionResult.Failed(DecryptionProblem.LOCKED)
        } catch (failed: DecryptionException) {
            DecryptionResult.Failed(
                when (failed.reason) {
                    Reason.NO_KEY ->
                        if (hadNoKeys) DecryptionProblem.NO_KEYS_HERE
                        else DecryptionProblem.NOT_FOR_THESE_KEYS
                    Reason.NO_CERTIFICATE -> DecryptionProblem.NO_CERTIFICATE
                    Reason.UNSUPPORTED -> DecryptionProblem.UNSUPPORTED_CIPHER
                    Reason.NO_CIPHERTEXT -> DecryptionProblem.NO_SOURCE
                    Reason.MALFORMED -> DecryptionProblem.FAILED
                }
            )
        } catch (_: IOException) {
            DecryptionResult.Failed(DecryptionProblem.UNREACHABLE)
        } catch (_: Exception) {
            DecryptionResult.Failed(DecryptionProblem.FAILED)
        }
    }

    private fun present(opened: OpenedMessage, signature: SignatureVerdict?): DecryptedContent {
        val html = opened.html?.takeIf { it.isNotBlank() }?.let(DecryptedHtml::sanitize)
        val referenced = html.orEmpty()

        // Parts the HTML shows by `cid:` belong to the body, not the attachment
        // list — as in the web app. A part with a Content-ID nobody refers to is
        // still a file the sender meant to send.
        val (images, files) =
            opened.parts.partition { part ->
                part.cid != null &&
                    "cid:${part.cid}" in referenced &&
                    part.type.startsWith("image/")
            }

        return DecryptedContent(
            subject = opened.subject,
            html = html ?: opened.text?.let(DecryptedHtml::fromText) ?: "",
            inlineImages = images.associateBy { it.cid!! },
            attachments = files,
            signature = signature,
        )
    }

    private fun verdict(
        signature: InnerSignature,
        ownFingerprints: List<String>,
        contacts: List<ContactKey>,
    ): SignatureVerdict? {
        val current = contacts.firstOrNull { it.isCurrent }

        return when (signature) {
            InnerSignature.None -> null
            InnerSignature.Unchecked -> SignatureVerdict(SignatureState.UNCHECKED, signer = null)
            // Judged by certificateVerdict; never handed here.
            is InnerSignature.Certificate ->
                SignatureVerdict(SignatureState.UNCHECKED, signature.fingerprint)
            is InnerSignature.Invalid ->
                SignatureVerdict(SignatureState.INVALID, signature.fingerprint)
            is InnerSignature.UnknownKey ->
                SignatureVerdict(
                    if (current == null) SignatureState.UNKNOWN_KEY else SignatureState.KEY_CHANGED,
                    signature.keyId,
                )
            is InnerSignature.Verified -> {
                val fingerprint = signature.fingerprint
                val state =
                    when {
                        ownFingerprints.any { it.equals(fingerprint, ignoreCase = true) } ->
                            SignatureState.VALID
                        current?.fingerprint.equals(fingerprint, ignoreCase = true) ->
                            if (current!!.pinned) SignatureState.VALID else SignatureState.UNTRUSTED
                        else -> SignatureState.KEY_CHANGED
                    }
                SignatureVerdict(state, fingerprint)
            }
        }
    }

    /**
     * An S/MIME signature inside, judged on the device (#47). The web app asks the server, over a
     * route only a browser session reaches; this is the same judgement from what JMAP offers:
     * - not intact, or a certificate that does not name the sender: invalid;
     * - one of the person's own certificates: valid;
     * - the certificate plMail holds for the sender: valid when it chained to a trusted authority,
     *   untrusted when not;
     * - another certificate while plMail holds one for the sender: changed;
     * - nothing known: untrusted.
     */
    private suspend fun certificateVerdict(
        signature: InnerSignature.Certificate,
        sender: String,
        own: List<String>,
    ): SignatureVerdict {
        val fingerprint = signature.fingerprint
        val state =
            when {
                !signature.isIntact -> SignatureState.INVALID
                sender.isNotBlank() &&
                    signature.addresses.none { it.equals(sender.trim(), ignoreCase = true) } ->
                    SignatureState.INVALID
                own.any { it.equals(fingerprint, ignoreCase = true) } -> SignatureState.VALID
                else -> {
                    val known = encryption.certificateFor(sender)
                    when {
                        known == null -> SignatureState.UNTRUSTED
                        known.fingerprint.equals(fingerprint, ignoreCase = true) ->
                            if (known.verified) SignatureState.VALID else SignatureState.UNTRUSTED
                        else -> SignatureState.KEY_CHANGED
                    }
                }
            }

        return SignatureVerdict(state, fingerprint.ifBlank { null })
    }

    private fun List<ContactKey>.certificates(): List<OpenPGPCertificate> = mapNotNull { key ->
        try {
            PGPainless.getInstance().readKey().parseCertificate(key.publicKey)
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        const val SOURCE_NAME = "message.eml"
        const val SOURCE_TYPE = "message/rfc822"
    }
}

sealed interface DecryptionResult {
    data class Opened(val content: DecryptedContent) : DecryptionResult

    data class Failed(val problem: DecryptionProblem) : DecryptionResult
}

enum class DecryptionProblem {
    /** This device holds no keys, or none that open: the Encryption settings are the way in. */
    NO_KEYS_HERE,

    /** The keys here are protected, and the person did not confirm it is them. */
    LOCKED,

    /** S/MIME, and the person has no certificate on this device: it lives in the web app. */
    NO_CERTIFICATE,

    /** S/MIME, encrypted with a cipher this device cannot open. */
    UNSUPPORTED_CIPHER,

    /** The device's keys do not open this message: it was encrypted to a key it does not have. */
    NOT_FOR_THESE_KEYS,

    /** The original is not on the server, or carries no ciphertext. */
    NO_SOURCE,
    UNREACHABLE,
    FAILED,
}

/** A decrypted message ready for the reader. In memory only. */
class DecryptedContent(
    /** The protected subject, when the sender put one inside. */
    val subject: String?,
    /** Sanitised HTML, or escaped plain text in a `<pre>`. Safe to hand to the reader as is. */
    val html: String,
    /** Images the HTML refers to by `cid:`, by Content-ID. */
    val inlineImages: Map<String, OpenedPart>,
    val attachments: List<OpenedPart>,
    val signature: SignatureVerdict?,
) {
    override fun toString(): String =
        "DecryptedContent(attachments=${attachments.size}, signature=$signature)"
}

/** The signature inside, judged. [signer] is a fingerprint or key id in lower-case hex. */
data class SignatureVerdict(val state: SignatureState, val signer: String?)

enum class SignatureState {
    VALID,
    UNTRUSTED,
    UNKNOWN_KEY,
    KEY_CHANGED,
    INVALID,
    UNCHECKED;

    /** Whether the web app shows this one as a warning. */
    val isWarning: Boolean
        get() = this == INVALID || this == KEY_CHANGED

    companion object {
        /** The server's `signatureState` for signed-only mail. */
        fun fromWire(wire: String?): SignatureState? =
            when (wire) {
                "valid" -> VALID
                "untrusted" -> UNTRUSTED
                "unknown_key" -> UNKNOWN_KEY
                "key_changed" -> KEY_CHANGED
                "invalid" -> INVALID
                else -> null
            }
    }
}

/**
 * Files out of decrypted messages, for the moment another app has to open one.
 *
 * Under the blob cache, so the FileProvider already serves them, in a directory of their own per
 * file, and removed by [clear] when the reader that made them goes away. The one place decrypted
 * bytes touch the disk, and only because an external viewer cannot read from this process's memory.
 */
@Singleton
class DecryptedFiles @Inject constructor(@param:ApplicationContext private val context: Context) {

    private val root: File
        get() = File(context.cacheDir, "blobs/decrypted")

    fun write(part: OpenedPart): File {
        val directory = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
        val name = part.name?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "attachment"

        return File(directory, name).apply { writeBytes(part.bytes) }
    }

    fun clear() {
        root.deleteRecursively()
    }

    /**
     * A file out of a decrypted message that goes into a new one: the attachments of an encrypted
     * draft being reopened, or of encrypted mail being forwarded.
     *
     * Apart from the reader's, because the reader clears its own when it closes and these are read
     * again after the composer has gone — the send queue builds the message once the screen is
     * closed. Left for a day, then swept by the next write.
     */
    fun writeOutgoing(part: OpenedPart): File {
        val outgoing = File(context.cacheDir, "blobs/outgoing")
        val stale = System.currentTimeMillis() - OUTGOING_FOR_MILLIS

        outgoing
            .listFiles()
            ?.filter { it.lastModified() < stale }
            ?.forEach { it.deleteRecursively() }

        val directory = File(outgoing, UUID.randomUUID().toString()).apply { mkdirs() }
        val name = part.name?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "attachment"

        return File(directory, name).apply { writeBytes(part.bytes) }
    }

    private companion object {
        const val OUTGOING_FOR_MILLIS = 24 * 60 * 60 * 1000L
    }
}
