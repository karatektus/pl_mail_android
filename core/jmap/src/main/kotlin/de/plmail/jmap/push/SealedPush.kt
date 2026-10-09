package de.plmail.jmap.push

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The key this device hands the server so that a push with content in it can be sealed to it.
 *
 * ## Why the app has one at all
 *
 * A state change says only that a token moved, and travels through Firebase as readable JSON
 * because there is nothing in it to read. A calendar reminder carries the event's title, and
 * Firebase is Google. The server therefore sends nothing with content in it through FCM unless it
 * can seal it first, to a key the device registered — exactly what a browser does for Web Push, and
 * deliberately the same scheme (RFC 8291) rather than one invented for this, so the sealing on the
 * server is the library that already does it for browsers and this side has a published vector to
 * check itself against.
 *
 * ## What the three parts are
 * - [privateScalar] — the P-256 private key, 32 bytes. Never leaves the device.
 * - [publicPoint] — its public half as the 65-byte uncompressed point. What a browser calls
 *   `p256dh`.
 * - [auth] — 16 random bytes mixed into the key derivation. What a browser calls `auth`.
 *
 * The private key is held as its raw scalar rather than as a platform key object because it has to
 * be stored, and the scalar is the form that needs no provider to read back.
 *
 * ## Why not the Android Keystore
 *
 * A keystore-bound key cannot be exported, which is the point of one, and ECDH with such a key
 * depends on what the device's hardware implements. This module also has no Android in it, on
 * purpose. The scalar is stored sealed under the keystore's AES key instead, the same way the
 * server password is (`PushKeyStore`), which protects it at rest without making push depend on a
 * hardware feature nobody here can test across devices.
 */
class PushKeys(val privateScalar: ByteArray, val publicPoint: ByteArray, val auth: ByteArray) {

    init {
        require(privateScalar.size == SCALAR_BYTES) { "A P-256 private key is 32 bytes." }
        require(publicPoint.size == POINT_BYTES && publicPoint[0] == UNCOMPRESSED) {
            "The public key must be the 65-byte uncompressed point."
        }
        require(auth.size == AUTH_BYTES) { "The auth secret is 16 bytes." }
    }

    /** As the server wants it in `keys.p256dh`: base64url, no padding. */
    val p256dh: String
        get() = ENCODER.encodeToString(publicPoint)

    /** As the server wants it in `keys.auth`. */
    val authSecret: String
        get() = ENCODER.encodeToString(auth)

    /** Never the key material: this ends up in logs and crash reports. */
    override fun toString(): String = "PushKeys(…)"

    companion object {
        const val SCALAR_BYTES = 32
        const val POINT_BYTES = 65
        const val AUTH_BYTES = 16
        const val UNCOMPRESSED: Byte = 0x04

        private val ENCODER = Base64.getUrlEncoder().withoutPadding()

        fun generate(random: SecureRandom = SecureRandom()): PushKeys {
            val pair =
                KeyPairGenerator.getInstance("EC")
                    .apply { initialize(ECGenParameterSpec(CURVE), random) }
                    .generateKeyPair()

            return PushKeys(
                privateScalar = fixed((pair.private as ECPrivateKey).s, SCALAR_BYTES),
                publicPoint = encode((pair.public as ECPublicKey).w),
                auth = ByteArray(AUTH_BYTES).also(random::nextBytes),
            )
        }

        internal const val CURVE = "secp256r1"

        internal fun encode(point: ECPoint): ByteArray =
            byteArrayOf(UNCOMPRESSED) +
                fixed(point.affineX, SCALAR_BYTES) +
                fixed(point.affineY, SCALAR_BYTES)

        /**
         * A big integer as exactly [size] bytes.
         *
         * `toByteArray()` is as long as the number needs: 33 bytes when the top bit is set (a sign
         * byte is added) and fewer than 32 when the value happens to start with zeros. Both occur
         * about once in every few hundred keys, which is how a key that "usually works" is made.
         */
        internal fun fixed(value: BigInteger, size: Int): ByteArray {
            val raw = value.toByteArray()

            return when {
                raw.size == size -> raw
                raw.size > size -> raw.copyOfRange(raw.size - size, raw.size)
                else -> ByteArray(size - raw.size) + raw
            }
        }
    }
}

/**
 * A sealed push could not be opened. The message says which step failed, never any key material.
 */
class SealedPushException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Opens a push the server sealed to this device's [PushKeys].
 *
 * What arrives is, byte for byte, the body a Web Push POST carries with `Content-Encoding:
 * aes128gcm` — the server base64url-encodes it into the FCM data key `encrypted`, because an FCM
 * data field holds a string.
 *
 * ```
 * salt (16) | record size (4) | key length (1) | sender's public key (65) | ciphertext + tag
 * ```
 *
 * The steps are RFC 8291 §3.4, in order: ECDH between this device's private key and the sender's
 * public key from the header; one HKDF round keyed with the auth secret, whose `info` names both
 * public keys so the result is bound to this pair and no other; a second round keyed with the salt,
 * giving the AES key and the nonce; AES-128-GCM; and the padding delimiter taken off the end.
 *
 * Written against `javax.crypto` alone. It is forty lines of arithmetic the platform already has
 * every piece of, and a dependency whose job is exactly this would still have to be handed the key
 * in a form this module would then be shaped around.
 *
 * `SealedPushTest` holds it to a payload sealed by the server's own code, which is the only test
 * that proves the two ends agree.
 */
object SealedPush {

    private const val SALT_BYTES = 16
    private const val HEADER_BYTES = SALT_BYTES + 4 + 1
    private const val TAG_BITS = 128
    private const val KEY_BYTES = 16
    private const val NONCE_BYTES = 12

    /** The delimiter RFC 8188 puts after the plaintext of the last record. */
    private const val LAST_RECORD: Byte = 0x02

    private val DECODER = Base64.getUrlDecoder()

    /** The base64url form, as it arrives in the FCM data map. */
    fun open(encoded: String, keys: PushKeys): ByteArray {
        val body =
            try {
                DECODER.decode(encoded.trim())
            } catch (malformed: IllegalArgumentException) {
                throw SealedPushException("The sealed push is not base64url.", malformed)
            }

        return open(body, keys)
    }

    fun open(body: ByteArray, keys: PushKeys): ByteArray {
        if (body.size < HEADER_BYTES) throw SealedPushException("The sealed push is too short.")

        val salt = body.copyOfRange(0, SALT_BYTES)
        val keyLength = body[HEADER_BYTES - 1].toInt() and 0xff
        val recordStart = HEADER_BYTES + keyLength

        if (keyLength != PushKeys.POINT_BYTES || body.size <= recordStart) {
            throw SealedPushException("The sealed push has no sender key in its header.")
        }

        val senderPoint = body.copyOfRange(HEADER_BYTES, recordStart)
        val record = body.copyOfRange(recordStart, body.size)

        try {
            val shared = agree(keys.privateScalar, senderPoint)

            // Bound to both public keys: a body sealed to somebody else's key
            // derives a different secret here and fails the tag below.
            val ikm =
                hkdf(
                    salt = keys.auth,
                    input = shared,
                    info = "WebPush: info".encodeToByteArray() + 0 + keys.publicPoint + senderPoint,
                    length = 32,
                )

            val key =
                hkdf(salt, ikm, "Content-Encoding: aes128gcm".encodeToByteArray() + 0, KEY_BYTES)
            val nonce =
                hkdf(salt, ikm, "Content-Encoding: nonce".encodeToByteArray() + 0, NONCE_BYTES)

            val plain =
                Cipher.getInstance("AES/GCM/NoPadding")
                    .apply {
                        init(
                            Cipher.DECRYPT_MODE,
                            SecretKeySpec(key, "AES"),
                            GCMParameterSpec(TAG_BITS, nonce),
                        )
                    }
                    .doFinal(record)

            return unpad(plain)
        } catch (failed: GeneralSecurityException) {
            // A wrong key and a tampered body both end here, as a tag that does
            // not verify. Which of the two it was is not knowable and not said.
            throw SealedPushException(
                "The sealed push did not open with this device's key.",
                failed,
            )
        }
    }

    /** The plaintext, then `0x02`, then any number of zero bytes. */
    private fun unpad(plain: ByteArray): ByteArray {
        val end = plain.indexOfLast { it != 0.toByte() }

        if (end < 0 || plain[end] != LAST_RECORD) {
            throw SealedPushException("The sealed push is not a single final record.")
        }

        return plain.copyOfRange(0, end)
    }

    private fun agree(privateScalar: ByteArray, senderPoint: ByteArray): ByteArray {
        if (senderPoint[0] != PushKeys.UNCOMPRESSED) {
            throw SealedPushException("The sender's key is not an uncompressed point.")
        }

        val curve = curve()
        val factory = KeyFactory.getInstance("EC")

        val x = BigInteger(1, senderPoint.copyOfRange(1, 1 + PushKeys.SCALAR_BYTES))
        val y = BigInteger(1, senderPoint.copyOfRange(1 + PushKeys.SCALAR_BYTES, senderPoint.size))

        return KeyAgreement.getInstance("ECDH")
            .apply {
                init(factory.generatePrivate(ECPrivateKeySpec(BigInteger(1, privateScalar), curve)))
                // generatePublic rejects a point that is not on the curve, which
                // is the check that matters when the point came off the network.
                doPhase(factory.generatePublic(ECPublicKeySpec(ECPoint(x, y), curve)), true)
            }
            .generateSecret()
    }

    private fun curve(): ECParameterSpec =
        AlgorithmParameters.getInstance("EC")
            .apply { init(ECGenParameterSpec(PushKeys.CURVE)) }
            .getParameterSpec(ECParameterSpec::class.java)

    /**
     * HKDF-SHA-256 (RFC 5869) for outputs of at most one hash length, which is every output here.
     *
     * Extract, then a single expand block: `HMAC(prk, info | 0x01)`, truncated.
     */
    private fun hkdf(salt: ByteArray, input: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(salt, input)

        return hmac(prk, info + 1).copyOf(length)
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)
}
