package de.plmail.feature.compose

import de.plmail.core.data.ComposeDraft
import de.plmail.core.data.EncryptionBlock
import de.plmail.core.data.EncryptionMode
import de.plmail.core.data.EncryptionPlan
import de.plmail.core.data.RecipientKeys
import de.plmail.core.data.ScheduledSend
import de.plmail.core.data.SendIdentity
import de.plmail.core.data.plan
import de.plmail.jmap.mail.DraftComposer
import de.plmail.jmap.mail.Email
import de.plmail.jmap.mail.EmailAddress
import de.plmail.jmap.protocol.SubmissionCapability
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle

/** Everything on the composer screen. */
data class ComposeUiState(
    val draft: ComposeDraft = ComposeDraft(accountKey = "", identityId = ""),
    val identities: List<SendIdentity> = emptyList(),
    /**
     * What the sending account will accept, read from the session.
     *
     * The default is the spec's — no delayed send — so a server that says nothing about it gets a
     * composer with no "Send later" in the menu rather than one offering a feature that would be
     * refused.
     */
    val submission: SubmissionCapability = SubmissionCapability(),
    /**
     * The release time this draft is already waiting for, when it is one.
     *
     * Only ever set from this device's own record: a held submission has no server-side row to read
     * back, so a draft scheduled on the web or on another phone opens here as an ordinary draft.
     */
    val scheduled: ScheduledSend? = null,
    /**
     * The original, quoted, held beside the draft rather than inside the editor.
     *
     * Appended when the message is saved or sent. Keeping it out of the editor means nobody else's
     * markup is ever round-tripped through the editor's HTML parser and reflowed into something
     * they did not write — and it means the user's own text and the quote can be told apart, which
     * is what "an empty composer" has to mean for a reply.
     */
    val quotedHtml: String = "",
    val isQuoteExpanded: Boolean = false,
    /**
     * The sending address's sign-off, held beside the draft for the same reason as [quotedHtml].
     *
     * Put under what was typed when the message is saved or sent, in the marked block the web swaps
     * on. It used to be put into `draft.bodyHtml` instead, which the editor never showed and
     * overwrote with its own HTML on the first keystroke — so mail written on the phone went out
     * unsigned (#3). A signature is the user's own HTML, often a table with a logo, and the editor
     * would not keep that shape or the marker even if it were loaded into it.
     */
    val signatureHtml: String = "",
    /**
     * Counts the times the ViewModel put a body into the draft that the editor has to load.
     *
     * The editor owns the text once it is open, so the body only travels the other way at these
     * moments: when a draft is reopened, when shared text arrives, when a new message starts.
     */
    val bodyLoads: Int = 0,
    val isShowingCopyFields: Boolean = false,
    val isLoading: Boolean = true,
    /** Whether everything on screen has reached the server. Shown, never assumed. */
    val isSaved: Boolean = false,
    val suggestions: List<EmailAddress> = emptyList(),
    val error: ComposeError? = null,
    /**
     * End-to-end encryption for this one message (#9): the person's choice, and what the server
     * said about the recipients' keys. [encryption] is the decision drawn from the two.
     */
    val encryptionMode: EncryptionMode = EncryptionMode.AUTO,
    val recipientKeys: RecipientKeys? = null,
    /**
     * A draft this device cannot open — sealed by the web app, or encrypted to keys that are not
     * here. Shown with what is known, and never saved or sent from here, which would replace it
     * with an empty message.
     */
    val isUnopenable: Boolean = false,
) {
    val encryption: EncryptionPlan
        get() =
            plan(
                encryptionMode,
                (draft.to + draft.cc + draft.bcc).mapNotNull { it.email },
                recipientKeys,
            )

    /** The lock on a recipient's chip, or null where encryption does not apply. */
    fun lockOf(address: EmailAddress): RecipientLock? {
        val known = recipientKeys as? RecipientKeys.Known ?: return null
        val answer = known.byAddress[address.email?.trim()?.lowercase()] ?: return null

        return when {
            answer.key == null -> null
            answer.changed -> RecipientLock.KEY_CHANGED
            else -> RecipientLock.HAS_KEY
        }
    }

    val identity: SendIdentity?
        get() = identities.firstOrNull { it.identityId == draft.identityId }

    /** Whether the From row is worth opening as a menu. */
    val hasChoiceOfSender: Boolean
        get() = identities.size > 1

    /**
     * Whether the account name has to be shown beside each address in the picker.
     *
     * One entry per *alias* now, so several rows can belong to one account — and with only one
     * account connected, repeating its name on every row is noise that tells nobody anything.
     */
    val showsAccountNames: Boolean
        get() = identities.distinctBy { it.accountKey }.size > 1

    /**
     * Whether the collapsed header has to name the sender.
     *
     * Only when it could be something else. With one account and one alias the answer is fixed for
     * the whole session, and spending forty per cent of a single-line summary restating it leaves
     * the two facts that *do* change — who it is going to, and what it is about — as a pair of
     * ellipses. That is what the first version of the line looked like.
     *
     * Two cases, and each is a genuine surprise worth the room. Several accounts are connected, so
     * which mailbox this leaves from is a real choice the user makes and can get wrong. Or the
     * draft is not on its account's default alias — a reply picks the alias the mail was addressed
     * to, which is exactly the moment somebody wants to see it before they send.
     *
     * The expanded header is unaffected: it always shows the From row, because that is where the
     * sender is *chosen* rather than merely reported.
     */
    val summaryNamesSender: Boolean
        get() =
            showsAccountNames ||
                identities.firstOrNull { it.accountKey == draft.accountKey }?.identityId !=
                    draft.identityId

    /** Whether "Send later" belongs in the menu at all. The session decides, not this client. */
    val canScheduleSend: Boolean
        get() = submission.supportsScheduledSend

    /** The furthest ahead this server will accept a release time. */
    fun latestSendAt(now: Instant): Instant = now.plus(submission.longestHold)
}

/** What went wrong, in terms the screen can turn into a sentence. */
sealed interface ComposeError {

    /** Send was tapped with nobody to send to. */
    data object NoRecipients : ComposeError

    /** No account can send — nothing is paired, or `Identity/get` has never been answered. */
    data object NoIdentity : ComposeError

    /** The message being replied to or forwarded could not be fetched. */
    data object OriginalUnavailable : ComposeError

    data class SaveFailed(val message: String) : ComposeError

    /** A schedule could not be called back — the server refused, or nothing answered. */
    data class CancelFailed(val message: String) : ComposeError

    /** The cancel arrived after the release. The mail has gone; nothing was undone. */
    data object AlreadySent : ComposeError

    /**
     * Shared files over the size a message can carry, named rather than trimmed.
     *
     * Two cases rather than one for the two ways a share can lose a file, because the two lead
     * somewhere different: a file that is too big can be sent another way, and a file that could
     * not be read is usually worth sharing again. A single "some files were not attached" tells the
     * user neither.
     */
    data class AttachmentsTooLarge(val names: List<String>, val limitMb: Int) : ComposeError

    /** Shared files the app was not allowed to read, or that were gone by the time it looked. */
    data class AttachmentsUnreadable(val names: List<String>) : ComposeError

    /** The message is to be encrypted, and cannot be. Nothing was sent. */
    data class EncryptionBlocked(val block: EncryptionBlock) : ComposeError

    /** The recipients' keys could not be checked, so whether to encrypt is unknown. */
    data object KeysUnchecked : ComposeError

    /** The encrypted original could not be decrypted, so the quote is empty. */
    data object OriginalNotDecrypted : ComposeError

    /** A draft the web app sealed: only a browser with the keys opens it. */
    data object SealedOnWeb : ComposeError

    /** An encrypted draft whose keys are not on this device. */
    data object DraftNotDecrypted : ComposeError
}

/** What a recipient's chip shows about their key. */
enum class RecipientLock {
    /** plMail has a key for the address: mail to it can be encrypted. */
    HAS_KEY,

    /** It has one, and a new one is waiting to be accepted on the web; the known one is used. */
    KEY_CHANGED,
}

/**
 * The localised text the draft composer needs.
 *
 * Passed in from the screen rather than read inside the ViewModel, and generated in `:core:jmap`
 * from parameters rather than resources, because the composition rules are pure protocol logic and
 * that module must stay Android-free. It also means the attribution line is covered by a JVM test
 * rather than an instrumented one.
 */
class ComposeStrings(
    /** `"On %1$s, %2$s wrote:"` — date first, sender second. */
    private val attributionFormat: String,
    val forwardLabels: DraftComposer.ForwardLabels,
) {

    fun attribution(original: Email): String =
        attributionFormat.format(date(original), original.from.firstOrNull()?.display.orEmpty())

    /**
     * When the original was sent, in the reader's own locale and zone.
     *
     * `sentAt` before `receivedAt`: a quote says when the sender wrote it, not when it happened to
     * arrive here — the two differ by days for anything that sat in a queue.
     */
    fun date(original: Email): String {
        val instant = (original.sentAt ?: original.receivedAt).toInstantOrNull() ?: return ""

        return FORMATTER.format(instant.atZone(ZoneId.systemDefault()))
    }

    private companion object {
        val FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    }
}

/**
 * Parses a JMAP `UTCDate`.
 *
 * The offset form is accepted as well as `Z`, because `Instant.parse` takes only the latter and a
 * server behind a proxy that rewrites dates hands back `2026-07-31T18:55:43+02:00` — a perfectly
 * valid instant that would otherwise leave the quote with no date at all.
 */
internal fun String?.toInstantOrNull(): Instant? {
    val text = this?.trim().orEmpty()
    if (text.isEmpty()) return null

    return try {
        Instant.parse(text)
    } catch (notInstant: DateTimeParseException) {
        try {
            OffsetDateTime.parse(text).toInstant()
        } catch (notOffset: DateTimeParseException) {
            null
        }
    }
}

/** A file size the way a person reads one. */
internal fun Long.asFileSize(): String =
    when {
        this < 1_024 -> "$this B"
        this < 1_024 * 1_024 -> "${this / 1_024} KB"
        else -> "%.1f MB".format(this / (1_024.0 * 1_024.0))
    }
