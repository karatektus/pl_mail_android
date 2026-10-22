package de.plmail.feature.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.plmail.core.data.NotesRepository
import de.plmail.jmap.methods.NoteDraft
import de.plmail.jmap.methods.NoteObject
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class NotesFilter {
    NOTES,
    ARCHIVE,
    BIN,
}

data class NotesUiState(
    val notes: List<NoteObject> = emptyList(),
    val loaded: Boolean = false,
    val filter: NotesFilter = NotesFilter.NOTES,
    val query: String = "",
    /** The label the wall is narrowed to, or null for every note. */
    val label: String? = null,
    /** Every label in use, for the filter chips. */
    val labels: List<String> = emptyList(),
    val refreshing: Boolean = false,
    val error: Boolean = false,
    val failedWrite: Boolean = false,
)

/**
 * Drives the notes wall and the editor. All state of substance is in [NotesRepository], which is
 * shared with the "Notes" sections on events and contacts; this holds only what one screen decides
 * — the filter, the search text, and whether the last read or write failed.
 */
@HiltViewModel
class NotesViewModel @Inject constructor(private val repository: NotesRepository) : ViewModel() {

    private val filter = MutableStateFlow(NotesFilter.NOTES)
    private val query = MutableStateFlow("")
    private val label = MutableStateFlow<String?>(null)
    private val refreshing = MutableStateFlow(false)
    private val error = MutableStateFlow(false)
    private val failedWrite = MutableStateFlow(false)

    /** Whether the server serves notes at all; sections elsewhere hide themselves when not. */
    val available: StateFlow<Boolean> =
        kotlinx.coroutines.flow
            .flow { emit(repository.isAvailable()) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val state: StateFlow<NotesUiState> =
        combine(
                repository.notes,
                repository.loaded,
                combine(filter, query, label) { f, q, l -> Triple(f, q, l) },
                combine(refreshing, error, failedWrite) { r, e, f -> Triple(r, e, f) },
            ) { notes, loaded, scope, flags ->
                val (filter, query, label) = scope
                NotesUiState(
                    notes = visible(notes, filter, query, label),
                    loaded = loaded,
                    filter = filter,
                    query = query,
                    label = label,
                    labels =
                        notes
                            .filter { !it.trashed }
                            .flatMap { it.labels }
                            .distinctBy { it.lowercase() }
                            .sortedBy { it.lowercase() },
                    refreshing = flags.first,
                    error = flags.second,
                    failedWrite = flags.third,
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotesUiState())

    init {
        refresh()
    }

    fun linkedTo(type: String, id: String) = repository.linkedTo(type, id)

    fun find(id: String): NoteObject? = repository.find(id)

    fun setFilter(value: NotesFilter) = filter.update { value }

    fun setQuery(value: String) = query.update { value }

    /** Narrows the wall to one label; the same label again shows everything. */
    fun toggleLabel(value: String) = label.update {
        if (it.equals(value, ignoreCase = true)) null else value
    }

    fun dismissFailure() = failedWrite.update { false }

    fun refresh() {
        viewModelScope.launch {
            refreshing.value = true
            error.value = !runCatching { repository.refresh() }.isSuccess
            refreshing.value = false
        }
    }

    /** Creates a note and hands back the stored copy, or null when the server refused. */
    fun create(draft: NoteDraft, onCreated: (NoteObject?) -> Unit = {}) {
        viewModelScope.launch {
            val note = guarded { repository.create(draft) }
            onCreated(note)
        }
    }

    /** Adds a picture, drawing or recording; [onDone] gets it, or the reason it was refused. */
    fun addMedia(
        noteId: String,
        kind: String,
        name: String,
        type: String,
        bytes: suspend () -> ByteArray,
        onDone: (de.plmail.jmap.methods.NoteMedia?, String?) -> Unit,
    ) {
        viewModelScope.launch {
            runCatching { repository.addMedia(noteId, kind, name, type, bytes()) }
                .onSuccess { onDone(it, null) }
                .onFailure { onDone(null, it.message ?: it.javaClass.simpleName) }
        }
    }

    fun removeMedia(noteId: String, mediaId: String) {
        viewModelScope.launch { runCatching { repository.removeMedia(noteId, mediaId) } }
    }

    /** The cached file behind one picture or recording, downloading it the first time. */
    suspend fun mediaFile(noteId: String, media: de.plmail.jmap.methods.NoteMedia): java.io.File? =
        runCatching {
            repository.mediaFile(noteId, media)
        }
        .getOrNull()

    fun save(note: NoteObject) {
        viewModelScope.launch { guarded { repository.save(note) } }
    }

    fun toggleItem(note: NoteObject, index: Int) {
        val items = note.items.toMutableList()
        val item = items.getOrNull(index) ?: return
        items[index] = item.copy(done = !item.done)
        save(note.copy(items = items))
    }

    fun togglePin(note: NoteObject) = save(note.copy(pinned = !note.pinned, archived = false))

    fun toggleArchive(note: NoteObject) = save(note.copy(archived = !note.archived, pinned = false))

    fun trash(note: NoteObject) = save(note.copy(trashed = true, pinned = false))

    fun restore(note: NoteObject) = save(note.copy(trashed = false))

    fun destroy(note: NoteObject) {
        viewModelScope.launch { guarded { repository.destroy(note.id) } }
    }

    private suspend fun <T> guarded(block: suspend () -> T): T? =
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            failedWrite.value = true
            null
        }

    private fun visible(
        all: List<NoteObject>,
        filter: NotesFilter,
        query: String,
        label: String?,
    ): List<NoteObject> {
        val scoped =
            when (filter) {
                NotesFilter.NOTES -> all.filter { !it.trashed && !it.archived }
                NotesFilter.ARCHIVE -> all.filter { !it.trashed && it.archived }
                NotesFilter.BIN -> all.filter { it.trashed }
            }.filter { note ->
                label == null || note.labels.any { it.equals(label, ignoreCase = true) }
            }
        val needle = query.trim()
        if (needle.isEmpty()) return scoped
        return scoped.filter { note ->
            note.title.contains(needle, ignoreCase = true) ||
                note.body.contains(needle, ignoreCase = true) ||
                note.items.any { it.text.contains(needle, ignoreCase = true) } ||
                note.labels.any { it.contains(needle, ignoreCase = true) } ||
                note.links.any { it.title?.contains(needle, ignoreCase = true) == true }
        }
    }
}
