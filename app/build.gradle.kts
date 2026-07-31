plugins {
    alias(libs.plugins.plmail.android.application)
    alias(libs.plugins.plmail.android.compose)
    alias(libs.plugins.plmail.android.hilt)
}

android {
    namespace = "de.plmail"

    defaultConfig { applicationId = "de.plmail" }
}

dependencies {
    implementation(projects.core.data)
    implementation(projects.core.datastore)
    implementation(projects.feature.onboarding)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}
