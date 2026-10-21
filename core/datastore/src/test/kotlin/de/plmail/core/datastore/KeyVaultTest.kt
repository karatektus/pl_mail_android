package de.plmail.core.datastore

import de.plmail.crypto.UserKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * The end-to-end keys on this device.
 *
 * The cipher here is a stand-in that reverses text, so what is pinned is this class's own rules —
 * what is stored, what "cannot be read back" answers, that forgetting forgets. The Keystore itself
 * is covered by the instrumented `KeystoreSecretCipherTest`.
 */
class KeyVaultTest {

    private val store = EmittingDataStore()

    @Test
    fun `stored keys come back as they went in`() = runTest {
        val vault = KeyVault(store, ReversingCipher())
        val keys =
            listOf(UserKey(3, ByteArray(32) { it.toByte() }), UserKey(5, ByteArray(32) { 7 }))

        vault.save(keys)

        assertEquals(keys, vault.userKeys())
        assertTrue(vault.hasKeys.first())
    }

    @Test
    fun `the keys are sealed, not stored as they are`() = runTest {
        val cipher = ReversingCipher()
        val vault = KeyVault(store, cipher)

        vault.save(listOf(UserKey(3, ByteArray(32) { 1 })))

        assertEquals(1, cipher.seals)
    }

    @Test
    fun `keys the keystore can no longer open read as none`() = runTest {
        KeyVault(store, ReversingCipher()).save(listOf(UserKey(3, ByteArray(32))))

        // A restore onto new hardware: the sealed value is there, its key is not.
        val restored = KeyVault(store, ReversingCipher(canOpen = false))

        assertTrue(restored.userKeys().isEmpty())
    }

    @Test
    fun `forgetting removes them`() = runTest {
        val vault = KeyVault(store, ReversingCipher())
        vault.save(listOf(UserKey(3, ByteArray(32))))

        vault.forget()

        assertTrue(vault.userKeys().isEmpty())
        assertFalse(vault.hasKeys.first())
    }

    @Test
    fun `saving no keys is forgetting`() = runTest {
        val vault = KeyVault(store, ReversingCipher())
        vault.save(listOf(UserKey(3, ByteArray(32))))

        vault.save(emptyList())

        assertFalse(vault.hasKeys.first())
    }

    @Test
    fun `a new set replaces the old one rather than joining it`() = runTest {
        val vault = KeyVault(store, ReversingCipher())
        vault.save(listOf(UserKey(3, ByteArray(32) { 1 })))

        vault.save(listOf(UserKey(9, ByteArray(32) { 2 })))

        assertEquals(listOf(9L), vault.userKeys().map { it.id })
    }

    private class ReversingCipher(private val canOpen: Boolean = true) : SecretCipher {
        var seals = 0
            private set

        override fun seal(plaintext: String): SealedSecret {
            seals++
            return SealedSecret("sealed:" + plaintext.reversed())
        }

        override fun open(sealed: SealedSecret): String? =
            sealed.encoded
                .takeIf { canOpen && it.startsWith("sealed:") }
                ?.removePrefix("sealed:")
                ?.reversed()
    }
}
