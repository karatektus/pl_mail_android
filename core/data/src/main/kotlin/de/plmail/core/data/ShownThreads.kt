package de.plmail.core.data

import de.plmail.core.database.PlMailDatabase
import de.plmail.jmap.methods.ThreadSet
import de.plmail.jmap.protocol.AccountId
import de.plmail.jmap.protocol.RequestBuilder
import de.plmail.jmap.protocol.ThreadId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Tells the server which conversations this device has actually put in front of the user.
 *
 * The write half of the New marker. The server keeps `listedAt` — when a conversation's row was
 * first *displayed* — and until it published `isNew` no client but the browser could clear one; a
 * mailbox triaged entirely on a phone opened in the browser with every conversation from the last
 * day still badged and every category tab still dotted. This is what stops that.
 *
 * ## Displayed, not merely fetched
 *
 * The distinction is the whole feature and it is easy to lose. Paging a list fetches conversations
 * nobody has seen; a notification fetches one; the prefetcher fetches bodies for mail that is still
 * below the fold. None of those is a display. Only [report] is, and only the list calls it, for the
 * rows Compose has actually composed.
 *
 * ## Why the reports are batched and deferred
 *
 * A scroll produces one of these per row, several times a second, and each is a JMAP request
 * against what is frequently a Raspberry Pi on somebody's domestic uplink. So the ids are collected
 * and flushed on a [Mutex] rather than sent as they arrive, and a flush already in flight simply
 * takes the newer ids with it. Nothing waits on the result: the marker is not something the user is
 * looking at, and a failed report costs one more attempt next time the row is drawn.
 *
 * ## Local first, so the digest does not flicker
 *
 * The local row is cleared in the same breath as the report is sent, rather than waiting for the
 * conversation to come back down an `Email/changes`. Otherwise a bundle the user has just opened
 * would sit there until the next sync happened to re-fetch its conversations — which is the
 * behaviour the marker exists to remove, reintroduced on the client side.
 */
@Singleton
class ShownThreads
@Inject
constructor(
    private val database: PlMailDatabase,
    private val clients: AccountClients,
    /**
     * Process-lifetime, because that is what a fire-and-forget report needs: the scope that started
     * it is a list the user is about to scroll away from, and cancelling the flush when they do
     * would drop exactly the reports for the rows they had just read.
     */
    @ApplicationScope private val scope: CoroutineScope,
) {

    private val pending = mutableMapOf<String, MutableSet<String>>()
    private val lock = Mutex()

    /**
     * Records that these conversations have been drawn, and schedules a report.
     *
     * Takes thread ids rather than rows because that is what the caller holds and what the server
     * addresses. Returns immediately; the work is done on [scope].
     */
    fun report(accountKey: String, threadIds: Collection<String>) {
        if (threadIds.isEmpty()) return

        scope.launch { reportNow(accountKey, threadIds) }
    }

    /**
     * The same work, awaited rather than launched.
     *
     * Split out so it can be *tested* instead of raced against. The queue hops onto Room's own
     * executor, which no test scheduler can advance, so a test that called [report] and advanced
     * virtual time would assert against work that had not begun — and would pass just as happily
     * for a reporter that sent nothing at all, which is the exact bug worth catching here.
     */
    internal suspend fun reportNow(accountKey: String, threadIds: Collection<String>) {
        if (threadIds.isEmpty()) return

        val toSend = lock.withLock {
            // Narrowed against the cache before anything is queued, so a
            // list redrawing the same fifty rows on every recomposition
            // does not queue fifty ids the server has already been told
            // about. The DAO answers only rows still marked new.
            val unreported =
                database.threads().stillNew(accountKey, threadIds.toList()).map { it.threadId }

            if (unreported.isEmpty()) return@withLock emptySet()

            pending.getOrPut(accountKey) { mutableSetOf() } += unreported

            pending.remove(accountKey).orEmpty()
        }

        if (toSend.isNotEmpty()) send(accountKey, toSend)
    }

    private suspend fun send(accountKey: String, threadIds: Set<String>) {
        val account = database.accounts().byUid(accountKey) ?: return
        val client = clients.forAccount(accountKey) ?: return

        // Cleared first, and deliberately. The report is idempotent on the
        // server -- it keeps the FIRST display time and a repeat changes
        // nothing -- so the cost of clearing before a failed send is one row
        // that stops saying "new" here a little early. The cost of the other
        // order is a bundle that stays on screen after the user has opened it,
        // which is the complaint this feature exists to answer.
        runCatching { database.threads().clearNew(accountKey, threadIds.toList()) }

        runCatching {
            val request = RequestBuilder()
            val set =
                request.add(
                    ThreadSet.shown(
                        accountId = AccountId(account.accountId),
                        threadIds = threadIds.map(::ThreadId),
                    )
                )

            client.send(request).result(set)
        }
    }
}
