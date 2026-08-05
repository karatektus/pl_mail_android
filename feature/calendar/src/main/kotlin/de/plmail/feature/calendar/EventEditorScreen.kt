package de.plmail.feature.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.plmail.core.database.CalendarEntity
import de.plmail.core.designsystem.PaneTone
import de.plmail.core.designsystem.PlMailPane
import de.plmail.core.designsystem.PlMailTheme
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * The event editor: one form for creating and for changing, matching the web's field list.
 *
 * **There is no reminders field, deliberately.** The web's editor has one and this cannot: `alerts`
 * is not writable over JMAP — `CalendarEvent/set` answers `invalidProperties` for it — so a control
 * here would be one whose most obvious use fails after the user has filled the whole form in. The
 * server refusing it is a decision rather than a gap, and `docs/SERVER_REQUESTS.md` is where an ask
 * would go if this ever needs to change.
 *
 * **A full recurrence editor is also out of scope**, and the shape of what is here is what protects
 * elaborate rules rather than a shortcut around writing one. The dropdown appears on a *create*
 * only. Editing an event that already repeats shows a read-only line instead, because an update
 * never sends `recurrenceRules`: the cache stores whether a series recurs and not the rule it
 * recurs by, so a dropdown on an edit could only send a rule derived from nothing — flattening
 * "every second Tuesday" to "every week", or clearing it altogether, because null on that property
 * means "stop recurring".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EventEditorScreen(
    session: EditorSession,
    calendars: List<CalendarEntity>,
    onClose: () -> Unit,
    viewModel: EventEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val untitled = stringResource(R.string.calendar_untitled)
    val request = session.request

    // Only calendars that accept new events. `myRights` is the one thing that
    // decides -- not isDefault, not the role -- and a picker that guessed would
    // offer a create the server then refuses with `forbidden`, after the whole
    // event has been typed.
    val writable = remember(calendars) { calendars.filter { it.mayAddItems } }

    LaunchedEffect(session, writable) {
        viewModel.open(
            session = session,
            defaultCalendarKey =
                writable.firstOrNull { it.isDefault }?.uid ?: writable.firstOrNull()?.uid,
        )
    }

    LaunchedEffect(state.outcome) {
        // Saved and deleted both mean the form is finished with. A failure keeps
        // it open with the reason on it, which is why this waits for the outcome
        // rather than closing on the tap.
        if (state.outcome == WriteOutcome.Saved || state.outcome == WriteOutcome.Deleted) onClose()
    }

    val form = state.form
    var confirmingDelete by remember(session) { mutableStateOf(false) }

    if (confirmingDelete) {
        DeleteConfirmation(
            isRecurring = state.isRecurring,
            onDismiss = { confirmingDelete = false },
            onConfirm = {
                confirmingDelete = false
                (request as? EditorRequest.Edit)?.let { viewModel.delete(it.eventKey) }
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
                title = {
                    Text(
                        stringResource(
                            if (request == EditorRequest.New) R.string.calendar_new_title
                            else R.string.calendar_edit_title
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.calendar_back_agenda),
                        )
                    }
                },
            )
        },
    ) { insets ->
        if (form == null) {
            // The event is still being read. Nothing is drawn rather than an
            // empty form, because an empty form invites typing into fields that
            // are about to be overwritten.
            Box(
                modifier = Modifier.fillMaxSize().padding(insets),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = PlMailTheme.colors.accent, strokeWidth = 2.dp)
            }

            return@Scaffold
        }

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
            OutlinedTextField(
                value = form.title,
                onValueChange = { value -> viewModel.edit { it.copy(title = value) } },
                singleLine = true,
                label = { Text(stringResource(R.string.calendar_field_title)) },
                // Said before the save rather than after it. JMAP refuses an
                // empty title outright and the web stores the word "Untitled"
                // instead of refusing; this is that promise, in advance.
                supportingText = { Text(stringResource(R.string.calendar_title_hint, untitled)) },
                modifier = Modifier.fillMaxWidth(),
            )

            AllDayToggle(
                isAllDay = form.isAllDay,
                onChange = { value -> viewModel.edit { it.copy(isAllDay = value) } },
            )

            WhenFields(
                form = form,
                onChange = { change -> viewModel.edit(change) },
            )

            if (form.endsBeforeStart) {
                Note(
                    text = stringResource(R.string.calendar_end_before_start),
                    tone = PaneTone.DANGER,
                )
            }

            CalendarPicker(
                calendars = writable,
                selected = form.calendarKey,
                onSelect = { key -> viewModel.edit { it.copy(calendarKey = key) } },
            )

            RepeatField(
                isEditingRecurring = request != EditorRequest.New && state.isRecurring,
                repeat = form.repeat,
                isCreating = request == EditorRequest.New,
                onSelect = { value -> viewModel.edit { it.copy(repeat = value) } },
            )

            OutlinedTextField(
                value = form.location,
                onValueChange = { value -> viewModel.edit { it.copy(location = value) } },
                singleLine = true,
                label = { Text(stringResource(R.string.calendar_field_location)) },
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = form.description,
                onValueChange = { value -> viewModel.edit { it.copy(description = value) } },
                label = { Text(stringResource(R.string.calendar_field_description)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            WriteNote(state.outcome)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { viewModel.save(untitled) },
                    enabled = !state.isSaving && !form.endsBeforeStart && form.calendarKey != null,
                ) {
                    Text(stringResource(R.string.calendar_save))
                }

                // Beside Save and in the same form, as the web has it: an event
                // is deleted from the place where it is being looked at rather
                // than from a menu somewhere else.
                if (request != EditorRequest.New) {
                    TextButton(onClick = { confirmingDelete = true }, enabled = !state.isSaving) {
                        Text(
                            text = stringResource(R.string.calendar_delete),
                            color = PlMailTheme.colors.danger,
                        )
                    }
                }

                if (state.isSaving) {
                    CircularProgressIndicator(
                        color = PlMailTheme.colors.accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            if (writable.isEmpty()) {
                Note(text = stringResource(R.string.calendar_none_writable), tone = PaneTone.INFO)
            }
        }
    }
}

@Composable
private fun AllDayToggle(isAllDay: Boolean, onChange: (Boolean) -> Unit) {
    val label = stringResource(R.string.calendar_all_day)

    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = PlMailTheme.spacing.touchTarget),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = PlMailTheme.colors.ink,
            // The switch carries the whole control's semantics; leaving the
            // label focusable too gives TalkBack a swipe stop that does nothing
            // and reads the setting twice.
            modifier = Modifier.weight(1f).clearAndSetSemantics {},
        )

        Switch(
            checked = isAllDay,
            onCheckedChange = onChange,
            colors =
                SwitchDefaults.colors(
                    checkedThumbColor = PlMailTheme.colors.onAccent,
                    checkedTrackColor = PlMailTheme.colors.accent,
                    uncheckedTrackColor = PlMailTheme.colors.sunken,
                    uncheckedBorderColor = PlMailTheme.colors.line,
                ),
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

/**
 * Starts and Ends.
 *
 * The wire takes a start and an ISO duration rather than two instants, and the arithmetic is
 * `EventFormState`'s. What this draws is the pair a person thinks in — and only the halves that
 * mean anything: an all-day event has dates and no times, so the time buttons are not drawn rather
 * than drawn disabled. A disabled 09:00 beside "All day" invites tapping it to find out why.
 */
@Composable
private fun WhenFields(
    form: EventFormState,
    onChange: ((EventFormState) -> EventFormState) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.medium)) {
        WhenRow(
            label = stringResource(R.string.calendar_starts),
            date = form.startDate,
            time = form.startTime.takeUnless { form.isAllDay },
            onDate = { picked ->
                onChange { state ->
                    // The end follows the start when the start moves past it,
                    // keeping the length. Anything else means every change of
                    // day is two corrections, and the second one is the error
                    // message.
                    val shift = ChronoUnit.DAYS.between(state.startDate, picked)

                    state.copy(startDate = picked, endDate = state.endDate.plusDays(shift))
                }
            },
            onTime = { picked -> onChange { it.copy(startTime = picked) } },
        )

        WhenRow(
            label = stringResource(R.string.calendar_ends),
            date = form.endDate,
            time = form.endTime.takeUnless { form.isAllDay },
            onDate = { picked -> onChange { it.copy(endDate = picked) } },
            onTime = { picked -> onChange { it.copy(endTime = picked) } },
        )
    }
}

@Composable
private fun WhenRow(
    label: String,
    date: LocalDate,
    time: LocalTime?,
    onDate: (LocalDate) -> Unit,
    onTime: (LocalTime) -> Unit,
) {
    var pickingDate by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf(false) }

    if (pickingDate) {
        DateDialog(date = date, onDismiss = { pickingDate = false }, onPicked = onDate)
    }

    if (pickingTime && time != null) {
        TimeDialog(time = time, onDismiss = { pickingTime = false }, onPicked = onTime)
    }

    Column(verticalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.tiny)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = PlMailTheme.colors.inkMuted,
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PickerButton(
                text = date.format(DAY_HEADER),
                description = stringResource(R.string.calendar_pick_date, label),
                onClick = { pickingDate = true },
                modifier = Modifier.weight(1f),
            )

            time?.let {
                PickerButton(
                    text = it.format(CLOCK),
                    description = stringResource(R.string.calendar_pick_time, label),
                    onClick = { pickingTime = true },
                )
            }
        }
    }
}

/**
 * A field that opens a picker.
 *
 * A button rather than a text field, because there is nothing to type: a date typed into a text
 * field has to be parsed, and a parser that disagrees with the user's locale rejects a date that
 * looks perfectly correct to whoever wrote it.
 */
@Composable
private fun PickerButton(
    text: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = PlMailTheme.values

    Box(
        modifier =
            modifier
                .heightIn(min = theme.spacing.touchTarget)
                .background(
                    color = theme.colors.fieldSurface,
                    shape = RoundedCornerShape(theme.radii.control),
                )
                .clickable(onClick = onClick, onClickLabel = description)
                .padding(horizontal = theme.spacing.medium, vertical = theme.spacing.small),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text = text, style = MaterialTheme.typography.bodyLarge, color = theme.colors.ink)
    }
}

/**
 * Material's date picker, which speaks epoch milliseconds.
 *
 * Converted through **UTC** in both directions, which looks wrong and is the only correct answer:
 * the picker's value is a calendar date with no time and no zone, and resolving it in the device's
 * zone shifts it by a day for anybody east or west of Greenwich at the wrong hour.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateDialog(date: LocalDate, onDismiss: () -> Unit, onPicked: (LocalDate) -> Unit) {
    val state =
        rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    state.selectedDateMillis?.let {
                        onPicked(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    onDismiss()
                }
            ) {
                Text(stringResource(R.string.calendar_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.calendar_cancel)) }
        },
    ) {
        DatePicker(state = state)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(time: LocalTime, onDismiss: () -> Unit, onPicked: (LocalTime) -> Unit) {
    val state = rememberTimePickerState(initialHour = time.hour, initialMinute = time.minute)

    AlertDialog(
        onDismissRequest = onDismiss,
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(
                onClick = {
                    onPicked(LocalTime.of(state.hour, state.minute))
                    onDismiss()
                }
            ) {
                Text(stringResource(R.string.calendar_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.calendar_cancel)) }
        },
    )
}

/**
 * Which calendar the event lands on.
 *
 * Radio rows rather than the web's checkbox list, and that is a scope cut recorded where it
 * happens: plMail can keep one meeting on several calendars at once, with rules about what
 * unticking one means that take two paragraphs of the product documentation to state. One calendar
 * per event is the honest subset — it never produces a state this editor cannot describe.
 */
@Composable
private fun CalendarPicker(
    calendars: List<CalendarEntity>,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    val theme = PlMailTheme.values

    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.tiny)) {
        Text(
            text = stringResource(R.string.calendar_field_calendar),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = theme.colors.inkMuted,
        )

        calendars.forEach { calendar ->
            val color = calendarColor(calendar.color) ?: theme.colors.inkFaint

            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .heightIn(min = theme.spacing.touchTarget)
                        .selectable(
                            selected = calendar.uid == selected,
                            onClick = { onSelect(calendar.uid) },
                            role = Role.RadioButton,
                        )
                        .padding(vertical = theme.spacing.tiny),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(theme.spacing.small),
            ) {
                RadioButton(selected = calendar.uid == selected, onClick = null)

                Box(modifier = Modifier.size(DOT).background(color = color, shape = CircleShape))

                Text(
                    text = calendar.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = theme.colors.ink,
                )
            }
        }
    }
}

/**
 * Repeat, or the fact that it already does.
 *
 * The dropdown is a create-only control. See this file's own note for why an edit shows a sentence
 * instead — it is what keeps an imported "every second Tuesday" from being flattened by somebody
 * fixing a typo.
 */
@Composable
private fun RepeatField(
    isEditingRecurring: Boolean,
    repeat: Repeat,
    isCreating: Boolean,
    onSelect: (Repeat) -> Unit,
) {
    var isOpen by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.tiny)) {
        Text(
            text = stringResource(R.string.calendar_repeat),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = PlMailTheme.colors.inkMuted,
        )

        when {
            isEditingRecurring ->
                Text(
                    text = stringResource(R.string.calendar_repeat_kept),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PlMailTheme.colors.inkSoft,
                )
            !isCreating ->
                Text(
                    text = stringResource(R.string.calendar_repeat_never),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PlMailTheme.colors.inkSoft,
                )
            else -> {
                val chosen = stringResource(repeat.label())

                Row(
                    modifier =
                        Modifier.fillMaxWidth()
                            .heightIn(min = PlMailTheme.spacing.touchTarget)
                            .clickable(
                                onClick = { isOpen = true },
                                onClickLabel = stringResource(R.string.calendar_repeat),
                            ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = chosen,
                        style = MaterialTheme.typography.bodyLarge,
                        color = PlMailTheme.colors.ink,
                        modifier = Modifier.weight(1f),
                    )

                    Icon(
                        imageVector = Icons.Filled.ArrowDropDown,
                        contentDescription = null,
                        tint = PlMailTheme.colors.inkMuted,
                    )
                }

                DropdownMenu(expanded = isOpen, onDismissRequest = { isOpen = false }) {
                    Repeat.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(stringResource(option.label())) },
                            onClick = {
                                isOpen = false
                                onSelect(option)
                            },
                        )
                    }
                }
            }
        }
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

private fun Repeat.label(): Int =
    when (this) {
        Repeat.NEVER -> R.string.calendar_repeat_never
        Repeat.DAILY -> R.string.calendar_repeat_daily
        Repeat.WEEKLY -> R.string.calendar_repeat_weekly
        Repeat.MONTHLY -> R.string.calendar_repeat_monthly
        Repeat.YEARLY -> R.string.calendar_repeat_yearly
    }

private val DOT = 10.dp
