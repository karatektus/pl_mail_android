package de.plmail

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards the build configuration itself.
 *
 * A trivial-looking suite, but it is the thing that proves the JUnit 5 platform is actually wired
 * into an *Android* unit test — Android's test task defaults to JUnit 4, and the failure mode when
 * the platform is missing is "0 tests found, build successful", which looks exactly like passing.
 */
class BuildConfigurationTest {

    @Test
    fun `application id is namespaced under the shared plMail identity`() {
        // One identity across both clients: de.plmail is what the iOS app's
        // PRODUCT_BUNDLE_IDENTIFIER already uses, and the pairing deep link
        // will be registered against this app, so it wants to be predictable.
        assertTrue(
            BuildConfig.APPLICATION_ID.startsWith("de.plmail"),
            "expected an id under de.plmail, got ${BuildConfig.APPLICATION_ID}",
        )
    }

    @Test
    fun `debug builds install alongside release ones rather than replacing them`() {
        // This suite runs against the debug variant, so the suffix must be
        // present here. It exists so a development build never uninstalls a
        // real one off the same device — and so the credential stored under
        // one applicationId cannot be read by the other.
        assertTrue(
            BuildConfig.APPLICATION_ID.endsWith(".debug"),
            "expected the debug suffix, got ${BuildConfig.APPLICATION_ID}",
        )
    }
}
