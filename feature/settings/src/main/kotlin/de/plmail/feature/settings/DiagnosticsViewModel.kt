package de.plmail.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.plmail.core.data.Diagnostics
import de.plmail.core.data.DiagnosticsReport
import de.plmail.core.data.RemoteSubscription
import de.plmail.core.datastore.PushState
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What pressing "check now" left behind, held separately from the report it decorates. */
private data class CheckState(
    val isChecking: Boolean = false,
    val subscription: RemoteSubscription? = null,
    val repaged: List<String> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(private val diagnostics: Diagnostics) : ViewModel() {

    private val check = MutableStateFlow(CheckState())

    val state: StateFlow<DiagnosticsReport> =
        combine(diagnostics.report, check) { report, checked ->
                report.copy(
                    isChecking = checked.isChecking,
                    subscriptionOnServer = checked.subscription,
                    checkError = checked.error,
                    repagedAccounts = checked.repaged,
                )
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue =
                    DiagnosticsReport(
                        server = null,
                        accounts = emptyList(),
                        push = PushState(),
                        distributor = null,
                        installedDistributors = emptyList(),
                    ),
            )

    /**
     * Runs the checks.
     *
     * On the ViewModel's scope, so leaving the screen mid-check does not cancel a sync that is
     * already talking to the server — the outcome is written into the account rows either way, and
     * a half-finished sync is worse than a finished one nobody watched.
     */
    fun checkNow() {
        if (check.value.isChecking) return

        check.update { it.copy(isChecking = true, error = null) }

        viewModelScope.launch {
            val outcome = runCatching { diagnostics.check() }

            check.update {
                CheckState(
                    isChecking = false,
                    subscription = outcome.getOrNull()?.subscription,
                    // Not folded into `error`: an account that has to re-page is
                    // not a failure and must not be drawn as one. It is the
                    // answer to "why is this list stale", which is a different
                    // sentence from "this is broken".
                    repaged = outcome.getOrNull()?.repaged.orEmpty(),
                    // The first failure, in its own words. Listing all of them
                    // would repeat what the per-account rows below already say;
                    // this line exists for the case where nothing reached an
                    // account at all.
                    error =
                        outcome.exceptionOrNull()?.message
                            ?: outcome.getOrNull()?.pushCheckError
                            ?: outcome.getOrNull()?.failures?.firstOrNull()?.message,
                )
            }
        }
    }

    fun retryPush() {
        viewModelScope.launch {
            val started = diagnostics.retryPush()

            if (!started) {
                check.update { it.copy(error = NO_DISTRIBUTOR) }
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L

        /**
         * Not a translated string, and that is on purpose for this one: it names the exact reason
         * registration cannot even be attempted, and the screen shows it beside the list of
         * installed distributors, which is empty. Translating it is M11's sweep.
         */
        const val NO_DISTRIBUTOR = "No push distributor is installed on this device."
    }
}
