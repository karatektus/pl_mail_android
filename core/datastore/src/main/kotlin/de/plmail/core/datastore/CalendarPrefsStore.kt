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
 * Which calendar view the user last chose.
 *
 * **Here rather than in Room, for the reason [AccountPrefsStore] is.** The database's whole
 * recovery strategy is "on a migration or corruption failure, drop it and re-sync", which is only
 * safe while every row in it is reconstructible from the server — and nothing on the server knows
 * that this person reads their calendar as a week. A schema bump would silently put them back on
 * the agenda, and there would be nothing to tell them anything had been lost.
 *
 * **A raw string rather than the view enum**, exactly as [AppearanceStore] keeps a raw theme name:
 * a store is persistence and the enum is UI, and a dependency from here onto `:feature:calendar`
 * would point the wrong way through the module graph. The decode is the caller's, which is also
 * what makes a value this build has never heard of — a file written by a newer one — degrade to the
 * default rather than crash on the first frame. A calendar is not worth a launch loop.
 *
 * **This is the fifth writer of one preferences file**, after the credential, the outbox, the
 * appearance overrides and the push state, and `docs/REMAINING.md` has been asking for that file to
 * be split since there were four. The [distinctUntilChanged] below is what keeps the cost of
 * sharing it at zero here: DataStore emits on every write to the file, so without it a sync
 * recording a push timestamp would re-derive the calendar's window and re-run its query.
 */
@Singleton
class CalendarPrefsStore @Inject constructor(private val preferences: DataStore<Preferences>) {

    /** The stored wire name, or null while nobody has chosen. Never decoded here. */
    val view: Flow<String?> = preferences.data.map { it[VIEW] }.distinctUntilChanged()

    suspend fun setView(wire: String) {
        preferences.edit { it[VIEW] = wire }
    }

    private companion object {
        val VIEW = stringPreferencesKey("calendar_view")
    }
}
