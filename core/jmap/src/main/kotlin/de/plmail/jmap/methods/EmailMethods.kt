package de.plmail.jmap.methods

import de.plmail.jmap.mail.Comparator
import de.plmail.jmap.mail.Email
import de.plmail.jmap.mail.EmailFilter
import de.plmail.jmap.protocol.AccountId
import de.plmail.jmap.protocol.EmailId
import de.plmail.jmap.protocol.JmapMethod
import de.plmail.jmap.protocol.ResultReference
import de.plmail.jmap.protocol.StateToken
import de.plmail.jmap.protocol.backReference
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `Email/query` — which messages, in what order.
 *
 * Two constraints worth knowing before building on it. `canCalculateChanges` is always false and
 * `Email/queryChanges` does not exist, so refreshing a list means re-running the query. And
 * **anchor paging is refused** — use [position] with [limit]; negative positions are rejected here
 * too (though `Mailbox/query` does accept them).
 */
class EmailQuery(
    private val accountId: AccountId,
    private val filter: EmailFilter? = null,
    private val sort: List<Comparator> = listOf(Comparator.NEWEST_FIRST),
    private val position: Int = 0,
    private val limit: Int? = null,
    private val collapseThreads: Boolean = false,
) : JmapMethod<EmailQueryResult> {

    init {
        require(position >= 0) {
            "Email/query rejects a negative position; page forward with position + limit."
        }
    }

    override val name = "Email/query"

    override fun arguments(): JsonObject = buildJsonObject {
        // A JSON *string*, always. An integer is rejected with
        // invalidArguments rather than coerced, and the error carries no
        // description saying which argument was wrong.
        put("accountId", accountId.value)
        filter?.let { put("filter", it.toJson()) }
        put("sort", buildJsonArray { sort.forEach { add(it.toJson()) } })
        put("position", position)
        limit?.let { put("limit", it) }
        if (collapseThreads) put("collapseThreads", true)
    }

    override fun decode(json: Json, arguments: JsonObject): EmailQueryResult =
        json.decodeFromJsonElement(EmailQueryResult.serializer(), arguments)
}

@Serializable
data class EmailQueryResult(
    val accountId: String = "",
    val queryState: String = "",
    /** Always false on this server; `Email/queryChanges` is not implemented. */
    val canCalculateChanges: Boolean = false,
    val position: Int = 0,
    val ids: List<EmailId> = emptyList(),
    /** `Email/query` always returns this (unlike `Mailbox/query`). */
    val total: Int? = null,
    /** Echoes the requested limit, not the server's 500 cap. */
    val limit: Int? = null,
)

/**
 * `Email/get`.
 *
 * Pair it with [EmailQuery] in one request via [byReference] rather than waiting for ids and asking
 * again — one round trip instead of two matters when the server is on someone's home uplink.
 */
class EmailGet(
    private val accountId: AccountId,
    private val ids: List<EmailId>? = null,
    private val idsReference: ResultReference? = null,
    private val properties: List<String>? = LIST_ROW_PROPERTIES,
    private val fetchTextBodyValues: Boolean = false,
    private val fetchHtmlBodyValues: Boolean = false,
    private val maxBodyValueBytes: Int? = null,
) : JmapMethod<EmailGetResult> {

    override val name = "Email/get"

    override fun arguments(): JsonObject = buildJsonObject {
        put("accountId", accountId.value)

        when {
            idsReference != null -> backReference("ids", idsReference)
            ids != null -> put("ids", buildJsonArray { ids.forEach { add(it.value) } })
        }

        properties?.let { put("properties", buildJsonArray { it.forEach { p -> add(p) } }) }

        if (fetchTextBodyValues) put("fetchTextBodyValues", true)

        // NOTE THE CAPITALISATION: fetchHTMLBodyValues, not fetchHtmlBodyValues.
        // That is the RFC 8621 spelling and what the server reads. An
        // unrecognised argument is simply absent rather than an error, so the
        // wrong spelling returns empty bodyValues with nothing to debug.
        if (fetchHtmlBodyValues) put("fetchHTMLBodyValues", true)

        maxBodyValueBytes?.let { put("maxBodyValueBytes", it) }
    }

    override fun decode(json: Json, arguments: JsonObject): EmailGetResult =
        json.decodeFromJsonElement(EmailGetResult.serializer(), arguments)

    companion object {
        /** Enough to draw a list row, and no body — bodies are large. */
        val LIST_ROW_PROPERTIES =
            listOf(
                "id",
                "threadId",
                "blobId",
                "subject",
                "from",
                "to",
                "receivedAt",
                "sentAt",
                "preview",
                "keywords",
                "hasAttachment",
                "mailboxIds",
                "size",
            )

        /** Everything the reader needs, bodies included. */
        val READER_PROPERTIES =
            LIST_ROW_PROPERTIES +
                listOf(
                    "cc",
                    "bcc",
                    "replyTo",
                    "messageId",
                    "inReplyTo",
                    "references",
                    "textBody",
                    "htmlBody",
                    "attachments",
                    "bodyValues",
                )

        /** Fetches exactly the ids an earlier query produced, in one request. */
        fun byReference(
            accountId: AccountId,
            queryReference: ResultReference,
            properties: List<String>? = LIST_ROW_PROPERTIES,
            fetchTextBodyValues: Boolean = false,
            fetchHtmlBodyValues: Boolean = false,
        ) =
            EmailGet(
                accountId = accountId,
                idsReference = queryReference,
                properties = properties,
                fetchTextBodyValues = fetchTextBodyValues,
                fetchHtmlBodyValues = fetchHtmlBodyValues,
            )
    }
}

@Serializable
data class EmailGetResult(
    val accountId: String = "",
    val state: String = "",
    val list: List<Email> = emptyList(),
    val notFound: List<EmailId> = emptyList(),
) {
    /**
     * The messages in the order *asked for*, rather than the order returned.
     *
     * **`Email/get` does not preserve the requested order.** It reads rows in repository order and
     * computes `notFound` by difference, so a query returning `[5,1,2,3,4]` newest-first is
     * answered `[1,2,3,4,5]` — the newest message arrives last. A list rendered straight from
     * `list` is therefore sorted by database id, which looks almost right on a small mailbox and
     * completely wrong on a real one.
     *
     * Any pairing of query with get must go through this.
     */
    fun ordered(ids: List<EmailId>): List<Email> {
        val byId = list.associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }
}

/**
 * `Email/changes` — what changed, not what order things are in.
 *
 * Two things that are easy to get wrong:
 *
 * Asking from [StateToken.INITIAL] does **not** enumerate existing mail. It answers with empty
 * created/updated/destroyed arrays, because there have been no *changes* since the beginning of the
 * log. A fresh client is populated by [EmailQuery]; `/changes` only ever keeps an already-populated
 * one current.
 *
 * The server caps this at 256 rows and sets `hasMoreChanges`, so callers loop until it clears. The
 * cap is deliberately modest for mobile.
 */
class EmailChanges(
    private val accountId: AccountId,
    private val sinceState: StateToken,
    private val maxChanges: Int = MAX_CHANGES,
) : JmapMethod<EmailChangesResult> {

    override val name = "Email/changes"

    override fun arguments(): JsonObject = buildJsonObject {
        put("accountId", accountId.value)
        put("sinceState", sinceState.value)
        put("maxChanges", maxChanges)
    }

    override fun decode(json: Json, arguments: JsonObject): EmailChangesResult =
        json.decodeFromJsonElement(EmailChangesResult.serializer(), arguments)

    companion object {
        const val MAX_CHANGES = 256
    }
}

@Serializable
data class EmailChangesResult(
    val accountId: String = "",
    val oldState: String = "",
    val newState: String = "",
    val hasMoreChanges: Boolean = false,
    val created: List<EmailId> = emptyList(),
    val updated: List<EmailId> = emptyList(),
    val destroyed: List<EmailId> = emptyList(),
) {
    /** Created and updated both need re-fetching; only the reason differs. */
    val changed: List<EmailId>
        get() = created + updated

    val isEmpty: Boolean
        get() = created.isEmpty() && updated.isEmpty() && destroyed.isEmpty()
}
