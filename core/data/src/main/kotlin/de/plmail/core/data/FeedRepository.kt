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
    repages: RepageSignal,
) : ReachableAccounts {

    private val _failures = MutableStateFlow<List<SourceFailure>>(emptyList())

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

    @OptIn(ExperimentalPagingApi::class)
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

        // The session is the one call in this flow that can fail before any row
        // exists, and it throws rather than returning — a revoked app password
        // is a `NotAuthenticated`, and an unreachable server an IO failure. Left
        // to propagate it reaches `cachedIn(viewModelScope)` with nothing
        // between, which is an uncaught exception on the main thread: the app
        // dies at launch rather than showing the mail it already has on disk.
        // Found by reseeding the test stack, which is exactly what "my
        // credential stopped working" looks like.
        val session = runCatching {
            client.session()
        }
            .getOrElse { failure ->
                _failures.update {
                    listOf(
                        SourceFailure(
                            // The origin, because there is nothing else: the
                            // call that would have named the accounts is the
                            // one that just failed.
                            accountKey = connection.address.origin,
                            error = failure,
                            isWholeServer = true,
                        )
                    )
                }

                // The cached rows, still paged from the local table. An
                // expired credential must not empty someone's inbox on
                // screen; it stops it being refreshed, which the banner
                // says.
                emitAll(cachedOnly(feedId, pageSize))
                return@flow
            }

        val server = connection.address.origin

        // Written before the first page, so the sidebar and the per-account
        // banner have names to show even while the first query is in flight.
        mail.replaceAccounts(server, session)

        // And the label bindings, because everything else needs them: the inbox
        // filter below, and every move action, which is expressed as adding or
        // removing a binding id that differs per account. Without this the list
        // silently pages the whole mailbox and archiving silently does nothing.
        session.accountIds.forEach { accountId ->
            val accountKey = StoreKey.account(server, accountId.value)

            runCatching {
                val request = RequestBuilder()
                val mailboxes = request.add(MailboxGet(AccountId(accountId.value)))
                // In the same batch rather than its own round trip. Identities
                // are what the composer's From row is drawn from, and a composer
                // opened before the first sync would otherwise have nothing to
                // send as -- against a server on a domestic uplink, one request
                // is the resource worth saving.
                val identities = request.add(IdentityGet(AccountId(accountId.value)))
                val results = client.send(request)

                mail.replaceMailboxes(accountKey, results.result(mailboxes).list)
                mail.replaceIdentities(accountKey, results.result(identities).list)
            }
        }

        val sources =
            session.accountIds.mapNotNull { accountId ->
                val accountKey = StoreKey.account(server, accountId.value)
                // "In this label" is a binding id, and the binding differs per
                // account -- resolved per account rather than shared.
                val filter = filterFor(accountKey, AccountId(accountId.value))

                // A list scoped to one label must exclude an account that does
                // not have it. Falling through to an unfiltered pager here would
                // merge that account's *whole* mailbox into the label's rows,
                // which reads as a filter that stopped working rather than as an
                // account that never had the label.
                if (filter == null && skipAccountsWithoutFilter) return@mapNotNull null

                AccountPager(
                    accountKey = accountKey,
                    accountId = AccountId(accountId.value),
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

        emitAll(
            Pager(
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
                            onRowsHeld = { held ->
                                mediatorRowCounts.tryEmit(feedId to held)
                            },
                        ),
                    pagingSourceFactory = { database.feed().pagingSource(feedId) },
                )
                .flow
        )
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
    }
}

private suspend fun <T> kotlinx.coroutines.flow.FlowCollector<T>.emitAll(source: Flow<T>) {
    source.collect { emit(it) }
}
