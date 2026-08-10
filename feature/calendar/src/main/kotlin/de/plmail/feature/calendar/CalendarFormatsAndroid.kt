package de.plmail.feature.calendar

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The date patterns this locale writes, asked of the platform.
 *
 * `android.text.format.DateFormat.getBestDateTimePattern` rather than
 * `DateTimeFormatter.ofLocalizedPattern`, which is API 34 against a minSdk of 31 — and rather than
 * a hand-written `"MMMM yyyy"`, which reads correctly in English and in German and is neither the
 * order nor the wording several other locales use. A skeleton names the *fields* wanted and lets
 * the locale data arrange them.
 *
 * Keyed on the configuration rather than computed once, because a per-app locale change (which is
 * how this repo tests German — see PLAN.md's `cmd locale set-app-locales`) arrives as a
 * configuration change and nothing else would re-read it.
 *
 * `Locale.getDefault()` is the right source even under a per-app locale: the platform sets it from
 * the app's own locale list before any composition runs.
 */
@Composable
internal fun rememberCalendarFormats(): CalendarFormats {
    val configuration = LocalConfiguration.current

    return remember(configuration) {
        val locale = Locale.getDefault()

        fun best(skeleton: String) =
            DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)

        CalendarFormats(
            date = best("yMMMd"),
            month = best("yMMMM"),
            dayOfMonth = best("d"),
            monthAndDay = best("MMMd"),
            weekday = best("EEE"),
            weekdayFull = best("EEEE"),
            hour = best("j"),
        )
    }
}
