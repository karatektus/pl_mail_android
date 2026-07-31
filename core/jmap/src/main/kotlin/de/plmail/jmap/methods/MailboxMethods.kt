package de.plmail.jmap.methods

import de.plmail.jmap.mail.Mailbox
import de.plmail.jmap.protocol.AccountId
import de.plmail.jmap.protocol.JmapMethod
import de.plmail.jmap.protocol.MailboxId
import de.plmail.jmap.protocol.StateToken
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** `Mailbox/get`. Passing no ids fetches every mailbox in the account. */
class MailboxGet(private val accountId: AccountId, private val ids: List<MailboxId>? = null) :
    JmapMethod<MailboxGetResult> {

    override val name = "Mailbox/get"

    override fun arguments(): JsonObject = buildJsonObject {
        put("accountId", accountId.value)

        // Explicit null rather than omitted: JMAP distinguishes "all of them"
        // (null) from "none of them" (an empty array), and omitting the
        // argument is the former only by convention.
        if (ids == null) {
            put("ids", JsonNull)
        } else {
            put("ids", buildJsonArray { ids.forEach { add(it.value) } })
        }
    }

    override fun decode(json: Json, arguments: JsonObject): MailboxGetResult =
        json.decodeFromJsonElement(MailboxGetResult.serializer(), arguments)
}

@Serializable
data class MailboxGetResult(
    val accountId: String = "",
    val state: String = "",
    val list: List<Mailbox> = emptyList(),
    val notFound: List<MailboxId> = emptyList(),
) {
    /**
     * Sidebar order: system labels in their fixed order, then custom ones alphabetically.
     *
     * Not sorted by the server's `sortOrder`, which reports 0 for Inbox *and* for every custom
     * label, so ordering by it alone does not reproduce the documented order.
     */
    fun inSidebarOrder(): List<Mailbox> =
        list.sortedWith(
            compareBy(
                { it.knownRole?.sidebarOrder ?: Int.MAX_VALUE },
                { it.name.lowercase() },
            )
        )

    fun withRole(role: de.plmail.jmap.mail.MailboxRole): Mailbox? = list.firstOrNull {
        it.knownRole == role
    }
}

class MailboxChanges(
    private val accountId: AccountId,
    private val sinceState: StateToken,
    private val maxChanges: Int = EmailChanges.MAX_CHANGES,
) : JmapMethod<MailboxChangesResult> {

    override val name = "Mailbox/changes"

    override fun arguments(): JsonObject = buildJsonObject {
        put("accountId", accountId.value)
        put("sinceState", sinceState.value)
        put("maxChanges", maxChanges)
    }

    override fun decode(json: Json, arguments: JsonObject): MailboxChangesResult =
        json.decodeFromJsonElement(MailboxChangesResult.serializer(), arguments)
}

@Serializable
data class MailboxChangesResult(
    val accountId: String = "",
    val oldState: String = "",
    val newState: String = "",
    val hasMoreChanges: Boolean = false,
    val created: List<MailboxId> = emptyList(),
    val updated: List<MailboxId> = emptyList(),
    val destroyed: List<MailboxId> = emptyList(),
)
