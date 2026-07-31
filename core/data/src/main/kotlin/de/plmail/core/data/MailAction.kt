package de.plmail.core.data

/**
 * A change the user made to one or more conversations.
 *
 * Named as verbs the product uses rather than as the wire operations behind them, because two of
 * them are not what they look like: archiving is *removing the Inbox label* and nothing else, and
 * trashing goes through JMAP's `destroy`, which in plMail is a move to Trash. There is no hard
 * delete anywhere in the product, and destructive UI must say Trash.
 */
sealed interface MailAction {

    /** Every action has one, and it is what makes the snackbar possible. */
    val inverse: MailAction

    data object Archive : MailAction {
        override val inverse: MailAction
            get() = MoveToInbox
    }

    /** Archiving's inverse: putting the Inbox label back. */
    data object MoveToInbox : MailAction {
        override val inverse: MailAction
            get() = Archive
    }

    /**
     * `Email/set` `destroy`, which moves to Trash.
     *
     * Its inverse restores the Inbox label rather than "un-destroying": the message still exists,
     * it is simply in Trash, so recovery is a move like any other.
     */
    data object Trash : MailAction {
        override val inverse: MailAction
            get() = MoveToInbox
    }

    data class Star(val flagged: Boolean) : MailAction {
        override val inverse: MailAction
            get() = Star(!flagged)
    }

    data class MarkRead(val seen: Boolean) : MailAction {
        override val inverse: MailAction
            get() = MarkRead(!seen)
    }

    data object MarkSpam : MailAction {
        override val inverse: MailAction
            get() = MoveToInbox
    }
}

/**
 * One conversation an action applies to.
 *
 * Deliberately *not* carrying message ids. A conversation's messages are known to the cache and not
 * to the list row, and letting a caller supply them invites the mistake of passing the thread id --
 * which is a different id space, so the server would be told to change messages that do not exist
 * while the local row moved anyway.
 */
data class ActionTarget(val accountKey: String, val threadId: String)

/**
 * An applied action, kept for as long as the snackbar is on screen.
 *
 * Holds what was done and to what, so undoing needs no further lookup — by the time the user taps
 * undo the rows have already moved and re-deriving the targets from the current state would find
 * the wrong ones.
 */
data class UndoableAction(val action: MailAction, val targets: List<ActionTarget>) {
    val threadCount: Int
        get() = targets.size
}

/**
 * What happened when the change reached the server.
 *
 * A failure is surfaced rather than logged, and that is a product rule rather than a preference:
 * the row has already moved on screen, so a rejection nobody mentions leaves the user believing
 * something happened that did not.
 */
sealed interface ActionOutcome {
    /**
     * Present on both cases on purpose.
     *
     * A rejected action is still undoable — the local change was applied and is what the user is
     * looking at, so the way back has to exist whether or not the server agreed.
     */
    val undoable: UndoableAction

    data class Applied(override val undoable: UndoableAction) : ActionOutcome

    data class Rejected(override val undoable: UndoableAction, val reason: String) : ActionOutcome
}
