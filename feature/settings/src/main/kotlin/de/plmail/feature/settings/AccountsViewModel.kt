package de.plmail.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.plmail.core.data.AccountSummary
import de.plmail.core.data.AccountsRepository
import de.plmail.jmap.protocol.SyncWindow
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the accounts screen is showing. */
data class AccountsState(val accounts: List<AccountSummary> = emptyList())

@HiltViewModel
class AccountsViewModel @Inject constructor(private val accounts: AccountsRepository) :
    ViewModel() {

    /**
     * What the server says it holds, read once when the screen opens.
     *
     * Held beside the list rather than joined into the database flow because it comes from the
     * session, which is a suspending read of something already cached rather than a table anything
     * observes.
     *
     * **No button, and no request.** This used to be an `Email/query` per account behind "Ask what
     * the server holds", because the only way to find the boundary was to ask for the oldest
     * message. The session now carries the window itself and the app has already fetched the
     * session before this screen exists, so the honest thing is to draw it.
     */
    private val windows = MutableStateFlow<Map<String, SyncWindow>>(emptyMap())

    init {
        viewModelScope.launch { windows.value = accounts.serverWindows() }
    }

    val state: StateFlow<AccountsState> =
        combine(accounts.summaries, windows) { summaries, server ->
                AccountsState(summaries.map { it.copy(serverWindow = server[it.accountKey]) })
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = AccountsState(),
            )

    /**
     * Moves an account one place.
     *
     * The repository takes the account key rather than a pair of indices, because the list this
     * screen is drawing and the list the store holds are two different things — see
     * `AccountsRepository.move`.
     */
    fun move(accountKey: String, by: Int) {
        viewModelScope.launch { accounts.move(accountKey, by) }
    }

    fun setNotifying(accountKey: String, notifying: Boolean) {
        viewModelScope.launch { accounts.setNotifying(accountKey, notifying) }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
