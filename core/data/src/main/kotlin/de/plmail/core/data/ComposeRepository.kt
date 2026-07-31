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
import de.plmail.jmap.methods.DraftAttachment
import de.plmail.jmap.methods.DraftEmail
import de.plmail.jmap.methods.EmailGet
import de.plmail.jmap.methods.EmailPatch
import de.plmail.jmap.methods.EmailSet
import de.plmail.jmap.methods.EmailSubmissionSet
import de.plmail.jmap.methods.IdentityGet
import de.plmail.jmap.protocol.AccountId
import de.plmail.jmap.protocol.BlobId
import de.plmail.jmap.protocol.EmailId
import de.plmail.jmap.protocol.IdentityId
import de.plmail.jmap.protocol.MailboxId
import de.plmail.jmap.protocol.RequestBuilder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/**
 * Saving, sending and discarding what the composer is holding.
 *
 * Three server behaviours shape everything below, all three established by probing the running
 * server rather than by reading the PHP, and all three of them silent:
 *
 * 1. **`attachments` can only be set when the draft is created.** `Email/set` update lists
 *    `attachments` among the properties a draft may change, accepts one, reports `updated`, and
 *    changes nothing — `JmapDraftWriter::update()` never looks at the key. A composer that saved
 *    first and attached second would send a message with no attachment and no error anywhere.
 * 2. **`destroy` on a draft leaves it in Drafts.** It adds the Trash label and removes Inbox, and a
 *    draft never had Inbox — so a "deleted" draft comes back with `mailboxIds: {drafts, trash}` and
 *    still appears in the Drafts list. Discarding is therefore an explicit mailbox patch.
 * 3. **The identity is not honoured.** Neither `from` on the create nor `identityId` on the
 *    submission reaches the sent message; `JmapDraftWriter` sets the From from the account. See
 *    `docs/SERVER_REQUESTS.md` — the picker still chooses the *account*, which is real.
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
) : DraftSender {

    /**
     * Every address the user can send as, across every account.
     *
     * Read from the cache rather than the network so the composer opens with a From row already
     * filled in. [refreshIdentities] keeps it current.
     */
    fun identities(): Flow<List<SendIdentity>> =
        combine(database.identities().observeAll(), database.accounts().observeAll()) {
            identities,
            accounts ->
            val byKey = accounts.associateBy { it.uid }

            identities.mapNotNull { identity ->
                val account = byKey[identity.accountKey] ?: return@mapNotNull null

                SendIdentity(
                    accountKey = identity.accountKey,
                    accountName = account.name,
                    identityId = identity.identityId,
                    name = identity.name,
                    email = identity.email,
                )
            }
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
        val identity =
            database.identities().forAccount(accountKey).firstOrNull()?.identityId ?: return null

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
     * Autosave calls this on a debounce; [send] calls it once more before submitting, so the mail
     * exists in Drafts before the undo window starts and a process death during that window loses
     * nothing.
     *
     * A change to the *attachments* forces a create, for reason (1) at the top of this file — there
     * is no patch that adds or removes one. Everything else is an ordinary update, so typing after
     * attaching a file costs one round trip rather than a new draft per keystroke. When a create
     * replaces a draft that was already saved, the previous copy is moved to Trash rather than left
     * behind: two drafts of one message in the list is worse than one in the bin.
     */
    override suspend fun save(draft: ComposeDraft): ComposeDraft {
        val account =
            database.accounts().byUid(draft.accountKey)
                ?: error("This account is no longer connected.")
        val client =
            clients.forAccount(draft.accountKey) ?: error("There is no connection to this server.")

        val accountId = AccountId(account.accountId)

        if (!draft.needsCreate) {
            update(client, accountId, draft)
            return draft
        }

        val uploaded = upload(client, accountId, draft.attachments)
        val created = create(client, accountId, draft, uploaded)

        draft.emailId?.let { previous ->
            // Best effort: a shell left in Drafts is untidy, a send that failed
            // because tidying failed is not acceptable.
            runCatching { moveToTrash(client, accountId, draft.accountKey, previous) }
        }

        // Read back rather than assumed. The ids that went out name *staged
        // uploads*, which the server copies into its own attachment store and
        // then reclaims on a timer; the ids that come back are the permanent
        // ones. It also confirms the attachments landed at all, which is the one
        // thing this server's success response does not tell you.
        val attached =
            if (uploaded.isEmpty()) emptyList() else attachmentsOf(client, accountId, created)

        return draft.copy(emailId = created, attachments = attached, savedAttachments = attached)
    }

    /**
     * Submits a draft that is already saved.
     *
     * Separate from [save] because the undo window sits between the two: the mail is on the server
     * as a draft for those seconds, and only this call makes it leave.
     */
    override suspend fun submit(draft: ComposeDraft) {
        val emailId = draft.emailId ?: error("This message has not been saved yet.")
        val account =
            database.accounts().byUid(draft.accountKey)
                ?: error("This account is no longer connected.")
        val client =
            clients.forAccount(draft.accountKey) ?: error("There is no connection to this server.")

        val request = RequestBuilder()
        val submission =
            request.add(
                EmailSubmissionSet.send(
                    accountId = AccountId(account.accountId),
                    emailId = EmailId(emailId),
                    identityId = IdentityId(draft.identityId),
                    drafts = binding(draft.accountKey, DRAFTS_ROLE),
                    sent = binding(draft.accountKey, SENT_ROLE),
                )
            )

        val result = client.send(request).result(submission)

        result.failure?.let { failure ->
            error(
                failure.description ?: failure.type.ifBlank { "The server refused to send this." }
            )
        }
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

    private suspend fun update(client: JmapClient, accountId: AccountId, draft: ComposeDraft) {
        val request = RequestBuilder()
        val set =
            request.add(
                EmailSet(
                    accountId = accountId,
                    update = mapOf(EmailId(draft.emailId!!) to draft.toPatch()),
                )
            )

        val result = client.send(request).result(set)

        // `Email/set` reports per-message refusals inside a perfectly successful
        // 200. An autosave that ignored them would show "Saved" over a draft the
        // server had rejected.
        result.notUpdated.values.firstOrNull()?.let { failure ->
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

    /** What a saved draft actually carries, by the ids the server will keep. */
    private suspend fun attachmentsOf(
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
 * Only what a composer owns. `attachments` is deliberately absent even though the server lists it
 * among the patchable draft properties: it accepts the key, answers `updated`, and drops it.
 */
internal fun ComposeDraft.toPatch(): EmailPatch = EmailPatch.build {
    addresses("to", to)
    addresses("cc", cc)
    addresses("bcc", bcc)
    text("subject", subject)
    html(bodyHtml)
    inReplyTo?.let { strings("inReplyTo", it) }
    references?.let { strings("references", it) }
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
