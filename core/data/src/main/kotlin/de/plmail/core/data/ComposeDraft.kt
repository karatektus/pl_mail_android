package de.plmail.core.data

import de.plmail.jmap.mail.Email
import de.plmail.jmap.mail.EmailAddress
import de.plmail.jmap.mail.Signatures

/**
 * What is being written, independent of both the screen and the wire.
 *
 * Held by value and copied on every edit rather than mutated, so the send path is handed the exact
 * draft the user was looking at when they tapped Send — a composer that kept editing a shared
 * object would send whatever the field happened to contain a keystroke later.
 */
data class ComposeDraft(
    /** Which account sends. Also decides the server the draft is written to. */
    val accountKey: String,
    val identityId: String,
    val to: List<EmailAddress> = emptyList(),
    val cc: List<EmailAddress> = emptyList(),
    val bcc: List<EmailAddress> = emptyList(),
    val subject: String = "",
    /** Always HTML. plMail stores and round-trips HTML bodies; text is derived server-side. */
    val bodyHtml: String = "",
    val inReplyTo: List<String>? = null,
    val references: List<String>? = null,
    val attachments: List<StagedAttachment> = emptyList(),
    /**
     * The server's id for this draft, once it has one.
     *
     * Null until the first successful save. A reply that has never been saved and a reply being
     * re-edited take different paths through [ComposeRepository], and this is what tells them
     * apart.
     */
    val emailId: String? = null,
    /**
     * The attachment list as the saved draft actually has it.
     *
     * The comparison against [attachments] decides whether a save has to *say anything* about them.
     * `Email/set` update takes the whole set, and an absent key means "leave them alone" — so
     * sending the array on every keystroke would re-state a dozen blob ids per autosave for
     * nothing, on a link that may be someone's uplink. Kept for that, not for the
     * recreate-the-draft workaround it was originally added for.
     */
    val savedAttachments: List<StagedAttachment> = emptyList(),
    /**
     * Set when the message is to be saved and sent end-to-end encrypted (#9), from this address.
     *
     * The composer decides it — by the recipients' keys and the person's choice — and sets it on
     * the draft it hands over, so the save that follows encrypts exactly what was decided for the
     * draft it is given.
     */
    val sealAs: SealAs? = null,
    /**
     * Whether the draft on the server is an encrypted one.
     *
     * Such a draft is a message this device built and imported, not fields the server holds, so it
     * cannot be patched: every save replaces it with a new one and moves the old one to Trash.
     */
    val savedSealed: Boolean = false,
) {
    /**
     * Whether saving means creating a new message rather than patching the one that exists.
     *
     * True only for a draft that has never been saved. Attachments used to force a create as well,
     * because update dropped them; it applies them now.
     */
    val needsCreate: Boolean
        get() = emailId == null

    /** Whether the next patch has to carry the attachment set. */
    val attachmentsChanged: Boolean
        get() = attachments != savedAttachments

    val hasRecipients: Boolean
        get() = to.isNotEmpty() || cc.isNotEmpty() || bcc.isNotEmpty()

    /**
     * Whether there is anything worth saving.
     *
     * A composer opened and closed again must not litter Drafts with an empty message, and a reply
     * carries a quoted body from the first frame — so the quote alone does not count as content.
     *
     * **Nor does the signature**, for exactly the same reason and it is the newer half of this: a
     * composer now opens with the sending address's sign-off already in the body, so a body that is
     * *only* a signature is still a message nobody has written. Without this every opened-and-
     * abandoned composer would leave a draft behind containing the user's own name.
     */
    fun isEmpty(quotedHtml: String): Boolean =
        !hasRecipients &&
            subject.isBlank() &&
            attachments.isEmpty() &&
            Signatures.replaceSignature(bodyHtml.removeSuffix(quotedHtml), null).isBlankHtml()
}

/**
 * A file on its way into the message, from either of the two places one can come from.
 *
 * A **picked** file is a content URI whose bytes are not uploaded until send. Uploading when the
 * file is picked would put a blob on the server that `app:prune:blobs` reclaims after seven days,
 * so a draft left open over a holiday would be sent with its attachments already collected — the
 * server refuses that with "blobId cannot be resolved" rather than sending a message with a hole in
 * it, which is better and still a failure the user can do nothing about.
 *
 * An **attached** file is one already on a saved draft, named by the blob id the server reports.
 * Re-sending that id re-attaches it, verified against the running server, which is what lets a
 * draft be reopened and saved again without its attachments having to be picked a second time.
 */
data class StagedAttachment(
    val name: String,
    val type: String,
    val size: Long,
    /** A content URI, as a string so this type stays comparable and saveable. */
    val uri: String? = null,
    /** Set once the bytes are on the server. Opaque; never parsed. */
    val blobId: String? = null,
)

/** One address the user may send as. */
data class SendIdentity(
    val accountKey: String,
    /** What the server calls this account — shown when more than one can send. */
    val accountName: String,
    val identityId: String,
    val name: String?,
    val email: String,
    /**
     * What this address signs with, as HTML, or empty for one that signs with nothing.
     *
     * Carried on the identity rather than looked up when it is needed, because the composer swaps
     * it the instant the From menu closes — see [de.plmail.jmap.mail.Signatures]. A signature
     * fetched at that moment would arrive after the user had started typing under it.
     */
    val htmlSignature: String = "",
) {
    /**
     * What the From row shows.
     *
     * Not assumed to parse as an address. `SeedTestEmailCommand` puts a display name in the
     * account's email column, so a seeded server answers `Identity/get` with `{"email": "E2E
     * Mailbox"}` — anything here that split on `@` would produce nonsense.
     */
    val label: String
        get() = if (name.isNullOrBlank() || name == email) email else "$name <$email>"
}

/**
 * Who answers this message, when the server says: [Email.replyFrom] as an entry for the From row.
 *
 * Null means the server named nobody — or is too old to — and the caller stays on the account's
 * first identity, which is what every reply did before.
 *
 * The entry from [known] when the server named an identity this device already lists, so the
 * picker's own row is the one selected. Otherwise one built from what the server sent: an address
 * at a catch-all domain is an identity `Identity/get` never lists, and the same construction covers
 * an alias added on the web a moment ago that the cache has not caught up with. Either way nothing
 * here decides whether the address may be used — the server chose it, and checks it again when the
 * mail is submitted.
 */
fun Email.replySender(accountKey: String, known: List<SendIdentity>): SendIdentity? {
    val named = replyFrom ?: return null
    val ofAccount = known.filter { it.accountKey == accountKey }

    return ofAccount.firstOrNull { it.identityId == named.id.value }
        ?: SendIdentity(
            accountKey = accountKey,
            accountName = ofAccount.firstOrNull()?.accountName.orEmpty(),
            identityId = named.id.value,
            name = named.name,
            email = named.email,
            htmlSignature = named.htmlSignature,
        )
}

/**
 * The picker's list with [sender] in it.
 *
 * A no-op for an identity already listed. One that is not — a catch-all address — goes *after* its
 * account's own entries, never before them: the first entry of an account is what the rest of the
 * composer reads as that account's default, and an address that exists for one message must not
 * become it.
 */
fun List<SendIdentity>.including(sender: SendIdentity?): List<SendIdentity> {
    if (sender == null) return this
    if (any { it.accountKey == sender.accountKey && it.identityId == sender.identityId })
        return this

    val after = indexOfLast { it.accountKey == sender.accountKey }

    return if (after < 0) this + sender else take(after + 1) + sender + drop(after + 1)
}

/**
 * Whether an HTML body carries anything the user typed.
 *
 * A rich-text editor emits `<p></p>`, `<br>` and non-breaking spaces for an empty field, so a naive
 * `isBlank()` on the HTML sees content in a composer nobody has typed into and saves an empty draft
 * on every open.
 */
internal fun String.isBlankHtml(): Boolean =
    replace(Regex("<[^>]*>"), " ")
        .replace("&nbsp;", " ")
        // As an escape rather than the character itself: a literal U+00A0 in
        // source is invisible, and the next person to touch this line deletes it
        // without knowing it was there.
        .replace('\u00A0', ' ')
        .isBlank()
