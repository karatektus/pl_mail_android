package de.plmail.feature.mail

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The avatar's colour, derived from a stable hash of the seed.
 *
 * `String.hashCode` rather than a cryptographic digest, and that is a deliberate limit: this only
 * has to be *stable*, not unpredictable. It is also why the seed is lower-cased by the mapper
 * before it ever gets here — `Ada@Example.com` and `ada@example.com` are one person and must be one
 * colour.
 *
 * The palette is fixed rather than generated so no two adjacent rows can come out as near-identical
 * shades, which is what a hash straight into HSL produces often enough to notice.
 */
internal fun avatarColour(seed: String): Long {
    if (seed.isBlank()) return AVATAR_PALETTE.last()

    // Absolute value taken via toUInt, because Int.MIN_VALUE has no positive
    // counterpart and abs() returns it unchanged -- a negative index, and a
    // crash on exactly one seed in four billion.
    val index = (seed.hashCode().toUInt() % AVATAR_PALETTE.size.toUInt()).toInt()

    return AVATAR_PALETTE[index]
}

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

/**
 * Eight colours that stay legible under white text in both themes.
 *
 * Deliberately no yellows or pale greens: those need dark text, and switching the foreground per
 * avatar makes a column of them look broken.
 */
private val AVATAR_PALETTE =
    listOf(
        0xFF1E6FD9, // blue
        0xFF7B4FC9, // violet
        0xFFC0392B, // red
        0xFF0F7B6C, // teal
        0xFFB8590F, // amber-brown
        0xFF2D6A4F, // green
        0xFF8E44AD, // purple
        0xFF4A5568, // slate, and the fallback for an unknown sender
    )
