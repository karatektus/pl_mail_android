plugins {
    alias(libs.plugins.plmail.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

// The cryptography of end-to-end encryption (#9), and nothing else: no
// Android, no JMAP, no storage. It takes bytes and strings and gives bytes and
// strings back, so every rule in it -- how a key is derived, how a wrapping is
// opened, which key unlocks which -- is tested on the JVM against values the
// browser and the server produce, in seconds.
//
// PGPainless is `api` because its key types cross this module's boundary: an
// unlocked address key is handed to whoever decrypts or signs with it.
dependencies {
    api(libs.pgpainless.core)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.mime4j.dom)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}
