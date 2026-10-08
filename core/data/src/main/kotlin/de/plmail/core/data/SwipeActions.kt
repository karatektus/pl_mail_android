package de.plmail.core.data

import de.plmail.core.datastore.StoredSwipes
import de.plmail.core.datastore.SwipePrefsStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * One thing a swipe on a list row can do.
 *
 * Not a [MailAction], though four of the six end in one: snoozing needs a time and moving needs a
 * destination, so those two open something rather than doing something, and [NONE] is the choice of
 * somebody who keeps archiving mail while scrolling.
 */
enum class SwipeAction(val wire: String) {
    NONE("none"),
    ARCHIVE("archive"),
    TRASH("trash"),
    /** Read if it is unread, unread if it is read: the row decides which when it is swiped. */
    READ("read"),
    SNOOZE("snooze"),
    MOVE("move");

    companion object {
        fun fromWire(wire: String?): SwipeAction? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * Whether a swipe that would do this is something there is a question to ask about.
 *
 * Everything but [SwipeAction.NONE]. Snoozing and moving were left out at first, on the theory that
 * the list each one opens is already the confirmation — and that is the app deciding for somebody
 * what counts as being asked. A list of times that appeared because a thumb slipped is still a
 * thing that appeared; whoever switches the question on for that direction wants it first.
 */
val SwipeAction.canBeConfirmed: Boolean
    get() = this != SwipeAction.NONE

/**
 * What each direction does.
 *
 * The defaults are what the row did before there was a choice — archive towards the end, trash
 * towards the start — which is also what every mail client on the platform does, so nobody who
 * never opens the setting finds anything changed.
 */
data class SwipeActions(
    val toEnd: SwipeAction = SwipeAction.ARCHIVE,
    val toStart: SwipeAction = SwipeAction.TRASH,
    /**
     * Whether each direction asks before it acts. Off is what the row has always done, and is safe
     * for the reason it always was: every one of these actions leaves a snackbar with the way back.
     *
     * Per direction rather than one answer for both, because the two are not the same gesture to
     * the person making it: the side they archive with all day is not the side they want a question
     * on, and the side that deletes may well be.
     */
    val confirmToEnd: Boolean = false,
    val confirmToStart: Boolean = false,
) {
    /** Whether a swipe towards the end has to be confirmed before it does anything. */
    val asksToEnd: Boolean
        get() = confirmToEnd && toEnd.canBeConfirmed

    val asksToStart: Boolean
        get() = confirmToStart && toStart.canBeConfirmed
}

/** The user's swipe choices, with the vocabulary this module owns put back on them. */
@Singleton
class SwipeActionsRepository @Inject constructor(private val store: SwipePrefsStore) {

    /**
     * A stored word this build does not know falls back to that direction's default rather than to
     * [SwipeAction.NONE]: it was written by a newer build, and a gesture that silently stops
     * working after a downgrade is worse than one that goes back to what it always did.
     */
    val actions: Flow<SwipeActions> = store.prefs.map { it.asActions() }

    suspend fun setToEnd(action: SwipeAction) = store.setToEnd(action.wire)

    suspend fun setToStart(action: SwipeAction) = store.setToStart(action.wire)

    suspend fun setConfirmToEnd(asks: Boolean) = store.setConfirmToEnd(asks)

    suspend fun setConfirmToStart(asks: Boolean) = store.setConfirmToStart(asks)
}

/** What was stored, with this module's vocabulary and defaults put back on it. */
internal fun StoredSwipes.asActions(): SwipeActions {
    val defaults = SwipeActions()
    val toEnd = SwipeAction.fromWire(toEnd) ?: defaults.toEnd
    val toStart = SwipeAction.fromWire(toStart) ?: defaults.toStart

    return SwipeActions(
        toEnd = toEnd,
        toStart = toStart,
        confirmToEnd = confirmToEnd ?: legacyConfirm.asksAbout(toEnd),
        confirmToStart = confirmToStart ?: legacyConfirm.asksAbout(toStart),
    )
}

/**
 * 0.0.27's one setting for both directions, read as an answer for one of them.
 *
 * `always` asked about everything and `trash` only about the direction that deletes; anything else,
 * including nothing stored, is the default.
 */
private fun String?.asksAbout(action: SwipeAction): Boolean =
    when (this) {
        "always" -> true
        "trash" -> action == SwipeAction.TRASH
        else -> false
    }
