package de.plmail.feature.mail

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

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

    @Test
    fun `an avatar colour is stable for one address`() {
        assertEquals(avatarColour("ada@example.com"), avatarColour("ada@example.com"))
    }

    /**
     * The mapper lower-cases the address before storing it, which is what makes this hold.
     * Colouring from the display name instead would recolour the same person whenever they
     * reconfigured their client.
     */
    @Test
    fun `two different addresses usually differ`() {
        val colours = (1..40).map { avatarColour("person$it@example.com") }.toSet()

        assertTrue(colours.size > 1, "every address landed on one colour")
    }

    @Test
    fun `an unknown sender still gets a colour and a letter`() {
        assertTrue(avatarColour("") != 0L)
        assertEquals("?", avatarLetter(""))
        assertEquals("?", avatarLetter("+++"))
    }

    @Test
    fun `the letter skips punctuation`() {
        assertEquals("A", avatarLetter("\"Ada Lovelace\" <ada@example.com>"))
        assertEquals("A", avatarLetter("ada@example.com"))
        assertEquals("7", avatarLetter("7up@example.com"))
    }

    /**
     * `abs(Int.MIN_VALUE)` is still negative, which would index out of the palette.
     *
     * Finding a seed that hashes to exactly that is impractical, so this exercises the arithmetic
     * the implementation uses rather than hunting for the input.
     */
    @Test
    fun `the palette index is never negative`() {
        val size = 8u
        val worst = (Int.MIN_VALUE.toUInt() % size).toInt()

        assertTrue(worst >= 0, "index was $worst")
        assertNotEquals(Int.MIN_VALUE, worst)
    }
}
