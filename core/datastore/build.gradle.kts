plugins {
    alias(libs.plugins.plmail.android.library)
    alias(libs.plugins.plmail.android.hilt)
}

android { namespace = "de.plmail.core.datastore" }

dependencies {
    // For ServerAddress and Credential: this module stores them, so it has to
    // speak the same types the transport does rather than its own copies.
    implementation(projects.core.jmap)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    // Turbine, for the one assertion that is about an emission *not* happening:
    // `expectNoEvents` is the only honest way to state "a sync writing a
    // timestamp must not re-theme the app".
    testImplementation(libs.turbine)
    testRuntimeOnly(libs.junit.platform.launcher)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
