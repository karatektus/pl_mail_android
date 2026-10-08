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
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
 * Two things are narrower here than on the web, both on purpose:
 * - A label is offered only where every conversation's account binds it. The web's labels are the
 *   user's; a phone patches one account's mailbox ids, and a row that would be refused for half the
 *   selection is not a row.
 * - From Trash or Spam the user's labels are not offered. Taking mail out of the bin is a provider
 *   operation the server composes from restore and archive, and `Email/set` has no way to ask for
 *   it — detaching Trash as though it were a tag is exactly what the server's own move refuses to
 *   do. Back to the Inbox is offered, which is the restore that already exists.
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
    // own leaves.
    val leaving = viewed?.takeIf { it.role == null }
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

        if (!isBin) {
            labels
                .filter { it.role == null && it.key != leaving?.key && it.key !in selection.onAll }
                .filter { label ->
                    accounts.all { key -> label.bindings.any { it.accountKey == key } }
                }
                .forEach { add(MoveDestination.To(it, MailAction.MoveTo(it, leaving))) }
        }

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
) {
    val sheet = rememberModalBottomSheetState()
    val theme = PlMailTheme.values

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

            LazyColumn(modifier = Modifier.heightIn(max = MOVE_LIST_MAX)) {
                items(items = destinations, key = { it.key() }) { destination ->
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
                            text =
                                when (destination) {
                                    is MoveDestination.Inbox -> stringResource(R.string.role_inbox)
                                    is MoveDestination.To -> destination.label.displayName()
                                    MoveDestination.Spam -> stringResource(R.string.role_junk)
                                    MoveDestination.Trash -> stringResource(R.string.role_trash)
                                },
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

private fun MoveDestination.key(): String =
    when (this) {
        is MoveDestination.Inbox -> "role:inbox"
        is MoveDestination.To -> "label:${label.key}"
        MoveDestination.Spam -> "role:junk"
        MoveDestination.Trash -> "role:trash"
    }

/** Taller than the label sheet's list: there is no row underneath this one to keep in reach. */
private val MOVE_LIST_MAX = 420.dp
