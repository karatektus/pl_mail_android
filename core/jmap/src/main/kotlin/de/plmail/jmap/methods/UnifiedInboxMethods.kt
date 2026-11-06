package de.plmail.jmap.methods

import de.plmail.jmap.mail.UnifiedInbox
import de.plmail.jmap.protocol.AccountId
import de.plmail.jmap.protocol.JmapMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `UnifiedInbox/get` — plMail's extension, under `urn:plmail:params:jmap:unifiedinboxes`.
 *
 * Every inbox in the sidebar's order. Every account answers with the same list; the account id only
 * decides whose change log [UnifiedInboxGetResult.state] belongs to. A request carrying this must
 * declare [de.plmail.jmap.protocol.Capability.USING_UNIFIED_INBOXES].
 *
 * No `/changes` client: the list is a handful of rows, so a push naming the type reads it again.
 */
class UnifiedInboxGet(private val accountId: AccountId) : JmapMethod<UnifiedInboxGetResult> {

    override val name = "UnifiedInbox/get"

    override fun arguments(): JsonObject = buildJsonObject {
        put("accountId", accountId.value)
        put("ids", JsonNull)
    }

    override fun decode(json: Json, arguments: JsonObject): UnifiedInboxGetResult =
        json.decodeFromJsonElement(UnifiedInboxGetResult.serializer(), arguments)
}

@Serializable
data class UnifiedInboxGetResult(
    val accountId: String = "",
    val state: String = "",
    val list: List<UnifiedInbox> = emptyList(),
    val notFound: List<String> = emptyList(),
)
