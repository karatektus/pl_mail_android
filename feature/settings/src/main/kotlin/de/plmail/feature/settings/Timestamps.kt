package de.plmail.feature.settings

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * A timestamp for somebody diagnosing something.
 *
 * Absolute, with the date, and never "3 minutes ago". Relative time is right on a message list,
 * where what matters is recency; it is wrong here, where what matters is *correlating* — with a
 * server log, with the moment somebody restarted a container, with the last time they touched their
 * reverse proxy. "Yesterday" cannot be lined up against a log line and "01/08/2026, 03:14" can.
 *
 * The device's own zone and the device's own locale, because the person reading is holding the
 * device. Rendered short rather than medium so a timestamp does not wrap on a narrow phone in
 * German, where the month names are long.
 */
internal fun asAbsoluteTime(epochMillis: Long): String =
    FORMATTER.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

private val FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT, FormatStyle.MEDIUM)
