package de.plmail.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Which labels the user has lifted into the sidebar's top group, and which they have pushed out of
 * it.
 *
 * The drawer is three groups: the inbox categories, a short **Important** list, and everything else
 * under **Labels**. The middle group has a default — Starred, Trash, Spam, Sent, Archive — and the
 * whole point of this store is that the default is not the answer, only the starting point.
 *
 * **Two sets rather than one**, exactly as [NotificationPrefsStore] keeps them and for the same
 * reason: the default is not uniform, so a single "pinned" set could not tell *the user unpinned
 * Sent* apart from *the user has never touched this screen*. A key is in [SidebarPrefs.pinned] when
 * they lifted it, in [SidebarPrefs.unpinned] when they pushed it down, and in neither when they
 * have never said — which is the only state a default may answer for.
 *
 * That is also what makes a label created later behave sensibly. It is in neither set, so it lands
 * wherever its role says, which for a user's own label is under Labels.
 *
 * Keys are opaque here. `:core:data` owns both the vocabulary and the default, so this store
 * deliberately cannot tell a system label from anybody's own.
 */
@Singleton
class SidebarPrefsStore @Inject constructor(private val preferences: DataStore<Preferences>) {

    val prefs: Flow<SidebarPrefs> =
        preferences.data
            .map { stored ->
                SidebarPrefs(
                    pinned = stored[PINNED].split().toSet(),
                    unpinned = stored[UNPINNED].split().toSet(),
                )
            }
            // The file behind this also holds the credential, the push state and
            // the recent searches, so without this the sidebar recomposes every
            // time a sync records anything at all.
            .distinctUntilChanged()

    /**
     * Records a move, in whichever direction.
     *
     * Both halves in one edit, because a key left in both sets would be answered by whichever check
     * ran first.
     */
    suspend fun setImportant(key: String, important: Boolean) {
        preferences.edit { store ->
            val up = store[PINNED].split().toMutableSet()
            val down = store[UNPINNED].split().toMutableSet()

            if (important) {
                up += key
                down -= key
            } else {
                up -= key
                down += key
            }

            store[PINNED] = up.joinToString(SEPARATOR)
            store[UNPINNED] = down.joinToString(SEPARATOR)
        }
    }

    /** Forgets every choice, so every label falls back to its default group. */
    suspend fun reset() {
        preferences.edit { store ->
            store.remove(PINNED)
            store.remove(UNPINNED)
        }
    }

    private companion object {
        val PINNED = stringPreferencesKey("sidebar_important_pinned")
        val UNPINNED = stringPreferencesKey("sidebar_important_unpinned")

        /** Newline, as every other key set here uses: no label key can contain one. */
        const val SEPARATOR = "\n"

        fun String?.split(): List<String> =
            this?.split(SEPARATOR).orEmpty().filter { it.isNotBlank() }
    }
}

/**
 * What the user has said about the sidebar's grouping, as one value.
 *
 * Either set may name a label that no longer exists, and neither is reconciled against anything —
 * reconciling would mean this store knowing what labels there are, which is the database's job. A
 * stranger in either set is simply never asked about.
 */
data class SidebarPrefs(
    val pinned: Set<String> = emptySet(),
    val unpinned: Set<String> = emptySet(),
)
