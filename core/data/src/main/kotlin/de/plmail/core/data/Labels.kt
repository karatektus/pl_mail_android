package de.plmail.core.data

import de.plmail.core.database.MailboxEntity
import de.plmail.core.database.StoreKey
import de.plmail.core.database.ThreadEntity

/**
 * One label as the user thinks of it: a single thing, however many accounts bind it.
 *
 * JMAP has no such object. It has Mailboxes, and a Mailbox is the *binding* of a label to one
 * account — so the label somebody sees once in the sidebar is three Mailboxes with three unrelated
 * ids when they have three accounts. Collapsing them is this type's whole reason to exist, and
 * [key] is what makes it possible: plMail's `labelId` extension, a user-scoped id that is the same
 * value in every account that binds the label.
 *
 * **Never collapse on name.** It is the obvious alternative and it is wrong in both directions:
 * renaming a label in one account silently splits it into two sidebar rows, and two genuinely
 * different labels that happen to share a name in two accounts merge into one — after which
 * applying it writes to a mailbox in an account the user never meant to touch.
 */
data class Label(
    /**
     * The collapse key — `labelId` where the server sends one.
     *
     * Falls back to the binding's own uid otherwise, which keeps a server without the extension
     * usable: one sidebar row per account rather than none at all.
     */
    val key: String,
    /** Leaf name, as it is typed and edited. */
    val name: String,
    /**
     * The full path, `Work/Invoices`, for a nested label.
     *
     * Flat-with-paths rather than a tree, deliberately: a sidebar that expands and collapses hides
     * the label somebody is looking for behind a disclosure triangle they have to remember to open,
     * and mail labels are nested two deep at most in practice.
     */
    val path: String,
    /** The JMAP role, lower case, or null for a label the user made. */
    val role: String?,
    /**
     * The colour token the server sent — `blue`, `amber` — or null for "no colour chosen".
     *
     * Decided by the *primary* binding, exactly as [name] is, and for the same reason: colour lives
     * on the label rather than on the binding, so the accounts agree in practice, and choosing
     * deterministically is what stops the sidebar re-tinting itself when two accounts sync in a
     * different order.
     *
     * Carried as the raw token rather than a resolved colour, because this module cannot see one.
     * `:core:designsystem` owns the vocabulary and the per-theme resolution; a colour resolved here
     * would be one theme's answer frozen into a data class the theme switcher cannot reach.
     */
    val color: String?,
    val unreadThreads: Int,
    val totalThreads: Int,
    val mayRename: Boolean,
    val mayDelete: Boolean,
    /** Where this label lives, per account. Applying it means writing to every one of these. */
    val bindings: List<LabelBinding>,
) {
    val isSystem: Boolean
        get() = role != null

    /** The feed id for browsing this label. Stable, because it keys the persisted feed table. */
    val feedId: String
        get() = labelFeedId(key)
}

/** One label's binding in one account. */
data class LabelBinding(val accountKey: String, val mailboxId: String)

/**
 * The key a mailbox row collapses onto — plMail's `labelId`, or the row's own uid where a server
 * does not send one.
 */
internal fun MailboxEntity.labelKey(): String = labelId ?: uid

/**
 * The feed id a label's list is persisted under.
 *
 * One function rather than the same string in two places, because the two places are a write and a
 * delete: [MailActions] has to reach a label's list from a raw mailbox row when it takes a
 * conversation out of it, and a second copy of this expression is how a row gets written into one
 * list and removed from another that only looks like it.
 */
internal fun labelFeedId(key: String): String = "label.$key"

/**
 * Collapses raw mailbox rows into the label list the sidebar draws.
 *
 * Unsubscribed labels are dropped. plMail creates Archive hidden, and hidden is a real preference
 * the user expressed on the web rather than an oddity to work around — showing it anyway would mean
 * the phone and the web disagree about a list they curated themselves.
 */
fun List<MailboxEntity>.asLabels(): List<Label> {
    // Built once over *every* row, including the unsubscribed ones, because a
    // visible label may hang under a hidden parent and still needs its path.
    val byUid = associateBy { it.uid }

    return filter { it.isSubscribed && it.role !in HIDDEN_ROLES }
        .groupBy { it.labelKey() }
        .map { (key, bindings) ->
            // The first binding by account decides the name and the rights.
            // They agree in practice -- one label row on the server, bindings
            // differing only by account -- and choosing deterministically is
            // what stops the sidebar reshuffling when two accounts happen to
            // sync in a different order.
            val primary = bindings.minBy { it.accountKey }

            Label(
                key = key,
                name = primary.name,
                path = primary.pathIn(byUid),
                role = primary.role,
                color = primary.color,
                // Summed, because the row is one row: two accounts each holding
                // four unread in the same label is eight unread to the person
                // reading it.
                unreadThreads = bindings.sumOf { it.unreadThreads },
                totalThreads = bindings.sumOf { it.totalThreads },
                mayRename = primary.mayRename,
                mayDelete = primary.mayDelete,
                bindings =
                    bindings
                        .map { LabelBinding(it.accountKey, it.mailboxId) }
                        .sortedBy { it.accountKey },
            )
        }
        .sortedWith(
            compareBy(
                { label ->
                    ROLE_ORDER.indexOf(label.role).let { if (it < 0) ROLE_ORDER.size else it }
                },
                { it.path.lowercase() },
            )
        )
}

/**
 * The label's full path, built by walking `parentId` upward.
 *
 * Depth-limited rather than trusting the data. `parentId` is a server-supplied id, and a cycle in
 * it — from a bug, a half-applied sync, or a row that outlived its parent — would hang the sidebar
 * rather than draw a slightly wrong name.
 */
private fun MailboxEntity.pathIn(byUid: Map<String, MailboxEntity>): String {
    val segments = mutableListOf(name)
    var current = this

    repeat(MAX_DEPTH) {
        val parentId = current.parentId ?: return segments.joinToString(SEPARATOR)
        val parent =
            byUid[StoreKey.objectKey(current.accountKey, parentId)]
                ?: return segments.joinToString(SEPARATOR)

        segments.add(0, parent.name)
        current = parent
    }

    return segments.joinToString(SEPARATOR)
}

/**
 * Where a system label sits in the sidebar, fixed rather than taken from the wire.
 *
 * The server does send a `sortOrder` and it agrees with this list — but it reports **0** for every
 * custom label too, so ordering on it alone drops the user's own labels in among Inbox at the top.
 * Roles rank here; everything else sorts alphabetically after all of them.
 */
private val ROLE_ORDER =
    listOf("inbox", "sent", "drafts", "junk", "trash", "archive", "snoozed", "flagged", "important")

/**
 * Roles that say where mail *is* rather than naming a place to browse.
 *
 * `all` is an IMAP All Mail container: every message is in it, so a sidebar row for it is a second
 * copy of the entire mailbox under a name that explains nothing.
 */
private val HIDDEN_ROLES = setOf("all")

private const val MAX_DEPTH = 8
private const val SEPARATOR = "/"

/**
 * One chip on a row: what it says and what colour it is.
 *
 * The colour is the server's raw token rather than a resolved colour, because this module cannot
 * resolve one — the vocabulary and the per-theme answer both live in `:core:designsystem`, which
 * nothing here depends on. The row maps the token across at the point of drawing.
 */
data class RowLabel(val name: String, val color: String? = null)

/**
 * The labels a list row shows, and how many it could not fit.
 *
 * [labels] is what gets drawn; [hidden] is everything past the cap, counted rather than dropped —
 * "+2" is a small thing to draw and the difference between a row that is short of space and a row
 * that is wrong.
 */
data class RowLabels(val labels: List<RowLabel>, val hidden: Int) {
    val isEmpty: Boolean
        get() = labels.isEmpty() && hidden == 0

    companion object {
        val NONE = RowLabels(emptyList(), 0)
    }
}

/**
 * Which of a conversation's labels are worth putting on its row.
 *
 * Three things are removed, and each of them is a chip that would appear on nearly every row while
 * telling nobody anything:
 *
 * - **The label being looked at.** Every conversation in the Work list carries Work. A column of
 *   identical chips down the side of a filtered list is pure noise, and it is noise precisely where
 *   the screen is at its most crowded.
 * - **System roles.** Inbox, Sent, Drafts, Trash, Junk, Archive. Where the mail *is* is what the
 *   list already says; a chip repeating it is a second copy of the screen's own title. This is by
 *   `role`, never by name, for the same reason the sidebar's icons are: a label somebody made and
 *   called "Archive" is theirs, and hiding it would silently drop it off every row it is on.
 * - **Anything the sidebar does not know.** A key with no matching label is a binding this device
 *   has not synced yet, or one hidden by the user's own subscription choices — either way, drawing
 *   a chip for it would mean drawing a label they cannot see anywhere else in the app.
 *
 * Order comes from [labels] rather than from the stored keys, so the same two labels appear in the
 * same order on every row rather than in whatever order the mailbox bindings happened to resolve.
 */
fun ThreadEntity.rowLabels(
    labels: List<Label>,
    viewing: Label?,
    limit: Int = ROW_LABEL_LIMIT,
): RowLabels {
    if (labelKeys.isBlank()) return RowLabels.NONE

    val carried = labelKeys.split(",").filter { it.isNotBlank() }.toSet()

    val shown =
        labels
            .filter { it.key in carried && !it.isSystem && it.key != viewing?.key }
            .map { RowLabel(name = it.name, color = it.color) }

    // The counter is one of the [limit] chips rather than an extra one after
    // them, which is why this is not `take(limit)`. See [ROW_LABEL_LIMIT].
    if (shown.size <= limit) return RowLabels(labels = shown, hidden = 0)

    val named = shown.take(limit - 1)

    return RowLabels(labels = named, hidden = shown.size - named.size)
}

/**
 * How many chips a row may carry — **counting the "+n" as one of them**.
 *
 * So two labels draw two names, and three draw one name and "+2". The counter taking a slot rather
 * than sitting after the names is what keeps the cluster's worst case to two chips, and the worst
 * case is the only case worth designing for here: the chips share their line with the snippet, and
 * three of them left the preview showing three words.
 *
 * The alternative — keep both names and let the cluster's width budget squeeze them — was tried and
 * is worse. Two names and a counter inside that budget come out at about fifty dp each, which is
 * four characters and an ellipsis: not a label, a smudge. One name that can be read plus an honest
 * count of what is not shown says more in less room.
 */
const val ROW_LABEL_LIMIT = 2
