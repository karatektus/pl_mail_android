package de.plmail.jmap.methods

import de.plmail.jmap.contacts.ContactObject
import de.plmail.jmap.contacts.DuplicatePair
import de.plmail.jmap.contacts.ReferenceEmail
import de.plmail.jmap.protocol.AccountId
import de.plmail.jmap.protocol.ContactId
import de.plmail.jmap.protocol.JmapMethod
import de.plmail.jmap.protocol.backReference
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `Contact/query` — the address book's ids, under `urn:plmail:params:jmap:contacts`.
 *
 * The list is small (an address book, not a mailbox), so the client asks for everything and pages
 * only when the server's `total` says there is more. Sorting is server-side: `"name"` for the list
 * screen, `"recent"` for pickers.
 */
class ContactQuery(
    private val accountId: AccountId,
    private val filter: ContactFilter? = null,
    private val sort: ContactSort? = null,
    private val position: Int = 0,
    private val limit: Int? = null,
) : JmapMethod<ContactQueryResult> {

    init {
        require(position >= 0) { "Contact/query rejects a negative position." }
    }

    override val name = "Contact/query"

    override fun arguments(): JsonObject = buildJsonObject {
        put("accountId", accountId.value)
        filter?.let { put("filter", it.toJson()) }
        sort?.let { put("sort", it.wire) }
        put("position", position)
        limit?.let { put("limit", it) }
    }

    override fun decode(json: Json, arguments: JsonObject): ContactQueryResult =
        json.decodeFromJsonElement(ContactQueryResult.serializer(), arguments)
}

/** The `filter` argument of `Contact/query`. Both fields are optional; both absent means all. */
data class ContactFilter(
    /** Matches display name, company, emails, phones and notes. */
    val text: String? = null,
    val tag: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        text?.takeIf { it.isNotBlank() }?.let { put("text", it) }
        tag?.takeIf { it.isNotBlank() }?.let { put("tag", it) }
    }
}

enum class ContactSort(val wire: String) {
    NAME("name"),
    RECENT("recent"),
}

@Serializable
data class ContactQueryResult(
    val accountId: String = "",
    val queryState: String = "",
    val ids: List<ContactId> = emptyList(),
    val total: Int? = null,
    val position: Int = 0,
    val limit: Int? = null,
)

/**
 * `Contact/get` — the contacts themselves.
 *
 * Pair it with [ContactQuery] in one request through back-references, the way the calendar pairs a
 * query with its get: the address book fits in one round trip.
 */
class ContactGet(
    private val accountId: AccountId,
    private val ids: List<ContactId>? = null,
    private val idsReference: de.plmail.jmap.protocol.ResultReference? = null,
    private val properties: List<String>? = null,
) : JmapMethod<ContactGetResult> {

    override val name = "Contact/get"

    override fun arguments(): JsonObject = buildJsonObject {
        put("accountId", accountId.value)

        when {
            idsReference != null -> backReference("ids", idsReference)
            ids != null -> put("ids", buildJsonArray { ids.forEach { add(it.value) } })
            else -> put("ids", JsonNull)
        }

        properties?.let { put("properties", buildJsonArray { it.forEach { p -> add(p) } }) }
    }

    override fun decode(json: Json, arguments: JsonObject): ContactGetResult =
        json.decodeFromJsonElement(ContactGetResult.serializer(), arguments)

    companion object {
        /**
         * Pairs a `Contact/query` with the get that consumes it, in one request — the way the
         * calendar pairs its query with its get. The address book fits in one round trip.
         */
        fun byReference(
            accountId: AccountId,
            queryReference: de.plmail.jmap.protocol.ResultReference,
            properties: List<String>? = null,
        ) =
            ContactGet(
                accountId = accountId,
                idsReference = queryReference,
                properties = properties,
            )
    }
}

@Serializable
data class ContactGetResult(
    val accountId: String = "",
    val state: String = "",
    val list: List<ContactObject> = emptyList(),
    val notFound: List<ContactId> = emptyList(),
) {
    /** The contacts in the order asked for — `Contact/get` preserves the requested order. */
    fun ordered(ids: List<ContactId>): List<ContactObject> {
        val byId = list.associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }
}

/**
 * `Contact/set` — create, update and soft-delete contacts.
 *
 * `create` maps a client-chosen id (any string, `"c1"` by convention) to the new contact; the
 * answer's `created` maps it to the server-assigned id. `update` maps a contact id to a patch —
 * emails/phones/addresses are **replace-all** when the key is present. `destroy` is a soft delete:
 * the contact moves to the 90-day trash, it is not gone.
 */
class ContactSet(
    private val accountId: AccountId,
    private val create: Map<String, JsonObject>? = null,
    private val update: Map<ContactId, JsonObject>? = null,
    private val destroy: List<ContactId>? = null,
) : JmapMethod<ContactSetResult> {

    override val name = "Contact/set"

    override fun arguments(): JsonObject = buildJsonObject {
        put("accountId", accountId.value)
        create?.let {
            put("create", buildJsonObject { it.forEach { (k, v) -> put(k, v) } })
        }
        update?.let {
            put("update", buildJsonObject { it.forEach { (k, v) -> put(k.value, v) } })
        }
        destroy?.let {
            put("destroy", buildJsonArray { it.forEach { id -> add(id.value) } })
        }
    }

    override fun decode(json: Json, arguments: JsonObject): ContactSetResult =
        json.decodeFromJsonElement(ContactSetResult.serializer(), arguments)

    companion object {
        /** The client id for a single create, so call sites do not invent their own. */
        const val CREATION_ID = "c1"
    }
}

@Serializable
data class ContactSetResult(
    val accountId: String = "",
    val oldState: String? = null,
    val newState: String = "",
    /** Client id → the contact the server made of it. */
    val created: Map<String, ContactCreated> = emptyMap(),
    /** Contact id → null on success; a null value is the success marker, not an absence. */
    val updated: Map<String, kotlinx.serialization.json.JsonElement?> = emptyMap(),
    val destroyed: List<ContactId> = emptyList(),
    val notCreated: Map<String, SetError> = emptyMap(),
    val notUpdated: Map<String, SetError> = emptyMap(),
    val notDestroyed: Map<String, SetError> = emptyMap(),
) {
    fun firstFailure(): SetError? =
        notCreated.values.firstOrNull()
            ?: notUpdated.values.firstOrNull()
            ?: notDestroyed.values.firstOrNull()
}

@Serializable data class ContactCreated(val id: ContactId)

/**
 * `Contact/referenceEmails` — every non-deleted message to or from a contact, newest first.
 *
 * Covers inbox, sent, drafts, trash, labels and starred: the "REFERENCE EMAILS" section on the
 * contact detail screen. The shape is deliberately small — a list row, not a message — because
 * opening one hands the id to the mail module.
 */
class ContactReferenceEmails(
    private val accountId: AccountId,
    private val contactId: ContactId,
    private val limit: Int = DEFAULT_LIMIT,
) : JmapMethod<ContactReferenceEmailsResult> {

    override val name = "Contact/referenceEmails"

    override fun arguments(): JsonObject = buildJsonObject {
        put("accountId", accountId.value)
        put("contactId", contactId.value)
        put("limit", limit)
    }

    override fun decode(json: Json, arguments: JsonObject): ContactReferenceEmailsResult =
        json.decodeFromJsonElement(ContactReferenceEmailsResult.serializer(), arguments)

    companion object {
        const val DEFAULT_LIMIT = 50
    }
}

@Serializable
data class ContactReferenceEmailsResult(
    val accountId: String = "",
    val contactId: String = "",
    val list: List<ReferenceEmail> = emptyList(),
)

/**
 * `Contact/duplicates` — the server's duplicate candidate pairs for the whole address book.
 *
 * The confidence tiers and the reasons are the server's (same normalized email: 100,
 * auto-mergeable; same normalized phone: 95, auto-mergeable; …), because the normalization the
 * tiers depend on lives server-side next to the data. The client draws them and asks which contact
 * to keep; it never computes its own pairs.
 */
class ContactDuplicates(private val accountId: AccountId) : JmapMethod<ContactDuplicatesResult> {

    override val name = "Contact/duplicates"

    override fun arguments(): JsonObject = buildJsonObject {
        put("accountId", accountId.value)
    }

    override fun decode(json: Json, arguments: JsonObject): ContactDuplicatesResult =
        json.decodeFromJsonElement(ContactDuplicatesResult.serializer(), arguments)
}

@Serializable
data class ContactDuplicatesResult(
    val accountId: String = "",
    val pairs: List<DuplicatePair> = emptyList(),
)

/**
 * `Contact/merge` — fold [mergeIds] into [keepId].
 *
 * The server moves emails/phones/addresses/notes/tags to the kept contact (deduping identical
 * values), fills its empty scalar fields from the merged ones, and writes a merge record the undo
 * call names. The answer is that record's id, kept so the UI can offer "Undo" without a second
 * round trip.
 */
class ContactMerge(
    private val accountId: AccountId,
    private val keepId: ContactId,
    private val mergeIds: List<ContactId>,
) : JmapMethod<ContactMergeResult> {

    init {
        require(mergeIds.isNotEmpty()) { "Contact/merge with nothing to merge is a no-op." }
        require(keepId !in mergeIds) { "The kept contact cannot also be merged away." }
    }

    override val name = "Contact/merge"

    override fun arguments(): JsonObject = buildJsonObject {
        put("accountId", accountId.value)
        put("keepId", keepId.value)
        put("mergeIds", buildJsonArray { mergeIds.forEach { add(it.value) } })
    }

    override fun decode(json: Json, arguments: JsonObject): ContactMergeResult =
        json.decodeFromJsonElement(ContactMergeResult.serializer(), arguments)
}

@Serializable
data class ContactMergeResult(
    val accountId: String = "",
    /** The merge record id, for [ContactMergeUndo] and for the undo snackbar. */
    val mergeRecordId: String = "",
)

/**
 * `Contact/mergeUndo` — restore the contacts a merge folded away, from the merge record's snapshot.
 * Valid for 90 days after the merge.
 */
class ContactMergeUndo(
    private val accountId: AccountId,
    private val mergeRecordId: String,
) : JmapMethod<ContactMergeUndoResult> {

    override val name = "Contact/mergeUndo"

    override fun arguments(): JsonObject = buildJsonObject {
        put("accountId", accountId.value)
        put("mergeRecordId", mergeRecordId)
    }

    override fun decode(json: Json, arguments: JsonObject): ContactMergeUndoResult =
        json.decodeFromJsonElement(ContactMergeUndoResult.serializer(), arguments)
}

@Serializable data class ContactMergeUndoResult(val accountId: String = "")
