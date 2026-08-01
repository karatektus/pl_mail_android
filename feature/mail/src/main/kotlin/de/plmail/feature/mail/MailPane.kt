package de.plmail.feature.mail

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.plmail.core.data.ActionTarget
import de.plmail.core.data.Label
import de.plmail.core.data.MailAction
import de.plmail.core.data.MailView
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
    onSearch: () -> Unit,
    onCompose: () -> Unit,
    onReply: (accountKey: String, emailId: String, all: Boolean) -> Unit,
    onForward: (accountKey: String, emailId: String) -> Unit,
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
    val snackbars = remember { SnackbarHostState() }

    UndoSnackbar(
        announcement = announcement,
        snackbars = snackbars,
        onUndo = viewModel::undo,
        onShown = viewModel::announcementShown,
    )

    // A conversation chosen somewhere this screen cannot see -- a notification
    // tap. Keyed on the target so tapping a second notification while the first
    // conversation is open switches to it, and acknowledged immediately so that
    // pressing back and then rotating does not silently reopen it.
    LaunchedEffect(openThread) {
        openThread?.let { target ->
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
                        onCompose = onCompose,
                        viewModel = viewModel,
                        onThreadSelected = { thread ->
                            selectedUid = thread.uid
                            selectedSubject = thread.subject
                            selectedAccount = thread.accountKey
                            selectedThread = thread.threadId
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
                        ReaderScreen(
                            accountKey = account,
                            threadId = thread,
                            subject = selectedSubject,
                            onReply = { emailId, all -> onReply(account, emailId, all) },
                            onForward = { emailId -> onForward(account, emailId) },
                            onAction = { action ->
                                viewModel.apply(action, listOf(ActionTarget(account, thread)))

                                // Archiving, trashing, marking spam and snoozing all
                                // take the conversation out of the list it was
                                // opened from, so the reader stops being a view of
                                // anything. Starring and labelling do not, and
                                // closing on those would be a screen that vanishes
                                // when somebody stars a message.
                                if (action.leavesTheList)
                                    close(navigator, scope) {
                                        selectedAccount = null
                                        selectedThread = null
                                        selectedSubject = null
                                        selectedUid = null
                                    }
                            },
                            onLabel = {
                                viewModel.openLabelSheet(listOf(ActionTarget(account, thread)))
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
                onCreateLabel()
            },
            onDismiss = viewModel::closeLabelSheet,
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

/** Whether an action takes the conversation out of the list it was opened from. */
private val MailAction.leavesTheList: Boolean
    get() =
        this == MailAction.Archive ||
            this == MailAction.Trash ||
            this == MailAction.MarkSpam ||
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
