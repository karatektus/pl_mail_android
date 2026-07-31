package de.plmail.core.datastore

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The half of the credential store that cannot run on the JVM.
 *
 * `CredentialStoreTest` covers everything around the cipher with a fake; this covers the cipher
 * itself, because the Android Keystore has no JVM provider and the properties worth checking — that
 * the key is real, that two sealings of one secret differ, that a lost key degrades to null rather
 * than throwing — are exactly the ones a fake cannot demonstrate.
 *
 * JUnit 4, unlike the unit tests: instrumented tests run under `AndroidJUnitRunner`, which is a
 * different runner entirely and does not speak the JUnit Platform.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreSecretCipherTest {

    private val alias = "plmail.test.${System.nanoTime()}"
    private val cipher = KeystoreSecretCipher(alias)
    private val secret = "plmail_" + "3f".repeat(32)

    @After
    fun deleteKey() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    @Test
    fun sealedSecretOpensBackToTheOriginal() {
        assertEquals(secret, cipher.open(cipher.seal(secret)))
    }

    @Test
    fun theCiphertextDoesNotContainThePlaintext() {
        val sealed = cipher.seal(secret)

        assertTrue(
            "the app password survived encryption in readable form",
            !sealed.encoded.contains(secret),
        )
    }

    /**
     * The property that makes GCM safe to reuse under one key.
     *
     * A fresh IV per sealing is what the Keystore provides and what
     * `setRandomizedEncryptionRequired(true)` enforces. Two identical plaintexts producing
     * identical ciphertext would mean the IV was being reused, which forfeits the mode's
     * guarantees.
     */
    @Test
    fun sealingTwiceProducesDifferentCiphertext() {
        assertNotEquals(cipher.seal(secret).encoded, cipher.seal(secret).encoded)
    }

    @Test
    fun aSecondCipherOverTheSameAliasReadsTheFirstsOutput() {
        // The restart case: the key is found rather than regenerated, or every
        // relaunch would silently invalidate the stored credential.
        val sealed = cipher.seal(secret)

        assertEquals(secret, KeystoreSecretCipher(alias).open(sealed))
    }

    @Test
    fun aLostKeyReadsAsNullRatherThanThrowing() {
        val sealed = cipher.seal(secret)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)

        // A new key is generated on demand under the same alias, and it cannot
        // open what the old one sealed. This is the restored-onto-new-hardware
        // path, and it must not crash the app.
        assertNull(KeystoreSecretCipher(alias).open(sealed))
    }

    @Test
    fun aTruncatedValueReadsAsNull() {
        assertNull(cipher.open(SealedSecret("not base64 at all")))
        assertNull(cipher.open(SealedSecret("")))
    }

    @Test
    fun anEmptySecretRoundTrips() {
        assertEquals("", cipher.open(cipher.seal("")))
    }
}
