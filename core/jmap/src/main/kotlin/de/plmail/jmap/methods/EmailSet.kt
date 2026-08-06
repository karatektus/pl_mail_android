package de.plmail.jmap.methods

import de.plmail.jmap.mail.EmailAddress
import de.plmail.jmap.mail.Keyword
import de.plmail.jmap.protocol.AccountId
import de.plmail.jmap.protocol.BlobId
import de.plmail.jmap.protocol.EmailId
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
 * `Email/set` — create drafts, change flags and labels, and "destroy".
 *
 * **"Destroy" moves to Trash.** There is no hard-delete path anywhere in plMail, deliberately:
 * deleting a row would discard the local copy of mail the provider still holds. Destructive UI must
 * say Trash, and must be undoable.
 *
 * Every change here goes through the same propagator the web UI uses, so archiving from this app
 * archives in Gmail. That also means the effect is asynchronous beyond the local database — apply
 * optimistically and reconcile.
 */
class EmailSet(
    private val accountId: AccountId,
    private val create: Map<String, DraftEmail> = emptyMap(),
    private val update: Map<EmailId, EmailPatch> = emptyMap(),
    private val destroy: List<EmailId> = emptyList(),
    /**
     * Rejects the whole call if the server has moved on.
     *
     * Worth setting for batch mutations — without it, two clients editing the same conversation
     * silently take turns overwriting each other.
     */
    private val ifInState: StateToken? = null,
) : JmapMethod<EmailSetResult> {

    override val name = "Email/set"

    override fun arguments(): JsonObject = buildJsonObject {
        put("accountId", accountId.value)
        ifInState?.let { put("ifInState", it.value) }

        if (create.isNotEmpty()) {
            put(
                "create",
                buildJsonObject { create.forEach { (id, draft) -> put(id, draft.toJson()) } },
            )
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

    override fun decode(json: Json, arguments: JsonObject): EmailSetResult =
        json.decodeFromJsonElement(EmailSetResult.serializer(), arguments)
}

/**
 * A partial update, in JMAP's JSON-pointer patch form.
 *
 * Patching rather than replacing matters for `mailboxIds` and `keywords`: both are maps, and
 * sending a whole new map means a client that fetched stale state silently removes a label another
 * client added a second ago. `mailboxIds/42: true` adds one without asserting anything about the
 * rest.
 */
class EmailPatch private constructor(private val fields: Map<String, JsonElement>) {

    fun toJson(): JsonObject = JsonObject(fields)

    val isEmpty: Boolean
        get() = fields.isEmpty()

    class Builder {
        private val fields = mutableMapOf<String, JsonElement>()

        fun addMailbox(id: MailboxId) = apply {
            fields["mailboxIds/${id.value}"] = JsonPrimitive(true)
        }

        /** Removing is a null, not a false. A false would *set* the key. */
        fun removeMailbox(id: MailboxId) = apply { fields["mailboxIds/${id.value}"] = JsonNull }

        fun keyword(keyword: Keyword, present: Boolean) = apply {
            fields["keywords/${keyword.wire}"] = if (present) JsonPrimitive(true) else JsonNull
        }

        fun seen(value: Boolean) = keyword(Keyword.SEEN, value)

        fun flagged(value: Boolean) = keyword(Keyword.FLAGGED, value)

        /**
         * A header the composer owns, on a draft.
         *
         * The server refuses these on anything that is not a draft — a received message's body is a
         * record of what arrived, and a client able to rewrite it would make the mailbox
         * unfalsifiable.
         *
         * A blank value is sent as JSON null rather than `""`, because the two mean different
         * things: the server treats an absent key as "leave it alone", so clearing a subject the
         * user has deleted needs the null.
         */
        fun text(property: String, value: String?) = apply {
            fields[property] = value?.takeIf { it.isNotBlank() }?.let(::JsonPrimitive) ?: JsonNull
        }

        fun addresses(property: String, value: List<EmailAddress>) = apply {
            fields[property] =
                if (value.isEmpty()) {
                    JsonNull
                } else {
                    buildJsonArray {
                        value.forEach { address ->
                            add(
                                buildJsonObject {
                                    address.name?.let { put("name", it) }
                                    address.email?.let { put("email", it) }
                                }
                            )
                        }
                    }
                }
        }

        fun strings(property: String, value: List<String>) = apply {
            fields[property] = buildJsonArray { value.forEach { add(it) } }
        }

        /**
         * The HTML body, as the part list and the value the part names.
         *
         * Both keys, always: `bodyValues` is keyed by `partId`, so sending the values without the
         * `htmlBody` part that names them leaves the server with a map it cannot look anything up
         * in, and the body silently does not change.
         */
        fun html(value: String) = apply {
            fields["htmlBody"] = buildJsonArray {
                add(
                    buildJsonObject {
                        put("partId", "html")
                        put("type", "text/html")
                    }
                )
            }

            fields["bodyValues"] = buildJsonObject {
                put("html", buildJsonObject { put("value", value) })
            }
        }

        /**
         * The complete attachment set, on a draft.
         *
         * **Whole-value, not a patch.** What is sent is what the draft ends up with: a part left
         * out is removed, and a part kept is named by the `p-` blobId `Email/get` handed out —
         * which costs no upload and keeps the same part id. A `p-` blob from a *different* message
         * is copied in, which is what makes forwarding an attachment free.
         *
         * An empty list is sent as an empty array rather than omitted or nulled, because that is
         * how "remove them all" is said. Omitting the key entirely means "leave them alone", so
         * every save that has not touched the attachments should not call this at all.
         *
         * An unresolvable blobId — expired, malformed, another account's — refuses the **whole
         * patch** with `invalidProperties` and writes nothing, subject and body included. That is
         * stricter than the rest of `Email/set` and it is the useful direction: there is nothing to
         * roll back, and the draft is exactly as it was.
         */
        fun attachments(parts: List<DraftAttachment>) = apply {
            fields["attachments"] = buildJsonArray { parts.forEach { add(it.toJson()) } }
        }

        /**
         * Archiving is *removing the Inbox label*, and nothing else.
         *
         * Adding an Archive label instead leaves the message in the inbox as well, which is not
         * what anyone means by archiving. The Archive label is IMAP location bookkeeping for
         * plain-IMAP accounts and is hidden by default.
         */
        fun archive(inbox: MailboxId) = removeMailbox(inbox)

        fun build() = EmailPatch(fields.toMap())
    }

    companion object {
        fun build(block: Builder.() -> Unit): EmailPatch = Builder().apply(block).build()
    }
}

/** A draft being created. */
data class DraftEmail(
    val mailboxIds: List<MailboxId>,
    val from: List<EmailAddress> = emptyList(),
    val to: List<EmailAddress> = emptyList(),
    val cc: List<EmailAddress> = emptyList(),
    val bcc: List<EmailAddress> = emptyList(),
    val replyTo: List<EmailAddress> = emptyList(),
    val subject: String? = null,
    val textBody: String? = null,
    val htmlBody: String? = null,
    /** Bare ids without angle brackets, as the server emits them. */
    val inReplyTo: List<String>? = null,
    val references: List<String>? = null,
    val attachments: List<DraftAttachment> = emptyList(),
    val keywords: List<Keyword> = listOf(Keyword.DRAFT, Keyword.SEEN),
) {
    fun toJson(): JsonObject = buildJsonObject {
        put(
            "mailboxIds",
            buildJsonObject { mailboxIds.forEach { put(it.value, JsonPrimitive(true)) } },
        )
        put("keywords", buildJsonObject { keywords.forEach { put(it.wire, JsonPrimitive(true)) } })

        putAddresses("from", from)
        putAddresses("to", to)
        putAddresses("cc", cc)
        putAddresses("bcc", bcc)
        putAddresses("replyTo", replyTo)

        subject?.let { put("subject", it) }

        // A reply that omits these starts a new conversation instead of
        // continuing one — the single most visible way a compose screen can be
        // subtly wrong.
        inReplyTo?.let { put("inReplyTo", buildJsonArray { it.forEach { id -> add(id) } }) }
        references?.let { put("references", buildJsonArray { it.forEach { id -> add(id) } }) }

        textBody?.let {
            put(
                "textBody",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("partId", "text")
                            put("type", "text/plain")
                        }
                    )
                },
            )
        }

        htmlBody?.let {
            put(
                "htmlBody",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("partId", "html")
                            put("type", "text/html")
                        }
                    )
                },
            )
        }

        val values = buildJsonObject {
            textBody?.let { put("text", buildJsonObject { put("value", it) }) }
            htmlBody?.let { put("html", buildJsonObject { put("value", it) }) }
        }

        if (values.isNotEmpty()) put("bodyValues", values)

        if (attachments.isNotEmpty()) {
            put("attachments", buildJsonArray { attachments.forEach { add(it.toJson()) } })
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putAddresses(
        key: String,
        addresses: List<EmailAddress>,
    ) {
        if (addresses.isEmpty()) return

        put(
            key,
            buildJsonArray {
                addresses.forEach { address ->
                    add(
                        buildJsonObject {
                            address.name?.let { put("name", it) }
                            address.email?.let { put("email", it) }
                        }
                    )
                }
            },
        )
    }
}

data class DraftAttachment(
    val blobId: BlobId,
    val type: String,
    val name: String?,
    val cid: String? = null,
    val isInline: Boolean = false,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("blobId", blobId.value)
        put("type", type)
        name?.let { put("name", it) }
        cid?.let { put("cid", it) }
        put("disposition", if (isInline) "inline" else "attachment")
    }
}

@Serializable
data class EmailSetResult(
    val accountId: String = "",
    val oldState: String? = null,
    val newState: String = "",
    /** Server-assigned ids, keyed by the creation id the client chose. */
    val created: Map<String, CreatedEmail> = emptyMap(),
    val notCreated: Map<String, SetError> = emptyMap(),
    val updated: Map<String, JsonElement?> = emptyMap(),
    val notUpdated: Map<String, SetError> = emptyMap(),
    val destroyed: List<EmailId> = emptyList(),
    val notDestroyed: Map<String, SetError> = emptyMap(),
) {
    val hasFailures: Boolean
        get() = notCreated.isNotEmpty() || notUpdated.isNotEmpty() || notDestroyed.isNotEmpty()

    /** The first failure, for a message the user can act on. */
    fun firstFailure(): SetError? =
        notCreated.values.firstOrNull()
            ?: notUpdated.values.firstOrNull()
            ?: notDestroyed.values.firstOrNull()
}

@Serializable
data class CreatedEmail(
    val id: EmailId,
    val blobId: BlobId? = null,
    val threadId: String? = null,
    val size: Long = 0,
)

@Serializable
data class SetError(
    val type: String = "",
    val description: String? = null,
    val properties: List<String>? = null,
)
