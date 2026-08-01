package de.plmail.core.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The letter in the avatar.
 *
 * Takes the first letter *or digit*, skipping punctuation, because an address beginning with a
 * quote or a plus sign would otherwise show a symbol that identifies nobody. Falls back to a
 * placeholder rather than an empty circle.
 */
internal fun avatarLetter(seed: String): String =
    seed.firstOrNull { it.isLetterOrDigit() }?.uppercase(Locale.ROOT) ?: "?"

/**
 * The date as a mail list writes it: time today, weekday this week, otherwise a date.
 *
 * Relative to [today] rather than to the clock so the result is testable, and so a list rendered
 * either side of midnight cannot show two different answers for the same message.
 */
fun Long.asListDate(
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone),
): String {
    if (this <= 0) return ""

    val moment = Instant.ofEpochMilli(this).atZone(zone)
    val date = moment.toLocalDate()

    return when {
        date == today -> TIME.format(moment)
        // Six days, not seven: a message from exactly one week ago would
        // otherwise be labelled with the same weekday as one from today.
        date.isAfter(today.minusDays(6)) && date.isBefore(today) -> WEEKDAY.format(moment)
        date.year == today.year -> DAY_AND_MONTH.format(moment)
        else -> WITH_YEAR.format(moment)
    }
}

private val TIME = DateTimeFormatter.ofPattern("HH:mm")
private val WEEKDAY = DateTimeFormatter.ofPattern("EEE")
private val DAY_AND_MONTH = DateTimeFormatter.ofPattern("d MMM")
private val WITH_YEAR = DateTimeFormatter.ofPattern("dd.MM.yyyy")
