package de.plmail.jmap.methods

import de.plmail.jmap.protocol.JmapMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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
