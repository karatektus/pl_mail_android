import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Paints every launcher icon — each motif in each paint — and writes the three things that have to
 * agree about them: the resources, the `<activity-alias>` per icon, and the Kotlin table the app
 * switches them by.
 *
 * One task per variant, because that is how AGP's `addGeneratedSourceDirectory` works: it owns the
 * output location and sets it per variant. The four copies do identical work from identical inputs,
 * and the task is cacheable with no variant name anywhere in its inputs or its output, so they
 * share one build-cache entry: a variant generated after another has is a cache hit, and the ones a
 * parallel build starts together each take well under a second.
 *
 * See `LauncherIconsConventionPlugin` for why this is generated at all, and [LauncherIconMatrix]
 * for the contract the table and the templates keep.
 */
@CacheableTask
abstract class GenerateLauncherIcons : DefaultTask() {

    /** `tools/logo-paints.json`: every motif × paint → part colours. */
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val paints: RegularFileProperty

    /** `app/src/launcher/motif/`: one VectorDrawable template per motif, `<wire>.xml`. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val motifs: DirectoryProperty

    /**
     * The committed default icon, read only to prove the pl template still draws the same mark. See
     * [LauncherIconMatrix.sameDrawing].
     */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val defaultForeground: RegularFileProperty

    /** The module's namespace: the package of the Kotlin table and of every alias class name. */
    @get:Input abstract val namespace: Property<String>

    @get:OutputDirectory abstract val resources: DirectoryProperty

    @get:OutputFile abstract val manifest: RegularFileProperty

    @get:OutputDirectory abstract val sources: DirectoryProperty

    @TaskAction
    fun generate() {
        val matrix = LauncherIconMatrix
        val pkg = namespace.get()
        val table = matrix.readTable(paints.get().asFile)
        val icons = matrix.icons(table, pkg)

        val templates = motifs.get().asFile
        val stray =
            templates.listFiles().orEmpty().map { it.name }.toSet() -
                table.map { "${it.wire}.xml" }.toSet()
        check(stray.isEmpty()) { "$templates has templates for no motif in the table: $stray" }

        val res = resources.get().asFile.also(::recreate)
        val drawable = File(res, "drawable").apply { mkdirs() }
        val mipmap = File(res, "mipmap-anydpi").apply { mkdirs() }
        val colours = sortedMapOf<String, String>()

        for (motif in table) {
            val template = File(templates, "${motif.wire}.xml")
            check(template.isFile) {
                "${motif.wire} is in the table and has no template at $template"
            }
            val source = template.readText()

            if (motif.wire == LauncherIconMatrix.DEFAULT_MOTIF) {
                val painted =
                    matrix.paintTemplate(source, motif, LauncherIconMatrix.DEFAULT_PAINT, "")
                check(matrix.sameDrawing(defaultForeground.get().asFile.readText(), painted)) {
                    "${defaultForeground.get().asFile.name} (the default icon) and " +
                        "${template.name} painted ${LauncherIconMatrix.DEFAULT_PAINT} no longer " +
                        "draw the same mark. The default alias wears the first and every other " +
                        "colourway of the mark is painted from the second, so a phone switching " +
                        "between them would see the letters change. Edit them together."
                }
            }

            for (icon in icons.filter { it.motif == motif.wire && !it.isDefault }) {
                val what = "${icon.motif} in ${icon.paint}"

                File(drawable, "${icon.resource}_foreground.xml")
                    .writeText(
                        matrix.paintTemplate(
                            source,
                            motif,
                            icon.paint,
                            header("$what, painted from motif/${template.name}"),
                        )
                    )

                val background =
                    when (val paint = motif.paints.getValue(icon.paint).getValue("background")) {
                        is LauncherIconMatrix.Paint.Solid -> {
                            colours["${icon.resource}_background"] = paint.hex
                            "@color/${icon.resource}_background"
                        }
                        is LauncherIconMatrix.Paint.Ramp -> {
                            File(drawable, "${icon.resource}_background.xml")
                                .writeText(
                                    matrix.backgroundVector(paint, header("the tile under $what"))
                                )
                            "@drawable/${icon.resource}_background"
                        }
                        null -> error("unreachable: the table checks every background is drawn")
                    }

                File(mipmap, "${icon.resource}.xml")
                    .writeText(
                        matrix.adaptiveIcon(
                            icon,
                            background,
                            monochrome(icon.motif),
                            header("the adaptive icon $what, worn by ${icon.alias}"),
                        )
                    )
            }
        }

        File(res, "values")
            .apply { mkdirs() }
            .resolve("ic_launcher_backgrounds.xml")
            .writeText(
                buildString {
                    append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
                    append(header("the solid tile under every icon that has one")).append('\n')
                    append("<resources>\n")
                    colours.forEach { (name, hex) ->
                        append("    <color name=\"$name\">$hex</color>\n")
                    }
                    append("</resources>\n")
                }
            )

        manifest
            .get()
            .asFile
            .apply { parentFile.mkdirs() }
            .writeText(
                matrix.manifest(
                    icons,
                    listOf(
                        "The launcher-icon aliases, one per motif and paint, ${icons.size} of them:",
                        "GENERATED at build time by build-logic's LauncherIconsConventionPlugin",
                        "from tools/logo-paints.json and merged in from an overlay, never committed.",
                        "THE LAUNCHER ICON in src/main/AndroidManifest.xml explains each attribute.",
                    ),
                )
            )

        val kotlin = sources.get().asFile.also(::recreate)
        File(kotlin, pkg.replace('.', '/'))
            .apply { mkdirs() }
            .resolve("LauncherIconTable.kt")
            .writeText(
                matrix.kotlinTable(
                    icons,
                    pkg,
                    "// GENERATED at build time by build-logic's LauncherIconsConventionPlugin from\n" +
                        "// tools/logo-paints.json. Not committed and not to be edited: change the table.",
                )
            )
    }

    /**
     * The themed-icon layer, which is per motif and not per paint.
     *
     * A themed icon is a silhouette the platform tints from the wallpaper, so a paint has nothing
     * to say to it: every colourway of one motif is the same shape. The pl mark keeps the committed
     * `ic_launcher_monochrome` it has always had; the others are committed beside it as
     * `ic_launcher_<motif>_monochrome`. Not checked here — aapt2 fails the link on a reference to a
     * drawable that does not exist, which is the same check made by the tool whose job it is.
     */
    private fun monochrome(motif: String): String =
        if (motif == LauncherIconMatrix.DEFAULT_MOTIF) "@drawable/ic_launcher_monochrome"
        else "@drawable/ic_launcher_${motif.replace('-', '_')}_monochrome"

    private fun header(what: String): String =
        "<!--\n" +
            "  GENERATED at build time by build-logic's LauncherIconsConventionPlugin:\n" +
            "  $what.\n" +
            "  Not committed and not to be edited. Change tools/logo-paints.json or the\n" +
            "  template in app/src/launcher/motif/, and the next build writes this again.\n" +
            "-->"

    /** Output directories are emptied first, so an icon dropped from the table leaves no file. */
    private fun recreate(directory: File) {
        directory.deleteRecursively()
        directory.mkdirs()
    }
}
