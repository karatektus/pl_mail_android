plugins {
    alias(libs.plugins.plmail.android.library)
    alias(libs.plugins.plmail.android.compose)
    alias(libs.plugins.plmail.android.hilt)
}

android { namespace = "de.plmail.feature.onboarding" }

dependencies {
    implementation(projects.core.data)
    implementation(projects.core.datastore)

    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.core)

    // The credential store's constructor takes a DataStore, and :core:datastore
    // keeps that dependency `implementation`. Declared here so the in-memory
    // fake below implements the same DataStore the store was compiled against
    // rather than a coincidentally-named one off a different classpath.
    testImplementation(libs.androidx.datastore.preferences)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testRuntimeOnly(libs.junit.platform.launcher)
}
