package de.plmail.crypto

import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * One of the person's user keys, opened: 32 raw bytes and the id the server files it under.
 *
 * The root of everything this device can decrypt. Address keys hang off it (their tokens are
 * wrapped under it), so holding it is holding the person's mail — which is why it never reaches a
 * log: [toString] names the id and nothing else.
 */
class UserKey(val id: Long, bytes: ByteArray) {

    private val material = bytes.copyOf()

    init {
        require(material.size == KEY_BYTES) {
            "A user key is $KEY_BYTES bytes, not ${material.size}."
        }
    }

    /** A copy, so a caller that zeroes what it was given cannot zero the key for everybody else. */
    val bytes: ByteArray
        get() = material.copyOf()

    override fun equals(other: Any?): Boolean =
        other is UserKey && other.id == id && other.material.contentEquals(material)

    override fun hashCode(): Int = 31 * id.hashCode() + material.contentHashCode()

    override fun toString(): String = "UserKey(id=$id)"

    companion object {
        const val KEY_BYTES = 32
    }
}

/** A user key as `Keyring/get` lists it: still wrapped, and whether it is the one in use. */
data class WrappedUserKey(val id: Long, val wrapped: String, val isActive: Boolean)

object UserKeys {

    /**
     * Opens every active user key with [wrapKey], skipping the ones it does not open.
     *
     * An empty answer is the whole of "wrong password" (or passphrase): the server keeps nothing to
     * compare a secret with, so a wrap key that opens none of them is the only evidence there is.
     * Locked keys — retired by a reset, kept only so older wrappings can be told apart — are never
     * tried, as in the browser.
     */
    fun open(
        wrapKey: ByteArray,
        keys: List<WrappedUserKey>,
        purpose: String = Purposes.USER_KEY,
    ): List<UserKey> =
        keys
            .filter { it.isActive }
            .mapNotNull { key ->
                try {
                    val bytes = Wrapping.open(wrapKey, key.wrapped, purpose)
                    try {
                        UserKey(key.id, bytes)
                    } finally {
                        bytes.fill(0)
                    }
                } catch (wrongKey: WrappingException) {
                    null
                }
            }
}

/**
 * The keys a browser hands over through the pairing QR code.
 *
 * The browser seals `{"v":1,"userKeys":[{"id":3,"key":"<base64, 32 bytes>"}]}` under a random
 * transfer key (`assets/crypto/pairing.js`); the transfer key travels only in the URI fragment and
 * the sealed bundle only through the server, so neither side alone holds the keys. The bundle is
 * the user keys the browser had open — active ones only.
 */
object PairingBundle {

    private const val VERSION = 1

    /** Opens the `keys` the redeem answer carried, or throws [WrappingException]. */
    fun open(
        transferKey: ByteArray,
        keys: String,
        purpose: String = Purposes.PAIRING_TRANSFER,
    ): List<UserKey> {
        val plaintext = Wrapping.open(transferKey, keys, purpose)

        val bundle =
            try {
                JSON.decodeFromString(Bundle.serializer(), plaintext.toString(Charsets.UTF_8))
            } catch (malformed: SerializationException) {
                throw WrappingException("The pairing bundle is not the expected JSON.", malformed)
            } catch (malformed: IllegalArgumentException) {
                throw WrappingException("The pairing bundle is not the expected JSON.", malformed)
            } finally {
                plaintext.fill(0)
            }

        if (bundle.version != VERSION) {
            throw WrappingException(
                "Pairing bundle version ${bundle.version} is not one this app reads."
            )
        }

        return bundle.userKeys.map { entry ->
            val bytes =
                try {
                    Base64.getDecoder().decode(entry.key)
                } catch (malformed: IllegalArgumentException) {
                    throw WrappingException(
                        "A user key in the pairing bundle is not base64.",
                        malformed,
                    )
                }
            try {
                UserKey(entry.id, bytes)
            } catch (wrongSize: IllegalArgumentException) {
                throw WrappingException(
                    "A user key in the pairing bundle has the wrong size.",
                    wrongSize,
                )
            } finally {
                bytes.fill(0)
            }
        }
    }

    /**
     * The transfer key from the URI fragment: base64url without padding, 32 bytes.
     *
     * Null for anything else, which pairs the device without keys rather than failing the pairing:
     * the app password is the thing being paired, and the keys can still come later.
     */
    fun transferKey(fragmentValue: String): ByteArray? =
        try {
            Base64.getUrlDecoder().decode(fragmentValue).takeIf { it.size == UserKey.KEY_BYTES }
        } catch (malformed: IllegalArgumentException) {
            null
        }

    @Serializable
    private data class Bundle(
        @SerialName("v") val version: Int,
        val userKeys: List<Entry> = emptyList(),
    )

    @Serializable private data class Entry(val id: Long, val key: String)

    private val JSON = Json { ignoreUnknownKeys = true }
}
