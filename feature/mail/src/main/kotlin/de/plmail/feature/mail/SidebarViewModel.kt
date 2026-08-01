package de.plmail.feature.mail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.plmail.core.data.Label
import de.plmail.core.data.LabelRepository
import de.plmail.core.data.MailRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The label list the navigation draws, straight from the cache. */
@HiltViewModel
class SidebarViewModel @Inject constructor(labels: LabelRepository, accounts: MailRepository) :
    ViewModel() {

    /**
     * Recomputed when either the mailboxes or the accounts change.
     *
     * The account list is in here because a label row is a sum across accounts: an account
     * disappearing has to take its share of the unread count with it, and a list keyed only on
     * mailboxes would keep showing the total until something else happened to touch a mailbox row.
     */
    val labels: StateFlow<List<Label>> =
        combine(labels.observeLabels(), accounts.observeAccounts()) { all, _ -> all }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = emptyList(),
            )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/** What the label dialog is doing, and what the server said about it. */
data class LabelEditorState(
    val isWorking: Boolean = false,
    val isDone: Boolean = false,
    val deleted: Label? = null,
    val error: String? = null,
)

/**
 * Create, rename and delete, against the server rather than against the cache.
 *
 * Not local-first, unlike every mail action. A created label has no id until the server assigns
 * one, and the id is what every subsequent apply is addressed to — a locally invented label could
 * be ticked onto a conversation before it existed, and there would be nothing to send.
 */
@HiltViewModel
class LabelEditorViewModel @Inject constructor(private val labels: LabelRepository) : ViewModel() {

    private val _state = MutableStateFlow(LabelEditorState())
    val state: StateFlow<LabelEditorState> = _state.asStateFlow()

    fun create(name: String) = work { labels.create(name.trim()) }

    fun rename(label: Label, name: String) = work { labels.rename(label, name.trim()) }

    fun delete(label: Label, all: List<Label>) = work(deleted = label) { labels.delete(label, all) }

    fun acknowledge() {
        _state.value = LabelEditorState()
    }

    private fun work(deleted: Label? = null, block: suspend () -> Unit) {
        _state.value = LabelEditorState(isWorking = true)

        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { _state.value = LabelEditorState(isDone = true, deleted = deleted) }
                .onFailure {
                    _state.value = LabelEditorState(error = it.message ?: "That did not work.")
                }
        }
    }
}
