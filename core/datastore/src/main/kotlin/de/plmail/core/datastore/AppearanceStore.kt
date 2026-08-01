package de.plmail.core.datastore

import androidx.datastore.core.DataStore
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
 * How the app looks, as the user set it.
 *
 * **Strings and booleans rather than the design system's enums, deliberately.** A store is
 * persistence and the enums are UI; a dependency from here onto `:core:designsystem` would point
 * the wrong way through the module graph, and the thing it would buy — type safety at the edge of a
 * key-value file — is not safety at all, because the file can already hold anything. The names are
 * plMail's own `Theme`, `Layout` and `Density` wire values, so the resolvers that turn these back
 * into enums (`PlMailThemeChoice.fromWire` and friends) are the same ones that will decode the
 * server's `Appearance` when it is exposed. That is the swap the plan promises will touch the
 * resolver and nothing else.
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

    private companion object {
        val THEME = stringPreferencesKey("appearance_theme")
        val LAYOUT = stringPreferencesKey("appearance_layout")
        val DENSITY = stringPreferencesKey("appearance_density")
        val DYNAMIC_COLOR = booleanPreferencesKey("appearance_dynamic_color")
        val REDUCE_TRANSPARENCY = booleanPreferencesKey("appearance_reduce_transparency")
        val PANE_ALPHA = stringPreferencesKey("appearance_pane_alpha")

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
 * Appearance as it is on disk: what was chosen, not what it resolves to.
 *
 * Null means "never chosen", which is not the same as the default having been chosen — it is what
 * lets a future `Appearance` sync fill in the values the user has not overridden locally without
 * having to guess which of them were deliberate.
 */
data class StoredAppearance(
    val theme: String? = null,
    val layout: String? = null,
    val density: String? = null,
    val dynamicColor: Boolean = false,
    val reduceTransparency: Boolean = false,
    val paneAlpha: String? = null,
)
