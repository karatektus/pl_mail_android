package de.plmail.feature.mail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import de.plmail.core.data.AccountsRepository
import de.plmail.core.data.ActionTarget
import de.plmail.core.data.CategoryArrivals
import de.plmail.core.data.CategoryDigest
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
import de.plmail.core.data.ShownThreads
import de.plmail.core.data.UndoableAction
import de.plmail.core.data.isStartDestination
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
    private val digest: CategoryDigest,
    private val shownThreads: ShownThreads,
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
     * Whether the list on screen merges more than one account.
     *
     * What decides whether a row carries its account's mark: on a single-account install every row
     * would carry the same one, which is decoration rather than information — and the setting that
     * turns the mark on is the user's, so it must not become the reason a mark appears where it
     * says nothing.
     *
     * The account *list* rather than the rows: a merge of two accounts where one happens to have
     * contributed nothing to the current page is still a list where the marks mean something, and
     * deriving this from the rows would flicker the mark on and off as the user scrolled.
     */
    val isMerged: StateFlow<Boolean> =
        mail
            .observeAccounts()
            .map { it.size > 1 }
            .distinctUntilChanged()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                // False until told otherwise: an unmarked row is the shape this
                // list has always had, and a mark that appears a frame late is
                // better than one that appears and then vanishes.
                initialValue = false,
            )

    /**
     * Whether this server classifies mail, for the one thing that needs it: the list's title.
     *
     * Deliberately not what decides which list is drawn — see [MailView.START].
     */
    val hasCategories: StateFlow<Boolean> =
        labelRepository
            .observeHasCategories()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = false,
            )

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

    private val shown = MutableStateFlow(MailView.START)

    /** Which list the screen is showing. */
    fun show(view: MailView) {
        // A badge says "this arrived since you last looked at *this list*", so
        // it does not follow the user to another one. Leaving the set alone
        // would badge a conversation in Promotions because it was new in the
        // digest that sent you there.
        if (shown.value.feedId != view.feedId) _badgedNew.value = emptySet()

        shown.value = view
    }

    /**
     * Whether this list should be forced to its first row, asked once per destination per process.
     *
     * The app was opening part-way down its own inbox, with the new-mail bundles scrolled off the
     * top — which is the one thing they exist to be seen. `LazyColumn`'s default state is
     * `rememberSaveable`, so a scroll offset is written into the saved instance state and handed
     * back when Android restarts a process it had killed. The list then restores to row forty of a
     * list whose rows have not been paged in yet.
     *
     * A `LaunchedEffect` in the screen cannot tell those cases apart on its own: a configuration
     * change also recreates the composition, and rotating a phone must *keep* your place. This
     * ViewModel is exactly the thing that survives the one and not the other, so the question is
     * answered here. Returning true consumes it — a screen that asks twice for the same list is a
     * screen that scrolled the user back to the top while they were reading.
     *
     * Opening a conversation and coming back is not affected: the ViewModel outlives the reader, so
     * the answer is already spent by then.
     */
    fun claimStartAtTop(feedId: String): Boolean = startedAtTop.add(feedId)

    /**
     * The destinations already positioned in this process.
     *
     * A plain set rather than state: nothing recomposes on it, and it is only ever touched from
     * [claimStartAtTop] on the main thread.
     */
    private val startedAtTop = mutableSetOf<String>()

    /**
     * Records that these conversations have been drawn.
     *
     * What retires the New marker — for the browser as well as for this device, because it goes to
     * the server rather than into a local note. Called from the list as rows compose, so "shown"
     * means shown rather than fetched: paging, a notification and the body prefetcher all put
     * conversations on the device without anybody having seen them.
     *
     * Cheap to call repeatedly. [ShownThreads] narrows against the cache before it queues anything,
     * so a list redrawing the same page reports nothing.
     */
    fun threadsShown(rows: List<ThreadEntity>) {
        // Remembered before it is reported, and that ordering is the point. See
        // `badgedNew`.
        _badgedNew.update { badged -> badged + rows.mapTo(mutableSetOf()) { it.uid } }

        rows
            .groupBy { it.accountKey }
            .forEach { (accountKey, forAccount) ->
                shownThreads.report(accountKey, forAccount.map { it.threadId })
            }
    }

    private val _badgedNew = MutableStateFlow(emptySet<String>())

    /**
     * Which rows keep their **New** badge while this list is on screen.
     *
     * The badge cannot be drawn from `ThreadEntity.isNew` directly, and the reason is the marker's
     * own definition: it means *never put in front of this user*, so drawing the row is precisely
     * what spends it. The phone reports the display through `Thread/set`, the row is cleared
     * locally the moment that is accepted, and a badge reading the column would appear and vanish
     * inside a second while somebody was looking straight at it.
     *
     * So the answer is held here instead: a row that was new when it was first drawn stays badged
     * for as long as the list is showing. That is the same bargain the web makes — its badge is
     * visible on the render that consumed the marker and gone on the next one — and it is what
     * makes "new since I last looked" a thing a person can actually act on.
     *
     * Cleared when the destination changes, in [show]. Not persisted: after a process restart the
     * server has already been told, so there is nothing left that is new, and restoring the set
     * would be the phone badging mail on the strength of its own memory rather than the marker.
     */
    val badgedNew: StateFlow<Set<String>> = _badgedNew.asStateFlow()

    /**
     * The categories with mail the user has not looked at, for the rows above Primary.
     *
     * Drawn only on Primary, and filtered here rather than in the screen so the flow is not
     * recomputed for every other list: the digest is about mail that is *elsewhere*, and elsewhere
     * has no meaning while the user is browsing a label.
     */
    val arrivals: StateFlow<List<CategoryArrivals>> =
        combine(shown, digest.arrivals) { view, found ->
                if (view == MailView.START) found else emptyList()
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = emptyList(),
            )

    /**
     * Which list is on screen, as the feed layer understands it.
     *
     * The Inbox *label* collapses onto [MailView.START], because on this app's navigation they are
     * one destination reached two ways — the sidebar draws an Inbox row on a server with no
     * classifier, and Primary is what that row means. Collapsing is not tidiness: the sidebar
     * arrives a moment after the first frame, so the list is created showing Primary and then told
     * about the Inbox label, and without this the second one cancels the page already in flight and
     * starts again for the same rows.
     *
     * The other four categories do not collapse into anything, and must not. Each is a narrower
     * list, and folding them would silently drop mail from whichever ran second.
     */
    private val shownFeed: Flow<MailView> =
        shown
            .map { view -> if (view.isStartDestination) MailView.START else view }
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
    }
}
