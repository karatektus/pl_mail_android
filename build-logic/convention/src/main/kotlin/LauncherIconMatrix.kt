import groovy.json.JsonSlurper
import java.io.File
import java.io.StringReader
import java.util.Locale
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Attr
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource

/**
 * Every launcher icon the app can wear, as data, and the pure functions that turn it into files.
 *
 * No Gradle in here on purpose: [GenerateLauncherIcons] reads the inputs and writes the outputs,
 * and everything between is a function of the table and the templates. See
 * `LauncherIconsConventionPlugin` for why this exists at all.
 *
 * ## The contract, which is `tools/logo-paints.json` and `app/src/launcher/motif/`
 *
 * A **motif** is one icon design (`pl`, `blue-horn`, …) and a **paint** is one way of colouring it:
 * `original`, the motif's own design, or one of the thirty-two logo colourways applied by that
 * motif's recipe. The recipes were settled on the design canvas and are baked into the table — a
 * paint is simply a value per named **part** of the motif, plus the `background`. A value is
 * `#rrggbb`, `null` for "this part is not drawn in this paint", or `{"ramp": [stops]}`, a linear
 * gradient: from (8,6) to (40,42) in the path's own coordinates for a glyph part, which is the
 * mark's 48 grid, and from (18,18) to (90,90) on the 108 canvas for the background.
 *
 * A template is a VectorDrawable whose paint is *named* rather than given, through attributes in
 * the `urn:plmail:paint` namespace: `paint:fill` and `paint:stroke` become `android:fillColor` and
 * `android:strokeColor`, or an inline `<aapt:attr>` gradient for a ramp, and an element carrying
 * `paint:if` is left out when the part it names is null. Everything else in a template — geometry,
 * transforms, stroke widths — is copied through untouched, which is what keeps the drawings the
 * ones that were pixel-checked against the approved design.
 *
 * **The checks here are strict and fail the build**, because every one of them guards a mismatch
 * that would otherwise ship silently: a part the table paints and no template draws is a recipe
 * with no effect, a part a template draws and the table does not paint is a transparent hole, and a
 * stray template is a motif nobody can pick. A launcher icon is not visible from any screen in the
 * app, so the build is the only place any of this can be caught.
 */
internal object LauncherIconMatrix {

    /** The product default, and the only launcher entry a fresh install has. */
    const val DEFAULT_MOTIF = "pl"
    const val DEFAULT_PAINT = "berry"

    /** Every motif's own design. The pl mark has none of its own — see [icons]. */
    const val ORIGINAL = "original"

    const val ANDROID = "http://schemas.android.com/apk/res/android"
    const val AAPT = "http://schemas.android.com/aapt"
    const val PAINT = "urn:plmail:paint"
    private const val XMLNS = "http://www.w3.org/2000/xmlns/"

    /** The two gradient axes the table's `about` names. Changing one is a design change. */
    private val GLYPH_AXIS = listOf("8", "6", "40", "42")
    private val BACKGROUND_AXIS = listOf("18", "18", "90", "90")

    private val HEX = Regex("#[0-9a-fA-F]{6}")
    private val WIRE = Regex("[a-z0-9]+(-[a-z0-9]+)*")

    sealed interface Paint {
        data class Solid(val hex: String) : Paint

        data class Ramp(val stops: List<String>) : Paint
    }

    /** One motif: its parts, and every paint as part → value (null: not drawn). */
    class Motif(
        val wire: String,
        val parts: List<String>,
        val paints: Map<String, Map<String, Paint?>>,
    )

    /** One icon: the pair, and the names it goes by in the manifest and in resources. */
    data class Icon(val motif: String, val paint: String, val alias: String) {
        val isDefault: Boolean
            get() = motif == DEFAULT_MOTIF && paint == DEFAULT_PAINT

        /** `ic_launcher_blue_horn_ocean`: the adaptive icon, and the stem of its two layers. */
        val resource: String
            get() = "ic_launcher_${motif.resourceName()}_${paint.resourceName()}"

        /**
         * What the alias wears. The default is the application's own committed icon, never a
         * generated copy of it — see `LauncherIconsConventionPlugin`.
         */
        val mipmap: String
            get() = if (isDefault) "@mipmap/ic_launcher" else "@mipmap/$resource"
    }

    /** `tools/logo-paints.json`, parsed and checked. */
    fun readTable(file: File): List<Motif> {
        @Suppress("UNCHECKED_CAST") val root = JsonSlurper().parse(file) as Map<String, Any?>
        check(root["version"] == 1) { "$file: expected version 1, got ${root["version"]}" }

        @Suppress("UNCHECKED_CAST")
        val motifs =
            (root["motifs"] as List<Map<String, Any?>>).map { motif ->
                val wire = motif["wire"] as String
                check(WIRE.matches(wire)) { "motif '$wire' is not a wire name" }

                val parts = motif["parts"] as List<String>

                val paints =
                    (motif["paints"] as Map<String, Map<String, Any?>>).mapValues { (paint, values)
                        ->
                        check(WIRE.matches(paint)) { "$wire: paint '$paint' is not a wire name" }
                        check(values.keys == parts.toSet() + "background") {
                            "$wire/$paint paints ${values.keys}, the motif's parts are $parts"
                        }
                        values.mapValues { (part, value) -> paint(value, "$wire/$paint/$part") }
                    }

                paints.forEach { (paint, values) ->
                    checkNotNull(values["background"]) { "$wire/$paint has no background" }
                }

                Motif(wire, parts, paints)
            }

        val default = motifs.singleOrNull { it.wire == DEFAULT_MOTIF }
        checkNotNull(default) { "$file has no '$DEFAULT_MOTIF' motif; it is the product default" }

        // The pl mark's "original" is the default colourway, not a design of its own: berry is
        // what the mark looked like before there was a choice. So `pl` + `original` resolves to
        // the berry alias rather than getting a second one that would have to look the same.
        // Asserted rather than assumed, because the resolver in the app relies on it.
        check(default.paints[ORIGINAL] == default.paints[DEFAULT_PAINT]) {
            "$DEFAULT_MOTIF/$ORIGINAL and $DEFAULT_MOTIF/$DEFAULT_PAINT differ in the table, " +
                "but the app resolves the one to the other"
        }

        motifs
            .filter { it.wire != DEFAULT_MOTIF }
            .forEach { check(ORIGINAL in it.paints) { "${it.wire} has no '$ORIGINAL' paint" } }

        return motifs
    }

    private fun paint(value: Any?, where: String): Paint? =
        when (value) {
            null -> null
            is String -> {
                check(HEX.matches(value)) { "$where: '$value' is not #rrggbb" }
                Paint.Solid(value.lowercase())
            }
            is Map<*, *> -> {
                val stops = (value["ramp"] as? List<*>)?.map { it as? String }
                check(
                    stops != null && stops.size >= 2 && stops.all { it != null && HEX.matches(it) }
                ) {
                    "$where: a ramp is {\"ramp\": [#rrggbb, …]}, got $value"
                }
                Paint.Ramp(stops.map { it!!.lowercase() })
            }
            else -> error("$where: '$value' is neither a colour, a ramp nor null")
        }

    /**
     * One entry per alias, in manifest order.
     *
     * Every motif in every paint, except the pl mark's `original` — see [readTable]. That is 32
     * aliases for the pl mark, which keep the names they had before there were motifs, and 33 for
     * every other.
     */
    fun icons(motifs: List<Motif>, namespace: String): List<Icon> {
        val icons = motifs.flatMap { motif ->
            motif.paints.keys
                .filterNot { motif.wire == DEFAULT_MOTIF && it == ORIGINAL }
                .map { paint -> Icon(motif.wire, paint, alias(namespace, motif.wire, paint)) }
        }

        check(icons.count { it.isDefault } == 1) { "no $DEFAULT_MOTIF/$DEFAULT_PAINT in the table" }

        // A collision would be two icons switching one component, and one resource name for two
        // drawings; neither is visible in anything generated.
        check(icons.map { it.alias }.toSet().size == icons.size) { "two icons share an alias" }
        check(icons.map { it.resource }.toSet().size == icons.size) { "two icons share a resource" }

        return icons
    }

    /**
     * `de.plmail.LogoLauncherOcean` for the pl mark, `de.plmail.LogoLauncherBlueHornOcean` for the
     * rest.
     *
     * The pl mark's aliases keep exactly the names they had when the colourway was the only choice:
     * an installed phone has one of them enabled, the enabled state is stored against the name, and
     * a renamed alias would be a phone that loses its icon in an update.
     *
     * The class half is spelled from the **namespace**, never the applicationId — see
     * `LauncherIcon` in the app for what getting that backwards costs.
     */
    fun alias(namespace: String, motif: String, paint: String): String =
        "$namespace.LogoLauncher" +
            (if (motif == DEFAULT_MOTIF) "" else motif.pascal()) +
            paint.pascal()

    // ------------------------------------------------------------------ painting

    /**
     * [template] painted with [paint]: a finished VectorDrawable, as text.
     *
     * Parsed afresh for every paint rather than cloned, because it is cheap and a DOM mutated by
     * one paint must never leak into the next.
     */
    fun paintTemplate(template: String, motif: Motif, paint: String, header: String): String {
        val parts = motif.paints.getValue(paint)
        val document = parse(template)
        val root = document.documentElement
        var ramps = false

        // Both directions, and over the whole template rather than what survives this paint: a
        // part the table paints and no template draws is a recipe with no effect, and a part the
        // template draws that the table does not list is a hole in every paint.
        val named =
            root.descendants().flatMap { element ->
                element.attributeList().filter { it.namespaceURI == PAINT }.map { it.value }
            }
        check(named.toSet() == motif.parts.toSet()) {
            "${motif.wire}.xml paints ${named.toSet().sorted()}, " +
                "the table lists ${motif.parts.sorted()}"
        }

        fun part(element: Element, attribute: String): String? =
            element.getAttributeNS(PAINT, attribute).takeIf { it.isNotEmpty() }

        fun visit(element: Element) {
            part(element, "if")?.let { name ->
                if (parts[name] == null) {
                    element.parentNode.removeChild(element)
                    return
                }
            }

            for ((attribute, target) in listOf("fill" to "fillColor", "stroke" to "strokeColor")) {
                val name = part(element, attribute) ?: continue

                when (val value = parts[name]) {
                    null ->
                        error(
                            "${motif.wire}/$paint leaves '$name' undrawn, but ${motif.wire}.xml " +
                                "paints it unconditionally; mark that element paint:if=\"$name\""
                        )
                    is Paint.Solid -> element.setAttributeNS(ANDROID, "android:$target", value.hex)
                    is Paint.Ramp -> {
                        ramps = true
                        element.appendChild(
                            gradient(document, "android:$target", value, GLYPH_AXIS)
                        )
                    }
                }
            }

            element
                .attributeList()
                .filter { it.namespaceURI == PAINT }
                .forEach(element::removeAttributeNode)
            element.children().forEach(::visit)
        }

        visit(root)

        root.removeAttributeNS(XMLNS, "paint")
        if (ramps) root.setAttributeNS(XMLNS, "xmlns:aapt", AAPT)

        return serialize(document, header)
    }

    /** A ramp background: the 108 canvas filled corner to corner. A hex one is a colour. */
    fun backgroundVector(ramp: Paint.Ramp, header: String): String {
        val document =
            parse(
                """<vector xmlns:android="$ANDROID" xmlns:aapt="$AAPT" android:width="108dp"
                android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
                <path android:pathData="M0,0 H108 V108 H0 Z" /></vector>"""
            )
        val path = document.documentElement.children().single()
        path.appendChild(gradient(document, "android:fillColor", ramp, BACKGROUND_AXIS))
        return serialize(document, header)
    }

    private fun gradient(
        document: Document,
        attribute: String,
        ramp: Paint.Ramp,
        axis: List<String>,
    ): Element {
        val holder = document.createElementNS(AAPT, "aapt:attr")
        holder.setAttribute("name", attribute)

        val gradient = document.createElementNS(null, "gradient")
        gradient.setAttributeNS(ANDROID, "android:type", "linear")
        gradient.setAttributeNS(ANDROID, "android:startX", axis[0])
        gradient.setAttributeNS(ANDROID, "android:startY", axis[1])
        gradient.setAttributeNS(ANDROID, "android:endX", axis[2])
        gradient.setAttributeNS(ANDROID, "android:endY", axis[3])

        ramp.stops.forEachIndexed { index, stop ->
            val item = document.createElementNS(null, "item")
            item.setAttributeNS(ANDROID, "android:offset", offset(index, ramp.stops.size))
            item.setAttributeNS(ANDROID, "android:color", stop)
            gradient.appendChild(item)
        }

        holder.appendChild(gradient)
        return holder
    }

    /** Evenly spaced, `0` to `1`, four places: `0.1667` for the second of seven. */
    private fun offset(index: Int, count: Int): String =
        "%.4f".format(Locale.ROOT, index.toDouble() / (count - 1)).trimEnd('0').trimEnd('.')

    // ---------------------------------------------------------- the default's guard

    /**
     * Whether the committed default icon and the pl template, painted in the default colourway, are
     * the same drawing.
     *
     * They have to be, and there are two copies. `ic_launcher_foreground.xml` is the application's
     * own icon and what the default alias wears; `motif/pl.xml` is what the other thirty-one
     * colourways of the mark are painted from. If the two ever drew different letters, a phone
     * switching from berry to ocean would see the mark itself change, not just its colour — so the
     * build compares them, stroke by stroke, rather than trusting them to have been edited
     * together.
     */
    fun sameDrawing(committed: String, painted: String): Boolean =
        strokes(parse(committed)) == strokes(parse(painted))

    private fun strokes(document: Document): List<String> {
        val signatures = mutableListOf<String>()

        fun visit(element: Element, transforms: String) {
            when (element.tagName) {
                "group" -> {
                    val transform =
                        listOf(
                                "translateX",
                                "translateY",
                                "scaleX",
                                "scaleY",
                                "rotation",
                                "pivotX",
                                "pivotY",
                            )
                            .joinToString(",") { number(element.getAttributeNS(ANDROID, it)) }
                    element.children().forEach { visit(it, "$transforms/$transform") }
                }
                "path" -> {
                    fun attribute(name: String) = element.getAttributeNS(ANDROID, name).lowercase()

                    val fill = attribute("fillColor").takeUnless { it == "#00000000" }.orEmpty()
                    signatures +=
                        listOf(
                                transforms,
                                attribute("pathData")
                                    .replace(',', ' ')
                                    .split(Regex("\\s+"))
                                    .joinToString(" ")
                                    .trim(),
                                fill,
                                attribute("strokeColor"),
                                number(attribute("strokeWidth")),
                                attribute("strokeLineCap"),
                                attribute("strokeLineJoin"),
                            )
                            .joinToString("|")
                }
                else -> element.children().forEach { visit(it, transforms) }
            }
        }

        visit(document.documentElement, "")
        return signatures
    }

    private fun number(value: String): String = value.toFloatOrNull()?.toString() ?: value

    // ------------------------------------------------------------------- the rest

    fun adaptiveIcon(icon: Icon, background: String, monochrome: String, header: String): String =
        listOf(
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>",
                header,
                "<adaptive-icon xmlns:android=\"$ANDROID\">",
                "    <background android:drawable=\"$background\" />",
                "    <foreground android:drawable=\"@drawable/${icon.resource}_foreground\" />",
                "    <monochrome android:drawable=\"$monochrome\" />",
                "</adaptive-icon>",
            )
            .joinToString("\n", postfix = "\n")

    /**
     * The aliases, as a manifest overlay the merger folds into `src/main/AndroidManifest.xml`.
     *
     * The shape of each one is the shape the aliases always had; the reasons for every attribute
     * are on the block comment in the main manifest, which is where somebody reading the manifest
     * will look for them.
     *
     * The comment goes **inside** `<application>`, not above `<manifest>`. The merger builds the
     * merged manifest on top of the overlay, so a document-level comment here becomes the first
     * thing in the merged file and describes all of it as generated. Nor does the order of the
     * aliases matter: the merger moves every `<activity-alias>` to follow the activity it targets
     * (`PostValidator.reOrderActivityAlias`), because the platform's parser resolves a target only
     * against activities it has already read.
     */
    fun manifest(icons: List<Icon>, comment: List<String>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<manifest xmlns:android=\"$ANDROID\">\n")
        append("    <application>\n")
        append("        <!--\n")
        comment.forEach { append("          ").append(it).append('\n') }
        append("        -->\n")
        icons.forEach { icon ->
            append('\n')
            append("        <activity-alias\n")
            append("            android:name=\"${icon.alias}\"\n")
            append("            android:enabled=\"${icon.isDefault}\"\n")
            append("            android:exported=\"true\"\n")
            append("            android:icon=\"${icon.mipmap}\"\n")
            append("            android:roundIcon=\"${icon.mipmap}\"\n")
            append("            android:targetActivity=\".MainActivity\">\n")
            append("            <intent-filter>\n")
            append("                <action android:name=\"android.intent.action.MAIN\" />\n")
            append(
                "                <category android:name=\"android.intent.category.LAUNCHER\" />\n"
            )
            append("            </intent-filter>\n")
            append("        </activity-alias>\n")
        }
        append("    </application>\n</manifest>\n")
    }

    fun kotlinTable(icons: List<Icon>, namespace: String, header: String): String = buildString {
        fun entry(icon: Icon) =
            "LauncherIcon(\"${icon.motif}\", \"${icon.paint}\", \"${icon.alias}\")"

        append(header).append('\n')
        append("package $namespace\n\n")
        append("/** The icon a fresh install wears, and the one the manifest enables. */\n")
        append("internal val DEFAULT_LAUNCHER_ICON: LauncherIcon =\n")
        append("    ${entry(icons.single { it.isDefault })}\n\n")
        append(
            "/** Every launcher icon compiled into this APK, one per alias, in manifest order. */\n"
        )
        append("internal val LAUNCHER_ICONS: List<LauncherIcon> =\n")
        append("    listOf(\n")
        icons.forEach { append("        ").append(entry(it)).append(",\n") }
        append("    )\n")
    }

    // ------------------------------------------------------------------ XML plumbing

    private fun parse(text: String): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        return factory.newDocumentBuilder().parse(InputSource(StringReader(text)))
    }

    /**
     * The document as indented XML, one attribute per line, comments and blank text dropped.
     *
     * Written by hand rather than through a `Transformer` so the output is the same on every JDK:
     * this file is an input to aapt2 and to the build cache, and an identity transform is free to
     * change its namespace fix-up or its line endings between releases.
     */
    private fun serialize(document: Document, header: String): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append(header).append('\n')

        fun write(element: Element, depth: Int) {
            val indent = "    ".repeat(depth)
            append(indent).append('<').append(element.tagName)

            // Namespace declarations first, the rest in the DOM's stable (sorted) order.
            val attributes =
                element.attributeList().sortedBy { if (it.namespaceURI == XMLNS) 0 else 1 }
            attributes.forEach {
                append('\n')
                    .append(indent)
                    .append("    ")
                    .append(it.name)
                    .append("=\"")
                    .append(escape(it.value))
                    .append('"')
            }

            val text =
                (0 until element.childNodes.length)
                    .map { element.childNodes.item(it) }
                    .filter {
                        it.nodeType == Node.TEXT_NODE || it.nodeType == Node.CDATA_SECTION_NODE
                    }
            check(text.all { it.nodeValue.isBlank() }) { "unexpected text in <${element.tagName}>" }

            val children = element.children()
            if (children.isEmpty()) {
                append(" />\n")
            } else {
                append(">\n")
                children.forEach { write(it, depth + 1) }
                append(indent).append("</").append(element.tagName).append(">\n")
            }
        }

        write(document.documentElement, 0)
    }

    private fun escape(value: String) =
        value.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")

    private fun Element.children(): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>()

    private fun Element.descendants(): List<Element> =
        listOf(this) + children().flatMap { it.descendants() }

    private fun Element.attributeList(): List<Attr> =
        (0 until attributes.length).map { attributes.item(it) as Attr }

    private fun String.pascal(): String =
        split('-').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }

    /** aapt takes `[a-z0-9_]` and refuses a dash outright. */
    private fun String.resourceName(): String = replace('-', '_')
}
