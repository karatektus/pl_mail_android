package de.plmail.core.data

import de.plmail.core.database.AccountEntity
import de.plmail.core.database.PlMailDatabase
import de.plmail.core.datastore.AccountPrefsStore
import de.plmail.core.datastore.CredentialStore
import de.plmail.jmap.protocol.AccountId
import de.plmail.jmap.protocol.SyncWindow
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * One account, as the settings screen and the From picker see it.
 *
 * [cachedMessages] and [oldestCachedAt] are about *this device*, and the wording everywhere they
 * are drawn has to keep saying so. The app pages backwards as the user scrolls, so "the mail I can
 * search" and "the mail that exists" are different sets and the difference is invisible — which is
 * the whole reason search's empty state has to talk about a sync window at all.
 *
 * [serverWindow] answers the other half of the same question and is not a substitute for either:
 * what the server intends to hold, whatever this phone has caught up on. Both are kept because a
 * mail that is missing because it was never fetched and a mail that is missing because this device
 * has not paged back that far want opposite reactions from the user.
 */
data class AccountSummary(
    val accountKey: String,
    val name: String,
    val server: String,
    val lastSyncedAt: Long?,
    val lastError: String?,
    /** Whether this account may raise a notification. Stored as *muted*, so new accounts speak. */
    val isNotifying: Boolean,
    /** How many of this account's messages are on the device. */
    val cachedMessages: Int,
    /** The oldest one's date, or null when the device holds none. */
    val oldestCachedAt: Long?,
    /**
     * What the *server* intends to hold for this account, straight out of the session.
     *
     * Null on an account that publishes no sync capability. This used to be an `Email/query` per
     * account behind a button that said it made requests; the session already carries the answer
     * and carries a better one — a cap and a backfill state rather than a date inferred from the
     * oldest row the server happened to return.
     */
    val serverWindow: SyncWindow? = null,
)

/**
 * The account list, in the order the user arranged it.
 *
 * The order is a *local preference* and lives in DataStore rather than in the account row — see
 * [AccountPrefsStore] for the argument, which is short: the database is a cache that may be thrown
 * away and rebuilt at any schema change, and an ordering nobody can reconstruct would go with it.
 *
 * This is the one place that joins the two, so every list of accounts in the app agrees. That
 * matters beyond the settings screen: the first account in the order is the one a new label is
 * created in and the one the composer opens with, so "put my main mailbox at the top" has to mean
 * something everywhere, not just in a list of rows with arrows beside them.
 */
@Singleton
class AccountsRepository
@Inject
constructor(
    private val database: PlMailDatabase,
    private val prefs: AccountPrefsStore,
    private val clients: AccountClients,
    credentials: CredentialStore,
) {

    /**
     * The host the app would be talking to, or null before pairing.
     *
     * The bare host rather than the origin, because it is going into a sentence: "Could not reach
     * nas.local" reads and "Could not reach https://nas.local:8443" does not. Exposed from here
     * rather than read out of the credential store by the UI, so no feature module has to depend on
     * the thing that holds the token to find out the name of a machine.
     */
    val serverHost: Flow<String?> = credentials.connection.map { it?.address?.host }

    /** The account rows, ordered, with nothing else joined onto them. */
    val ordered: Flow<List<AccountEntity>> =
        combine(database.accounts().observeAll(), prefs.prefs) { rows, stored ->
            ordered(rows, stored.order)
        }

    /** Everything the accounts screen draws, apart from what it has to ask the server for. */
    val summaries: Flow<List<AccountSummary>> =
        combine(
            database.accounts().observeAll(),
            prefs.prefs,
            database.accounts().observeCachedWindows(),
        ) { rows, stored, windows ->
            val byAccount = windows.associateBy { it.accountKey }

            ordered(rows, stored.order).map { account ->
                AccountSummary(
                    accountKey = account.uid,
                    name = account.name,
                    server = account.serverId,
                    lastSyncedAt = account.lastSyncedAt,
                    lastError = account.lastSyncError,
                    isNotifying = account.uid !in stored.muted,
                    cachedMessages = byAccount[account.uid]?.messages ?: 0,
                    oldestCachedAt = byAccount[account.uid]?.oldestReceivedAt,
                )
            }
        }

    /** The same order, once, for the callers that are not composing a screen. */
    suspend fun all(): List<AccountEntity> =
        ordered(database.accounts().all(), prefs.prefs.first().order)

    /** The account a new label, or a new message, belongs to when nobody has said. */
    suspend fun primary(): AccountEntity? = all().firstOrNull()

    /** Whether an account is allowed to interrupt. Read on the sync path, so it takes no flow. */
    suspend fun isNotifying(accountKey: String): Boolean = accountKey !in prefs.prefs.first().muted

    suspend fun setNotifying(accountKey: String, notifying: Boolean) {
        prefs.setMuted(accountKey, muted = !notifying)
    }

    /**
     * Moves one account one place in the user's order.
     *
     * Takes the list the caller is looking at rather than reading it back, because the stored order
     * may not mention every account — a mailbox added on the server has never been ordered — and
     * resolving that here would mean writing an order the user never arranged. Moving the *first*
     * unordered account is what would otherwise silently reorder every other one.
     */
    suspend fun move(accountKey: String, by: Int) {
        val current = all().map { it.uid }
        val from = current.indexOf(accountKey)
        val to = from + by

        // Both guards matter. `from < 0` is an account that has been deleted
        // under the screen; an out-of-range target is the arrow at the end of
        // the list, which is disabled in the UI and reachable by TalkBack's own
        // actions, so it has to be harmless rather than merely unlikely.
        if (from < 0 || to !in current.indices) return

        prefs.setOrder(current.toMutableList().apply { add(to, removeAt(from)) })
    }

    /**
     * What the server says it holds for each account, keyed by the account's local uid.
     *
     * A **session read**, not a request: `urn:plmail:params:jmap:sync` sits in each account's
     * `accountCapabilities`, and the session is already fetched and cached before anything is
     * drawn. That is what replaced the old probe — one `Email/query` per account, ascending, limit
     * one — which cost a round trip each, had to sit behind a button that admitted it, and could
     * only ever report the oldest message the server *happened to have fetched* rather than the
     * window it intends to keep. The two look the same on a mailbox that has finished backfilling
     * and disagree on every mailbox that has not, which is exactly the one somebody is asking
     * about.
     *
     * An empty map means there is no connection, or a server without the extension. Neither is an
     * error and neither has anything to say.
     */
    suspend fun serverWindows(): Map<String, SyncWindow> {
        val client = clients.current() ?: return emptyMap()
        val session = runCatching { client.session() }.getOrNull() ?: return emptyMap()

        return database
            .accounts()
            .all()
            .mapNotNull { account ->
                session.syncWindow(AccountId(account.accountId))?.let { account.uid to it }
            }
            .toMap()
    }
}

/**
 * The stored order applied to the rows that actually exist.
 *
 * Pure, and separate from the repository, because every interesting case is a disagreement between
 * two lists that were written at different times and neither of them is wrong:
 *
 * - **An account in the order that no longer exists** — a mailbox removed on the server, or the
 *   user re-paired against a different one. Dropped silently; keeping it would put a row in the
 *   list for mail nobody can reach.
 * - **An account that exists and was never ordered** — a mailbox added on the server since the last
 *   time anybody touched this screen. Appended, in the *server's* own order, rather than sorted
 *   into the middle: the user's arrangement is about the accounts they arranged, and a newcomer
 *   inserting itself above them is the kind of surprise that makes people distrust a setting.
 * - **No stored order at all**, which is every user until they open the screen. Falls through to
 *   the session's order, which is what the account row's `sortIndex` already holds.
 */
internal fun ordered(rows: List<AccountEntity>, order: List<String>): List<AccountEntity> {
    if (order.isEmpty()) return rows

    val byUid = rows.associateBy { it.uid }

    // `distinct()` on the *keys*, before the lookup. A uid appearing twice in
    // the stored list would otherwise put the same account in the result twice
    // — which draws two rows for one mailbox, and makes the arrows move
    // whichever copy `indexOf` happens to find. Nothing in the app writes a
    // duplicate today; that is exactly the kind of thing that stops being true
    // quietly.
    val arranged = order.distinct().mapNotNull { byUid[it] }
    val known = arranged.mapTo(mutableSetOf()) { it.uid }

    return arranged + rows.filterNot { it.uid in known }
}
