plugins {
    alias(libs.plugins.plmail.android.library)
    alias(libs.plugins.plmail.android.compose)
    alias(libs.plugins.plmail.android.hilt)
}

android { namespace = "de.plmail.feature.compose" }

dependencies {
    implementation(projects.core.designsystem)
    implementation(projects.core.data)
    implementation(projects.core.database)
    // EmailAddress and the reply/forward composer are this module's vocabulary:
    // the screen draws addresses and the ViewModel asks DraftComposer what a
    // reply should start as.
    implementation(projects.core.jmap)

    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    // Only for the window size class: whether there is room to present the
    // composer as a dialog rather than as a screen is a question about the
    // window, and asking it any other way means hardcoding a breakpoint that
    // then disagrees with the one the mail pane uses.
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.kotlinx.coroutines.core)

    // The one third-party dependency in this path, and the reason is narrow:
    // Compose ships no rich-text editor and plMail round-trips HTML bodies, so
    // the alternative is writing an HTML serialiser over AnnotatedString. It
    // never sees foreign markup -- a quoted original is appended at send time
    // rather than loaded into the editor -- so its parser is not on the hook
    // for anyone else's mail.
    implementation(libs.richeditor.compose)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
