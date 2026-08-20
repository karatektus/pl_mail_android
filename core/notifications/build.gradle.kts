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

    // What a notification actually is cannot be asserted without the platform
    // that builds it: RemoteInput, the action list and the merged manifest's
    // exported flag are all read back through real framework classes. A fake
    // NotificationCompat.Builder would only prove that the test's idea of a
    // notification agrees with itself, which is precisely the shape of test
    // that would have let an immutable reply PendingIntent ship -- it compiles,
    // it looks right, and it silently delivers no text.
    testImplementation(libs.robolectric)
    // ApplicationProvider, for the Context every one of those needs.
    testImplementation(libs.androidx.test.ext.junit)
    // Robolectric is JUnit 4 and this module's other tests are JUnit 5. The
    // platform runs both engines side by side only if the vintage one is
    // present; without it the Robolectric classes are silently not discovered
    // and the task passes having run none of them. `core/data` carries the same
    // pair for the same reason.
    testRuntimeOnly(libs.junit.vintage.engine)
}
