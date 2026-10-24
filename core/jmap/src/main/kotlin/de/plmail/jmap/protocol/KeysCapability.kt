package de.plmail.jmap.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The session's `urn:plmail:params:jmap:keys` object: what an app needs to open the keys the
 * browser wrapped.
 *
 * The server owns these strings and the app reads them rather than copying them, because a purpose
 * that drifted by one character would turn every wrapping into "wrong key" with nothing to say why.
 * The defaults are what the server sends today, for a capability object that leaves a field out.
 *
 * Not to be confused with the `kdf` inside `Keyring/get`, which has the same name and holds the
 * login's salt and iterations; this one names the algorithm and the HKDF info string.
 */
data class KeysCapability(
    /** HKDF info for the wrap key: `plmail/user-key-wrap`. */
    val wrapKeyInfo: String = "plmail/user-key-wrap",
    val purposes: KeyPurposes = KeyPurposes(),
    /** Whether the installation allows any account to keep decrypted copies at all. */
    val decryptedMailAllowed: Boolean = false,
    /** The largest decrypted message `DecryptedMail/store` accepts, in bytes. */
    val maxDecryptedMailSize: Long = 0,
    /** How many addresses one `ContactKey/lookup` may ask about. */
    val maxContactKeyLookups: Int = 0,
) {
    companion object {
        fun from(json: JsonObject): KeysCapability {
            val defaults = KeysCapability()
            val kdf = json["kdf"] as? JsonObject
            val purposes = json["purposes"] as? JsonObject

            return KeysCapability(
                wrapKeyInfo = kdf?.get("wrapKeyInfo")?.text() ?: defaults.wrapKeyInfo,
                purposes = purposes?.let(KeyPurposes::from) ?: defaults.purposes,
                decryptedMailAllowed =
                    json["decryptedMailAllowed"]?.text()?.toBooleanStrictOrNull() ?: false,
                maxDecryptedMailSize = json["maxDecryptedMailSize"]?.text()?.toLongOrNull() ?: 0,
                maxContactKeyLookups = json["maxContactKeyLookups"]?.text()?.toIntOrNull() ?: 0,
            )
        }
    }
}

/** The associated data of each kind of wrapping. */
data class KeyPurposes(
    val userKey: String = "plmail/user-key",
    val addressKeyToken: String = "plmail/address-key-token",
    val pairingTransfer: String = "plmail/pairing-transfer",
    val smimeKey: String = "plmail/smime-key",
) {
    companion object {
        fun from(json: JsonObject): KeyPurposes {
            val defaults = KeyPurposes()

            return KeyPurposes(
                userKey = json["userKey"]?.text() ?: defaults.userKey,
                addressKeyToken = json["addressKeyToken"]?.text() ?: defaults.addressKeyToken,
                pairingTransfer = json["pairingTransfer"]?.text() ?: defaults.pairingTransfer,
                smimeKey = json["smimeKey"]?.text() ?: defaults.smimeKey,
            )
        }
    }
}

/** A string value, or null for anything else — `JsonNull` included, whose content is "null". */
private fun JsonElement.text(): String? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.takeIf { it.isNotBlank() }
