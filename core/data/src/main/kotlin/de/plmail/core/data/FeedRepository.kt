package de.plmail.core.data

import androidx.paging.ExperimentalPagingApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import de.plmail.core.database.PlMailDatabase
import de.plmail.core.database.StoreKey
import de.plmail.core.database.ThreadEntity
import de.plmail.core.datastore.CredentialStore
import de.plmail.jmap.client.JmapClient
import de.plmail.jmap.mail.EmailFilter
import de.plmail.jmap.methods.IdentityGet
import de.plmail.jmap.methods.MailboxGet
import de.plmail.jmap.protocol.AccountId
import de.plmail.jmap.protocol.MailboxId
import de.plmail.jmap.protocol.RequestBuilder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The lists the app can show. Ids are stable, because they key the feed table. */
enum class Feed(val id: String) {
    /** Every account's inbox, merged. The product's default view. */
    UNIFIED_INBOX("unified.inbox"),

    /**
     * The current search. One at a time, cleared as each new query starts.
     *
     * Shares the feed table so results page and draw exactly like the inbox, but never shows its
     * rows before a refresh: they answer the previous query. See [SearchRepository].
     */
    SEARCH("search.results"),
}

/**
 * Told that an account has just answered.
 *
 * One method rather than the whole of [FeedRepository], for the same reason [KnownLabels] is one
 * method rather than the whole of `LabelRepository`: [DeltaSync] has no business holding a class
 * that builds pagers and opens sockets, and a seam this narrow cannot quietly grow into one.
 */
fun interface ReachableAccounts {
    fun answered(accountKey: String)
}

/**
 * The unified inbox, as pages.
 *
 * Builds one [AccountPager] per JMAP account behind the stored credential — one credential reaches
 * several mailboxes — and hands them to a [FeedMediator] that fills the table Paging reads.
 */
@Singleton
class FeedRepository
@Inject
constructor(
    private val database: PlMailDatabase,
    private val credentials: CredentialStore,
    private val transports: TransportFactory,
    private val mail: MailRepository,
    /**
     * Where the directory refresh runs when a cached list is already on screen.
     *
     * Process-lifetime rather than the caller's, because the refresh outlives the flow that started
     * it by construction: the point of doing it here is that nobody is waiting for it, and a scope
     * tied to the screen would cancel it the moment the user switched view — which is the exact
     * moment it was started.
     */
    @ApplicationScope private val scope: CoroutineScope,
    repages: RepageSignal,
) : ReachableAccounts {

    private val _failures = MutableStateFlow<List<SourceFailure>>(emptyList())

    /**
     * Serialises the directory refresh, and remembers when the last one finished.
     *
     * Both halves are one guard. The lock single-flights — flipping between Inbox, Promotions and a
     * label in three seconds starts three refreshes, and a server advertising four concurrent
     * requests that is frequently a Raspberry Pi should see one. The timestamp is what makes the
     * queued callers *cheap* rather than merely late: each takes the lock, sees a refresh newer
     * than [DIRECTORY_REFRESH_INTERVAL], and returns without asking anything.
     */
    private val directoryLock = Mutex()
    private var directoryRefreshedAt = 0L

    /**
     * The mediator's own report of how many rows it has just committed, per feed.
     *
     * Unbuffered and conflated: a report is only interesting until the next one, and a subscriber
     * that arrives late gets the truth from Room instead — see [rowsHeld].
     */
    private val mediatorRowCounts = MutableSharedFlow<Pair<String, Int>>(extraBufferCapacity = 16)

    /**
     * Accounts that could not be reached on the last pull.
     *
     * Separate from the paging stream on purpose: a failure must not interrupt the rows, because
     * the whole point is that three working accounts keep drawing while a fourth is down.
     */
    val failures = _failures.asStateFlow()

    /**
     * Accounts a sync has decided cannot be brought up to date incrementally.
     *
     * Re-exposed here for the same reason [failures] is: the list layer already holds this
     * repository and should not have to reach past it to a signal class in this module to learn
     * something about the list it is drawing. A plain [Flow] rather than the `SharedFlow` behind
     * it, because the only thing a collector may do with it is collect.
     */
    val repagedAccounts: Flow<String> = repages.accounts

    /**
     * Withdraws the claim that this account cannot be reached.
     *
     * [_failures] used to be rewritten only by a page load, so the "could not reach nas.local"
     * banner outlived the outage that caused it: nothing re-pages until the user scrolls, and on a
     * list that already fits the screen they never will. The banner then sat over mail that was, by
     * that point, being synced perfectly well — which teaches people to ignore the one message the
     * app has for a server that really is down.
     *
     * A whole-server failure is cleared by *any* account answering, deliberately. That entry means
     * the session itself could not be fetched, and a successful sync is proof that it can be: there
     * is no narrower fact to withdraw, because the failure never named an account in the first
     * place.
     */
    override fun answered(accountKey: String) {
        _failures.update { held ->
            held.filterNot { it.accountKey == accountKey || it.isWholeServer }
        }
    }

    /**
     * How many conversations one list holds, from the two things that know.
     *
     * The list needs this to draw an honest empty state, and Paging's item count is the wrong
     * source for it: it is this table seen one Room invalidation later, and the gap is widest on a
     * list's first visit, when the same query executor that delivers the invalidation is busy
     * writing the messages the rows point at. In that gap the mediator has committed rows, Paging
     * reports none, and neither load state is loading — which is indistinguishable from an empty
     * label unless something else answers.
     *
     * So two reporters of one number, merged, newest wins. Room is the authority and covers
     * everything that writes the table — a delta sync, an archive, a label being emptied by
     * somebody else. [FeedMediator] covers the one moment Room is late for, because it publishes
     * after its transaction commits and before the load state the list watches has flipped. They
     * cannot disagree for long and cannot disagree in a way that hides rows: both read the same
     * table, and Room re-runs its query on every invalidation rather than replaying a cached value.
     */
    fun rowsHeld(feedId: String): Flow<Int> =
        merge(
            database.feed().observeCount(feedId),
            mediatorRowCounts.filter { (id, _) -> id == feedId }.map { (_, count) -> count },
        )

    /**
     * Every account's inbox, merged.
     *
     * Resolved by *role* rather than by a label the caller chose, because the inbox is the one list
     * that must work before anything has been synced — including the mailbox table it would
     * otherwise be looked up in.
     */
    fun unifiedInbox(pageSize: Int = PAGE_SIZE): Flow<PagingData<ThreadEntity>> =
        feed(Feed.UNIFIED_INBOX.id, pageSize) { accountKey, _ ->
            // Null pages everything rather than nothing: an account whose
            // mailboxes have not been synced yet would otherwise contribute no
            // rows at all, and the unified inbox would silently be missing a
            // whole mailbox on first run.
            database.mailboxes().byRole(accountKey, INBOX_ROLE)?.let {
                EmailFilter.InMailbox(MailboxId(it.mailboxId))
            }
        }

    /**
     * One inbox category, merged across accounts — a Gmail tab.
     *
     * **The category is a condition on the query, not a sieve over its results**, and that is the
     * whole reason `EmailFilter.ThreadCategory` was asked of the server rather than worked around
     * here. A page is twenty-five messages; if the filtering happened on this side, a page
     * containing two Promotions would put two rows on screen and report the page as full, and the
     * list would look almost empty while more existed further down. Nothing on the device can tell
     * that from a genuinely quiet tab.
     *
     * The Inbox binding is part of the filter because categories are an *inbox* idea: the server
     * classifies mail as it arrives and never stops, so a conversation moved to Trash keeps its
     * category, and a tab without the mailbox condition would show the bin's promotions alongside
     * the inbox's. When the binding is not known yet the category stands alone — the same fallback
     * [unifiedInbox] takes, and just as narrow a window: `feed` syncs mailboxes before it builds a
     * single pager, so this is the first-run corner rather than the ordinary path.
     */
    fun category(
        category: MailCategory,
        pageSize: Int = PAGE_SIZE,
    ): Flow<PagingData<ThreadEntity>> =
        feed(category.feedId, pageSize) { accountKey, _ ->
            val inCategory = EmailFilter.ThreadCategory(category.wire)

            database.mailboxes().byRole(accountKey, INBOX_ROLE)?.let {
                EmailFilter.And(listOf(EmailFilter.InMailbox(MailboxId(it.mailboxId)), inCategory))
            } ?: inCategory
        }

    /**
     * One label's mail, merged across every account that binds it.
     *
     * Accounts with no binding for the label are **dropped from the merge entirely**, not queried
     * unfiltered. That distinction is the whole difference between "this label, everywhere" and
     * "this label in one account plus the other account's entire mailbox", and the second one looks
     * exactly like a broken filter to whoever is reading it.
     */
    fun labelled(label: Label, pageSize: Int = PAGE_SIZE): Flow<PagingData<ThreadEntity>> {
        val byAccount = label.bindings.associate { it.accountKey to it.mailboxId }

        return feed(label.feedId, pageSize, skipAccountsWithoutFilter = true) { accountKey, _ ->
            byAccount[accountKey]?.let { EmailFilter.InMailbox(MailboxId(it)) }
        }
    }

    /** Whichever list the sidebar has selected. One entry point, so the caller has no `when`. */
    fun forView(view: MailView, pageSize: Int = PAGE_SIZE): Flow<PagingData<ThreadEntity>> =
        when (view) {
            MailView.Inbox -> unifiedInbox(pageSize)
            is MailView.Category -> category(view.category, pageSize)
            is MailView.Labelled -> labelled(view.label, pageSize)
        }

    private fun feed(
        feedId: String,
        pageSize: Int,
        skipAccountsWithoutFilter: Boolean = false,
        filterFor: suspend (accountKey: String, accountId: AccountId) -> EmailFilter?,
    ): Flow<PagingData<ThreadEntity>> = flow {
        val connection = credentials.connection.first()

        if (connection == null) {
            // No server yet. An empty stream rather than an error: onboarding
            // is what fixes this, and it is already on screen.
            emitAll(flowOf(PagingData.empty()))
            return@flow
        }

        val client =
            JmapClient(
                discoveryUrl = connection.address.discoveryUrl,
                credential = connection.credential,
                transport = transports.create(connection.address, connection.pinnedKey),
            )

        val server = connection.address.origin

        // The accounts this server has already been seen to have. Everything
        // needed to build a pager is in this row -- the JMAP account id and the
        // key the mailbox and label tables are scoped by -- and the pager needs
        // nothing else, because `JmapClient.session()` is cached, single-flighted
        // and called lazily by `send()`. So the round trip happens if and only if
        // the mediator actually goes to the network, which it skips whenever the
        // feed table can answer.
        val cached = database.accounts().all().filter { it.serverId == server }

        if (cached.isNotEmpty()) {
            // Behind the rows, not in front of them. This preamble used to be
            // blocking, and it defeated the whole of `FeedMediator`: the mediator
            // skips its initial refresh precisely so a cold launch draws from
            // disk, and then every cold launch and every sidebar switch sat on
            // "Fetching your mail…" waiting for a session and a MailboxGet per
            // account -- for names, bindings and identities that were already in
            // Room.
            scope.launch { refreshDirectory(client, server) }

            emitAll(
                pagerFor(
                    feedId = feedId,
                    pageSize = pageSize,
                    client = client,
                    accounts = cached.map { it.uid to AccountId(it.accountId) },
                    skipAccountsWithoutFilter = skipAccountsWithoutFilter,
                    filterFor = filterFor,
                )
            )
            return@flow
        }

        // Nothing cached -- a first run, right after pairing. There is no list to
        // show behind a background refresh, so the directory is fetched in front
        // of the first page as it always was.
        //
        // The session is the one call here that can fail before any row exists,
        // and it throws rather than returning — a revoked app password is a
        // `NotAuthenticated`, and an unreachable server an IO failure. Left to
        // propagate it reaches `cachedIn(viewModelScope)` with nothing between,
        // which is an uncaught exception on the main thread: the app dies at
        // launch rather than showing the mail it already has on disk. Found by
        // reseeding the test stack, which is exactly what "my credential stopped
        // working" looks like.
        val session = runCatching {
            client.session()
        }
            .getOrElse { failure ->
                publishServerFailure(server, failure)

                // The cached rows, still paged from the local table. An
                // expired credential must not empty someone's inbox on
                // screen; it stops it being refreshed, which the banner
                // says.
                emitAll(cachedOnly(feedId, pageSize))
                return@flow
            }

        // Written before the first page, so the sidebar and the per-account
        // banner have names to show even while the first query is in flight.
        mail.replaceAccounts(server, session)

        // And the label bindings, because everything else needs them: the inbox
        // filter below, and every move action, which is expressed as adding or
        // removing a binding id that differs per account. Without this the list
        // silently pages the whole mailbox and archiving silently does nothing.
        refreshBindings(client, server, session.accountIds.map { AccountId(it.value) })

        // This *was* the directory refresh, so the throttle counts it. Without
        // this the very next view switch after pairing -- which is now a cached
        // one, because the lines above filled the accounts table -- would ask the
        // server for the whole directory again a second later.
        directoryLock.withLock { directoryRefreshedAt = now() }

        emitAll(
            pagerFor(
                feedId = feedId,
                pageSize = pageSize,
                client = client,
                accounts =
                    session.accountIds.map {
                        StoreKey.account(server, it.value) to AccountId(it.value)
                    },
                skipAccountsWithoutFilter = skipAccountsWithoutFilter,
                filterFor = filterFor,
            )
        )
    }

    /**
     * The paged list itself, over whichever accounts the caller resolved.
     *
     * Takes the accounts rather than finding them, because the two callers above differ in exactly
     * that: one read them from Room and one from a session it had to wait for. Everything after
     * that point is identical, and two copies of it is how the cached path and the first-run path
     * come to build subtly different pagers.
     */
    @OptIn(ExperimentalPagingApi::class)
    private suspend fun pagerFor(
        feedId: String,
        pageSize: Int,
        client: JmapClient,
        accounts: List<Pair<String, AccountId>>,
        skipAccountsWithoutFilter: Boolean,
        filterFor: suspend (accountKey: String, accountId: AccountId) -> EmailFilter?,
    ): Flow<PagingData<ThreadEntity>> {
        val sources = accounts.mapNotNull { (accountKey, accountId) ->
            // "In this label" is a binding id, and the binding differs per
            // account -- resolved per account rather than shared.
            val filter = filterFor(accountKey, accountId)

            // A list scoped to one label must exclude an account that does
            // not have it. Falling through to an unfiltered pager here would
            // merge that account's *whole* mailbox into the label's rows,
            // which reads as a filter that stopped working rather than as an
            // account that never had the label.
            if (filter == null && skipAccountsWithoutFilter) return@mapNotNull null

            AccountPager(
                accountKey = accountKey,
                accountId = accountId,
                client = client,
                filter = filter,
                onPage = { emails, threads, state ->
                    mail.storeEmails(accountKey, emails, threads, fetchedAt = now())

                    // The cursor delta sync resumes from. Recorded here
                    // because a page is the only place it is reported, and
                    // without it every push triggers a sync that finds no
                    // cursor and gives up.
                    mail.recordEmailState(accountKey, state)
                },
            )
        }

        return Pager(
                config =
                    PagingConfig(
                        pageSize = pageSize,
                        // Room's PagingSource reads from disk, so a generous
                        // prefetch costs a query rather than a round trip.
                        prefetchDistance = pageSize,
                        enablePlaceholders = false,
                    ),
                remoteMediator =
                    FeedMediator(
                        feedId = feedId,
                        database = database,
                        sources = sources,
                        onFailures = { failed -> _failures.update { failed } },
                        onRowsHeld = { held -> mediatorRowCounts.tryEmit(feedId to held) },
                    ),
                pagingSourceFactory = { database.feed().pagingSource(feedId) },
            )
            .flow
    }

    /**
     * Brings account names, label bindings and identities up to date, behind a list already drawn.
     *
     * Throttled and single-flighted together — see [directoryLock]. The suspension on the lock is
     * what makes the throttle free: a caller that arrives during a refresh waits for it and then
     * finds its own work already done, rather than deciding on a stale timestamp.
     */
    private suspend fun refreshDirectory(client: JmapClient, server: String) {
        directoryLock.withLock {
            if (now() - directoryRefreshedAt < DIRECTORY_REFRESH_INTERVAL) return

            val session = runCatching {
                client.session()
            }
                .getOrElse { failure ->
                    // The same banner the blocking path raised, for the same
                    // reason: the list is drawing rows nothing is refreshing, and
                    // the one thing worse than saying so is not saying so. The
                    // rows stay -- this path never touches them.
                    publishServerFailure(server, failure)
                    return
                }

            mail.replaceAccounts(server, session)
            refreshBindings(client, server, session.accountIds.map { AccountId(it.value) })

            // Stamped only where the session answered -- the failure path above
            // returns without setting it, so a server that is down is retried on
            // the next view switch rather than being throttled out for the whole
            // interval.
            directoryRefreshedAt = now()
        }
    }

    /** Mailboxes and identities, per account, each account's failure its own. */
    private suspend fun refreshBindings(
        client: JmapClient,
        server: String,
        accountIds: List<AccountId>,
    ) {
        accountIds.forEach { accountId ->
            val accountKey = StoreKey.account(server, accountId.value)

            runCatching {
                val request = RequestBuilder()
                val mailboxes = request.add(MailboxGet(accountId))
                // In the same batch rather than its own round trip.
                // Identities are what the composer's From row is drawn from,
                // and a composer opened before the first sync would otherwise
                // have nothing to send as -- against a server on a domestic
                // uplink, one request is the resource worth saving.
                val identities = request.add(IdentityGet(accountId))
                val results = client.send(request)

                mail.replaceMailboxes(accountKey, results.result(mailboxes).list)
                mail.replaceIdentities(accountKey, results.result(identities).list)
            }
                // This account just answered two methods, which is the whole of
                // what a "could not reach it" banner claims it cannot do. Said
                // here rather than left to the next sync: on the cached path
                // nothing else runs, so a banner raised by one failed refresh
                // would sit over mail that is being refreshed perfectly well.
                .onSuccess { answered(accountKey) }
        }
    }

    private fun publishServerFailure(server: String, failure: Throwable) {
        _failures.update {
            listOf(
                SourceFailure(
                    // The origin, because there is nothing else: the call that
                    // would have named the accounts is the one that just failed.
                    accountKey = server,
                    error = failure,
                    isWholeServer = true,
                )
            )
        }
    }

    /**
     * The feed table alone, with no mediator behind it.
     *
     * What the list falls back to when the server cannot be reached at all. Paging without a
     * `RemoteMediator` simply never appends, which is the honest behaviour: there is nothing to
     * append from.
     */
    private fun cachedOnly(feedId: String, pageSize: Int): Flow<PagingData<ThreadEntity>> =
        Pager(
                config =
                    PagingConfig(
                        pageSize = pageSize,
                        prefetchDistance = pageSize,
                        enablePlaceholders = false,
                    ),
                pagingSourceFactory = { database.feed().pagingSource(feedId) },
            )
            .flow

    private fun now(): Long = System.currentTimeMillis()

    private companion object {
        const val PAGE_SIZE = 25
        const val INBOX_ROLE = "inbox"

        /**
         * How long a directory refresh stands for.
         *
         * A minute, not an hour: mailbox and identity lists change rarely, but when they do — a
         * label created in the browser — the user is usually about to look for it. Long enough that
         * flipping between four views costs one refresh rather than four, which is the only thing
         * this is defending against; the periodic sync covers the rest.
         */
        const val DIRECTORY_REFRESH_INTERVAL = 60_000L
    }
}

private suspend fun <T> kotlinx.coroutines.flow.FlowCollector<T>.emitAll(source: Flow<T>) {
    source.collect { emit(it) }
}
