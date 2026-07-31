package de.plmail.jmap.methods

import de.plmail.jmap.mail.MailThread
import de.plmail.jmap.protocol.AccountId
import de.plmail.jmap.protocol.JmapMethod
import de.plmail.jmap.protocol.ResultReference
import de.plmail.jmap.protocol.StateToken
import de.plmail.jmap.protocol.ThreadId
import de.plmail.jmap.protocol.backReference
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ThreadGet(
    private val accountId: AccountId,
    private val ids: List<ThreadId>? = null,
    private val idsReference: ResultReference? = null,
) : JmapMethod<ThreadGetResult> {

    override val name = "Thread/get"

    override fun arguments(): JsonObject = buildJsonObject {
        put("accountId", accountId.value)

        when {
            idsReference != null -> backReference("ids", idsReference)
            ids != null -> put("ids", buildJsonArray { ids.forEach { add(it.value) } })
        }
    }

    override fun decode(json: Json, arguments: JsonObject): ThreadGetResult =
        json.decodeFromJsonElement(ThreadGetResult.serializer(), arguments)

    companion object {
        /** Collects the thread of every message an `Email/get` returned. */
        fun forEmailsOf(accountId: AccountId, emailGet: ResultReference) =
            ThreadGet(accountId = accountId, idsReference = emailGet)
    }
}

@Serializable
data class ThreadGetResult(
    val accountId: String = "",
    val state: String = "",
    val list: List<MailThread> = emptyList(),
    val notFound: List<ThreadId> = emptyList(),
) {
    /**
     * Threads in the order asked for.
     *
     * `Thread/get` reorders just as `Email/get` does — and not into ascending id order either:
     * asking for `[5,1,2,3,4]` comes back `[4,3,2,1,5]`. The client documentation only warns about
     * `Email/get`, which makes this the easier of the two to be caught by.
     */
    fun ordered(ids: List<ThreadId>): List<MailThread> {
        val byId = list.associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }
}

class ThreadChanges(
    private val accountId: AccountId,
    private val sinceState: StateToken,
    private val maxChanges: Int = EmailChanges.MAX_CHANGES,
) : JmapMethod<ThreadChangesResult> {

    override val name = "Thread/changes"

    override fun arguments(): JsonObject = buildJsonObject {
        put("accountId", accountId.value)
        put("sinceState", sinceState.value)
        put("maxChanges", maxChanges)
    }

    override fun decode(json: Json, arguments: JsonObject): ThreadChangesResult =
        json.decodeFromJsonElement(ThreadChangesResult.serializer(), arguments)
}

@Serializable
data class ThreadChangesResult(
    val accountId: String = "",
    val oldState: String = "",
    val newState: String = "",
    val hasMoreChanges: Boolean = false,
    val created: List<ThreadId> = emptyList(),
    val updated: List<ThreadId> = emptyList(),
    val destroyed: List<ThreadId> = emptyList(),
)
