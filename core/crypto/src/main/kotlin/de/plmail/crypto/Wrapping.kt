package de.plmail.crypto

import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * plMail's one wrapping format: `v1.<iv>.<ciphertext>`, AES-256-GCM, the purpose as associated
 * data.
 *
 * Every secret the server holds for a person is in this shape — a user key under the wrap key, an
 * address key's token under a user key, the pairing bundle under the transfer key — and the browser
 * writes all of them (`assets/crypto/aead.js` in the server repository). So this is not a format
 * the app chose; it is one it has to read byte for byte:
 * - both parts are **standard** base64 with padding (the browser's `btoa`), not base64url;
 * - the IV is 12 bytes; the ciphertext carries the 16-byte GCM tag at its end, as WebCrypto writes
 *   it and as `Cipher` expects it;
 * - the associated data is the UTF-8 bytes of the purpose string, so a token can never be opened as
 *   a user key even under the right key.
 *
 * The purpose strings come from the session (`purposes` in `urn:plmail:params:jmap:keys`); the
 * defaults in [Purposes] are what the server sends today and are only used where no session is at
 * hand yet, as during pairing.
 */
object Wrapping {

    private const val VERSION = "v1"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEY_BYTES = 32

    /**
     * Opens [wrapped] with [key] for [purpose], or throws [WrappingException].
     *
     * A wrong key, a wrong purpose and a tampered ciphertext are the same failure — GCM cannot tell
     * them apart and neither can the caller, which is the point of authenticated encryption. The
     * caller decides what a failure means: "wrong password" when trying a derived wrap key, "not
     * this user key" when trying several.
     */
    fun open(key: ByteArray, wrapped: String, purpose: String): ByteArray {
        require(key.size == KEY_BYTES) { "A wrapping key is $KEY_BYTES bytes, not ${key.size}." }

        val parts = wrapped.split('.')
        if (parts.size != 3 || parts[0] != VERSION) {
            throw WrappingException("Not a v1 wrapping.")
        }

        val iv = decode(parts[1])
        val ciphertext = decode(parts[2])
        if (iv.size != IV_BYTES)
            throw WrappingException("The IV is ${iv.size} bytes, not $IV_BYTES.")

        return try {
            cipher(Cipher.DECRYPT_MODE, key, iv, purpose).doFinal(ciphertext)
        } catch (failed: GeneralSecurityException) {
            throw WrappingException("The wrapping does not open with this key.", failed)
        }
    }

    /**
     * Wraps [plaintext] the way the browser does.
     *
     * The app never writes a wrapping the server keeps — keys are made and rewrapped in the web app
     * — so this exists for the tests, which need wrappings this module did not just invent the
     * format of, and for symmetry with the browser should a later phase need it.
     */
    fun seal(
        key: ByteArray,
        plaintext: ByteArray,
        purpose: String,
        random: SecureRandom = SecureRandom(),
    ): String {
        require(key.size == KEY_BYTES) { "A wrapping key is $KEY_BYTES bytes, not ${key.size}." }

        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val ciphertext = cipher(Cipher.ENCRYPT_MODE, key, iv, purpose).doFinal(plaintext)

        return "$VERSION.${ENCODER.encodeToString(iv)}.${ENCODER.encodeToString(ciphertext)}"
    }

    private fun cipher(mode: Int, key: ByteArray, iv: ByteArray, purpose: String): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            updateAAD(purpose.toByteArray(Charsets.UTF_8))
        }

    private fun decode(part: String): ByteArray =
        try {
            DECODER.decode(part)
        } catch (malformed: IllegalArgumentException) {
            throw WrappingException("Not base64.", malformed)
        }

    private val ENCODER = Base64.getEncoder()
    private val DECODER = Base64.getDecoder()
}

/** A wrapping that is malformed, or does not open with the key and purpose it was given. */
class WrappingException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The purpose strings the server uses today, for the moments there is no session to read them from.
 *
 * Read from the session's `urn:plmail:params:jmap:keys` capability wherever one is at hand: the
 * server owns these, and a copy that drifted would turn every wrapping into "wrong key".
 */
object Purposes {
    const val USER_KEY = "plmail/user-key"
    const val ADDRESS_KEY_TOKEN = "plmail/address-key-token"
    const val PAIRING_TRANSFER = "plmail/pairing-transfer"
    const val SMIME_KEY = "plmail/smime-key"
    const val WRAP_KEY_INFO = "plmail/user-key-wrap"
}
