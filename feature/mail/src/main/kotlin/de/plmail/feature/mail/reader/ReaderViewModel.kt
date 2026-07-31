package de.plmail.feature.mail.reader

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.plmail.core.data.MailRepository
import de.plmail.core.data.MessageLoader
import de.plmail.core.database.EmailEntity
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One message as the reader shows it. */
data class ReaderMessage(
    val email: EmailEntity,
    val html: String?,
    val text: String?,
    val isExpanded: Boolean,
    /** Per message: a plain reply and the marketing mail it quotes need different treatment. */
    val remoteImages: RemoteImages = RemoteImages.BLOCKED,
    /** Set when the user has asked to see a transformed message as it was sent. */
    val showOriginal: Boolean = false,
) {
    val body: String?
        get() = html ?: text?.let { "<pre>$it</pre>" }
}

data class ReaderUiState(
    val subject: String? = null,
    val messages: List<ReaderMessage> = emptyList(),
    val isLoading: Boolean = true,
)

/**
 * One conversation, opened.
 *
 * The newest message is expanded and the rest are collapsed, because a thread of thirty is a wall
 * of quoted text otherwise and the newest is nearly always the reason it was opened.
 */
@HiltViewModel
class ReaderViewModel
@Inject
constructor(private val mail: MailRepository, private val loader: MessageLoader) : ViewModel() {

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

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
                        remoteImages = previous?.remoteImages ?: RemoteImages.BLOCKED,
                        showOriginal = previous?.showOriginal ?: false,
                    )
                }

        _state.update { ReaderUiState(subject = subject, messages = messages, isLoading = false) }
    }

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
    }
}
