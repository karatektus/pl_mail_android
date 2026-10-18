package de.plmail.jmap.mail

import de.plmail.jmap.protocol.BlobId
import de.plmail.jmap.protocol.EmailId
import de.plmail.jmap.protocol.MailboxId
import de.plmail.jmap.protocol.ThreadId
import kotlinx.serialization.Serializable

/**
 * One message.
 *
 * Every field is optional because `Email/get` returns exactly the properties that were asked for
 * and nothing else — a list-row fetch and a reader fetch are the same type with different halves
 * populated. Modelling that with two types would double every mapper for no gain.
 */
@Serializable
data class Email(
    val id: EmailId,
    val threadId: ThreadId? = null,
    val blobId: BlobId? = null,
    val subject: String? = null,
    val from: List<EmailAddress> = emptyList(),
    val to: List<EmailAddress> = emptyList(),
    val cc: List<EmailAddress> = emptyList(),
    val bcc: List<EmailAddress> = emptyList(),
    val replyTo: List<EmailAddress> = emptyList(),
    val receivedAt: String? = null,
    val sentAt: String? = null,
    val preview: String = "",
    /**
     * Reported as `0` by a server whose size column is null, which the seeded test data is — so
     * never assert this is positive.
     */
    val size: Long = 0,
    val hasAttachment: Boolean = false,
    /**
     * This message's own inbox category, or null when it has not been classified.
     *
     * plMail's extension, and the **raw** signal rather than the value a tab is drawn from — see
     * [MailThread.category], which is this folded over the conversation most-recent-wins. The two
     * disagree whenever a conversation mixes kinds, which a newsletter somebody answered does; that
     * is the classifier working, not misfiring.
     *
     * Read-only, and deliberately not offered as an [EmailFilter] condition. Filtering it would put
     * one conversation in two tabs, and the server refuses it for that reason.
     */
    val category: String? = null,
    /**
     * A JMAP map, `{"42": true}` — and `{}` rather than `[]` when empty.
     *
     * These are mailbox **binding** ids, already translated out of the user-scoped label space, so
     * they can be passed straight back to `inMailbox` and `Email/set` with no conversion anywhere.
     */
    val mailboxIds: Map<String, Boolean> = emptyMap(),
    /** Same map shape. Only the four supported keywords ever appear. */
    val keywords: Map<String, Boolean> = emptyMap(),
    /** Bare ids, angle brackets already stripped by the server. */
    val messageId: List<String>? = null,
    val inReplyTo: List<String>? = null,
    val references: List<String>? = null,
    val textBody: List<EmailBodyPart> = emptyList(),
    val htmlBody: List<EmailBodyPart> = emptyList(),
    val attachments: List<EmailBodyPart> = emptyList(),
    val bodyValues: Map<String, EmailBodyValue> = emptyMap(),
    /**
     * The identity a reply to this message — or a forward of it — should be sent as, or null.
     *
     * plMail's extension, and **the server's answer rather than something to work out here**: it is
     * the address the mail was sent to, chosen by the same code the web composer opens a reply
     * with, so the browser and this app pick the same sender for the same mail. Reading To and Cc
     * against the identity list would be a second copy of that rule, and it could not find the case
     * that matters most — an address at a catch-all domain, which is in no list.
     *
     * For an alias this is the identity `Identity/get` lists, same id. For a catch-all address it
     * is one that list does not contain, and this object is the only place its id, name and
     * signature can be learned; the id goes into `EmailSubmission/set` like any other and the
     * server checks it there.
     *
     * On a draft it is the address the draft is already written from.
     *
     * Null three ways, all meaning "stay on the primary identity": the mail names none of the
     * account's addresses, the property was not asked for (it is never sent unasked — see
     * [de.plmail.jmap.methods.EmailGet.REPLY_PROPERTIES]), or the server predates it and ignored
     * the name.
     */
    val replyFrom: Identity? = null,
) {
    val mailboxes: List<MailboxId>
        get() = mailboxIds.filterValues { it }.keys.map(::MailboxId)

    val isSeen: Boolean
        get() = hasKeyword(Keyword.SEEN)

    val isFlagged: Boolean
        get() = hasKeyword(Keyword.FLAGGED)

    val isDraft: Boolean
        get() = hasKeyword(Keyword.DRAFT)

    val isAnswered: Boolean
        get() = hasKeyword(Keyword.ANSWERED)

    fun hasKeyword(keyword: Keyword): Boolean = keywords[keyword.wire] == true

    /**
     * The text body, if it was fetched.
     *
     * Bodies are synthetic: plMail stores a flattened body rather than a MIME tree, so a message
     * publishes at most two parts with the fixed ids `text` and `html`. Look them up through the
     * part rather than assuming the id, as the spec requires — and note the `bodyValues` key is the
     * *partId*.
     */
    val textContent: String?
        get() = textBody.firstOrNull()?.let { bodyValues[it.partId]?.value }

    /** Always the server's sanitised HTML, never the raw column. */
    val htmlContent: String?
        get() = htmlBody.firstOrNull()?.let { bodyValues[it.partId]?.value }
}

@Serializable
data class EmailBodyPart(
    val partId: String? = null,
    val blobId: BlobId? = null,
    val size: Long = 0,
    val type: String = "application/octet-stream",
    val charset: String? = null,
    val name: String? = null,
    val disposition: String? = null,
    val cid: String? = null,
) {
    /**
     * Whether this part is displayed inside the message rather than listed beneath it.
     *
     * The `disposition` decides when there is one. A `cid` is what an inline image is referenced
     * by, but plenty of mailers also put a Content-ID on a part they mark `attachment` -- an
     * invoice PDF from an accounting package, a forwarded file -- and reading the cid alone hid
     * every such file from the reader's list and from a forward. Only a part with no disposition
     * falls back to the cid.
     */
    val isInline: Boolean
        get() = disposition == "inline" || (disposition == null && cid != null)
}

@Serializable
data class EmailBodyValue(
    val value: String = "",
    val isEncodingProblem: Boolean = false,
    val isTruncated: Boolean = false,
)
