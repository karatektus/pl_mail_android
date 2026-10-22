package de.plmail.jmap.methods

import de.plmail.jmap.protocol.JmapMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One checklist line of a [NoteObject]. */
@Serializable data class NoteItem(val text: String = "", val done: Boolean = false)

/**
 * A reference from a note to something else the user keeps: an `event`, a `file`, a `contact` or a
 * `mail`.
 *
 * [id] is the id the target has on its own surface; the server does not check it exists. [title] is
 * the label it had when it was linked, kept so a list can be drawn without fetching the target.
 */
@Serializable
data class NoteLink(val type: String, val id: String, val title: String? = null) {
    companion object {
        const val EVENT = "event"
        const val FILE = "file"
        const val CONTACT = "contact"
        const val MAIL = "mail"
    }
}

/** One picture, drawing or voice recording on a note. */
@Serializable
data class NoteMedia(
    val id: String,
    val kind: String,
    val name: String = "",
    val type: String = "application/octet-stream",
    val size: Long = 0,
    val createdAt: String? = null,
) {
    val isAudio: Boolean
        get() = kind == AUDIO

    companion object {
        const val IMAGE = "image"
        const val DRAWING = "drawing"
        const val AUDIO = "audio"
    }
}

/** A note as it comes off the wire. See the server's `Note` in `docs/CLIENT_DEVELOPMENT.md`. */
@Serializable
data class NoteObject(
    val id: String,
    val title: String = "",
    val body: String = "",
    val items: List<NoteItem> = emptyList(),
    /** A colour *name* from the capability's `colors`; the client picks the actual paint. */
    val color: String? = null,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val trashed: Boolean = false,
    val links: List<NoteLink> = emptyList(),
    /** Keep-style labels, case kept as typed, each once. */
    val labels: List<String> = emptyList(),
    /** When to remind, a UTCDate, or null. The server mails and pushes it once it is due. */
    val reminderAt: String? = null,
    /** The current reminder has gone out (read-only). */
    val reminderSent: Boolean = false,
    /** Pictures, drawings and voice notes (read-only; changed through [NoteMediaClient]). */
    val media: List<NoteMedia> = emptyList(),
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

/**
 * `Note/get` — the user's notes, pinned first.
 *
 * Per user, so **no `accountId`**: the server refuses one. `ids: null` is "all of them", sent
 * explicitly. There is no `/query` and no `/changes`; `state` is a hash of the list, so a client
 * that holds a different one fetches again.
 */
class NoteGet : JmapMethod<NoteGetResult> {
    override val name = "Note/get"

    override fun arguments(): JsonObject = buildJsonObject { put("ids", JsonNull) }

    override fun decode(json: Json, arguments: JsonObject): NoteGetResult =
        json.decodeFromJsonElement(NoteGetResult.serializer(), arguments)
}

@Serializable
data class NoteGetResult(
    val state: String = "",
    val list: List<NoteObject> = emptyList(),
    val notFound: List<String> = emptyList(),
)

/** The writable properties of a note, for a create. Absent keys are the server's defaults. */
data class NoteDraft(
    val title: String = "",
    val body: String = "",
    val items: List<NoteItem> = emptyList(),
    val color: String? = null,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val links: List<NoteLink> = emptyList(),
    val labels: List<String> = emptyList(),
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("title", title)
        put("body", body)
        put("items", itemsJson(items))
        put("color", color?.let { JsonPrimitive(it) } ?: JsonNull)
        put("pinned", pinned)
        put("archived", archived)
        put("links", linksJson(links))
        // Only when there are some: a server from before labels refuses the property outright.
        if (labels.isNotEmpty()) {
            put("labels", buildJsonArray { labels.forEach { add(JsonPrimitive(it)) } })
        }
    }
}

/**
 * `Note/set`: one batch of creates, whole-property updates and destroys.
 *
 * Updates replace properties outright — `items` and `links` are sent complete — so two devices
 * never merge each other's half-lists. [ifInState] makes a stale write a request-level
 * `stateMismatch`, which a caller answers by re-reading.
 */
class NoteSet(
    private val create: Map<String, NoteDraft> = emptyMap(),
    private val update: Map<String, JsonObject> = emptyMap(),
    private val destroy: List<String> = emptyList(),
    private val ifInState: String? = null,
) : JmapMethod<NoteSetResult> {
    override val name = "Note/set"

    override fun arguments(): JsonObject = buildJsonObject {
        ifInState?.let { put("ifInState", it) }
        if (create.isNotEmpty()) {
            put(
                "create",
                buildJsonObject { create.forEach { (id, draft) -> put(id, draft.toJson()) } },
            )
        }
        if (update.isNotEmpty()) {
            put("update", buildJsonObject { update.forEach { (id, patch) -> put(id, patch) } })
        }
        if (destroy.isNotEmpty()) {
            put("destroy", buildJsonArray { destroy.forEach { add(JsonPrimitive(it)) } })
        }
    }

    override fun decode(json: Json, arguments: JsonObject): NoteSetResult =
        json.decodeFromJsonElement(NoteSetResult.serializer(), arguments)

    companion object {
        /** A patch that sets exactly the given properties. */
        fun patch(build: PatchBuilder.() -> Unit): JsonObject = PatchBuilder().apply(build).toJson()
    }

    class PatchBuilder {
        private val fields = linkedMapOf<String, JsonElement>()

        fun title(value: String) {
            fields["title"] = JsonPrimitive(value)
        }

        fun body(value: String) {
            fields["body"] = JsonPrimitive(value)
        }

        fun items(value: List<NoteItem>) {
            fields["items"] = itemsJson(value)
        }

        fun color(value: String?) {
            fields["color"] = value?.let { JsonPrimitive(it) } ?: JsonNull
        }

        fun pinned(value: Boolean) {
            fields["pinned"] = JsonPrimitive(value)
        }

        fun archived(value: Boolean) {
            fields["archived"] = JsonPrimitive(value)
        }

        fun trashed(value: Boolean) {
            fields["trashed"] = JsonPrimitive(value)
        }

        fun links(value: List<NoteLink>) {
            fields["links"] = linksJson(value)
        }

        fun reminderAt(value: String?) {
            fields["reminderAt"] = value?.let { JsonPrimitive(it) } ?: JsonNull
        }

        fun labels(value: List<String>) {
            fields["labels"] = buildJsonArray { value.forEach { add(JsonPrimitive(it)) } }
        }

        fun toJson(): JsonObject = JsonObject(fields)
    }
}

@Serializable
data class NoteSetResult(
    val oldState: String? = null,
    val newState: String = "",
    val created: Map<String, JsonObject> = emptyMap(),
    val notCreated: Map<String, SetError> = emptyMap(),
    val updated: Map<String, JsonObject?> = emptyMap(),
    val notUpdated: Map<String, SetError> = emptyMap(),
    val destroyed: List<String> = emptyList(),
    val notDestroyed: Map<String, SetError> = emptyMap(),
) {
    /** The first refusal of any kind, for a caller that only needs to know "did it land". */
    val firstError: SetError?
        get() =
            notCreated.values.firstOrNull()
                ?: notUpdated.values.firstOrNull()
                ?: notDestroyed.values.firstOrNull()
}

private fun itemsJson(items: List<NoteItem>): JsonArray = buildJsonArray {
    items.forEach {
        add(
            buildJsonObject {
                put("text", it.text)
                put("done", it.done)
            }
        )
    }
}

private fun linksJson(links: List<NoteLink>): JsonArray = buildJsonArray {
    links.forEach {
        add(
            buildJsonObject {
                put("type", it.type)
                put("id", it.id)
                put("title", it.title?.let { t -> JsonPrimitive(t) } ?: JsonNull)
            }
        )
    }
}
