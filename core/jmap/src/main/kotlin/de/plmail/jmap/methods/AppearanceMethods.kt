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
 * this map is a real instruction — clear the accent override — while an absent key means the server
 * keeps whatever it has.
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
