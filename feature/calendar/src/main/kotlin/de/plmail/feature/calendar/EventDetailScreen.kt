package de.plmail.feature.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.plmail.core.database.AgendaRow
import de.plmail.core.database.CalendarEntity
import de.plmail.core.designsystem.PaneTone
import de.plmail.core.designsystem.PlMailPane
import de.plmail.core.designsystem.PlMailTheme
import java.time.Duration
import java.time.ZoneId

/**
 * One occurrence, in full.
 *
 * A screen rather than a sheet, because it carries the two controls that change the event and a
 * sheet's dismissal gesture is the same swipe that scrolls a description.
 *
 * **Edit and Delete are drawn disabled rather than hidden** on a calendar that does not accept
 * changes — a mirrored feed, or somebody else's shared calendar. Hiding them would say the event
 * cannot be changed at all, when the truth is that it cannot be changed *here*; the reason is in
 * each button's description, where a screen reader gets it and a long press shows it.
 *
 * The rights come from `myRights` on the calendar, which is the only thing that decides this — not
 * `isDefault`, not the role. What the *server* does about a write it refuses is a separate answer
 * and is surfaced as it sends it; this is only what the screen offers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EventDetailScreen(
    row: AgendaRow,
    calendars: List<CalendarEntity>,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    viewModel: EventEditorViewModel = hiltViewModel(),
) {
    val calendar = calendars.firstOrNull { it.uid == row.calendarKey }
    val mayChange = calendar?.mayUpdateAll == true
    val mayDelete = calendar?.mayRemoveItems == true

    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmingDelete by remember(row.eventKey) { mutableStateOf(false) }

    // Whether *this* screen asked for the delete. The editor's ViewModel is
    // scoped to the activity, so an outcome left over from a previous event
    // would otherwise close this one the moment it opened.
    var isDeleting by remember(row.eventKey) { mutableStateOf(false) }

    // Anything the last write left behind belongs to whatever produced it, not
    // to this event.
    LaunchedEffect(row.eventKey) { viewModel.acknowledge() }

    // A delete that reached the server closes the screen; one the server refused
    // leaves it open with the reason on it, which is the whole reason this waits
    // for the outcome rather than closing on the tap.
    LaunchedEffect(state.outcome, isDeleting) {
        if (isDeleting && state.outcome == WriteOutcome.Deleted) onBack()
    }

    if (confirmingDelete) {
        DeleteConfirmation(
            isRecurring = row.isRecurring,
            onDismiss = { confirmingDelete = false },
            onConfirm = {
                confirmingDelete = false
                isDeleting = true
                viewModel.delete(row.eventKey)
            },
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = PlMailTheme.colors.surface,
        topBar = {
            TopAppBar(
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = PlMailTheme.colors.surface,
                        scrolledContainerColor = PlMailTheme.colors.surface,
                        titleContentColor = PlMailTheme.colors.ink,
                        navigationIconContentColor = PlMailTheme.colors.inkSoft,
                        actionIconContentColor = PlMailTheme.colors.inkSoft,
                    ),
                title = { Text(stringResource(R.string.calendar_event)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.calendar_back_agenda),
                        )
                    }
                },
                actions = {
                    val readOnly = stringResource(R.string.calendar_readonly)

                    IconButton(onClick = onEdit, enabled = mayChange) {
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            // The reason travels with the control. A greyed
                            // button with no explanation is a bug as far as
                            // anybody looking at it can tell.
                            contentDescription =
                                if (mayChange) stringResource(R.string.calendar_edit)
                                else stringResource(R.string.calendar_edit_forbidden, readOnly),
                            tint =
                                if (mayChange) PlMailTheme.colors.inkSoft
                                else PlMailTheme.colors.inkFaint,
                        )
                    }

                    IconButton(onClick = { confirmingDelete = true }, enabled = mayDelete) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription =
                                if (mayDelete) stringResource(R.string.calendar_delete)
                                else stringResource(R.string.calendar_delete_forbidden, readOnly),
                            tint =
                                if (mayDelete) PlMailTheme.colors.inkSoft
                                else PlMailTheme.colors.inkFaint,
                        )
                    }
                },
            )
        },
    ) { insets ->
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .padding(insets)
                    .verticalScroll(rememberScrollState())
                    .padding(
                        horizontal = PlMailTheme.spacing.gutter,
                        vertical = PlMailTheme.spacing.medium,
                    ),
            verticalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.large),
        ) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.headlineSmall,
                color = PlMailTheme.colors.ink,
            )

            Times(row)

            row.location
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    Field(label = stringResource(R.string.calendar_field_location), value = it)
                }

            CalendarLine(row)

            if (row.isRecurring) {
                Field(
                    label = stringResource(R.string.calendar_repeat),
                    value = stringResource(R.string.calendar_repeats),
                )
            }

            // Only when it is *not* confirmed. Saying "Confirmed" on every event
            // is a line that is always there and therefore never read, and it
            // would take the emphasis off the two states that mean something.
            row.status
                ?.takeIf { it != STATUS_CONFIRMED }
                ?.let {
                    Field(
                        label = stringResource(R.string.calendar_field_status),
                        value = statusWord(it),
                    )
                }

            row.description
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    Field(
                        label = stringResource(R.string.calendar_field_description),
                        value = it,
                    )
                }

            WriteNote(state.outcome)
        }
    }
}

/**
 * When the event is, in the three shapes an occurrence actually takes.
 *
 * An **all-day** event has no time to render and says so. A **timed** one gets its start, its end
 * and how long it lasts, because "09:00 – 09:15" and "15 min" answer different questions and the
 * second is the one somebody scanning a day is asking.
 *
 * A **floating** event — no zone at all — is drawn exactly as it is stored, in wall-clock time, and
 * that is the point of storing it that way: it means the same clock time wherever the reader is, so
 * resolving it into the device's zone is precisely the bug that makes a birthday move when somebody
 * travels. An event whose zone is not the device's says which zone it is in rather than being
 * converted, because a conversion the phone did and the web did not is two surfaces of one product
 * disagreeing about the same meeting.
 */
@Composable
private fun Times(row: AgendaRow) {
    val start = startTimeOf(row)
    val end = endTimeOf(row)

    val hours = stringResource(R.string.calendar_hours)
    val minutes = stringResource(R.string.calendar_minutes)

    Column(verticalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.tiny)) {
        Text(
            text = row.date.toLocalDateOrNull()?.format(DAY_HEADER) ?: row.date,
            style = MaterialTheme.typography.bodyLarge,
            color = PlMailTheme.colors.ink,
        )

        Text(
            text =
                when {
                    row.isAllDay || start == null -> stringResource(R.string.calendar_all_day)
                    end == null -> start.format(CLOCK)
                    else ->
                        stringResource(
                            R.string.calendar_time_range,
                            start.format(CLOCK),
                            end.format(CLOCK),
                        )
                },
            style = MaterialTheme.typography.bodyMedium,
            color = PlMailTheme.colors.inkSoft,
        )

        if (!row.isAllDay && start != null && end != null) {
            durationWords(
                    minutes = Duration.between(start, end).toMinutes(),
                    hourWord = hours,
                    minuteWord = minutes,
                )
                ?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = PlMailTheme.colors.inkMuted,
                    )
                }
        }

        if (!row.isAllDay) {
            val zone = row.zoneId

            when {
                zone == null -> Caption(stringResource(R.string.calendar_floating))
                zone != ZoneId.systemDefault().id ->
                    Caption(stringResource(R.string.calendar_zone, zone))
            }
        }
    }
}

/** Which calendar this is on, with its own colour beside the name. */
@Composable
private fun CalendarLine(row: AgendaRow) {
    val theme = PlMailTheme.values
    val color = calendarColor(row.calendarColor) ?: theme.colors.inkFaint

    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.tiny)) {
        Label(stringResource(R.string.calendar_field_calendar))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(theme.spacing.small),
        ) {
            Box(modifier = Modifier.size(DOT).background(color = color, shape = CircleShape))

            Text(
                text = row.calendarName.orEmpty(),
                style = MaterialTheme.typography.bodyLarge,
                color = theme.colors.ink,
            )
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.tiny)) {
        Label(label)

        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = PlMailTheme.colors.ink,
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Medium,
        color = PlMailTheme.colors.inkMuted,
    )
}

@Composable
private fun Caption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = PlMailTheme.colors.inkMuted,
    )
}

/**
 * What a write came to, said in the words its own failure deserves.
 *
 * Shared by the detail and the editor, because both make the same three requests and the two
 * failures have to keep reading differently wherever they surface: "the server said no, and here is
 * what it said" against "nothing answered, and the machine is called this".
 *
 * A success draws nothing — the screen that asked for it has already closed or moved on.
 */
@Composable
internal fun WriteNote(outcome: WriteOutcome?) {
    when (outcome) {
        is WriteOutcome.Refused ->
            Note(
                text =
                    if (outcome.isForbidden) stringResource(R.string.calendar_readonly)
                    else stringResource(R.string.calendar_write_refused, outcome.reason),
                tone = PaneTone.DANGER,
            )
        is WriteOutcome.Unreachable ->
            Note(
                text =
                    outcome.host?.let { stringResource(R.string.calendar_write_unreachable, it) }
                        ?: stringResource(R.string.calendar_write_unreachable_unnamed),
                tone = PaneTone.WARNING,
            )
        WriteOutcome.NoCalendar ->
            Note(text = stringResource(R.string.calendar_gone), tone = PaneTone.DANGER)
        WriteOutcome.Saved,
        WriteOutcome.Deleted,
        null -> Unit
    }
}

@Composable
private fun Note(text: String, tone: PaneTone) {
    PlMailPane(modifier = Modifier.fillMaxWidth(), tone = tone) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = PlMailTheme.colors.inkSoft,
            modifier = Modifier.padding(PlMailTheme.spacing.small),
        )
    }
}

/**
 * The question a real delete has to ask.
 *
 * `CalendarEvent/set` `destroy` is a genuine delete, unlike `Email/set`'s, which moves a message to
 * Trash — there is no calendar bin to recover from, so this cannot borrow mail's undo snackbar and
 * has to ask first. A repeating event says so in the same breath, because "delete" on the
 * occurrence somebody is looking at reads as deleting that Tuesday.
 */
@Composable
internal fun DeleteConfirmation(
    isRecurring: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.calendar_delete_title)) },
        text = {
            Text(
                stringResource(
                    if (isRecurring) R.string.calendar_delete_body_recurring
                    else R.string.calendar_delete_body
                )
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.calendar_delete),
                    color = PlMailTheme.colors.danger,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.calendar_cancel)) }
        },
    )
}

/** The three states the wire uses, in the app's own words. */
@Composable
private fun statusWord(status: String): String =
    when (status) {
        STATUS_TENTATIVE -> stringResource(R.string.calendar_status_tentative)
        STATUS_CANCELLED -> stringResource(R.string.calendar_status_cancelled)
        // Unknown to this build rather than absent. Shown raw, because a status
        // added on a newer server is still something to say about the event and
        // substituting "confirmed" would be inventing an answer.
        else -> status
    }

private const val STATUS_CONFIRMED = "confirmed"
private const val STATUS_TENTATIVE = "tentative"
private const val STATUS_CANCELLED = "cancelled"

private val DOT = 10.dp
