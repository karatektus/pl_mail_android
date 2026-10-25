package de.plmail

import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `application id is the one its flavour is published under`() {
        // foss keeps de.plmail, the identity the iOS app's
        // PRODUCT_BUNDLE_IDENTIFIER shares. google is what Google Play
        // publishes, and Play never lets that change after the first upload,
        // so it sits under a domain the project owns. Asserted exactly: either
        // id drifting would orphan every installed copy.
        val published = if (BuildConfig.HAS_GOOGLE_PUSH) "de.pluetzner.plmail" else "de.plmail"
        assertEquals("$published.debug", BuildConfig.APPLICATION_ID)
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
