package de.plmail.core.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import de.plmail.core.database.PlMailDatabase
import de.plmail.core.database.StoreKey
import de.plmail.core.datastore.CredentialStore
import de.plmail.jmap.client.JmapClient
import de.plmail.jmap.mail.DraftComposer
import de.plmail.jmap.mail.Email
import de.plmail.jmap.mail.EmailAddress
import de.plmail.jmap.methods.CANNOT_UNSEND
import de.plmail.jmap.methods.DraftAttachment
import de.plmail.jmap.methods.DraftEmail
import de.plmail.jmap.methods.EmailGet
import de.plmail.jmap.methods.EmailPatch
import de.plmail.jmap.methods.EmailSet
import de.plmail.jmap.methods.EmailSubmissionGet
import de.plmail.jmap.methods.EmailSubmissionSet
import de.plmail.jmap.methods.FORBIDDEN_FROM
import de.plmail.jmap.methods.IdentityGet
import de.plmail.jmap.methods.SendHold
import de.plmail.jmap.methods.SetError
import de.plmail.jmap.methods.SubmissionRecord
import de.plmail.jmap.protocol.AccountId
import de.plmail.jmap.protocol.BlobId
import de.plmail.jmap.protocol.Capability
import de.plmail.jmap.protocol.EmailId
import de.plmail.jmap.protocol.IdentityId
import de.plmail.jmap.protocol.MailboxId
import de.plmail.jmap.protocol.RequestBuilder
import de.plmail.jmap.protocol.SubmissionCapability
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/**
 * Saving, sending and discarding what the composer is holding.
 *
 * Three server behaviours shape everything below, all three established by probing the running
 * server rather than by reading the PHP:
 *
 * 1. **`attachments` on an update is whole-value.** The array sent is the complete set: a part left
 *    out is removed, a part kept is named by the `p-` blobId `Email/get` handed out and costs no
 *    upload. An unresolvable blobId refuses the *whole* patch with `invalidProperties` and writes
 *    nothing — so there is never anything to roll back, and the draft is exactly as it was. This
 *    used to be a silent no-op, which is why saving an attachment once meant recreating the draft
 *    and binning the old one; that machinery is gone.
 * 2. **`destroy` on a draft leaves it in Drafts.** It adds the Trash label and removes Inbox, and a
 *    draft never had Inbox — so a "deleted" draft comes back with `mailboxIds: {drafts, trash}` and
 *    still appears in the Drafts list. Discarding is therefore an explicit mailbox patch.
 * 3. **`identityId` decides the From address.** `EmailSubmission/set` resolves it through the same
 *    list `Identity/get` publishes, so an id the server offered is an id it accepts and an id it
 *    did not is `forbiddenFrom` rather than a mail quietly sent as the account's own address. It
 *    sets the *address* only — the display name still comes from the account, on the web path too.
 */
@Singleton
class ComposeRepository
@Inject
constructor(
    @param:ApplicationContext private val context: Context,
    private val database: PlMailDatabase,
    private val clients: AccountClients,
    private val credentials: CredentialStore,
    private val mail: MailRepository,
    private val accounts: AccountsRepository,
) : DraftSender {

    /**
     * Every address the user can send as, across every account.
     *
     * Read from the cache rather than the network so the composer opens with a From row already
     * filled in. [refreshIdentities] keeps it current.
     */
    fun identities(): Flow<List<SendIdentity>> =
        combine(database.identities().observeAll(), accounts.ordered) { identities, ordered ->
            val byKey = ordered.associateBy { it.uid }

            // Grouped by account in the user's own order rather than left in
            // the identity table's, which is the *server's*. The composer opens
            // on the first entry, so this is what decides which mailbox a new
            // message is written from — and somebody who has put their personal
            // account at the top of the settings screen has already answered
            // that question.
            identities
                .mapNotNull { identity ->
                    val account = byKey[identity.accountKey] ?: return@mapNotNull null

                    SendIdentity(
                        accountKey = identity.accountKey,
                        accountName = account.name,
                        identityId = identity.identityId,
                        name = identity.name,
                        email = identity.email,
                    )
                }
                .sortedBy { ordered.indexOfFirst { account -> account.uid == it.accountKey } }
        }

    /**
     * Re-reads `Identity/get` for every account.
     *
     * Excluded from push and from delta sync on purpose: identities change only when the user edits
     * their own addresses. This runs when a composer opens, which is the one moment a stale list
     * would be visible.
     */
    suspend fun refreshIdentities() {
        val client = clients.current() ?: return
        // The same origin the account keys were built from, taken from the
        // stored connection rather than re-derived from a session URL: the
        // session reports whatever host the request arrived on, so a phone on
        // the LAN and the same phone on a VPN would compute two different
        // account keys for one account and the identities would attach to
        // neither of them.
        val server = credentials.connection.first()?.address?.origin ?: return

        client.session().accountIds.forEach { accountId ->
            runCatching {
                val request = RequestBuilder()
                val get = request.add(IdentityGet(AccountId(accountId.value)))
                val identities = client.send(request).result(get).list

                mail.replaceIdentities(StoreKey.account(server, accountId.value), identities)
            }
        }
    }

    /**
     * The message a reply or forward is built from, with its body, from the cache or the server.
     */
    suspend fun original(accountKey: String, emailId: String): Email? {
        val account = database.accounts().byUid(accountKey) ?: return null
        val client = clients.forAccount(accountKey) ?: return null

        val request = RequestBuilder()
        val get =
            request.add(
                EmailGet(
                    accountId = AccountId(account.accountId),
                    ids = listOf(EmailId(emailId)),
                    properties = EmailGet.READER_PROPERTIES,
                    fetchTextBodyValues = true,
                    fetchHtmlBodyValues = true,
                )
            )

        return client.send(request).result(get).list.firstOrNull()
    }

    /**
     * Reopens a draft that is already on the server.
     *
     * Its attachments come back as blob ids rather than as files to pick again, which is what makes
     * "undo send, change a word, send" cost nothing and re-upload nothing.
     */
    suspend fun loadDraft(accountKey: String, emailId: String): ComposeDraft? {
        val email = original(accountKey, emailId) ?: return null
        val known = database.identities().forAccount(accountKey)

        // Matched on the address the draft already carries rather than taking
        // the first of the account's identities. Now that there is one identity
        // per sendable alias, "the first" is the primary — so reopening a draft
        // written from an alias and saving it again would silently move it back
        // to the main address.
        val from = email.from.firstOrNull()?.email?.trim()?.lowercase()
        val identity =
            known.firstOrNull { it.email.trim().lowercase() == from }?.identityId
                ?: known.firstOrNull()?.identityId
                ?: return null

        return ComposeDraft(
            accountKey = accountKey,
            identityId = identity,
            to = email.to,
            cc = email.cc,
            bcc = email.bcc,
            subject = email.subject.orEmpty(),
            bodyHtml = email.htmlContent ?: email.textContent.orEmpty(),
            inReplyTo = email.inReplyTo,
            references = email.references,
            attachments = email.attachments.toStaged(),
            emailId = emailId,
            savedAttachments = email.attachments.toStaged(),
        )
    }

    /**
     * Writes the draft to the server and returns it carrying its id.
     *
     * Autosave calls this on a debounce; the send path calls it once more before submitting, so the
     * mail exists in Drafts before anything can go wrong with the submission.
     *
     * A draft that has never been saved is created; everything else is a patch, **attachments
     * included**. The attachment array is only sent when the set has actually changed — it is
     * whole-value, so re-stating a dozen blob ids on every keystroke would be pure cost, and an
     * absent key means "leave them alone".
     */
    override suspend fun save(draft: ComposeDraft): ComposeDraft {
        val account =
            database.accounts().byUid(draft.accountKey)
                ?: error("This account is no longer connected.")
        val client =
            clients.forAccount(draft.accountKey) ?: error("There is no connection to this server.")

        val accountId = AccountId(account.accountId)

        if (draft.needsCreate) {
            val uploaded = upload(client, accountId, draft.attachments)
            val created = create(client, accountId, draft, uploaded)

            return draft
                .copy(emailId = created, attachments = readBack(client, accountId, created))
                .let { it.copy(savedAttachments = it.attachments) }
        }

        if (!draft.attachmentsChanged) {
            update(client, accountId, draft, attachments = null)
            return draft
        }

        val uploaded = upload(client, accountId, draft.attachments)

        update(client, accountId, draft, attachments = uploaded)

        val attached = readBack(client, accountId, draft.emailId!!)

        return draft.copy(attachments = attached, savedAttachments = attached)
    }

    /**
     * Submits a draft that is already saved, now or at [hold].
     *
     * Separate from [save] so the draft is on the server before anything asks for it to leave: the
     * worst case of a failure here is a message still in Drafts, which is what the user would
     * expect to find.
     *
     * The returned [Submitted] carries the server's own `sendAt`, which is the only trustworthy
     * release time — a `HOLDFOR` is counted from when the request arrived, and a phone whose clock
     * is fast would otherwise promise a moment that has not happened.
     */
    override suspend fun submit(draft: ComposeDraft, hold: SendHold?): Submitted {
        val emailId = draft.emailId ?: error("This message has not been saved yet.")
        val account =
            database.accounts().byUid(draft.accountKey)
                ?: error("This account is no longer connected.")
        val client =
            clients.forAccount(draft.accountKey) ?: error("There is no connection to this server.")

        val request = RequestBuilder(Capability.USING_MAIL_SUBMISSION)
        val submission =
            request.add(
                EmailSubmissionSet.send(
                    accountId = AccountId(account.accountId),
                    emailId = EmailId(emailId),
                    identityId = IdentityId(draft.identityId),
                    drafts = binding(draft.accountKey, DRAFTS_ROLE),
                    sent = binding(draft.accountKey, SENT_ROLE),
                    hold = hold,
                )
            )

        val result = client.send(request).result(submission)

        result.failure?.let { failure -> throw refusal(draft, failure) }

        val created = result.submission

        return Submitted(
            submissionId = created?.id?.takeIf { it.isNotBlank() } ?: emailId,
            sendAt = created?.sendAt,
        )
    }

    /**
     * Declines a send the server has not released yet.
     *
     * Not an error path: a cancel that arrives too late is an ordinary outcome of a race the user
     * started, and [CancelOutcome.AlreadySent] is what says so honestly. Anything else — a
     * connection that failed, a server that refused for a reason this client did not anticipate —
     * is thrown, because "cancelled" must never be shown for a message that is on its way.
     *
     * A successful cancel leaves the draft in Drafts. There is nothing to fetch afterwards:
     * `EmailSubmission/get` answers `notFound` for a cancelled submission, because there is no row
     * to hold that state.
     */
    override suspend fun cancel(accountKey: String, submissionId: String): CancelOutcome {
        val account =
            database.accounts().byUid(accountKey) ?: error("This account is no longer connected.")
        val client =
            clients.forAccount(accountKey) ?: error("There is no connection to this server.")

        val request = RequestBuilder(Capability.USING_MAIL_SUBMISSION)
        val set = request.add(EmailSubmissionSet.cancel(AccountId(account.accountId), submissionId))

        val result = client.send(request).result(set)

        result.updateFailure?.let { failure ->
            if (failure.type == CANNOT_UNSEND) return CancelOutcome.AlreadySent

            error(
                failure.description
                    ?: failure.type.ifBlank { "The server refused to cancel this send." }
            )
        }

        return CancelOutcome.Cancelled
    }

    /**
     * Whether this account released a completed send, and when.
     *
     * The only question `EmailSubmission/get` can answer: a held submission and a cancelled one
     * both come back `notFound`, so absence means "not sent (yet)" rather than "cancelled". Used to
     * settle a schedule whose time has passed, never to poll one that has not.
     */
    override suspend fun releasedAt(accountKey: String, submissionId: String): SubmissionRecord? {
        val account = database.accounts().byUid(accountKey) ?: return null
        val client = clients.forAccount(accountKey) ?: return null

        val request = RequestBuilder(Capability.USING_MAIL_SUBMISSION)
        val get =
            request.add(EmailSubmissionGet(AccountId(account.accountId), listOf(submissionId)))

        return client.send(request).result(get).list.firstOrNull()
    }

    /**
     * How far ahead this account will let a send be scheduled, from the session.
     *
     * Zero hides the feature. Read per account rather than per server because that is where RFC
     * 8621 puts it — and because a login that reaches two mailboxes can genuinely have two answers.
     */
    suspend fun sendWindow(accountKey: String): SubmissionCapability {
        val account = database.accounts().byUid(accountKey) ?: return SubmissionCapability()
        val client = clients.forAccount(accountKey) ?: return SubmissionCapability()

        return runCatching { client.session().submission(AccountId(account.accountId)) }
            .getOrDefault(SubmissionCapability())
    }

    /**
     * Whether this account's undo window can be the server's hold.
     *
     * The ceiling has to cover the window itself, which on any plMail built since the scheduling
     * batch it does by four orders of magnitude — but reading it is what makes the fallback real
     * rather than dead code, and an instance that switched delayed send off has to keep sending.
     */
    override suspend fun submissionMode(accountKey: String): SubmissionMode {
        val capability = sendWindow(accountKey)
        val window = SendQueue.UNDO_WINDOW_MS / 1_000

        return if (capability.supportsHoldFor && capability.maxDelayedSend >= window) {
            SubmissionMode.SERVER_HOLD
        } else {
            SubmissionMode.LOCAL_DELAY
        }
    }

    /**
     * A refused submission, as a sentence naming what the user actually chose.
     *
     * `forbiddenFrom` is the one worth translating rather than passing through: it means the alias
     * in the From row is not one this account may send as — a stale `Identity/get` list, or an
     * alias deleted on the web since the composer opened — and the server's own wording names an
     * id, which is not a thing anybody picked. The list is re-read in the same breath so the picker
     * stops offering it.
     */
    private suspend fun refusal(draft: ComposeDraft, failure: SetError): Throwable {
        if (failure.type != FORBIDDEN_FROM) {
            return IllegalStateException(
                failure.description ?: failure.type.ifBlank { "The server refused to send this." }
            )
        }

        val address =
            database
                .identities()
                .forAccount(draft.accountKey)
                .firstOrNull { it.identityId == draft.identityId }
                ?.email

        runCatching { refreshIdentities() }

        return IllegalStateException(
            if (address == null) {
                "This account may not send as the address you chose. Pick another and try again."
            } else {
                "This account may not send as \"$address\" any more. Pick another address and " +
                    "try again."
            }
        )
    }

    /**
     * Throws the draft away.
     *
     * An explicit move, never `Email/set` `destroy` — reason (2) at the top of this file.
     * Destroying a draft adds Trash without removing Drafts, so the discarded message stays in the
     * Drafts list and looks like a discard that did nothing.
     */
    suspend fun discard(draft: ComposeDraft) {
        val emailId = draft.emailId ?: return
        val account = database.accounts().byUid(draft.accountKey) ?: return
        val client = clients.forAccount(draft.accountKey) ?: return

        moveToTrash(client, AccountId(account.accountId), draft.accountKey, emailId)
    }

    /** Reads the bytes of a picked file. Null when the URI has been revoked since it was staged. */
    private fun bytesOf(attachment: StagedAttachment): ByteArray? {
        val uri = attachment.uri ?: return null

        return runCatching {
            context.contentResolver.openInputStream(uri.toUri())?.use { it.readBytes() }
        }
            .getOrNull()
    }

    /**
     * Describes a picked file without reading it.
     *
     * The name and size come from the provider rather than the URI: a content URI's last path
     * segment is an opaque id on most providers, so a document called "Rechnung.pdf" would be
     * attached as "1000000042".
     */
    fun describe(uri: Uri): StagedAttachment {
        val type = context.contentResolver.getType(uri) ?: "application/octet-stream"

        val described = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null

                val nameColumn =
                    cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf {
                        it >= 0
                    }
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }

                val name = nameColumn?.let { cursor.getString(it) }
                val size =
                    sizeColumn?.takeIf { !cursor.isNull(it) }?.let { cursor.getLong(it) } ?: 0L

                name to size
            }
        }
            .getOrNull()

        return StagedAttachment(
            name = described?.first?.takeIf { it.isNotBlank() } ?: FALLBACK_NAME,
            type = type,
            size = described?.second ?: 0L,
            uri = uri.toString(),
        )
    }

    private suspend fun create(
        client: JmapClient,
        accountId: AccountId,
        draft: ComposeDraft,
        attachments: List<DraftAttachment>,
    ): String {
        val request = RequestBuilder()
        val set =
            request.add(
                EmailSet(
                    accountId = accountId,
                    create = mapOf(CREATION_ID to draft.toWire(attachments)),
                )
            )

        val result = client.send(request).result(set)

        result.notCreated.values.firstOrNull()?.let { failure ->
            error(
                failure.description ?: failure.type.ifBlank { "The server refused to save this." }
            )
        }

        return result.created[CREATION_ID]?.id?.value
            ?: error("The server saved the draft without telling us its id.")
    }

    private suspend fun update(
        client: JmapClient,
        accountId: AccountId,
        draft: ComposeDraft,
        attachments: List<DraftAttachment>?,
    ) {
        val request = RequestBuilder()
        val set =
            request.add(
                EmailSet(
                    accountId = accountId,
                    update = mapOf(EmailId(draft.emailId!!) to draft.toPatch(attachments)),
                )
            )

        val result = client.send(request).result(set)

        // `Email/set` reports per-message refusals inside a perfectly successful
        // 200. An autosave that ignored them would show "Saved" over a draft the
        // server had rejected.
        result.notUpdated.values.firstOrNull()?.let { failure ->
            // A patch carrying attachments is refused whole — subject, body and
            // all — when one blob cannot be resolved, and nothing is written. So
            // there is no rollback to do and no partial save to explain; what
            // the user needs is the name of the problem, because the server's
            // wording is an array index.
            if (attachments != null && failure.type == "invalidProperties") {
                error(
                    "One of the attached files is no longer on the server, so nothing was " +
                        "saved. Remove it and attach it again."
                )
            }

            error(
                failure.description ?: failure.type.ifBlank { "The server refused to save this." }
            )
        }
    }

    /**
     * Uploads the staged files, at send time and not before.
     *
     * Sequential rather than concurrent: the session advertises `maxConcurrentUpload`, the request
     * gate already holds callers to `maxConcurrentRequests`, and the audience runs this on hardware
     * where four simultaneous multi-megabyte POSTs is the difference between a send and a timeout.
     */
    private suspend fun upload(
        client: JmapClient,
        accountId: AccountId,
        staged: List<StagedAttachment>,
    ): List<DraftAttachment> = staged.map { attachment ->
        // Already on the server: re-sending the id re-attaches the same
        // bytes, so reopening a draft does not make the user pick its files
        // again — and does not re-upload megabytes over someone's uplink.
        attachment.blobId?.let { existing ->
            return@map DraftAttachment(
                blobId = BlobId(existing),
                type = attachment.type,
                name = attachment.name,
            )
        }

        val bytes =
            bytesOf(attachment)
                ?: error(
                    "\"${attachment.name}\" could not be read. It may have been moved or " +
                        "deleted since you attached it."
                )

        val blob = client.upload(bytes, attachment.type, accountId)

        DraftAttachment(
            blobId = BlobId(blob.blobId),
            // The server's own answer, not the type guessed from the file
            // name: it stores what it was sent and echoes that back, and
            // disagreeing with it here achieves nothing.
            type = blob.type,
            name = attachment.name,
        )
    }

    /**
     * Moves a message out of Drafts and into Trash.
     *
     * Both halves in one patch. Removing Drafts alone would leave the message in no mailbox, which
     * the server refuses outright with "An Email must belong to at least one Mailbox".
     */
    private suspend fun moveToTrash(
        client: JmapClient,
        accountId: AccountId,
        accountKey: String,
        emailId: String,
    ) {
        val trash = binding(accountKey, TRASH_ROLE) ?: return
        val drafts = binding(accountKey, DRAFTS_ROLE)

        val patch = EmailPatch.build {
            addMailbox(trash)
            drafts?.let { removeMailbox(it) }
        }

        val request = RequestBuilder()
        val set =
            request.add(EmailSet(accountId = accountId, update = mapOf(EmailId(emailId) to patch)))

        client.send(request).result(set)
    }

    /**
     * What a saved draft actually carries, by the ids the server will keep.
     *
     * Read back rather than assumed, and worth the round trip. The ids that went out name *staged
     * uploads*, which the server copies into its own attachment store and then reclaims on a timer;
     * the ids that come back are the permanent `p-` ones, and re-sending those on the next save
     * keeps the part rather than uploading it again. It also confirms the attachments landed at
     * all, which is the one thing a success response does not say.
     */
    private suspend fun readBack(
        client: JmapClient,
        accountId: AccountId,
        emailId: String,
    ): List<StagedAttachment> {
        val request = RequestBuilder()
        val get =
            request.add(
                EmailGet(
                    accountId = accountId,
                    ids = listOf(EmailId(emailId)),
                    properties = listOf("id", "attachments"),
                )
            )

        return client.send(request).result(get).list.firstOrNull()?.attachments.orEmpty().toStaged()
    }

    private suspend fun binding(accountKey: String, role: String): MailboxId? =
        database.mailboxes().byRole(accountKey, role)?.let { MailboxId(it.mailboxId) }

    private companion object {
        const val CREATION_ID = "c1"
        const val FALLBACK_NAME = "attachment"
        const val DRAFTS_ROLE = "drafts"
        const val SENT_ROLE = "sent"
        const val TRASH_ROLE = "trash"
    }
}

/**
 * Attachment parts as things the composer can show and re-send.
 *
 * Inline parts are dropped: a `cid:` image belongs to the body that references it, and listing it
 * as an attachment shows the user a file they never attached and cannot meaningfully remove.
 */
internal fun List<de.plmail.jmap.mail.EmailBodyPart>.toStaged(): List<StagedAttachment> =
    filterNot {
        it.isInline
    }
    .mapNotNull { part ->
        val blobId = part.blobId?.value ?: return@mapNotNull null

        StagedAttachment(
            name = part.name.orEmpty().ifBlank { "attachment" },
            type = part.type,
            size = part.size,
            blobId = blobId,
        )
    }

/** The full object, for a create. */
internal fun ComposeDraft.toWire(attachments: List<DraftAttachment>): DraftEmail =
    DraftEmail(
        // Empty rather than the Drafts binding: the server files a create into
        // Drafts itself and ignores this key entirely, so naming a binding here
        // would be a claim the client cannot check.
        mailboxIds = emptyList(),
        from = emptyList(),
        to = to,
        cc = cc,
        bcc = bcc,
        subject = subject.ifBlank { null },
        htmlBody = bodyHtml,
        inReplyTo = inReplyTo,
        references = references,
        attachments = attachments,
    )

/**
 * The patch for a re-save.
 *
 * Only what a composer owns. [attachments] is null for a save that has not touched them, which
 * leaves the key out — the server reads an absent key as "leave them alone", and the array is
 * whole-value, so sending it unchanged would be a dozen blob ids per keystroke for no effect. An
 * *empty* list is different and is sent: that is how the last attachment is removed.
 */
internal fun ComposeDraft.toPatch(attachments: List<DraftAttachment>?): EmailPatch =
    EmailPatch.build {
        addresses("to", to)
        addresses("cc", cc)
        addresses("bcc", bcc)
        text("subject", subject)
        html(bodyHtml)
        inReplyTo?.let { strings("inReplyTo", it) }
        references?.let { strings("references", it) }
        attachments?.let { attachments(it) }
    }

/** Compares two drafts by everything that would need saving. */
internal fun ComposeDraft.sameContentAs(other: ComposeDraft): Boolean =
    to == other.to &&
        cc == other.cc &&
        bcc == other.bcc &&
        subject == other.subject &&
        bodyHtml == other.bodyHtml &&
        attachments == other.attachments &&
        accountKey == other.accountKey

/** Whether a reply-all would reach anyone other than the sender. */
internal fun DraftComposer.ComposedDraft.isReplyAllUseful(): Boolean = cc.isNotEmpty()

internal fun List<EmailAddress>.addressesOnly(): Set<String> = mapNotNull {
    it.email?.trim()?.lowercase()?.takeIf(String::isNotEmpty)
}
    .toSet()
