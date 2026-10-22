package de.plmail.feature.compose

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.plmail.core.data.CancelOutcome
import de.plmail.core.data.ComposeDraft
import de.plmail.core.data.ComposeRepository
import de.plmail.core.data.ContactSuggestions
import de.plmail.core.data.DecryptedContent
import de.plmail.core.data.DecryptionResult
import de.plmail.core.data.EncryptionBlock
import de.plmail.core.data.EncryptionMode
import de.plmail.core.data.EncryptionPlan
import de.plmail.core.data.RecipientKeys
import de.plmail.core.data.ReopenedEncryption
import de.plmail.core.data.ScheduledSend
import de.plmail.core.data.ScheduledSends
import de.plmail.core.data.SealAs
import de.plmail.core.data.SendIdentity
import de.plmail.core.data.SendQueue
import de.plmail.core.data.StagedAttachment
import de.plmail.core.data.including
import de.plmail.core.data.replySender
import de.plmail.core.data.withDecrypted
import de.plmail.jmap.mail.DraftComposer
import de.plmail.jmap.mail.Email
import de.plmail.jmap.mail.EmailAddress
import de.plmail.jmap.mail.Signatures
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
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
 * **Nor is the signature.** The same reasoning, for the user's own HTML this time: a sign-off is
 * often a table with a logo, and it carries the marker the web swaps it by. It is held as
 * [ComposeUiState.signatureHtml] and joined on with the quote, in [outgoing].
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
    private var keyWatch: Job? = null
    private var opening: Job? = null
    private var opened = false

    /** Set while a send is being decided, so a second tap does not send it twice. */
    private var handing = false

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

        opening = viewModelScope.launch {
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

            // The editor loads what was just put into the draft: a reopened
            // draft's text, a share's. Without this it opened empty, and the
            // first keystroke replaced the draft's body with what it held.
            _state.update { it.copy(bodyLoads = it.bodyLoads + 1) }

            readSendWindow()
            watchForAutosave()
            watchRecipientKeys()
        }
    }

    // ------------------------------------------------------------------ editing

    fun setTo(addresses: List<EmailAddress>) = edit { it.copy(to = addresses) }

    fun setCc(addresses: List<EmailAddress>) = edit { it.copy(cc = addresses) }

    fun setBcc(addresses: List<EmailAddress>) = edit { it.copy(bcc = addresses) }

    fun setSubject(subject: String) = edit { it.copy(subject = subject) }

    /**
     * What the editor holds now.
     *
     * Ignored until the draft has loaded. The editor reports its empty first frame before [open]
     * has finished, and taking that would put an empty body over the draft being opened.
     */
    fun setBody(html: String) {
        if (_state.value.isLoading) return

        edit { it.copy(bodyHtml = html) }
    }

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

        // The signature follows the address. It is held beside the body, so the
        // swap cannot touch a word of what has been typed.
        _state.update { state ->
            val draft = state.draft

            state.copy(
                draft =
                    if (!changedAccount) {
                        draft.copy(identityId = identity.identityId)
                    } else {
                        draft.copy(
                            accountKey = identity.accountKey,
                            identityId = identity.identityId,
                            emailId = null,
                            savedSealed = false,
                            // The blobs belong to the old account too; BlobResolver
                            // filters by account and would refuse them.
                            attachments = draft.attachments.filter { it.uri != null },
                            savedAttachments = emptyList(),
                        )
                    },
                signatureHtml = identity.htmlSignature,
                isSaved = false,
            )
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
    fun removeQuote() = _state.update { it.copy(quotedHtml = "", isSaved = false) }

    fun toggleQuote() = _state.update { it.copy(isQuoteExpanded = !it.isQuoteExpanded) }

    /** Sends this one message without a sign-off. The next one is signed again. */
    fun removeSignature() = _state.update { it.copy(signatureHtml = "", isSaved = false) }

    /**
     * The lock in the app bar (#9): from the automatic choice to the opposite of what it would do,
     * then between on and off. It does not go back to automatic, as in the web app.
     */
    fun toggleEncryption() = _state.update { state ->
        val next =
            when (state.encryptionMode) {
                EncryptionMode.AUTO ->
                    if (state.encryption.encrypt) EncryptionMode.OFF else EncryptionMode.ON
                EncryptionMode.ON -> EncryptionMode.OFF
                EncryptionMode.OFF -> EncryptionMode.ON
            }

        state.copy(encryptionMode = next, isSaved = false)
    }

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
     * Hands the message to the send queue and calls [onHanded] once it has, so the screen closes.
     *
     * The composer closes as soon as the message is handed over and the undo window runs outside it
     * — which is why the queue owns the work rather than `viewModelScope`, and why nothing here
     * waits for the send itself. What it does wait for is the encryption decision; see [hand].
     */
    fun send(onHanded: () -> Unit) = hand(at = null, onHanded)

    /**
     * Hands the message over to be released at [at] rather than now.
     *
     * Nothing here checks [at] against the ceiling beyond what the picker already bounded: the
     * ceiling is `maxDelayedSend` from the session, and a second copy of the rule in this class
     * would start refusing sends the server would accept the day an instance raised it.
     */
    fun sendLater(at: Instant, onHanded: () -> Unit) = hand(at, onHanded)

    /**
     * Decides, then hands over. The decision is whether the message goes out end-to-end encrypted,
     * and it is taken again here with the recipients' keys asked for once more — a key found or
     * accepted since the composer last asked changes it — so [onHanded] closes the screen only once
     * the message has actually been handed to the queue. A message that is to be encrypted and
     * cannot be stays open with the reason.
     */
    private fun hand(at: Instant?, onHanded: () -> Unit) {
        if (handing || _state.value.isUnopenable) return

        if (!_state.value.draft.hasRecipients) {
            _state.update { it.copy(error = ComposeError.NoRecipients) }
            return
        }

        handing = true

        viewModelScope.launch {
            try {
                val plan = decide() ?: return@launch

                plan.blocked?.let { block ->
                    _state.update { it.copy(error = ComposeError.EncryptionBlocked(block)) }
                    return@launch
                }

                val state = _state.value
                val outgoing =
                    state.draft
                        .outgoing(state.signatureHtml, state.quotedHtml)
                        .sealedFor(plan, state.identity)
                        ?: run {
                            _state.update {
                                it.copy(
                                    error = ComposeError.EncryptionBlocked(EncryptionBlock.NoOwnKey)
                                )
                            }
                            return@launch
                        }

                // Protected keys are opened here, where the person can be asked,
                // and not in the queue, which runs after the screen has gone.
                if (plan.encrypt && !compose.openSigningKeys()) {
                    _state.update {
                        it.copy(error = ComposeError.EncryptionBlocked(EncryptionBlock.KeysLocked))
                    }
                    return@launch
                }

                // Cancelled rather than left to fire: an autosave landing after
                // the queue has taken over would write the pre-send body over
                // the one being sent, and on a draft the queue may already have
                // replaced.
                autosave?.cancel()
                sendQueue.enqueue(outgoing, at)

                finish()
                onHanded()
            } finally {
                handing = false
            }
        }
    }

    /**
     * The encryption decision for the message as it stands now, with the recipients' keys asked for
     * again. Null when they could not be asked and the answer matters — the error then says so.
     *
     * Nothing is asked where encryption does not apply: a server without it, or a device without
     * keys, answered that once already.
     */
    private suspend fun decide(): EncryptionPlan? {
        val state = _state.value
        if (state.recipientKeys == RecipientKeys.Unavailable) return state.encryption

        val from = state.identity?.email ?: return state.encryption
        val addresses = (state.draft.to + state.draft.cc + state.draft.bcc).mapNotNull { it.email }

        val keys = runCatching {
            compose.recipientKeys(
                state.draft.accountKey,
                from,
                addresses,
                lookUp = state.encryption.pending,
            )
        }
            .getOrElse {
                _state.update { it.copy(error = ComposeError.KeysUnchecked) }
                return null
            }

        _state.update { it.copy(recipientKeys = keys) }

        return _state.value.encryption
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
            when {
                // Saving would put an empty message over the one that could not
                // be opened.
                state.isUnopenable -> Unit
                state.draft.isEmpty(state.quotedHtml) ->
                    runCatching { compose.discard(state.draft) }
                else -> {
                    // An encrypted message that cannot be encrypted right now is
                    // not saved at all: saving it in the clear is the one thing
                    // that must not happen to it. What was saved before stays.
                    val plan = runCatching { decide() }.getOrNull()
                    val current = _state.value

                    // Nor saved again when nothing changed: each save of an
                    // encrypted draft is a new import, and the old one goes to
                    // Trash for nothing.
                    val unchanged =
                        plan?.encrypt == true && current.draft.savedSealed && current.isSaved

                    val worthSaving =
                        plan != null && !(plan.encrypt && plan.blocked != null) && !unchanged

                    // Protected keys are asked for only when there is something to
                    // save with them, and a person who declines saves nothing.
                    if (
                        plan != null && worthSaving && (!plan.encrypt || compose.openSigningKeys())
                    ) {
                        current.draft
                            .outgoing(current.signatureHtml, current.quotedHtml)
                            .sealedFor(plan, current.identity)
                            ?.let { runCatching { compose.save(it) } }
                    }
                }
            }

            finish()
            onClosed()
        }
    }

    fun discard(onDiscarded: () -> Unit) {
        val draft = _state.value.draft
        autosave?.cancel()

        viewModelScope.launch {
            runCatching { compose.discard(draft) }
            finish()
            onDiscarded()
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    /**
     * Puts the composer back to nothing once the message it held has been dealt with — saved,
     * discarded or handed to the queue.
     *
     * On a phone the composer is a screen of the mail activity rather than a destination of its
     * own, so this ViewModel belongs to the activity and outlives the screen: without this, the
     * next message written in the same session opened on the last one, and a reply opened as the
     * previous message because [open] had already run. The guard in [open] is for a configuration
     * change during one composer, which still keeps everything.
     */
    private fun finish() {
        opening?.cancel()
        autosave?.cancel()
        keyWatch?.cancel()
        opening = null
        autosave = null
        keyWatch = null
        opened = false
        _state.value = ComposeUiState()
    }

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
                    ),
                signatureHtml = identity.htmlSignature,
                isLoading = false,
            )
        }
    }

    private suspend fun startReply(identities: List<SendIdentity>, request: ComposeRequest.Reply) {
        val fetched =
            compose.original(request.accountKey, request.emailId)
                ?: run {
                    _state.update {
                        it.copy(error = ComposeError.OriginalUnavailable, isLoading = false)
                    }
                    return
                }

        val (original, _) = quotable(request.accountKey, fetched)

        // Answering from the address the mail was sent to, which the server
        // names -- an alias, or an address at a catch-all domain that is in
        // no list of identities and is added to the picker for this message.
        // Failing that, from the account the message arrived in: replying to
        // a work mail from a private address is a mistake the composer should
        // not make on the user's behalf.
        val identity =
            answering(original, request.accountKey, identities)
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
                // copy them in on their own reply -- the one being answered
                // from included, which for a catch-all address is in no list.
                self = (identities + identity).map { it.email }.toSet(),
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
                        inReplyTo = composed.inReplyTo,
                        references = composed.references,
                    ),
                // Above the quote, which is where [outgoing] puts it. A sign-off
                // under somebody else's message is a sign-off nobody reads.
                signatureHtml = identity.htmlSignature,
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
        val fetched =
            compose.original(request.accountKey, request.emailId)
                ?: run {
                    _state.update {
                        it.copy(error = ComposeError.OriginalUnavailable, isLoading = false)
                    }
                    return
                }

        val (original, decrypted) = quotable(request.accountKey, fetched)

        // The same sender a reply would have: a forward of mail that came in
        // for an alias goes out from that alias, as it does in the browser.
        val identity =
            answering(original, request.accountKey, identities)
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
                        // The original's own files travel with the forward, by
                        // blob id -- nothing is downloaded to the phone and
                        // re-uploaded to the server it came from. Those of an
                        // encrypted original are inside its ciphertext, so they
                        // come from what was decrypted.
                        attachments =
                            decrypted?.attachments?.map(compose::outgoingAttachment)
                                ?: original.attachments.toStagedAttachments(),
                    ),
                signatureHtml = identity.htmlSignature,
                quotedHtml = composed.quotedHtml,
                isLoading = false,
            )
        }
    }

    /**
     * [original] as a reply or forward can quote it (#9): decrypted when it is end-to-end
     * encrypted, with what was decrypted beside it. An answer to encrypted mail is encrypted, as in
     * the web app, so the mode is set to on here — and stays on when the original would not open,
     * because the answer is still to somebody who wrote encrypted.
     */
    private suspend fun quotable(
        accountKey: String,
        original: Email,
    ): Pair<Email, DecryptedContent?> {
        if (!original.needsDecryption) return original to null

        _state.update { it.copy(encryptionMode = EncryptionMode.ON) }

        return when (val result = compose.decrypted(accountKey, original)) {
            is DecryptionResult.Opened -> original.withDecrypted(result.content) to result.content
            is DecryptionResult.Failed -> {
                _state.update { it.copy(error = ComposeError.OriginalNotDecrypted) }
                original to null
            }
        }
    }

    /**
     * Who a reply to, or a forward of, [original] is sent as — and makes sure the picker has a row
     * for them.
     *
     * [Email.replySender] is the server's answer. The fallbacks under it are what this did before
     * the server gave one, and still what happens against a plMail that does not: the first
     * identity of the account the message is in, then the first of any.
     */
    private fun answering(
        original: Email,
        accountKey: String,
        identities: List<SendIdentity>,
    ): SendIdentity? {
        val named = original.replySender(accountKey, identities)

        if (named != null) _state.update { it.copy(identities = it.identities.including(named)) }

        return named
            ?: identities.firstOrNull { it.accountKey == accountKey }
            ?: identities.firstOrNull()
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
                signatureHtml = identity.htmlSignature,
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
        val reopened =
            compose.reopen(request.accountKey, request.emailId)
                ?: run {
                    _state.update {
                        it.copy(error = ComposeError.OriginalUnavailable, isLoading = false)
                    }
                    return
                }
        // The sign-off comes out of the body and is held beside it, as for
        // every other draft. A draft saved without one stays without one.
        val (typed, signature) = Signatures.split(reopened.draft.bodyHtml)
        val draft = reopened.draft.copy(bodyHtml = typed)

        val encryption = reopened.encryption
        val unopenable =
            encryption == ReopenedEncryption.UNREADABLE ||
                encryption == ReopenedEncryption.SEALED_ON_WEB

        _state.update {
            it.copy(
                draft = draft,
                signatureHtml = signature.orEmpty(),
                encryptionMode =
                    if (encryption == ReopenedEncryption.OPENED) EncryptionMode.ON
                    else it.encryptionMode,
                isUnopenable = unopenable,
                error =
                    when (encryption) {
                        ReopenedEncryption.SEALED_ON_WEB -> ComposeError.SealedOnWeb
                        ReopenedEncryption.UNREADABLE -> ComposeError.DraftNotDecrypted
                        else -> it.error
                    },
                // A draft on a catch-all address names an identity the list
                // does not have; without its row the From line would read
                // "unknown" over a draft that is perfectly sendable.
                identities = it.identities.including(reopened.sender),
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
                .map {
                    Autosave(
                        it.draft,
                        it.signatureHtml,
                        it.quotedHtml,
                        it.encryption,
                        it.isUnopenable,
                    )
                }
                .distinctUntilChanged()
                .debounce(AUTOSAVE_DELAY_MS)
                .collect { (draft, signature, quote, encryption, unopenable) ->
                    if (unopenable || draft.isEmpty(quote)) return@collect

                    // An encrypted message is saved when the composer closes or
                    // sends, not as it is typed: each save is a new import that
                    // leaves the last one in Trash. And while the recipients'
                    // keys are still being asked about, nobody knows yet whether
                    // a save in the clear would be one it must not get.
                    if (encryption.encrypt || encryption.pending) return@collect

                    runCatching { compose.save(draft.outgoing(signature, quote)) }
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
                                            savedSealed = saved.savedSealed,
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

    /**
     * Asks the server about the recipients' keys as they are added (#9), so the chips can show a
     * lock and the composer knows whether the message will be encrypted before Send is tapped.
     *
     * With `lookup`, so the server searches WKD and the key servers for an address it holds no key
     * for. It does that once per address and caches the answer, so asking about the whole list
     * again when one address is added costs it nothing for the others.
     */
    private fun watchRecipientKeys() {
        keyWatch = viewModelScope.launch {
            _state
                .map { state ->
                    Triple(
                        state.draft.accountKey,
                        state.identity?.email,
                        (state.draft.to + state.draft.cc + state.draft.bcc)
                            .mapNotNull { it.email?.trim()?.lowercase() }
                            .distinct()
                            .sorted(),
                    )
                }
                .distinctUntilChanged()
                .debounce(KEY_LOOKUP_DELAY_MS)
                .collectLatest { (accountKey, from, addresses) ->
                    if (from == null) return@collectLatest

                    runCatching {
                        compose.recipientKeys(accountKey, from, addresses, lookUp = true)
                    }
                        .onSuccess { keys -> _state.update { it.copy(recipientKeys = keys) } }
                }
        }
    }

    private data class Autosave(
        val draft: ComposeDraft,
        val signature: String,
        val quote: String,
        val encryption: EncryptionPlan,
        val unopenable: Boolean,
    )

    private companion object {
        const val AUTOSAVE_DELAY_MS = 3_000L
        const val KEY_LOOKUP_DELAY_MS = 500L
    }
}

/**
 * The draft as the queue is handed it: marked to be encrypted, from the sending address, when
 * [plan] says so. Null when it is to be encrypted and there is no sending address to do it from.
 */
internal fun ComposeDraft.sealedFor(plan: EncryptionPlan, identity: SendIdentity?): ComposeDraft? =
    when {
        !plan.encrypt -> copy(sealAs = null)
        identity == null -> null
        else -> copy(sealAs = SealAs(identity.email, identity.name))
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
        // Escaped rather than inserted: shared text is somebody else's, and a
        // page title containing `<b>` must not start rendering as markup in the
        // editor. The signature is not part of it; the composer holds that
        // beside the editor and signs under the shared text on save.
        bodyHtml = text.asBodyHtml(),
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

/**
 * The message as it is saved and sent: what was typed, the sign-off under it, the quote under both.
 *
 * The one place the three are joined, so a save and a send cannot disagree about it.
 * [Signatures.replaceSignature] rather than a plain append, so a body that still carries a marked
 * block of its own is re-signed rather than signed twice.
 */
internal fun ComposeDraft.outgoing(signatureHtml: String, quotedHtml: String): ComposeDraft =
    copy(bodyHtml = Signatures.replaceSignature(bodyHtml, signatureHtml)).withQuote(quotedHtml)

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
