plugins {
    alias(libs.plugins.plmail.android.library)
    alias(libs.plugins.plmail.android.compose)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "de.plmail.core.ui"

    // Robolectric needs the real resources to inflate anything, and Roborazzi
    // is Robolectric with a canvas attached. Without this the row renders
    // against stubbed resources and every screenshot is blank.
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    // The row draws a ThreadEntity directly. That is the denormalisation being
    // load-bearing rather than a leak: the table exists in that shape so fifty
    // rows can scroll without a join, and a UI model copied field for field
    // would only add a mapping to keep in sync.
    api(projects.core.database)
    // Every screen reads tokens from here, so it is `api` rather than
    // `implementation`: a feature drawing a ThreadRow inside PlMailTheme needs
    // the theme type on its own classpath.
    api(projects.core.designsystem)

    implementation(libs.androidx.compose.material.icons.extended)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)

    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.ext.junit)
    testRuntimeOnly(libs.junit.platform.launcher)
    // Robolectric, and therefore Roborazzi, is JUnit 4 while this module's
    // other tests are JUnit 5. The platform runs both engines side by side
    // only if the vintage one is present; without it the screenshot class is
    // silently not discovered and the task passes having rendered nothing.
    testRuntimeOnly(libs.junit.vintage.engine)
}
