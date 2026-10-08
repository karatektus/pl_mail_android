package de.plmail.feature.mail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.plmail.core.data.ActionTarget
import de.plmail.core.data.Label
import de.plmail.core.data.LabelSelection
import de.plmail.core.data.MailAction
import de.plmail.core.data.MailView
import de.plmail.core.designsystem.PlMailTheme

/**
 * One row of the "Move to" sheet: where the mail would go, and the action that takes it there.
 *
 * The action is decided when the row is built rather than when it is tapped, because it is not the
 * same action for every row — the bin and spam are their own, with their own paths on the server —
 * and a sheet that worked that out on the tap would be a second place the rule lived.
 */
sealed interface MoveDestination {
    val action: MailAction

    data class Inbox(override val action: MailAction) : MoveDestination

    data class To(val label: Label, override val action: MailAction) : MoveDestination

    data object Spam : MoveDestination {
        override val action: MailAction
            get() = MailAction.MarkSpam
    }

    data object Trash : MoveDestination {
        override val action: MailAction
            get() = MailAction.Trash
    }
}

/**
 * Where [targets] can be moved to from [view]: the Inbox, the user's labels, Spam and Trash.
 *
 * The web's picker, rule for rule (`MoveToService`): the list being looked at is left off, and so
 * is a label every one of the conversations already wears. What comes off is the view's own label —
 * and only the Inbox, a label the user made, the bin and spam count as one. Sent, Starred and the
 * rest say how a message came to be there rather than where it is filed, so a move from them takes
 * the Inbox off and nothing else.
 *
 * One thing is narrower here than on the web, on purpose: a label is offered only where every
 * conversation's account binds it. The web's labels are the user's; a phone names one account's
 * mailbox ids, and a row that would be refused for half the selection is not a row.
 *
 * From Trash or Spam the user's labels are offered like anywhere else. Taking mail out of the bin
 * is a provider operation, and it is the server that carries the move out (`Thread/set` `moveTo`) —
 * a server too old for that refuses, and the app says so rather than detaching Trash as though it
 * were a tag. Back to the Inbox from the bin stays the restore that every server has.
 */
fun moveDestinations(
    view: MailView,
    labels: List<Label>,
    targets: List<ActionTarget>,
    selection: LabelSelection,
): List<MoveDestination> {
    val viewed = view.browsedLabel
    val isBin = viewed?.role == ROLE_TRASH || viewed?.role == ROLE_JUNK
    // Null is the Inbox, and it is also what every list with no label of its
    // own leaves. The bin and spam are lists with one: moving out of them
    // leaves it behind.
    val leaving = viewed?.takeIf { it.role == null || isBin }
    val leavesInbox = leaving == null && !isBin
    val accounts = targets.map { it.accountKey }.toSet()

    return buildList {
        when {
            isBin -> add(MoveDestination.Inbox(MailAction.MoveToInbox))
            // Not from the Inbox itself, and not from a list that only stands
            // in for it: there the move would put the Inbox on and take the
            // Inbox off.
            !leavesInbox -> add(MoveDestination.Inbox(MailAction.MoveTo(null, leaving)))
        }

        labels
            .filter { it.role == null && it.key != leaving?.key && it.key !in selection.onAll }
            .filter { label ->
                accounts.all { key -> label.bindings.any { it.accountKey == key } }
            }
            .forEach { add(MoveDestination.To(it, MailAction.MoveTo(it, leaving))) }

        if (viewed?.role != ROLE_JUNK) add(MoveDestination.Spam)
        if (viewed?.role != ROLE_TRASH) add(MoveDestination.Trash)
    }
}

private const val ROLE_TRASH = "trash"
private const val ROLE_JUNK = "junk"

/**
 * "Move to", over one conversation or a selection.
 *
 * One tap and it is done, unlike [LabelSheet] beside it: a move has one destination, so there is
 * nothing to tick and nothing to confirm, and the way back is the snackbar every other action has.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoveSheet(
    destinations: List<MoveDestination>,
    targets: List<ActionTarget>,
    onPick: (MailAction) -> Unit,
    onDismiss: () -> Unit,
    /** Label keys mail was last moved to, newest first. They lead the list. */
    recent: List<String> = emptyList(),
) {
    val sheet = rememberModalBottomSheetState()
    val theme = PlMailTheme.values

    var query by rememberSaveable { mutableStateOf("") }

    val labelCount = destinations.count { it is MoveDestination.To }
    val names = destinations.associateWith { it.name() }
    val shown =
        remember(destinations, recent, query, names) {
            destinations.recentFirst(recent).matching(query) { names.getValue(it) }
        }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = theme.colors.surface,
    ) {
        Column(modifier = Modifier.navigationBarsPadding().padding(bottom = theme.spacing.large)) {
            Text(
                text = stringResource(R.string.move_to),
                style = MaterialTheme.typography.titleMedium,
                color = theme.colors.ink,
                modifier =
                    Modifier.padding(
                        horizontal = theme.spacing.gutter,
                        vertical = theme.spacing.small,
                    ),
            )

            // Only once there are enough labels to lose one in. On a short list
            // a search box is a keyboard that covers the thing being looked for.
            if (labelCount > FILTER_FROM) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.move_filter)) },
                    modifier =
                        Modifier.fillMaxWidth()
                            .padding(horizontal = theme.spacing.gutter)
                            .padding(bottom = theme.spacing.small),
                )
            }

            if (shown.isEmpty()) {
                Text(
                    text = stringResource(R.string.move_no_match),
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.colors.inkMuted,
                    modifier = Modifier.padding(horizontal = theme.spacing.gutter),
                )
            }

            LazyColumn(modifier = Modifier.heightIn(max = MOVE_LIST_MAX)) {
                items(items = shown, key = { it.key() }) { destination ->
                    Row(
                        modifier =
                            Modifier.fillMaxWidth()
                                .clickable { onPick(destination.action) }
                                .heightIn(min = theme.spacing.touchTarget)
                                .padding(horizontal = theme.spacing.gutter),
                        horizontalArrangement = Arrangement.spacedBy(theme.spacing.medium),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector =
                                when (destination) {
                                    is MoveDestination.Inbox -> Icons.Outlined.Inbox
                                    is MoveDestination.To -> Icons.AutoMirrored.Outlined.Label
                                    MoveDestination.Spam -> Icons.Outlined.Report
                                    MoveDestination.Trash -> Icons.Outlined.Delete
                                },
                            contentDescription = null,
                            tint = theme.colors.inkSoft,
                        )

                        Text(
                            text = names.getValue(destination),
                            color = theme.colors.ink,
                            maxLines = 1,
                            overflow = TextOverflow.MiddleEllipsis,
                        )
                    }
                }
            }
        }
    }

    // Nothing to move. The same guard as the label sheet, for the same reason.
    LaunchedEffect(targets) { if (targets.isEmpty()) onDismiss() }
}

@Composable
private fun MoveDestination.name(): String =
    when (this) {
        is MoveDestination.Inbox -> stringResource(R.string.role_inbox)
        is MoveDestination.To -> label.displayName()
        MoveDestination.Spam -> stringResource(R.string.role_junk)
        MoveDestination.Trash -> stringResource(R.string.role_trash)
    }

/**
 * The same rows with the labels mail was last moved to leading the user's own.
 *
 * Moved, not copied: a label listed under "recent" and again in its alphabetical place is two rows
 * that do the same thing. Only the user's labels are reordered — the Inbox stays first and the bin
 * and spam stay last, because those are found by position.
 */
internal fun List<MoveDestination>.recentFirst(recent: List<String>): List<MoveDestination> {
    val labels = filterIsInstance<MoveDestination.To>()
    val byKey = labels.associateBy { it.label.key }
    val leading = recent.mapNotNull { byKey[it] }
    val ordered = leading + (labels - leading.toSet())
    val next = ordered.iterator()

    return map { if (it is MoveDestination.To) next.next() else it }
}

/** The rows whose name contains [query], or all of them for a blank one. */
internal fun List<MoveDestination>.matching(
    query: String,
    name: (MoveDestination) -> String,
): List<MoveDestination> {
    val wanted = query.trim()

    return if (wanted.isEmpty()) this else filter { name(it).contains(wanted, ignoreCase = true) }
}

/** How many of the user's labels it takes before the sheet offers to filter them. */
private const val FILTER_FROM = 8

private fun MoveDestination.key(): String =
    when (this) {
        is MoveDestination.Inbox -> "role:inbox"
        is MoveDestination.To -> "label:${label.key}"
        MoveDestination.Spam -> "role:junk"
        MoveDestination.Trash -> "role:trash"
    }

/** Taller than the label sheet's list: there is no row underneath this one to keep in reach. */
private val MOVE_LIST_MAX = 420.dp
