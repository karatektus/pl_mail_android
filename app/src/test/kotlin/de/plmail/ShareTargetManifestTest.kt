package de.plmail

import java.io.File
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.w3c.dom.Element

/**
 * Whether this app can be shared into, read out of the manifest the build actually produces.
 *
 * **There is no Kotlin for any of this.** An intent filter is the whole feature: a missing action
 * means plMail is absent from the share sheet, a mime claim narrower than the wildcard means it is
 * absent for everything except the types it names, a missing `BROWSABLE` means a browser will not
 * offer it for a `mailto:` link, and `exported="false"` means every one of them resolves to nothing
 * at all. None of those fail loudly. They fail by the app simply not appearing in a list, which is
 * indistinguishable from the user not looking properly, and that is exactly how `ACTION_SEND` came
 * to be missing from this manifest for the whole of the app's life so far.
 *
 * The merged manifest rather than the source one, found the way `CalendarLauncherManifestTest`
 * finds it and for the same reason: the path AGP writes is per variant, so this runs over four
 * merged manifests and the two flavours are checked rather than assumed to agree.
 */
class ShareTargetManifestTest {

    private val manifest: Element by lazy {
        val config =
            checkNotNull(javaClass.classLoader?.getResourceAsStream(TEST_CONFIG)) {
                "No $TEST_CONFIG on the test classpath. " +
                    "android.testOptions.unitTests.isIncludeAndroidResources must be on."
            }

        val properties = config.use { Properties().apply { load(it) } }
        val path =
            checkNotNull(properties.getProperty("android_merged_manifest")) {
                "$TEST_CONFIG names no android_merged_manifest"
            }

        val file = File(path).absoluteFile
        assertTrue(file.isFile, "expected a merged manifest at $file")

        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(file)
            .documentElement
    }

    @Test
    fun `the share sheet can reach this app`() {
        val share =
            filters().singleOrNull { "android.intent.action.SEND" in it.actions }
                ?: error(
                    "no SEND filter on MainActivity: nothing on the device can share into plMail"
                )

        assertTrue(
            "android.intent.action.SEND_MULTIPLE" in share.actions,
            "SEND alone means sharing two photos at once silently does nothing: ${share.actions}",
        )

        // DEFAULT, or the filter is unreachable from an implicit intent, which
        // is the only kind a share sheet sends.
        assertTrue("android.intent.category.DEFAULT" in share.categories)
    }

    @Test
    fun `the share filter claims every type a message can carry`() {
        val share = filters().single { "android.intent.action.SEND" in it.actions }

        // The decision is argued at length in the manifest. What is asserted
        // here is only that it is still the decision: an enumeration would leave
        // a user unable to email a .zip, with no error to report.
        assertEquals(
            listOf("*/*"),
            share.mimeTypes,
            "narrowing this is a product change, not a tidy-up; see the manifest comment",
        )
    }

    @Test
    fun `a mailto link reaches this app from a browser`() {
        val view =
            filters().singleOrNull {
                "android.intent.action.VIEW" in it.actions && "mailto" in it.schemes
            } ?: error("no VIEW filter for mailto: tapping an email address cannot offer plMail")

        // The one that is easy to leave out and impossible to notice: without
        // BROWSABLE a browser will not hand the URI over at all, so the filter
        // exists and the link still does nothing.
        assertTrue(
            "android.intent.category.BROWSABLE" in view.categories,
            "a VIEW filter without BROWSABLE is invisible to every browser: ${view.categories}",
        )
        assertTrue("android.intent.category.DEFAULT" in view.categories)
    }

    @Test
    fun `an app asking for a composer on an address reaches this app`() {
        val sendTo =
            filters().singleOrNull { "android.intent.action.SENDTO" in it.actions }
                ?: error("no SENDTO filter: a contacts app cannot open plMail on an address")

        assertTrue("mailto" in sendTo.schemes)
        assertTrue("android.intent.category.DEFAULT" in sendTo.categories)

        // Deliberately absent. BROWSABLE means "safe to start from untrusted web
        // content", and SENDTO is never started that way; claiming it would be a
        // statement about this component that is not true of this action.
        assertTrue(
            "android.intent.category.BROWSABLE" !in sendTo.categories,
            "SENDTO is not a web entry point and must not claim to be",
        )
    }

    @Test
    fun `the pairing link is still its own filter`() {
        // Three VIEW filters now sit on one activity. This is what keeps the
        // mailto one from having quietly replaced the plmail:// one -- they
        // would look identical in a diff that only counted filters.
        val pairing =
            filters().singleOrNull { "plmail" in it.schemes }
                ?: error("the plmail:// pairing filter has gone")

        assertTrue("android.intent.action.VIEW" in pairing.actions)
        assertTrue("android.intent.category.BROWSABLE" in pairing.categories)
    }

    @Test
    fun `mailto is declared without a host`() {
        // A mailto: is an opaque URI: everything after the colon is the
        // scheme-specific part, so there is no authority for a host to match and
        // a host here would match nothing at all -- silently, as an intent
        // filter that never fires.
        filters()
            .filter { "mailto" in it.schemes }
            .forEach { filter ->
                assertEquals(
                    emptyList(),
                    filter.hosts,
                    "a host on a mailto filter makes it match nothing",
                )
            }
    }

    @Test
    fun `the activity everything resolves to is exported`() {
        // All five filters are on MainActivity, and none of them resolves for
        // another app unless this is true. It already was, for the pairing link;
        // this is the assertion that says so out loud now that four more things
        // depend on it.
        assertEquals("true", activity().android("exported"))

        // singleTask, which is what makes a share or a mailto arriving while the
        // app is open reach onNewIntent rather than stack a second mailbox. The
        // cost of that choice is set out in the manifest.
        assertEquals("singleTask", activity().android("launchMode"))
    }

    // ------------------------------------------------------------------ reading

    private fun filters(): List<Filter> = activity().children("intent-filter").map(::Filter)

    private fun activity(): Element =
        manifest.children("application").single().children("activity").singleOrNull {
            it.android("name") == "$NAMESPACE.MainActivity"
        } ?: error("no <activity> named $NAMESPACE.MainActivity")

    private companion object {
        const val NAMESPACE = "de.plmail"

        /** A path and not a dotted name; see `CalendarLauncherManifestTest` for why. */
        const val TEST_CONFIG = "com/android/tools/test_config.properties"
    }
}

/** One `<intent-filter>`, flattened into the four lists worth asking about. */
private class Filter(element: Element) {
    val actions: List<String> = element.names("action")
    val categories: List<String> = element.names("category")
    val schemes: List<String> = element.attributes("data", "scheme")
    val hosts: List<String> = element.attributes("data", "host")
    val mimeTypes: List<String> = element.attributes("data", "mimeType")
}

private fun Element.names(tag: String): List<String> = attributes(tag, "name")

private fun Element.attributes(tag: String, name: String): List<String> =
    children(tag).mapNotNull { it.android(name) }

/** An attribute in the Android namespace, or null when it is not declared at all. */
private fun Element.android(name: String): String? = getAttributeNodeNS(ANDROID, name)?.value

private fun Element.children(tag: String? = null): List<Element> =
    (0..<childNodes.length)
        .map { childNodes.item(it) }
        .filterIsInstance<Element>()
        .filter { tag == null || it.tagName == tag }

private const val ANDROID = "http://schemas.android.com/apk/res/android"
