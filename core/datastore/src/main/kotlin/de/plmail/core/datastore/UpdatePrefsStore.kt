package de.plmail.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The in-app updater's two remembered facts: whether to look on its own, and what it last
 * announced.
 *
 * **Here rather than in Room**, for the reason [CalendarPrefsStore] gives: neither is
 * reconstructible from the mail server, so a database reset must not take them with it.
 *
 * [notifiedVersionCode] is what keeps the daily check from posting the same notification every
 * morning. A release is announced once; somebody who swiped it away has seen it, and the version
 * section on the diagnostics screen still offers it for as long as it is the newest.
 */
@Singleton
class UpdatePrefsStore @Inject constructor(private val preferences: DataStore<Preferences>) {

    /** On unless switched off. A build that cannot update itself never reads it. */
    val isAutoCheckOn: Flow<Boolean> =
        preferences.data.map { it[AUTO_CHECK] ?: true }.distinctUntilChanged()

    suspend fun setAutoCheck(isOn: Boolean) {
        preferences.edit { it[AUTO_CHECK] = isOn }
    }

    /** The versionCode of the last release a notification was posted for, or null for none. */
    suspend fun notifiedVersionCode(): Int? = preferences.data.first()[NOTIFIED]

    suspend fun setNotified(versionCode: Int) {
        preferences.edit { it[NOTIFIED] = versionCode }
    }

    private companion object {
        val AUTO_CHECK = booleanPreferencesKey("update_auto_check")
        val NOTIFIED = intPreferencesKey("update_notified_version")
    }
}
