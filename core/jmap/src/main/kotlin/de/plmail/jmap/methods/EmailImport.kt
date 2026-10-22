package de.plmail.jmap.methods

import de.plmail.jmap.protocol.AccountId
import de.plmail.jmap.protocol.BlobId
import de.plmail.jmap.protocol.JmapMethod
import de.plmail.jmap.protocol.MailboxId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * `Email/import` (RFC 8621 §4.8) — a message this device built, uploaded as a blob, stored as an
 * Email.
 *
 * The way an end-to-end encrypted message is sent (#9, phase 4): the server cannot build one — it
 * has no keys — so the app writes the whole RFC 5322 message, imports it as a draft (`$draft`) and
 * submits that. plMail keeps the message's Content-* headers and body byte for byte and writes the
 * outer headers again from what it stored when the mail leaves.
 *
 * For an encrypted draft, plMail's extension [ImportedEmail.fingerprints] names every key the
 * message is encrypted to. The server refuses the import (`forbidden`) unless each recipient's
 * current key is among them — a message only some of its recipients could read is not let out.
 */
class EmailImport(
    private val accountId: AccountId,
    private val emails: Map<String, ImportedEmail>,
) : JmapMethod<EmailImportResult> {

    override val name = "Email/import"

    override fun arguments(): JsonObject = buildJsonObject {
        put("accountId", accountId.value)
        putJsonObject("emails") {
            emails.forEach { (creationId, email) ->
                putJsonObject(creationId) {
                    put("blobId", email.blobId.value)
                    putJsonObject("mailboxIds") { email.mailboxIds.forEach { put(it.value, true) } }
                    putJsonObject("keywords") { email.keywords.forEach { put(it, true) } }
                    if (email.fingerprints.isNotEmpty()) {
                        put("fingerprints", JsonArray(email.fingerprints.map(::JsonPrimitive)))
                    }
                    email.signer?.let { put("signer", it) }
                }
            }
        }
    }

    override fun decode(json: Json, arguments: JsonObject): EmailImportResult =
        json.decodeFromJsonElement(EmailImportResult.serializer(), arguments)
}

data class ImportedEmail(
    val blobId: BlobId,
    /**
     * Where it is filed. Never empty, even for a draft: the server requires a mailbox and then
     * files a `$draft` import under the account's Drafts whatever this names.
     */
    val mailboxIds: List<MailboxId>,
    val keywords: Set<String> = emptySet(),
    /** Lower-case hex fingerprints of every key the message is encrypted to. */
    val fingerprints: List<String> = emptyList(),
    /** The signing key's fingerprint; checked by the server only for S/MIME. */
    val signer: String? = null,
)

@Serializable
data class EmailImportResult(
    val accountId: String = "",
    val oldState: String? = null,
    val newState: String = "",
    val created: Map<String, ImportedEmailCreated> = emptyMap(),
    val notCreated: Map<String, SetError> = emptyMap(),
)

@Serializable
data class ImportedEmailCreated(
    val id: String = "",
    val blobId: String? = null,
    val threadId: String? = null,
    val size: Long = 0,
)
