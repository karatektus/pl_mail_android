package de.plmail.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.plmail.core.data.AccountSummary
import de.plmail.core.data.AccountsRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the accounts screen is showing, once the server has been asked anything. */
data class AccountsState(
    val accounts: List<AccountSummary> = emptyList(),
    val isAsking: Boolean = false,
    val askError: String? = null,
    /** True once the sweep has run, so "nothing came back" can be told from "not asked". */
    val hasAsked: Boolean = false,
)

/** What pressing "ask the server" left behind, held apart from the list it decorates. */
private data class ServerWindow(
    val oldest: Map<String, Long> = emptyMap(),
    val isAsking: Boolean = false,
    val error: String? = null,
    val hasAsked: Boolean = false,
)

@HiltViewModel
class AccountsViewModel @Inject constructor(private val accounts: AccountsRepository) :
    ViewModel() {

    private val asked = MutableStateFlow(ServerWindow())

    val state: StateFlow<AccountsState> =
        combine(accounts.summaries, asked) { summaries, window ->
                AccountsState(
                    accounts =
                        summaries.map { it.copy(oldestOnServer = window.oldest[it.accountKey]) },
                    isAsking = window.isAsking,
                    askError = window.error,
                    hasAsked = window.hasAsked,
                )
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

    /**
     * Asks every account how far back the server still holds mail.
     *
     * On the ViewModel's scope so leaving the screen does not cancel a sweep already in flight, the
     * same way the diagnostics check is written — and for a second reason here: the result is
     * cached in this ViewModel, so a cancelled sweep would leave the screen showing "asking…"
     * forever when the user came back.
     */
    fun askServer() {
        if (asked.value.isAsking) return

        asked.update { it.copy(isAsking = true, error = null) }

        viewModelScope.launch {
            val outcome = runCatching { accounts.oldestOnServer() }

            asked.update {
                ServerWindow(
                    oldest = outcome.getOrNull().orEmpty(),
                    isAsking = false,
                    error = outcome.exceptionOrNull()?.message,
                    hasAsked = true,
                )
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
