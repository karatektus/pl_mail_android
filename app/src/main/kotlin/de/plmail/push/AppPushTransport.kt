package de.plmail.push

import android.content.Context
import android.os.Build
import android.provider.Settings
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.plmail.core.data.DeviceClientId
import de.plmail.core.data.PushTransport
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UnifiedPush, as the rest of the app is allowed to see it.
 *
 * Bound here rather than in `:core:data` for the same reason `MailDestinations` is: only `:app`
 * depends on the connector, and only `:app` knows whether this build has one at all. `FcmSupport`
 * is its Firebase counterpart and is bound per *flavour*, which is what keeps the `foss` build free
 * of Google's code — this class is in `main` because UnifiedPush ships in both.
 */
@Singleton
class AppPushTransport @Inject constructor(@param:ApplicationContext private val context: Context) :
    PushTransport {

    override fun distributor(): String? = PushSetup.distributor(context)

    override fun installed(): List<String> = PushSetup.available(context)

    override fun register(): Boolean = PushSetup.enable(context)

    override fun unregister() = PushSetup.disable(context)

    @Module
    @InstallIn(SingletonComponent::class)
    abstract class Bindings {
        @Binds @Singleton abstract fun transport(real: AppPushTransport): PushTransport

        companion object {
            /**
             * This device's `deviceClientId`: stable per device, and the same string whichever
             * transport is in use.
             *
             * **Stable across transports is the requirement that matters.** The server replaces the
             * subscription matching this id, which is the mechanism that makes a move from a
             * distributor to Firebase produce one subscription rather than two — so both paths have
             * to agree on it. Before this it was whatever the UnifiedPush connector passed back as
             * its instance name, which Firebase has no counterpart for.
             *
             * **Unique per device is the requirement that was missing.** That instance name was the
             * constant `plmail`, so two Android phones on one account registered the *same*
             * `deviceClientId` and each replaced the other's subscription: whichever registered
             * last received the mail and the other went quiet, with both apps reporting themselves
             * registered. `ANDROID_ID` fixes it — since Android 8 it is scoped per app-signing-key,
             * so it identifies this install on this device and is not a handle anybody else can
             * correlate. It is hashed anyway and truncated, because the id ends up in a table the
             * user reads and its only job is to differ.
             *
             * One consequence, which turned out to be a bug rather than a footnote: a device
             * upgrading from a build that used `plmail` registers under a new id, and the server's
             * replace only ever looks at one id — so the old row does *not* go away, and a phone
             * that moved to Firebase kept receiving over the abandoned Web Push subscription as
             * well. `PushRepository` sweeps that row once, before the first registration under the
             * hashed id; the note there is why destroying it cannot cost anybody anything.
             *
             * Note that the fallback below can still produce the bare constant, on a device that
             * answers neither `ANDROID_ID` nor `Build.MODEL`. The sweep checks for exactly that and
             * declines, rather than deleting the subscription it is about to create.
             */
            @Provides
            @Singleton
            fun deviceClientId(@ApplicationContext context: Context): DeviceClientId {
                @Suppress("HardwareIds")
                val installation =
                    Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)

                val fingerprint =
                    installation?.takeIf { it.isNotBlank() }?.let(::shorten)
                        // Null on a device that refuses to answer. A random id
                        // is worse than a wrong one here -- it would register a
                        // new subscription on every process start -- so the
                        // model stands in, and two identical phones colliding
                        // is the failure this replaced rather than a new one.
                        ?: Build.MODEL?.trim()?.takeIf { it.isNotBlank() }?.let(::shorten)

                return DeviceClientId(
                    if (fingerprint == null) PushSetup.INSTANCE
                    else "${PushSetup.INSTANCE}-$fingerprint"
                )
            }

            private fun shorten(value: String): String =
                MessageDigest.getInstance("SHA-256")
                    .digest(value.toByteArray())
                    .take(FINGERPRINT_BYTES)
                    .joinToString("") { "%02x".format(it) }

            /**
             * Eight hex characters: enough that two devices colliding is not a thing that happens.
             */
            private const val FINGERPRINT_BYTES = 4
        }
    }
}
