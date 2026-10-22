package de.plmail.core.data

import de.plmail.crypto.MailAddress
import de.plmail.crypto.OutgoingContent
import de.plmail.crypto.OutgoingFile
import de.plmail.crypto.OutgoingMessages
import de.plmail.crypto.UnlockedAddressKey
import de.plmail.jmap.client.JmapClient
import de.plmail.jmap.mail.EmailAddress
import de.plmail.jmap.methods.ContactKeyLookup
import de.plmail.jmap.methods.ContactKeyLookupResult
import de.plmail.jmap.methods.RecipientKey
import de.plmail.jmap.protocol.AccountId
import de.plmail.jmap.protocol.Capability
import de.plmail.jmap.protocol.RequestBuilder
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.pgpainless.PGPainless

/**
 * Whether a message can go out end-to-end encrypted, and the encrypting itself (#9, phase 4).
 *
 * The composer asks [keysFor] as recipients are added: the server answers per address with the key
 * it holds — looking one up through WKD and the key servers when it has none — and with the key the
 * From address signs with. [plan] turns that into the composer's decision, by the web app's rules.
 *
 * [seal] runs when the message is saved: it asks the server again, without looking anything up, so
 * the message is encrypted to the keys the server will check it against — a key accepted on the web
 * while the person was writing is the one used, and the import is not refused.
 */
@Singleton
class OutgoingEncryption
@Inject
constructor(
    private val clients: AccountClients,
    private val encryption: EncryptionRepository,
) {

    /**
     * What plMail knows about the keys of [addresses], for a message from [from] in [accountId].
     *
     * [RecipientKeys.Unavailable] when encryption does not apply on this device at all: a server
     * without it, or a device paired without keys. Throws when the server cannot be asked.
     */
    suspend fun keysFor(
        accountId: String,
        from: String,
        addresses: List<String>,
        lookUp: Boolean,
    ): RecipientKeys {
        val client = clients.current() ?: return RecipientKeys.Unavailable
        if (client.session().keys == null) return RecipientKeys.Unavailable

        // Without opening protected keys: nobody is asked to confirm it is them
        // for typing an address.
        val fingerprints = encryption.signingFingerprints()
        val certificates = encryption.signingCertificates()
        if (fingerprints.isEmpty() && certificates.isEmpty()) return RecipientKeys.Unavailable

        val answer = lookup(client, AccountId(accountId), from, addresses, lookUp)
        val signer = answer.sender?.fingerprint
        val certificate = answer.sender?.smime

        return RecipientKeys.Known(
            ownKey = signer != null && fingerprints.any { it.equals(signer, ignoreCase = true) },
            byAddress = answer.recipients.associateBy { it.email.lowercase() },
            ownCertificate =
                certificate != null &&
                    certificates.any { it.equals(certificate, ignoreCase = true) },
        )
    }

    /**
     * Opens the signing keys now, asking the person to confirm it is them if they are protected —
     * before a message is handed to the send queue, which has no screen to ask on. False when they
     * declined. The keys then stay open in memory for a few minutes, long enough for the send.
     */
    suspend fun openKeys(): Boolean =
        try {
            encryption.unlockedAddressKeys(interactive = true)
            true
        } catch (_: KeysLocked) {
            false
        }

    /**
     * The message, encrypted to every recipient and to the sender, signed by the sender: the bytes
     * to upload and import, and the fingerprints the import names.
     *
     * Throws [EncryptionRefused] when a recipient has no key, or the From address none this device
     * opens — the composer checked both, but the answer can change between its check and this.
     */
    internal suspend fun seal(
        client: JmapClient,
        accountId: AccountId,
        draft: ComposeDraft,
        from: SealAs,
        files: List<OutgoingFile>,
    ): SealedMessage {
        val recipients = (draft.to + draft.cc + draft.bcc).mapNotNull { it.email?.trim() }
        val answer = lookup(client, accountId, from.email, recipients, lookUp = false)

        if (from.scheme == EncryptionScheme.SMIME) {
            return sealSmime(draft, from, files, recipients, answer)
        }

        val missing = recipients.filter { answer.forAddress(it)?.key == null }
        if (missing.isNotEmpty()) throw EncryptionRefused.Missing(missing.distinct())

        val keys =
            try {
                encryption.unlockedAddressKeys(interactive = true)
            } catch (_: KeysLocked) {
                throw EncryptionRefused.Locked
            }
        val signer: UnlockedAddressKey =
            keys.firstOrNull { it.fingerprint.equals(answer.sender?.fingerprint, true) }
                ?: throw EncryptionRefused.NoOwnKey

        val publicKeys =
            recipients.mapNotNull { answer.forAddress(it)?.key }.distinctBy { it.fingerprint }

        val content = content(draft, from, files)

        return withContext(Dispatchers.Default) {
            val raw =
                try {
                    val certificates = publicKeys.map {
                        PGPainless.getInstance().readKey().parseCertificate(it.publicKey)
                    }

                    OutgoingMessages.seal(content, certificates, signer)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    // A key that cannot be read or cannot encrypt: expired,
                    // revoked, or without an encryption subkey.
                    throw EncryptionRefused.Unusable
                }

            SealedMessage(
                raw = raw,
                fingerprints =
                    (publicKeys.map { it.fingerprint } + signer.fingerprint)
                        .map { it.lowercase() }
                        .distinct(),
                signer = signer.fingerprint.lowercase(),
            )
        }
    }

    /**
     * The S/MIME branch of [seal] (#47): every recipient's current certificate and the sender's
     * own, signed with the certificate the server names for the From address — the one it checks
     * the import's `signer` against.
     */
    private suspend fun sealSmime(
        draft: ComposeDraft,
        from: SealAs,
        files: List<OutgoingFile>,
        recipients: List<String>,
        answer: ContactKeyLookupResult,
    ): SealedMessage {
        val missing = recipients.filter { answer.forAddress(it)?.smime == null }
        if (missing.isNotEmpty()) throw EncryptionRefused.Missing(missing.distinct())

        val keys =
            try {
                encryption.unlockedKeys(interactive = true).smimeKeys
            } catch (_: KeysLocked) {
                throw EncryptionRefused.Locked
            }
        val named = answer.sender?.smime
        val signer =
            keys.firstOrNull { it.fingerprint.equals(named, ignoreCase = true) }
                ?: keys.firstOrNull { it.servesAt(from.email) }
                ?: throw EncryptionRefused.NoOwnCertificate

        val certificates =
            recipients.mapNotNull { answer.forAddress(it)?.smime }.distinctBy { it.fingerprint }
        val content = content(draft, from, files)

        return withContext(Dispatchers.Default) {
            val raw =
                try {
                    OutgoingMessages.sealSmime(content, certificates.map { it.certificate }, signer)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    // A certificate that cannot be read, or is not RSA.
                    throw EncryptionRefused.Unusable
                }

            SealedMessage(
                raw = raw,
                fingerprints =
                    (certificates.map { it.fingerprint } + signer.fingerprint)
                        .map { it.lowercase() }
                        .distinct(),
                signer = signer.fingerprint.lowercase(),
            )
        }
    }

    private fun content(draft: ComposeDraft, from: SealAs, files: List<OutgoingFile>) =
        OutgoingContent(
            from = MailAddress(from.email, from.name),
            to = draft.to.toMailAddresses(),
            cc = draft.cc.toMailAddresses(),
            bcc = draft.bcc.toMailAddresses(),
            subject = draft.subject,
            html = draft.bodyHtml,
            text = DecryptedHtml.toText(draft.bodyHtml),
            files = files,
            messageId = OutgoingMessages.messageId(from.email),
            date = ZonedDateTime.now(),
            inReplyTo = draft.inReplyTo.orEmpty().map(::bracketed),
            references = draft.references.orEmpty().map(::bracketed),
        )

    private suspend fun lookup(
        client: JmapClient,
        accountId: AccountId,
        from: String,
        addresses: List<String>,
        lookUp: Boolean,
    ): ContactKeyLookupResult {
        val wanted = addresses.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()

        val request = RequestBuilder(using = Capability.USING_KEYS)
        val call =
            request.add(
                ContactKeyLookup(
                    emails = wanted.take(ContactKeyLookup.MAX),
                    lookup = lookUp,
                    accountId = accountId,
                    from = from,
                )
            )

        return client.send(request).result(call)
    }

    /** JMAP carries Message-IDs without their brackets; the header has them. */
    private fun bracketed(id: String): String =
        id.trim().let { if (it.startsWith("<")) it else "<$it>" }

    private fun List<EmailAddress>.toMailAddresses(): List<MailAddress> = mapNotNull { address ->
        address.email?.trim()?.takeIf { it.isNotEmpty() }?.let { MailAddress(it, address.name) }
    }
}

/** The address an encrypted message is written from: the composer knows it, the draft keeps it. */
data class SealAs(
    val email: String,
    val name: String? = null,
    /** OpenPGP, or S/MIME when that is what every recipient can be reached with (#47). */
    val scheme: EncryptionScheme = EncryptionScheme.PGP,
)

/** How a message is end-to-end encrypted. */
enum class EncryptionScheme {
    PGP,
    SMIME,
}

internal class SealedMessage(
    val raw: ByteArray,
    val fingerprints: List<String>,
    val signer: String,
)

/** Why a message that was to be encrypted was not. Nothing was saved or sent. */
sealed class EncryptionRefused(message: String) : IllegalStateException(message) {

    class Missing(val addresses: List<String>) :
        EncryptionRefused(
            "There is no key for ${addresses.joinToString(", ")}. Remove them, or switch " +
                "encryption off for this message."
        )

    object NoOwnKey :
        EncryptionRefused(
            "You have no key for this From address, so nothing can be signed or encrypted from it."
        )

    object NoOwnCertificate :
        EncryptionRefused(
            "You have no S/MIME certificate for this From address, so this message cannot be " +
                "signed or encrypted with S/MIME."
        )

    object Unusable : EncryptionRefused("The message could not be encrypted. Nothing was sent.")

    object Locked :
        EncryptionRefused(
            "Your keys are locked on this device. Confirm it is you, or switch encryption off " +
                "for this message."
        )

    object KeysChanged :
        EncryptionRefused(
            "A recipient's key changed while you were writing. Nothing was sent; send it again."
        )
}

/** What the server knows about the keys of a message's recipients. */
sealed interface RecipientKeys {

    /** Encryption does not apply here: no support on the server, or no keys on this device. */
    data object Unavailable : RecipientKeys

    data class Known(
        /** Whether the From address has a key, and this device opens it. */
        val ownKey: Boolean,
        /** By lower-cased address. An address missing here has not been asked about yet. */
        val byAddress: Map<String, RecipientKey>,
        /** Whether the From address has an S/MIME certificate this device opens (#47). */
        val ownCertificate: Boolean = false,
    ) : RecipientKeys
}

/** The person's choice for this one message. */
enum class EncryptionMode {
    /** Encrypt when every recipient can be reached encrypted — the default. */
    AUTO,
    ON,
    OFF,
}

/** What the composer is going to do, and what stops it. */
data class EncryptionPlan(
    /** Whether encryption applies to this message at all; the composer shows nothing otherwise. */
    val available: Boolean = false,
    val encrypt: Boolean = false,
    /** How, when [encrypt]: OpenPGP unless S/MIME is the way every recipient can be reached. */
    val scheme: EncryptionScheme = EncryptionScheme.PGP,
    /** Recipients that have not been asked about yet: nothing is decided while there are any. */
    val pending: Boolean = false,
    val blocked: EncryptionBlock? = null,
    /**
     * Recipients without a key, when the message goes out unencrypted because of them though others
     * have one: the composer says so.
     */
    val unencryptedFor: List<String> = emptyList(),
)

sealed interface EncryptionBlock {
    /** Encryption is on, and these recipients have no key. */
    data class Missing(val addresses: List<String>) : EncryptionBlock

    /** A recipient asks for encryption, and these others cannot be reached encrypted. */
    data class Partial(val addresses: List<String>) : EncryptionBlock

    /** Encryption is on, and the From address has no key this device opens. */
    data object NoOwnKey : EncryptionBlock

    /** Encryption is on, and this device holds no keys. */
    data object NoKeysHere : EncryptionBlock

    /** The keys here are protected, and the person did not confirm it is them. */
    data object KeysLocked : EncryptionBlock
}

/**
 * The web app's decision (`#plan()` in `assets/controllers/compose/encryption_controller.js`).
 * [keys] is null until the server has been asked. "Reachable" means with OpenPGP when every
 * recipient has a key, otherwise with S/MIME when every recipient has a certificate, the sender has
 * one and there is no Bcc (#47).
 * - **On**: encrypted; blocked while recipients are not reachable — the shorter list is named.
 * - **Auto**: encrypted when every recipient is reachable and nobody is set to `never`. When some
 *   are and others are not, the message goes out unencrypted and the composer says for whom — or is
 *   blocked, when one of the recipients is set to `always`.
 * - **Off**: not encrypted.
 *
 * The person's "block partial encryption" setting is not published over JMAP, so here only a
 * recipient set to `always` blocks. Signing without encrypting is not offered yet.
 */
fun plan(
    mode: EncryptionMode,
    addresses: List<String>,
    keys: RecipientKeys?,
    /** The Bcc recipients among [addresses]: S/MIME is not used with any. */
    bcc: List<String> = emptyList(),
): EncryptionPlan {
    val known =
        when (keys) {
            // Not asked yet: nothing shown, nothing decided.
            null -> return EncryptionPlan(encrypt = mode == EncryptionMode.ON, pending = true)
            RecipientKeys.Unavailable ->
                return if (mode == EncryptionMode.ON) {
                    EncryptionPlan(
                        available = true,
                        encrypt = true,
                        blocked = EncryptionBlock.NoKeysHere,
                    )
                } else {
                    EncryptionPlan()
                }
            is RecipientKeys.Known -> keys
        }

    val wanted = addresses.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
    val pending = wanted.any { it !in known.byAddress }
    val answers = wanted.mapNotNull { known.byAddress[it] }
    val pgpMissing = answers.filter { it.key == null }.map { it.email }
    val smimeMissing = answers.filter { it.smime == null }.map { it.email }
    val hasBcc = bcc.any { it.isNotBlank() }

    // OpenPGP whenever everyone has a key; S/MIME when everyone has a
    // certificate, the sender has one too and nobody is in Bcc (S/MIME names
    // every recipient in the envelope).
    val scheme =
        when {
            wanted.isNotEmpty() && pgpMissing.isEmpty() && known.ownKey -> EncryptionScheme.PGP
            known.ownCertificate && !hasBcc && wanted.isNotEmpty() && smimeMissing.isEmpty() ->
                EncryptionScheme.SMIME
            else -> null
        }
    // Who to name when neither works: the shorter list, as the web app does.
    val missing =
        when {
            scheme != null -> emptyList()
            known.ownCertificate && !hasBcc && smimeMissing.size < pgpMissing.size -> smimeMissing
            else -> pgpMissing
        }
    val canSign = known.ownKey || known.ownCertificate

    return when (mode) {
        EncryptionMode.OFF -> EncryptionPlan(available = true, pending = pending)
        EncryptionMode.ON ->
            EncryptionPlan(
                available = true,
                encrypt = true,
                scheme = scheme ?: EncryptionScheme.PGP,
                pending = pending,
                blocked =
                    when {
                        scheme != null -> null
                        !canSign -> EncryptionBlock.NoOwnKey
                        wanted.isNotEmpty() && pgpMissing.isEmpty() -> EncryptionBlock.NoOwnKey
                        missing.isNotEmpty() -> EncryptionBlock.Missing(missing)
                        else -> null
                    },
            )
        EncryptionMode.AUTO -> {
            val encrypt = scheme != null && !pending && answers.none { it.refusesEncryption }
            val reachable = answers.any { it.key != null || it.smime != null }
            val partial =
                if (!encrypt && canSign && missing.isNotEmpty() && reachable) missing
                else emptyList()

            EncryptionPlan(
                available = true,
                encrypt = encrypt,
                scheme = scheme ?: EncryptionScheme.PGP,
                pending = pending,
                blocked =
                    EncryptionBlock.Partial(partial).takeIf {
                        partial.isNotEmpty() && answers.any { it.wantsEncryption }
                    },
                unencryptedFor = partial,
            )
        }
    }
}
