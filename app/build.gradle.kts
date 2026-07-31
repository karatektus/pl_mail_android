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
}

dependencies {
    implementation(projects.core.data)
    implementation(projects.core.datastore)
    implementation(projects.feature.onboarding)
    implementation(projects.feature.mail)
    implementation(projects.feature.search)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.kotlinx.coroutines.core)

    // UnifiedPush in *both* flavours: it is the only push path the foss build
    // has, and the fallback the google build uses when Play services are
    // absent -- a Huawei device, a de-Googled ROM, an emulator image without
    // them. The connector generates the P-256 keypair and decrypts RFC 8291
    // aes128gcm, which is exactly what WebPushSender emits, so it needs no
    // server change.
    implementation(libs.unifiedpush.connector)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}
