plugins {
    alias(libs.plugins.plmail.android.library)
    alias(libs.plugins.plmail.android.compose)
}

android { namespace = "de.plmail.core.designsystem" }

// No Hilt and no data dependencies, deliberately. This module is the bottom of
// the UI stack: every feature depends on it, so anything it depends on becomes
// a dependency of the whole app's UI. It knows about colours, spacing, motion
// and text — nothing else.
dependencies {
    implementation(libs.androidx.compose.material.icons.extended)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}
