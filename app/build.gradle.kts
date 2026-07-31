plugins {
    alias(libs.plugins.plmail.android.application)
    alias(libs.plugins.plmail.android.compose)
}

android {
    namespace = "de.plmail"

    defaultConfig { applicationId = "de.plmail" }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}
