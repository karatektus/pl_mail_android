package de.plmail.core.data

import de.plmail.core.database.MailboxEntity
import de.plmail.core.database.StoreKey

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
        get() = "label.$key"
}

/** One label's binding in one account. */
data class LabelBinding(val accountKey: String, val mailboxId: String)

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
        .groupBy { it.labelId ?: it.uid }
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
