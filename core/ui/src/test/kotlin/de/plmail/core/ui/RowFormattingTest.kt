package de.plmail.core.ui

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The two bits of row rendering with a right answer.
 *
 * Both are the kind of thing that looks fine in a screenshot and is wrong in use: an avatar that
 * changes colour when someone edits their display name, and a date column that says "Tue" for two
 * messages a week apart.
 */
class RowFormattingTest {

    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 7, 31)

    private fun at(year: Int, month: Int, day: Int, hour: Int = 9, minute: Int = 5): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun `today shows a time`() {
        assertEquals("09:05", at(2026, 7, 31).asListDate(zone, today))
    }

    @Test
    fun `earlier this week shows a weekday`() {
        assertEquals("Mon", at(2026, 7, 27).asListDate(zone, today))
    }

    /**
     * Six days, not seven.
     *
     * A message from exactly a week ago falls on the same weekday as today, so labelling it "Fri"
     * next to today's "Fri" is indistinguishable.
     */
    @Test
    fun `exactly a week ago shows a date rather than the same weekday as today`() {
        assertEquals("24 Jul", at(2026, 7, 24).asListDate(zone, today))
    }

    @Test
    fun `earlier this year shows day and month`() {
        assertEquals("3 Mar", at(2026, 3, 3).asListDate(zone, today))
    }

    @Test
    fun `another year shows the year`() {
        assertEquals("25.12.2025", at(2025, 12, 25).asListDate(zone, today))
    }

    @Test
    fun `a missing date renders as nothing rather than 1970`() {
        assertEquals("", 0L.asListDate(zone, today))
    }

    /**
     * Colouring is the design system's job now; what stays here is the letter, which is a
     * formatting decision about a *name* rather than a decision about a palette.
     */
    @Test
    fun `the letter skips punctuation`() {
        assertEquals("A", avatarLetter("\"Ada Lovelace\" <ada@example.com>"))
        assertEquals("A", avatarLetter("ada@example.com"))
        assertEquals("7", avatarLetter("7up@example.com"))
    }

    @Test
    fun `an unknown sender still gets a letter rather than an empty circle`() {
        assertEquals("?", avatarLetter(""))
        assertEquals("?", avatarLetter("+++"))
    }
}
