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

    /**
     * Putting a label on a conversation, or taking it off.
     *
     * Carries the whole [Label] rather than an id, because the id a conversation has to be patched
     * with depends on which account it is in — one label, one row in the sidebar, a different
     * mailbox id per account.
     */
    data class SetLabel(val label: Label, val applied: Boolean) : MailAction {
        override val inverse: MailAction
            get() = SetLabel(label, !applied)
    }

    /**
     * Putting a conversation away until a time, or bringing it back.
     *
     * `Thread/set` rather than `Email/set`, and a move rather than a flag: the server takes the
     * mail out of Inbox and into a Snoozed mailbox, and a scheduled job puts it back. Verified
     * against the running server — see `docs/SERVER_REQUESTS.md`.
     *
     * Its inverse is the only one in this file that is a **guess**, and it says so. "Wake it up" is
     * right whenever the conversation was awake beforehand, which is every snooze made from an
     * ordinary list — but a re-snooze, and a bulk unsnooze off the Snoozed list, are getting back
     * to a time this action never knew. That value belongs to each *conversation* rather than to
     * the change, so [MailActions] reads it out of the cache before it writes and builds an exact
     * [UndoStep] per group. This stays as the type's own meaning, and as the fallback when nobody
     * has read the cache.
     */
    data class Snooze(val until: Long?) : MailAction {
        override val inverse: MailAction
            get() = Snooze(until = null)
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
 * One leg of the way back from a change.
 *
 * There is usually exactly one — the inverse of what was done, over everything it was done to.
 * Snooze is why this is a list at all: conversations sleeping until three different times have
 * three different destinations, and no single action can express that. Undoing it stays one tap and
 * becomes several `Thread/set` calls.
 */
data class UndoStep(val action: MailAction, val targets: List<ActionTarget>)

/**
 * An applied action, kept for as long as the snackbar is on screen.
 *
 * Holds what was done, to what, and how to get back — so undoing needs no further lookup. That last
 * part is not tidiness: by the time the user taps undo the rows have already moved and the cache
 * has already been overwritten, so anything re-derived then describes the world *after* the change
 * rather than before it. A snooze undone from re-read state restores the time it was just set to.
 */
data class UndoableAction(
    /**
     * What was done, or null when it took more than one action to say.
     *
     * Null arises only from undoing something with several destinations, and it exists so the UI
     * can say "put back" instead of picking one of them and being wrong about the rest.
     */
    val action: MailAction?,
    val targets: List<ActionTarget>,
    /**
     * The way back, worked out when the action was applied.
     *
     * Empty means there is none, which is honest rather than defensive: an [UndoableAction] with no
     * action to invert has nothing to offer.
     */
    val steps: List<UndoStep> = action?.let { listOf(UndoStep(it.inverse, targets)) }.orEmpty(),
) {
    val threadCount: Int
        get() = targets.size
}

/**
 * The way back from [action], given what each conversation looked like *before* it was applied.
 *
 * Split out of [MailActions] and given its pre-state as an argument rather than a database, because
 * the interesting half is the grouping and the grouping is what breaks silently: a single
 * `previous` value restores one conversation's snooze time onto every conversation in the
 * selection, which nobody notices until somebody's mail comes back on the wrong morning.
 */
internal fun wayBackFrom(
    action: MailAction,
    targets: List<ActionTarget>,
    snoozedBefore: Map<ActionTarget, Long?>,
): List<UndoStep> =
    when (action) {
        // Grouped by what each conversation was sleeping until. Ordinarily they
        // were all awake and this collapses to the single step every other
        // action produces; it only fans out where the times genuinely differ.
        is MailAction.Snooze ->
            targets
                .groupBy { snoozedBefore[it] }
                .map { (previous, group) -> UndoStep(MailAction.Snooze(previous), group) }

        else -> listOf(UndoStep(action.inverse, targets))
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
