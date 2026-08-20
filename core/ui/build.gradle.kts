plugins {
    alias(libs.plugins.plmail.android.library)
    alias(libs.plugins.plmail.android.compose)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "de.plmail.core.ui"

    // The touch-target assertion in src/testFixtures is shared with the feature
    // modules. It lives here rather than being copied into each because the
    // rule it enforces is this module's -- `PlMailSpacing.touchTarget` -- and a
    // rule enforced by three private copies is three rules.
    testFixtures.enable = true

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

    "testFixturesImplementation"(platform(libs.androidx.compose.bom))
    "testFixturesImplementation"(libs.androidx.compose.ui.test.junit4)
    "testFixturesImplementation"(libs.kotlin.test.junit5)
    testImplementation(libs.androidx.test.ext.junit)
    testRuntimeOnly(libs.junit.platform.launcher)
    // Robolectric, and therefore Roborazzi, is JUnit 4 while this module's
    // other tests are JUnit 5. The platform runs both engines side by side
    // only if the vintage one is present; without it the screenshot class is
    // silently not discovered and the task passes having rendered nothing.
    testRuntimeOnly(libs.junit.vintage.engine)
}

// `verifyScreenshotsOnCheck` — a baseline recorded from a broken build once
// shipped describing itself as proof of the fix, and nothing noticed.
tasks.matching { it.name == "check" }.configureEach { dependsOn("verifyRoborazziDebug") }

// The checked-in screenshots are a gate, not documentation.
//
// Roborazzi creates `recordRoborazzi<Variant>` and `verifyRoborazzi<Variant>` and wires *neither*
// into anything, so a wrong baseline could be committed and nothing would ever say so. That is not
// hypothetical: a baseline was once recorded from a deliberately broken build — the counterfactual
// half of "prove the fix actually fixes it" — the fix was restored, the re-record silently did not
// take, and the *broken* render was committed and described as pinning the fix. `./gradlew build`
// stayed green throughout, twice, and so did CI. The picture in the repository showed the bug.
//
// Safe to make mandatory because the rendering is deterministic: two consecutive records of the
// same source produce byte-identical files. It is Robolectric with a bundled Skia and bundled
// fonts, so there is no system font or GPU to vary underneath it. If that ever stops holding, find
// out why rather than unhooking this — an unverified baseline is worse than none, because it looks
// like coverage.
//
// Wired per module rather than globally: the variant name differs where there are flavours, and a
// module that grows flavours later should have to say so.
tasks.matching { it.name == "check" }.configureEach { dependsOn("verifyRoborazziDebug") }
