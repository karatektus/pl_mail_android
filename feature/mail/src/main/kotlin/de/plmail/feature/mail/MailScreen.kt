package de.plmail.feature.mail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.paging.CombinedLoadStates
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import de.plmail.core.data.ActionTarget
import de.plmail.core.data.Label
import de.plmail.core.data.MailAction
import de.plmail.core.data.MailView
import de.plmail.core.data.rowLabels
import de.plmail.core.database.ThreadEntity
import de.plmail.core.designsystem.PaneTone
import de.plmail.core.designsystem.PlMailBanner
import de.plmail.core.designsystem.PlMailDivider
import de.plmail.core.designsystem.PlMailEmptyState
import de.plmail.core.designsystem.PlMailTheme
import de.plmail.core.ui.rowLabelSlots
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
    view: MailView = MailView.Inbox,
    /** Null where the sidebar is already on screen and there is nothing to open. */
    onOpenSidebar: (() -> Unit)? = null,
    onEditLabel: (Label) -> Unit = {},
    onCreateLabel: () -> Unit = {},
    viewModel: MailViewModel = hiltViewModel(),
) {
    // The label this list is browsing, if it is browsing one. A category list is
    // not: the conversations in Promotions carry whatever labels the user put on
    // them, and none of those is the name of the list.
    val label = view.browsedLabel

    // Keyed on the destination rather than done once: switching has to switch
    // the list, and the ViewModel is the thing that owns which pager is running.
    // On the feed id rather than the whole view, because a Label re-arrives with
    // new counts on every sync and restarting the pager for that would re-query
    // somebody's NAS whenever a message was read.
    LaunchedEffect(view.feedId) { viewModel.show(view) }

    val threads = viewModel.threads.collectAsLazyPagingItems()
    val rowsInFeed by viewModel.rowsInFeed.collectAsStateWithLifecycle()
    // The same list the "Label as" sheet is drawn from, and the same list the
    // sidebar orders. A row stores label *keys*, so this is what turns them into
    // names -- which means a rename shows on every row immediately instead of
    // waiting for each conversation to be re-synced.
    val labels by viewModel.labels.collectAsStateWithLifecycle()
    val unreachable by viewModel.unreachable.collectAsStateWithLifecycle()
    val offline by viewModel.offline.collectAsStateWithLifecycle()
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
                    // and "Work/Invoices" as a screen title is noise. A system
                    // role takes the app's word for it rather than the server's,
                    // which is English whatever the device is set to.
                    title = { Text(view.displayTitle()) },
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

                        // Only where there is something to edit. A category has
                        // no server object behind it at all -- there is nothing
                        // to rename, recolour or delete -- and a system label
                        // would be a rename the server refuses. Colour is the
                        // exception the editor is reached for on a system label,
                        // and that is reached from the sidebar rather than from
                        // here: this bar is over one list, and Inbox's colour is
                        // a property of the sidebar row.
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
            // First, above the per-account banners, because it explains them:
            // with no network every account is unreachable, and three rows
            // saying so is three copies of one fact. The mail below stays on
            // screen throughout -- offline is a state this app is expected to
            // be usable in, not an error screen.
            if (!offline.isQuiet) {
                PlMailBanner(
                    text =
                        when {
                            // Two different sentences, and the difference is what
                            // the reader can do about it. No network is fixable
                            // from the quick settings; a server that is not
                            // answering is not, and telling somebody to check
                            // their wifi while their NAS is off wastes the ten
                            // minutes they had.
                            offline.isOffline && offline.pendingChanges > 0 ->
                                pluralStringResource(
                                    R.plurals.offline_with_pending,
                                    offline.pendingChanges,
                                    offline.pendingChanges,
                                )
                            offline.isOffline -> stringResource(R.string.offline)
                            else ->
                                pluralStringResource(
                                    R.plurals.pending_changes,
                                    offline.pendingChanges,
                                    offline.pendingChanges,
                                )
                        },
                    tone = if (offline.isOffline) PaneTone.WARNING else PaneTone.INFO,
                    modifier =
                        Modifier.padding(
                            horizontal = PlMailTheme.spacing.medium,
                            vertical = PlMailTheme.spacing.small,
                        ),
                )
            }

            // Above the list, not instead of it. One unreachable account must
            // never take the other accounts' mail off the screen.
            //
            // Suppressed entirely while the device has no network, because the
            // banner above has already said it and every account is failing for
            // the same reason. Three rows of "could not reach X" under "you are
            // offline" is three copies of one fact, and the per-account wording
            // ("the other accounts are still up to date") is a lie in exactly
            // that state.
            unreachable
                .filterNot { offline.isOffline }
                .forEach { account ->
                    PlMailBanner(
                        text =
                            when {
                                // Nothing was reached, so there are no "other
                                // accounts" to be reassuring about and no account
                                // name to use — the call that would have listed them
                                // is the one that failed.
                                account.isWholeServer ->
                                    stringResource(
                                        R.string.server_unreachable,
                                        offline.host ?: account.displayName,
                                    )
                                // The hostname beside the account name, because the
                                // two answer different halves of "why". The account
                                // name is what the user calls this mailbox; the host
                                // is the machine they have to go and look at.
                                offline.host != null ->
                                    stringResource(
                                        R.string.account_unreachable_host,
                                        account.displayName,
                                        offline.host!!,
                                    )
                                else ->
                                    stringResource(
                                        R.string.account_unreachable,
                                        account.displayName,
                                    )
                            },
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
                rowsInFeed = rowsInFeed,
                labels = labels,
                viewing = label,
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
    /**
     * How many rows the feed table holds, or null before it has been read. See [hasNothingToShow].
     */
    rowsInFeed: Int?,
    /** Every label the user has, for resolving each row's stored keys into names. */
    labels: List<Label>,
    /** The label this list is showing, so its own name is not chipped onto every row in it. */
    viewing: Label?,
    selection: Set<String>,
    onThreadSelected: (ThreadEntity) -> Unit,
    onToggleSelected: (String) -> Unit,
    onAction: (ThreadEntity, MailAction) -> Unit,
) {
    if (threads.itemCount == 0) {
        // "Nothing here" and "still looking" are different answers, and showing
        // the first while the first page is in flight tells someone their inbox
        // is empty when it is not.
        if (hasNothingToShow(threads.loadState, rowsInFeed)) {
            PlMailEmptyState(
                icon = Icons.Outlined.Inbox,
                title = stringResource(R.string.inbox_empty),
                body = stringResource(R.string.inbox_empty_body),
            )
        } else {
            Message(stringResource(R.string.inbox_loading))
        }

        return
    }

    // Measured once for the whole list rather than once per row. What it
    // decides -- how many chips a row may draw -- is a composition question and
    // cannot be answered during measurement, and the pane's width is not the
    // window's: a tablet's list pane is a fraction of an 840dp window and can
    // easily be narrower than a phone. One subcomposition per list is nothing;
    // one per row, fifty times a scroll, is not.
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val labelSlots = rowLabelSlots(maxWidth)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            // Room under the last row for the compose button to sit in.
            //
            // `Scaffold` positions the FAB *over* its content and reports nothing
            // about it in the padding it hands back, so without this the button
            // covers whatever the list happens to end on -- a row's chips and date on
            // a long list, and on a short one the "That's everything on this device"
            // line, which is precisely the thing that tells someone the list is
            // finished rather than broken.
            //
            // `contentPadding` rather than a padded modifier or a spacer item,
            // because it has to scroll: padding the list would leave a permanent
            // dead band at the bottom of the viewport, and a trailing item would sit
            // below the end-of-list footer and be counted by anything that walks the
            // list's children.
            contentPadding = PaddingValues(bottom = FAB_CLEARANCE),
        ) {
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
                        // Resolved per row rather than precomputed for the page:
                        // it is a set intersection over a list the sidebar already
                        // holds in memory, and doing it here means a label renamed
                        // or deleted while the list is on screen corrects itself on
                        // the next frame.
                        labels = thread.rowLabels(labels, viewing, limit = labelSlots),
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
}

/**
 * Whether the list may say "Nothing here yet".
 *
 * It looks like it should be `itemCount == 0 && refresh !is Loading`, and that is what it was. That
 * version tells someone a label is empty at the one moment it has just been filled, and the reason
 * is worth stating because nothing about the API hints at it:
 *
 * 1. `CombinedLoadStates.refresh` is **the mediator's** refresh state once a `RemoteMediator`
 *    exists, not a combination of both. It reports "not loading" the instant the mediator returns.
 * 2. The mediator returns having *committed rows to the feed table*, not having handed them to
 *    Paging. Paging finds out through Room's invalidation tracker, which runs on the database's
 *    query executor — the same executor that is, on a list's first visit, busy writing the hundreds
 *    of message rows those feed rows point at.
 *
 * Between (1) and (2) the load states say nothing is loading and the item count says zero, which is
 * bit-for-bit the state of a genuinely empty label. Every later visit skips the initial refresh
 * because the table already has rows, which is why this only ever showed up once and never
 * reproduced.
 *
 * So the table answers instead, through [de.plmail.core.data.FeedRepository.rowsHeld]. Null means
 * nobody has asked yet, which is not the same as zero and must not be drawn as one — a screen that
 * treated it as zero would flash the empty state on every cold launch.
 *
 * Both load states are consulted rather than the convenience one, for the reason in (1): the source
 * can still be reading the rows the mediator wrote after the mediator has finished writing them.
 */
internal fun hasNothingToShow(state: CombinedLoadStates, rowsInFeed: Int?): Boolean =
    when {
        rowsInFeed == null -> false
        rowsInFeed > 0 -> false
        state.source.refresh is LoadState.Loading -> false
        state.mediator?.refresh is LoadState.Loading -> false
        else -> true
    }

/**
 * How far the row's text column starts from the edge.
 *
 * The gutter plus the avatar plus the gap between them. Kept as one constant rather than three
 * additions at the call site so the divider cannot drift out of alignment with the text it is
 * separating.
 */
private val ROW_TEXT_INSET = 72.dp

/**
 * How far the list scrolls past the compose button.
 *
 * The button is 56dp and `Scaffold` insets it 16dp from the bottom, so 72dp is where it stops
 * covering things and 88dp is where the last row stops looking crowded by it. Spelled out as an
 * addition rather than as the number 88 so it is obvious what has to change if the button ever does
 * — a bare constant here is one that quietly stops matching a resized FAB.
 */
private val FAB_CLEARANCE = 56.dp + 16.dp + 16.dp

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
