package de.plmail.core.data

import de.plmail.jmap.client.JmapClient
import de.plmail.jmap.methods.NoteDraft
import de.plmail.jmap.methods.NoteGet
import de.plmail.jmap.methods.NoteMedia
import de.plmail.jmap.methods.NoteMediaClient
import de.plmail.jmap.methods.NoteObject
import de.plmail.jmap.methods.NoteSet
import de.plmail.jmap.protocol.Capability
import de.plmail.jmap.protocol.RequestBuilder
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * The user's Keep-style notes, held in memory and kept in step with the server's Note methods.
 *
 * One list for the whole app, and a singleton on purpose: the notes screen, the "Notes" section on
 * an event or a contact, and the "save as note" action are all views of the same notes, and one
 * writer is what stops two of them racing each other's `ifInState`.
 *
 * Writes are optimistic. The list changes first, so a checkbox ticks and a pin moves at once, and
 * the request follows; a refusal or a network failure re-reads the server's list, which puts back
 * whatever did not land rather than leaving the screen describing a note that is not there. There
 * is no offline queue — a write made with no connection fails and is undone — because a queue of
 * stale whole-property writes is how two devices end up overwriting each other's checklists.
 *
 * `Note/get` has no `/changes`, so [refresh] always fetches the whole list. It is a few hundred
 * short objects, and it runs when the notes screen opens and after a failed write.
 */
@Singleton
class NotesRepository
@Inject
constructor(
    private val clients: AccountClients,
    @param:dagger.hilt.android.qualifiers.ApplicationContext
    private val context: android.content.Context,
) {

    private val _notes = MutableStateFlow<List<NoteObject>>(emptyList())

    /** Every note, in the order the server sent them: pinned first, newest change first. */
    val notes: StateFlow<List<NoteObject>> = _notes.asStateFlow()

    private val _loaded = MutableStateFlow(false)

    /** False until the first successful read, so a screen can tell "empty" from "not loaded". */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val writes = Mutex()
    private var state: String? = null

    /** Whether the connected server serves notes. False when not connected or unreachable. */
    suspend fun isAvailable(): Boolean = runCatching {
        clients.current()?.session()?.supportsNotes == true
    }
        .getOrDefault(false)

    /** Re-reads every note. Throws on failure; callers decide whether that is worth showing. */
    suspend fun refresh() {
        val client = client()
        val request = RequestBuilder(using = Capability.USING_NOTES)
        val get = request.add(NoteGet())
        val result = client.send(request).result(get)

        state = result.state
        _notes.value = result.list
        _loaded.value = true
    }

    /**
     * The notes that link to one thing: the "Notes" section of an event, a contact or a file. Notes
     * in the bin are left out; archived ones are kept, because a note put away is still a note
     * about that meeting.
     */
    fun linkedTo(type: String, id: String): Flow<List<NoteObject>> = _notes.map { all ->
        all.filter { note ->
            !note.trashed && note.links.any { it.type == type && it.id == id }
        }
    }

    /** The note with this id as the list holds it now, or null. */
    fun find(id: String): NoteObject? = _notes.value.firstOrNull { it.id == id }

    /** Creates a note and returns it with the server's id. */
    suspend fun create(draft: NoteDraft): NoteObject = writes.withLock {
        val client = client()
        val request = RequestBuilder(using = Capability.USING_NOTES)
        val set = request.add(NoteSet(create = mapOf(CREATION_ID to draft), ifInState = null))
        val result = client.send(request).result(set)

        result.notCreated[CREATION_ID]?.let { throw NoteRefused(it.description ?: it.type) }
        val created =
            result.created[CREATION_ID] ?: throw NoteRefused("The server did not create the note.")

        val note =
            NoteObject(
                id =
                    created["id"]?.jsonPrimitive?.content
                        ?: throw NoteRefused("The server sent no id."),
                title = draft.title,
                body = draft.body,
                items = draft.items,
                color = draft.color,
                pinned = draft.pinned,
                archived = draft.archived,
                links = draft.links,
                labels = draft.labels,
                createdAt = created.string("createdAt"),
                updatedAt = created.string("updatedAt"),
            )
        state = result.newState
        _notes.update { sorted(listOf(note) + it) }
        note
    }

    /**
     * Saves the whole of [note]: every settable property, so a save is never a merge. Optimistic;
     * on failure the server's list is re-read and the error rethrown.
     */
    suspend fun save(note: NoteObject) = writes.withLock {
        // Labels go on the wire only when this note has or had some: a server from before
        // labels refuses the property, and every other save must still work against it.
        val sendLabels = note.labels.isNotEmpty() || find(note.id)?.labels?.isNotEmpty() == true
        // The reminder likewise: only when it is set or was, for the same older servers.
        val previousReminder = find(note.id)?.reminderAt
        val sendReminder = note.reminderAt != null || previousReminder != null
        val stamped =
            note.copy(
                updatedAt = Instant.now().toString(),
                reminderSent = note.reminderSent && note.reminderAt == previousReminder,
            )
        _notes.update { list -> sorted(list.map { if (it.id == note.id) stamped else it }) }

        send(
            NoteSet(
                update =
                    mapOf(
                        note.id to
                            NoteSet.patch {
                                title(note.title)
                                body(note.body)
                                items(note.items)
                                color(note.color)
                                pinned(note.pinned)
                                archived(note.archived)
                                trashed(note.trashed)
                                links(note.links)
                                if (sendLabels) labels(note.labels)
                                if (sendReminder) reminderAt(note.reminderAt)
                            }
                    )
            )
        )
    }

    /** Whether the server keeps pictures, drawings and voice notes on notes. */
    suspend fun supportsMedia(): Boolean = runCatching {
        clients.current()?.let { NoteMediaClient(it).isAvailable() } ?: false
    }
        .getOrDefault(false)

    /**
     * Adds a picture, drawing or recording to note [noteId] and puts it on the note in the local
     * list straight away. Throws what the server refused, with its reason.
     */
    suspend fun addMedia(
        noteId: String,
        kind: String,
        name: String,
        type: String,
        bytes: ByteArray,
    ): NoteMedia {
        val media = NoteMediaClient(client()).upload(noteId, kind, name, type, bytes)
        _notes.update { list ->
            list.map { if (it.id == noteId) it.copy(media = it.media + media) else it }
        }
        return media
    }

    /** Takes one off the note, here and on the server. */
    suspend fun removeMedia(noteId: String, mediaId: String) {
        _notes.update { list ->
            list.map {
                if (it.id == noteId) it.copy(media = it.media.filterNot { m -> m.id == mediaId })
                else it
            }
        }
        NoteMediaClient(client()).delete(noteId, mediaId)
        mediaCacheFile(mediaId).delete()
    }

    /**
     * The file behind one of a note's pictures or recordings, downloaded once into the cache
     * (`cache/notes/media/`, which the app's FileProvider shares for "open").
     */
    suspend fun mediaFile(noteId: String, media: NoteMedia): java.io.File {
        val target = mediaCacheFile(media.id)
        if (target.isFile && target.length() > 0) return target
        val client = client()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val partial = java.io.File(target.parentFile, target.name + ".part")
            partial.outputStream().use { out ->
                NoteMediaClient(client).download(noteId, media.id, out)
            }
            partial.renameTo(target)
        }
        return target
    }

    private fun mediaCacheFile(mediaId: String): java.io.File =
        java.io.File(java.io.File(context.cacheDir, "notes/media").apply { mkdirs() }, mediaId)

    /** Removes a note for good — the bin's "delete forever". */
    suspend fun destroy(id: String) = writes.withLock {
        _notes.update { list -> list.filterNot { it.id == id } }
        send(NoteSet(destroy = listOf(id)))
    }

    private suspend fun send(set: NoteSet) {
        try {
            val client = client()
            val request = RequestBuilder(using = Capability.USING_NOTES)
            val handle = request.add(set)
            val result = client.send(request).result(handle)

            result.firstError?.let { throw NoteRefused(it.description ?: it.type) }
            state = result.newState
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            // Put back what did not land. The re-read is best effort: if the network is the
            // problem it fails too, and the optimistic list stays until the next refresh.
            runCatching { refresh() }
            throw failure
        }
    }

    private suspend fun client(): JmapClient =
        clients.current() ?: throw NoteRefused("Not connected to a server.")

    private fun sorted(list: List<NoteObject>): List<NoteObject> =
        list.sortedWith(
            compareByDescending<NoteObject> { it.pinned }.thenByDescending { it.updatedAt ?: "" }
        )

    private fun kotlinx.serialization.json.JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.content

    private companion object {
        const val CREATION_ID = "n1"
    }
}

/** The server said no, or there was no server to ask. The message is what a screen may show. */
class NoteRefused(message: String) : Exception(message)
