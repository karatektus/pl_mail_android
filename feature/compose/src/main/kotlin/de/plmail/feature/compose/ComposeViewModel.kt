package de.plmail.feature.compose

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.plmail.core.data.CancelOutcome
import de.plmail.core.data.ComposeDraft
import de.plmail.core.data.ComposeRepository
import de.plmail.core.data.ContactSuggestions
import de.plmail.core.data.ScheduledSend
import de.plmail.core.data.ScheduledSends
import de.plmail.core.data.SendIdentity
import de.plmail.core.data.SendQueue
import de.plmail.core.data.StagedAttachment
import de.plmail.jmap.mail.DraftComposer
import de.plmail.jmap.mail.EmailAddress
import de.plmail.jmap.mail.Signatures
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The composer's state and everything that writes to the server on its behalf.
 *
 * Two decisions here are not obvious from the screen.
 *
 * **The quoted original is not in the editor.** It is held beside the draft and appended when the
 * message is saved or sent. Loading someone else's HTML — a marketing mail with a table layout, a
 * newsletter with inline styles — into a rich-text editor means round-tripping it through that
 * editor's parser, which reflows it into something the sender never wrote. Gmail collapses the
 * quote behind a chip for the same reason. It also keeps the editor's HTML support narrow enough to
 * be trustworthy: it only ever has to serialise what this user typed.
 *
 * **Autosave writes to the server, not to a local table.** Everything in the local database is a
 * cache that can be dropped and re-synced; a draft that existed only there would be the one row
 * that could not. So the draft goes to Drafts, where the web UI and every other device can see it,
 * and process death costs at most the last few seconds of typing.
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class ComposeViewModel
@Inject
constructor(
    private val compose: ComposeRepository,
    private val contacts: ContactSuggestions,
    private val sendQueue: SendQueue,
    private val scheduled: ScheduledSends,
) : ViewModel() {

    private val _state = MutableStateFlow(ComposeUiState())
    val state: StateFlow<ComposeUiState> = _state.asStateFlow()

    private var autosave: Job? = null
    private var opened = false

    /**
     * The localised text a reply and a forward are built from.
     *
     * Handed in by the screen at [open]. `:core:jmap` composes drafts and must stay Android-free,
     * so nothing down there can read a string resource — and nothing up here should be formatting
     * German on its own.
     */
    private lateinit var strings: ComposeStrings

    /**
     * Fills the composer in for [request].
     *
     * Guarded against running twice: `LaunchedEffect` re-runs on configuration changes that keep
     * the ViewModel, and a second run would overwrite everything typed since the first.
     */
    fun open(request: ComposeRequest, strings: ComposeStrings) {
        if (opened) return
        opened = true
        this.strings = strings

        viewModelScope.launch {
            // From the cache first so the From row is never empty, then
            // refreshed. An account added on another device would otherwise be
            // missing from the picker until something else happened to sync.
            val identities = compose.identities().first()

            _state.update { it.copy(identities = identities) }

            runCatching { compose.refreshIdentities() }

            val current = compose.identities().first().ifEmpty { identities }

            _state.update { it.copy(identities = current) }

            when (request) {
                ComposeRequest.New -> startBlank(current)
                is ComposeRequest.Reply -> startReply(current, request)
                is ComposeRequest.Forward -> startForward(current, request)
                is ComposeRequest.Edit -> startEdit(current, request)
                is ComposeRequest.Share -> startShare(current, request)
            }

            readSendWindow()
            watchForAutosave()
        }
    }

    // ------------------------------------------------------------------ editing

    fun setTo(addresses: List<EmailAddress>) = edit { it.copy(to = addresses) }

    fun setCc(addresses: List<EmailAddress>) = edit { it.copy(cc = addresses) }

    fun setBcc(addresses: List<EmailAddress>) = edit { it.copy(bcc = addresses) }

    fun setSubject(subject: String) = edit { it.copy(subject = subject) }

    fun setBody(html: String) = edit { it.copy(bodyHtml = html) }

    fun showCopyFields() = _state.update { it.copy(isShowingCopyFields = true) }

    /**
     * Changes who the message comes from.
     *
     * A different identity can belong to a different account, and an account is a different server
     * object graph — so a draft already saved under the old account is abandoned rather than
     * carried over. Its id would name a message in a mailbox the new account cannot see.
     */
    fun setIdentity(identity: SendIdentity) {
        val changedAccount = identity.accountKey != _state.value.draft.accountKey

        // The signature follows the address, and ONLY the signature: the swap is
        // scoped to the marked block, so a paragraph already typed above it is
        // untouched. Changing From must never cost somebody what they have
        // written -- the same rule, and the same marker, as the web composer's.
        edit { draft ->
            val body = Signatures.replaceSignature(draft.bodyHtml, identity.htmlSignature)

            if (!changedAccount) {
                draft.copy(identityId = identity.identityId, bodyHtml = body)
            } else {
                draft.copy(
                    accountKey = identity.accountKey,
                    identityId = identity.identityId,
                    bodyHtml = body,
                    emailId = null,
                    // The blobs belong to the old account too; BlobResolver
                    // filters by account and would refuse them.
                    attachments = draft.attachments.filter { it.uri != null },
                    savedAttachments = emptyList(),
                )
            }
        }

        // A different account is a different `maxDelayedSend`. One login can
        // reach two mailboxes and RFC 8621 puts the ceiling per account, so
        // carrying the old answer over would offer "send later" on a mailbox
        // that would refuse it.
        if (changedAccount) viewModelScope.launch { readSendWindow() }
    }

    /**
     * Stages picked files without reading them.
     *
     * The content URIs are held as they arrived, on the transient grant the picker gives this task
     * — deliberately not `takePersistableUriPermission`. The bytes are only ever needed between
     * tapping Send and the save that precedes the undo window, which is the same process and the
     * same task; a persisted grant would buy nothing and consume one of the limited number of them
     * the platform allows an app to hold.
     */
    fun attachPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return

        edit { draft -> draft.copy(attachments = draft.attachments + uris.map(compose::describe)) }
    }

    fun detach(attachment: StagedAttachment) = edit {
        it.copy(attachments = it.attachments - attachment)
    }

    /** Drops the quoted original entirely, which is a thing people do on purpose. */
    fun removeQuote() = _state.update { it.copy(quotedHtml = "") }

    fun toggleQuote() = _state.update { it.copy(isQuoteExpanded = !it.isQuoteExpanded) }

    // ------------------------------------------------------------- suggestions

    fun suggest(query: String) {
        viewModelScope.launch {
            _state.update { it.copy(suggestions = contacts.suggest(query)) }
        }
    }

    fun clearSuggestions() = _state.update { it.copy(suggestions = emptyList()) }

    fun mayReadDeviceContacts(): Boolean = contacts.mayReadDeviceContacts()

    // ------------------------------------------------------------------ actions

    /**
     * Hands the message to the send queue and reports that the screen may close.
     *
     * The composer closes immediately and the undo window runs outside it — which is why the queue
     * owns the work rather than `viewModelScope`, and why nothing here waits for a result.
     */
    fun send(): Boolean = hand(at = null)

    /**
     * Hands the message over to be released at [at] rather than now.
     *
     * Nothing here checks [at] against the ceiling beyond what the picker already bounded: the
     * ceiling is `maxDelayedSend` from the session, and a second copy of the rule in this class
     * would start refusing sends the server would accept the day an instance raised it.
     */
    fun sendLater(at: Instant): Boolean = hand(at)

    private fun hand(at: Instant?): Boolean {
        val draft = _state.value.draft

        if (!draft.hasRecipients) {
            _state.update { it.copy(error = ComposeError.NoRecipients) }
            return false
        }

        // Cancelled rather than left to fire: an autosave landing after the
        // queue has taken over would write the pre-send body over the one being
        // sent, and on a draft the queue may already have replaced.
        autosave?.cancel()
        sendQueue.enqueue(draft.withQuote(_state.value.quotedHtml), at)

        return true
    }

    /**
     * Calls back a schedule from the composer the draft was reopened in.
     *
     * The draft stays in Drafts either way — a cancelled submission leaves the message exactly as
     * it was — so the composer stays open on it and the user can send, edit or reschedule. What
     * changes is only whether this device still has a promise to keep.
     */
    fun cancelSchedule() {
        val send = _state.value.scheduled ?: return

        viewModelScope.launch {
            runCatching { sendQueue.cancelScheduled(send) }
                .onSuccess { outcome ->
                    _state.update {
                        it.copy(
                            scheduled = null,
                            error =
                                if (outcome == CancelOutcome.AlreadySent) ComposeError.AlreadySent
                                else null,
                        )
                    }
                }
                .onFailure { failure ->
                    _state.update {
                        it.copy(
                            error =
                                ComposeError.CancelFailed(
                                    failure.message
                                        ?: "The scheduled send could not be called back."
                                )
                        )
                    }
                }
        }
    }

    /**
     * Leaves the composer, saving if there is anything worth saving.
     *
     * An empty composer is *discarded* rather than saved: opening the composer, thinking better of
     * it and going back must not leave a blank draft behind, and a reply's quoted original does not
     * count as content the user produced.
     */
    fun close(onClosed: () -> Unit) {
        val state = _state.value
        autosave?.cancel()

        viewModelScope.launch {
            if (state.draft.isEmpty(state.quotedHtml)) {
                runCatching { compose.discard(state.draft) }
            } else {
                runCatching { compose.save(state.draft.withQuote(state.quotedHtml)) }
            }

            onClosed()
        }
    }

    fun discard(onDiscarded: () -> Unit) {
        val draft = _state.value.draft
        autosave?.cancel()

        viewModelScope.launch {
            runCatching { compose.discard(draft) }
            onDiscarded()
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    // ------------------------------------------------------------------ internals

    private fun edit(change: (ComposeDraft) -> ComposeDraft) {
        _state.update { it.copy(draft = change(it.draft), isSaved = false) }
    }

    private fun startBlank(identities: List<SendIdentity>) {
        val identity =
            identities.firstOrNull()
                ?: run {
                    _state.update { it.copy(error = ComposeError.NoIdentity, isLoading = false) }
                    return
                }

        _state.update {
            it.copy(
                draft =
                    ComposeDraft(
                        accountKey = identity.accountKey,
                        identityId = identity.identityId,
                        bodyHtml = Signatures.block(identity.htmlSignature),
                    ),
                isLoading = false,
            )
        }
    }

    private suspend fun startReply(identities: List<SendIdentity>, request: ComposeRequest.Reply) {
        val original =
            compose.original(request.accountKey, request.emailId)
                ?: run {
                    _state.update {
                        it.copy(error = ComposeError.OriginalUnavailable, isLoading = false)
                    }
                    return
                }

        // Answering from the account the message arrived in, which is almost
        // always what is meant -- replying to a work mail from a private address
        // is a mistake the composer should not make on the user's behalf.
        val identity =
            identities.firstOrNull { it.accountKey == request.accountKey }
                ?: identities.firstOrNull()
                ?: run {
                    _state.update { it.copy(error = ComposeError.NoIdentity, isLoading = false) }
                    return
                }

        val composed =
            DraftComposer.reply(
                original = original,
                mode =
                    if (request.all) DraftComposer.ReplyMode.REPLY_ALL
                    else DraftComposer.ReplyMode.REPLY,
                // Every address this user can send as, so reply-all does not
                // copy them in on their own reply.
                self = identities.map { it.email }.toSet(),
                attribution = strings.attribution(original),
            )

        _state.update {
            it.copy(
                draft =
                    ComposeDraft(
                        accountKey = identity.accountKey,
                        identityId = identity.identityId,
                        to = composed.to,
                        cc = composed.cc,
                        subject = composed.subject.orEmpty(),
                        // Above the quote, because the quote is held beside the
                        // draft and appended at save. A sign-off under somebody
                        // else's message is a sign-off nobody reads.
                        bodyHtml = Signatures.block(identity.htmlSignature),
                        inReplyTo = composed.inReplyTo,
                        references = composed.references,
                    ),
                quotedHtml = composed.quotedHtml,
                isShowingCopyFields = composed.cc.isNotEmpty(),
                isLoading = false,
            )
        }
    }

    private suspend fun startForward(
        identities: List<SendIdentity>,
        request: ComposeRequest.Forward,
    ) {
        val original =
            compose.original(request.accountKey, request.emailId)
                ?: run {
                    _state.update {
                        it.copy(error = ComposeError.OriginalUnavailable, isLoading = false)
                    }
                    return
                }

        val identity =
            identities.firstOrNull { it.accountKey == request.accountKey }
                ?: identities.firstOrNull()
                ?: run {
                    _state.update { it.copy(error = ComposeError.NoIdentity, isLoading = false) }
                    return
                }

        val composed =
            DraftComposer.forward(original, strings.forwardLabels, strings.date(original))

        _state.update {
            it.copy(
                draft =
                    ComposeDraft(
                        accountKey = identity.accountKey,
                        identityId = identity.identityId,
                        subject = composed.subject.orEmpty(),
                        bodyHtml = Signatures.block(identity.htmlSignature),
                        // The original's own files travel with the forward, by
                        // blob id -- nothing is downloaded to the phone and
                        // re-uploaded to the server it came from.
                        attachments = original.attachments.toStagedAttachments(),
                    ),
                quotedHtml = composed.quotedHtml,
                isLoading = false,
            )
        }
    }

    /**
     * Opens on what another app handed over.
     *
     * Closest to [startBlank] of the four: there is no original to fetch and no draft on the server
     * to load, only fields to fill in and an identity to pick. The first identity, because the
     * intent names no account — see [ComposeRequest.Share].
     *
     * The attachments are read from the cache rather than from the intent, and are already there by
     * the time this runs: [SharedAttachmentStore] copied them before the composer was mounted. A
     * directory that has since gone — swept, or reclaimed under storage pressure — yields null and
     * is dropped, which is the only case where the composer opens quieter than it should.
     * Everything else that went wrong is named in [ComposeRequest.Share.tooLarge] and
     * [ComposeRequest.Share.unreadable] and is turned into a message here.
     *
     * The draft is left `isSaved = false`, so the ordinary autosave picks it up three seconds later
     * and uploads the files. That is what eventually retires the local copies: once the save comes
     * back with blob ids, nothing reads them again.
     */
    private fun startShare(identities: List<SendIdentity>, request: ComposeRequest.Share) {
        val identity =
            identities.firstOrNull()
                ?: run {
                    _state.update { it.copy(error = ComposeError.NoIdentity, isLoading = false) }
                    return
                }

        val draft = request.asDraft(identity)

        _state.update {
            it.copy(
                draft = draft,
                // Opened when there is something in them, because a share that
                // filled a Cc line the user cannot see is a share that copies
                // somebody in silently.
                isShowingCopyFields = draft.cc.isNotEmpty() || draft.bcc.isNotEmpty(),
                error = request.refusal(),
                isLoading = false,
            )
        }
    }

    private suspend fun startEdit(identities: List<SendIdentity>, request: ComposeRequest.Edit) {
        val draft =
            compose.loadDraft(request.accountKey, request.emailId)
                ?: run {
                    _state.update {
                        it.copy(error = ComposeError.OriginalUnavailable, isLoading = false)
                    }
                    return
                }

        _state.update {
            it.copy(
                draft = draft,
                scheduled = liveSchedule(request.accountKey, request.emailId),
                isLoading = false,
                isSaved = true,
            )
        }
    }

    /**
     * The schedule this draft is still waiting on, or null.
     *
     * A record whose release time has passed is *confirmed* before it is shown, because the two
     * things it could mean look identical from here: the mail went out on time, or the worker is
     * behind. `EmailSubmission/get` resolves only a completed send, so an answer means the message
     * has left — and offering "cancel this send" over a message already delivered is the exact lie
     * this whole path exists to avoid.
     */
    private suspend fun liveSchedule(accountKey: String, emailId: String): ScheduledSend? {
        val record = scheduled.forDraft(accountKey, emailId) ?: return null

        if (record.isPendingAt(System.currentTimeMillis())) return record

        val released = runCatching { compose.releasedAt(accountKey, emailId) }.getOrNull()

        if (released != null) scheduled.forget(accountKey, emailId)

        return record.takeIf { released == null }
    }

    private suspend fun readSendWindow() {
        val capability = compose.sendWindow(_state.value.draft.accountKey)

        _state.update { it.copy(submission = capability) }
    }

    /**
     * Saves a few seconds after typing stops.
     *
     * Debounced rather than per keystroke: every save is a round trip to someone's home server, and
     * a composer that sent one per character would be both slow and rude. `distinctUntilChanged` on
     * the draft itself means moving the cursor or reopening the Cc field costs nothing.
     */
    private fun watchForAutosave() {
        autosave = viewModelScope.launch {
            _state
                .map { it.draft to it.quotedHtml }
                .distinctUntilChanged()
                .debounce(AUTOSAVE_DELAY_MS)
                .collect { (draft, quote) ->
                    if (draft.isEmpty(quote)) return@collect

                    runCatching { compose.save(draft.withQuote(quote)) }
                        .onSuccess { saved ->
                            // The id and the attachment blobs the save
                            // assigned, folded back in without touching
                            // anything the user has typed since -- copying
                            // the whole draft back would undo those
                            // keystrokes.
                            _state.update {
                                it.copy(
                                    draft =
                                        it.draft.copy(
                                            emailId = saved.emailId,
                                            attachments =
                                                if (it.draft.attachments == draft.attachments) {
                                                    saved.attachments
                                                } else {
                                                    it.draft.attachments
                                                },
                                            savedAttachments = saved.savedAttachments,
                                        ),
                                    isSaved = true,
                                    error = null,
                                )
                            }
                        }
                        .onFailure { failure ->
                            _state.update {
                                it.copy(
                                    error =
                                        ComposeError.SaveFailed(
                                            failure.message ?: "The draft could not be saved."
                                        )
                                )
                            }
                        }
                }
        }
    }

    private companion object {
        const val AUTOSAVE_DELAY_MS = 3_000L
    }
}

/**
 * A share as the draft it opens.
 *
 * A free function rather than four lines inside the ViewModel, because it is the whole of what
 * "another app's intent becomes a message" means and it has no collaborators: a request, an
 * identity, and files that are already on disk. That makes it the piece a test can actually hold —
 * the ViewModel around it needs a database, a client pool and a send queue to exist at all.
 *
 * Addresses go through [parseAddresses], the same parser the recipient field uses on pasted text,
 * so `Name <address>` and semicolon-separated lists work here for free and an address that would be
 * refused is refused in one place rather than two.
 */
internal fun ComposeRequest.Share.asDraft(identity: SendIdentity): ComposeDraft =
    ComposeDraft(
        accountKey = identity.accountKey,
        identityId = identity.identityId,
        to = to.flatMap { it.parseAddresses() },
        cc = cc.flatMap { it.parseAddresses() },
        bcc = bcc.flatMap { it.parseAddresses() },
        subject = subject,
        // The shared text above the signature, the same way a reply puts the
        // sign-off above the quote. Escaped rather than inserted: shared text is
        // somebody else's, and a page title containing `<b>` must not start
        // rendering as markup in the editor.
        bodyHtml = text.asBodyHtml() + Signatures.block(identity.htmlSignature),
        // Anything whose directory has gone is dropped rather than carried as a
        // row pointing at nothing. It is the one case where the composer opens
        // quieter than the share was.
        attachments = attachments.mapNotNull(::stagedAttachment),
    )

/**
 * What to say about the files a share could not take, or null when it took them all.
 *
 * Size first when both happened. There is one error slot on the state and a snackbar shows one
 * message, and of the two this is the one the user can act on — the file exists and is theirs, it
 * simply has to go another way.
 */
internal fun ComposeRequest.Share.refusal(): ComposeError? =
    when {
        tooLarge.isNotEmpty() -> ComposeError.AttachmentsTooLarge(tooLarge, MAX_MEGABYTES)
        unreadable.isNotEmpty() -> ComposeError.AttachmentsUnreadable(unreadable)
        else -> null
    }

/**
 * Plain text as a body the editor can hold.
 *
 * Escaped, then broken at blank lines into paragraphs with single newlines as `<br>` inside them.
 * The shape matters more than it looks: shared text is usually a URL and a title, or a quoted
 * paragraph, and collapsing all of it into one run of `<br>` produces a block the editor cannot be
 * navigated out of sensibly.
 *
 * The escaping is four lines rather than a call to `:core:jmap`'s: that one is `internal` to its
 * own module, and the ways out of that are widening the API of a module this one is not allowed to
 * touch, or copying four lines. Note the order — `&` first, or every entity written after it is
 * escaped a second time.
 */
internal fun String.asBodyHtml(): String {
    val text = replace("\r\n", "\n").replace('\r', '\n').trim()
    if (text.isEmpty()) return ""

    return text.split(Regex("\n{2,}")).joinToString("") { paragraph ->
        "<p>" + paragraph.escapedForHtml().replace("\n", "<br>") + "</p>"
    }
}

private fun String.escapedForHtml(): String =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

/** The message with its quoted original appended, which is the form that is actually sent. */
internal fun ComposeDraft.withQuote(quotedHtml: String): ComposeDraft =
    if (quotedHtml.isBlank()) this else copy(bodyHtml = bodyHtml + quotedHtml)

private fun List<de.plmail.jmap.mail.EmailBodyPart>.toStagedAttachments(): List<StagedAttachment> =
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
