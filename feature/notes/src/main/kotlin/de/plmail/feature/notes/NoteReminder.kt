package de.plmail.feature.notes

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * A note's reminder: presets Keep users know (later today, tomorrow morning, next week) or a date
 * and a time. The server sends it — an email and a notification on every device — so nothing is
 * scheduled on this phone. Times are picked in the phone's zone and stored as UTC.
 */
internal object NoteReminderTimes {
    fun later(now: LocalDateTime = LocalDateTime.now()): LocalDateTime {
        val evening = now.toLocalDate().atTime(18, 0)
        return if (evening.isAfter(now)) evening else now.plusHours(3).truncatedTo(ChronoUnit.HOURS)
    }

    fun tomorrow(now: LocalDateTime = LocalDateTime.now()): LocalDateTime =
        now.toLocalDate().plusDays(1).atTime(8, 0)

    fun nextWeek(now: LocalDateTime = LocalDateTime.now()): LocalDateTime =
        now.toLocalDate().with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atTime(8, 0)

    /** A local time as the UTCDate the server wants. */
    fun utc(at: LocalDateTime, zone: ZoneId = ZoneId.systemDefault()): String =
        at.atZone(zone).toInstant().truncatedTo(ChronoUnit.SECONDS).toString()

    /** How a stored reminder reads on the chip. */
    fun label(utc: String, zone: ZoneId = ZoneId.systemDefault()): String = runCatching {
        Instant.parse(utc)
            .atZone(zone)
            .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
    }
        .getOrDefault(utc)

    fun isPast(utc: String): Boolean = runCatching {
        Instant.parse(utc).isBefore(Instant.now())
    }
        .getOrDefault(false)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NoteReminderDialog(
    hasReminder: Boolean,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var step by remember { mutableStateOf(0) } // 0 presets, 1 date, 2 time
    var date by remember { mutableStateOf<LocalDate?>(null) }

    when (step) {
        0 ->
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.notes_reminder)) },
                text = {
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(R.string.notes_reminder_hint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(
                            onClick = { onPick(NoteReminderTimes.utc(NoteReminderTimes.later())) }
                        ) {
                            Text(stringResource(R.string.notes_reminder_later))
                        }
                        TextButton(
                            onClick = {
                                onPick(NoteReminderTimes.utc(NoteReminderTimes.tomorrow()))
                            }
                        ) {
                            Text(stringResource(R.string.notes_reminder_tomorrow))
                        }
                        TextButton(
                            onClick = {
                                onPick(NoteReminderTimes.utc(NoteReminderTimes.nextWeek()))
                            }
                        ) {
                            Text(stringResource(R.string.notes_reminder_next_week))
                        }
                        TextButton(onClick = { step = 1 }) {
                            Text(stringResource(R.string.notes_reminder_pick))
                        }
                        if (hasReminder) {
                            TextButton(onClick = { onPick(null) }) {
                                Text(stringResource(R.string.notes_reminder_remove))
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.notes_reminder_cancel))
                    }
                },
            )
        1 -> {
            val state =
                rememberDatePickerState(initialSelectedDateMillis = System.currentTimeMillis())
            DatePickerDialog(
                onDismissRequest = onDismiss,
                confirmButton = {
                    TextButton(
                        onClick = {
                            // The picker answers UTC midnight of the chosen day.
                            date =
                                state.selectedDateMillis?.let {
                                    Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                                }
                            if (date != null) step = 2
                        }
                    ) {
                        Text(stringResource(R.string.notes_reminder_set))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.notes_reminder_cancel))
                    }
                },
            ) {
                DatePicker(state = state)
            }
        }
        else -> {
            val state = rememberTimePickerState(initialHour = 8, initialMinute = 0)
            AlertDialog(
                onDismissRequest = onDismiss,
                text = { TimePicker(state = state) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val day = date ?: LocalDate.now()
                            onPick(
                                NoteReminderTimes.utc(
                                    day.atTime(LocalTime.of(state.hour, state.minute))
                                )
                            )
                        }
                    ) {
                        Text(stringResource(R.string.notes_reminder_set))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.notes_reminder_cancel))
                    }
                },
            )
        }
    }
}
