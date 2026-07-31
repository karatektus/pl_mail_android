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
import de.plmail.jmap.methods.MailboxGet
import de.plmail.jmap.protocol.AccountId
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

    @OptIn(ExperimentalPagingApi::class)
    fun unifiedInbox(pageSize: Int = PAGE_SIZE): Flow<PagingData<ThreadEntity>> = flow {
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

        val session = client.session()
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
                val get = request.add(MailboxGet(AccountId(accountId.value)))

                mail.replaceMailboxes(accountKey, client.send(request).result(get).list)
            }
        }

        val sources =
            session.accountIds.map { accountId ->
                val accountKey = StoreKey.account(server, accountId.value)

                AccountPager(
                    accountKey = accountKey,
                    accountId = AccountId(accountId.value),
                    client = client,
                    // "In the inbox" is a label binding, and the binding id
                    // differs per account -- resolved per account rather than
                    // shared.
                    filter = inboxFilter(accountKey),
                    onPage = { emails, state ->
                        mail.storeEmails(accountKey, emails, fetchedAt = now())

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
                            feedId = Feed.UNIFIED_INBOX.id,
                            database = database,
                            sources = sources,
                            onFailures = { failed -> _failures.update { failed } },
                        ),
                    pagingSourceFactory = { database.feed().pagingSource(Feed.UNIFIED_INBOX.id) },
                )
                .flow
        )
    }

    /**
     * The Inbox binding for one account, or null when it has none.
     *
     * Null pages everything rather than nothing: an account whose mailboxes have not been synced
     * yet would otherwise contribute no rows at all, and the unified inbox would silently be
     * missing an entire mailbox on first run.
     */
    private suspend fun inboxFilter(accountKey: String): EmailFilter? =
        database.mailboxes().byRole(accountKey, INBOX_ROLE)?.let {
            EmailFilter.InMailbox(de.plmail.jmap.protocol.MailboxId(it.mailboxId))
        }

    private fun now(): Long = System.currentTimeMillis()

    private companion object {
        const val PAGE_SIZE = 25
        const val INBOX_ROLE = "inbox"
    }
}

private suspend fun <T> kotlinx.coroutines.flow.FlowCollector<T>.emitAll(source: Flow<T>) {
    source.collect { emit(it) }
}
