package de.plmail.feature.mail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import de.plmail.core.data.ActionOutcome
import de.plmail.core.data.ActionTarget
import de.plmail.core.data.MailAction
import de.plmail.core.database.ThreadEntity

/**
 * The unified inbox.
 *
 * Rows come from the feed table, so a cold launch draws whatever was last synced before any request
 * leaves the device. That is the point of the table, and it is why the empty state below is only
 * shown once loading has actually settled — flashing "nothing here" at someone whose mail is on
 * disk would be a lie about their own inbox.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailScreen(
    onThreadSelected: (ThreadEntity) -> Unit,
    viewModel: MailViewModel = hiltViewModel(),
) {
    val threads = viewModel.threads.collectAsLazyPagingItems()
    val unreachable by viewModel.unreachable.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val announcement by viewModel.announcement.collectAsStateWithLifecycle()

    val snackbars = remember { SnackbarHostState() }

    // Six seconds, matching the product's undo window. Long enough to notice a
    // row leave and change your mind, short enough not to sit over the list.
    // Resolved through the composition's own resources rather than
    // LocalContext.resources, which does not recompose on a locale change --
    // the snackbar would keep the language the screen was first created in.
    val message = announcement?.let { describe(it.outcome) }.orEmpty()
    val undoLabel = stringResource(R.string.undo)

    LaunchedEffect(announcement?.id) {
        val shown = announcement ?: return@LaunchedEffect

        val result =
            snackbars.showSnackbar(
                message = message,
                actionLabel = undoLabel,
                duration = SnackbarDuration.Short,
            )

        if (result == SnackbarResult.ActionPerformed) viewModel.undo(shown.outcome.undoable)

        viewModel.announcementShown(shown.id)
    }

    // Back clears a selection before it leaves the screen: a selection is a
    // mode, and leaving a mode is what back is for.
    BackHandler(enabled = selection.isNotEmpty()) { viewModel.clearSelection() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            if (selection.isEmpty()) {
                TopAppBar(title = { Text(stringResource(R.string.inbox_title)) })
            } else {
                SelectionBar(
                    count = selection.size,
                    onClear = viewModel::clearSelection,
                    onAction = { action ->
                        viewModel.apply(action, threads.targetsFor(selection))
                    },
                )
            }
        },
    ) { insets ->
        Column(modifier = Modifier.fillMaxSize().padding(insets)) {
            // Above the list, not instead of it. One unreachable account must
            // never take the other accounts' mail off the screen.
            unreachable.forEach { account ->
                Card(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text =
                                stringResource(
                                    R.string.account_unreachable,
                                    account.displayName,
                                ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { threads.retry() }) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
            }

            ThreadList(
                threads = threads,
                selection = selection,
                onThreadSelected = onThreadSelected,
                onToggleSelected = viewModel::toggleSelected,
                onAction = { thread, action -> viewModel.apply(action, listOf(thread.target())) },
            )
        }
    }
}

@Composable
private fun ThreadList(
    threads: LazyPagingItems<ThreadEntity>,
    selection: Set<String>,
    onThreadSelected: (ThreadEntity) -> Unit,
    onToggleSelected: (String) -> Unit,
    onAction: (ThreadEntity, MailAction) -> Unit,
) {
    val refreshing = threads.loadState.refresh is LoadState.Loading

    if (threads.itemCount == 0) {
        // "Nothing here" and "still looking" are different answers, and showing
        // the first while the first page is in flight tells someone their inbox
        // is empty when it is not.
        Message(stringResource(if (refreshing) R.string.inbox_loading else R.string.inbox_empty))
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(
            count = threads.itemCount,
            // Keyed on the row's own identity, so an inserted message does not
            // recycle every row below it and lose their scroll position.
            key = { index -> threads.peek(index)?.uid ?: index },
        ) { index ->
            val thread = threads[index]

            if (thread != null) {
                SwipeableThreadRow(
                    thread = thread,
                    isSelected = thread.uid in selection,
                    // While a selection is open, tapping extends it rather than
                    // opening a conversation -- otherwise the only way to add a
                    // second row is another long press.
                    onClick = {
                        if (selection.isEmpty()) onThreadSelected(thread)
                        else onToggleSelected(thread.uid)
                    },
                    onLongClick = { onToggleSelected(thread.uid) },
                    onAction = { action -> onAction(thread, action) },
                )
                HorizontalDivider()
            }
        }

        if (threads.loadState.append is LoadState.Loading) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The bar that replaces the title while conversations are selected.
 *
 * Every gesture has an equivalent here. A swipe is the fast path and never the only one: it is
 * undiscoverable, and unusable with TalkBack or a switch device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(count: Int, onClear: () -> Unit, onAction: (MailAction) -> Unit) {
    TopAppBar(
        title = { Text(pluralStringResource(R.plurals.selected_count, count, count)) },
        navigationIcon = {
            IconButton(onClick = onClear) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.clear_selection),
                )
            }
        },
        actions = {
            IconButton(onClick = { onAction(MailAction.Archive) }) {
                Icon(
                    imageVector = Icons.Outlined.Archive,
                    contentDescription = stringResource(R.string.action_archive),
                )
            }
            IconButton(onClick = { onAction(MailAction.MarkRead(seen = true)) }) {
                Icon(
                    imageVector = Icons.Outlined.MarkEmailRead,
                    contentDescription = stringResource(R.string.action_read),
                )
            }
            IconButton(onClick = { onAction(MailAction.Star(flagged = true)) }) {
                Icon(
                    imageVector = Icons.Outlined.StarOutline,
                    contentDescription = stringResource(R.string.action_star),
                )
            }
            IconButton(onClick = { onAction(MailAction.MarkSpam) }) {
                Icon(
                    imageVector = Icons.Outlined.Report,
                    contentDescription = stringResource(R.string.action_spam),
                )
            }
            IconButton(onClick = { onAction(MailAction.Trash) }) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.action_trash),
                )
            }
        },
    )
}

/** One conversation as an action target. */
private fun ThreadEntity.target(): ActionTarget =
    ActionTarget(accountKey = accountKey, threadId = threadId)

/**
 * The selected conversations, resolved from what Paging currently holds.
 *
 * `peek` rather than `get`, so building a bulk action out of the selection cannot trigger a page
 * load -- and a selected row that has since been paged out is simply dropped rather than blocking.
 */
private fun LazyPagingItems<ThreadEntity>.targetsFor(selection: Set<String>): List<ActionTarget> =
    (0 until itemCount).mapNotNull { peek(it) }.filter { it.uid in selection }.map { it.target() }

/** What the snackbar says. Conversations, because conversations are what the user acted on. */
@Composable
private fun describe(outcome: ActionOutcome): String {
    val undoable = outcome.undoable
    val count = undoable.threadCount

    val done =
        when (undoable.action) {
            MailAction.Archive -> pluralStringResource(R.plurals.archived, count, count)
            MailAction.Trash -> pluralStringResource(R.plurals.trashed, count, count)
            MailAction.MoveToInbox -> pluralStringResource(R.plurals.moved_to_inbox, count, count)
            MailAction.MarkSpam -> pluralStringResource(R.plurals.marked_spam, count, count)
            else -> stringResource(R.string.changed)
        }

    return when (outcome) {
        is ActionOutcome.Applied -> done
        // Said out loud: the row already moved, so a rejection nobody mentions
        // leaves the user believing something happened that did not.
        is ActionOutcome.Rejected -> stringResource(R.string.action_rejected, done)
    }
}
