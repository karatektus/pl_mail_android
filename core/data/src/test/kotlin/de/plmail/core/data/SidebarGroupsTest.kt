package de.plmail.core.data

import de.plmail.core.datastore.SidebarPrefs
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

/**
 * How the drawer's three groups are decided.
 *
 * All of it is pure, which is the point of the split living here rather than in the composable: the
 * rules that matter are about *defaults* and about a user's choice outranking them, and neither is
 * a thing anybody should have to open a drawer to check.
 */
class SidebarGroupsTest {

    @Test
    fun `the inbox is its own group, because the categories are drawn in its place`() {
        val sections = listOf(inbox, sent, work).sidebarSections(SidebarPrefs())

        assertEquals(inbox, sections.inbox)
        // Not in either of the other two: drawn twice is the failure this guards.
        assertEquals(listOf(sent), sections.important)
        assertEquals(listOf(work), sections.other)
    }

    @Test
    fun `the five roles the group starts with`() {
        val all = listOf(starred, trash, spam, sent, archive, drafts, work)

        assertEquals(
            listOf(starred, trash, spam, sent, archive),
            all.sidebarSections(SidebarPrefs()).important,
        )
    }

    /**
     * Drafts and Snoozed are not in the default group, and that is a decision rather than an
     * omission — see `isImportantByDefault`. They are ordinary rows under Labels until somebody
     * says otherwise.
     */
    @Test
    fun `everything else starts under Labels, in the order it arrived`() {
        val all = listOf(sent, drafts, snoozed, work, invoices)

        assertEquals(
            listOf(drafts, snoozed, work, invoices),
            all.sidebarSections(SidebarPrefs()).other,
        )
    }

    @Test
    fun `pinning lifts a label the default left below`() {
        val sections = listOf(sent, work).sidebarSections(SidebarPrefs(pinned = setOf(work.key)))

        assertEquals(listOf(sent, work), sections.important)
        assertEquals(emptyList(), sections.other)
    }

    /**
     * The half a single "pinned" set could not express.
     *
     * Unpinning Sent has to be distinguishable from never having opened the editor, or the default
     * would helpfully put it back on the next read.
     */
    @Test
    fun `unpinning pushes a default one down and stays pushed down`() {
        val sections = listOf(sent, work).sidebarSections(SidebarPrefs(unpinned = setOf(sent.key)))

        assertEquals(emptyList(), sections.important)
        assertEquals(listOf(sent, work), sections.other)
    }

    /**
     * A label made on the web after the editor was last opened is in neither set, so it falls to
     * its role — which for somebody's own label means Labels rather than nowhere.
     */
    @Test
    fun `a label nobody has ruled on falls to its role`() {
        val prefs = SidebarPrefs(pinned = setOf(work.key), unpinned = setOf(sent.key))
        val sections = listOf(trash, invoices).sidebarSections(prefs)

        assertEquals(listOf(trash), sections.important)
        assertEquals(listOf(invoices), sections.other)
    }

    @Test
    fun `no labels at all is three empty groups rather than a guess`() {
        val sections = emptyList<Label>().sidebarSections(SidebarPrefs())

        assertNull(sections.inbox)
        assertEquals(emptyList(), sections.important)
        assertEquals(emptyList(), sections.other)
    }

    // -- fixtures ------------------------------------------------------------

    private val inbox = label("1", "Inbox", role = "inbox")
    private val sent = label("2", "Sent", role = "sent")
    private val drafts = label("3", "Drafts", role = "drafts")
    private val trash = label("4", "Trash", role = "trash")
    private val spam = label("5", "Spam", role = "junk")
    private val archive = label("6", "Archive", role = "archive")
    private val starred = label("7", "Starred", role = "flagged")
    private val snoozed = label("8", "Snoozed", role = "snoozed")
    private val work = label("9", "Work")
    private val invoices = label("10", "Invoices", path = "Work/Invoices")

    private fun label(key: String, name: String, path: String = name, role: String? = null) =
        Label(
            key = key,
            name = name,
            path = path,
            role = role,
            color = null,
            unreadThreads = 0,
            totalThreads = 0,
            mayRename = role == null,
            mayDelete = role == null,
            bindings = listOf(LabelBinding("https://nas.local/1", key)),
        )
}
