package de.plmail.crypto

import java.text.Normalizer
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The wrap key, from the secret the keyring hangs off: the plMail password, or the encryption
 * passphrase of an account that signs in only through single sign-on.
 *
 * Exactly the browser's derivation (`assets/password_kdf.js` and `assets/crypto/aead.js` in the
 * server repository):
 * ```
 * masterKey = PBKDF2-SHA256(NFC(secret) as UTF-8, salt = base64-decoded, iterations, 32 bytes)
 * wrapKey   = HKDF-SHA256(masterKey, salt = empty, info = "plmail/user-key-wrap", 32 bytes)
 * ```
 *
 * A wrap key that opens none of the active user keys means the secret was wrong. There is no other
 * check: for a passphrase the server holds nothing to compare it with.
 *
 * PBKDF2 is written out over `Mac` rather than taken from `SecretKeyFactory`. The JCA form takes
 * the password as `char[]` and leaves turning it into bytes to the provider — Android's providers
 * and the JDK's have not always agreed on that — and NFC-normalised UTF-8 is the one encoding that
 * has to hold here, because it is what the browser hashed.
 */
object KeyDerivation {

    private const val KEY_BYTES = 32
    private const val HMAC = "HmacSHA256"

    /**
     * The wrap key for [secret] under the keyring's `rootKdf`. Slow on purpose: call it off the
     * main thread.
     */
    fun wrapKey(
        secret: String,
        salt: String,
        iterations: Int,
        info: String = Purposes.WRAP_KEY_INFO,
    ): ByteArray {
        val masterKey = masterKey(secret, salt, iterations)

        return try {
            hkdf(masterKey, info.toByteArray(Charsets.UTF_8))
        } finally {
            masterKey.fill(0)
        }
    }

    /**
     * `PBKDF2-SHA256(NFC(secret), salt, iterations)`. Public for the test vector the server pins.
     */
    fun masterKey(secret: String, salt: String, iterations: Int): ByteArray {
        require(iterations > 0) { "PBKDF2 needs at least one iteration." }

        val password = Normalizer.normalize(secret, Normalizer.Form.NFC).toByteArray(Charsets.UTF_8)

        return try {
            pbkdf2(password, Base64.getDecoder().decode(salt), iterations)
        } finally {
            password.fill(0)
        }
    }

    /** PBKDF2 with HMAC-SHA256 for exactly one output block, which is all a 32-byte key needs. */
    internal fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int): ByteArray {
        val mac = Mac.getInstance(HMAC).apply { init(SecretKeySpec(password, HMAC)) }

        // U1 = HMAC(P, S || INT(1)); Ui = HMAC(P, Ui-1); T = U1 ^ U2 ^ ... ^ Uc
        var u = mac.doFinal(salt + byteArrayOf(0, 0, 0, 1))
        val t = u.copyOf()

        repeat(iterations - 1) {
            u = mac.doFinal(u)
            for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
        }

        return t
    }

    /**
     * HKDF-SHA256 with an empty salt, one 32-byte block of output.
     *
     * RFC 5869 treats a missing salt as a string of HashLen zero bytes, and HMAC pads a short key
     * with zeros anyway, so the browser's empty salt and the zeros used here are the same key —
     * written as zeros because `SecretKeySpec` refuses an empty one.
     */
    internal fun hkdf(ikm: ByteArray, info: ByteArray): ByteArray {
        val extract =
            Mac.getInstance(HMAC).apply { init(SecretKeySpec(ByteArray(KEY_BYTES), HMAC)) }
        val prk = extract.doFinal(ikm)

        val expand = Mac.getInstance(HMAC).apply { init(SecretKeySpec(prk, HMAC)) }
        prk.fill(0)

        return expand.doFinal(info + byteArrayOf(1)).copyOf(KEY_BYTES)
    }
}
