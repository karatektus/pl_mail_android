package de.plmail.push

import android.content.Context
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import de.plmail.core.data.PushRegistration
import de.plmail.core.data.PushRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.MessagingReceiver
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * Where UnifiedPush delivers.
 *
 * The connector owns the P-256 keypair and decrypts RFC 8291 `aes128gcm` before this is called, so
 * what arrives here is the plaintext JMAP payload — the same bytes `WebPushSender` encrypted. That
 * is why UnifiedPush needs no server change: the server is already speaking the protocol the
 * distributor expects.
 *
 * A broadcast receiver has roughly ten seconds and no coroutine scope of its own, so the work is
 * handed to an application-scoped one. `goAsync` is not used deliberately: the sync a state change
 * triggers can take far longer than a receiver is allowed to hold the system, and the right answer
 * to that is to let it outlive the broadcast rather than to race the deadline.
 */
class PlMailPushReceiver : MessagingReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun push(): PushRepository
    }

    /**
     * The endpoint the distributor assigned.
     *
     * Registered with the server every time it arrives, not only the first: a distributor may
     * re-issue an endpoint after its own server changes, and a subscription pointing at the old one
     * fails silently forever.
     */
    override fun onNewEndpoint(context: Context, endpoint: PushEndpoint, instance: String) {
        val keys = endpoint.pubKeySet

        if (keys == null) {
            // Without the keypair the server has nothing to encrypt to, so the
            // subscription would be registered and undeliverable. Said out
            // loud: a distributor that negotiates no encryption is a
            // configuration problem, not a silent no-op.
            Log.w(TAG, "Distributor returned an endpoint with no keys; push cannot be encrypted.")
            return
        }

        launch(context) { push ->
            push.subscribe(
                registration =
                    PushRegistration(
                        endpoint = endpoint.url,
                        p256dh = keys.pubKey,
                        auth = keys.auth,
                    ),
                deviceClientId = instance,
            )
        }
    }

    /**
     * A delivered message: either the verification handshake or a state change.
     *
     * Both are handled by [PushRepository], which tells them apart by shape. The first one is
     * load-bearing — until the verification code is echoed back, nothing else is ever delivered.
     */
    override fun onMessage(context: Context, message: PushMessage, instance: String) {
        launch(context) { push -> push.handle(message.content) }
    }

    /**
     * The distributor was removed or refused.
     *
     * Nothing to do beyond letting the periodic sync carry on: it never stopped, precisely so that
     * losing push degrades to slower mail rather than to no mail.
     */
    override fun onUnregistered(context: Context, instance: String) = Unit

    override fun onRegistrationFailed(
        context: Context,
        reason: org.unifiedpush.android.connector.FailedReason,
        instance: String,
    ) = Unit

    private companion object {
        const val TAG = "plMail.Push"
    }

    private fun launch(context: Context, block: suspend (PushRepository) -> Unit) {
        Log.d(TAG, "push event")
        val push =
            EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    Dependencies::class.java,
                )
                .push()

        // Application-scoped rather than tied to the broadcast: a receiver has
        // about ten seconds, and the Email/changes loop a push triggers can
        // legitimately take longer against a sleeping NAS.
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            // Logged rather than swallowed. A push path that fails quietly is
            // indistinguishable from one that was never triggered, and this
            // runs where no user is watching.
            runCatching { block(push) }.onFailure { Log.w(TAG, "Push handling failed", it) }
        }
    }
}
