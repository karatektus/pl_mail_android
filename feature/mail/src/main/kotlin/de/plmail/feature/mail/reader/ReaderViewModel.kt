package de.plmail.feature.mail.reader

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.plmail.core.data.BlobStore
import de.plmail.core.data.MailRepository
import de.plmail.core.data.MessageLoader
import de.plmail.core.database.AttachmentEntity
import de.plmail.core.database.EmailEntity
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One message as the reader shows it. */
data class ReaderMessage(
    val email: EmailEntity,
    val html: String?,
    val text: String?,
    val isExpanded: Boolean,
    /** Files this message carries. Inline parts are excluded — they are in the body. */
    val attachments: List<AttachmentEntity> = emptyList(),
    /** Per message: a plain reply and the marketing mail it quotes need different treatment. */
    val remoteImages: RemoteImages = RemoteImages.BLOCKED,
    /** Set when the user has asked to see a transformed message as it was sent. */
    val showOriginal: Boolean = false,
) {
    val body: String?
        get() = html ?: text?.let { "<pre>$it</pre>" }

    /**
     * Whether a reply-all would reach anyone a plain reply would not.
     *
     * Counted from the stored recipient blobs rather than from a parsed address list, because the
     * only question is "is there more than one line's worth of people here" and parsing three
     * hundred bytes of JSON per row to answer it would be waste. The user's own address is in
     * there, which is why one recipient is not enough to justify the button.
     */
    val hasOtherRecipients: Boolean
        get() =
            (email.toJson?.count { it == '@' } ?: 0) + (email.ccJson?.count { it == '@' } ?: 0) > 1
}

data class ReaderUiState(
    val subject: String? = null,
    val messages: List<ReaderMessage> = emptyList(),
    val isLoading: Boolean = true,
    /**
     * When this conversation is sleeping until, or null.
     *
     * Read so the reader offers the opposite verb: "snooze" on something already put away replaces
     * a time the user cannot see with another one, which is a control whose effect is invisible.
     */
    val snoozedUntil: Long? = null,
    /**
     * Attachments currently being fetched, by row uid.
     *
     * Per attachment rather than one screen-wide flag: a message can carry several, they download
     * independently, and a single spinner over the whole list cannot say which one the user is
     * waiting for.
     */
    val busyAttachments: Set<String> = emptySet(),
    /** The message source view, when it is open. */
    val source: MessageSource? = null,
    /**
     * The last thing that went wrong, for a snackbar, and null once shown.
     *
     * A download that fails silently is the worst outcome here: the row stops spinning and nothing
     * opens, which reads as a tap that did not register.
     */
    val failure: ReaderFailure? = null,
)

/** One message's RFC822 source, as the sheet shows it. */
data class MessageSource(
    val emailUid: String,
    val title: String,
    /** Null while it is still being fetched. */
    val text: String? = null,
)

/**
 * Something that failed, with an id so the same failure is not announced twice.
 *
 * [detail] is the exception's own message rather than a translated sentence, deliberately: this
 * audience runs the server, and "connection refused" or "404" is the diagnosis. It is shown *after*
 * a translated sentence naming what was being attempted, never on its own.
 */
data class ReaderFailure(val id: Long, val what: FailedAt, val detail: String?)

enum class FailedAt {
    DOWNLOAD,
    SAVE,
    SOURCE,
}

/**
 * One conversation, opened.
 *
 * The newest message is expanded and the rest are collapsed, because a thread of thirty is a wall
 * of quoted text otherwise and the newest is nearly always the reason it was opened.
 */
@HiltViewModel
class ReaderViewModel
@Inject
constructor(
    private val mail: MailRepository,
    private val loader: MessageLoader,
    private val blobs: BlobStore,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    /**
     * Files ready to hand to another app, as events rather than state.
     *
     * A download that finishes has to *open* something, which is a one-off, and holding it in the
     * UI state would reopen the file on every rotation until something cleared it.
     */
    private val _open = MutableSharedFlow<OpenableFile>(extraBufferCapacity = 4)
    val open: SharedFlow<OpenableFile> = _open.asSharedFlow()

    fun open(accountKey: String, threadId: String, subject: String?) {
        _state.update { ReaderUiState(subject = subject, isLoading = true) }

        viewModelScope.launch {
            // What is cached, drawn first: a conversation opened twice must not
            // wait on the network to show what is already on disk.
            show(accountKey, threadId, subject)

            // A failure here is not fatal -- the cached half is still readable
            // and the reader says which messages have no body. It is logged
            // rather than swallowed, though: a thread that silently never
            // downloads is indistinguishable from one the server has nothing
            // for, and that is exactly the diagnosis a self-hosting user has to
            // be able to make.
            runCatching { loader.loadBodies(accountKey, threadId) }
                .onFailure { Log.w(TAG, "Could not download bodies for thread $threadId", it) }

            show(accountKey, threadId, subject)
        }
    }

    /**
     * Rebuilds the visible thread from the cache, keeping what the user has done to it.
     *
     * The carry-forward is the point. This runs a second time once bodies arrive, and rebuilding
     * from scratch would collapse the message they had just expanded and re-block the pictures they
     * had just allowed — at an unpredictable moment, because it depends on the network.
     */
    private suspend fun show(accountKey: String, threadId: String, subject: String?) {
        val emails = mail.messagesInThread(accountKey, threadId)
        val thread = mail.thread(accountKey, threadId)
        val newest = emails.maxByOrNull { it.receivedAt ?: Long.MIN_VALUE }
        val existing = _state.value.messages.associateBy { it.email.uid }

        val messages =
            emails
                .sortedBy { email -> email.receivedAt ?: Long.MIN_VALUE }
                .map { email ->
                    val body = mail.body(email.uid)
                    val previous = existing[email.uid]

                    ReaderMessage(
                        email = email,
                        html = body?.htmlBody,
                        text = body?.textBody,
                        isExpanded = previous?.isExpanded ?: (email.uid == newest?.uid),
                        attachments = mail.attachments(email.uid),
                        remoteImages = previous?.remoteImages ?: RemoteImages.BLOCKED,
                        showOriginal = previous?.showOriginal ?: false,
                    )
                }

        _state.update { current ->
            ReaderUiState(
                // The caller's subject where there is one, the conversation's
                // own otherwise. A list row already knows it and passes it, so
                // the title is right on the first frame before anything is read
                // from disk — but a notification tap has only two ids, and
                // without this fallback it opened a perfectly good conversation
                // under the heading "(no subject)". The opening message's, not
                // the newest, matching how the list titles a thread: a reply
                // prefixed "Re:" must not rename the conversation.
                subject =
                    subject?.takeIf { it.isNotBlank() } ?: messages.firstOrNull()?.email?.subject,
                messages = messages,
                isLoading = false,
                snoozedUntil = thread?.snoozedUntil,
                // Carried across the rebuild for the same reason expansion is.
                // This runs again when bodies arrive, at a moment nobody can
                // predict, and dropping them would stop an attachment's spinner
                // and close the source sheet the user is reading.
                busyAttachments = current.busyAttachments,
                source = current.source,
                failure = current.failure,
            )
        }
    }

    /**
     * Downloads an attachment and asks the screen to open it.
     *
     * The download is on the ViewModel's scope rather than the composable's, so backing out of the
     * reader by accident does not throw away a fifteen-megabyte transfer that was nearly done — and
     * so a rotation mid-download is not a restart.
     */
    fun openAttachment(attachment: AttachmentEntity) {
        fetch(attachment, FailedAt.DOWNLOAD) { file ->
            _open.tryEmit(OpenableFile(file = file, type = attachment.type))
        }
    }

    /**
     * Downloads if needed, then copies into wherever the user pointed the system file picker.
     *
     * A copy rather than a move: the cached file stays, so opening the attachment again after
     * saving it does not go back to the server. The picker's own Uri is written through the content
     * resolver because the destination is very often a provider — Drive, a USB stick, a NAS mount —
     * rather than a path this process could open.
     */
    fun saveAttachment(attachment: AttachmentEntity, destination: Uri) {
        fetch(attachment, FailedAt.SAVE) { file ->
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(destination)?.use { sink ->
                    file.inputStream().use { it.copyTo(sink) }
                } ?: error("The place you chose could not be written to.")
            }
        }
    }

    private fun fetch(
        attachment: AttachmentEntity,
        what: FailedAt,
        then: suspend (java.io.File) -> Unit,
    ) {
        // Ignored rather than queued: a second tap on a row that is already
        // spinning means "did that work?", and answering it with a second
        // download and a second file chooser is worse than answering nothing.
        if (attachment.uid in _state.value.busyAttachments) return

        _state.update { it.copy(busyAttachments = it.busyAttachments + attachment.uid) }

        viewModelScope.launch {
            runCatching {
                then(
                    blobs.file(
                        accountKey = attachment.accountKey,
                        blobId = attachment.blobId,
                        name = attachment.name ?: DEFAULT_NAME,
                        type = attachment.type,
                    )
                )
            }
                .onFailure { failure ->
                    Log.w(TAG, "Could not fetch attachment ${attachment.uid}", failure)
                    announce(what, failure)
                }

            _state.update { it.copy(busyAttachments = it.busyAttachments - attachment.uid) }
        }
    }

    /**
     * Opens the message's own source, downloaded from its `m-` blob.
     *
     * Deliberately per message rather than per conversation, like reply: a thread has several
     * messages and "the source" of a conversation is not a thing. The sheet opens immediately with
     * no text and fills in, because on a slow link the alternative is a menu item that appears to
     * do nothing for four seconds.
     */
    fun showSource(message: ReaderMessage) {
        val blobId = message.email.blobId ?: return
        val title = message.email.subject?.takeIf { it.isNotBlank() } ?: SOURCE_FALLBACK_NAME

        _state.update { it.copy(source = MessageSource(message.email.uid, title)) }

        viewModelScope.launch {
            runCatching {
                blobs.text(
                    accountKey = message.email.accountKey,
                    blobId = blobId,
                    name = SOURCE_FILE_NAME,
                    type = RFC822,
                )
            }
                .onSuccess { text ->
                    _state.update { current ->
                        // Only if the sheet is still showing *this* message.
                        // Closing it and opening another one while a slow
                        // download finishes would otherwise drop the wrong
                        // source into the sheet on screen.
                        if (current.source?.emailUid != message.email.uid) current
                        else current.copy(source = current.source.copy(text = text))
                    }
                }
                .onFailure { failure ->
                    Log.w(TAG, "Could not download the source of ${message.email.uid}", failure)
                    _state.update { it.copy(source = null) }
                    announce(FailedAt.SOURCE, failure)
                }
        }
    }

    fun closeSource() {
        _state.update { it.copy(source = null) }
    }

    fun failureShown(id: Long) {
        _state.update { if (it.failure?.id == id) it.copy(failure = null) else it }
    }

    private fun announce(what: FailedAt, failure: Throwable) {
        _state.update {
            it.copy(
                failure =
                    ReaderFailure(
                        // Monotonic, so two identical failures are two
                        // announcements rather than one the second of which is
                        // silently deduplicated by the snackbar host.
                        id = nextFailureId++,
                        what = what,
                        detail = failure.message,
                    )
            )
        }
    }

    private var nextFailureId = 1L

    fun toggleExpanded(uid: String) {
        _state.update { current ->
            current.copy(
                messages =
                    current.messages.map {
                        if (it.email.uid == uid) it.copy(isExpanded = !it.isExpanded) else it
                    }
            )
        }
    }

    /** Per message and per session; nothing about this is remembered for the sender yet. */
    fun allowRemoteImages(uid: String) {
        _state.update { current ->
            current.copy(
                messages =
                    current.messages.map {
                        if (it.email.uid == uid) it.copy(remoteImages = RemoteImages.ALLOWED)
                        else it
                    }
            )
        }
    }

    fun toggleOriginal(uid: String) {
        _state.update { current ->
            current.copy(
                messages =
                    current.messages.map {
                        if (it.email.uid == uid) it.copy(showOriginal = !it.showOriginal) else it
                    }
            )
        }
    }

    /**
     * Marks a message read once it has actually been shown.
     *
     * **After display, never on prefetch.** Paging fetches ahead of the viewport and the reader
     * loads every message in a thread, so marking on load would clear the unread badge on mail the
     * user has never seen — the one bug in a mail client that cannot be undone by the user, because
     * they no longer know what they missed.
     */
    fun markRead(accountKey: String, uid: String) {
        viewModelScope.launch { mail.markSeen(accountKey, uid) }
    }

    private companion object {
        const val TAG = "plMail.Reader"

        /** Used when a part arrived with no filename, which happens more often than it should. */
        const val DEFAULT_NAME = "attachment"

        /**
         * The `name` and `type` the download URL is asked for a message source with.
         *
         * Both are template variables the server echoes into `Content-Disposition` and
         * `Content-Type`, so asking for the wrong type gets the right bytes labelled as something
         * else — and the file, once saved, opens in whatever handles that instead.
         */
        const val SOURCE_FILE_NAME = "message.eml"
        const val RFC822 = "message/rfc822"
        const val SOURCE_FALLBACK_NAME = "message"
    }
}

/** A downloaded file the screen is being asked to hand to another app. */
data class OpenableFile(val file: java.io.File, val type: String)
