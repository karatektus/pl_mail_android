import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register

/**
 * Every launcher icon the phone can wear, generated at build time rather than committed.
 *
 * ## Why every combination has to be in the APK
 *
 * The user picks the icon in the browser, in two steps: a **motif** — the pl mark, a horn, a love
 * letter, ten of them — and then a **paint**, which is that motif's own design or one of the
 * thirty-two logo colourways applied by the motif's recipe. The phone follows the choice, and an
 * installed Android app cannot change its own icon: `android:icon` is a resource reference the
 * launcher resolves out of the manifest, in another process, long before any of our code runs. What
 * can be switched is which `<activity-alias>` is offering a launcher entry — `AppLauncherIcon` in
 * the app does that — so every motif in every paint has to exist in the APK as an alias, an
 * adaptive icon and its layers: 329 icons, all but the default generated.
 *
 * ## Why they are generated instead of committed
 *
 * Because the user asked for exactly that — "without saving every icon in every colourway" — and
 * because the committed version had already shown what it costs. The thirty-two colourways of the
 * pl mark alone were a Python script's output: sixty-two resource files, thirty-two aliases pasted
 * between marker comments in the manifest and a generated enum, all of which had to be regenerated
 * and committed together or disagree. Ten motifs of that is close to seven hundred resource files
 * and three hundred pasted aliases whose only content is a table lookup, reviewed as walls of
 * coordinates nobody reads.
 *
 * So the repository holds the two things a person actually edits — `tools/logo-paints.json`, the
 * paint of every part of every motif in every paint, and one VectorDrawable template per motif in
 * `app/src/launcher/motif/` — and [GenerateLauncherIcons] turns them into everything else on every
 * build. The templates sit **outside** `res/` on purpose: in there aapt2 would try to compile them
 * as drawables, and the paint attributes are not Android's.
 *
 * ## How it reaches the build
 *
 * Through AGP's Variant API, three ways, all from one task per variant:
 *
 * - `sources.res.addGeneratedSourceDirectory` — the painted foregrounds, the backgrounds and the
 *   adaptive icons, merged like any other resource directory.
 * - `sources.manifests.addGeneratedManifestFile` — the aliases, as an overlay the manifest merger
 *   folds into `src/main/AndroidManifest.xml`.
 * - `sources.kotlin.addGeneratedSourceDirectory` — `LauncherIconTable.kt`, the list the app
 *   switches by. Generated from the same table in the same action as the aliases, which is what
 *   makes it impossible for the app to name an alias the manifest does not declare.
 *
 * Registered this way rather than as a task some other task depends on, AGP knows which consumer
 * needs which output: resource merging, manifest merging and Kotlin compilation each depend on the
 * generator through the output they read, and nothing has to remember to run it first.
 *
 * ## The default stays exactly what it was
 *
 * The pl mark in berry is the product default and the only alias the manifest enables. It is not
 * generated: its alias wears `@mipmap/ic_launcher`, the application's own committed icon, so the
 * app's `android:icon`, the default alias and the fallback for anything this build does not know
 * are one asset. The generator checks that the committed icon and the pl template still draw the
 * same mark — see [LauncherIconMatrix.sameDrawing] — because they are two copies of it now.
 */
class LauncherIconsConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.withPlugin("com.android.application") {
                extensions
                    .getByType(ApplicationAndroidComponentsExtension::class.java)
                    .onVariants { variant ->
                        val name = "generate${variant.name.replaceFirstChar(Char::uppercaseChar)}"
                        val icons =
                            tasks.register<GenerateLauncherIcons>("${name}LauncherIcons") {
                                description = "Paints every launcher icon for ${variant.name}."
                                paints.set(layout.settingsDirectory.file("tools/logo-paints.json"))
                                motifs.set(layout.projectDirectory.dir("src/launcher/motif"))
                                defaultForeground.set(
                                    layout.projectDirectory.file(
                                        "src/main/res/drawable/ic_launcher_foreground.xml"
                                    )
                                )
                                namespace.set(variant.namespace)
                            }

                        variant.sources.res?.addGeneratedSourceDirectory(
                            icons,
                            GenerateLauncherIcons::resources,
                        )
                        variant.sources.manifests.addGeneratedManifestFile(
                            icons,
                            GenerateLauncherIcons::manifest,
                        )
                        variant.sources.kotlin?.addGeneratedSourceDirectory(
                            icons,
                            GenerateLauncherIcons::sources,
                        )
                    }
            }
        }
    }
}
