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
 * Messages the server is holding until a time the user picked — a **cache** now, not the record.
 *
 * **This file used to be the only copy of a scheduled send in existence, and it no longer is.**
 * plMail reconstructs a submission from the Message and used to skip any Message with no `sentAt`,
 * so a held submission answered `notFound` from `EmailSubmission/get` exactly as a draft nobody
 * ever submitted did; the release time lived in the create response and nowhere else. The server
 * now reports a held submission as `pending` with its real `sendAt`, a cancelled one as `canceled`,
 * and `EmailSubmission/changes` carries every transition — so the schedule is shared by every
 * device signed into the account, and `ScheduledSendReconciler` is what keeps this file in step
 * with it.
 *
 * **Still not in Room**, and the reason has changed rather than disappeared. It is no longer "the
 * server does not have this"; it is that the bar over the mail list has to be right on the first
 * frame, before any network, and a store the schema bump drops would show an empty bar to somebody
 * whose message leaves in ten minutes. A cache that survives an upgrade is worth having even when
 * it is reconstructible.
 *
 * It is also still the **fallback**. An older plMail answers `notFound` for a hold it is genuinely
 * holding, and against one of those this file is the only record there is — which is exactly the
 * old behaviour, kept rather than removed, and detected from how the server answers rather than
 * from a version nobody publishes.
 *
 * Held as an opaque string, like the outbox: what a scheduled send *is* belongs to `:core:data`,
 * which owns the JSON, and a store that understood the shape would change every time a field did.
 * [cursors] is the same bargain for the per-account `EmailSubmission/changes` positions.
 *
 * The mutex is not decoration — see [OutboxStore] for the read-modify-write race it closes.
 */
@Singleton
class ScheduledSendStore @Inject constructor(private val preferences: DataStore<Preferences>) {

    /** The schedule as `:core:data` wrote it, or empty when nothing is waiting. */
    val records: Flow<String> =
        preferences.data.map { it[RECORDS].orEmpty() }.distinctUntilChanged()

    /**
     * Where each account's `EmailSubmission/changes` walk has reached.
     *
     * Separate from [records] because they are written at different moments and for different
     * reasons: a cursor moves on every reconcile including the ones that find nothing, and folding
     * it into the record list would rewrite the whole schedule — and wake every collector of
     * [records] — for a token no screen draws.
     */
    val cursors: Flow<String> =
        preferences.data.map { it[CURSORS].orEmpty() }.distinctUntilChanged()

    private val mutex = Mutex()

    suspend fun update(transform: (String) -> String) {
        mutex.withLock { preferences.edit { it[RECORDS] = transform(it[RECORDS].orEmpty()) } }
    }

    suspend fun updateCursors(transform: (String) -> String) {
        mutex.withLock { preferences.edit { it[CURSORS] = transform(it[CURSORS].orEmpty()) } }
    }

    private companion object {
        val RECORDS = stringPreferencesKey("scheduled_sends")
        val CURSORS = stringPreferencesKey("scheduled_send_cursors")
    }
}
