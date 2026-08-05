plugins {
    alias(libs.plugins.plmail.android.library)
    alias(libs.plugins.plmail.android.compose)
    alias(libs.plugins.plmail.android.hilt)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "de.plmail.feature.calendar"

    // Robolectric needs the real resources to inflate anything, and Roborazzi
    // is Robolectric with a canvas attached. Without this the agenda renders
    // against stubbed resources and every screenshot is blank.
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    implementation(projects.core.data)
    // The agenda draws an AgendaRow, which is the DAO's own projection. Same
    // reasoning as the thread row: the join exists in that shape so a list can
    // scroll without a query per row, and a UI model copied field for field
    // would only add a mapping to keep in sync.
    implementation(projects.core.database)
    implementation(projects.core.designsystem)

    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)

    // Screenshot tests run on the JVM under Robolectric rather than on a
    // device: the agenda's appearance is worth guarding on every build, and an
    // emulator in that loop would mean it is guarded on none.
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.ext.junit)
    testRuntimeOnly(libs.junit.platform.launcher)
    // Robolectric, and therefore Roborazzi, is JUnit 4. This module's other
    // tests are JUnit 5, and the platform runs both engines side by side --
    // but only if the vintage one is present. Without it the screenshot class
    // is silently not discovered and the task reports success having rendered
    // nothing.
    testRuntimeOnly(libs.junit.vintage.engine)
}
