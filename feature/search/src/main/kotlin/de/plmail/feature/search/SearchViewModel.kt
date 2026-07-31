package de.plmail.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import de.plmail.core.data.SearchRepository
import de.plmail.core.database.ThreadEntity
import de.plmail.core.datastore.RecentSearchStore
import de.plmail.jmap.search.SearchQuery
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the search screen is showing, apart from the rows.
 *
 * The rows are a [PagingData] stream of their own, because Paging owns its loading state and a
 * second copy of it here would drift.
 */
data class SearchUiState(
    val query: String = "",
    val recent: List<String> = emptyList(),
    /** The parsed query, so the chips can show what the string actually means. */
    val parsed: SearchQuery = SearchQuery(),
    /** Set once results come back empty; see [SearchViewModel.onEmptyResults]. */
    val emptyReason: EmptyReason? = null,
) {
    /** Whether the box holds something worth running. */
    val isSearchable: Boolean
        get() = query.isNotBlank()
}

/** Why there is nothing to show, when there is nothing to show. */
sealed interface EmptyReason {

    /** Nothing has been typed. Show recents, not an apology. */
    data object NotAsked : EmptyReason

    /** The search ran and genuinely matched nothing. */
    data object NoMatches : EmptyReason

    /**
     * The search ran, matched nothing, and asked about a time the server has no mail for.
     *
     * The distinction matters: mail older than what the server has synced is not searchable, so "no
     * results" would be a true sentence that means the wrong thing. [oldest] is the earliest
     * message the server actually holds — an observed fact, not a policy, because nothing in the
     * JMAP session reports a retention window.
     */
    data class OutsideSyncedRange(val oldest: Instant) : EmptyReason
}

@HiltViewModel
class SearchViewModel
@Inject
constructor(
    private val search: SearchRepository,
    private val recents: RecentSearchStore,
) : ViewModel() {

    private val _query = MutableStateFlow("")

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    /** The matched fragments, keyed `accountKey#threadId` — the row's own identity. */
    val snippets = search.snippets

    /** Accounts skipped because `in:` named a mailbox they do not have. */
    val skipped = search.skipped

    /**
     * The results.
     *
     * Debounced, because this goes to a server on someone's home uplink and a request per keystroke
     * would queue behind itself — the session allows four concurrent requests, and a fast typist
     * produces more than that per second. [flatMapLatest] then abandons a query the moment a newer
     * one exists, so the rows can never be the answer to a prefix of what is now in the box.
     */
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val results: StateFlow<PagingData<ThreadEntity>> =
        _query
            .debounce { if (it.isBlank()) 0 else DEBOUNCE_MILLIS }
            .distinctUntilChanged()
            .flatMapLatest { query ->
                if (query.isBlank()) flowOf(PagingData.empty()) else search.search(query)
            }
            .cachedIn(viewModelScope)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PagingData.empty())

    init {
        viewModelScope.launch {
            recents.recent.collect { list -> _state.update { it.copy(recent = list) } }
        }
    }

    fun onQueryChanged(query: String) {
        _query.value = query

        // Parsed on every keystroke so the chips describe what will actually
        // run. It is a string walk with no I/O -- cheaper than the recomposition
        // it feeds.
        _state.update {
            it.copy(
                query = query,
                parsed = SearchQuery.parse(query),
                // Any edit invalidates the previous verdict. Leaving it would
                // show "nothing older than March" against a query the user has
                // since rewritten.
                emptyReason = if (query.isBlank()) EmptyReason.NotAsked else null,
            )
        }
    }

    /**
     * Records the query as recent.
     *
     * On submit rather than on keystroke: recording as the user types would fill the list with
     * every prefix of what they meant.
     */
    fun onSubmit() {
        val query = _state.value.query

        viewModelScope.launch { recents.record(query) }
    }

    fun onRecentChosen(query: String) {
        onQueryChanged(query)
        onSubmit()
    }

    fun onForgetRecent(query: String) {
        viewModelScope.launch { recents.forget(query) }
    }

    /**
     * Called when Paging reports the list settled with no rows.
     *
     * The extra query only happens here — once, on an empty dated search — rather than alongside
     * every search, because it is a round trip that exists purely to write a better sentence.
     */
    fun onEmptyResults() {
        val current = _state.value

        if (!current.isSearchable) {
            _state.update { it.copy(emptyReason = EmptyReason.NotAsked) }
            return
        }

        if (!current.parsed.hasDateBound) {
            _state.update { it.copy(emptyReason = EmptyReason.NoMatches) }
            return
        }

        viewModelScope.launch {
            val oldest = runCatching { search.oldestHeldMessage() }.getOrNull()

            _state.update { state ->
                state.copy(
                    emptyReason =
                        // Only when the query actually reaches past what the
                        // server holds. A dated search *inside* the synced range
                        // that found nothing really did find nothing, and
                        // blaming the window for it is its own dishonesty.
                        if (oldest != null && state.parsed.reachesBefore(oldest))
                            EmptyReason.OutsideSyncedRange(oldest)
                        else EmptyReason.NoMatches
                )
            }
        }
    }

    /** Leaves search, dropping the rows so re-entering does not flash the last query's results. */
    fun onLeave() {
        _query.value = ""
        _state.update { SearchUiState(recent = it.recent, emptyReason = EmptyReason.NotAsked) }

        viewModelScope.launch { search.clear() }
    }

    private companion object {
        /**
         * Long enough that a typed word is one request, short enough that the list feels live.
         * Search here is a network round trip, not a local index.
         */
        const val DEBOUNCE_MILLIS = 300L
    }
}

/**
 * Whether this query asks about a time before [boundary].
 *
 * Both bounds count, and for different reasons. `after:2019` asks for everything since a date the
 * server has no mail from, so its window starts outside what was synced. `before:2019` asks only
 * for mail older than that, which — if the boundary is later — is *entirely* outside it.
 *
 * `before` is compared inclusively because it is a strict `<` on the server: `before:` the oldest
 * message excludes that message too, leaving a window with nothing in it.
 */
internal fun SearchQuery.reachesBefore(boundary: Instant): Boolean =
    after?.isBefore(boundary) == true || before?.isAfter(boundary) == false
