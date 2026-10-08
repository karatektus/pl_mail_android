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
 * What a swipe on a list row does, in each direction, as the words the user's choice is stored in.
 *
 * Strings rather than an enum, because the vocabulary is `:core:data`'s and this module must not
 * know it: a value written by a later build that this one has never heard of has to survive being
 * read, and the place that decides what it falls back to is the place that owns the meanings.
 *
 * **This phone's, never the account's.** A swipe is a habit of one hand on one device; the tablet
 * on the sofa and the phone on the train are held differently, and syncing the answer would make
 * changing one silently retrain the other.
 */
@Singleton
class SwipePrefsStore @Inject constructor(private val preferences: DataStore<Preferences>) {

    val prefs: Flow<StoredSwipes> =
        preferences.data
            .map { stored ->
                StoredSwipes(
                    toEnd = stored[TO_END],
                    toStart = stored[TO_START],
                    confirmToEnd = stored[CONFIRM_TO_END],
                    confirmToStart = stored[CONFIRM_TO_START],
                    legacyConfirm = stored[LEGACY_CONFIRM],
                )
            }
            // The file behind this also holds the credential and the push state,
            // so without this every row in the list is told about every sync.
            .distinctUntilChanged()

    suspend fun setToEnd(wire: String) {
        preferences.edit { it[TO_END] = wire }
    }

    suspend fun setToStart(wire: String) {
        preferences.edit { it[TO_START] = wire }
    }

    suspend fun setConfirmToEnd(asks: Boolean) {
        preferences.edit { it[CONFIRM_TO_END] = asks }
    }

    suspend fun setConfirmToStart(asks: Boolean) {
        preferences.edit { it[CONFIRM_TO_START] = asks }
    }

    private companion object {
        val CONFIRM_TO_END = booleanPreferencesKey("swipe_confirm_to_end")
        val CONFIRM_TO_START = booleanPreferencesKey("swipe_confirm_to_start")

        /**
         * 0.0.27's single answer for both directions: `never`, `trash` or `always`. Still read,
         * never written — it stands in for a direction nobody has set since, so the one release
         * that offered it does not lose what was chosen in it.
         */
        val LEGACY_CONFIRM = stringPreferencesKey("swipe_confirm")
        val TO_END = stringPreferencesKey("swipe_to_end")
        val TO_START = stringPreferencesKey("swipe_to_start")
    }
}

/** Null on either side means nothing was ever chosen, which is the default and not "nothing". */
data class StoredSwipes(
    val toEnd: String? = null,
    val toStart: String? = null,
    val confirmToEnd: Boolean? = null,
    val confirmToStart: Boolean? = null,
    val legacyConfirm: String? = null,
)
