package de.plmail.core.data

import de.plmail.core.datastore.StoredSwipes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a swipe does, read back out of what was stored.
 *
 * The defaults are a promise — somebody who never opens the setting finds the row doing what it
 * always did — and the rest is about values this build did not write: a word from a newer one, and
 * the single confirmation answer 0.0.27 kept for both directions.
 */
class SwipeActionsTest {

    @Test
    fun `nothing stored is archive one way, trash the other, and no questions`() {
        assertEquals(
            SwipeActions(SwipeAction.ARCHIVE, SwipeAction.TRASH, false, false),
            StoredSwipes().asActions(),
        )
    }

    /** A gesture that silently stops working after a downgrade is worse than the old one back. */
    @Test
    fun `a word this build does not know falls back to that direction's default`() {
        val actions = StoredSwipes(toEnd = "teleport", toStart = "read").asActions()

        assertEquals(SwipeAction.ARCHIVE, actions.toEnd)
        assertEquals(SwipeAction.READ, actions.toStart)
    }

    @Test
    fun `each direction keeps its own answer`() {
        val actions = StoredSwipes(confirmToEnd = false, confirmToStart = true).asActions()

        assertFalse(actions.asksToEnd)
        assertTrue(actions.asksToStart)
    }

    /**
     * Snooze and move can be asked about like anything else: the list each one opens is not the
     * question for somebody who switched the question on. Only "nothing" has nothing to ask.
     */
    @Test
    fun `every action but nothing can be asked about`() {
        val actions =
            StoredSwipes(
                    toEnd = "snooze",
                    toStart = "none",
                    confirmToEnd = true,
                    confirmToStart = true,
                )
                .asActions()

        assertTrue(actions.asksToEnd)
        assertFalse(actions.asksToStart)
        assertTrue(SwipeAction.MOVE.canBeConfirmed)
    }

    /**
     * 0.0.27 kept one answer for both directions. `trash` meant "only the side that deletes", so it
     * becomes a switch on that side; `always` becomes both.
     */
    @Test
    fun `the single answer of the release before is read into both directions`() {
        val trashOnly = StoredSwipes(legacyConfirm = "trash").asActions()

        assertFalse(trashOnly.confirmToEnd)
        assertTrue(trashOnly.confirmToStart)

        val always = StoredSwipes(legacyConfirm = "always").asActions()

        assertTrue(always.confirmToEnd && always.confirmToStart)
    }

    /** Once a direction has been set here, what the old release said about it no longer counts. */
    @Test
    fun `a direction set since outranks the old answer`() {
        val actions = StoredSwipes(confirmToStart = false, legacyConfirm = "always").asActions()

        assertTrue(actions.confirmToEnd)
        assertFalse(actions.confirmToStart)
    }
}
