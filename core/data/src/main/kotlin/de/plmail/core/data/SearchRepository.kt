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
import de.plmail.jmap.methods.SearchSnippet
import de.plmail.jmap.protocol.AccountId
import de.plmail.jmap.protocol.MailboxId
import de.plmail.jmap.search.CompiledSearch
import de.plmail.jmap.search.SearchQuery
import de.plmail.jmap.search.SearchQueryCompiler
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update

/**
 * Why an account contributed nothing, when it contributed nothing.
 *
 * Only [NoSuchMailbox] is worth showing: it means `in:` named a role this account has no binding
 * for, so the account was skipped entirely rather than searched. Silently returning no rows for it
 * would read as "nothing matched here", which is a different and wrong statement.
 */
data class SkippedAccount(val accountKey: String, val reason: SkipReason)

enum class SkipReason {
    /** `in:` named a role this account does not have. */
    NoSuchMailbox
}

/**
 * Search, across every account behind the credential.
 *
 * The parsing and compiling are already done — [SearchQuery] and [SearchQueryCompiler] — so this is
 * the part that knows about accounts: a role means a different mailbox id in each one, and an
 * account missing that role must be *skipped* rather than searched unfiltered. Getting that wrong
 * returns an account's whole mailbox as if it were the search result, which is the single most
 * expensive mistake available here.
 *
 * Results are paged through the same [FeedMediator] the inbox uses, under their own feed id, with
 * one deliberate difference: cached rows are never shown first. They belong to the *previous*
 * query.
 */
@Singleton
class SearchRepository
@Inject
constructor(
    private val database: PlMailDatabase,
    private val credentials: CredentialStore,
    private val transports: TransportFactory,
    private val mail: MailRepository,
) {

    private val _snippets = MutableStateFlow<Map<String, SearchSnippet>>(emptyMap())

    /**
     * The matched fragments, keyed as `accountKey#threadId`.
     *
     * Keyed by *thread* although the server answers per message, because the list draws threads and
     * that is the lookup a row can perform. The remap is unambiguous only because the query
     * collapses threads: exactly one message comes back per conversation, so there is exactly one
     * snippet to attach. It is done where both ids are in hand rather than left to the UI to join.
     *
     * In memory rather than in the database, and cleared with each new query, because a snippet is
     * only meaningful for the search that produced it. Persisting them would mean a row eventually
     * showing a highlight for a word the current query does not contain.
     */
    val snippets = _snippets.asStateFlow()

    private val _skipped = MutableStateFlow<List<SkippedAccount>>(emptyList())

    /** Accounts that could not answer the query at all. See [SkippedAccount]. */
    val skipped = _skipped.asStateFlow()

    private val _failures = MutableStateFlow<List<SourceFailure>>(emptyList())

    /**
     * Accounts that were unreachable. Separate from the rows, so three working accounts still draw.
     */
    val failures = _failures.asStateFlow()

    /**
     * Runs [raw] and returns its results as pages.
     *
     * An empty or whitespace query returns an empty stream rather than every message: a search box
     * that has been focused but not typed into has not asked for anything, and answering it with
     * the entire mailbox is both wrong and expensive.
     */
    @OptIn(ExperimentalPagingApi::class)
    fun search(raw: String, pageSize: Int = PAGE_SIZE): Flow<PagingData<ThreadEntity>> = flow {
        val query = SearchQuery.parse(raw)

        if (query.isEmpty) {
            emitAll(flowOf(PagingData.empty()))
            return@flow
        }

        val connection = credentials.connection.first()

        if (connection == null) {
            emitAll(flowOf(PagingData.empty()))
            return@flow
        }

        // A new query invalidates the last one's highlights before any row can
        // be drawn against them.
        _snippets.value = emptyMap()
        _skipped.value = emptyList()

        val client =
            JmapClient(
                discoveryUrl = connection.address.discoveryUrl,
                credential = connection.credential,
                transport = transports.create(connection.address, connection.pinnedKey),
            )

        val session = client.session()
        val server = connection.address.origin

        val skippedAccounts = mutableListOf<SkippedAccount>()

        val sources =
            session.accountIds.mapNotNull { accountId ->
                val accountKey = StoreKey.account(server, accountId.value)

                when (val compiled = compile(query, accountKey)) {
                    // The account has no binding for the role `in:` named. Not
                    // searched: an unfiltered query here would return its entire
                    // mailbox under the heading of a search result.
                    CompiledSearch.MatchesNothing -> {
                        skippedAccounts += SkippedAccount(accountKey, SkipReason.NoSuchMailbox)
                        null
                    }

                    // Cannot happen for a non-empty query — every operator
                    // compiles to at least one condition — but the type says it
                    // can, and searching unfiltered is the one outcome worth
                    // refusing outright rather than handling loosely.
                    CompiledSearch.MatchesEverything -> null

                    is CompiledSearch.Filter ->
                        SearchPager(
                            accountKey = accountKey,
                            accountId = AccountId(accountId.value),
                            client = client,
                            filter = compiled.filter,
                            onPage = { emails, pageSnippets ->
                                // Cached like any other message, so opening a
                                // result works offline and the reader has a row
                                // to draw. The Email state is deliberately not
                                // recorded here -- see SearchPager.
                                mail.storeEmails(accountKey, emails, fetchedAt = now())

                                // Re-keyed onto the thread the row will draw. A
                                // message whose thread id is missing falls back
                                // to its own id, which is what the feed row uses
                                // for it too.
                                val threadOf = emails.associate { email ->
                                    email.id.value to (email.threadId?.value ?: email.id.value)
                                }

                                _snippets.update { existing ->
                                    existing +
                                        pageSnippets.mapNotNull { (emailId, snippet) ->
                                            val threadId =
                                                threadOf[emailId] ?: return@mapNotNull null

                                            StoreKey.objectKey(accountKey, threadId) to snippet
                                        }
                                }
                            },
                        )
                }
            }

        _skipped.value = skippedAccounts

        emitAll(
            Pager(
                    config =
                        PagingConfig(
                            pageSize = pageSize,
                            prefetchDistance = pageSize,
                            enablePlaceholders = false,
                        ),
                    remoteMediator =
                        FeedMediator(
                            feedId = Feed.SEARCH.id,
                            database = database,
                            sources = sources,
                            onFailures = { failed -> _failures.update { failed } },
                            // The table holds the last query's results, which are
                            // not an answer to this one.
                            cachedRowsAnswerThis = false,
                        ),
                    pagingSourceFactory = { database.feed().pagingSource(Feed.SEARCH.id) },
                )
                .flow
        )
    }

    /** Drops the last query's rows, so re-entering search does not flash them. */
    suspend fun clear() {
        database.feed().clearFeed(Feed.SEARCH.id)
        _snippets.value = emptyMap()
        _skipped.value = emptyList()
        _failures.value = emptyList()
    }

    /**
     * Resolves `in:` against one account's stored mailboxes.
     *
     * Read from the database rather than the network: the bindings are already synced by the inbox,
     * and a round trip per account per keystroke would make search unusable on the uplink this
     * product targets.
     */
    private suspend fun compile(query: SearchQuery, accountKey: String): CompiledSearch {
        val mailboxes = database.mailboxes().forAccount(accountKey)

        return SearchQueryCompiler.compile(query) { role ->
            mailboxes.firstOrNull { it.role == role.wire }?.let { MailboxId(it.mailboxId) }
        }
    }

    private fun now(): Long = System.currentTimeMillis()

    private companion object {
        const val PAGE_SIZE = 25
    }
}

private suspend fun <T> kotlinx.coroutines.flow.FlowCollector<T>.emitAll(source: Flow<T>) {
    source.collect { emit(it) }
}
