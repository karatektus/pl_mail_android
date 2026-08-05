package de.plmail.feature.calendar

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Which occurrence a detail screen is showing. */
data class OccurrenceKey(val eventKey: String, val date: String)

/** What the editor was opened for. */
sealed interface EditorRequest {
    data object New : EditorRequest

    /**
     * By key rather than by value.
     *
     * The editor outlives a refresh, and a captured row would keep the times it had when it was
     * opened — so a save would send back whatever the cache held before the server was last asked.
     */
    data class Edit(val eventKey: String) : EditorRequest
}

/**
 * The calendar, and everything reachable from it.
 *
 * Three screens swapped by state rather than pushed onto a back stack, which is the same shape
 * `MainActivity` uses and is deliberate rather than inherited: the flags are not mutually exclusive
 * — opening the editor from a detail leaves both set — so the winner is derived **once** and used
 * by both the `when` that draws and the [BackHandler] that dismisses. One handler per flag is how
 * back ends up closing a screen nobody can see.
 *
 * Opening the editor closes the detail underneath it, so back from the editor lands on the agenda
 * rather than on a detail describing an event that has just been renamed or deleted.
 */
@Composable
fun CalendarScreen(onBack: () -> Unit, viewModel: CalendarViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    var open by rememberSaveable(stateSaver = OccurrenceKeySaver) { mutableStateOf(null) }
    var editing by rememberSaveable(stateSaver = EditorRequestSaver) { mutableStateOf(null) }

    val screen =
        when {
            editing != null -> CalendarPage.EDITOR
            open != null -> CalendarPage.DETAIL
            else -> CalendarPage.AGENDA
        }

    // The agenda's own back is :app's to handle -- it is the step out of the
    // calendar and into the mail -- so this handler is disabled there and the
    // one above it takes the gesture.
    BackHandler(enabled = screen != CalendarPage.AGENDA) {
        when (screen) {
            CalendarPage.EDITOR -> editing = null
            CalendarPage.DETAIL -> open = null
            CalendarPage.AGENDA -> Unit
        }
    }

    // Resolved from the list rather than captured at the tap, so an event that a
    // refresh has moved out of the window closes its own detail instead of
    // describing a day it is no longer on.
    val showing = open?.let { key ->
        state.days
            .firstOrNull { it.date.toString() == key.date }
            ?.rows
            ?.firstOrNull { it.eventKey == key.eventKey }
    }

    when (screen) {
        CalendarPage.EDITOR ->
            EventEditorScreen(
                request = editing ?: EditorRequest.New,
                calendars = state.calendars,
                onClose = { editing = null },
            )
        CalendarPage.DETAIL ->
            if (showing == null) {
                // The row has gone -- deleted here, or moved out of the window by
                // a refresh -- so the detail closes itself. In an effect rather
                // than in the branch: writing state while composing is what makes
                // a screen recompose forever, and the agenda underneath already
                // says what is there now.
                //
                // Keyed on the *state* as well, so the frame in which the list is
                // still empty on a cold open does not close a detail that is
                // about to resolve.
                LaunchedEffect(open, state.days) { if (state.days.isNotEmpty()) open = null }
            } else {
                EventDetailScreen(
                    row = showing,
                    calendars = state.calendars,
                    onBack = { open = null },
                    onEdit = {
                        open = null
                        editing = EditorRequest.Edit(showing.eventKey)
                    },
                )
            }
        CalendarPage.AGENDA ->
            AgendaScreen(
                state = state,
                onBack = onBack,
                onRefresh = viewModel::refresh,
                onOpen = { row -> open = OccurrenceKey(row.eventKey, row.date) },
                onNew = { editing = EditorRequest.New },
            )
    }
}

/** Which of the calendar's own screens is on top. See [CalendarScreen]. */
private enum class CalendarPage {
    AGENDA,
    DETAIL,
    EDITOR,
}

/** Primitives only, so a rename cannot change what a saved bundle means. */
private val OccurrenceKeySaver: Saver<OccurrenceKey?, Any> =
    listSaver<OccurrenceKey?, String>(
        save = { it?.let { key -> listOf(key.eventKey, key.date) }.orEmpty() },
        restore = {
            it.takeIf { saved -> saved.size == 2 }?.let { s -> OccurrenceKey(s[0], s[1]) }
        },
    )

private val EditorRequestSaver: Saver<EditorRequest?, Any> =
    listSaver<EditorRequest?, String>(
        save = {
            when (it) {
                null -> emptyList()
                EditorRequest.New -> listOf("new")
                is EditorRequest.Edit -> listOf("edit", it.eventKey)
            }
        },
        restore = {
            when (it.getOrNull(0)) {
                "new" -> EditorRequest.New
                "edit" -> EditorRequest.Edit(it[1])
                else -> null
            }
        },
    )
