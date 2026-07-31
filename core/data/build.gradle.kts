plugins {
    alias(libs.plugins.plmail.android.library)
    alias(libs.plugins.plmail.android.hilt)
}

android { namespace = "de.plmail.core.data" }

dependencies {
    api(projects.core.jmap)
    implementation(projects.core.database)
    // PlMailDatabase is public API of :core:database but Room is an
    // `implementation` dependency there, so its supertype is not on this
    // module's classpath -- and `withTransaction`, which is what makes a page
    // of messages and the thread rows derived from them commit together, lives
    // in room-ktx.
    implementation(libs.androidx.room.ktx)
    implementation(projects.core.datastore)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(projects.core.jmap))
    testRuntimeOnly(libs.junit.platform.launcher)
}
