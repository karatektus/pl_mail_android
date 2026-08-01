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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
) {

    private val _failures = MutableStateFlow<List<SourceFailure>>(emptyList())

    /**
     * Accounts that could not be reached on the last pull.
     *
     * Separate from the paging stream on purpose: a failure must not interrupt the rows, because
     * the whole point is that three working accounts keep drawing while a fourth is down.
     */
    val failures = _failures.asStateFlow()

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
                    listOf(SourceFailure(accountKey = connection.address.origin, error = failure))
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
