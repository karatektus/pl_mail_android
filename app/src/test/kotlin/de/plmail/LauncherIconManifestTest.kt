package de.plmail

import java.io.File
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.w3c.dom.Element

/**
 * The launcher-icon aliases, read out of the manifest the build actually produces.
 *
 * The same argument `CalendarLauncherManifestTest` makes, and the same merged file: every claim
 * this feature rests on is a manifest attribute, and every one of them fails silently. An alias
 * missing its `android:icon` shows the default icon and looks like a sync that has not run. Two
 * aliases enabled ships two icons. **None enabled ships an app with no way to open it** — a build
 * that installs, passes every other test, and cannot be launched.
 *
 * The aliases are generated at build time into a manifest overlay, one per motif and paint, and the
 * merged manifest is the first place they exist beside the activity they target — which is why this
 * reads that and not anything the generator wrote. A generator gets every alias identically right
 * or identically wrong, so what this checks is the *shape* it produces and, above all, the two
 * places where one alias is deliberately unlike the rest: the default's `enabled` and the default's
 * icon. And that the overlay landed at all, after `MainActivity`, where the platform's parser needs
 * it.
 *
 * It also asserts the thing that is not generated at all — that `MainActivity` has no launcher
 * filter of its own. That is what makes the aliases the only launcher entries, and it is the reason
 * switching the icon never disables the component this process runs in.
 */
class LauncherIconManifestTest {

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

        // See CalendarLauncherManifestTest: the path AGP writes is relative to
        // the module directory, which is a test task's working directory.
        val file = File(path).absoluteFile
        assertTrue(file.isFile, "expected a merged manifest at $file")

        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(file)
            .documentElement
    }

    @Test
    fun `every icon in the table has an alias, and every alias is in the table`() {
        // Both directions. An icon with no alias is a choice the app resolves
        // and then cannot wear; an alias with no icon is a launcher entry nothing
        // can ever turn off again, because the switch only knows about aliases
        // it has a table entry for. One generator writes both, from one file, so
        // this failing means the overlay did not reach the merged manifest.
        assertEquals(LauncherIcon.all.map { it.alias }.toSet(), logoAliases().keys)
        assertEquals(LauncherIcon.all.size, logoAliases().size)
    }

    @Test
    fun `exactly one alias is enabled in the manifest, and it is the default`() {
        val enabled = logoAliases().filterValues { it.android("enabled") == "true" }.keys

        // Not "at least one". Two would be two icons on every fresh install, and
        // zero would be an app that installs and cannot be opened -- which is
        // also the state the switching code must never pass through, and it
        // cannot, because this is where it starts from.
        assertEquals(setOf(LauncherIcon.Default.alias), enabled)

        // Stated explicitly rather than left to follow from the line above: an
        // absent android:enabled defaults to *true* for a component with an
        // intent filter, so a generator that stopped writing the attribute would
        // ship three hundred launcher icons and this is the assertion that says so.
        logoAliases()
            .filterKeys { it != LauncherIcon.Default.alias }
            .forEach { (name, alias) -> assertEquals("false", alias.android("enabled"), name) }
    }

    @Test
    fun `every alias is exported and points at the mail activity`() {
        logoAliases().forEach { (name, alias) ->
            // The launcher is another app, and it is the *alias's* exported flag
            // the permission check reads.
            assertEquals("true", alias.android("exported"), name)

            // Every one is a door into the same activity, so they inherit
            // its affinity and its singleTask and open the same one mail task.
            // An alias cannot override either -- see the manifest -- which here
            // is the behaviour that was wanted rather than a limitation.
            assertEquals("$NAMESPACE.MainActivity", alias.android("targetActivity"), name)
        }
    }

    @Test
    fun `every alias carries a launcher filter, because nothing else does now`() {
        logoAliases().forEach { (name, alias) ->
            val filters =
                alias.children("intent-filter").map { filter ->
                    filter.children("action").map { it.android("name") } to
                        filter.children("category").map { it.android("name") }
                }

            assertTrue(
                filters.any { (actions, categories) ->
                    MAIN in actions && LAUNCHER in categories
                },
                "$name carries no MAIN + LAUNCHER, so no launcher will draw it: $filters",
            )
        }
    }

    @Test
    fun `the mail activity has no launcher filter of its own`() {
        val filters =
            activity("$NAMESPACE.MainActivity").children("intent-filter").map { filter ->
                filter.children("action").map { it.android("name") } to
                    filter.children("category").map { it.android("name") }
            }

        // The whole safety argument in one assertion. With a filter here this
        // activity would be a launcher entry beside whichever alias is enabled --
        // two icons -- and the only way to have one would be to disable the
        // activity the process is running in when the colourway changes.
        assertFalse(
            filters.any { (actions, categories) -> MAIN in actions && LAUNCHER in categories },
            "MainActivity must not be a launcher entry; the aliases carry it: $filters",
        )

        // The deep link stays, and with it the reason this activity is still
        // exported. Losing it while removing the launcher filter would break
        // pairing from a QR code on the same device, silently.
        assertTrue(
            filters.any { (actions, _) -> "android.intent.action.VIEW" in actions },
            "the plmail:// pairing filter has gone missing: $filters",
        )
        assertEquals("true", activity("$NAMESPACE.MainActivity").android("exported"))
    }

    @Test
    fun `every alias names its own icon for both icon attributes`() {
        logoAliases().forEach { (name, alias) ->
            val icon = assertNotNull(alias.android("icon"), "$name declares no icon")

            // roundIcon as well as icon, pointed at the same drawable. Left
            // undeclared it would inherit the application's, which is the
            // *default* icon -- so a launcher that asks for the round icon would
            // show the pl mark in berry whatever the user had chosen, and only on
            // that launcher.
            assertEquals(icon, alias.android("roundIcon"), name)
        }
    }

    @Test
    fun `the default reuses the application's own icon and the rest wear their own`() {
        val aliases = logoAliases()

        // The default is not given a generated copy of the fallback: the
        // application's android:icon, the drawable anything unknown falls back to
        // and this alias are deliberately one asset, so "the default keeps
        // working" holds by construction rather than by three files agreeing.
        assertEquals(
            "@mipmap/ic_launcher",
            aliases.getValue(LauncherIcon.Default.alias).android("icon"),
        )

        LauncherIcon.all
            .filter { it != LauncherIcon.Default }
            .forEach { icon ->
                assertEquals(
                    "@mipmap/ic_launcher_${icon.motif}_${icon.paint}".replace('-', '_'),
                    aliases.getValue(icon.alias).android("icon"),
                    "${icon.motif}/${icon.paint}",
                )
            }
    }

    @Test
    fun `no alias carries a label`() {
        // Unlike CalendarLauncher, which needs one. These are all the mail app,
        // so a label here would be @string/app_name written three hundred more
        // times and three hundred more places to forget it when it changes.
        logoAliases().forEach { (name, alias) -> assertNull(alias.android("label"), name) }
    }

    @Test
    fun `the aliases are declared after the activity they target`() {
        // A manifest requirement rather than a style: the platform's parser
        // resolves targetActivity against the activities it has already read, so
        // an alias above its target is an APK that builds and then fails to
        // install. The aliases arrive from an overlay now, which the merger uses
        // as the base document; it moves each alias after its target itself
        // (PostValidator.reOrderActivityAlias), and this is what says it still
        // does.
        val components = application().children().map { it.tagName to it.android("name") }
        val target = components.indexOf("activity" to "$NAMESPACE.MainActivity")

        LauncherIcon.all.forEach { icon ->
            val alias = components.indexOf("activity-alias" to icon.alias)
            assertTrue(
                target in 0..<alias,
                "${icon.alias} is declared at $alias, target at $target",
            )
        }
    }

    /** The launcher-icon aliases only, by name — the calendar's is an alias too and is not one. */
    private fun logoAliases(): Map<String, Element> =
        application()
            .children("activity-alias")
            .filter { it.android("name")?.startsWith("$NAMESPACE.LogoLauncher") == true }
            .associateBy { checkNotNull(it.android("name")) }

    private fun application(): Element =
        manifest.children("application").singleOrNull()
            ?: error("expected exactly one <application> in the merged manifest")

    private fun activity(name: String): Element =
        application().children("activity").singleOrNull { it.android("name") == name }
            ?: error("no <activity> named $name")

    private fun Element.android(name: String): String? = getAttributeNodeNS(ANDROID, name)?.value

    private fun Element.children(tag: String? = null): List<Element> =
        (0..<childNodes.length)
            .map { childNodes.item(it) }
            .filterIsInstance<Element>()
            .filter { tag == null || it.tagName == tag }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"

        const val MAIN = "android.intent.action.MAIN"
        const val LAUNCHER = "android.intent.category.LAUNCHER"

        /** See CalendarLauncherManifestTest: the namespace, not `BuildConfig.APPLICATION_ID`. */
        const val NAMESPACE = "de.plmail"

        /** A path and not a dotted name. See CalendarLauncherManifestTest. */
        const val TEST_CONFIG = "com/android/tools/test_config.properties"
    }
}
