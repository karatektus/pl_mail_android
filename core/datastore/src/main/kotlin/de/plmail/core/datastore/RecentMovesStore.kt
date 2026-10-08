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
 * The labels mail was last moved to, newest first.
 *
 * Label *keys*, for the reason the outbox stores keys: a key is the same value in every account
 * that binds the label and survives a re-sync, where a mailbox id does neither. A key whose label
 * has since been deleted is simply not offered — nothing here is cleaned up, because the list is
 * capped and the next move pushes a dead entry out.
 */
@Singleton
class RecentMovesStore @Inject constructor(private val preferences: DataStore<Preferences>) {

    val recent: Flow<List<String>> =
        preferences.data.map { it[RECENT].split() }.distinctUntilChanged()

    suspend fun record(labelKey: String) {
        preferences.edit { store ->
            store[RECENT] =
                (listOf(labelKey) + store[RECENT].split().filter { it != labelKey })
                    .take(LIMIT)
                    .joinToString(SEPARATOR)
        }
    }

    private companion object {
        val RECENT = stringPreferencesKey("move_recent_labels")

        /**
         * Three: enough to cover the handful of places most mail is filed, short enough to read.
         */
        const val LIMIT = 3
        const val SEPARATOR = "\n"

        fun String?.split(): List<String> =
            this?.split(SEPARATOR).orEmpty().filter { it.isNotBlank() }
    }
}
