package de.plmail.feature.mail

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import de.plmail.core.data.ActionOutcome
import de.plmail.core.data.MailAction
import de.plmail.core.data.UndoableAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/** A change to announce, with the undo that reverses it. */
data class ActionAnnouncement(
    val outcome: ActionOutcome,
    /**
     * Distinguishes two identical archives so the snackbar re-shows rather than being deduplicated.
     */
    val id: Long,
)

/**
 * The "one thing just happened, here is the way back" channel.
 *
 * A small class rather than four fields copied into every ViewModel that can act on mail. There are
 * two of those now — the list and the reader — and there will be more; what matters is that the
 * monotonic id is generated in one place, because it is the only thing that makes two identical
 * archives show two snackbars instead of one.
 */
class ActionAnnouncements {

    private val _announcement = MutableStateFlow<ActionAnnouncement?>(null)
    val announcement: StateFlow<ActionAnnouncement?> = _announcement.asStateFlow()

    private var count = 0L

    fun announce(outcome: ActionOutcome) {
        _announcement.update { ActionAnnouncement(outcome, count++) }
    }

    /** Cleared by id, so an announcement that arrived while the last was showing is not lost. */
    fun shown(id: Long) {
        _announcement.update { current -> current?.takeIf { it.id != id } }
    }
}

/**
 * Shows an action's announcement, with its undo, for as long as the window lasts.
 *
 * Shared by every screen that can act on mail so that the window is one number rather than a habit,
 * and so the accessibility extension below cannot be remembered on one screen and forgotten on the
 * next.
 */
@Composable
fun UndoSnackbar(
    announcement: ActionAnnouncement?,
    snackbars: SnackbarHostState,
    onUndo: (UndoableAction) -> Unit,
    onShown: (Long) -> Unit,
) {
    // Resolved through the composition's own resources rather than
    // LocalContext.resources, which does not recompose on a locale change --
    // the snackbar would keep the language the screen was first created in.
    val message = announcement?.let { describe(it.outcome) }.orEmpty()
    val undoLabel = stringResource(R.string.undo)
    val accessibility = LocalAccessibilityManager.current

    LaunchedEffect(announcement?.id) {
        val shown = announcement ?: return@LaunchedEffect

        // Extended for anyone the system says needs longer. This is the one
        // control in the app with a deadline, so it is the one place where a
        // fixed timeout quietly excludes people -- and it is exactly the users
        // who need the extra seconds who would lose them.
        val window =
            accessibility?.calculateRecommendedTimeoutMillis(
                originalTimeoutMillis = UNDO_WINDOW_MILLIS,
                containsIcons = false,
                containsText = true,
                containsControls = true,
            ) ?: UNDO_WINDOW_MILLIS

        // Indefinite plus a timeout, rather than SnackbarDuration.Short. Short
        // is four seconds, and this code claimed six in a comment while asking
        // for it -- four is not enough to watch a row leave, decide against it
        // and reach a button at the other end of the screen, which is a large
        // part of why the undo path was so hard to catch working that it shipped
        // without anyone seeing it. Material offers no way to name a duration,
        // so the duration is named here.
        val result =
            withTimeoutOrNull(window) {
                snackbars.showSnackbar(
                    message = message,
                    actionLabel = undoLabel,
                    duration = SnackbarDuration.Indefinite,
                )
            }

        if (result == SnackbarResult.ActionPerformed) onUndo(shown.outcome.undoable)

        onShown(shown.id)
    }
}

/**
 * How long the way back stays on screen.
 *
 * Six seconds is the product's window, and it is a deliberate number rather than a default: long
 * enough to see a row leave, realise it was the wrong one and reach the button, short enough that
 * it is not sitting over the list while somebody reads.
 */
private const val UNDO_WINDOW_MILLIS = 6_000L

/** What the snackbar says. Conversations, because conversations are what the user acted on. */
@Composable
private fun describe(outcome: ActionOutcome): String {
    val undoable = outcome.undoable
    val count = undoable.threadCount

    val action = undoable.action

    val done =
        when {
            // The way back took more than one change -- a bulk unsnooze put
            // conversations back to times that differed. Naming one of them
            // would be a lie about the rest, and the count is the part the user
            // is checking anyway.
            action == null -> pluralStringResource(R.plurals.put_back, count, count)
            action == MailAction.Archive -> pluralStringResource(R.plurals.archived, count, count)
            action == MailAction.Trash -> pluralStringResource(R.plurals.trashed, count, count)
            action == MailAction.MoveToInbox ->
                pluralStringResource(R.plurals.moved_to_inbox, count, count)
            action == MailAction.MarkSpam ->
                pluralStringResource(R.plurals.marked_spam, count, count)
            action is MailAction.SetLabel && action.applied ->
                pluralStringResource(R.plurals.labelled, count, count)
            action is MailAction.SetLabel ->
                pluralStringResource(R.plurals.unlabelled, count, count)
            action is MailAction.Snooze && action.until != null ->
                pluralStringResource(R.plurals.snoozed, count, count)
            action is MailAction.Snooze -> pluralStringResource(R.plurals.unsnoozed, count, count)
            else -> stringResource(R.string.changed)
        }

    return when (outcome) {
        is ActionOutcome.Applied -> done
        // Said out loud: the row already moved, so a rejection nobody mentions
        // leaves the user believing something happened that did not.
        is ActionOutcome.Rejected -> stringResource(R.string.action_rejected, done)
        // A different sentence from both, because it is a different promise.
        // The change is real on this phone and the server has not been told —
        // saying "archived" would claim it reached a machine that is switched
        // off, and saying it failed would be wrong about what the user is
        // looking at. The hostname is included where the transport knew it,
        // because "can't reach nas.local" is something somebody can act on.
        is ActionOutcome.Queued ->
            outcome.host?.let { stringResource(R.string.action_queued_host, done, it) }
                ?: stringResource(R.string.action_queued, done)
    }
}
