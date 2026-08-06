package de.plmail.push

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import de.plmail.core.data.PushDelivery
import de.plmail.core.data.PushRepository
import de.plmail.core.data.PushTransportManager
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Where Firebase delivers, in the `google` flavour only.
 *
 * ## What arrives
 *
 * A **data message**, never a `notification` payload, and the server is explicit about why: the
 * system tray must not draw anything before this app has seen it, because only the app knows
 * whether the user is already looking at that mailbox. `RemoteMessage.getData()["payload"]` is a
 * JSON string whose `@type` is `StateChange` or `PushVerification` — byte for byte the same JSON
 * Web Push carries, which is what lets both go through one parser.
 *
 * ## One apply-path
 *
 * Nothing here interprets the payload. It is handed to [PushRepository.deliver], the same call the
 * UnifiedPush receiver makes and the same one the EventSource stream ends in, so a state change is
 * applied by one piece of code however it arrived. Forking that per transport is how two paths come
 * to disagree about when a sync is worth making — and here the two would be exercised on different
 * *devices*, so the disagreement would never show up in one person's testing.
 *
 * ## Why this outlives the callback
 *
 * A high-priority data message buys the process a short window, not an unbounded one, and the
 * `Email/changes` loop a state change triggers can legitimately take longer against a sleeping NAS.
 * The work goes to a scope of its own for the same reason the broadcast receiver's does.
 */
@AndroidEntryPoint
class PlMailFirebaseMessagingService : FirebaseMessagingService() {

    @Inject lateinit var push: PushRepository

    @Inject lateinit var transports: PushTransportManager

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onMessageReceived(message: RemoteMessage) {
        val payload = message.data[PAYLOAD]

        if (payload == null) {
            // Logged rather than ignored. Every message plMail sends carries
            // this key, so one without it is either another sender reaching
            // this app's token or a server change nobody told the client about
            // -- and both are invisible unless said out loud.
            Log.w(TAG, "FCM message with no \"$PAYLOAD\" key; ignored.")
            return
        }

        launch { push.deliver(payload, PushDelivery.FCM) }
    }

    /**
     * Firebase issued this device a token, in answer to the `register()` the settings screen made.
     *
     * This is where an FCM switch actually completes: `FcmSupport.prepare` asks, and the address
     * comes back here — exactly as a UnifiedPush endpoint comes back to the broadcast receiver. The
     * subscription is created from it, and even then the server has still to be answered, because a
     * create arms the verification handshake.
     */
    override fun onRegistered(token: String) {
        launch { transports.tokenRotated(token) }
    }

    /**
     * Firebase reissued this device's token.
     *
     * Not optional and not rare: Android rotates tokens on its own schedule — after a restore, a
     * reinstall, or when the previous one is considered stale — and a subscription pointing at the
     * old one goes silent without anything reporting a failure. The server accepts `fcmToken` on an
     * update for exactly this, and doing so **re-arms the handshake**: a fresh `PushVerification`
     * goes to the new token and nothing is delivered until it is echoed back, which is why this
     * hands the whole decision to [PushTransportManager] rather than patching the token here.
     *
     * Overridden **as well as** [onRegistered] and deprecated in the SDK. Firebase dispatches the
     * two from different intent actions, and which one carries a rotation is the SDK's business
     * rather than this app's; taking both and letting `tokenRotated` be idempotent costs four lines
     * and removes a way for a phone to go quiet. It is dropped when the SDK stops sending it, not
     * before.
     */
    @Deprecated("Firebase's own replacement is onRegistered; both are taken while both are sent.")
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) {
        launch { transports.tokenRotated(token) }
    }

    /**
     * Firebase gave this device's token up, which this app asked it to.
     *
     * Nothing to do: [PushTransportManager] has already cleared the local registration, and the
     * server's row dies on its next delivery attempt. Overridden to say so rather than left to the
     * default, because an unhandled callback and a deliberate no-op read identically in a stack
     * trace.
     */
    override fun onUnregistered(token: String) {
        Log.d(TAG, "FCM registration given up.")
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun launch(block: suspend () -> Unit) {
        scope.launch {
            // Logged rather than swallowed. A push path that fails quietly is
            // indistinguishable from one that was never triggered, and this
            // runs where no user is watching.
            runCatching { block() }.onFailure { Log.w(TAG, "FCM handling failed", it) }
        }
    }

    private companion object {
        const val TAG = "plMail.Fcm"

        /** The one data key plMail sends. Its value is the JSON Web Push would have carried. */
        const val PAYLOAD = "payload"
    }
}
