plugins {
    alias(libs.plugins.plmail.jvm.library)
    alias(libs.plugins.kotlin.serialization)

    // Fakes live in testFixtures rather than src/test so the data layer's own
    // suite can drive a real-behaving JMAP server without this module's tests
    // being on its classpath — and so nothing in testFixtures can ever be
    // linked into the app.
    `java-test-fixtures`
}

// No Android dependency, and that is the point — see JvmLibraryConventionPlugin.
// OkHttp is `implementation` rather than `api` so it stays an implementation
// detail of the transport: callers depend on the JmapTransport interface, which
// mentions no HTTP library, and a test substitutes its own without linking one.
dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)

    testFixturesImplementation(libs.kotlinx.serialization.json)
    testFixturesImplementation(libs.kotlinx.coroutines.core)

    testImplementation(testFixtures(project))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
