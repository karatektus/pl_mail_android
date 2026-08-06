package de.plmail.feature.compose

import de.plmail.core.data.ComposeDraft
import de.plmail.core.data.ScheduledSend
import de.plmail.core.data.SendIdentity
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
    val isShowingCopyFields: Boolean = false,
    val isLoading: Boolean = true,
    /** Whether everything on screen has reached the server. Shown, never assumed. */
    val isSaved: Boolean = false,
    val suggestions: List<EmailAddress> = emptyList(),
    val error: ComposeError? = null,
) {
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
