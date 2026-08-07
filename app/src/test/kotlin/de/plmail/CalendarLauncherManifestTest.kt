package de.plmail

import java.io.File
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.w3c.dom.Element

/**
 * The launcher alias, read out of the manifest the build actually produces.
 *
 * **Every claim the feature rests on is a manifest attribute**, and every one of them fails
 * silently. An alias that inherits the wrong task affinity still launches; an alias left enabled
 * ships a second icon to everybody; a `targetActivity` pointed at the wrong class resolves at
 * install time and not at build time. None of that is visible from Kotlin, and the only place the
 * pieces exist together is the merged manifest — the flavour manifests, the library manifests and
 * `src/main`'s are three files until the merger has run.
 *
 * So this reads the merged file rather than the source one. It is found through
 * `com.android.tools.test_config.properties`, which AGP writes onto the unit-test classpath when
 * `isIncludeAndroidResources` is on and which is what Robolectric itself uses to find the same
 * file. That indirection is the point: the path it names is per variant, so this suite runs four
 * times over four merged manifests and the two flavours are checked rather than assumed to match.
 */
class CalendarLauncherManifestTest {

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

        // The path AGP writes is relative to the module directory, which is what
        // Gradle sets a test task's working directory to. Resolved rather than
        // assumed absolute, so this does not silently start looking in whatever
        // directory a future runner happens to start in.
        val file = File(path).absoluteFile
        assertTrue(file.isFile, "expected a merged manifest at $file")

        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(file)
            .documentElement
    }

    private val applicationId: String by lazy { manifest.getAttribute("package") }

    @Test
    fun `the calendar launcher alias exists and is off until asked for`() {
        val alias = alias()

        // Absent means default, and the default for a component with an intent
        // filter is enabled -- which would ship a second icon to every install.
        assertEquals(
            "false",
            alias.android("enabled"),
            "the alias must be disabled in the manifest; the settings toggle is what enables it",
        )

        // The launcher is another app, so this one has to be true. It is the
        // alias's flag that the permission check reads, which is why the
        // activity behind it can stay unexported.
        assertEquals("true", alias.android("exported"))

        assertEquals(
            "$NAMESPACE.CalendarActivity",
            alias.android("targetActivity"),
            "the alias must point at the activity that owns the calendar task",
        )
    }

    @Test
    fun `the alias name is the one the app switches at runtime`() {
        // AppCalendarLauncherIcon builds its ComponentName from a string
        // constant, because android:name resolves against the namespace and not
        // against the applicationId, which carries a flavour and a build-type
        // suffix. This is the assertion that keeps the constant and the
        // manifest from drifting apart.
        assertEquals(AppCalendarLauncherIcon.ALIAS, alias().android("name"))
    }

    @Test
    fun `the alias is a launcher entry in its own right`() {
        val filters =
            alias().children("intent-filter").map { filter ->
                filter.children("action").map { it.android("name") } to
                    filter.children("category").map { it.android("name") }
            }

        assertTrue(
            filters.any { (actions, categories) ->
                "android.intent.action.MAIN" in actions &&
                    "android.intent.category.LAUNCHER" in categories
            },
            "the alias must carry MAIN + LAUNCHER or no launcher will draw it: $filters",
        )
    }

    @Test
    fun `the alias carries its own label and its own icon`() {
        val alias = alias()

        // Resolved to resource references rather than literals at this stage,
        // so what is asserted is that it has its own and not the app's.
        assertNotNull(alias.android("label"))
        assertNotEquals(
            "@string/app_name",
            alias.android("label"),
            "a second icon labelled plMail is two icons called plMail",
        )
        assertNotEquals(
            "@mipmap/ic_launcher",
            alias.android("icon"),
            "the calendar needs a mark of its own or the home screen shows the icon twice",
        )
        assertNotEquals("@mipmap/ic_launcher_round", alias.android("roundIcon"))
    }

    @Test
    fun `the calendar opens in a task of its own`() {
        val calendar = activity("$NAMESPACE.CalendarActivity")
        val mail = activity("$NAMESPACE.MainActivity")

        // A leading colon, so the affinity is expanded against the applicationId
        // at install time: de.plmail:calendar for foss and
        // de.plmail.google:calendar for the other, with .debug folded in. Two
        // flavours side by side on one device therefore cannot share a calendar
        // task. Asserted as the literal because the expansion is the platform's
        // and happens well after this file is written.
        assertEquals(
            ":calendar",
            calendar.android("taskAffinity"),
            "without its own affinity the calendar lands in the mail's task: " +
                "one recents card, one back stack, no split screen",
        )

        // The mail keeps the default, which is the applicationId itself. Stated
        // as a difference rather than a value, because what the feature needs is
        // that the two are not equal.
        assertNotEquals(
            calendar.android("taskAffinity"),
            mail.android("taskAffinity"),
            "the two tasks must not share an affinity",
        )

        // Both singleTask, and that is not a copy-paste: it means each icon
        // brings its own task forward rather than stacking a second copy, and
        // the affinity above is what keeps "its own" true.
        assertEquals("singleTask", calendar.android("launchMode"))

        // Not exported. The launcher never names it -- it names the alias, and
        // the alias's own exported flag is what is checked.
        assertEquals("false", calendar.android("exported"))
    }

    @Test
    fun `the alias is declared after the activity it targets`() {
        // A manifest requirement rather than a style: the merger and the
        // platform both resolve targetActivity against what has already been
        // read, and an alias above its target is a build that fails somewhere
        // less obvious than here.
        val components = application().children().map { it.tagName to it.android("name") }
        val target = components.indexOf("activity" to "$NAMESPACE.CalendarActivity")
        val alias = components.indexOf("activity-alias" to AppCalendarLauncherIcon.ALIAS)

        assertTrue(target in 0..<alias, "expected the activity before the alias, got $components")
    }

    @Test
    fun `the alias belongs to this build's application id`() {
        // The alias's class name is fixed to the namespace while the package it
        // is switched under is the applicationId. This suite runs once per
        // variant, so this is where the two are seen to differ on the flavours
        // where they do.
        assertTrue(
            applicationId.startsWith(NAMESPACE),
            "expected an applicationId under $NAMESPACE, got $applicationId",
        )
    }

    private fun application(): Element =
        manifest.children("application").singleOrNull()
            ?: error("expected exactly one <application> in the merged manifest")

    private fun alias(): Element =
        application().children("activity-alias").singleOrNull {
            it.android("name") == AppCalendarLauncherIcon.ALIAS
        } ?: error("no <activity-alias> named ${AppCalendarLauncherIcon.ALIAS}")

    private fun activity(name: String): Element =
        application().children("activity").singleOrNull { it.android("name") == name }
            ?: error("no <activity> named $name")

    /** An attribute in the Android namespace, or null when it is not declared at all. */
    private fun Element.android(name: String): String? = getAttributeNodeNS(ANDROID, name)?.value

    private fun Element.children(tag: String? = null): List<Element> =
        (0..<childNodes.length)
            .map { childNodes.item(it) }
            .filterIsInstance<Element>()
            .filter { tag == null || it.tagName == tag }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"

        /**
         * The module's namespace, which is what `android:name=".Foo"` resolves against, and
         * deliberately not `BuildConfig.APPLICATION_ID`: those two are the same string in the foss
         * debug build and differ in the other three.
         */
        const val NAMESPACE = "de.plmail"

        /**
         * A path and not a dotted name. The file is `test_config.properties` inside the
         * `com/android/tools` package, and asking the class loader for
         * `com.android.tools.test_config.properties` finds nothing at all — quietly, because a
         * missing resource is a null rather than an error. Robolectric reads the same file by the
         * same path.
         */
        const val TEST_CONFIG = "com/android/tools/test_config.properties"
    }
}
