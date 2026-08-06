package de.plmail.push

import android.content.Context
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.plmail.core.data.FcmAvailability
import de.plmail.core.data.FcmSupport
import de.plmail.core.data.PushUnavailable
import de.plmail.jmap.protocol.FcmConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Firebase, initialised at runtime against whichever plMail this install is signed into.
 *
 * ## No `google-services.json`, and no `google-services` plugin
 *
 * The normal Android arrangement processes that file at build time and bakes one Firebase project
 * into the APK. It cannot work here: **one Play Store APK serves every self-hosted plMail**, and
 * every installation has its own Firebase project — created by whoever runs the server, paid for by
 * them, and unknown to us. So the server publishes the four public values in its session capability
 * and this builds [FirebaseOptions] from them the moment the user picks FCM.
 *
 * All four ship inside every Firebase app's APK anyway and are public by nature. The
 * service-account key that can actually *send* never leaves the server.
 *
 * ## The default app, on purpose
 *
 * A named secondary app would be tidier and is the wrong shape: [FirebaseMessaging] reads the
 * *default* instance, and an incoming message reaches [PlMailFirebaseMessagingService] against the
 * default app whatever else is registered. So a token minted from a secondary app would be issued
 * for a project the delivery path is not using. Owning the default instance is safe here precisely
 * because there is no `google-services.json`: nothing initialises it at process start, Firebase's
 * own init provider fails quietly and leaves it empty, and this is the only code that ever creates
 * one.
 *
 * ## Auto-init is off until the user asks
 *
 * The manifest disables it, and [prepare] turns it on. That is the difference between an app that
 * mints a Google-held identifier for every install and one that mints it for the people who chose
 * Firebase — which, on a product whose users self-host their mail specifically to avoid that, is
 * not a detail.
 *
 * ## Everything here degrades rather than throws
 *
 * The failures are ordinary, not exceptional. A de-Googled ROM has no Play services; a Huawei has
 * none either; an emulator image often has none. A server whose administrator pasted three of four
 * values publishes a config that cannot start Firebase. None of those is a crash — they are reasons
 * the FCM option is not offered, and each one is a *different* reason, which is why they come back
 * as [PushUnavailable] values rather than as a null token.
 */
@Singleton
class GoogleFcmSupport @Inject constructor(@param:ApplicationContext private val context: Context) :
    FcmSupport {

    override val isCompiledIn = true

    /** One initialisation at a time; two would race `FirebaseApp`'s own registry. */
    private val mutex = Mutex()

    /**
     * Whether Play services are usable, and nothing else.
     *
     * Deliberately cheap and deliberately inert: it reads a status code off the device and starts
     * no Firebase app, mints no token, and makes no network request. The settings screen calls this
     * on every draw, and if it were [prepare] then opening settings would hand Google a
     * registration token for this install — including for the user who opened settings in order to
     * turn push off.
     */
    override fun probe(): PushUnavailable? = playServices()?.reason

    override suspend fun prepare(config: FcmConfig): FcmAvailability = mutex.withLock {
        if (!config.isComplete) {
            return@withLock FcmAvailability.Unavailable(PushUnavailable.SERVER_CONFIG_INCOMPLETE)
        }

        playServices()?.let {
            return@withLock it
        }

        runCatching {
                app(config)

                val messaging = FirebaseMessaging.getInstance()

                // Turned on here rather than in the manifest: until the user
                // picks FCM there is nothing for Google to know about this
                // install, and that is the default the product's audience
                // would choose.
                messaging.isAutoInitEnabled = true

                // Asks for a token; the token itself arrives in
                // PlMailFirebaseMessagingService's registration callback.
                // Awaited only so that a *refusal* — no network, Play
                // services mid-update — is reported here as a failure to
                // start rather than as a switch that silently never
                // finishes.
                withContext(Dispatchers.IO) { await(messaging.register()) }
            }
            .fold(
                onSuccess = { FcmAvailability.Ready },
                onFailure = { failure ->
                    Log.w(TAG, "Firebase would not start against this server's project", failure)

                    FcmAvailability.Unavailable(
                        PushUnavailable.INIT_FAILED,
                        failure.message ?: failure::class.java.simpleName,
                    )
                },
            )
    }

    /**
     * Gives the token up and tears the Firebase app down.
     *
     * `deleteToken` matters and is not tidiness: a token the server still holds and Google still
     * routes is one that wakes a phone for a mailbox it is no longer signed into. It talks to the
     * network, so it is allowed to fail — the local teardown happens either way, because leaving a
     * Firebase app initialised against a server the user has left is the thing that would deliver
     * to the wrong place.
     */
    override suspend fun release() {
        mutex.withLock {
            val app = existing() ?: return@withLock

            runCatching {
                withContext(Dispatchers.IO) {
                    val messaging = FirebaseMessaging.getInstance()

                    messaging.isAutoInitEnabled = false
                    await(messaging.unregister())
                }
            }
                .onFailure { Log.w(TAG, "Could not give up the FCM token", it) }

            runCatching { app.delete() }
                .onFailure { Log.w(TAG, "Could not delete the Firebase app", it) }
        }
    }

    /**
     * Whether this device can run Firebase at all.
     *
     * Asked before initialisation rather than after it fails, because "no Play services" is a
     * permanent property of the device with an honest sentence attached — install the `foss` build
     * and a distributor — while an initialisation failure is a configuration problem on the server.
     * Both would otherwise arrive as the same exception.
     */
    private fun playServices(): FcmAvailability.Unavailable? {
        val availability = GoogleApiAvailability.getInstance()
        val status = availability.isGooglePlayServicesAvailable(context)

        if (status == ConnectionResult.SUCCESS) return null

        return FcmAvailability.Unavailable(
            PushUnavailable.NO_PLAY_SERVICES,
            // The status string in Google's own words, because it distinguishes
            // "missing" from "needs updating" and the second is something the
            // user can act on.
            availability.getErrorString(status),
        )
    }

    /**
     * The Firebase app for [config], rebuilt when the server's project has changed.
     *
     * Comparing the running options rather than assuming the first initialisation is the right one:
     * a user who signs out of one plMail and into another arrives here with a live app pointed at
     * the old project, and Firebase does not notice.
     */
    private fun app(config: FcmConfig): FirebaseApp {
        val options =
            FirebaseOptions.Builder()
                .setProjectId(config.projectId)
                .setApplicationId(config.applicationId)
                .setApiKey(config.apiKey)
                .setGcmSenderId(config.senderId)
                .build()

        existing()?.let { running ->
            if (
                running.options.applicationId == options.applicationId &&
                    running.options.projectId == options.projectId &&
                    running.options.gcmSenderId == options.gcmSenderId
            ) {
                return running
            }

            running.delete()
        }

        return FirebaseApp.initializeApp(context, options)
    }

    private fun existing(): FirebaseApp? = runCatching { FirebaseApp.getInstance() }.getOrNull()

    /** `Task` to coroutine, rather than another dependency for two calls. */
    private suspend fun <T> await(task: com.google.android.gms.tasks.Task<T>): T? =
        suspendCancellableCoroutine { continuation ->
            task.addOnCompleteListener { finished ->
                if (finished.isSuccessful) {
                    continuation.resume(finished.result)
                } else {
                    continuation.resumeWith(
                        Result.failure(finished.exception ?: IllegalStateException(NO_REASON))
                    )
                }
            }
        }

    @Module
    @InstallIn(SingletonComponent::class)
    abstract class Bindings {
        @Binds @Singleton abstract fun fcmSupport(real: GoogleFcmSupport): FcmSupport
    }

    private companion object {
        const val TAG = "plMail.Fcm"

        /** Not translated: it is logged beside Firebase's own messages, which are not either. */
        const val NO_REASON = "Firebase refused to register and gave no reason."
    }
}
