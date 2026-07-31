package de.plmail.feature.mail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.plmail.core.data.MailAction
import de.plmail.core.database.ThreadEntity

/**
 * A thread row that can be swiped.
 *
 * Both directions are also reachable as explicit controls in the selection bar — a gesture is the
 * fast path, never the only path, because a swipe is undiscoverable and impossible for anyone using
 * a switch device or TalkBack.
 *
 * Trash is deliberately the *end*-to-start direction and archive the other way round, matching what
 * every mail client on the platform does; the destructive one being the deliberate second gesture
 * matters more than which side it is on.
 */
@Composable
fun SwipeableThreadRow(
    thread: ThreadEntity,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onAction: (MailAction) -> Unit,
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

        // Fired once per conversation. The action removes the row from the feed
        // table itself, so the list closes the gap rather than this animating a
        // row that is about to disappear anyway.
        LaunchedEffect(state.currentValue) {
            when (state.currentValue) {
                SwipeToDismissBoxValue.StartToEnd -> onAction(MailAction.Archive)
                SwipeToDismissBoxValue.EndToStart -> onAction(MailAction.Trash)
                SwipeToDismissBoxValue.Settled -> Unit
            }
        }

        SwipeToDismissBox(
            state = state,
            backgroundContent = { SwipeBackground(state.dismissDirection) },
            content = {
                ThreadRow(
                    thread = thread,
                    onClick = onClick,
                    onLongClick = onLongClick,
                    isSelected = isSelected,
                )
            },
        )
    }
}

/**
 * What is revealed behind the row.
 *
 * Colour and icon both, rather than colour alone: colour is the fast signal and the icon is the one
 * that survives a red-green colour deficiency, which is common enough that a destructive gesture
 * distinguished only by hue is a real hazard.
 */
@Composable
private fun SwipeBackground(direction: SwipeToDismissBoxValue) {
    // Nothing at all while settled. The background is drawn for *every* row,
    // not only the one under the thumb, so colouring the settled state paints
    // the whole list in the trash colour and makes an untouched inbox look
    // like a pending deletion.
    if (direction == SwipeToDismissBoxValue.Settled) return

    val archiving = direction == SwipeToDismissBoxValue.StartToEnd

    val colour =
        if (archiving) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.errorContainer

    val alignment = if (archiving) Alignment.CenterStart else Alignment.CenterEnd

    Box(
        modifier = Modifier.fillMaxSize().background(colour).padding(horizontal = 24.dp),
        contentAlignment = alignment,
    ) {
        Icon(
            imageVector = if (archiving) Icons.Outlined.Archive else Icons.Outlined.Delete,
            contentDescription =
                stringResource(if (archiving) R.string.action_archive else R.string.action_trash),
            tint =
                if (archiving) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}
