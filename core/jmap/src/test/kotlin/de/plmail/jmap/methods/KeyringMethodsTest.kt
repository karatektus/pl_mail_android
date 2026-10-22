package de.plmail.jmap.methods

import de.plmail.jmap.protocol.AccountId
import de.plmail.jmap.protocol.Capability
import de.plmail.jmap.protocol.MethodResults
import de.plmail.jmap.protocol.Session
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonObject

/**
 * The end-to-end encryption surface of the session and `Keyring/get` (#9).
 *
 * The shapes are the server's (`SessionBuilder::keysCapabilities`, `KeyringView::build` on
 * `milestone/v0.4.0`). Two traps are pinned here: the session's `kdf` and the keyring's `kdf` are
 * different objects under one name, and "no keyring" is never an error but a shape.
 */
class KeyringMethodsTest {

    private val json = MethodResults.JMAP_JSON

    @Test
    fun `the session names the wrap key info and every purpose`() {
        val session = session(capability = KEYS)

        val keys = session.keys!!

        assertEquals("plmail/user-key-wrap", keys.wrapKeyInfo)
        assertEquals("plmail/user-key", keys.purposes.userKey)
        assertEquals("plmail/address-key-token", keys.purposes.addressKeyToken)
        assertEquals("plmail/pairing-transfer", keys.purposes.pairingTransfer)
        assertEquals(50, keys.maxContactKeyLookups)
        assertTrue(keys.decryptedMailAllowed)
    }

    @Test
    fun `an instance without the capability has no keys`() {
        assertNull(session(capability = null).keys)
    }

    @Test
    fun `whether an account keeps decrypted mail is read per account, false when unsaid`() {
        val session = session(capability = KEYS, keepsDecryptedMail = true)

        assertTrue(session.keepsDecryptedMail(AccountId("a1")))
        assertFalse(session(capability = KEYS).keepsDecryptedMail(AccountId("a1")))
        assertFalse(session.keepsDecryptedMail(AccountId("unknown")))
    }

    @Test
    fun `keyring get is a singleton with no account id`() {
        val arguments = KeyringGet().arguments()

        assertFalse("accountId" in arguments)
        assertTrue("ids" in arguments)
        assertEquals(listOf("urn:ietf:params:jmap:core", Capability.KEYS), Capability.USING_KEYS)
    }

    @Test
    fun `a set-up keyring decodes with its user and address keys`() {
        val keyring = decode(SET_UP).keyring!!

        assertTrue(keyring.isSetUp)
        assertFalse(keyring.isPassphrase)
        assertEquals(1000, keyring.rootKdf!!.iterations)
        assertEquals(listOf(3L), keyring.userKeys.filter { it.isActive }.map { it.id })
        val address = keyring.addressKeys.single()
        assertEquals(3L, address.userKeyId)
        assertEquals("ada@example.org", address.addresses.single { it.primary }.email)
    }

    @Test
    fun `a password account still hashing its password itself has no keyring yet`() {
        val keyring =
            decode(
                    """{"list":[{"id":"singleton","root":"password","rootKdf":null,"kdf":null,"userKeys":[],"addressKeys":[]}]}"""
                )
                .keyring!!

        assertFalse(keyring.isSetUp)
    }

    @Test
    fun `a passphrase account that never chose one has a null salt, not an error`() {
        val keyring =
            decode(
                    """{"list":[{"id":"singleton","root":"passphrase","rootKdf":{"salt":null,"iterations":600000},"userKeys":[],"addressKeys":[]}]}"""
                )
                .keyring!!

        assertTrue(keyring.isPassphrase)
        assertFalse(keyring.isSetUp)
    }

    @Test
    fun `a demo installation is never set up`() {
        val keyring = decode(SET_UP.replace("\"enabled\":true", "\"enabled\":false")).keyring!!

        assertFalse(keyring.isSetUp)
    }

    private fun decode(text: String): KeyringGetResult =
        KeyringGet().decode(json, json.parseToJsonElement(text).jsonObject)

    private fun session(capability: String?, keepsDecryptedMail: Boolean? = null): Session {
        val accountKeys =
            keepsDecryptedMail
                ?.let { """, "${Capability.KEYS}": {"keepsDecryptedMail": $it}""" }
                .orEmpty()
        val top = capability?.let { """, "${Capability.KEYS}": $it""" }.orEmpty()

        return json.decodeFromString(
            Session.serializer(),
            """
            {
              "capabilities": {"urn:ietf:params:jmap:core": {}$top},
              "accounts": {"a1": {"name": "a@example.org", "isPersonal": true, "isReadOnly": false,
                "accountCapabilities": {"urn:ietf:params:jmap:mail": {}$accountKeys}}},
              "primaryAccounts": {}, "username": "a@example.org",
              "apiUrl": "https://x/jmap/api", "downloadUrl": "https://x/d", "uploadUrl": "https://x/u",
              "state": "s"
            }
            """
                .trimIndent(),
        )
    }

    private companion object {
        // SessionBuilder::keysCapabilities, verbatim in shape.
        const val KEYS =
            """{"kdf":{"algorithm":"PBKDF2-SHA256","wrapKeyInfo":"plmail/user-key-wrap"},
            "purposes":{"userKey":"plmail/user-key","addressKeyToken":"plmail/address-key-token",
            "pairingTransfer":"plmail/pairing-transfer","smimeKey":"plmail/smime-key"},
            "decryptedMailAllowed":true,"maxDecryptedMailSize":50331648,"maxContactKeyLookups":50}"""

        const val SET_UP =
            """{"state":"0123","notFound":[],"list":[{"id":"singleton","enabled":true,
            "kdf":{"salt":"c2FsdHNhbHRzYWx0c2FsdA==","iterations":1000},"root":"password",
            "rootKdf":{"salt":"c2FsdHNhbHRzYWx0c2FsdA==","iterations":1000},
            "userKeys":[{"id":3,"wrapped":"v1.aXY=.Y3Q=","status":"active"},
                        {"id":4,"wrapped":"v1.aXY=.Y3Q=","status":"locked"}],
            "addressKeys":[{"id":7,"userKeyId":3,"fingerprint":"ed07","publicKey":"-","privateKey":"-",
              "token":"v1.aXY=.Y3Q=","status":"active","addresses":[{"email":"ada@example.org","primary":true}],
              "publications":[]}],
            "publishTo":[],"smimeCertificates":[],"wkd":[]}]}"""
    }
}
