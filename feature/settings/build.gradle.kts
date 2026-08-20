plugins {
    alias(libs.plugins.plmail.android.library)
    alias(libs.plugins.plmail.android.compose)
    alias(libs.plugins.plmail.android.hilt)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "de.plmail.feature.settings"

    // Robolectric needs the real resources to inflate anything, and Roborazzi
    // is Robolectric with a canvas attached. Without this the section renders
    // against stubbed resources and every screenshot is blank.
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    implementation(projects.core.data)
    implementation(projects.core.datastore)
    implementation(projects.core.designsystem)
    implementation(projects.core.ui)

    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    // LocalActivity, for the one control that has to re-create the screen it is
    // drawn in: below API 33 nothing else applies a language change.
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)

    // Screenshot tests run on the JVM under Robolectric rather than on a
    // device, for the reason :feature:mail gives: a control's appearance is
    // worth guarding on every build, and an emulator in that loop would mean it
    // is guarded on none.
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
