package de.plmail.feature.mail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.Snooze
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.plmail.core.data.MailAction
import de.plmail.core.data.RowLabels
import de.plmail.core.data.SwipeAction
import de.plmail.core.data.SwipeActions
import de.plmail.core.database.ThreadEntity
import de.plmail.core.designsystem.PlMailLabelColor
import de.plmail.core.ui.RowChip
import de.plmail.core.ui.ThreadRow
import kotlinx.coroutines.launch

/**
 * A thread row that can be swiped.
 *
 * Both directions are also reachable as explicit controls in the selection bar — a gesture is the
 * fast path, never the only path, because a swipe is undiscoverable and impossible for anyone using
 * a switch device or TalkBack.
 *
 * What each direction does is the user's — see [SwipeActions]. The default is archive towards the
 * end and trash towards the start, matching what every mail client on the platform does; the
 * destructive one being the deliberate second gesture matters more than which side it is on.
 *
 * **A swipe that does not remove the row puts the row back.** Archive and trash take the
 * conversation out of the list and the gap closes behind it. Marking read does not, and neither
 * does opening the snooze menu or the move sheet — the conversation is still here until something
 * is chosen — so the row is reset rather than left parked off-screen over a background that says an
 * action is in progress.
 */
@Composable
fun SwipeableThreadRow(
    thread: ThreadEntity,
    isSelected: Boolean,
    labels: RowLabels,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onAction: (MailAction) -> Unit,
    /** Opens "Move to" over this one conversation. Hosted a level up, like the label sheet. */
    onMove: () -> Unit = {},
    swipes: SwipeActions = SwipeActions(),
    /**
     * Whether the row should carry its account's mark.
     *
     * Passed down rather than derived, because this composable can see one conversation and the
     * question is about the whole list: a mark saying "this one is from your work address" means
     * nothing on a list where every row is.
     */
    showsAccount: Boolean = false,
    /**
     * Whether the row carries a **New** badge.
     *
     * Passed down for the same reason [showsAccount] is, and for one more: the answer cannot be
     * read off the conversation at all, because drawing the row is what retires the server's
     * marker. See [de.plmail.feature.mail.MailViewModel.badgedNew].
     */
    isNew: Boolean = false,
) {
    // Scoped to the conversation, not to the position in the list.
    //
    // Without the key, the dismiss state belongs to the slot: archiving a row
    // removes it, the row below shifts up into the same slot, and inherits a
    // state that is still "dismissed" -- which fires the action again, on a
    // conversation the user never touched, repeatedly, until the list is empty.
    // That is exactly what happened.
    key(thread.uid) {
        val state = rememberSwipeToDismissBoxState()

        var isSnoozeOpen by remember { mutableStateOf(false) }
        var isPickingTime by remember { mutableStateOf(false) }

        if (isPickingTime) {
            SnoozePicker(
                onDismiss = { isPickingTime = false },
                onChosen = {
                    isPickingTime = false
                    onAction(MailAction.Snooze(it.toEpochMilli()))
                },
            )
        }

        // A swipe waiting to be confirmed, where the user has asked to be asked.
        var pending by remember { mutableStateOf<SwipeAction?>(null) }
        val scope = rememberCoroutineScope()

        // What a swipe does once nothing stands between it and doing it.
        fun perform(swiped: SwipeAction) {
            when (swiped) {
                SwipeAction.ARCHIVE -> onAction(MailAction.Archive)
                SwipeAction.TRASH -> onAction(MailAction.Trash)
                SwipeAction.READ -> onAction(MailAction.MarkRead(seen = thread.isUnread))
                SwipeAction.SNOOZE -> isSnoozeOpen = true
                SwipeAction.MOVE -> onMove()
                SwipeAction.NONE -> Unit
            }
        }

        // Fired once per conversation. An action that removes the row from the
        // feed table lets the list close the gap rather than this animating a
        // row that is about to disappear anyway; one that does not, puts the
        // row back.
        LaunchedEffect(state.currentValue) {
            val swiped =
                when (state.currentValue) {
                    SwipeToDismissBoxValue.StartToEnd -> swipes.toEnd
                    SwipeToDismissBoxValue.EndToStart -> swipes.toStart
                    SwipeToDismissBoxValue.Settled -> return@LaunchedEffect
                }

            // Held open under the question, so what is being asked about is
            // still showing behind it: the row stays aside over its icon until
            // the answer comes.
            if (swipes.confirm.asksBefore(swiped)) {
                pending = swiped

                return@LaunchedEffect
            }

            perform(swiped)

            if (!swiped.removesTheRow) state.reset()
        }

        pending?.let { asked ->
            SwipeConfirmation(
                action = asked,
                isUnread = thread.isUnread,
                onConfirm = {
                    pending = null
                    perform(asked)

                    if (!asked.removesTheRow) scope.launch { state.reset() }
                },
                // Back, outside and "Cancel" are all the same answer, and the
                // row goes back to where it was.
                onDismiss = {
                    pending = null
                    scope.launch { state.reset() }
                },
            )
        }

        SwipeToDismissBox(
            state = state,
            // A direction set to "nothing" does not move at all. A row that
            // slid aside over an empty background and sprang back would be a
            // gesture that looks broken rather than one that is switched off.
            enableDismissFromStartToEnd = swipes.toEnd != SwipeAction.NONE,
            enableDismissFromEndToStart = swipes.toStart != SwipeAction.NONE,
            backgroundContent = {
                SwipeBackground(
                    direction = state.dismissDirection,
                    swipes = swipes,
                    isUnread = thread.isUnread,
                )
            },
            content = {
                ThreadRow(
                    thread = thread,
                    onClick = onClick,
                    showsAccount = showsAccount,
                    onLongClick = onLongClick,
                    isSelected = isSelected,
                    // The one place the server's colour token becomes a colour
                    // this app can draw. `:core:data` carries the raw string
                    // because it cannot see the design system, and `:core:ui`
                    // cannot see `:core:data`; this feature sees both.
                    labels =
                        labels.labels.map {
                            RowChip(name = it.name, color = PlMailLabelColor.fromWire(it.color))
                        },
                    hiddenLabels = labels.hidden,
                    isNew = isNew,
                )

                // Anchored to the row it is about, so the times open where the
                // thumb already is.
                SnoozeMenu(
                    isOpen = isSnoozeOpen,
                    onDismiss = { isSnoozeOpen = false },
                    onChosen = { onAction(MailAction.Snooze(it.toEpochMilli())) },
                    onPickExact = { isPickingTime = true },
                )
            },
        )
    }
}

/**
 * "Move this conversation to the trash?", for somebody who asked to be asked.
 *
 * The confirming button carries the verb rather than "OK", so the dialog can be answered without
 * reading its sentence — and so the two buttons cannot be mistaken for each other at a glance.
 */
@Composable
private fun SwipeConfirmation(
    action: SwipeAction,
    isUnread: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(stringResource(action.question(isUnread))) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(action.label(isUnread))) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.swipe_confirm_cancel)) }
        },
    )
}

private fun SwipeAction.question(isUnread: Boolean): Int =
    when (this) {
        SwipeAction.TRASH -> R.string.swipe_confirm_trash
        SwipeAction.READ ->
            if (isUnread) R.string.swipe_confirm_read else R.string.swipe_confirm_unread
        else -> R.string.swipe_confirm_archive
    }

/** Whether the conversation leaves the list the moment this is swiped. */
private val SwipeAction.removesTheRow: Boolean
    get() = this == SwipeAction.ARCHIVE || this == SwipeAction.TRASH

/**
 * What is revealed behind the row.
 *
 * Colour and icon both, rather than colour alone: colour is the fast signal and the icon is the one
 * that survives a red-green colour deficiency, which is common enough that a destructive gesture
 * distinguished only by hue is a real hazard.
 */
@Composable
private fun SwipeBackground(
    direction: SwipeToDismissBoxValue,
    swipes: SwipeActions,
    isUnread: Boolean,
) {
    // Nothing at all while settled. The background is drawn for *every* row,
    // not only the one under the thumb, so colouring the settled state paints
    // the whole list in the trash colour and makes an untouched inbox look
    // like a pending deletion.
    if (direction == SwipeToDismissBoxValue.Settled) return

    val towardsEnd = direction == SwipeToDismissBoxValue.StartToEnd
    val action = if (towardsEnd) swipes.toEnd else swipes.toStart

    // Red is for the one that throws mail away, whichever side it is on now.
    val destructive = action == SwipeAction.TRASH

    val colour =
        if (destructive) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.secondaryContainer

    Box(
        modifier = Modifier.fillMaxSize().background(colour).padding(horizontal = 24.dp),
        contentAlignment = if (towardsEnd) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        val icon = action.icon(isUnread) ?: return@Box

        Icon(
            imageVector = icon,
            contentDescription = stringResource(action.label(isUnread)),
            tint =
                if (destructive) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

private fun SwipeAction.icon(isUnread: Boolean): ImageVector? =
    when (this) {
        SwipeAction.NONE -> null
        SwipeAction.ARCHIVE -> Icons.Outlined.Archive
        SwipeAction.TRASH -> Icons.Outlined.Delete
        SwipeAction.READ ->
            if (isUnread) Icons.Outlined.MarkEmailRead else Icons.Outlined.MarkEmailUnread
        SwipeAction.SNOOZE -> Icons.Outlined.Snooze
        SwipeAction.MOVE -> Icons.AutoMirrored.Outlined.DriveFileMove
    }

private fun SwipeAction.label(isUnread: Boolean): Int =
    when (this) {
        SwipeAction.NONE,
        SwipeAction.ARCHIVE -> R.string.action_archive
        SwipeAction.TRASH -> R.string.action_trash
        SwipeAction.READ -> if (isUnread) R.string.action_read else R.string.action_unread
        SwipeAction.SNOOZE -> R.string.snooze
        SwipeAction.MOVE -> R.string.move_to
    }
