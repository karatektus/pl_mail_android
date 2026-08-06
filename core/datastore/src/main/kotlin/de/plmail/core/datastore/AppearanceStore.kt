package de.plmail.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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
                    paneAlpha = stored[PANE_ALPHA],
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
            stored.put(REMOTE_STATE, remote.state)
        }
    }

    /**
     * Drops the local overrides the server has now accepted.
     *
     * Named by wire property so a write of the theme cannot clear a density the same user changed
     * while it was in flight. Anything not named stays pending and is retried on the next sync,
     * which is what makes a change made offline survive.
     */
    suspend fun clearOverrides(properties: Set<String>) {
        if (properties.isEmpty()) return

        preferences.edit { stored ->
            properties.forEach { property ->
                when (property) {
                    "theme" -> stored.remove(THEME)
                    "layout" -> stored.remove(LAYOUT)
                    "density" -> stored.remove(DENSITY)
                    "paneAlpha" -> stored.remove(PANE_ALPHA)
                }
            }
        }
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

    private fun MutablePreferences.put(key: Preferences.Key<String>, value: String?) {
        if (value == null) remove(key) else set(key, value)
    }

    private companion object {
        val THEME = stringPreferencesKey("appearance_theme")
        val LAYOUT = stringPreferencesKey("appearance_layout")
        val DENSITY = stringPreferencesKey("appearance_density")
        val DYNAMIC_COLOR = booleanPreferencesKey("appearance_dynamic_color")
        val REDUCE_TRANSPARENCY = booleanPreferencesKey("appearance_reduce_transparency")
        val PANE_ALPHA = stringPreferencesKey("appearance_pane_alpha")

        val REMOTE_THEME = stringPreferencesKey("appearance_remote_theme")
        val REMOTE_LAYOUT = stringPreferencesKey("appearance_remote_layout")
        val REMOTE_DENSITY = stringPreferencesKey("appearance_remote_density")
        val REMOTE_PANE_ALPHA = stringPreferencesKey("appearance_remote_pane_alpha")
        val REMOTE_STATE = stringPreferencesKey("appearance_remote_state")

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
    val paneAlpha: String? = null,
) {
    /** Whether anything here still has to reach the server. */
    val hasPendingWrites: Boolean
        get() = theme != null || layout != null || density != null || paneAlpha != null
}

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
    /** `Appearance/get`'s state, for the next write's `ifInState`. Null before the first read. */
    val state: String? = null,
)
