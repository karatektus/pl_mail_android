plugins {
    alias(libs.plugins.plmail.android.library)
    alias(libs.plugins.plmail.android.hilt)
    // The outbox serialises queued mutations into DataStore. Before this, the
    // module used kotlinx-serialization only to read JSON by hand, so the
    // runtime was on the classpath and the compiler plugin was not — which
    // fails at *run* time with "serializer not found", not at compile time.
    alias(libs.plugins.kotlin.serialization)
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
    // ContextCompat.checkSelfPermission, for the composer's optional read of the
    // device address book. Nothing here demands the permission; it asks whether
    // it already has it.
    implementation(libs.androidx.core.ktx)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    // The feed is a Paging source over the materialised feed table, so this
    // module owns the RemoteMediator that fills it.
    api(libs.androidx.paging.runtime)
    implementation(libs.kotlinx.coroutines.core)
    // Periodic background sync. WorkManager rather than a bare coroutine so the
    // schedule survives the process dying and honours the network constraint.
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    // The outbox is stored through a DataStore<Preferences>, and its tests need
    // one that is not a file on a device. `datastore-preferences-core` comes in
    // with this artifact and is pure Kotlin, so the fake runs on the JVM with
    // the rest of the suite.
    testImplementation(libs.androidx.datastore.preferences)
    // Availability is a flow that has to change *without* being collected again,
    // which is a statement about a sequence of emissions rather than about a
    // value -- and `first()` cannot make it.
    testImplementation(libs.turbine)
    testImplementation(testFixtures(projects.core.jmap))
    testRuntimeOnly(libs.junit.platform.launcher)

    // The feed projection, the mediator and delta sync are all *about* what
    // ends up in Room, so nothing below Room can test them: a fake DAO would
    // only assert that the test's own idea of `feed_entries` matches itself.
    // Robolectric runs `inMemoryDatabaseBuilder` on the JVM, which keeps these
    // in the suite that runs on every build rather than in an androidTest
    // source set that needs an emulator and therefore runs on none.
    testImplementation(libs.robolectric)
    // ApplicationProvider, which is what hands Room a Context under Robolectric.
    testImplementation(libs.androidx.test.ext.junit)
    // Robolectric is JUnit 4 and this module's other tests are JUnit 5. The
    // platform runs both engines side by side only if the vintage one is
    // present; without it the Robolectric classes are silently not discovered
    // and the task passes having run none of them. `feature/mail` and
    // `core/ui` carry the same pair for the same reason.
    testRuntimeOnly(libs.junit.vintage.engine)
}
