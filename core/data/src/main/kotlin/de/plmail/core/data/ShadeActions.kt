package de.plmail.core.data

import de.plmail.core.datastore.ShadeActionsStore
import de.plmail.core.datastore.StoredShadeActions
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * A button on a mail notification (#93).
 *
 * Two of these beside Reply, because Android draws three actions on a notification and no more;
 * Reply keeps its place since it is the one that needs the shade's own text field. Each is a
 * [MailAction] the conversation list already knows how to apply and undo.
 */
enum class ShadeAction(val wire: String) {
    NONE("none"),
    ARCHIVE("archive"),
    MARK_READ("read"),
    TRASH("trash"),
    STAR("star");

    /** What the button does to the conversation, or null for no button. */
    val mailAction: MailAction?
        get() =
            when (this) {
                NONE -> null
                ARCHIVE -> MailAction.Archive
                MARK_READ -> MailAction.MarkRead(seen = true)
                TRASH -> MailAction.Trash
                STAR -> MailAction.Star(flagged = true)
            }

    companion object {
        fun fromWire(wire: String?): ShadeAction? = entries.firstOrNull { it.wire == wire }
    }
}

/** Archive and Mark read unless somebody chose otherwise: what the shade has always offered. */
data class ShadeActions(
    val first: ShadeAction = ShadeAction.ARCHIVE,
    val second: ShadeAction = ShadeAction.MARK_READ,
) {
    /** The buttons to draw, in order, without "none" and without the same one twice. */
    val shown: List<ShadeAction>
        get() = listOf(first, second).filter { it != ShadeAction.NONE }.distinct()
}

@Singleton
class ShadeActionsRepository @Inject constructor(private val store: ShadeActionsStore) {

    /** A word this build does not know falls back to that slot's default, as the swipes do. */
    val actions: Flow<ShadeActions> = store.prefs.map { it.asActions() }

    /** Read when a notification is built, so a change applies to the next mail that arrives. */
    suspend fun current(): ShadeActions = actions.first()

    suspend fun setFirst(action: ShadeAction) = store.setFirst(action.wire)

    suspend fun setSecond(action: ShadeAction) = store.setSecond(action.wire)
}

internal fun StoredShadeActions.asActions(): ShadeActions {
    val defaults = ShadeActions()

    return ShadeActions(
        first = ShadeAction.fromWire(first) ?: defaults.first,
        second = ShadeAction.fromWire(second) ?: defaults.second,
    )
}
