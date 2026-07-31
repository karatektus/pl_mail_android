package de.plmail.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

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
}
