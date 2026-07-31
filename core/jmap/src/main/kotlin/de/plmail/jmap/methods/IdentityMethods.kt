package de.plmail.jmap.methods

import de.plmail.jmap.mail.Identity
import de.plmail.jmap.protocol.AccountId
import de.plmail.jmap.protocol.IdentityId
import de.plmail.jmap.protocol.JmapMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `Identity/get` — the addresses this account may send from, primary first.
 *
 * Excluded from push notifications on purpose: identities change only when the user edits their own
 * addresses, which they just did in the app.
 */
class IdentityGet(private val accountId: AccountId) : JmapMethod<IdentityGetResult> {

    override val name = "Identity/get"

    override fun arguments(): JsonObject = buildJsonObject {
        put("accountId", accountId.value)
        put("ids", JsonNull)
    }

    override fun decode(json: Json, arguments: JsonObject): IdentityGetResult =
        json.decodeFromJsonElement(IdentityGetResult.serializer(), arguments)
}

@Serializable
data class IdentityGetResult(
    val accountId: String = "",
    val state: String = "",
    val list: List<Identity> = emptyList(),
    val notFound: List<IdentityId> = emptyList(),
) {
    /** The default From. The server already orders primary first. */
    val primary: Identity?
        get() = list.firstOrNull()
}
