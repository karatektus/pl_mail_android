plugins {
    alias(libs.plugins.plmail.android.application)
    alias(libs.plugins.plmail.android.compose)
    alias(libs.plugins.plmail.android.hilt)
}

android {
    namespace = "de.plmail"

    defaultConfig { applicationId = "de.plmail" }

    /**
     * Two builds, because "without Google" has to be true rather than merely configurable.
     *
     * A single build that ships firebase-messaging and decides at runtime still contains Google's
     * code, still links its libraries, and is still ineligible for F-Droid. The only way to promise
     * an installation with no Google dependency is to build one that does not contain any, so the
     * flavour dimension is the promise.
     *
     * `foss` is the default and takes the plain applicationId: it is the build the product's own
     * audience -- people who self-host their mail specifically to avoid this -- should get without
     * having to ask.
     */
    flavorDimensions += "distribution"

    productFlavors {
        create("foss") {
            dimension = "distribution"
            // UnifiedPush only. Needs no server change at all: the connector
            // decrypts the same RFC 8291 aes128gcm payload WebPushSender
            // already emits.
            buildConfigField("boolean", "HAS_GOOGLE_PUSH", "false")
        }

        create("google") {
            dimension = "distribution"
            // A distinct id, so both can be installed side by side while the
            // two push paths are being compared.
            applicationIdSuffix = ".google"
            buildConfigField("boolean", "HAS_GOOGLE_PUSH", "true")
        }
    }

    /**
     * No `google-services` plugin, and no `google-services.json`.
     *
     * That plugin's whole job is to bake one Firebase project into the APK at build time, which is
     * exactly what cannot happen here: **one Play Store APK serves every self-hosted plMail**, and
     * every installation has its own Firebase project belonging to whoever runs the server. The
     * four public values come out of the session capability instead and `FirebaseOptions` is built
     * from them at runtime — see `GoogleFcmSupport`. Adding the plugin later would make the build
     * fail for want of a file that must never exist in this repository.
     *
     * The flavour source sets themselves need no declaration: `src/foss/kotlin`,
     * `src/google/kotlin` and `src/google/AndroidManifest.xml` are conventional locations AGP
     * already reads, and the manifest is merged into the main one for that flavour only.
     */

    /**
     * Needed by the launcher-icon suites, and for two separate reasons.
     *
     * Robolectric cannot answer anything about a component without the real resources behind it,
     * and `CalendarLauncherManifestTest` needs something else again: the **merged** manifest, which
     * is the only artefact where the alias, the activity it targets and the affinity that separates
     * their tasks exist together. Switching this on is what makes AGP write the
     * `com.android.tools.test_config.properties` that names the path to it, and that file is how
     * the test finds it without hard-coding a build directory that changes with the variant.
     */
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    implementation(projects.core.data)
    implementation(projects.core.datastore)
    implementation(projects.core.designsystem)
    // :app is the only module that knows which activity a notification tap
    // reaches, so it is the one that supplies MailDestinations.
    implementation(projects.core.notifications)
    implementation(projects.feature.onboarding)
    implementation(projects.feature.mail)
    implementation(projects.feature.calendar)
    implementation(projects.feature.compose)
    implementation(projects.feature.search)
    implementation(projects.feature.settings)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // ProcessLifecycleOwner, for ForegroundPresence. Only :app may hold this:
    // "the whole app is visible" is a process-wide fact and there is exactly one
    // place that can observe it.
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.kotlinx.coroutines.core)

    // UnifiedPush in *both* flavours: it is the only push path the foss build
    // has, and the fallback the google build uses when Play services are
    // absent -- a Huawei device, a de-Googled ROM, an emulator image without
    // them. The connector generates the P-256 keypair and decrypts RFC 8291
    // aes128gcm, which is exactly what WebPushSender emits, so it needs no
    // server change.
    implementation(libs.unifiedpush.connector)

    // Firebase in the `google` flavour and NOWHERE else.
    //
    // `googleImplementation` rather than `implementation` is the mechanism that
    // makes "without Google" true rather than merely configurable: the artifact
    // is on one flavour's compile and runtime classpath and absent from the
    // other's, so the foss APK contains no gms bytecode to be found by anyone
    // who goes looking -- which is the condition F-Droid inclusion turns on and
    // the reason this app's own audience picked it.
    //
    // The corollary is that every Firebase *call site* has to live under
    // app/src/google/, behind the FcmSupport interface in :core:data. A single
    // import of com.google.firebase from app/src/main would break the foss
    // build, which is the check working rather than an inconvenience.
    "googleImplementation"(platform(libs.firebase.bom))
    "googleImplementation"(libs.firebase.messaging)
    "googleImplementation"(libs.play.services.base)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)

    // Robolectric, for the one thing in this module that cannot be tested
    // without an Android: the enabled state of a manifest component, which is
    // PackageManager's to hold. ShadowPackageManager keeps it honestly enough
    // that "default means the manifest's false" is a statement a test can make.
    testImplementation(libs.robolectric)
    // For org.junit.Test and org.junit.runner.RunWith, which Robolectric needs
    // and which nothing else here puts on the compile classpath: this module's
    // own tests are Jupiter, and the vintage engine below is runtime only. The
    // same artifact the calendar and core:ui screenshot suites take it from.
    testImplementation(libs.androidx.test.ext.junit)
    // Robolectric is JUnit 4 and this module runs on the JUnit Platform.
    // Without the vintage engine its classes are not discovered at all and the
    // task reports success having run none of them.
    testRuntimeOnly(libs.junit.vintage.engine)
}
