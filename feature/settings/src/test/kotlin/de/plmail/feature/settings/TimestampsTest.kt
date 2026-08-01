package de.plmail.feature.settings

import java.time.Duration
import java.util.Locale
import java.util.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotEquals

/**
 * What a diagnostics timestamp has to be able to do.
 *
 * Neither of these asserts a literal string, deliberately — the format is the platform's and
 * follows the device's locale, which is the correct behaviour and not something to freeze. What
 * they assert is the two properties that make the timestamp *usable*, both of which a plausible
 * "improvement" destroys while still rendering something that looks fine.
 *
 * The improvement in question is switching to relative time — "3 minutes ago" — because that is
 * what the rest of the app does on a message list. It is right there and wrong here: the whole
 * purpose of these timestamps is to be lined up against a server log, a container restart or the
 * moment somebody last touched their reverse proxy, and "yesterday" cannot be lined up against
 * anything.
 */
class TimestampsTest {

    private lateinit var zone: TimeZone
    private lateinit var locale: Locale

    @BeforeTest
    fun pinTheEnvironment() {
        zone = TimeZone.getDefault()
        locale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.UK)
    }

    @AfterTest
    fun restoreTheEnvironment() {
        TimeZone.setDefault(zone)
        Locale.setDefault(locale)
    }

    /**
     * The date is in there.
     *
     * Two syncs at the same clock time a week apart must not read identically. A time-only format
     * renders both as "09:14" and the screen then reports a week-old sync as this morning's, which
     * is worse than showing nothing — it actively argues that the server is fine.
     */
    @Test
    fun `the same clock time on two different days reads differently`() {
        val morning = 1_754_035_200_000L // 2025-08-01T08:00:00Z
        val aWeekLater = morning + Duration.ofDays(7).toMillis()

        assertNotEquals(asAbsoluteTime(morning), asAbsoluteTime(aWeekLater))
    }

    /**
     * And the seconds are in there.
     *
     * Correlating against a log means matching a line, and a sync at 09:14:02 against an error at
     * 09:14:31 is a sequence rather than a coincidence. Minute precision loses that ordering
     * exactly where somebody is trying to establish it.
     */
    @Test
    fun `two moments in the same minute read differently`() {
        val at = 1_754_035_200_000L
        val halfAMinuteLater = at + Duration.ofSeconds(30).toMillis()

        assertNotEquals(asAbsoluteTime(at), asAbsoluteTime(halfAMinuteLater))
    }
}
