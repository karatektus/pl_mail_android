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
import java.time.LocalDateTime

/** Which occurrence a detail screen is showing. */
data class OccurrenceKey(val eventKey: String, val date: String)

/** What the editor was opened for. */
sealed interface EditorRequest {
    data object New : EditorRequest

    /**
     * A new event at a time the user pointed at — the grid's long press, and the month cell's.
     *
     * A separate case rather than `New` carrying a nullable start, so the editor cannot forget to
     * distinguish them: `New` means "some time soon, rounded up", which is the right proposal from
     * a `+` button and is exactly the wrong one from a finger held over the 14:30 line.
     *
     * The time is carried as a wire string rather than as a `LocalDateTime`, so
     * [EditorSessionSaver] stays primitives — a saved bundle must not change meaning because a type
     * was renamed.
     */
    data class NewAt(val startWire: String) : EditorRequest {
        val start: LocalDateTime?
            get() = startWire.toLocalDateTimeOrNull()
    }

    /**
     * By key rather than by value.
     *
     * The editor outlives a refresh, and a captured row would keep the times it had when it was
     * opened — so a save would send back whatever the cache held before the server was last asked.
     */
    data class Edit(val eventKey: String) : EditorRequest
}

/**
 * One *opening* of the editor: what it is for, and the fact that it is a new one.
 *
 * [serial] is the whole point and it is not decoration. `EventEditorViewModel` is scoped to the
 * activity, so it outlives the screen, and it guards against reloading the form on every
 * recomposition by comparing what it was opened for — which works for editing two different events
 * and fails for creating two events in a row, because [EditorRequest.New] equals itself. Watched on
 * a device on 2026-08-06: type a title, save, tap New again, and the form comes back holding the
 * event that was just created, a single tap away from being created twice.
 *
 * A counter rather than a reset on close, because the editor can leave the screen in ways it never
 * hears about — the system back gesture is handled by [CalendarScreen], not by the editor — and a
 * reset that the commonest exit route skips is the same bug with a longer path to it.
 */
data class EditorSession(val serial: Int, val request: EditorRequest)

/**
 * The calendar, and everything reachable from it.
 *
 * Three screens swapped by state rather than pushed onto a back stack, which is the same shape
 * `MainActivity` uses and is deliberate rather than inherited: the flags are not mutually exclusive
 * — opening the editor from a detail leaves both set — so the winner is derived **once** and used
 * by both the `when` that draws and the [BackHandler] that dismisses. One handler per flag is how
 * back ends up closing a screen nobody can see.
 *
 * Opening the editor closes the detail underneath it, so back from the editor lands on the board
 * rather than on a detail describing an event that has just been renamed or deleted.
 */
@Composable
fun CalendarScreen(onBack: () -> Unit, viewModel: CalendarViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    var open by rememberSaveable(stateSaver = OccurrenceKeySaver) { mutableStateOf(null) }
    var editing by rememberSaveable(stateSaver = EditorSessionSaver) { mutableStateOf(null) }

    // Saved beside the session rather than derived from it: it has to keep
    // counting across the editor being closed, which is precisely when the
    // session is null, and it has to survive a rotation or the second New after
    // one would be the first again.
    var openings by rememberSaveable { mutableStateOf(0) }

    val screen =
        when {
            editing != null -> CalendarPage.EDITOR
            open != null -> CalendarPage.DETAIL
            else -> CalendarPage.BOARD
        }

    // The board's own back is :app's to handle -- it is the step out of the
    // calendar and into the mail -- so this handler is disabled there and the
    // one above it takes the gesture.
    BackHandler(enabled = screen != CalendarPage.BOARD) {
        when (screen) {
            CalendarPage.EDITOR -> editing = null
            CalendarPage.DETAIL -> open = null
            CalendarPage.BOARD -> Unit
        }
    }

    // Resolved from the list rather than captured at the tap, so an event that a
    // refresh has moved out of the window closes its own detail instead of
    // describing a day it is no longer on. The cluster is re-derived too, which
    // is deliberate: a cluster is a fact about the data at the moment it was
    // read, and holding one across a refresh would be a claim the next write can
    // silently falsify -- the same argument the web makes for not threading a
    // cluster id through a URL.
    val showing = open?.let { key ->
        state.days
            .firstOrNull { it.date.toString() == key.date }
            ?.clusters
            ?.firstOrNull { cluster -> cluster.members.any { it.eventKey == key.eventKey } }
    }

    fun openEditor(request: EditorRequest) {
        openings += 1
        editing = EditorSession(openings, request)
    }

    when (screen) {
        CalendarPage.EDITOR ->
            EventEditorScreen(
                session = editing ?: EditorSession(openings, EditorRequest.New),
                calendars = state.calendars,
                onClose = { editing = null },
            )
        CalendarPage.DETAIL ->
            if (showing == null) {
                // The row has gone -- deleted here, or moved out of the window by
                // a refresh -- so the detail closes itself. In an effect rather
                // than in the branch: writing state while composing is what makes
                // a screen recompose forever, and the board underneath already
                // says what is there now.
                //
                // Keyed on the *state* as well, so the frame in which the list is
                // still empty on a cold open does not close a detail that is
                // about to resolve.
                LaunchedEffect(open, state.days) { if (state.days.isNotEmpty()) open = null }
            } else {
                EventDetailScreen(
                    cluster = showing,
                    calendars = state.calendars,
                    onBack = { open = null },
                    onEdit = {
                        open = null
                        // The cluster's representative, which is the member the
                        // detail was already describing. Opening a merged
                        // meeting edits one of its copies and says which --
                        // exactly what the web does, where the chip's URL names
                        // the primary member's event and only that one.
                        openEditor(EditorRequest.Edit(showing.primary.eventKey))
                    },
                )
            }
        CalendarPage.BOARD ->
            CalendarBoard(
                state = state,
                onBack = onBack,
                onRefresh = viewModel::refresh,
                onWindowShown = viewModel::refreshIfNeeded,
                onChoose = viewModel::choose,
                onPage = viewModel::page,
                onToday = viewModel::goToToday,
                onOpen = { cluster ->
                    open = OccurrenceKey(cluster.primary.eventKey, cluster.primary.date)
                },
                onOpenDay = viewModel::openDay,
                onNew = { openEditor(EditorRequest.New) },
                onCreateAt = { at -> openEditor(EditorRequest.NewAt(at.toString())) },
            )
    }
}

/** Which of the calendar's own screens is on top. See [CalendarScreen]. */
private enum class CalendarPage {
    BOARD,
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

private val EditorSessionSaver: Saver<EditorSession?, Any> =
    listSaver<EditorSession?, String>(
        save = {
            when (val request = it?.request) {
                null -> emptyList()
                EditorRequest.New -> listOf(it.serial.toString(), "new")
                is EditorRequest.NewAt -> listOf(it.serial.toString(), "newAt", request.startWire)
                is EditorRequest.Edit -> listOf(it.serial.toString(), "edit", request.eventKey)
            }
        },
        restore = {
            val serial = it.getOrNull(0)?.toIntOrNull()

            when {
                serial == null -> null
                it.getOrNull(1) == "new" -> EditorSession(serial, EditorRequest.New)
                it.getOrNull(1) == "newAt" -> EditorSession(serial, EditorRequest.NewAt(it[2]))
                it.getOrNull(1) == "edit" -> EditorSession(serial, EditorRequest.Edit(it[2]))
                else -> null
            }
        },
    )
