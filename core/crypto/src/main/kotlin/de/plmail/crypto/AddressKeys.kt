package de.plmail.crypto

import java.io.IOException
import org.bouncycastle.openpgp.PGPException
import org.bouncycastle.openpgp.api.OpenPGPKey
import org.pgpainless.PGPainless
import org.pgpainless.key.protection.SecretKeyRingProtector
import org.pgpainless.util.Passphrase

/**
 * An address key, unlocked: the OpenPGP key a person signs with and decrypts with for one or more
 * of their addresses.
 *
 * The private key arrives from `Keyring/get` armored and passphrase-protected. The passphrase is
 * the address key's **token** — 32 random bytes the browser wrote as standard base64 text and used
 * *as that text*, not decoded — and the token is wrapped under one of the person's user keys
 * (`assets/crypto/keyring.js`). So a device that holds the user key can open every address key
 * hanging off it, including ones made after it was paired, without asking anybody for anything.
 *
 * The key itself stays protected; what is kept is the key and a [protector] that knows the token,
 * so it is unlocked for the moment an operation needs it and not for the life of the object.
 */
class UnlockedAddressKey
internal constructor(
    val fingerprint: String,
    val key: OpenPGPKey,
    private val token: Passphrase,
) {
    /** Hands the token to PGPainless for a decryption or a signature. */
    val protector: SecretKeyRingProtector
        get() = SecretKeyRingProtector.unlockEachKeyWith(token, key)

    override fun toString(): String = "UnlockedAddressKey($fingerprint)"
}

/** An address key that could not be read, or whose token does not unlock it. */
class AddressKeyException(message: String, cause: Throwable? = null) : Exception(message, cause)

object AddressKeys {

    /** Opens an address key's wrapped token with the user key it hangs off. */
    fun token(
        userKey: UserKey,
        wrappedToken: String,
        purpose: String = Purposes.ADDRESS_KEY_TOKEN,
    ): String {
        val key = userKey.bytes

        return try {
            val plaintext = Wrapping.open(key, wrappedToken, purpose)
            try {
                plaintext.toString(Charsets.UTF_8)
            } finally {
                plaintext.fill(0)
            }
        } finally {
            key.fill(0)
        }
    }

    /**
     * Reads [armoredPrivateKey] and proves [token] unlocks every secret key in it.
     *
     * Every one, not only the primary: openpgp.js protects the signing primary and the encryption
     * subkey with the same passphrase, and a key whose subkey stayed locked would fail later, at
     * the first encrypted mail, with nothing left to say which address it was.
     */
    fun unlock(armoredPrivateKey: String, token: String): UnlockedAddressKey {
        val key =
            try {
                PGPainless.getInstance().readKey().parseKey(armoredPrivateKey)
            } catch (unreadable: IOException) {
                throw AddressKeyException(
                    "The address key is not a readable OpenPGP key.",
                    unreadable,
                )
            }

        val chars = token.toCharArray()
        try {
            key.secretKeys.values.forEach { secret ->
                if (secret.isLocked) secret.unlock(chars)
            }
        } catch (wrongToken: PGPException) {
            throw AddressKeyException("The token does not unlock this address key.", wrongToken)
        } finally {
            chars.fill(' ')
        }

        return UnlockedAddressKey(
            fingerprint = key.primaryKey.pgpPublicKey.fingerprint.toHex(),
            key = key,
            token = Passphrase.fromPassword(token),
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
