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
 * What the user decided about their accounts, as opposed to what the server said about them.
 *
 * **Here rather than on `AccountEntity`, and that is not a filing preference.** The obvious home
 * for an ordering is a `sortIndex` column on the account row, and the account row already has one —
 * set from the *session's* order, which is the server's answer and is reconstructible from it. The
 * database's whole recovery strategy is "on a migration or corruption failure, drop it and
 * re-sync", which is only safe while every row in it is reconstructible. A user's ordering is not:
 * nothing on the server knows it, so a schema bump would silently reset the order of somebody's
 * mailboxes and there would be no way to tell that anything had been lost. The same argument
 * applies to muting an account's notifications, which is why it is here too.
 *
 * Keyed by the account **uid** — `"<server>/<accountId>"` — which survives a cache wipe for the
 * same reason it is a good primary key: both halves come back identical from the session, so a
 * re-synced database lands on the ordering the user set before it was thrown away.
 *
 * Both fields are stored as one delimited string rather than a `stringSetPreferencesKey`, because a
 * set has no order and order is the entire point of one of them. Newline is the delimiter: a JMAP
 * account id is server-issued and an origin is a URL, so neither can contain one.
 */
@Singleton
class AccountPrefsStore @Inject constructor(private val preferences: DataStore<Preferences>) {

    val prefs: Flow<AccountPrefs> =
        preferences.data
            .map { stored ->
                AccountPrefs(
                    order = stored[ORDER].split(),
                    muted = stored[MUTED].split().toSet(),
                )
            }
            // Every account list in the app subscribes to this, and the file it
            // reads also holds the credential, the push state and the recent
            // searches. Without this a sync recording "last push received" would
            // recompute the sidebar, the From picker and the accounts screen.
            .distinctUntilChanged()

    /**
     * Records the order the user has arranged their accounts into.
     *
     * The whole list, not a move: two adjacent reorders raced through a read-modify-write would
     * otherwise interleave into an order that is neither of them. The caller already holds the
     * complete list it just rendered, so handing it back costs nothing.
     */
    suspend fun setOrder(uids: List<String>) {
        preferences.edit { it[ORDER] = uids.joinToString(SEPARATOR) }
    }

    /**
     * Whether an account may interrupt the user.
     *
     * Muting is stored rather than notifying, so an account that appears later — a second mailbox
     * added on the server — notifies by default. The alternative would mean a new account silently
     * never announcing anything, which looks exactly like push being broken and is the failure this
     * product can least afford to fake.
     */
    suspend fun setMuted(uid: String, muted: Boolean) {
        preferences.edit { store ->
            val current = store[MUTED].split().toMutableSet()

            if (muted) current += uid else current -= uid

            store[MUTED] = current.joinToString(SEPARATOR)
        }
    }

    private companion object {
        val ORDER = stringPreferencesKey("accounts_order")
        val MUTED = stringPreferencesKey("accounts_muted")

        const val SEPARATOR = "\n"

        /** Empty and absent are the same thing here: nobody has chosen yet. */
        fun String?.split(): List<String> =
            this?.split(SEPARATOR).orEmpty().filter { it.isNotBlank() }
    }
}

/** The user's own decisions about their accounts, as one value. */
data class AccountPrefs(
    /**
     * Account uids, in the order the user arranged them.
     *
     * May name accounts that no longer exist and may omit ones that do — it is written when the
     * user reorders and never reconciled against the database, because reconciling it would mean
     * this store having an opinion about what accounts exist. The resolver drops the strangers and
     * appends the newcomers.
     */
    val order: List<String> = emptyList(),
    val muted: Set<String> = emptySet(),
)
