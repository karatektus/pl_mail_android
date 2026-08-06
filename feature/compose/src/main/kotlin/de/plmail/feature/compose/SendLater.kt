package de.plmail.feature.compose

import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
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
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.TemporalAdjusters

/**
 * When a message should leave.
 *
 * The same shape as the snooze presets in `:feature:mail`, and computed the same way — against the
 * device's own zone and the *user's* idea of a day rather than against UTC. "Tomorrow morning"
 * means eight o'clock where they are; a send that goes out at four in the afternoon because the
 * arithmetic was done in UTC is worse than no scheduling at all. The conversion to the wire's UTC
 * happens once, in `SendQueue`.
 */
enum class SendLaterPreset(val label: Int) {
    LATER_TODAY(R.string.send_later_today),
    TOMORROW(R.string.send_later_tomorrow),
    MONDAY(R.string.send_later_monday);

    /**
     * The instant this preset means, or null when it is not offerable.
     *
     * Null for a moment that has already passed today — offering "later today" at eleven at night
     * and having the mail leave immediately is a control that appears to do nothing — and null for
     * anything beyond [latest], which is the server's `maxDelayedSend` and not a rule of this
     * client's. The menu drops those entries rather than showing something that would be refused.
     */
    fun resolve(now: Instant, zone: ZoneId, latest: Instant): Instant? {
        // atZone().toLocalDate() rather than LocalDate.ofInstant, which is API 34
        // and this app runs from 31.
        val today = now.atZone(zone).toLocalDate()

        val at =
            when (this) {
                LATER_TODAY -> today.atTime(EVENING)
                TOMORROW -> today.plusDays(1).atTime(MORNING)
                MONDAY -> today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atTime(MORNING)
            }

        val instant = at.atZone(zone).toInstant()

        return instant.takeIf { it.isAfter(now) && !it.isAfter(latest) }
    }

    private companion object {
        val MORNING: LocalTime = LocalTime.of(8, 0)
        val EVENING: LocalTime = LocalTime.of(18, 0)
    }
}

/**
 * The send-later menu, hung off whatever opened it.
 *
 * A menu rather than a sheet: three presets and one escape hatch is a list short enough that a
 * sheet would be more chrome than content, and it matches the snooze menu the same user already
 * knows.
 */
@Composable
fun SendLaterMenu(
    isOpen: Boolean,
    latest: Instant,
    onDismiss: () -> Unit,
    onChosen: (Instant) -> Unit,
    onPickExact: () -> Unit,
) {
    val now = remember(isOpen) { Instant.now() }
    val zone = remember { ZoneId.systemDefault() }

    DropdownMenu(expanded = isOpen, onDismissRequest = onDismiss) {
        SendLaterPreset.entries.forEach { preset ->
            val at = preset.resolve(now, zone, latest) ?: return@forEach

            DropdownMenuItem(
                text = { Text("${stringResource(preset.label)} · ${at.asWhen(zone)}") },
                onClick = {
                    onDismiss()
                    onChosen(at)
                },
            )
        }

        DropdownMenuItem(
            text = { Text(stringResource(R.string.send_later_pick)) },
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
 *
 * The calendar is bounded by [latest], which is the session's `maxDelayedSend` and nothing else: a
 * date past the ceiling is not selectable because the server would refuse it by name, and a refusal
 * after the composer has closed is a message the user believes is scheduled.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendLaterPicker(latest: Instant, onDismiss: () -> Unit, onChosen: (Instant) -> Unit) {
    val zone = remember { ZoneId.systemDefault() }
    val now = remember { Instant.now() }

    val selectable = remember(latest) { WithinWindow(now, latest, zone) }

    val dateState = rememberDatePickerState(selectableDates = selectable)
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
                                Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                            }
                    },
                ) {
                    Text(stringResource(R.string.send_later_next))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.send_later_cancel)) }
            },
        ) {
            DatePicker(state = dateState)
        }

        return
    }

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            val at = chosenDate.atTime(timeState.hour, timeState.minute).atZone(zone).toInstant()

            TextButton(
                // A time already past, or one minute past the ceiling on the
                // last selectable day. Disabled rather than refused after the
                // fact: the server's answer would arrive with the composer
                // already closed.
                enabled = at.isAfter(Instant.now()) && !at.isAfter(latest),
                onClick = { onChosen(at) },
            ) {
                Text(stringResource(R.string.send_later_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.send_later_cancel)) }
        },
    ) {
        TimePicker(state = timeState)
    }
}

/** Today through the last day the server would accept, in the user's own zone. */
@OptIn(ExperimentalMaterial3Api::class)
private class WithinWindow(
    now: Instant,
    latest: Instant,
    private val zone: ZoneId,
) : SelectableDates {

    private val first = now.atZone(zone).toLocalDate()
    private val last = latest.atZone(zone).toLocalDate()

    override fun isSelectableDate(utcTimeMillis: Long): Boolean {
        val date = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()

        return !date.isBefore(first) && !date.isAfter(last)
    }

    override fun isSelectableYear(year: Int): Boolean = year >= first.year && year <= last.year
}

/** A release time the way a person reads one, in their own zone. */
internal fun Instant.asWhen(zone: ZoneId = ZoneId.systemDefault()): String =
    RELEASE_FORMAT.format(atZone(zone))

private val RELEASE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
