package de.plmail.feature.compose

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.plmail.core.data.ComposeDraft
import de.plmail.core.data.ComposeRepository
import de.plmail.core.data.ContactSuggestions
import de.plmail.core.data.SendIdentity
import de.plmail.core.data.SendQueue
import de.plmail.core.data.StagedAttachment
import de.plmail.jmap.mail.DraftComposer
import de.plmail.jmap.mail.EmailAddress
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
            }

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
    fun setIdentity(identity: SendIdentity) = edit { draft ->
        if (identity.accountKey == draft.accountKey) {
            draft.copy(identityId = identity.identityId)
        } else {
            draft.copy(
                accountKey = identity.accountKey,
                identityId = identity.identityId,
                emailId = null,
                // The blobs belong to the old account too; BlobResolver filters
                // by account and would refuse them.
                attachments = draft.attachments.filter { it.uri != null },
                savedAttachments = emptyList(),
            )
        }
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
    fun send(): Boolean {
        val draft = _state.value.draft

        if (!draft.hasRecipients) {
            _state.update { it.copy(error = ComposeError.NoRecipients) }
            return false
        }

        // Cancelled rather than left to fire: an autosave landing after the
        // queue has taken over would write the pre-send body over the one being
        // sent, and on a draft the queue may already have replaced.
        autosave?.cancel()
        sendQueue.enqueue(draft.withQuote(_state.value.quotedHtml))

        return true
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

    private suspend fun startEdit(identities: List<SendIdentity>, request: ComposeRequest.Edit) {
        val draft =
            compose.loadDraft(request.accountKey, request.emailId)
                ?: run {
                    _state.update {
                        it.copy(error = ComposeError.OriginalUnavailable, isLoading = false)
                    }
                    return
                }

        _state.update { it.copy(draft = draft, isLoading = false, isSaved = true) }
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
