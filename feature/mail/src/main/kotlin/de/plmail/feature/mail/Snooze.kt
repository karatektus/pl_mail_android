package de.plmail.feature.mail

import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.res.stringResource
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * When a conversation should come back.
 *
 * Presets, plus an exact time for the case none of them fits. The presets are computed against the
 * device's own zone and the *user's* idea of a day rather than against UTC — "tomorrow morning"
 * means eight o'clock where they are, and a snooze that reappears at four in the afternoon because
 * the arithmetic was done in UTC is worse than no snooze at all. The conversion to the wire's UTC
 * happens once, at the edge, in `MailActions`.
 */
enum class SnoozePreset(val label: Int) {
    LATER_TODAY(R.string.snooze_later_today),
    TOMORROW(R.string.snooze_tomorrow),
    THIS_WEEKEND(R.string.snooze_this_weekend),
    NEXT_WEEK(R.string.snooze_next_week);

    /**
     * The instant this preset means, or null when it has already passed today.
     *
     * Null rather than "in a moment": offering "later today" at eleven at night and having the mail
     * reappear immediately is a control that appears to do nothing. The menu drops those entries
     * instead.
     */
    fun resolve(now: Instant, zone: ZoneId): Instant? {
        // atZone().toLocalDate() rather than LocalDate.ofInstant, which is API 34
        // and this app runs from 31. Same answer, and lint is the only thing that
        // would have caught it -- the emulator here is API 36.
        val today = now.atZone(zone).toLocalDate()

        val at =
            when (this) {
                LATER_TODAY -> today.atTime(LATER_TODAY_HOUR)
                TOMORROW -> today.plusDays(1).atTime(MORNING)
                THIS_WEEKEND ->
                    today.with(TemporalAdjusters.next(DayOfWeek.SATURDAY)).atTime(MORNING)
                NEXT_WEEK -> today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atTime(MORNING)
            }

        val instant = at.atZone(zone).toInstant()

        return instant.takeIf { it.isAfter(now) }
    }

    private companion object {
        val MORNING: LocalTime = LocalTime.of(8, 0)
        val LATER_TODAY_HOUR: LocalTime = LocalTime.of(18, 0)
    }
}

/**
 * The snooze menu, hung off whatever opened it.
 *
 * A menu rather than a sheet: four presets and one escape hatch is a list short enough that a sheet
 * would be more chrome than content.
 */
@Composable
fun SnoozeMenu(
    isOpen: Boolean,
    onDismiss: () -> Unit,
    onChosen: (Instant) -> Unit,
    onPickExact: () -> Unit,
) {
    val now = remember { Instant.now() }
    val zone = remember { ZoneId.systemDefault() }

    DropdownMenu(expanded = isOpen, onDismissRequest = onDismiss) {
        SnoozePreset.entries.forEach { preset ->
            val at = preset.resolve(now, zone) ?: return@forEach

            DropdownMenuItem(
                text = { Text(stringResource(preset.label)) },
                onClick = {
                    onDismiss()
                    onChosen(at)
                },
            )
        }

        DropdownMenuItem(
            text = { Text(stringResource(R.string.snooze_pick)) },
            onClick = {
                onDismiss()
                onPickExact()
            },
        )
    }
}

/**
 * Date, then time, for the case the presets do not cover.
 *
 * Two dialogs in sequence rather than one combined control, because Material ships exactly these
 * two and a hand-rolled combination of them is a lot of surface for a rare path.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnoozePicker(onDismiss: () -> Unit, onChosen: (Instant) -> Unit) {
    val zone = remember { ZoneId.systemDefault() }
    val dateState = rememberDatePickerState()
    val timeState = rememberTimePickerState(initialHour = 8, initialMinute = 0, is24Hour = true)

    var date by remember { mutableStateOf<LocalDate?>(null) }
    val chosenDate = date

    if (chosenDate == null) {
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(
                    enabled = dateState.selectedDateMillis != null,
                    onClick = {
                        // The picker answers in UTC millis at midnight, which is
                        // a *date* rather than an instant. Read as a local date
                        // and given a time below; treating it as an instant would
                        // shift the day by one either side of the meridian.
                        date =
                            dateState.selectedDateMillis?.let {
                                Instant.ofEpochMilli(it).atZone(ZoneId.of("UTC")).toLocalDate()
                            }
                    },
                ) {
                    Text(stringResource(R.string.label_save))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.label_cancel)) }
            },
        ) {
            DatePicker(state = dateState)
        }

        return
    }

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val at =
                        chosenDate.atTime(timeState.hour, timeState.minute).atZone(zone).toInstant()

                    onChosen(at)
                }
            ) {
                Text(stringResource(R.string.label_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.label_cancel)) }
        },
    ) {
        TimePicker(state = timeState)
    }
}
