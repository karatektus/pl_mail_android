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
 * Which two buttons a mail notification carries (#93), as the words the choice is stored in.
 *
 * Strings for the reason [SwipePrefsStore] gives: the vocabulary is `:core:data`'s, and a word a
 * later build wrote has to survive being read by this one. This phone's alone, like the swipes: the
 * server's notification settings are about *which* mail rings, not what the shade offers.
 */
@Singleton
class ShadeActionsStore @Inject constructor(private val preferences: DataStore<Preferences>) {

    val prefs: Flow<StoredShadeActions> =
        preferences.data
            .map { StoredShadeActions(first = it[FIRST], second = it[SECOND]) }
            .distinctUntilChanged()

    suspend fun setFirst(wire: String) {
        preferences.edit { it[FIRST] = wire }
    }

    suspend fun setSecond(wire: String) {
        preferences.edit { it[SECOND] = wire }
    }

    private companion object {
        val FIRST = stringPreferencesKey("shade_action_first")
        val SECOND = stringPreferencesKey("shade_action_second")
    }
}

/** Null means nothing was ever chosen, which is the default and not "no button". */
data class StoredShadeActions(val first: String? = null, val second: String? = null)
