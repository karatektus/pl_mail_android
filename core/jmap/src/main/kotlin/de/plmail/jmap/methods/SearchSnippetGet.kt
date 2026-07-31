package de.plmail.jmap.methods

import de.plmail.jmap.mail.EmailFilter
import de.plmail.jmap.protocol.AccountId
import de.plmail.jmap.protocol.EmailId
import de.plmail.jmap.protocol.JmapMethod
import de.plmail.jmap.protocol.ResultReference
import de.plmail.jmap.protocol.backReference
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `SearchSnippet/get` (RFC 8621 §5) — the bit of a message that actually matched.
 *
 * Without it a ranked full-text search presents identically to a plain filter: every result shows
 * the same opening line, and the reader has to open each one to find out why it came back.
 *
 * The filter is **resent** rather than referred to, because the spec has no notion of a stored
 * query. It must be the same filter the query ran with, or the highlight describes a search nobody
 * performed — [de.plmail.jmap.search.SearchQueryCompiler] produces both from one place for exactly
 * that reason.
 *
 * The server answers over Postgres `ts_headline` on the same vector that ran the query, so a
 * snippet cannot highlight something the search did not match on. Two consequences worth expecting
 * rather than debugging: a term that is a **stopword** (`the`, `is`) highlights nothing, because
 * the query it compiles to is empty; and matching is **stemmed**, so `running` highlights `run`.
 */
class SearchSnippetGet(
    private val accountId: AccountId,
    private val emailIds: List<EmailId>? = null,
    private val idsReference: ResultReference? = null,
    /**
     * The filter the query ran with.
     *
     * Null, or one carrying no free text, yields null strings rather than an error — the spec's way
     * of saying "nothing to highlight", which is distinct from "message not found".
     */
    private val filter: EmailFilter? = null,
) : JmapMethod<SearchSnippetGetResult> {

    override val name = "SearchSnippet/get"

    override fun arguments(): JsonObject = buildJsonObject {
        put("accountId", accountId.value)

        when {
            idsReference != null -> backReference("emailIds", idsReference)
            emailIds != null ->
                put("emailIds", buildJsonArray { emailIds.forEach { add(it.value) } })
        }

        filter?.let { put("filter", it.toJson()) }
    }

    override fun decode(json: Json, arguments: JsonObject): SearchSnippetGetResult =
        json.decodeFromJsonElement(SearchSnippetGetResult.serializer(), arguments)

    companion object {
        /**
         * Snippets for the ids an `Email/query` in the same request returned.
         *
         * Saves a round trip and, more importantly, guarantees the snippets describe *those* rows:
         * a second request could be answered after new mail arrived and highlight a different page.
         */
        fun byReference(
            accountId: AccountId,
            queryReference: ResultReference,
            filter: EmailFilter?,
        ) =
            SearchSnippetGet(
                accountId = accountId,
                idsReference = queryReference,
                filter = filter,
            )
    }
}

@Serializable
data class SearchSnippetGetResult(
    val accountId: String = "",
    val list: List<SearchSnippet> = emptyList(),
    val notFound: List<EmailId> = emptyList(),
) {
    /** By email id, which is how a list row looks one up. */
    fun byEmail(): Map<String, SearchSnippet> = list.associateBy { it.emailId.value }
}

/**
 * One message's highlighted fragments.
 *
 * Both strings are **HTML**, with `<mark>` around each hit and everything else escaped — this is
 * message content coming back for display, so it is rendered as marked-up text rather than
 * concatenated into a document. Null means that field had no hit: the term matched the body, or the
 * subject, or neither, and saying so is the whole value of asking.
 */
@Serializable
data class SearchSnippet(
    val emailId: EmailId,
    val subject: String? = null,
    val preview: String? = null,
) {
    /** Whether this says anything the row does not already show. */
    val hasHighlight: Boolean
        get() = subject != null || preview != null
}
