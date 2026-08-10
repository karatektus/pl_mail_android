package de.plmail.feature.calendar

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * What a block on the time grid says in the height it was given.
 *
 * The arithmetic behind the fix for a week that drew "● 9:00" and no title at all: the old chip
 * laid the dot and the clock out first and measured the title against what was left, which in a
 * column fifty dp wide is nothing. The order is now a *priority* — title, then clock, then place —
 * and this is where that order is asserted, without a canvas, for the reason [monthCellPlan] is:
 * what a person actually reads off the grid is decided by these three numbers, and "one line short"
 * is the difference between a readable block and a coloured rectangle.
 *
 * The line height is passed in rather than assumed because it is a `sp` measurement resolved
 * against the user's font scale — a large-text phone divides the same block by a bigger number and
 * gets fewer lines, which is the behaviour that keeps the title unclipped instead of merely
 * smaller.
 */
class BlockTextPlanTest {

    /** The floor: a quarter-hour block still says what the meeting is. */
    @Test
    fun `a block with room for one line spends it on the title`() {
        val plan = plan(available = 14.dp)

        assertEquals(1, plan.titleLines)
        assertFalse(plan.showsTime)
        assertFalse(plan.showsPlace)
    }

    /** A block too short even for one line is still a title, never an empty rectangle. */
    @Test
    fun `a block with room for nothing still draws the title`() {
        val plan = plan(available = 4.dp)

        assertEquals(1, plan.titleLines)
        assertFalse(plan.showsTime)
    }

    @Test
    fun `the clock arrives on the second line, not in place of the first`() {
        val plan = plan(available = 27.dp)

        assertEquals(1, plan.titleLines)
        assertTrue(plan.showsTime)
    }

    /**
     * The title takes the spare lines back before anything else is offered one, which is what
     * "title has priority" means once there is more than one line to hand out.
     */
    @Test
    fun `a taller block wraps the title rather than adding furniture`() {
        val plan = plan(available = 40.dp)

        assertEquals(2, plan.titleLines)
        assertTrue(plan.showsTime)
        assertFalse(plan.showsPlace)
    }

    /** Only where the column is wide enough to have asked for it. See `BLOCK_ROOMY_WIDTH`. */
    @Test
    fun `the place appears on the third line when the column asked for one`() {
        val plan = plan(available = 40.dp, hasPlace = true)

        assertEquals(1, plan.titleLines)
        assertTrue(plan.showsTime)
        assertTrue(plan.showsPlace)
    }

    @Test
    fun `a narrow column never draws a place, however tall the block`() {
        val plan = plan(available = 200.dp, hasPlace = false)

        assertFalse(plan.showsPlace)
    }

    /** An all-afternoon block is not an excuse to set a title as a paragraph. */
    @Test
    fun `a very tall block stops growing the title`() {
        val plan = plan(available = 400.dp, hasPlace = true)

        assertEquals(4, plan.titleLines)
    }

    /** And the line it would have taken goes back to the title rather than staying blank. */
    @Test
    fun `an event with no start time is given no clock line to fill`() {
        val plan = plan(available = 40.dp, hasTime = false)

        assertFalse(plan.showsTime)
        assertEquals(3, plan.titleLines)
    }

    /**
     * A chip whose parent never constrained it asks to be small, not infinite — the case the
     * all-day band was in before it was given a height of its own.
     */
    @Test
    fun `an unbounded height is one line rather than an arithmetic error`() {
        val plan = plan(available = Dp.Infinity)

        assertEquals(1, plan.titleLines)
        assertFalse(plan.showsTime)
    }

    /**
     * The same block at a larger font scale: the line it divides by grows, so the block holds fewer
     * of them and drops the clock rather than clipping the title.
     */
    @Test
    fun `a larger font scale buys fewer lines, not smaller ones`() {
        val standard = plan(available = 27.dp, line = 13.dp)
        val large = plan(available = 27.dp, line = 13.dp * 1.5f)

        assertTrue(standard.showsTime)
        assertFalse(large.showsTime)
        assertEquals(1, large.titleLines)
    }

    private fun plan(
        available: Dp,
        line: Dp = 13.dp,
        hasTime: Boolean = true,
        hasPlace: Boolean = false,
    ) = blockTextPlan(available = available, line = line, hasTime = hasTime, hasPlace = hasPlace)
}
