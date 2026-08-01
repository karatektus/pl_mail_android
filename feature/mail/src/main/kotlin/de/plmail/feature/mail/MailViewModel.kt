package de.plmail.feature.mail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import de.plmail.core.data.ActionOutcome
import de.plmail.core.data.ActionTarget
import de.plmail.core.data.FeedRepository
import de.plmail.core.data.Label
import de.plmail.core.data.LabelRepository
import de.plmail.core.data.LabelSelection
import de.plmail.core.data.MailAction
import de.plmail.core.data.MailActions
import de.plmail.core.data.MailRepository
import de.plmail.core.data.UndoableAction
import de.plmail.core.database.ThreadEntity
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** An account that is not answering, named so the banner can say which. */
data class UnreachableAccount(val accountKey: String, val displayName: String)

/** The "Label as" sheet, once its ticks have been resolved. */
data class LabelSheetState(val targets: List<ActionTarget>, val selection: LabelSelection)

/** A change to announce, with the undo that reverses it. */
data class ActionAnnouncement(
    val outcome: ActionOutcome,
    /**
     * Distinguishes two identical archives so the snackbar re-shows rather than being deduplicated.
     */
    val id: Long,
)

@HiltViewModel
class MailViewModel
@Inject
constructor(
    feed: FeedRepository,
    private val mail: MailRepository,
    private val actions: MailActions,
    private val labelRepository: LabelRepository,
) : ViewModel() {

    private val _announcement = MutableStateFlow<ActionAnnouncement?>(null)
    val announcement: StateFlow<ActionAnnouncement?> = _announcement.asStateFlow()

    private val _selection = MutableStateFlow<Set<String>>(emptySet())
    val selection: StateFlow<Set<String>> = _selection.asStateFlow()

    private var announcements = 0L

    fun toggleSelected(uid: String) {
        _selection.update { if (uid in it) it - uid else it + uid }
    }

    fun clearSelection() {
        _selection.update { emptySet() }
    }

    /**
     * Applies an action and announces the result.
     *
     * The selection is cleared immediately rather than when the server answers: the rows are gone
     * from the list the moment the local write lands, and a selection referring to conversations
     * that are no longer shown is a checkbox nobody can uncheck.
     */
    fun apply(action: MailAction, targets: List<ActionTarget>) {
        if (targets.isEmpty()) return
        clearSelection()

        viewModelScope.launch {
            val outcome = actions.apply(action, targets)
            _announcement.update { ActionAnnouncement(outcome, announcements++) }
        }
    }

    fun undo(undoable: UndoableAction) {
        viewModelScope.launch {
            val outcome = actions.undo(undoable)
            _announcement.update { ActionAnnouncement(outcome, announcements++) }
        }
    }

    fun announcementShown(id: Long) {
        _announcement.update { current -> current?.takeIf { it.id != id } }
    }

    /**
     * Every label, for the "Label as" sheet. The sidebar reads the same list through its own VM.
     */
    val labels: StateFlow<List<Label>> =
        labelRepository
            .observeLabels()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = emptyList(),
            )

    private val _labelSheet = MutableStateFlow<LabelSheetState?>(null)
    val labelSheet: StateFlow<LabelSheetState?> = _labelSheet.asStateFlow()

    /**
     * Opens the sheet over a set of conversations, having first worked out which labels they carry.
     *
     * Resolved before the sheet is shown rather than inside it, so the ticks are right on the first
     * frame. A sheet that opens with everything unticked and then corrects itself teaches people
     * that the ticks cannot be trusted.
     */
    fun openLabelSheet(targets: List<ActionTarget>) {
        if (targets.isEmpty()) return

        viewModelScope.launch {
            val applied = labelRepository.appliedTo(labels.value, targets)

            _labelSheet.update { LabelSheetState(targets = targets, selection = applied) }
        }
    }

    fun closeLabelSheet() {
        _labelSheet.update { null }
    }

    private val shown = MutableStateFlow<Label?>(null)

    /**
     * Which label the list is showing. Null until the sidebar has been read, which means the inbox.
     */
    fun show(label: Label?) {
        shown.value = label
    }

    /**
     * `cachedIn` so the pages survive a rotation.
     *
     * Without it the list re-collects on every configuration change, which on this product means
     * re-querying somebody's NAS because the user turned their phone sideways.
     *
     * `flatMapLatest` rather than a pager per label held open: switching label has to cancel the
     * previous list's loading, or a slow first page for Archive keeps writing into the feed table
     * after the user has moved on to Sent.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val threads: Flow<PagingData<ThreadEntity>> =
        shown
            // The Inbox label and the unified inbox are the same mail seen two
            // ways, so both collapse to null here and neither restarts the
            // other. That is not tidiness: the sidebar arrives a moment after
            // the first frame, so the list is created with no label and then
            // told about Inbox -- and without this the second one cancels the
            // page already in flight and starts again for the same rows.
            .map { label -> label?.takeIf { it.role != INBOX_ROLE } }
            .distinctUntilChanged { old, new -> old?.feedId == new?.feedId }
            .flatMapLatest { label ->
                if (label == null) feed.unifiedInbox() else feed.labelled(label)
            }
            .cachedIn(viewModelScope)

    /**
     * Failures resolved to names the user recognises.
     *
     * The account row carries the address; the failure only carries a key. Joining them here keeps
     * the banner from saying `https://nas.local/13`.
     */
    val unreachable: StateFlow<List<UnreachableAccount>> =
        combine(feed.failures, mail.observeAccounts()) { failures, accounts ->
                val names = accounts.associate { it.uid to it.name }

                failures.map {
                    UnreachableAccount(
                        accountKey = it.accountKey,
                        displayName = names[it.accountKey] ?: it.accountKey,
                    )
                }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = emptyList(),
            )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val INBOX_ROLE = "inbox"
    }
}
