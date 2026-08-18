package de.plmail.core.data

import de.plmail.core.datastore.SidebarPrefs
import de.plmail.core.datastore.SidebarPrefsStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * The sidebar, split into the three groups it draws.
 *
 * Gmail's shape rather than an invention: the inbox and its categories at the top, a short list of
 * the places people actually go, and then everything else. What made the old drawer hard to use was
 * not that it was wrong but that it was *flat* — Starred, Trash and Sent sat in the same undivided
 * column as thirty user labels, so the five rows somebody wants a hundred times a day were found by
 * scanning rather than by knowing where to look.
 *
 * [inbox] is in neither group, and that is structural rather than a special case. It is the anchor
 * the inbox categories are drawn in the place of — see `LabelSidebar` — so it is the top group, and
 * putting it in Important as well would draw it twice.
 */
data class SidebarSections(
    val inbox: Label?,
    val important: List<Label>,
    val other: List<Label>,
)

/**
 * Which group a label falls in, given what the user has said.
 *
 * The user's own answer wins over the default in both directions. Anything they have never touched
 * falls back to [isImportantByDefault], which is what makes a label created on the web after this
 * setting was last opened land somewhere sensible instead of nowhere.
 */
internal fun SidebarPrefs.isImportant(label: Label): Boolean =
    when (label.key) {
        in pinned -> true
        in unpinned -> false
        else -> isImportantByDefault(label.role)
    }

/**
 * The five the group starts with: Starred, Trash, Spam, Sent, Archive.
 *
 * By role rather than by name, for the reason the sidebar's glyphs are chosen by role: a label
 * somebody made and called "Trash" is one of *their* labels, and lifting it into the important
 * group on the strength of its name would be the app claiming to know something it does not.
 *
 * Drafts and Snoozed are deliberately not here. Both are places mail waits for *you* rather than
 * places you go looking, and the group is short on purpose — a shortlist of eight is a list again.
 * Neither is stuck there: both are one tap from being pinned, which is the whole reason the default
 * is only a default.
 */
private fun isImportantByDefault(role: String?): Boolean = role in DEFAULT_IMPORTANT_ROLES

private val DEFAULT_IMPORTANT_ROLES = setOf("flagged", "trash", "junk", "sent", "archive")

/**
 * Splits the sidebar's labels into its groups, in the order they arrive.
 *
 * The incoming order is kept inside each group rather than re-sorted, because it is already a
 * product decision that `LabelRepository` makes — system labels in a chosen sequence, then the
 * user's own alphabetically. Sorting again here would mean two places deciding, and pinning a label
 * would silently reorder the ones around it.
 */
internal fun List<Label>.sidebarSections(prefs: SidebarPrefs): SidebarSections {
    val inbox = firstOrNull { it.role == INBOX_ROLE }
    val rest = filter { it.role != INBOX_ROLE }
    val (important, other) = rest.partition(prefs::isImportant)

    return SidebarSections(inbox = inbox, important = important, other = other)
}

private const val INBOX_ROLE = "inbox"

/**
 * The sidebar's grouping, as the navigation sees it.
 *
 * Here rather than in `:feature:mail` for the reason [NotificationSettings] is: the preference
 * lives in `:core:datastore`, the vocabulary of labels lives here, and a feature module that
 * reached for the store directly would be the one place in the app where a screen owned a
 * persistence detail. What the ViewModel gets is a flow of groups and one method to move a row.
 */
@Singleton
class SidebarSettings @Inject constructor(private val store: SidebarPrefsStore) {

    /**
     * The groups, recomputed whenever the labels or the user's choices change.
     *
     * Takes the label flow rather than reading one itself, because the caller's is already joined
     * against the account list — a label row is a sum across accounts, and an account disappearing
     * has to take its share of the unread count with it.
     */
    fun sections(labels: Flow<List<Label>>): Flow<SidebarSections> =
        combine(labels, store.prefs) { all, prefs -> all.sidebarSections(prefs) }

    suspend fun setImportant(label: Label, important: Boolean) =
        store.setImportant(label.key, important)
}
