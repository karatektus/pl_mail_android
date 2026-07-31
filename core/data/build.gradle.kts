plugins {
    alias(libs.plugins.plmail.android.library)
    alias(libs.plugins.plmail.android.hilt)
}

android { namespace = "de.plmail.core.data" }

dependencies {
    api(projects.core.jmap)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(projects.core.jmap))
    testRuntimeOnly(libs.junit.platform.launcher)
}
