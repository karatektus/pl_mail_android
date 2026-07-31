plugins {
    alias(libs.plugins.plmail.android.library)
    alias(libs.plugins.plmail.android.room)
    alias(libs.plugins.plmail.android.hilt)
}

android { namespace = "de.plmail.core.database" }

dependencies {
    implementation(projects.core.jmap)
    implementation(libs.androidx.room.paging)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)

    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
