package de.plmail.core.datastore

import de.plmail.crypto.UserKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    // ---------------------------------------------- protected keys (phase 5)

    @Test
    fun `protected keys need the person present, and open once they were`() = runTest {
        val gate = LockableCipher()
        val vault = KeyVault(store, ReversingCipher(), gate)
        val keys = listOf(UserKey(3, ByteArray(32) { 4 }))

        vault.save(keys)
        vault.setProtected(true)
        assertTrue(vault.isProtected.first())

        gate.isUnlocked = false
        assertFailsWith<DeviceLockRequired> { vault.userKeys() }
        // Locked is not gone.
        assertTrue(vault.hasKeys.first())

        gate.isUnlocked = true
        assertEquals(keys, vault.userKeys())
    }

    @Test
    fun `switching protection seals the keys under the other key, and back`() = runTest {
        val plain = ReversingCipher()
        val gate = LockableCipher()
        val vault = KeyVault(store, plain, gate)
        val keys = listOf(UserKey(3, ByteArray(32) { 9 }))

        vault.save(keys)
        vault.setProtected(true)
        assertEquals(1, gate.seals)

        vault.setProtected(false)
        assertFalse(vault.isProtected.first())
        assertEquals(2, plain.seals)

        // Opens without the gate again.
        gate.isUnlocked = false
        assertEquals(keys, vault.userKeys())
    }

    @Test
    fun `switching while locked changes nothing`() = runTest {
        val gate = LockableCipher(isUnlocked = false)
        val vault = KeyVault(store, ReversingCipher(), gate)
        val keys = listOf(UserKey(3, ByteArray(32) { 2 }))

        vault.save(keys)

        assertFailsWith<DeviceLockRequired> { vault.setProtected(true) }
        assertFalse(vault.isProtected.first())
        assertEquals(keys, vault.userKeys())
    }

    @Test
    fun `keys that arrive while protection cannot seal are kept unprotected, and it says so`() =
        runTest {
            val gate = LockableCipher()
            val vault = KeyVault(store, ReversingCipher(), gate)

            vault.save(listOf(UserKey(3, ByteArray(32))))
            vault.setProtected(true)

            gate.isUnlocked = false
            val renewed = listOf(UserKey(4, ByteArray(32) { 5 }))
            vault.save(renewed)

            assertFalse(vault.isProtected.first())
            assertEquals(renewed, vault.userKeys())
        }

    @Test
    fun `forgetting forgets the protection too`() = runTest {
        val vault = KeyVault(store, ReversingCipher(), LockableCipher())

        vault.save(listOf(UserKey(3, ByteArray(32))))
        vault.setProtected(true)
        vault.forget()

        assertFalse(vault.isProtected.first())
        assertFalse(vault.hasKeys.first())
    }

    /** The protected key: it seals and opens only while [isUnlocked]. */
    private class LockableCipher(var isUnlocked: Boolean = true) : SecretCipher {
        var seals = 0
            private set

        override fun seal(plaintext: String): SealedSecret {
            if (!isUnlocked) throw DeviceLockRequired()
            seals++
            return SealedSecret("gated:" + plaintext.reversed())
        }

        override fun open(sealed: SealedSecret): String? {
            if (!isUnlocked) throw DeviceLockRequired()
            return sealed.encoded
                .takeIf { it.startsWith("gated:") }
                ?.removePrefix("gated:")
                ?.reversed()
        }
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
