package de.plmail.crypto

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * The keys a browser made, opened here.
 *
 * Nothing in this file was produced by this module. The KDF vector is the server's own
 * (`tests/Security/PasswordKdfTest.php`, computed independently in Python there); the fixtures in
 * `resources/crypto` were written with Python's `cryptography` for the AES-GCM wrappings and with
 * the server's vendored openpgp.js 6.3.2 for the address key — the same library and the same
 * `generateKey({passphrase: token})` call the web app makes. A test that sealed with
 * [Wrapping.seal] and opened with [Wrapping.open] would pass with both halves equally wrong.
 */
class KeyringInteropTest {

    // ------------------------------------------------------------- derivation

    @Test
    fun `the master key matches the server's vector`() {
        val masterKey = KeyDerivation.masterKey(PASSWORD, SALT, 1000)

        assertEquals("+4vJIMKAlIPqwJ+COWdFg7Dt05LxeOyPkbTxxHIw8tA=", masterKey.base64())
        // The server pins the login hash, one PBKDF2 round over the master key; reproducing it is
        // what ties this master key to the value the server actually checks.
        assertEquals(
            "RhhWu3KBQElRsV5ner2qXtn5TAwdYoWNjl2lwSpp9Ik=",
            KeyDerivation.pbkdf2(masterKey, PASSWORD.toByteArray(Charsets.UTF_8), 1).base64(),
        )
    }

    @Test
    fun `the wrap key is HKDF of the master key with the browser's info string`() {
        assertEquals(
            "mxlH3Su8Xf0brhQvxCochhuq3Q6htzrCj6OUo10gKtU=",
            KeyDerivation.wrapKey(PASSWORD, SALT, 1000).base64(),
        )
    }

    /** "ü" typed as one code point or as u + combining diaeresis is the same password. */
    @Test
    fun `a decomposed password derives the same key`() {
        val decomposed = "correct horse ü battery"

        assertContentEquals(
            KeyDerivation.wrapKey(PASSWORD, SALT, 1000),
            KeyDerivation.wrapKey(decomposed, SALT, 1000),
        )
    }

    // -------------------------------------------------------------- user keys

    @Test
    fun `the right password opens the active user key and skips the locked one`() {
        val opened = UserKeys.open(KeyDerivation.wrapKey(PASSWORD, SALT, 1000), wrappedUserKeys())

        assertEquals(listOf(3L), opened.map { it.id })
        assertContentEquals(expectedUserKey(), opened.single().bytes)
    }

    @Test
    fun `a wrong password opens nothing`() {
        val opened =
            UserKeys.open(
                KeyDerivation.wrapKey("correct horse u battery", SALT, 1000),
                wrappedUserKeys(),
            )

        assertTrue(opened.isEmpty())
    }

    // ---------------------------------------------------------------- pairing

    @Test
    fun `the pairing bundle opens with the key from the fragment`() {
        val transferKey = PairingBundle.transferKey(pairing["fragment"].text())!!

        val keys = PairingBundle.open(transferKey, pairing["keys"].text())

        assertEquals(listOf(3L), keys.map { it.id })
        assertContentEquals(expectedUserKey(), keys.single().bytes)
    }

    @Test
    fun `a fragment that is not 32 bytes of base64url is no transfer key`() {
        assertNull(PairingBundle.transferKey("too-short"))
        assertNull(PairingBundle.transferKey("not base64 at all!"))
        // Standard base64 with padding is not what the browser writes either.
        assertNull(
            PairingBundle.transferKey(Base64.getEncoder().encodeToString(ByteArray(32)) + "==")
        )
    }

    @Test
    fun `the bundle does not open as anything but a pairing transfer`() {
        val transferKey = PairingBundle.transferKey(pairing["fragment"].text())!!

        assertFailsWith<WrappingException> {
            PairingBundle.open(transferKey, pairing["keys"].text(), purpose = Purposes.USER_KEY)
        }
    }

    // ----------------------------------------------------------- address keys

    @Test
    fun `the user key opens the token and the token unlocks the openpgp-js key`() {
        val userKey = UserKey(3, expectedUserKey())
        val entry = addressKey()

        val token = AddressKeys.token(userKey, entry["token"].text())
        val unlocked = AddressKeys.unlock(entry["privateKey"].text(), token)

        assertEquals(addressKeySource["fingerprint"].text(), unlocked.fingerprint)
        assertEquals(entry["fingerprint"].text(), unlocked.fingerprint)
    }

    @Test
    fun `a wrong token does not unlock the address key`() {
        assertFailsWith<AddressKeyException> {
            AddressKeys.unlock(addressKey()["privateKey"].text(), "bm90IHRoZSB0b2tlbg==")
        }
    }

    @Test
    fun `a token does not open under a user key it does not hang off`() {
        assertFailsWith<WrappingException> {
            AddressKeys.token(UserKey(3, ByteArray(32)), addressKey()["token"].text())
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun keyring(): JsonObject = keyringGet["list"]!!.jsonArray.single().jsonObject

    private fun wrappedUserKeys(): List<WrappedUserKey> =
        keyring()["userKeys"]!!.jsonArray.map {
            val key = it.jsonObject
            WrappedUserKey(
                id = key["id"]!!.jsonPrimitive.long,
                wrapped = key["wrapped"].text(),
                isActive = key["status"].text() == "active",
            )
        }

    private fun addressKey(): JsonObject = keyring()["addressKeys"]!!.jsonArray.single().jsonObject

    private fun expectedUserKey(): ByteArray = Base64.getDecoder().decode(pairing["userKey"].text())

    private fun kotlinx.serialization.json.JsonElement?.text(): String =
        this!!.jsonPrimitive.content

    private fun ByteArray.base64(): String = Base64.getEncoder().encodeToString(this)

    private companion object {
        const val PASSWORD = "correct horse ü battery"
        const val SALT = "c2FsdHNhbHRzYWx0c2FsdA=="

        fun resource(name: String): JsonObject =
            Json.parseToJsonElement(
                    KeyringInteropTest::class.java.getResource("/crypto/$name")!!.readText()
                )
                .jsonObject

        val keyringGet = resource("keyring-get.json")
        val pairing = resource("pairing.json")
        val addressKeySource = resource("addresskey.json")

        init {
            // The fixture's KDF parameters are the vector's, so the two halves of this file test
            // the same derivation.
            check(
                keyringGet["list"]!!
                    .jsonArray
                    .single()
                    .jsonObject["rootKdf"]!!
                    .jsonObject["iterations"]!!
                    .jsonPrimitive
                    .int == 1000
            )
        }
    }
}
