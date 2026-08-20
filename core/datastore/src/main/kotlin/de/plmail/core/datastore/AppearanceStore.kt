package de.plmail.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * How the app looks: what the server holds, and what this device has changed since.
 *
 * **Strings and booleans rather than the design system's enums, deliberately.** A store is
 * persistence and the enums are UI; a dependency from here onto `:core:designsystem` would point
 * the wrong way through the module graph, and the thing it would buy — type safety at the edge of a
 * key-value file — is not safety at all, because the file can already hold anything. The names are
 * plMail's own `Theme`, `Layout` and `Density` wire values, so the resolvers that turn these back
 * into enums (`PlMailThemeChoice.fromWire` and friends) are the ones that decode the server's
 * `Appearance` too.
 *
 * **Two records, and the split is the whole design.** [remote] is the server's copy, refreshed from
 * the session hint and `Appearance/get`. [appearance] is this device's *overrides*: a field is
 * non-null there only between the moment the user chose it and the moment the server confirms it,
 * after which it is cleared and the server's answer is what the app reads. That is what makes a
 * theme changed in the browser appear on the phone at all — a permanent local value would win
 * forever — and what keeps a choice made on a plane from being lost when the plane lands.
 *
 * [StoredAppearance.dynamicColor] and [StoredAppearance.reduceTransparency] are the exception and
 * stay local always: Material You is an Android answer to a question the server does not ask, and
 * Android has no system reduce-transparency setting to inherit.
 *
 * [StoredAppearance.syncWithServer] is the third such value and the one that changes what the other
 * two paragraphs mean. Off, the overrides above stop being *pending* and become simply what this
 * device looks like: the repository sends nothing and reads nothing, so [remote] freezes at
 * whatever was last read and the local record sits on top of it for as long as the switch is off.
 * Nothing here enforces that — a store cannot know what a network is doing — but the split is what
 * makes it expressible at all, because "phone differs from browser" is the same shape on disk as
 * "phone has not managed to send yet".
 *
 * **The booleans and the numbers below are typed preferences, never strings, and that is not
 * tidiness.** `Appearance/set` validates `accountCorner` and `listAvatars` with `requireBool` and
 * `previewLines` with `requireInt`, and both refuse the loose spellings — `"1"`, `"true"`, `1.0` —
 * that a value round-tripped through `toString()` would arrive as. The patch is validated whole, so
 * one stringly-typed switch would take the theme sent beside it down with it. Keeping the type from
 * disk to the wire is what makes that unspellable rather than merely avoided.
 *
 * Every field is nullable-by-absence and resolved by the caller, which is what makes an unknown
 * value — a theme this version does not have, a file written by a newer build — degrade to the
 * default instead of crashing at launch. Appearance is not worth a crash loop.
 */
@Singleton
class AppearanceStore @Inject constructor(private val preferences: DataStore<Preferences>) {

    val appearance: Flow<StoredAppearance> =
        preferences.data
            .map { stored ->
                StoredAppearance(
                    theme = stored[THEME],
                    layout = stored[LAYOUT],
                    density = stored[DENSITY],
                    dynamicColor = stored[DYNAMIC_COLOR] ?: false,
                    reduceTransparency = stored[REDUCE_TRANSPARENCY] ?: false,
                    // Absent reads as on. A user who has never seen the switch is
                    // a user whose phone follows the browser, which is what every
                    // install before this one did.
                    syncWithServer = stored[SYNC_WITH_SERVER] ?: true,
                    paneAlpha = stored[PANE_ALPHA],
                    accountCorner = stored[ACCOUNT_CORNER],
                    listAvatars = stored[LIST_AVATARS],
                    previewLines = stored[PREVIEW_LINES],
                    unreadEmphasis = stored[UNREAD_EMPHASIS],
                    fontFamily = stored[FONT_FAMILY],
                    fontScale = stored[FONT_SCALE],
                    sidebarDensity = stored[SIDEBAR_DENSITY]?.asOverride(),
                    listDensity = stored[LIST_DENSITY]?.asOverride(),
                    readingDensity = stored[READING_DENSITY]?.asOverride(),
                )
            }
            // The theme sits above every screen in the app, so a recomposition
            // of the entire tree is what one emission costs. DataStore emits on
            // every write to the file, and this file also holds the credential,
            // the push state and the recent searches -- so without this a sync
            // recording a timestamp would re-theme the app.
            .distinctUntilChanged()

    /** The server's copy, as last read. Empty until the first session or `Appearance/get`. */
    val remote: Flow<RemoteAppearance> =
        preferences.data
            .map { stored ->
                RemoteAppearance(
                    theme = stored[REMOTE_THEME],
                    layout = stored[REMOTE_LAYOUT],
                    density = stored[REMOTE_DENSITY],
                    paneAlpha = stored[REMOTE_PANE_ALPHA],
                    accountCorner = stored[REMOTE_ACCOUNT_CORNER],
                    listAvatars = stored[REMOTE_LIST_AVATARS],
                    previewLines = stored[REMOTE_PREVIEW_LINES],
                    unreadEmphasis = stored[REMOTE_UNREAD_EMPHASIS],
                    fontFamily = stored[REMOTE_FONT_FAMILY],
                    fontScale = stored[REMOTE_FONT_SCALE],
                    // Plain nullable strings here, unlike their local
                    // counterparts: on the server's own copy an absent key and a
                    // null both mean "this surface follows the global density",
                    // so there is no third state to keep apart. The distinction
                    // only exists for an override this device has made and not
                    // yet sent, where "follow" is an instruction.
                    sidebarDensity = stored[REMOTE_SIDEBAR_DENSITY],
                    listDensity = stored[REMOTE_LIST_DENSITY],
                    readingDensity = stored[REMOTE_READING_DENSITY],
                    logoStyle = stored[REMOTE_LOGO_STYLE],
                    state = stored[REMOTE_STATE],
                )
            }
            .distinctUntilChanged()

    /**
     * Records what the server holds.
     *
     * [state] is what `ifInState` on the next write is built from. It is written together with the
     * values it describes rather than separately, because a state paired with the wrong values is
     * how a client convinces itself it is up to date.
     */
    suspend fun setRemote(remote: RemoteAppearance) {
        preferences.edit { stored ->
            stored.put(REMOTE_THEME, remote.theme)
            stored.put(REMOTE_LAYOUT, remote.layout)
            stored.put(REMOTE_DENSITY, remote.density)
            stored.put(REMOTE_PANE_ALPHA, remote.paneAlpha)
            stored.put(REMOTE_ACCOUNT_CORNER, remote.accountCorner)
            stored.put(REMOTE_LIST_AVATARS, remote.listAvatars)
            stored.put(REMOTE_PREVIEW_LINES, remote.previewLines)
            stored.put(REMOTE_UNREAD_EMPHASIS, remote.unreadEmphasis)
            stored.put(REMOTE_FONT_FAMILY, remote.fontFamily)
            stored.put(REMOTE_FONT_SCALE, remote.fontScale)
            stored.put(REMOTE_SIDEBAR_DENSITY, remote.sidebarDensity)
            stored.put(REMOTE_LIST_DENSITY, remote.listDensity)
            stored.put(REMOTE_READING_DENSITY, remote.readingDensity)
            stored.put(REMOTE_LOGO_STYLE, remote.logoStyle)
            stored.put(REMOTE_STATE, remote.state)
        }
    }

    /**
     * Drops every override at once, whether or not the server has ever seen it.
     *
     * What "match the web again" means, and the only way back to it. Every choice made on this
     * phone is an override that outranks the account's value for as long as it exists, so a device
     * that has been styled by hand goes on looking that way however often it re-reads. Dropping the
     * lot is the reset, and it is server-wins by construction rather than by a merge rule somebody
     * has to get right.
     *
     * The three local-only flags are not overrides and are not touched: they answer questions the
     * server does not ask, so there is nothing for it to win.
     */
    suspend fun clearAllOverrides() {
        preferences.edit { stored -> OVERRIDES.values.forEach { key -> stored.remove(key) } }
    }

    /**
     * Whether this device follows the server's appearance at all.
     *
     * Local-only and deliberately so: it is a fact about this phone, and a phone that announced "I
     * have stopped listening" to the account would be telling every other device something none of
     * them can act on.
     */
    suspend fun setSyncWithServer(enabled: Boolean) {
        preferences.edit { it[SYNC_WITH_SERVER] = enabled }
    }

    suspend fun setTheme(wire: String) {
        preferences.edit { it[THEME] = wire }
    }

    suspend fun setLayout(wire: String) {
        preferences.edit { it[LAYOUT] = wire }
    }

    suspend fun setDensity(wire: String) {
        preferences.edit { it[DENSITY] = wire }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        preferences.edit { it[DYNAMIC_COLOR] = enabled }
    }

    suspend fun setReduceTransparency(enabled: Boolean) {
        preferences.edit { it[REDUCE_TRANSPARENCY] = enabled }
    }

    /**
     * How solid a pane is in the boxed layout, 0.5 to 1.
     *
     * Stored as a string rather than a float preference so the whole record reads back as one shape
     * — and because the value that matters is what the *user* chose, which has to survive being
     * overridden by reduced transparency. Storing the override instead would mean turning the
     * accessibility switch off gave everybody 100% rather than the number they had set.
     */
    suspend fun setPaneAlpha(alpha: Float) {
        preferences.edit { it[PANE_ALPHA] = alpha.coerceIn(MIN_ALPHA, 1f).toString() }
    }

    suspend fun setAccountCorner(shown: Boolean) {
        preferences.edit { it[ACCOUNT_CORNER] = shown }
    }

    suspend fun setListAvatars(shown: Boolean) {
        preferences.edit { it[LIST_AVATARS] = shown }
    }

    /**
     * Clamped here as well as on the server, and the two clamps are not redundant.
     *
     * The screen is its own preview: an out-of-range number would be drawn before the server ever
     * saw it, and `previewLines = 5` is five lines of snippet in a list whose whole promise is that
     * every row is the same height. The server's clamp corrects the stored value a round trip
     * later, which is a round trip too late.
     */
    suspend fun setPreviewLines(lines: Int) {
        preferences.edit {
            it[PREVIEW_LINES] = lines.coerceIn(MIN_PREVIEW_LINES, MAX_PREVIEW_LINES)
        }
    }

    suspend fun setUnreadEmphasis(wire: String) {
        preferences.edit { it[UNREAD_EMPHASIS] = wire }
    }

    suspend fun setFontFamily(wire: String) {
        preferences.edit { it[FONT_FAMILY] = wire }
    }

    /** Clamped for the same reason [setPreviewLines] is: this is drawn before it is sent. */
    suspend fun setFontScale(scale: Float) {
        preferences.edit { it[FONT_SCALE] = scale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE) }
    }

    suspend fun setSidebarDensity(override: DensityOverride) {
        preferences.edit { it[SIDEBAR_DENSITY] = override.stored() }
    }

    suspend fun setListDensity(override: DensityOverride) {
        preferences.edit { it[LIST_DENSITY] = override.stored() }
    }

    suspend fun setReadingDensity(override: DensityOverride) {
        preferences.edit { it[READING_DENSITY] = override.stored() }
    }

    private fun MutablePreferences.put(key: Preferences.Key<String>, value: String?) {
        if (value == null) remove(key) else set(key, value)
    }

    private fun MutablePreferences.put(key: Preferences.Key<Boolean>, value: Boolean?) {
        if (value == null) remove(key) else set(key, value)
    }

    private fun MutablePreferences.put(key: Preferences.Key<Int>, value: Int?) {
        if (value == null) remove(key) else set(key, value)
    }

    private fun MutablePreferences.put(key: Preferences.Key<Float>, value: Float?) {
        if (value == null) remove(key) else set(key, value)
    }

    private companion object {
        val THEME = stringPreferencesKey("appearance_theme")
        val LAYOUT = stringPreferencesKey("appearance_layout")
        val DENSITY = stringPreferencesKey("appearance_density")
        val DYNAMIC_COLOR = booleanPreferencesKey("appearance_dynamic_color")
        val REDUCE_TRANSPARENCY = booleanPreferencesKey("appearance_reduce_transparency")
        val SYNC_WITH_SERVER = booleanPreferencesKey("appearance_sync_with_server")
        val PANE_ALPHA = stringPreferencesKey("appearance_pane_alpha")
        val ACCOUNT_CORNER = booleanPreferencesKey("appearance_account_corner")
        val LIST_AVATARS = booleanPreferencesKey("appearance_list_avatars")
        val PREVIEW_LINES = intPreferencesKey("appearance_preview_lines")
        val UNREAD_EMPHASIS = stringPreferencesKey("appearance_unread_emphasis")
        val FONT_FAMILY = stringPreferencesKey("appearance_font_family")
        val FONT_SCALE = floatPreferencesKey("appearance_font_scale")
        val SIDEBAR_DENSITY = stringPreferencesKey("appearance_sidebar_density")
        val LIST_DENSITY = stringPreferencesKey("appearance_list_density")
        val READING_DENSITY = stringPreferencesKey("appearance_reading_density")

        val REMOTE_THEME = stringPreferencesKey("appearance_remote_theme")
        val REMOTE_LAYOUT = stringPreferencesKey("appearance_remote_layout")
        val REMOTE_DENSITY = stringPreferencesKey("appearance_remote_density")
        val REMOTE_PANE_ALPHA = stringPreferencesKey("appearance_remote_pane_alpha")
        val REMOTE_ACCOUNT_CORNER = booleanPreferencesKey("appearance_remote_account_corner")
        val REMOTE_LIST_AVATARS = booleanPreferencesKey("appearance_remote_list_avatars")
        val REMOTE_PREVIEW_LINES = intPreferencesKey("appearance_remote_preview_lines")
        val REMOTE_UNREAD_EMPHASIS = stringPreferencesKey("appearance_remote_unread_emphasis")
        val REMOTE_FONT_FAMILY = stringPreferencesKey("appearance_remote_font_family")
        val REMOTE_FONT_SCALE = floatPreferencesKey("appearance_remote_font_scale")
        val REMOTE_SIDEBAR_DENSITY = stringPreferencesKey("appearance_remote_sidebar_density")
        val REMOTE_LIST_DENSITY = stringPreferencesKey("appearance_remote_list_density")
        val REMOTE_READING_DENSITY = stringPreferencesKey("appearance_remote_reading_density")

        /**
         * The logo colourway, and there is no local counterpart to it anywhere in this file.
         *
         * Every other value here is one half of a pair — the server's copy and this device's
         * override — because every other value is something the Appearance screen can change.
         * `logoStyle` is read-only on the server and has no control on the phone at all: it is
         * picked in the browser, arrives here, and is spent switching a launcher alias. A local key
         * beside this one would be a key nothing could ever write, and its presence would invite
         * somebody to add the setter that makes the launcher icon disagree with the web.
         */
        val REMOTE_LOGO_STYLE = stringPreferencesKey("appearance_remote_logo_style")

        val REMOTE_STATE = stringPreferencesKey("appearance_remote_state")

        /**
         * The overrides, keyed by the wire property they are sent as.
         *
         * A map rather than a `when`, because there are now two callers with opposite needs —
         * "clear the four the server just accepted" and "clear all of them" — and a second `when`
         * listing the same thirteen names is a list that goes out of date the first time a
         * fourteenth property arrives. Absence from this map is what makes the three local-only
         * flags unclearable rather than merely unlisted.
         */
        val OVERRIDES: Map<String, Preferences.Key<*>> =
            mapOf(
                "theme" to THEME,
                "layout" to LAYOUT,
                "density" to DENSITY,
                "paneAlpha" to PANE_ALPHA,
                "accountCorner" to ACCOUNT_CORNER,
                "listAvatars" to LIST_AVATARS,
                "previewLines" to PREVIEW_LINES,
                "unreadEmphasis" to UNREAD_EMPHASIS,
                "fontFamily" to FONT_FAMILY,
                "fontScale" to FONT_SCALE,
                "sidebarDensity" to SIDEBAR_DENSITY,
                "listDensity" to LIST_DENSITY,
                "readingDensity" to READING_DENSITY,
            )

        const val MIN_PREVIEW_LINES = 0
        const val MAX_PREVIEW_LINES = 2

        /**
         * The type-size bounds, and the same numbers the server publishes in `ranges.fontScale`.
         *
         * Narrow on purpose. This scales the app's own type on top of whatever the user has already
         * set system-wide, and the two multiply — a phone at 130% font size with this at 150% is an
         * app whose list rows no longer hold a subject. Android's own accessibility setting is the
         * one that should go large; this is for closing the gap between it and the browser.
         */
        const val MIN_FONT_SCALE = 0.875f
        const val MAX_FONT_SCALE = 1.25f

        /**
         * The floor on translucency.
         *
         * Below about half, text on a pane is being read against whatever is behind it rather than
         * against the pane, and no contrast the palette guarantees still holds. A slider that can
         * reach zero is a slider that can make the app unreadable and then hide the settings screen
         * that would undo it.
         */
        const val MIN_ALPHA = 0.5f
    }
}

/**
 * Appearance as it is on disk: what was chosen *here*, not what it resolves to.
 *
 * Null means "not overridden on this device", which is not the same as the default having been
 * chosen. It is what lets the server's `Appearance` fill in everything the user has not just
 * changed, without having to guess which values were deliberate — and, once a change has been
 * accepted by the server, these go back to null and the server's copy is what the app reads.
 */
data class StoredAppearance(
    val theme: String? = null,
    val layout: String? = null,
    val density: String? = null,
    val dynamicColor: Boolean = false,
    val reduceTransparency: Boolean = false,
    /** See [AppearanceStore.setSyncWithServer]. On for anyone who has never touched the switch. */
    val syncWithServer: Boolean = true,
    val paneAlpha: String? = null,
    val accountCorner: Boolean? = null,
    val listAvatars: Boolean? = null,
    val previewLines: Int? = null,
    val unreadEmphasis: String? = null,
    val fontFamily: String? = null,
    val fontScale: Float? = null,
    val sidebarDensity: DensityOverride? = null,
    val listDensity: DensityOverride? = null,
    val readingDensity: DensityOverride? = null,
) {
    /**
     * Whether this phone has an appearance of its own rather than the account's.
     *
     * Not "still has to reach the server", which is what this meant while the app pushed
     * `Appearance/set`. Nothing is ever sent now, so an override is not pending — it is simply the
     * answer, until [AppearanceStore.clearAllOverrides] takes it away.
     */
    val hasOwnChoices: Boolean
        get() =
            theme != null ||
                layout != null ||
                density != null ||
                paneAlpha != null ||
                accountCorner != null ||
                listAvatars != null ||
                previewLines != null ||
                unreadEmphasis != null ||
                fontFamily != null ||
                fontScale != null ||
                sidebarDensity != null ||
                listDensity != null ||
                readingDensity != null
}

/**
 * A per-surface density this device has chosen, where "chose to follow the global one" is a choice.
 *
 * Three states have to survive the trip to disk and only two of them are values: no override at
 * all, an override naming a density, and an override that says *follow* — which is the only way
 * back from one of the other two. Written as `String?` alone the third would be indistinguishable
 * from the first, and the symptom is precise: somebody sets the folder list to Compact, changes
 * their mind, taps "Follow the overall density", and the control writes nothing at all because a
 * null local value is what "untouched" already means. The wrapper is what makes the outer null and
 * the inner null different questions.
 *
 * [wire] is null for follow, and that is deliberately the same null `AppearancePatch` sends as a
 * JSON null — the two nulls mean the same thing and the value passes through untranslated.
 */
@JvmInline
value class DensityOverride(val wire: String?) {
    companion object {
        /** Put the surface back on the global density. Sent as an explicit JSON null. */
        val Follow = DensityOverride(null)
    }
}

/**
 * The empty string, standing for "follow", because DataStore cannot hold a null.
 *
 * A sentinel rather than a second boolean key beside each density: two keys that must agree is two
 * keys that can disagree, and a half-written pair reads as an override of a density named `""`.
 * Nothing else can ever produce an empty string here — every real value comes from `Density`'s
 * vocabulary — so the sentinel cannot collide with a legitimate one.
 */
private fun DensityOverride.stored(): String = wire.orEmpty()

private fun String.asOverride(): DensityOverride = DensityOverride(ifEmpty { null })

/**
 * The server's appearance, as last read, plus the state token that read came with.
 *
 * [theme] is the **raw** wire value and may be a theme this build cannot draw — `paper` is one
 * today. Resolving it is the design system's job; keeping it verbatim here is what stops the app
 * from writing its own approximation back over the user's choice.
 */
data class RemoteAppearance(
    val theme: String? = null,
    val layout: String? = null,
    val density: String? = null,
    val paneAlpha: String? = null,
    val accountCorner: Boolean? = null,
    val listAvatars: Boolean? = null,
    val previewLines: Int? = null,
    val unreadEmphasis: String? = null,
    val fontFamily: String? = null,
    val fontScale: Float? = null,
    val sidebarDensity: String? = null,
    val listDensity: String? = null,
    val readingDensity: String? = null,
    /**
     * The logo colourway, raw off the wire and possibly one this build has never heard of.
     *
     * Null covers both absences and they are not distinguished here: a server too old to publish
     * the property, and a server that has simply not been read yet. Resolving either to the product
     * default is `:app`'s job — see `LogoStyle.fromWire` — for the same reason [theme] keeps
     * `paper` verbatim rather than storing an approximation of it.
     */
    val logoStyle: String? = null,
    /** `Appearance/get`'s state, for the next write's `ifInState`. Null before the first read. */
    val state: String? = null,
)
