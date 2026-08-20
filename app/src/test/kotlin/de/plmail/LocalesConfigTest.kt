package de.plmail

import de.plmail.core.data.AppLanguage
import java.io.File
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.w3c.dom.Element

/**
 * The per-app language list, against the translations that actually exist.
 *
 * **The failure this exists to catch is silent in every direction.** A locale listed in
 * `locales_config.xml` with no `values-` directory behind it is offered by Android's own language
 * picker and then renders in English, because resource resolution falls back rather than failing —
 * so the user picks a language, the app changes nothing, and there is no error anywhere. A
 * translation added without a line here is the mirror image: the strings ship and nothing can
 * select them. And an `AppLanguage` member without either is a button on the settings screen that
 * does nothing at all.
 *
 * So all three lists are read and compared rather than any one of them being trusted: the merged
 * manifest, for the `android:localeConfig` attribute; the XML it names, for the locales; and the
 * source tree, for the `values-` directories every module contributes. The source tree rather than
 * the merged resources, because that is where somebody adds a translation, and the point is to fail
 * on the commit that forgets the other half.
 *
 * The merged manifest is found the way [CalendarLauncherManifestTest] finds it, and that file
 * carries the explanation of why.
 */
class LocalesConfigTest {

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

        parse(File(path).absoluteFile)
    }

    @Test
    fun `the application declares a locale config`() {
        // Without it there is no plMail entry under Settings -> Apps -> Language
        // at all, and the app's own picker becomes the only place the choice
        // exists -- which is the version of this feature that looks finished.
        assertEquals(
            "@xml/locales_config",
            manifest
                .children("application")
                .single()
                .getAttributeNodeNS(ANDROID, "localeConfig")
                ?.value,
            "android:localeConfig must name the locale list or the system offers no language entry",
        )
    }

    @Test
    fun `the locale config lists exactly the languages the app is translated into`() {
        assertEquals(
            translatedLanguages(),
            configuredLocales(),
            "a locale here with no values- directory is a language that silently renders in " +
                "English; a translation with no locale here cannot be selected at all",
        )
    }

    @Test
    fun `every language the picker offers is one the locale config carries`() {
        // AppLanguage.SYSTEM is the absence of a choice and has no locale.
        val offered = AppLanguage.entries.map { it.tag }.filter { it.isNotEmpty() }.toSet()

        assertEquals(
            configuredLocales(),
            offered,
            "the settings picker and the system picker must offer the same languages",
        )
    }

    /** The locales the shipped config file names, as bare tags. */
    private fun configuredLocales(): Set<String> {
        val file = File(LOCALES_CONFIG).absoluteFile
        assertTrue(file.isFile, "expected the locale config at $file")

        return parse(file)
            .children("locale")
            .map { checkNotNull(it.getAttributeNodeNS(ANDROID, "name")?.value) }
            .toSet()
    }

    /**
     * The languages this app actually has strings for, from the resource directories themselves.
     *
     * `en` is added rather than found: English lives in the unqualified `values/` because it is the
     * base resources, so there is no `values-en` anywhere to discover — and a test that only looked
     * for qualified directories would demand English be removed from the config file.
     *
     * Only the language subtag is kept, so a future `values-de-rAT` does not read as a second
     * language. Only `src/main` is walked, because a translation under `src/test` or a build
     * directory is not one that ships.
     */
    private fun translatedLanguages(): Set<String> {
        // canonicalFile and not absoluteFile: the latter keeps the ".." in the
        // path, so the walk's own root is a directory named ".." and the dot
        // filter below throws the whole tree away before it starts -- which
        // looks exactly like a repository with no translations in it.
        val root = File("..").canonicalFile
        assertTrue(
            File(root, "settings.gradle.kts").isFile,
            "expected the repository root at $root",
        )

        val qualified =
            root
                .walkTopDown()
                // Build outputs carry a copy of every module's resources, and
                // the dot directories carry Gradle's caches -- both would be
                // walked for minutes and neither is where a translation is added.
                .onEnter { it.name != "build" && !it.name.startsWith(".") }
                .filter { it.isDirectory && it.name.startsWith("values-") }
                .filter {
                    it.parentFile?.name == "res" && it.parentFile?.parentFile?.name == "main"
                }
                .mapNotNull { LANGUAGE.matchEntire(it.name)?.groupValues?.get(1) }

        return (qualified + "en").toSet()
    }

    private fun parse(file: File): Element =
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(file)
            .documentElement

    private fun Element.children(tag: String): List<Element> =
        (0..<childNodes.length)
            .map { childNodes.item(it) }
            .filterIsInstance<Element>()
            .filter { it.tagName == tag }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"

        /** Relative to the module directory, which is a test task's working directory. */
        const val LOCALES_CONFIG = "src/main/res/xml/locales_config.xml"

        /** See [CalendarLauncherManifestTest] for why this is a path and not a dotted name. */
        const val TEST_CONFIG = "com/android/tools/test_config.properties"

        /**
         * `values-de`, but not `values-night`, `values-w600dp` or `values-v34`.
         *
         * A resource qualifier is only a language when it is two or three letters, which is what
         * separates the translations from the dozen other things that share the directory prefix.
         */
        val LANGUAGE = Regex("""values-([a-z]{2,3})(-r[A-Z]{2})?""")
    }
}
