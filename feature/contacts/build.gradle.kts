plugins {
    alias(libs.plugins.plmail.android.library)
    alias(libs.plugins.plmail.android.compose)
    alias(libs.plugins.plmail.android.hilt)
}

// Contacts: the address book on the phone — list, search, detail with
// reference emails, editor with photos, duplicate finder and merge. Talks to the
// server's Contact/* JMAP methods through :core:data's ContactsRepository.
android { namespace = "de.plmail.feature.contacts" }

dependencies {
    implementation(projects.core.data)
    implementation(projects.feature.notes)
    implementation(projects.core.designsystem)
    implementation(projects.core.jmap)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.core)
}

// No unit tests in this module yet, and Gradle 9 fails a test task that finds
// none. Remove this once the first test lands.
tasks.withType<Test>().configureEach { failOnNoDiscoveredTests = false }
