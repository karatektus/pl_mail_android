package de.plmail.feature.mail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
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
import androidx.compose.material3.TopAppBarDefaults
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
import de.plmail.core.designsystem.PlMailBanner
import de.plmail.core.designsystem.PlMailDivider
import de.plmail.core.designsystem.PlMailEmptyState
import de.plmail.core.designsystem.PlMailTheme

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
    // Hoisted rather than handled here: search is its own feature module, and a
    // dependency from one feature onto another is the thing module boundaries
    // exist to prevent. :app owns the swap.
    onSearch: () -> Unit,
    onCompose: () -> Unit,
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
        floatingActionButton = {
            // Hidden while rows are selected: the bar above is a mode, and a
            // compose button inside it invites tapping it by accident with
            // forty conversations chosen.
            if (selection.isEmpty()) {
                FloatingActionButton(
                    onClick = onCompose,
                    // Shape and elevation both spelled out. Material's FAB takes
                    // its shape from `shapes.large`, which in this design system
                    // is the *pane* radius -- zero in the flat layout -- so the
                    // default produced a square button with a drop shadow under
                    // it. A FAB is a control: fixed radius, and separated by its
                    // fill rather than by a shadow.
                    shape = RoundedCornerShape(PlMailTheme.radii.floating),
                    // Tonal rather than filled. The accent is the scarcest
                    // thing in this palette and a 56dp block of it is the
                    // largest single area of colour the app would have -- which
                    // is the opposite of what makes it mean anything. The tint
                    // plus an accent glyph is just as findable and does not
                    // shout across an otherwise quiet list.
                    containerColor = PlMailTheme.colors.accentSoft,
                    contentColor = PlMailTheme.colors.accent,
                    elevation =
                        FloatingActionButtonDefaults.elevation(
                            defaultElevation = 0.dp,
                            pressedElevation = 0.dp,
                            focusedElevation = 0.dp,
                            hoveredElevation = 0.dp,
                        ),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = stringResource(R.string.compose_new),
                    )
                }
            }
        },
        topBar = {
            if (selection.isEmpty()) {
                TopAppBar(
                    title = { Text(stringResource(R.string.inbox_title)) },
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            // The bar is part of the page rather than a
                            // separate plane. Material's default tints it as it
                            // scrolls, which reintroduces the elevation model
                            // this design deliberately does not use.
                            containerColor = PlMailTheme.colors.surface,
                            scrolledContainerColor = PlMailTheme.colors.surface,
                            titleContentColor = PlMailTheme.colors.ink,
                            actionIconContentColor = PlMailTheme.colors.inkSoft,
                        ),
                    actions = {
                        IconButton(onClick = onSearch) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = stringResource(R.string.search),
                            )
                        }
                    },
                )
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
                PlMailBanner(
                    text = stringResource(R.string.account_unreachable, account.displayName),
                    modifier =
                        Modifier.padding(
                            horizontal = PlMailTheme.spacing.medium,
                            vertical = PlMailTheme.spacing.small,
                        ),
                    action = {
                        TextButton(onClick = { threads.retry() }) {
                            Text(stringResource(R.string.retry))
                        }
                    },
                )
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
        if (refreshing) {
            Message(stringResource(R.string.inbox_loading))
        } else {
            PlMailEmptyState(
                icon = Icons.Outlined.Inbox,
                title = stringResource(R.string.inbox_empty),
                body = stringResource(R.string.inbox_empty_body),
            )
        }

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

                // Indented past the avatar, so the line separates the text
                // columns rather than cutting the row in half. A full-bleed
                // rule under every row turns a list into a table.
                PlMailDivider(startIndent = ROW_TEXT_INSET)
            }
        }

        if (threads.loadState.append is LoadState.Loading) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(PlMailTheme.spacing.large),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(
                        color = PlMailTheme.colors.accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/**
 * How far the row's text column starts from the edge.
 *
 * The gutter plus the avatar plus the gap between them. Kept as one constant rather than three
 * additions at the call site so the divider cannot drift out of alignment with the text it is
 * separating.
 */
private val ROW_TEXT_INSET = 72.dp

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
