package de.plmail.push

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import de.plmail.core.data.FcmSupport
import de.plmail.core.data.NoFcmSupport
import javax.inject.Singleton

/**
 * The `foss` flavour's answer about Firebase: there is none, and there is none in the file either.
 *
 * **This module is the mechanism, not a formality.** A build that shipped `firebase-messaging` and
 * switched it off at runtime would still link Google's libraries and still contain their bytecode,
 * so "no Firebase" cannot be kept by a flag; it is kept by the `googleImplementation` configuration
 * in `app/build.gradle.kts`, which puts the artifact on one flavour's classpath and not the
 * other's, and by this file, which is the only thing that has to exist here as a result. Verified
 * rather than asserted: `com/google/firebase/messaging` appears 150 times in the `google` APK's dex
 * and zero times in the `foss` one.
 *
 * If the Firebase dependency ever moves to a plain `implementation`, this file goes on compiling
 * and the separation quietly stops being true — so the check that matters is the one on the
 * dependency, not this class.
 *
 * **What this file does not do is make the `foss` build Google-free, and it never did.** That build
 * already carries `com.google.mlkit:barcode-scanning`, and with it `play-services-base`,
 * `play-services-basement` and several `com.google.firebase` support artifacts, because onboarding
 * pairs by scanning a QR code. Nothing here adds to that and nothing here removes it; a genuinely
 * Google-free `foss` flavour needs the scanner replaced, which is a separate piece of work with its
 * own product question attached.
 */
@Module
@InstallIn(SingletonComponent::class)
object FossFcmBindings {

    /**
     * [NoFcmSupport] lives in `:core:data` rather than here so both flavours can name it: the
     * `google` build falls back to exactly this answer on a device with no Play services, and two
     * copies of "no" would be two things to keep in step.
     */
    @Provides @Singleton fun fcmSupport(): FcmSupport = NoFcmSupport
}
