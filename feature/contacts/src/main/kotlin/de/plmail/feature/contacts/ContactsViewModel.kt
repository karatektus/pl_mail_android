package de.plmail.feature.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.plmail.core.data.ContactsRepository
import de.plmail.core.data.NewContactPhoto
import de.plmail.jmap.contacts.ContactDraft
import de.plmail.jmap.contacts.ContactObject
import de.plmail.jmap.contacts.DuplicatePair
import de.plmail.jmap.contacts.PhotoChange
import de.plmail.jmap.contacts.ReferenceEmail
import de.plmail.jmap.methods.ContactFilter
import de.plmail.jmap.protocol.ContactId
import de.plmail.jmap.protocol.JmapError
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ContactsUiState(
    val contacts: List<ContactObject> = emptyList(),
    val isLoading: Boolean = true,
    val loadError: String? = null,
    val query: String = "",
    /** True when the server has no contacts capability — the screen explains rather than spins. */
    val unavailable: Boolean = false,
    val duplicates: List<DuplicatePair> = emptyList(),
    val duplicatesLoading: Boolean = false,
    val duplicatesError: String? = null,
    val detail: ContactDetailState? = null,
)

data class ContactDetailState(
    val contact: ContactObject,
    val photo: ByteArray? = null,
    val referenceEmails: List<ReferenceEmail> = emptyList(),
    val referencesLoading: Boolean = true,
    val referencesError: String? = null,
)

sealed interface ContactsEvent {
    data class Message(val text: String) : ContactsEvent

    /**
     * A merge completed. [mergeRecordId] is the undo handle — the screen offers it in the snackbar
     * without a second round trip.
     */
    data class Merged(val mergeRecordId: String, val keptName: String) : ContactsEvent
}

@HiltViewModel
class ContactsViewModel @Inject constructor(private val contacts: ContactsRepository) :
    ViewModel() {

    private val _state = MutableStateFlow(ContactsUiState())
    val state: StateFlow<ContactsUiState> = _state.asStateFlow()

    private val _events = Channel<ContactsEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var searchJob: Job? = null

    init {
        refresh()
    }

    // ------------------------------------------------------------------ list

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, loadError = null, unavailable = false) }

            if (!contacts.isAvailable()) {
                _state.update { it.copy(isLoading = false, unavailable = true) }
                return@launch
            }

            runCatching { contacts.list(filter()) }
                .onSuccess { list -> _state.update { it.copy(contacts = list, isLoading = false) } }
                .onFailure { e ->
                    _state.update { it.copy(isLoading = false, loadError = readable(e)) }
                }
        }
    }

    /** Live search, debounced — an address book is small, so every keystroke may ask. */
    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            runCatching { contacts.list(filter()) }
                .onSuccess { list ->
                    _state.update { it.copy(contacts = list, isLoading = false, loadError = null) }
                }
                .onFailure { e -> _state.update { it.copy(loadError = readable(e)) } }
        }
    }

    private fun filter(): ContactFilter? =
        _state.value.query.trim().takeIf { it.isNotEmpty() }?.let { ContactFilter(text = it) }

    // ------------------------------------------------------------------ detail

    fun openDetail(id: ContactId) {
        viewModelScope.launch {
            val cached = _state.value.contacts.firstOrNull { it.id == id }
            val contact = cached ?: runCatching { contacts.get(id) }.getOrNull()
            if (contact == null) {
                _events.send(ContactsEvent.Message("That contact is no longer on the server."))
                refresh()
                return@launch
            }

            _state.update {
                it.copy(
                    detail =
                        ContactDetailState(
                            contact = contact,
                            referencesLoading = true,
                        )
                )
            }

            // Photo and reference emails load beside each other; either may fail alone.
            val photo = viewModelScope.launch {
                runCatching { contacts.photoBytes(contact) }
                    .onSuccess { bytes ->
                        _state.update { s ->
                            s.copy(
                                detail =
                                    s.detail
                                        ?.takeIf { d -> d.contact.id == id }
                                        ?.copy(photo = bytes)
                            )
                        }
                    }
            }
            val references = viewModelScope.launch {
                runCatching { contacts.referenceEmails(id) }
                    .onSuccess { emails ->
                        _state.update { s ->
                            s.copy(
                                detail =
                                    s.detail
                                        ?.takeIf { d -> d.contact.id == id }
                                        ?.copy(
                                            referenceEmails = emails,
                                            referencesLoading = false,
                                        )
                            )
                        }
                    }
                    .onFailure { e ->
                        _state.update { s ->
                            s.copy(
                                detail =
                                    s.detail
                                        ?.takeIf { d -> d.contact.id == id }
                                        ?.copy(
                                            referencesLoading = false,
                                            referencesError = readable(e),
                                        )
                            )
                        }
                    }
            }
            photo.join()
            references.join()
        }
    }

    fun closeDetail() {
        _state.update { it.copy(detail = null) }
    }

    // ------------------------------------------------------------------ write

    fun createContact(draft: ContactDraft, photo: NewContactPhoto?) {
        viewModelScope.launch {
            runCatching { contacts.create(draft, photo) }
                .onSuccess {
                    _events.send(ContactsEvent.Message(SAVED))
                    refresh()
                }
                .onFailure { e -> _events.send(ContactsEvent.Message(readable(e))) }
        }
    }

    fun updateContact(
        id: ContactId,
        draft: ContactDraft,
        photo: NewContactPhoto?,
        removePhoto: Boolean,
    ) {
        viewModelScope.launch {
            val withPhoto =
                when {
                    photo != null -> draft
                    removePhoto -> draft.copy(photo = PhotoChange.Removed)
                    else -> draft
                }
            runCatching { contacts.update(id, withPhoto, photo) }
                .onSuccess {
                    _events.send(ContactsEvent.Message(SAVED))
                    // Re-open the detail so it shows what the server now holds.
                    openDetail(id)
                    refresh()
                }
                .onFailure { e -> _events.send(ContactsEvent.Message(readable(e))) }
        }
    }

    fun deleteContact(id: ContactId) {
        viewModelScope.launch {
            runCatching { contacts.delete(id) }
                .onSuccess {
                    closeDetail()
                    _events.send(ContactsEvent.Message(DELETED))
                    refresh()
                }
                .onFailure { e -> _events.send(ContactsEvent.Message(readable(e))) }
        }
    }

    // ------------------------------------------------------------------ duplicates

    fun loadDuplicates() {
        viewModelScope.launch {
            _state.update { it.copy(duplicatesLoading = true, duplicatesError = null) }
            runCatching { contacts.duplicates() }
                .onSuccess { pairs ->
                    _state.update { it.copy(duplicates = pairs, duplicatesLoading = false) }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(duplicatesLoading = false, duplicatesError = readable(e))
                    }
                }
        }
    }

    /**
     * Folds [mergeIds] into [keepId]; the returned event carries the merge record id for the
     * snackbar's Undo.
     */
    fun merge(keepId: ContactId, mergeIds: List<ContactId>) {
        viewModelScope.launch {
            runCatching { contacts.merge(keepId, mergeIds) }
                .onSuccess { recordId ->
                    val keptName =
                        _state.value.contacts.firstOrNull { it.id == keepId }?.displayName.orEmpty()
                    _events.send(ContactsEvent.Merged(recordId, keptName))
                    loadDuplicates()
                    refresh()
                }
                .onFailure { e -> _events.send(ContactsEvent.Message(readable(e))) }
        }
    }

    fun undoMerge(mergeRecordId: String) {
        viewModelScope.launch {
            runCatching { contacts.mergeUndo(mergeRecordId) }
                .onSuccess {
                    _events.send(ContactsEvent.Message(UNDONE))
                    loadDuplicates()
                    refresh()
                }
                .onFailure { e -> _events.send(ContactsEvent.Message(readable(e))) }
        }
    }

    // ------------------------------------------------------------------ photos for rows

    /** One contact's photo bytes, for list rows — the repository caches them in memory. */
    suspend fun photoFor(contact: ContactObject): ByteArray? = runCatching {
        contacts.photoBytes(contact)
    }
        .getOrNull()

    // ------------------------------------------------------------------ errors

    /**
     * What the user reads. `JmapError` already carries a sentence; anything else is a transport
     * failure, which gets the honest one-liner rather than a stack trace.
     */
    private fun readable(e: Throwable): String =
        when (e) {
            is JmapError -> e.message ?: "The server refused."
            is IOException -> "Could not reach the server."
            else -> e.message ?: "Something went wrong."
        }

    companion object {
        const val SEARCH_DEBOUNCE_MS = 400L

        // Event text lives here rather than in the screens so both flavors share the one copy;
        // the screens that need them as resources use the string table instead.
        const val SAVED = "Contact saved"
        const val DELETED = "Contact deleted"
        const val UNDONE = "Merge undone"
    }
}
