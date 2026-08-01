plugins {
    alias(libs.plugins.plmail.android.library)
    alias(libs.plugins.plmail.android.hilt)
}

android { namespace = "de.plmail.core.notifications" }

dependencies {
    // One direction only, and deliberately: this module knows about mail and
    // about acting on it, and :core:data knows nothing about notifications. The
    // seam is `NewMailListener`, declared there and implemented here, so a build
    // without this module still syncs -- it simply says nothing.
    implementation(projects.core.data)
    implementation(projects.core.database)
    // NotificationCompat, and NotificationManagerCompat.areNotificationsEnabled,
    // which is the only honest answer to "why did nothing appear".
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
