package de.plmail

import android.app.Activity
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import de.plmail.core.data.DeviceUnlock
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Shows the system's prompt for [DeviceUnlock] while [activity] is on screen (#9, phase 5).
 *
 * The platform's own `BiometricPrompt` rather than AndroidX's: the app's minimum is API 31, where
 * the platform prompt takes the screen lock as well as a fingerprint or face
 * (`setAllowedAuthenticators`), and it needs no fragment activity. No `CryptoObject`: the protected
 * key is time-bound, so any confirmation in the last five minutes opens it — the prompt only has to
 * happen.
 *
 * Attached from `onStart` to `onStop`, so a prompt is only ever asked of an activity the person can
 * see. Anything that needs the keys while nothing is attached is told no, and waits.
 */
class UnlockPromptHost(private val activity: Activity, private val unlock: DeviceUnlock) :
    DefaultLifecycleObserver {

    private var detach: (() -> Unit)? = null

    override fun onStart(owner: LifecycleOwner) {
        detach = unlock.attach { withContext(Dispatchers.Main) { ask() } }
    }

    override fun onStop(owner: LifecycleOwner) {
        detach?.invoke()
        detach = null
    }

    private suspend fun ask(): Boolean = suspendCancellableCoroutine { continuation ->
        val cancel = CancellationSignal()
        continuation.invokeOnCancellation { cancel.cancel() }

        val prompt =
            BiometricPrompt.Builder(activity)
                .setTitle(activity.getString(R.string.unlock_title))
                .setSubtitle(activity.getString(R.string.unlock_subtitle))
                // Spelled out rather than DeviceUnlock.AUTHENTICATORS, which is
                // the same value: lint checks this argument's flags only when it
                // can see them.
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                .build()

        prompt.authenticate(
            cancel,
            activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult
                ) {
                    if (continuation.isActive) continuation.resume(true)
                }

                // Cancelled, too many tries, no hardware: all of them are "not
                // now", and the caller says so in its own words. A single
                // unrecognised finger is not an error; the prompt stays up.
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    if (continuation.isActive) continuation.resume(false)
                }
            },
        )
    }
}
