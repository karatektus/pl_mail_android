package de.plmail.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Which lists of mail are allowed to interrupt: the per-label and per-category notification
 * switches.
 *
 * Here rather than on any database row for the reason [AccountPrefsStore] gives at length — the
 * database is a cache that is dropped and rebuilt on any schema change, and a preference nobody can
 * reconstruct from the server would go with it. This one is worse than the account ordering if it
 * is lost: an ordering that resets is visible, and a notification preference that resets is a phone
 * that quietly starts buzzing for every newsletter again.
 *
 * **Two sets rather than one, and that is the whole design.** The defaults are not uniform: Primary
 * is on and everything else is off, which is what makes a fresh install behave like Gmail. A single
 * "enabled" set could not express that — switching Primary off would leave the set empty, which is
 * indistinguishable from a user who has never touched the screen, and the next sync would helpfully
 * switch it back on. So a key is in [NotificationPrefs.enabled] when the user has switched it on,
 * in [NotificationPrefs.disabled] when they have switched it off, and in neither when they have
 * never said — which is the only state a *default* is allowed to answer for.
 *
 * That also gives the right answer for a label that appears later. A label created on the web is in
 * neither set, so it is off, which is what "everything else off by default" has to mean for labels
 * that did not exist when the user last looked. Note this is the opposite of how
 * [AccountPrefsStore] stores muting, and deliberately: a new *account* not notifying looks exactly
 * like push being broken, whereas a new *label* notifying is one more interruption nobody asked
 * for.
 *
 * Keys are opaque here. `:core:data` owns the vocabulary (`category:primary`, `label:<key>`); this
 * store deliberately cannot tell them apart, so a third kind added later needs no change to it.
 */
@Singleton
class NotificationPrefsStore @Inject constructor(private val preferences: DataStore<Preferences>) {

    val prefs: Flow<NotificationPrefs> =
        preferences.data
            .map { stored ->
                NotificationPrefs(
                    enabled = stored[ENABLED].split().toSet(),
                    disabled = stored[DISABLED].split().toSet(),
                )
            }
            // The settings screen and the sync path both read this, and the file
            // behind it also holds the credential, the push state and the recent
            // searches. Without this a sync recording "last push received" would
            // recompute the whole notification screen.
            .distinctUntilChanged()

    /** The same value once, for the sync path, which has no business collecting a flow. */
    suspend fun current(): NotificationPrefs = prefs.first()

    /**
     * Records a switch, in whichever direction.
     *
     * Written as a removal from one set and an addition to the other, in one edit, because the two
     * halves have to move together: a key left in both sets would be answered by whichever check
     * ran first, and the two callers that ask do not run in the same order.
     */
    suspend fun setEnabled(key: String, enabled: Boolean) {
        preferences.edit { store ->
            val on = store[ENABLED].split().toMutableSet()
            val off = store[DISABLED].split().toMutableSet()

            if (enabled) {
                on += key
                off -= key
            } else {
                on -= key
                off += key
            }

            store[ENABLED] = on.joinToString(SEPARATOR)
            store[DISABLED] = off.joinToString(SEPARATOR)
        }
    }

    private companion object {
        val ENABLED = stringPreferencesKey("notify_scopes_enabled")
        val DISABLED = stringPreferencesKey("notify_scopes_disabled")

        /**
         * Newline, exactly as [AccountPrefsStore] uses, and for the same reason: a label key is
         * either a server-issued id or a mailbox uid built from one, and neither can contain one.
         */
        const val SEPARATOR = "\n"

        fun String?.split(): List<String> =
            this?.split(SEPARATOR).orEmpty().filter { it.isNotBlank() }
    }
}

/**
 * What the user has said about notifications, as one value.
 *
 * Both sets may name scopes that no longer exist — a label deleted on the web, a category a later
 * build stops knowing about — and neither is reconciled against anything. Reconciling would mean
 * this store having an opinion about what labels exist, which is the database's job; a stranger in
 * either set is simply never asked about.
 */
data class NotificationPrefs(
    val enabled: Set<String> = emptySet(),
    val disabled: Set<String> = emptySet(),
)
