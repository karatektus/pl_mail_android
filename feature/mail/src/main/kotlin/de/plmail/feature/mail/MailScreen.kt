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
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import de.plmail.core.data.ActionTarget
import de.plmail.core.data.Label
import de.plmail.core.data.MailAction
import de.plmail.core.database.ThreadEntity
import de.plmail.core.designsystem.PlMailBanner
import de.plmail.core.designsystem.PlMailDivider
import de.plmail.core.designsystem.PlMailEmptyState
import de.plmail.core.designsystem.PlMailTheme
import java.time.Instant

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
    label: Label? = null,
    /** Null where the sidebar is already on screen and there is nothing to open. */
    onOpenSidebar: (() -> Unit)? = null,
    onEditLabel: (Label) -> Unit = {},
    onCreateLabel: () -> Unit = {},
    viewModel: MailViewModel = hiltViewModel(),
) {
    // Keyed on the label rather than done once: switching label has to switch
    // the list, and the ViewModel is the thing that owns which pager is running.
    LaunchedEffect(label?.key) { viewModel.show(label) }

    val threads = viewModel.threads.collectAsLazyPagingItems()
    val unreachable by viewModel.unreachable.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()

    // Back clears a selection before it leaves the screen: a selection is a
    // mode, and leaving a mode is what back is for.
    BackHandler(enabled = selection.isNotEmpty()) { viewModel.clearSelection() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
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
                    // The label's own name, and its leaf rather than its path:
                    // the path belongs in the sidebar, where it disambiguates
                    // between two labels shown at once. Here there is only one,
                    // and "Work/Invoices" as a screen title is noise.
                    title = { Text(label?.name ?: stringResource(R.string.inbox_title)) },
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            // The bar is part of the page rather than a
                            // separate plane. Material's default tints it as it
                            // scrolls, which reintroduces the elevation model
                            // this design deliberately does not use.
                            containerColor = PlMailTheme.colors.surface,
                            scrolledContainerColor = PlMailTheme.colors.surface,
                            titleContentColor = PlMailTheme.colors.ink,
                            navigationIconContentColor = PlMailTheme.colors.inkSoft,
                            actionIconContentColor = PlMailTheme.colors.inkSoft,
                        ),
                    navigationIcon = {
                        onOpenSidebar?.let { open ->
                            IconButton(onClick = open) {
                                Icon(
                                    Icons.Default.Menu,
                                    contentDescription = stringResource(R.string.open_labels),
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = onSearch) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = stringResource(R.string.search),
                            )
                        }

                        // Only where there is something to edit. A system label
                        // reports mayRename false and the server enforces it, so
                        // offering the control would be a button that always
                        // fails.
                        if (label?.mayRename == true || label?.mayDelete == true) {
                            IconButton(onClick = { onEditLabel(label) }) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.label_edit),
                                )
                            }
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
                    onLabel = { viewModel.openLabelSheet(threads.targetsFor(selection)) },
                    onSnooze = { at ->
                        viewModel.apply(
                            MailAction.Snooze(at?.toEpochMilli()),
                            threads.targetsFor(selection),
                        )
                    },
                    // Already snoozed mail gets the opposite verb. Offering
                    // "snooze" on the Snoozed list is a control whose effect is
                    // to replace a time the user cannot see with another one.
                    isSnoozed = label?.role == SNOOZED_ROLE,
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

                // Between rows, never after the last one. Indented past the
                // avatar, so the line separates the text columns rather than
                // cutting the row in half -- a full-bleed rule under every row
                // turns a list into a table.
                //
                // The trailing case is the one worth spelling out: a hairline
                // under the final row, with nothing beneath it, is what made an
                // inbox of four messages look truncated rather than short. The
                // line implies another row is coming and then none does.
                if (index < threads.itemCount - 1) {
                    PlMailDivider(startIndent = ROW_TEXT_INSET)
                }
            }
        }

        // What the end of the list actually is, said once, rather than a list
        // that simply stops halfway up an empty page. A new account with three
        // messages is the common case for this product, not an edge one, and
        // the untreated gap under those three rows reads as a screen that
        // failed to finish loading.
        //
        // Deliberately not "you're up to date": that is a claim about the
        // server, and the app cannot make it between syncs. This is a claim
        // about the list, which it can.
        if (threads.loadState.append.endOfPaginationReached) {
            item(key = END_OF_LIST) { EndOfList() }
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

/** The role plMail gives the mailbox a snoozed conversation waits in. */
private const val SNOOZED_ROLE = "snoozed"

/** Keyed, so Paging does not confuse the footer with a row when the list grows under it. */
private const val END_OF_LIST = "end-of-list"

/**
 * The line that closes the list.
 *
 * Quiet on purpose — `inkFaint` is the furniture step of the ink scale, and this is furniture. It
 * is there so the eye has somewhere to stop, not to be read twice.
 */
@Composable
private fun EndOfList() {
    Text(
        text = stringResource(R.string.inbox_end),
        style = MaterialTheme.typography.labelMedium,
        color = PlMailTheme.colors.inkFaint,
        textAlign = TextAlign.Center,
        modifier =
            Modifier.fillMaxWidth()
                .padding(
                    horizontal = PlMailTheme.spacing.gutter,
                    vertical = PlMailTheme.spacing.xLarge,
                ),
    )
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
private fun SelectionBar(
    count: Int,
    onClear: () -> Unit,
    onAction: (MailAction) -> Unit,
    onLabel: () -> Unit,
    onSnooze: (Instant?) -> Unit,
    isSnoozed: Boolean,
) {
    var isMenuOpen by remember { mutableStateOf(false) }
    var isSnoozeOpen by remember { mutableStateOf(false) }
    var isPickingTime by remember { mutableStateOf(false) }

    if (isPickingTime) {
        SnoozePicker(
            onDismiss = { isPickingTime = false },
            onChosen = {
                isPickingTime = false
                onSnooze(it)
            },
        )
    }

    TopAppBar(
        title = {
            Text(
                text = pluralStringResource(R.plurals.selected_count, count, count),
                // One line, always. "40 selected" beside five icon buttons and
                // an overflow does not fit a phone, and Material's default is to
                // wrap the title rather than to give up any of the actions -- so
                // "1 selected" came out over three lines and the bar was 150dp
                // tall. Three of the actions moved into the overflow below for
                // the same reason; what stays are the ones a bulk selection is
                // usually made for.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
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
            IconButton(onClick = { onAction(MailAction.Trash) }) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.action_trash),
                )
            }
            IconButton(onClick = { onAction(MailAction.MarkRead(seen = true)) }) {
                Icon(
                    imageVector = Icons.Outlined.MarkEmailRead,
                    contentDescription = stringResource(R.string.action_read),
                )
            }

            // The two that are not one tap: labelling needs a list and snoozing
            // needs a time, so neither belongs in a row of icon buttons where
            // every other control acts immediately.
            IconButton(onClick = { isMenuOpen = true }) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.more),
                )
            }

            DropdownMenu(expanded = isMenuOpen, onDismissRequest = { isMenuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_star)) },
                    onClick = {
                        isMenuOpen = false
                        onAction(MailAction.Star(flagged = true))
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_spam)) },
                    onClick = {
                        isMenuOpen = false
                        onAction(MailAction.MarkSpam)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.labels_apply)) },
                    onClick = {
                        isMenuOpen = false
                        onLabel()
                    },
                )
                if (isSnoozed) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.unsnooze)) },
                        onClick = {
                            isMenuOpen = false
                            onSnooze(null)
                        },
                    )
                } else {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.snooze)) },
                        onClick = {
                            isMenuOpen = false
                            isSnoozeOpen = true
                        },
                    )
                }
            }

            SnoozeMenu(
                isOpen = isSnoozeOpen,
                onDismiss = { isSnoozeOpen = false },
                onChosen = onSnooze,
                onPickExact = { isPickingTime = true },
            )
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
