package de.plmail.core.data

import de.plmail.core.database.AccountEntity
import de.plmail.core.database.PlMailDatabase
import de.plmail.core.datastore.AccountPrefsStore
import de.plmail.jmap.mail.Comparator
import de.plmail.jmap.methods.EmailGet
import de.plmail.jmap.methods.EmailQuery
import de.plmail.jmap.protocol.AccountId
import de.plmail.jmap.protocol.RequestBuilder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * One account, as the settings screen and the From picker see it.
 *
 * The window fields are about *this device*, not about the server, and the wording everywhere they
 * are drawn has to keep saying so. The app pages backwards as the user scrolls, so "the mail I can
 * search" and "the mail that exists" are different sets and the difference is invisible — which is
 * the whole reason search's empty state has to talk about a sync window at all.
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
     * The oldest message the *server* holds, once somebody has asked.
     *
     * Null until then, and deliberately: answering costs an `Email/query` per account, and a
     * settings screen that quietly makes requests when it is opened is the same mistake the
     * diagnostics screen was written to avoid.
     */
    val oldestOnServer: Long? = null,
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
) {

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
     * Asks each account for the date of the oldest message the server still holds.
     *
     * One `Email/query` per account — ascending, limit one, ids only — which is the cheapest
     * question that has an honest answer. Nothing in the JMAP session reports a retention policy:
     * plMail keeps `sync.message_limit` and `sync.backfill_target` on the account entity and
     * exposes neither, so the observable boundary is all there is. Filed in
     * `docs/SERVER_REQUESTS.md`.
     *
     * Failures are dropped per account rather than failing the sweep. One unreachable mailbox must
     * not blank the answer for the others, which is the same rule the unified feed is built on.
     */
    suspend fun oldestOnServer(): Map<String, Long> {
        val client = clients.current() ?: return emptyMap()

        return database
            .accounts()
            .all()
            .mapNotNull { account ->
                runCatching {
                    val request = RequestBuilder()

                    val query =
                        request.add(
                            EmailQuery(
                                accountId = AccountId(account.accountId),
                                sort = listOf(Comparator.OLDEST_FIRST),
                                limit = 1,
                            )
                        )

                    val get =
                        request.add(
                            EmailGet.byReference(
                                AccountId(account.accountId),
                                query.reference("/ids"),
                                properties = listOf("id", "receivedAt"),
                            )
                        )

                    client.send(request).result(get).list.firstOrNull()?.receivedAt
                }
                    .getOrNull()
                    ?.toEpochMillis()
                    ?.let { account.uid to it }
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
