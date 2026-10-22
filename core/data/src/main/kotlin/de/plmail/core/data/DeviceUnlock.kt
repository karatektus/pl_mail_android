package de.plmail.core.data

import android.content.Context
import android.hardware.biometrics.BiometricManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Asking the person to confirm it is them — fingerprint, face or screen lock — for protected
 * end-to-end keys (#9, phase 5).
 *
 * The prompt needs an activity on screen, and the code that finds the keys locked does not have
 * one: it is a repository, run from a reader, a composer or a worker. So the activity [attach]es
 * itself while it is started, and [confirm] asks through it. With nothing attached — the app in the
 * background, a worker with the screen off — [confirm] answers false straight away, and the work
 * that needed the keys waits for the next time the app is open. Nothing here ever shows a prompt
 * the person did not cause by opening something encrypted.
 */
@Singleton
class DeviceUnlock @Inject constructor(@param:ApplicationContext private val context: Context) :
    UnlockPrompt {

    private var host: (suspend () -> Boolean)? = null
    private val asking = Mutex()
    private var confirmedAt = 0L

    /**
     * Whether this device can ask at all: it has a screen lock, or a strong biometric enrolled with
     * one. Without either there is nothing to gate a key on, and the setting is not offered.
     */
    val isAvailable: Boolean
        get() =
            context
                .getSystemService(BiometricManager::class.java)
                ?.canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    /**
     * Makes [prompt] the way to ask, until the returned function is called. [prompt] shows the
     * system's prompt and answers whether the person confirmed.
     */
    fun attach(prompt: suspend () -> Boolean): () -> Unit {
        host = prompt

        return { if (host === prompt) host = null }
    }

    /**
     * Asks, once, however many callers found the keys locked at the same moment — a conversation of
     * three encrypted messages opening together is one prompt. False when nothing can ask or the
     * person declined.
     */
    override suspend fun confirm(): Boolean = asking.withLock {
        // Somebody else's prompt has just been answered: the key is open for
        // the next few minutes, and asking again would be asking twice.
        if (System.currentTimeMillis() - confirmedAt < JUST_NOW_MILLIS) return@withLock true

        val confirmed = host?.invoke() == true
        if (confirmed) confirmedAt = System.currentTimeMillis()

        confirmed
    }

    companion object {
        /** What the protected key accepts, and so what the prompt offers. */
        const val AUTHENTICATORS =
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL

        private const val JUST_NOW_MILLIS = 10_000L
    }
}

/** What [EncryptionRepository] asks through; [DeviceUnlock] on a device, a stand-in in tests. */
fun interface UnlockPrompt {
    /** Whether the person confirmed it is them. */
    suspend fun confirm(): Boolean

    companion object {
        /** Nobody to ask. */
        val NEVER = UnlockPrompt { false }
    }
}

/**
 * The end-to-end keys are protected on this device and the person has not confirmed it is them — or
 * declined to. Nothing is gone; asking again opens them.
 */
class KeysLocked : Exception("The keys on this device are locked until you confirm it is you.")
