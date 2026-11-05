package de.plmail.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.plmail.core.data.InboxTabRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The web's Inbox tabs switch, from the phone (#11).
 *
 * **The account's, not this device's** — the one switch on the Appearance screen that is, and its
 * body says so. Splitting the inbox into tabs is a choice about the mail, and the web, this phone
 * and every other client draw the same lists from it. Written to the server first and shown only
 * once it has landed, so the switch never claims a state the server does not hold.
 */
@HiltViewModel
class InboxTabsViewModel @Inject constructor(private val tabs: InboxTabRepository) : ViewModel() {

    val wantsTabs: StateFlow<Boolean> =
        tabs.wantsTabs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), true)

    val hasTabs: StateFlow<Boolean> =
        tabs.serverHasTabs.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT),
            false,
        )

    /** Set while a write is on its way, so a second tap cannot race it. */
    var isSaving by mutableStateOf(false)
        private set

    /** Set when the last write did not land, until the next one. */
    var failed by mutableStateOf(false)
        private set

    fun setWantsTabs(wanted: Boolean) {
        if (isSaving) return

        isSaving = true
        failed = false

        viewModelScope.launch {
            failed = !tabs.setWantsTabs(wanted)
            isSaving = false
        }
    }

    private companion object {
        const val STOP_TIMEOUT = 5_000L
    }
}

/** Inbox tabs on or off, for this person everywhere. Absent on a server without tabs. */
@Composable
internal fun InboxTabsSection(viewModel: InboxTabsViewModel = hiltViewModel()) {
    val hasTabs by viewModel.hasTabs.collectAsStateWithLifecycle()
    val wantsTabs by viewModel.wantsTabs.collectAsStateWithLifecycle()

    if (!hasTabs) return

    Section(stringResource(R.string.appearance_inbox)) {
        Toggle(
            title = stringResource(R.string.appearance_inbox_tabs),
            body =
                stringResource(
                    if (viewModel.failed) R.string.appearance_inbox_tabs_failed
                    else R.string.appearance_inbox_tabs_body
                ),
            isOn = wantsTabs,
            onChange = viewModel::setWantsTabs,
            isEnabled = !viewModel.isSaving,
        )
    }
}
