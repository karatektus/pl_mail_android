package de.plmail.core.data

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
 * Whether a swipe asks before it acts.
 *
 * Three answers because "ask" is two different wishes. Somebody who archives by accident while
 * scrolling wants to be asked every time; somebody who trusts the undo for everything but a
 * deletion wants to be asked about that one. [NEVER] is what the row has always done, and is safe
 * for the reason it always was: every one of these actions leaves a snackbar with the way back.
 *
 * Snoozing and moving are never asked about. Each opens a list of times or of places, and choosing
 * from it — or dismissing it — is already the confirmation.
 */
enum class SwipeConfirm(val wire: String) {
    NEVER("never"),
    TRASH("trash"),
    ALWAYS("always");

    /** Whether a swipe that would do [action] has to be confirmed first. */
    fun asksBefore(action: SwipeAction): Boolean =
        when (this) {
            NEVER -> false
            TRASH -> action == SwipeAction.TRASH
            ALWAYS ->
                action == SwipeAction.ARCHIVE ||
                    action == SwipeAction.TRASH ||
                    action == SwipeAction.READ
        }

    companion object {
        fun fromWire(wire: String?): SwipeConfirm? = entries.firstOrNull { it.wire == wire }
    }
}

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
    val confirm: SwipeConfirm = SwipeConfirm.NEVER,
)

/** The user's swipe choices, with the vocabulary this module owns put back on them. */
@Singleton
class SwipeActionsRepository @Inject constructor(private val store: SwipePrefsStore) {

    /**
     * A stored word this build does not know falls back to that direction's default rather than to
     * [SwipeAction.NONE]: it was written by a newer build, and a gesture that silently stops
     * working after a downgrade is worse than one that goes back to what it always did.
     */
    val actions: Flow<SwipeActions> =
        store.prefs.map { stored ->
            val defaults = SwipeActions()

            SwipeActions(
                toEnd = SwipeAction.fromWire(stored.toEnd) ?: defaults.toEnd,
                toStart = SwipeAction.fromWire(stored.toStart) ?: defaults.toStart,
                confirm = SwipeConfirm.fromWire(stored.confirm) ?: defaults.confirm,
            )
        }

    suspend fun setToEnd(action: SwipeAction) = store.setToEnd(action.wire)

    suspend fun setToStart(action: SwipeAction) = store.setToStart(action.wire)

    suspend fun setConfirm(confirm: SwipeConfirm) = store.setConfirm(confirm.wire)
}
