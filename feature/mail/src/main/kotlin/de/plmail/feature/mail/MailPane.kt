package de.plmail.feature.mail

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import de.plmail.core.data.ActionTarget
import de.plmail.core.data.Label
import de.plmail.core.data.MailAction
import de.plmail.core.data.MailView
import de.plmail.core.database.ThreadEntity
import de.plmail.feature.mail.reader.ReaderScreen
import kotlinx.coroutines.launch

/**
 * The list and the reader, side by side where there is room.
 *
 * `NavigableListDetailPaneScaffold` rather than a hand-rolled `if (isTablet)`: it owns the back
 * behaviour as well as the layout, and back is the part that is easy to get wrong. On a phone,
 * opening a conversation is a navigation step and back must return to the list; on a tablet both
 * panes are visible and back must leave the screen instead. A layout-only solution gets the columns
 * right and traps the user in the reader.
 *
 * The selected thread is a [rememberSaveable] uid rather than the row object, so a rotation or a
 * process death restores the selection without holding an entity across it.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun MailPane(
    view: MailView,
    onOpenSidebar: (() -> Unit)?,
    onEditLabel: (Label) -> Unit,
    onCreateLabel: () -> Unit,
    /** "New label" from the label sheet: a label to make *and* put on these conversations. */
    onCreateLabelFor: (List<ActionTarget>) -> Unit,
    /** Passed through to the list, which is where a new-mail bundle is tapped. See [MailScreen]. */
    onNavigate: (MailView) -> Unit,
    onSearch: () -> Unit,
    /** Null where this install has no calendar. Passed straight to the list's top bar. */
    onCalendar: (() -> Unit)?,
    onCompose: () -> Unit,
    onReply: (accountKey: String, emailId: String, all: Boolean) -> Unit,
    onForward: (accountKey: String, emailId: String) -> Unit,
    onEditDraft: (accountKey: String, emailId: String) -> Unit,
    openThread: ThreadTarget? = null,
    onThreadOpened: () -> Unit = {},
    viewModel: MailViewModel = hiltViewModel(),
) {
    val navigator = rememberListDetailPaneScaffoldNavigator<Nothing>()
    val scope = rememberCoroutineScope()
    var selectedUid by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedSubject by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedAccount by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedThread by rememberSaveable { mutableStateOf<String?>(null) }

    // Which way the reader last moved: +1 to the next conversation, -1 to the
    // previous, 0 for one opened any other way. Only the arrival animation reads
    // it, so a conversation tapped in the list appears in place rather than
    // sliding in from a side it has nothing to do with.
    var step by remember { mutableIntStateOf(0) }

    // Collected here rather than by the list, because the reader needs the
    // order too: a swipe opens the row beside the open one. Handed down to
    // MailScreen so that both read one copy of what Paging has loaded.
    val threads = viewModel.threads.collectAsLazyPagingItems()

    val openRow = { row: ThreadEntity, towards: Int ->
        step = towards
        selectedUid = row.uid
        selectedSubject = row.subject
        selectedAccount = row.accountKey
        selectedThread = row.threadId
    }

    // Both panes act on mail, and neither is on screen for the whole life of
    // what it started. Archiving from the reader closes the reader; archiving
    // from the list leaves the row's own pane composed but is the same change --
    // so the announcement, its undo and the label sheet all live here, at the
    // one level that outlives either pane. Hosting the snackbar inside the
    // reader was the first version and it took the way back off screen at
    // exactly the moment it was needed.
    val announcement by viewModel.announcement.collectAsStateWithLifecycle()
    val labels by viewModel.labels.collectAsStateWithLifecycle()
    val labelSheet by viewModel.labelSheet.collectAsStateWithLifecycle()
    val moveSheet by viewModel.moveSheet.collectAsStateWithLifecycle()
    val tabs by viewModel.tabs.collectAsStateWithLifecycle()
    val snackbars = remember { SnackbarHostState() }

    UndoSnackbar(
        announcement = announcement,
        snackbars = snackbars,
        onUndo = viewModel::undo,
        onShown = viewModel::announcementShown,
        tabs = tabs,
    )

    // A conversation chosen somewhere this screen cannot see -- a notification
    // tap. Keyed on the target so tapping a second notification while the first
    // conversation is open switches to it, and acknowledged immediately so that
    // pressing back and then rotating does not silently reopen it.
    LaunchedEffect(openThread) {
        openThread?.let { target ->
            step = 0
            selectedUid = null
            // Null rather than a guess, and the reader falls back to the
            // conversation's own subject. Inventing one here — from the
            // notification's line, say — would put a "Re:" prefix in the title
            // of a thread the list titles by its opening message.
            selectedSubject = null
            selectedAccount = target.accountKey
            selectedThread = target.threadId

            navigator.navigateTo(ListDetailPaneScaffoldRole.Detail)
            onThreadOpened()
        }
    }

    // Only when the detail pane is the one being shown. On a tablet both panes
    // are visible and there is nothing to go back *from*, so intercepting here
    // would swallow the gesture that should leave the screen.
    BackHandler(enabled = navigator.canNavigateBack()) {
        scope.launch { navigator.navigateBack() }
    }

    val closeReader = {
        close(navigator, scope) {
            selectedAccount = null
            selectedThread = null
            selectedSubject = null
            selectedUid = null
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NavigableListDetailPaneScaffold(
            navigator = navigator,
            listPane = {
                AnimatedPane {
                    MailScreen(
                        view = view,
                        onOpenSidebar = onOpenSidebar,
                        onEditLabel = onEditLabel,
                        onCreateLabel = onCreateLabel,
                        onSearch = onSearch,
                        onCalendar = onCalendar,
                        onCompose = onCompose,
                        onNavigate = onNavigate,
                        viewModel = viewModel,
                        threads = threads,
                        onThreadSelected = { thread ->
                            openRow(thread, 0)
                            scope.launch {
                                navigator.navigateTo(ListDetailPaneScaffoldRole.Detail)
                            }
                        },
                    )
                }
            },
            detailPane = {
                AnimatedPane {
                    val account = selectedAccount
                    val thread = selectedThread

                    if (account == null || thread == null) {
                        NothingSelected()
                    } else {
                        // Indices into what Paging has loaded, placeholders included,
                        // so they are the same indices `threads[i]` takes. Derived
                        // rather than read here (#34): the snapshot changes with every
                        // page loaded and every row rewritten, and reading it in this
                        // composition recomposed the reader for each of those although
                        // the open row and its neighbours stayed where they were.
                        val neighbours by
                            remember(threads, account, thread) {
                                derivedStateOf {
                                    neighboursOf(threads.itemSnapshotList, account, thread)
                                }
                            }
                        val nextAt = neighbours?.next
                        val previousAt = neighbours?.previous

                        // Read through LazyPagingItems rather than off the snapshot,
                        // because a read is what tells Paging where the user is. The
                        // list stops reading once the reader covers it, so without
                        // this a run of swipes reaches the last loaded row and stops
                        // there, with more mail on the server than on the device.
                        val at = neighbours?.at
                        LaunchedEffect(at) { if (at != null) threads[at] }

                        // A fresh reader per conversation: its list's scroll offset,
                        // the attachment being saved and its snackbars are about the
                        // conversation they were made for. Without the key, swiping
                        // from the end of a long thread opened the next one scrolled
                        // to the same depth.
                        key(account, thread) {
                            val towards = remember { step }
                            val arriving = remember { Animatable(if (towards == 0) 0f else 1f) }
                            LaunchedEffect(Unit) {
                                arriving.animateTo(0f, tween(ARRIVAL_MILLIS))
                            }

                            Box(
                                modifier =
                                    Modifier.fillMaxSize().graphicsLayer {
                                        // From the side the swipe pushed towards: the
                                        // next conversation comes in from the right.
                                        translationX =
                                            arriving.value * towards * size.width * ARRIVAL_SHIFT
                                        alpha = 1f - arriving.value
                                    }
                            ) {
                                ReaderScreen(
                                    accountKey = account,
                                    threadId = thread,
                                    subject = selectedSubject,
                                    onNext =
                                        nextAt?.let { i ->
                                            { threads[i]?.let { row -> openRow(row, 1) } }
                                        },
                                    onPrevious =
                                        previousAt?.let { i ->
                                            { threads[i]?.let { row -> openRow(row, -1) } }
                                        },
                                    onReply = { emailId, all -> onReply(account, emailId, all) },
                                    onForward = { emailId -> onForward(account, emailId) },
                                    onEditDraft = { emailId -> onEditDraft(account, emailId) },
                                    onAction = { action ->
                                        viewModel.apply(
                                            action,
                                            listOf(ActionTarget(account, thread)),
                                        )

                                        // Archiving, trashing, marking spam and snoozing all
                                        // take the conversation out of the list it was
                                        // opened from, so the reader stops being a view of
                                        // anything. Starring and labelling do not, and
                                        // closing on those would be a screen that vanishes
                                        // when somebody stars a message.
                                        if (action.leavesTheList) closeReader()
                                    },
                                    onLabel = {
                                        viewModel.openLabelSheet(
                                            listOf(ActionTarget(account, thread))
                                        )
                                    },
                                    onOpenLabel = { label ->
                                        // The reader first, or on a phone the list
                                        // changes underneath a pane that is still
                                        // covering it.
                                        closeReader()
                                        onNavigate(MailView.Labelled(label))
                                    },
                                    onMove = {
                                        viewModel.openMoveSheet(
                                            view,
                                            listOf(ActionTarget(account, thread)),
                                        )
                                    },
                                    // Only where there is a list to go back *to*. On a
                                    // tablet both panes are on screen and an arrow that
                                    // leaves a pane already beside its list is a control
                                    // pointing at nothing.
                                    onBack =
                                        if (navigator.canNavigateBack()) {
                                            { scope.launch { navigator.navigateBack() } }
                                        } else {
                                            null
                                        },
                                )
                            }
                        }
                    }
                }
            },
        )

        // Last, so it draws over whichever pane is showing, and inset only
        // against the navigation bar: it floats above content rather than being
        // laid out with it.
        SnackbarHost(
            hostState = snackbars,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        )
    }

    labelSheet?.let { sheet ->
        LabelSheet(
            labels = labels,
            selection = sheet.selection,
            targets = sheet.targets,
            onToggle = { label, applied ->
                viewModel.apply(MailAction.SetLabel(label, applied), sheet.targets)
            },
            onCreate = {
                viewModel.closeLabelSheet()
                onCreateLabelFor(sheet.targets)
            },
            onDismiss = viewModel::closeLabelSheet,
        )
    }

    moveSheet?.let { sheet ->
        MoveSheet(
            destinations = sheet.destinations,
            recent = sheet.recent,
            targets = sheet.targets,
            onPick = { action ->
                viewModel.closeMoveSheet()
                viewModel.apply(action, sheet.targets)

                // The same rule as an action taken in the reader itself: a
                // conversation moved out of the list it was opened from is no
                // longer something the reader is a view of.
                val isOpen =
                    sheet.targets.any {
                        it.accountKey == selectedAccount && it.threadId == selectedThread
                    }

                if (isOpen && action.leavesTheList) closeReader()
            },
            onDismiss = viewModel::closeMoveSheet,
        )
    }
}

/**
 * Leaves the reader, whichever way "leaving" means on this window.
 *
 * On a phone the detail pane is a navigation step and back returns to the list. On a tablet both
 * panes are on screen, so there is nothing to go back from and the detail pane empties instead —
 * otherwise it keeps describing a row that is no longer in the list beside it.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
private fun close(
    navigator: androidx.compose.material3.adaptive.navigation.ThreePaneScaffoldNavigator<Nothing>,
    scope: kotlinx.coroutines.CoroutineScope,
    clearSelection: () -> Unit,
) {
    if (navigator.canNavigateBack()) scope.launch { navigator.navigateBack() } else clearSelection()
}

/** How long a conversation opened by a swipe takes to settle into place. */
private const val ARRIVAL_MILLIS = 220

/**
 * How far it starts from its resting place, as a share of the pane's width.
 *
 * A quarter rather than the whole width: the conversation being left is not drawn sliding out — see
 * `swipeBetweenConversations` for why only one reader exists at a time — so a full-width entrance
 * would cross an empty pane first. A short slide with a fade says which way the list moved without
 * pretending to be a pager.
 */
private const val ARRIVAL_SHIFT = 0.25f

/** Whether an action takes the conversation out of the list it was opened from. */
private val MailAction.leavesTheList: Boolean
    get() =
        this == MailAction.Archive ||
            this == MailAction.Trash ||
            this == MailAction.MarkSpam ||
            this is MailAction.MoveTo ||
            // Every tab move is made from a tab, and leaves it.
            this is MailAction.MoveToTab ||
            this == MailAction.MoveToInbox ||
            (this is MailAction.Snooze && until != null)

/**
 * A conversation to open, pushed in from outside the screen.
 *
 * Carries no subject and no row, only the two ids the reader needs, because whoever is asking --
 * today a notification tap -- has no access to the cache and no business holding an entity.
 */
data class ThreadTarget(val accountKey: String, val threadId: String)

/** The detail pane before anything has been chosen, which only a tablet ever shows. */
@Composable
private fun NothingSelected() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.reader_placeholder),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
