package de.plmail.jmap.methods

import de.plmail.jmap.protocol.AccountId
import de.plmail.jmap.protocol.JmapMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * `DecryptedMail/pending` — the encrypted messages of an account that keeps decrypted mail on the
 * server, and that no device has decrypted for it yet (#9, phase 3). Newest first.
 *
 * Empty for an account that does not keep decrypted mail: the server decides that per account
 * (`keepsDecryptedMail` in the session), and an empty answer is all a device needs to stop.
 */
class DecryptedMailPending(private val accountId: AccountId, private val limit: Int = 25) :
    JmapMethod<DecryptedMailPendingResult> {

    init {
        require(limit in 1..100) { "The server takes a limit from 1 to 100." }
    }

    override val name = "DecryptedMail/pending"

    override fun arguments(): JsonObject = buildJsonObject {
        put("accountId", accountId.value)
        put("limit", limit)
    }

    override fun decode(json: Json, arguments: JsonObject): DecryptedMailPendingResult =
        json.decodeFromJsonElement(DecryptedMailPendingResult.serializer(), arguments)
}

@Serializable
data class DecryptedMailPendingResult(
    val accountId: String = "",
    val ids: List<String> = emptyList(),
)

/**
 * `DecryptedMail/store` — hands the server the decrypted inner message of each Email, uploaded as a
 * blob, so it can parse and sanitise it and search, rules and parcels work on it. At most fifty per
 * call.
 *
 * Idempotent on the server's side: a message already stored from another device is answered as
 * stored again rather than as an error.
 */
class DecryptedMailStore(
    private val accountId: AccountId,
    private val emails: Map<String, String>,
) : JmapMethod<DecryptedMailStoreResult> {

    init {
        require(emails.size in 1..MAX) { "One call stores 1 to $MAX messages." }
    }

    override val name = "DecryptedMail/store"

    override fun arguments(): JsonObject = buildJsonObject {
        put("accountId", accountId.value)
        putJsonObject("emails") { emails.forEach { (emailId, blobId) -> put(emailId, blobId) } }
    }

    override fun decode(json: Json, arguments: JsonObject): DecryptedMailStoreResult =
        json.decodeFromJsonElement(DecryptedMailStoreResult.serializer(), arguments)

    companion object {
        const val MAX = 50
    }
}

@Serializable
data class DecryptedMailStoreResult(
    val accountId: String = "",
    val stored: List<String> = emptyList(),
    val notStored: Map<String, SetError> = emptyMap(),
)
