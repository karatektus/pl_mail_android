package de.plmail.jmap.methods

import de.plmail.jmap.protocol.JmapMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `Appearance/get` — how the user wants plMail to look, everywhere.
 *
 * The JMAP **singleton** pattern: one object with the literal id `"singleton"`, and **no
 * `accountId`**. Appearance is per user, like `PushSubscription`, and a request carrying an account
 * is refused with `invalidArguments` rather than ignored — verified against the 8002 stack, not
 * read out of the PHP.
 *
 * There is no `Appearance/changes` and no push. A theme changed in the browser is seen the next
 * time this is called, which is why the app calls it on sync and on foreground and never polls.
 */
class AppearanceGet : JmapMethod<AppearanceGetResult> {

    override val name = "Appearance/get"

    /**
     * `ids: null` — every object, which for a singleton is the one.
     *
     * Sent explicitly rather than omitted: RFC 8620 §5.1 gives null the meaning "all", and an
     * omitted `ids` is the same thing only by convention.
     */
    override fun arguments(): JsonObject = buildJsonObject { put("ids", JsonNull) }

    override fun decode(json: Json, arguments: JsonObject): AppearanceGetResult =
        json.decodeFromJsonElement(AppearanceGetResult.serializer(), arguments)
}

@Serializable
data class AppearanceGetResult(
    val state: String = "",
    val list: List<Appearance> = emptyList(),
    val notFound: List<String> = emptyList(),
) {
    /** The one object, or null on a server that answered without it. */
    val appearance: Appearance?
        get() = list.firstOrNull()
}

/**
 * The appearance object, as it comes off the wire.
 *
 * Every field is loose — strings for the closed vocabularies, floats for the knobs — because
 * resolving them is `:core:designsystem`'s job and this module has no Android in it. A theme this
 * build has never heard of has to arrive intact and be decided on above, not fail to parse here.
 *
 * [paneBlur] is carried and never rendered. Compose blurs a composable's *own* content and has no
 * backdrop filter, so a "frosted" pane on Android would blur the text written on it rather than the
 * list behind it. Keeping the value means a phone never writes somebody's web-side frosting away.
 *
 * [sidebarDensity], [listDensity] and [readingDensity] are nullable **on purpose and not by
 * accident of decoding**: on this object null is the server's own answer, meaning "this surface
 * follows the global [density]", and it is the only way back from a per-surface override. The
 * server always sends all three keys, so on a `get` there is no absent case to tell apart — the
 * distinction that does matter is in [AppearancePatch], where absent and null are two different
 * instructions.
 */
@Serializable
data class Appearance(
    val id: String = SINGLETON,
    val theme: String? = null,
    val layout: String? = null,
    val accent: String? = null,
    val paneAlpha: Float? = null,
    val paneBlur: Float? = null,
    val radius: Float? = null,
    val density: String? = null,
    val backgroundKind: String? = null,
    val backgroundPreset: String? = null,
    val backgroundSolid: String? = null,
    val scrimAlpha: Float? = null,
    val accountCorner: Boolean? = null,
    val listAvatars: Boolean? = null,
    val previewLines: Int? = null,
    val unreadEmphasis: String? = null,
    val fontFamily: String? = null,
    val fontScale: Float? = null,
    val sidebarDensity: String? = null,
    val listDensity: String? = null,
    val readingDensity: String? = null,
) {
    /**
     * The same object with everything the server reported changed applied on top.
     *
     * The one rule of a clamped write: apply what came back, never what was sent. The server pulls
     * an out-of-range knob to the nearest end and reports the number it used in `updated`, and a
     * client that kept its own value would show a slider at 140% that the server has stored as 100.
     */
    fun with(reported: Map<String, JsonElement>): Appearance {
        fun string(key: String) =
            if (key in reported) (reported[key] as? JsonPrimitive)?.contentOrNull() else null

        fun float(key: String) =
            if (key in reported) (reported[key] as? JsonPrimitive)?.content?.toFloatOrNull()
            else null

        fun int(key: String) =
            if (key in reported) (reported[key] as? JsonPrimitive)?.content?.toIntOrNull() else null

        fun bool(key: String) =
            if (key in reported) (reported[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
            else null

        /**
         * The three nullable densities, where `?:` would be a bug.
         *
         * Every other property here folds an unreported key and a reported null into the same
         * answer, which is right when null is meaningless for that property. For a per-surface
         * density null is the value that says "follow the global density" — so a server that
         * reports `{"listDensity": null}`, because clearing an override was a change the client did
         * not ask for, has to be believed. Written `?:` instead, the override would survive in the
         * client's copy and be re-sent on the next write, which is the surface silently refusing to
         * be cleared.
         */
        fun surfaceDensity(key: String, current: String?) =
            if (key in reported) (reported[key] as? JsonPrimitive)?.contentOrNull() else current

        return copy(
            theme = string("theme") ?: theme,
            layout = string("layout") ?: layout,
            accent = string("accent") ?: accent,
            paneAlpha = float("paneAlpha") ?: paneAlpha,
            paneBlur = float("paneBlur") ?: paneBlur,
            radius = float("radius") ?: radius,
            density = string("density") ?: density,
            backgroundKind = string("backgroundKind") ?: backgroundKind,
            backgroundPreset = string("backgroundPreset") ?: backgroundPreset,
            backgroundSolid = string("backgroundSolid") ?: backgroundSolid,
            scrimAlpha = float("scrimAlpha") ?: scrimAlpha,
            accountCorner = bool("accountCorner") ?: accountCorner,
            listAvatars = bool("listAvatars") ?: listAvatars,
            previewLines = int("previewLines") ?: previewLines,
            unreadEmphasis = string("unreadEmphasis") ?: unreadEmphasis,
            fontFamily = string("fontFamily") ?: fontFamily,
            fontScale = float("fontScale") ?: fontScale,
            sidebarDensity = surfaceDensity("sidebarDensity", sidebarDensity),
            listDensity = surfaceDensity("listDensity", listDensity),
            readingDensity = surfaceDensity("readingDensity", readingDensity),
        )
    }

    companion object {
        const val SINGLETON = "singleton"

        private fun JsonPrimitive.contentOrNull(): String? = if (this is JsonNull) null else content
    }
}

/**
 * `Appearance/set` — a patch of the singleton, and only ever a patch.
 *
 * **Nothing in this app calls it.** The method is modelled because the server has it and this
 * module's job is to describe the protocol, not to decide policy — but the one client that used to
 * send it does not any more. Theming a phone is not a statement about the browser on somebody's
 * desk, and while this was wired up, choosing a darker theme on a train restyled a desktop session.
 * See `AppearanceRepository`, which reads the appearance and layers local choices on top without
 * ever writing one. Before reaching for this, be sure you have a reason the phone gets to decide.
 *
 * `create` and `destroy` are answered with the spec's `singleton` `SetError`, so neither is offered
 * here. What is offered is one `update` of named properties, which matters for a reason that is not
 * obvious: **a property this client never sends is a property it can never overwrite**. The server
 * has seven themes and backgrounds and ink overrides that Android does not render, and sending a
 * whole object back would flatten every one of them to whatever this app happened to resolve.
 *
 * Two server behaviours to build against:
 *
 * - **Picking a `layout` seeds that layout's knob preset** — radius, paneAlpha, paneBlur. That is
 *   what a layout *is* here, the web pane does the same thing client-side, and the seeded values
 *   come back in `updated` like any other change the client did not ask for. Explicit knobs in the
 *   same patch are applied after the preset and win.
 * - **The patch is validated whole.** One refused property refuses all of it and writes nothing, so
 *   a theme sent beside a bad density does not land. Confirmed on the wire.
 *
 * [ifInState] is honoured, and a stale one is a **request-level** `stateMismatch` error rather than
 * a `notUpdated` entry — the whole call fails, which is what a caller has to be ready for.
 */
class AppearanceSet(private val patch: AppearancePatch, private val ifInState: String? = null) :
    JmapMethod<AppearanceSetResult> {

    override val name = "Appearance/set"

    override fun arguments(): JsonObject = buildJsonObject {
        ifInState?.let { put("ifInState", it) }
        put("update", buildJsonObject { put(Appearance.SINGLETON, patch.toJson()) })
    }

    override fun decode(json: Json, arguments: JsonObject): AppearanceSetResult =
        json.decodeFromJsonElement(AppearanceSetResult.serializer(), arguments)
}

/**
 * The properties one write touches, and nothing else.
 *
 * Built rather than constructed so that "unset" and "set to null" stay different things: a null in
 * this map is a real instruction — clear the accent override, put a surface back on the global
 * density — while an absent key means the server keeps whatever it has. That distinction is load
 * bearing for [Builder.sidebarDensity] and its two siblings and is the reason those take a nullable
 * string rather than being split into a setter and a clearer.
 */
class AppearancePatch private constructor(private val fields: Map<String, JsonElement>) {

    val isEmpty: Boolean
        get() = fields.isEmpty()

    /** The property names this patch will write. What a caller reconciles against. */
    val properties: Set<String>
        get() = fields.keys

    fun toJson(): JsonObject = JsonObject(fields)

    class Builder internal constructor() {
        private val fields = linkedMapOf<String, JsonElement>()

        fun theme(wire: String) = apply { fields["theme"] = JsonPrimitive(wire) }

        fun layout(wire: String) = apply { fields["layout"] = JsonPrimitive(wire) }

        fun density(wire: String) = apply { fields["density"] = JsonPrimitive(wire) }

        /** Out of range is clamped by the server and the clamp is reported. Send what was asked. */
        fun paneAlpha(alpha: Float) = apply { fields["paneAlpha"] = JsonPrimitive(alpha) }

        /**
         * The two switches, and the one place a `Boolean` may not become a `String`.
         *
         * `Appearance/set` runs these through `requireBool`, which takes a real JSON `true`/`false`
         * and refuses `"1"`, `"0"` and `"true"` — the spelling the server's own web pane posts,
         * because its form fields hold the string in a DOM node. A client sending `"0"` almost
         * certainly means false and would have been silently given true, so the loose spelling is
         * refused outright rather than coerced. `JsonPrimitive(Boolean)` is what makes that
         * impossible to get wrong here, and it is the reason nothing between the store and this
         * builder carries one of these as text: a boolean that spends any part of its journey as a
         * string is one `toString()` away from failing the whole patch, and the patch is validated
         * whole — so a theme sent beside a stringly-typed switch would not land either.
         */
        fun accountCorner(shown: Boolean) = apply {
            fields["accountCorner"] = JsonPrimitive(shown)
        }

        /** See [accountCorner] — a real JSON boolean, never `"1"`. */
        fun listAvatars(shown: Boolean) = apply { fields["listAvatars"] = JsonPrimitive(shown) }

        /**
         * How many lines of preview a list row shows: none, one or two.
         *
         * Sent as a JSON integer because `requireInt` demands one — `1.0` is refused as surely as
         * `"1"` — and clamped to 0..2 by the server, which reports the number it used.
         */
        fun previewLines(lines: Int) = apply { fields["previewLines"] = JsonPrimitive(lines) }

        fun unreadEmphasis(wire: String) = apply {
            fields["unreadEmphasis"] = JsonPrimitive(wire)
        }

        fun fontFamily(wire: String) = apply { fields["fontFamily"] = JsonPrimitive(wire) }

        /** Clamped to the published `ranges.fontScale` by the server, which reports the clamp. */
        fun fontScale(scale: Float) = apply { fields["fontScale"] = JsonPrimitive(scale) }

        /**
         * A per-surface density override, or null to put the surface back on the global one.
         *
         * **Null here is a value and not an omission**, which is the whole reason this takes a
         * nullable string: a patch that simply left the key out would mean "leave the override
         * alone", so a user who had set the folder list to Compact and then chose "Follow the
         * overall density" would tap a control that wrote nothing. Sending an explicit JSON null is
         * the only way back, and the server reads these three with `array_key_exists` rather than
         * `isset` for exactly that reason.
         */
        fun sidebarDensity(wire: String?) = apply {
            fields["sidebarDensity"] = wire?.let(::JsonPrimitive) ?: JsonNull
        }

        /** See [sidebarDensity]: null clears the override rather than leaving it alone. */
        fun listDensity(wire: String?) = apply {
            fields["listDensity"] = wire?.let(::JsonPrimitive) ?: JsonNull
        }

        /** See [sidebarDensity]: null clears the override rather than leaving it alone. */
        fun readingDensity(wire: String?) = apply {
            fields["readingDensity"] = wire?.let(::JsonPrimitive) ?: JsonNull
        }

        internal fun build() = AppearancePatch(fields.toMap())
    }

    companion object {
        fun build(block: Builder.() -> Unit): AppearancePatch = Builder().apply(block).build()
    }
}

@Serializable
data class AppearanceSetResult(
    val oldState: String? = null,
    val newState: String = "",
    /**
     * Per RFC 8620 §5.3: what the server changed **beyond** what was asked for.
     *
     * An entry of `null` means "applied exactly as sent". An object means the server had its own
     * answer — a clamped slider, a knob seeded by a layout — and that answer is the one to store.
     */
    val updated: Map<String, JsonObject?> = emptyMap(),
    val notUpdated: Map<String, SetError> = emptyMap(),
) {
    /**
     * What the server decided differently for the singleton. Empty when it took the patch as-is.
     */
    val reported: Map<String, JsonElement>
        get() = updated[Appearance.SINGLETON].orEmpty()

    /** Why the write was refused, if it was. */
    val refusal: SetError?
        get() = notUpdated[Appearance.SINGLETON]
}
