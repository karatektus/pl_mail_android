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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Messages the server is holding until a time the user picked.
 *
 * **Not in Room, for the same reason [OutboxStore] is not.** That database is dropped and re-synced
 * on any schema bump precisely because every row in it is reconstructible from the server — and a
 * scheduled send is the clearest counter-example in the app. plMail reconstructs a submission from
 * the Message, so a send still waiting to be released has *no server-side row at all*:
 * `EmailSubmission/get` answers `notFound` for it, exactly as it does for a draft nobody ever
 * submitted. The release time exists in the create response and nowhere else, so if this file loses
 * it the user has a message that will leave at a time nothing on the device can name, and no way to
 * call it back.
 *
 * That is also the honest limit of the feature, and it is worth stating where the state lives: the
 * schedule is *this device's* memory of a promise the server made. Another phone, or this one after
 * the app's data is cleared, will not show it — the mail still goes, and cancelling it is no longer
 * offered because nothing knows there is anything to cancel.
 *
 * Held as an opaque string, like the outbox: what a scheduled send *is* belongs to `:core:data`,
 * which owns the JSON, and a store that understood the shape would change every time a field did.
 *
 * The mutex is not decoration — see [OutboxStore] for the read-modify-write race it closes.
 */
@Singleton
class ScheduledSendStore @Inject constructor(private val preferences: DataStore<Preferences>) {

    /** The schedule as `:core:data` wrote it, or empty when nothing is waiting. */
    val records: Flow<String> =
        preferences.data.map { it[RECORDS].orEmpty() }.distinctUntilChanged()

    private val mutex = Mutex()

    suspend fun update(transform: (String) -> String) {
        mutex.withLock { preferences.edit { it[RECORDS] = transform(it[RECORDS].orEmpty()) } }
    }

    private companion object {
        val RECORDS = stringPreferencesKey("scheduled_sends")
    }
}
