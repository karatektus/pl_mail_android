package de.plmail.core.data

import de.plmail.core.database.MailboxEntity
import de.plmail.core.database.StoreKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Collapsing per-account mailboxes into the one label list a person sees.
 *
 * The cases here are the ones that fail quietly rather than loudly. Collapsing on name looks
 * correct against any test data where nobody has renamed anything; ordering on the server's
 * `sortOrder` looks correct until a custom label — which reports 0, the same as Inbox — lands at
 * the top of the sidebar. Both produce a sidebar that is merely *wrong*, never one that crashes.
 */
class LabelsTest {

    private val nas = "https://nas.local/1"
    private val work = "https://nas.local/2"

    @Test
    fun `one label bound in two accounts collapses to one row`() {
        val labels =
            listOf(
                    mailbox(nas, mailboxId = "7", labelId = "42", name = "Invoices"),
                    mailbox(work, mailboxId = "13", labelId = "42", name = "Invoices"),
                )
                .asLabels()

        assertEquals(1, labels.size)
        assertEquals(
            listOf(LabelBinding(nas, "7"), LabelBinding(work, "13")),
            labels.single().bindings,
            "both bindings have to survive -- applying the label writes to each of them",
        )
    }

    /**
     * The reason `labelId` exists at all.
     *
     * Someone renames a label in one account. Collapsed on name that becomes two sidebar rows for
     * one label, and applying either only reaches half their mail.
     */
    @Test
    fun `a label renamed in one account is still one label`() {
        val labels =
            listOf(
                    mailbox(nas, mailboxId = "7", labelId = "42", name = "Invoices"),
                    mailbox(work, mailboxId = "13", labelId = "42", name = "Rechnungen"),
                )
                .asLabels()

        assertEquals(1, labels.size)
        assertEquals(2, labels.single().bindings.size)
    }

    /**
     * And the reason it cannot be replaced by name matching in the other direction either: two
     * genuinely different labels that happen to share a name must not merge, because applying the
     * merged row would write into an account the user never chose.
     */
    @Test
    fun `two different labels sharing a name stay separate`() {
        val labels =
            listOf(
                    mailbox(nas, mailboxId = "7", labelId = "42", name = "Personal"),
                    mailbox(work, mailboxId = "13", labelId = "99", name = "Personal"),
                )
                .asLabels()

        assertEquals(2, labels.size)
    }

    @Test
    fun `system labels come first, in the product's order, whatever the wire says`() {
        val labels =
            listOf(
                    // Every custom label reports sortOrder 0, exactly like Inbox.
                    // Ordering on it alone is what puts "Aardvark" above Sent.
                    mailbox(nas, "90", "90", name = "Aardvark", sortOrder = 0),
                    mailbox(nas, "4", "4", name = "Trash", role = "trash", sortOrder = 40),
                    mailbox(nas, "1", "1", name = "Inbox", role = "inbox", sortOrder = 0),
                    mailbox(nas, "3", "3", name = "Drafts", role = "drafts", sortOrder = 20),
                    mailbox(nas, "91", "91", name = "banking", sortOrder = 0),
                    mailbox(nas, "2", "2", name = "Sent", role = "sent", sortOrder = 10),
                )
                .asLabels()

        assertEquals(
            listOf("Inbox", "Sent", "Drafts", "Trash", "Aardvark", "banking"),
            labels.map { it.name },
        )
    }

    /** Case-insensitively, or a lower-cased label sorts after every capitalised one. */
    @Test
    fun `custom labels sort alphabetically ignoring case`() {
        val labels =
            listOf(
                    mailbox(nas, "1", "1", name = "zebra"),
                    mailbox(nas, "2", "2", name = "Apple"),
                    mailbox(nas, "3", "3", name = "banana"),
                )
                .asLabels()

        assertEquals(listOf("Apple", "banana", "zebra"), labels.map { it.name })
    }

    @Test
    fun `a nested label carries its full path and sorts under its parent`() {
        val labels =
            listOf(
                    mailbox(nas, "10", "10", name = "Work"),
                    mailbox(nas, "11", "11", name = "Invoices", parentId = "10"),
                    mailbox(nas, "12", "12", name = "Zoo"),
                )
                .asLabels()

        assertEquals(listOf("Work", "Work/Invoices", "Zoo"), labels.map { it.path })
    }

    /**
     * A parent that is hidden still names its child.
     *
     * plMail creates Archive unsubscribed, and a label nested under something hidden would
     * otherwise lose the first half of its own name.
     */
    @Test
    fun `a hidden parent still contributes to the path`() {
        val labels =
            listOf(
                    mailbox(nas, "10", "10", name = "Archive", isSubscribed = false),
                    mailbox(nas, "11", "11", name = "2024", parentId = "10"),
                )
                .asLabels()

        assertEquals(listOf("Archive/2024"), labels.map { it.path })
    }

    /**
     * A `parentId` cycle must not hang the sidebar.
     *
     * It should not be possible, which is exactly why it is worth a test: the data comes from a
     * server, and an infinite loop while drawing a navigation drawer is a frozen app rather than a
     * slightly wrong label name.
     */
    @Test
    fun `a parent cycle terminates`() {
        val labels =
            listOf(
                    mailbox(nas, "10", "10", name = "A", parentId = "11"),
                    mailbox(nas, "11", "11", name = "B", parentId = "10"),
                )
                .asLabels()

        assertEquals(2, labels.size)
        assertTrue(labels.all { it.path.isNotBlank() })
    }

    @Test
    fun `unread counts add up across accounts because the row is one row`() {
        val labels =
            listOf(
                    mailbox(nas, "7", "42", name = "Invoices", unreadThreads = 4),
                    mailbox(work, "13", "42", name = "Invoices", unreadThreads = 3),
                )
                .asLabels()

        assertEquals(7, labels.single().unreadThreads)
    }

    /** An All Mail container is every message under a name that explains nothing. */
    @Test
    fun `the all role is not a place to browse`() {
        val labels =
            listOf(
                    mailbox(nas, "1", "1", name = "Inbox", role = "inbox"),
                    mailbox(nas, "9", "9", name = "All Mail", role = "all"),
                )
                .asLabels()

        assertEquals(listOf("Inbox"), labels.map { it.name })
    }

    /** Hidden is a preference the user set on the web, not an oddity to route around. */
    @Test
    fun `unsubscribed labels are not shown`() {
        val labels =
            listOf(mailbox(nas, "9", "9", name = "Archive", isSubscribed = false)).asLabels()

        assertTrue(labels.isEmpty())
    }

    /**
     * A server without plMail's extension is still usable.
     *
     * One row per account rather than none: the fallback key is the binding's own uid, which is
     * unique, so nothing merges that should not.
     */
    @Test
    fun `mailboxes with no labelId fall back to one row each`() {
        val labels =
            listOf(
                    mailbox(nas, "7", labelId = null, name = "Invoices"),
                    mailbox(work, "13", labelId = null, name = "Invoices"),
                )
                .asLabels()

        assertEquals(2, labels.size)
        assertNull(labels.first().role)
    }

    private fun mailbox(
        accountKey: String,
        mailboxId: String,
        labelId: String? = mailboxId,
        name: String,
        parentId: String? = null,
        role: String? = null,
        sortOrder: Int = 0,
        unreadThreads: Int = 0,
        isSubscribed: Boolean = true,
    ) =
        MailboxEntity(
            uid = StoreKey.objectKey(accountKey, mailboxId),
            accountKey = accountKey,
            mailboxId = mailboxId,
            labelId = labelId,
            name = name,
            parentId = parentId,
            role = role,
            sortOrder = sortOrder,
            unreadThreads = unreadThreads,
            isSubscribed = isSubscribed,
        )
}
