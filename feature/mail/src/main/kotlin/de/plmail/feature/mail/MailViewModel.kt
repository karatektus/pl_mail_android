package de.plmail.feature.mail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import de.plmail.core.data.AccountsRepository
import de.plmail.core.data.ActionTarget
import de.plmail.core.data.Connectivity
import de.plmail.core.data.DeltaSync
import de.plmail.core.data.FeedRepository
import de.plmail.core.data.Label
import de.plmail.core.data.LabelRepository
import de.plmail.core.data.LabelSelection
import de.plmail.core.data.MailAction
import de.plmail.core.data.MailActions
import de.plmail.core.data.MailRepository
import de.plmail.core.data.MailView
import de.plmail.core.data.Outbox
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

/**
 * Something that is not answering, named so the banner can say which.
 *
 * [isWholeServer] decides the sentence, not just the noun. One account failing leaves the others
 * refreshing and is worth saying so; the *session* failing means nothing was reached, and the
 * reassurance that "the other accounts are still up to date" is then false about every row on the
 * screen. It is also the case with no account name available at all — the call that would have
 * listed them is the one that failed — so [displayName] is the address, and repeating it beside the
 * hostname produced "Could not reach http://10.0.2.2:8002 at 10.0.2.2".
 */
data class UnreachableAccount(
    val accountKey: String,
    val displayName: String,
    val isWholeServer: Boolean,
)

/**
 * The list's honest account of itself when the server is not there.
 *
 * Three facts rather than one boolean, because they are three different sentences and the app has
 * been guilty of collapsing them: the device has no network, the device has one and the server is
 * not answering, and changes are waiting to be sent. A phone in a lift and a NAS that has been
 * switched off need different words — one of them is fixable from the quick settings and the other
 * is not — and "N changes waiting" is what makes the first two survivable rather than alarming.
 */
data class OfflineState(
    val isOffline: Boolean,
    /** The server's address as stored, so the banner can name it rather than say "the server". */
    val host: String?,
    val pendingChanges: Int,
) {
    val isQuiet: Boolean
        get() = !isOffline && pendingChanges == 0
}

/** The "Label as" sheet, once its ticks have been resolved. */
data class LabelSheetState(val targets: List<ActionTarget>, val selection: LabelSelection)

@HiltViewModel
class MailViewModel
@Inject
constructor(
    private val feed: FeedRepository,
    private val mail: MailRepository,
    private val actions: MailActions,
    private val labelRepository: LabelRepository,
    private val deltaSync: DeltaSync,
    connectivity: Connectivity,
    outbox: Outbox,
    accounts: AccountsRepository,
) : ViewModel() {

    private val _isSyncing = MutableStateFlow(false)

    /**
     * Whether the *server* half of a pull-to-refresh is still running.
     *
     * Separate from Paging's own load state because a pull does two things, and Paging can only see
     * one of them. Re-paging the list on screen is the visible half; the delta sync is the half
     * that brings every other list, and every read and flag state anywhere, up to date. A spinner
     * tied to the mediator alone disappears while that is still going, so the gesture looks
     * finished before the thing the user pulled for has happened.
     */
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    /**
     * Accounts a sync has decided can no longer be described incrementally.
     *
     * The screen re-pages on each one. `SyncResult.NeedsRepage` is durable in the account's null
     * cursor, which is what a list built *afterwards* reads — but the list somebody is looking at
     * was built before it, and would go on drawing rows nothing can bring up to date.
     */
    val repagedAccounts: Flow<String> = feed.repagedAccounts

    /**
     * The server half of a pull-to-refresh.
     *
     * One `Email/changes` per account, which is the cheapest possible way to correct *everything*:
     * the projection puts each changed conversation into the lists it now belongs to, so pulling on
     * the inbox also fixes Promotions, every label list, and the read and starred state of anything
     * touched on another device. Re-paging alone would only correct the list being pulled.
     *
     * Guarded rather than restarted, because a second pull while the first is in flight would ask
     * the same server the same question twice.
     *
     * The flag is raised here rather than inside the coroutine, and both halves of that matter: two
     * pulls in the same frame would otherwise both read `false` and both launch, and the indicator
     * would not appear until the sync had already been dispatched.
     */
    fun refresh() {
        if (_isSyncing.value) return

        _isSyncing.value = true

        viewModelScope.launch {
            try {
                deltaSync.syncAll()
            } finally {
                // In a `finally` because the flag is what dismisses the spinner:
                // a sync that threw would otherwise leave the list pinned under
                // an indicator that never stops.
                _isSyncing.value = false
            }
        }
    }

    private val announcements = ActionAnnouncements()
    val announcement: StateFlow<ActionAnnouncement?> = announcements.announcement

    private val _selection = MutableStateFlow<Set<String>>(emptySet())
    val selection: StateFlow<Set<String>> = _selection.asStateFlow()

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

        viewModelScope.launch { announcements.announce(actions.apply(action, targets)) }
    }

    fun undo(undoable: UndoableAction) {
        viewModelScope.launch { announcements.announce(actions.undo(undoable)) }
    }

    fun announcementShown(id: Long) {
        announcements.shown(id)
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

    private val shown = MutableStateFlow<MailView>(MailView.Inbox)

    /** Which list the screen is showing. */
    fun show(view: MailView) {
        shown.value = view
    }

    /**
     * Which list is on screen, as the feed layer understands it.
     *
     * The Inbox label and the unified inbox are the same mail seen two ways, so both collapse to
     * [MailView.Inbox] here and neither restarts the other. That is not tidiness: the sidebar
     * arrives a moment after the first frame, so the list is created showing the inbox and then
     * told about the Inbox label — and without this the second one cancels the page already in
     * flight and starts again for the same rows.
     *
     * A **category** does not collapse into it, and must not. Primary is a narrower list than the
     * inbox, not the same one under another name: the server leaves an unclassified conversation
     * out of every category, so folding them would silently drop mail from whichever of the two ran
     * second.
     */
    private val shownFeed: Flow<MailView> =
        shown
            .map { view ->
                if (view is MailView.Labelled && view.label.role == INBOX_ROLE) MailView.Inbox
                else view
            }
            .distinctUntilChanged { old, new -> old.feedId == new.feedId }

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
        shownFeed.flatMapLatest { view -> feed.forView(view) }.cachedIn(viewModelScope)

    /**
     * How many conversations the list on screen actually holds, or null before that is known.
     *
     * The screen needs this to tell "there is nothing here" apart from "Paging has not caught up
     * with the rows that were just written", which look identical from inside Paging and are
     * opposite answers to the person reading. Null rather than zero as the starting value, because
     * zero is a claim and "not yet asked" is not — starting at zero would flash the empty state on
     * every cold launch, before the first query has run.
     *
     * Derived from [shownFeed] so it switches with the pager rather than beside it: a count left
     * over from the previous label is what would make an empty label say "still loading" forever.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val rowsInFeed: StateFlow<Int?> =
        shownFeed
            .flatMapLatest { view -> feed.rowsHeld(view.feedId) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = null,
            )

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
                        isWholeServer = it.isWholeServer,
                    )
                }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = emptyList(),
            )

    /**
     * Whether the app can reach anything, and what is waiting if it cannot.
     *
     * The host comes from the stored connection rather than from a failure, deliberately: a phone
     * with no network never *makes* a failed request, so there is nothing to read a hostname out of
     * — and "we can't reach your server" without the name of it is the kind of message this
     * product's users have been complaining about for years.
     */
    val offline: StateFlow<OfflineState> =
        combine(
                connectivity.isOnline,
                outbox.state,
                accounts.serverHost,
            ) { online, queue, host ->
                OfflineState(isOffline = !online, host = host, pendingChanges = queue.pending)
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                // Online until told otherwise. A banner that flashes on for one
                // frame at every launch, before the first callback arrives, is a
                // banner people learn to ignore.
                initialValue = OfflineState(isOffline = false, host = null, pendingChanges = 0),
            )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val INBOX_ROLE = "inbox"
    }
}
