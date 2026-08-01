package de.plmail.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * What each action means, and what undoing it means.
 *
 * Two of these are not what their names suggest, and both are stated as rules in the client
 * specification rather than left to a reader's intuition: archiving is *removing the Inbox label*
 * and adding nothing, and trashing is a move rather than a deletion. Encoding them here is what
 * stops either drifting into the obvious-but-wrong version.
 */
class MailActionTest {

    @Test
    fun `archiving is undone by putting the message back in the inbox`() {
        assertEquals(MailAction.MoveToInbox, MailAction.Archive.inverse)
        assertEquals(MailAction.Archive, MailAction.MoveToInbox.inverse)
    }

    /**
     * Trash's inverse is a move, not an "un-destroy".
     *
     * `Email/set` `destroy` relocates the message to Trash — the server has no hard-delete path at
     * all — so recovering it is putting the Inbox label back, exactly like undoing an archive.
     */
    @Test
    fun `trash is undone as a move rather than a resurrection`() {
        assertEquals(MailAction.MoveToInbox, MailAction.Trash.inverse)
    }

    @Test
    fun `spam is undone by moving back to the inbox`() {
        assertEquals(MailAction.MoveToInbox, MailAction.MarkSpam.inverse)
    }

    @Test
    fun `the toggles invert their own value`() {
        assertEquals(MailAction.Star(false), MailAction.Star(true).inverse)
        assertEquals(MailAction.Star(true), MailAction.Star(false).inverse)
        assertEquals(MailAction.MarkRead(false), MailAction.MarkRead(true).inverse)
    }

    @Test
    fun `undoing a toggle twice returns to where it started`() {
        val star = MailAction.Star(true)

        assertEquals(star, star.inverse.inverse)
        assertNotEquals(star, star.inverse)
    }

    /**
     * Archive and MoveToInbox are inverses of each other, so undoing an undo is the original.
     *
     * Trash is deliberately *not* symmetric: undoing a trash restores to Inbox, and undoing that
     * archives rather than re-trashing. Re-trashing would be a destructive action triggered by
     * pressing undo twice, which is the one place this pattern can genuinely hurt someone.
     */
    @Test
    fun `undoing a trash twice does not trash again`() {
        assertNotEquals(MailAction.Trash, MailAction.Trash.inverse.inverse)
        assertEquals(MailAction.Archive, MailAction.Trash.inverse.inverse)
    }

    @Test
    fun `an undoable action counts conversations rather than messages`() {
        val undoable =
            UndoableAction(
                action = MailAction.Archive,
                targets =
                    listOf(
                        ActionTarget("acct", "t1"),
                        ActionTarget("acct", "t2"),
                    ),
            )

        // "2 archived" is what the snackbar says, because conversations are
        // what the user acted on.
        assertEquals(2, undoable.threadCount)
    }

    /**
     * Snooze's own `inverse` is the fallback, and it only knows one answer.
     *
     * Stated here so it is obvious that the type cannot do this job alone: what a conversation goes
     * back to lives on the conversation, which is why [wayBackFrom] takes the pre-state.
     */
    @Test
    fun `snooze inverts to awake when nothing knows what it was before`() {
        assertEquals(MailAction.Snooze(null), MailAction.Snooze(WEDNESDAY).inverse)
    }

    @Test
    fun `every other action needs one step and takes its own inverse`() {
        val targets = listOf(ActionTarget("acct", "t1"), ActionTarget("acct", "t2"))

        assertEquals(
            listOf(UndoStep(MailAction.MoveToInbox, targets)),
            wayBackFrom(MailAction.Archive, targets, snoozedBefore = emptyMap()),
        )
    }

    /** The ordinary case: mail snoozed out of an inbox goes back to being awake, in one step. */
    @Test
    fun `snoozing conversations that were awake is undone in a single step`() {
        val targets = listOf(ActionTarget("acct", "t1"), ActionTarget("acct", "t2"))

        val steps =
            wayBackFrom(
                MailAction.Snooze(WEDNESDAY),
                targets,
                snoozedBefore = targets.associateWith { null },
            )

        assertEquals(listOf(UndoStep(MailAction.Snooze(null), targets)), steps)
    }

    /**
     * The case a single `previous` on the action gets wrong, and the reason this is a list.
     *
     * Selecting three sleeping conversations in the Snoozed list and waking them all is one gesture
     * over three different times. Undoing it has to send each one back to its own — with one shared
     * value, whichever conversation was read last decides when everybody else's mail returns, and
     * the failure is invisible until somebody's mail arrives on the wrong morning.
     */
    @Test
    fun `undoing a bulk unsnooze restores each conversation to its own time`() {
        val monday = ActionTarget("acct", "t1")
        val wednesday = ActionTarget("acct", "t2")
        val alsoMonday = ActionTarget("acct", "t3")

        val steps =
            wayBackFrom(
                MailAction.Snooze(null),
                listOf(monday, wednesday, alsoMonday),
                snoozedBefore =
                    mapOf(monday to MONDAY, wednesday to WEDNESDAY, alsoMonday to MONDAY),
            )

        // Grouped by time rather than one step per conversation: two
        // conversations going back to the same morning are one `Thread/set`.
        assertEquals(
            listOf(
                UndoStep(MailAction.Snooze(MONDAY), listOf(monday, alsoMonday)),
                UndoStep(MailAction.Snooze(WEDNESDAY), listOf(wednesday)),
            ),
            steps,
        )
    }

    /**
     * Re-snoozing something already asleep restores the time it replaced, not "awake".
     *
     * The bug this pins is off by exactly one step: the obvious inverse of a snooze is an unsnooze,
     * and applying it to a conversation that was already sleeping delivers the mail today instead
     * of putting the original time back.
     */
    @Test
    fun `undoing a re-snooze restores the time it replaced rather than waking the mail`() {
        val target = ActionTarget("acct", "t1")

        val steps =
            wayBackFrom(
                MailAction.Snooze(WEDNESDAY),
                listOf(target),
                snoozedBefore = mapOf(target to MONDAY),
            )

        assertEquals(listOf(UndoStep(MailAction.Snooze(MONDAY), listOf(target))), steps)
    }

    /**
     * A selection spanning awake and sleeping conversations has no single way back.
     *
     * Reachable from any label that holds both — the selection bar offers one snooze control for
     * the whole selection. The UI reads "more than one step" as "put back" rather than picking one
     * of the two verbs and being wrong about half the rows.
     */
    @Test
    fun `a mixed selection produces one step per destination`() {
        val awake = ActionTarget("acct", "t1")
        val sleeping = ActionTarget("acct", "t2")

        val steps =
            wayBackFrom(
                MailAction.Snooze(WEDNESDAY),
                listOf(awake, sleeping),
                snoozedBefore = mapOf(awake to null, sleeping to MONDAY),
            )

        assertEquals(2, steps.size)
        assertNull(steps.map { it.action }.distinct().singleOrNull())
    }

    private companion object {
        const val MONDAY = 1_767_254_400_000L
        const val WEDNESDAY = 1_767_427_200_000L
    }
}
