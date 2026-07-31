package de.plmail.jmap.mail

import de.plmail.jmap.protocol.LabelId
import de.plmail.jmap.protocol.MailboxId
import kotlinx.serialization.Serializable

/**
 * A JMAP Mailbox — which in plMail is a **label binding**, not an IMAP folder.
 *
 * Labels are user-scoped and span accounts; a binding is the per-account instance of one, and the
 * binding is what has a stable identity inside a single JMAP account. The user-facing concept is
 * the label. Folders are sync plumbing and must never be surfaced.
 */
@Serializable
data class Mailbox(
    val id: MailboxId,
    /**
     * The user-scoped label this binding materialises — plMail's extension to RFC 8621.
     *
     * **This is how one label is recognised across accounts.** Binding ids are per-account by
     * necessity, so a label reachable from three accounts is three Mailboxes with three unrelated
     * ids and nothing else tying them together. Collapsing the sidebar by `name` instead breaks the
     * moment the label is renamed in one account.
     *
     * It is *not* an id that can be passed to `inMailbox` or `Email/set`.
     */
    val labelId: LabelId? = null,
    /** The leaf name only — hierarchy lives in [parentId]. */
    val name: String = "",
    /** A parent *binding* id, or null when the parent has no binding here. */
    val parentId: MailboxId? = null,
    val role: String? = null,
    val sortOrder: Int = 0,
    val totalEmails: Int = 0,
    val unreadEmails: Int = 0,
    val totalThreads: Int = 0,
    val unreadThreads: Int = 0,
    val isSubscribed: Boolean = true,
    val myRights: MailboxRights = MailboxRights(),
) {
    val knownRole: MailboxRole?
        get() = MailboxRole.fromWire(role)

    /** System labels are not renamable or deletable; custom ones are. */
    val isSystem: Boolean
        get() = role != null
}

@Serializable
data class MailboxRights(
    val mayReadItems: Boolean = true,
    val mayAddItems: Boolean = true,
    val mayRemoveItems: Boolean = true,
    val maySetSeen: Boolean = true,
    val maySetKeywords: Boolean = true,
    val mayCreateChild: Boolean = true,
    val mayRename: Boolean = false,
    val mayDelete: Boolean = false,
    val maySubmit: Boolean = true,
)

/**
 * The roles plMail maps, with the sidebar order the web UI uses.
 *
 * The order is fixed here rather than read from `sortOrder`, because the server reports `sortOrder:
 * 0` for custom labels as well as for Inbox — sorting on it alone does not reproduce the documented
 * order.
 */
enum class MailboxRole(val wire: String, val sidebarOrder: Int) {
    INBOX("inbox", 0),
    SENT("sent", 10),
    DRAFTS("drafts", 20),
    /** plMail calls this Spam; the JMAP role name is `junk`. */
    JUNK("junk", 30),
    TRASH("trash", 40),
    /**
     * Created **hidden** by default, appearing only once the user makes it visible. It is IMAP
     * location bookkeeping for plain-IMAP accounts, not where "archive" as a verb puts things —
     * that means *removing* Inbox.
     */
    ARCHIVE("archive", 50),
    FLAGGED("flagged", 60),
    IMPORTANT("important", 70),
    ALL("all", 80);

    companion object {
        /** An unmapped role degrades to null; the mailbox still appears. */
        fun fromWire(value: String?): MailboxRole? = entries.firstOrNull { it.wire == value }
    }
}
