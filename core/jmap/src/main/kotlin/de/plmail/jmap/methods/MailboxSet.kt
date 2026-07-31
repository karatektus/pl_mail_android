package de.plmail.jmap.methods

import de.plmail.jmap.protocol.AccountId
import de.plmail.jmap.protocol.JmapMethod
import de.plmail.jmap.protocol.MailboxId
import de.plmail.jmap.protocol.StateToken
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `Mailbox/set` — creating, renaming and deleting labels.
 *
 * Labels are the user-facing concept; a Mailbox is the per-account binding of one. System labels
 * (anything with a role) report `mayRename: false` and `mayDelete: false` and the server enforces
 * it, so check [de.plmail.jmap.mail.Mailbox.myRights] before offering either.
 *
 * Deleting a label deletes the label, not the mail in it.
 */
class MailboxSet(
    private val accountId: AccountId,
    private val create: Map<String, NewMailbox> = emptyMap(),
    private val update: Map<MailboxId, MailboxPatch> = emptyMap(),
    private val destroy: List<MailboxId> = emptyList(),
    private val ifInState: StateToken? = null,
) : JmapMethod<MailboxSetResult> {

    override val name = "Mailbox/set"

    override fun arguments(): JsonObject = buildJsonObject {
        put("accountId", accountId.value)
        ifInState?.let { put("ifInState", it.value) }

        if (create.isNotEmpty()) {
            put("create", buildJsonObject { create.forEach { (id, m) -> put(id, m.toJson()) } })
        }

        if (update.isNotEmpty()) {
            put(
                "update",
                buildJsonObject { update.forEach { (id, patch) -> put(id.value, patch.toJson()) } },
            )
        }

        if (destroy.isNotEmpty()) {
            put("destroy", buildJsonArray { destroy.forEach { add(it.value) } })
        }
    }

    override fun decode(json: Json, arguments: JsonObject): MailboxSetResult =
        json.decodeFromJsonElement(MailboxSetResult.serializer(), arguments)
}

data class NewMailbox(
    /** Leaf name only — hierarchy is expressed through [parentId]. */
    val name: String,
    val parentId: MailboxId? = null,
    val isSubscribed: Boolean = true,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("name", name)
        put("parentId", parentId?.let { JsonPrimitive(it.value) } ?: JsonNull)
        put("isSubscribed", isSubscribed)
    }
}

class MailboxPatch private constructor(private val fields: Map<String, JsonElement>) {

    fun toJson(): JsonObject = JsonObject(fields)

    class Builder {
        private val fields = mutableMapOf<String, JsonElement>()

        fun rename(name: String) = apply { fields["name"] = JsonPrimitive(name) }

        fun reparent(parentId: MailboxId?) = apply {
            fields["parentId"] = parentId?.let { JsonPrimitive(it.value) } ?: JsonNull
        }

        /**
         * Whether the label shows in the sidebar.
         *
         * Archive is created hidden and only appears once switched on, so this is a normal thing
         * for a client to set rather than an obscure corner.
         */
        fun subscribed(value: Boolean) = apply { fields["isSubscribed"] = JsonPrimitive(value) }

        fun build() = MailboxPatch(fields.toMap())
    }

    companion object {
        fun build(block: Builder.() -> Unit): MailboxPatch = Builder().apply(block).build()
    }
}

@Serializable
data class MailboxSetResult(
    val accountId: String = "",
    val oldState: String? = null,
    val newState: String = "",
    val created: Map<String, CreatedMailbox> = emptyMap(),
    val notCreated: Map<String, SetError> = emptyMap(),
    val updated: Map<String, JsonElement?> = emptyMap(),
    val notUpdated: Map<String, SetError> = emptyMap(),
    val destroyed: List<MailboxId> = emptyList(),
    val notDestroyed: Map<String, SetError> = emptyMap(),
)

@Serializable data class CreatedMailbox(val id: MailboxId, val sortOrder: Int = 0)
