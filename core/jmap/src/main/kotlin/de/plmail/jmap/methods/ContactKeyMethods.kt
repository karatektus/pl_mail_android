package de.plmail.jmap.methods

import de.plmail.jmap.protocol.AccountId
import de.plmail.jmap.protocol.JmapMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `ContactKey/get` — the OpenPGP keys plMail holds for other people (#9).
 *
 * Per user, so no `accountId` (the server refuses one). There is no filter by address: the whole
 * list comes back, ordered by address and then `current` before `changed`, and the caller picks the
 * sender's out. One row per address per key, so a key with three user IDs is three rows.
 */
class ContactKeyGet : JmapMethod<ContactKeyGetResult> {

    override val name = "ContactKey/get"

    override fun arguments(): JsonObject = buildJsonObject { put("ids", JsonNull) }

    override fun decode(json: Json, arguments: JsonObject): ContactKeyGetResult =
        json.decodeFromJsonElement(ContactKeyGetResult.serializer(), arguments)
}

@Serializable
data class ContactKeyGetResult(
    val state: String = "",
    val list: List<ContactKey> = emptyList(),
    val notFound: List<String> = emptyList(),
) {
    /** The keys held for [email], case-insensitively, `current` first as the server orders them. */
    fun forAddress(email: String): List<ContactKey> = list.filter {
        it.email.equals(email.trim(), ignoreCase = true)
    }
}

@Serializable
data class ContactKey(
    val id: String = "",
    val email: String = "",
    /** Lower-case hex primary fingerprint. */
    val fingerprint: String = "",
    /** Armored. */
    val publicKey: String = "",
    /** `current` — the one in use — or `changed`, waiting to be accepted in the web app. */
    val status: String = "",
    /** Whether the person confirmed this key. Only a pinned key makes a signature `valid`. */
    val pinned: Boolean = false,
    /** `wkd`, `keyserver`, `attached`, `autocrypt` or `manual`. */
    val origin: String? = null,
    val originHost: String? = null,
) {
    val isCurrent: Boolean
        get() = status == "current"
}

/**
 * `ContactKey/lookup` — what plMail knows about the keys of the people a message is going to, the
 * question the composer asks before it decides whether to encrypt (#9, phase 4). The browser asks
 * the same thing (`/contact-keys/recipients`).
 *
 * [lookup] lets the server look for a key it does not have yet, through WKD and the key servers the
 * person allowed. It does that only for an address with no current key, inside the call, bounded to
 * a few seconds, and answers "no key" when the search fails rather than an error. [from] is the
 * address the message is written from; without [accountId] there is no `sender` in the answer.
 */
class ContactKeyLookup(
    private val emails: List<String>,
    private val lookup: Boolean = false,
    private val accountId: AccountId? = null,
    private val from: String? = null,
) : JmapMethod<ContactKeyLookupResult> {

    init {
        require(emails.size <= MAX) { "The server looks up at most $MAX addresses per call." }
    }

    override val name = "ContactKey/lookup"

    override fun arguments(): JsonObject = buildJsonObject {
        put("emails", JsonArray(emails.map { JsonPrimitive(it) }))
        put("lookup", lookup)
        accountId?.let { put("accountId", it.value) }
        from?.let { put("from", it) }
    }

    override fun decode(json: Json, arguments: JsonObject): ContactKeyLookupResult =
        json.decodeFromJsonElement(ContactKeyLookupResult.serializer(), arguments)

    companion object {
        /** `maxContactKeyLookups`; the server cuts the list there before checking the addresses. */
        const val MAX = 50
    }
}

@Serializable
data class ContactKeyLookupResult(
    /** One per valid address asked about, lower-cased. Invalid addresses are dropped silently. */
    val recipients: List<RecipientKey> = emptyList(),
    /** The address the message is written from, and its key. Null without an `accountId`. */
    val sender: LookupSender? = null,
) {
    fun forAddress(email: String): RecipientKey? = recipients.firstOrNull {
        it.email.equals(email.trim(), ignoreCase = true)
    }
}

@Serializable
data class RecipientKey(
    val email: String = "",
    /** One of the person's own addresses: [key] is their own address key. */
    val own: Boolean = false,
    /** The current key for the address, or null when there is none. */
    val key: RecipientPublicKey? = null,
    /** Whether another key for this address is waiting to be accepted in the web app. */
    val changed: Boolean = false,
    /** The person's preference for this contact: `auto`, `always` or `never`. */
    val encrypt: String = "auto",
    val sign: String = "auto",
    /** `pgp-mime` or `pgp-inline`. */
    val scheme: String = "pgp-mime",
) {
    val wantsEncryption: Boolean
        get() = encrypt == "always"

    val refusesEncryption: Boolean
        get() = encrypt == "never"
}

@Serializable
data class RecipientPublicKey(
    /** Lower-case hex primary fingerprint. */
    val fingerprint: String = "",
    /** Armored. */
    val publicKey: String = "",
    val pinned: Boolean = false,
    val origin: String? = null,
    val originHost: String? = null,
)

@Serializable
data class LookupSender(
    val email: String = "",
    /** The address key messages from [email] are signed with, or null when it has none. */
    val fingerprint: String? = null,
    val smime: String? = null,
    val keepsDecrypted: Boolean = false,
)
