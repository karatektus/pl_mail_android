plugins {
    alias(libs.plugins.plmail.android.library)
    alias(libs.plugins.plmail.android.compose)
    alias(libs.plugins.plmail.android.hilt)
}

// Keep-style notes: a wall of cards, a text/checklist editor, colours, pin/archive/bin, and links
// to
// events, files, contacts and mail. Talks to the server's Note/* JMAP methods through
// :core:data's NotesRepository. Also exports LinkedNotesSection, which the calendar and contacts
// screens embed so a note can be reached from the thing it is about.
android { namespace = "de.plmail.feature.notes" }

dependencies {
    implementation(projects.core.data)
    implementation(projects.core.designsystem)
    implementation(projects.core.jmap)
    implementation(projects.core.database)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.core)
}

// No unit tests in this module yet, and Gradle 9 fails a test task that finds none.
tasks.withType<Test>().configureEach { failOnNoDiscoveredTests = false }
