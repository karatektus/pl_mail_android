package de.plmail.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Where the app opens, and what a saved destination resolves to.
 *
 * Both are worth pinning for the same reason the notification scope keys are: the key is
 * **persisted**. `rememberSaveable` hands one back after a process death, and an install upgrading
 * from a build that opened on the whole inbox presents the retired key exactly once. A resolution
 * that fell over on either would drop somebody onto a list they did not choose, or — before this —
 * onto the one list the tabs exist to break up.
 */
class MailViewTest {

    private val work =
        Label(
            key = "lbl-7",
            name = "Work",
            path = "Work",
            role = null,
            color = null,
            unreadThreads = 0,
            totalThreads = 0,
            mayRename = true,
            mayDelete = true,
            bindings = emptyList(),
        )

    /**
     * **The change, in one assertion.** The app opens on Primary, not on the whole inbox.
     *
     * A constant rather than something resolved from the cache, and that is the property under
     * test: a start view that waited on "does this server classify mail" would draw the whole inbox
     * for the frame before the answer came back, on every cold launch.
     */
    @Test
    fun `the app opens on primary`() {
        assertEquals(MailView.Category(MailCategory.PRIMARY), MailView.START)
    }

    @Test
    fun `nothing saved resolves to where the app opens`() {
        assertEquals(MailView.START, MailView.restore(null, emptyList()))
    }

    /**
     * The migration, and the only reason the old key is still read.
     *
     * An install that was last open on the whole inbox has `"inbox"` saved. Falling through to the
     * `else` branch would have reached [MailView.START] anyway — this pins that it is deliberate
     * rather than accidental, so nobody later "tidies up" the constant and reintroduces a
     * whole-inbox destination by resurrecting its key.
     */
    @Test
    fun `the retired whole-inbox key resolves to primary`() {
        assertEquals(MailView.START, MailView.restore("inbox", emptyList()))
    }

    @Test
    fun `a saved category comes back as itself`() {
        assertEquals(
            MailView.Category(MailCategory.PROMOTIONS),
            MailView.restore("category:promotions", emptyList()),
        )
    }

    /** A category a later build stops knowing about, and a server that invents a sixth. */
    @Test
    fun `a category this build cannot name falls back rather than failing`() {
        assertEquals(MailView.START, MailView.restore("category:purchases", emptyList()))
    }

    @Test
    fun `a saved label resolves against the labels the app now has`() {
        assertEquals(MailView.Labelled(work), MailView.restore("label:lbl-7", listOf(work)))
    }

    /**
     * A label deleted on the web between the save and the restore.
     *
     * The alternative is a list paging a mailbox the server has forgotten, which surfaces as an
     * account that cannot be reached rather than as a label that is gone.
     */
    @Test
    fun `a label that no longer exists falls back rather than paging nothing`() {
        assertEquals(MailView.START, MailView.restore("label:lbl-7", emptyList()))
    }

    /** Round-tripping is what makes the save worth anything. */
    @Test
    fun `every view survives being saved and restored`() {
        val views = MailCategory.entries.map(MailView::Category) + MailView.Labelled(work)

        views.forEach { view ->
            assertEquals(view, MailView.restore(view.toKey(), listOf(work)), view.toKey())
        }
    }

    /**
     * A tab of the reader's own is saved by key and restored against the tabs the server now lists,
     * under whatever it is now called.
     */
    @Test
    fun `an own tab survives being saved and restored`() {
        val shipping = MailTab(key = "c12", name = "Shipping", sortOrder = 5)
        val view = shipping.view

        assertEquals(MailView.Tab("c12", "Shipping"), view)
        assertEquals("tab.c12", view.feedId)
        assertEquals(view, MailView.restore(view.toKey(), emptyList(), MailTab.BUILT_IN + shipping))
    }

    /** On one list no tab is a destination, so every saved tab restores to Primary. */
    @Test
    fun `on one list a saved tab restores to primary`() {
        val shipping = MailTab(key = "c12", name = "Shipping")

        listOf("category:promotions", "tab:c12").forEach {
            assertEquals(
                MailView.START,
                MailView.restore(it, emptyList(), MailTab.BUILT_IN + shipping, tabbed = false),
            )
        }
    }

    /** One the reader deleted on the web, or one not read off the cache yet, is Primary. */
    @Test
    fun `an own tab that is not listed restores to primary`() {
        assertEquals(MailView.START, MailView.restore("tab:c12", emptyList(), MailTab.BUILT_IN))
    }

    /**
     * A built-in the reader switched off on the web lists nothing there, so it is not restored as a
     * destination here either. With no tabs known at all — the server predates them — every
     * built-in still restores, as it always did.
     */
    @Test
    fun `a switched-off built-in restores to primary`() {
        val strip = MailTab.BUILT_IN.filter { it.builtIn != MailCategory.FORUMS }

        assertEquals(MailView.START, MailView.restore("category:forums", emptyList(), strip))
        assertEquals(
            MailView.Category(MailCategory.FORUMS),
            MailView.restore("category:forums", emptyList()),
        )
    }

    /**
     * The start destination has two spellings, and both have to answer yes.
     *
     * On a plMail that classifies nothing there are no category rows, so the sidebar draws an Inbox
     * *label* and that row reaches the same list Primary does. Code that tested only `==
     * MailView.START` got this wrong quietly and consistently: back on the Inbox label was a dead
     * press, and the pager restarted for a list it was already showing.
     */
    @Test
    fun `primary and the inbox label are both the start destination`() {
        assertTrue(MailView.START.isStartDestination)
        assertTrue(MailView.Labelled(label("1", "Inbox", role = "inbox")).isStartDestination)
    }

    @Test
    fun `nothing else is`() {
        assertFalse(MailView.Category(MailCategory.PROMOTIONS).isStartDestination)
        assertFalse(MailView.Labelled(label("2", "Archive", role = "archive")).isStartDestination)
        // Somebody's own label called Inbox is one of their labels, not the
        // inbox -- the same rule the sidebar's glyphs follow.
        assertFalse(MailView.Labelled(label("3", "Inbox")).isStartDestination)
    }

    private fun label(key: String, name: String, role: String? = null) =
        Label(
            key = key,
            name = name,
            path = name,
            role = role,
            color = null,
            unreadThreads = 0,
            totalThreads = 0,
            mayRename = role == null,
            mayDelete = role == null,
            bindings = listOf(LabelBinding("https://nas.local/1", key)),
        )

    // -- unified inboxes (pl_mail#265) --------------------------------------

    @Test
    fun `a list of one unified inbox is kept apart from every account's`() {
        val scoped = MailView.Category(MailCategory.PRIMARY, "12")

        assertEquals("ui.12/category.primary", scoped.feedId)
        assertEquals("category:primary@12", scoped.toKey())
        assertEquals("ui.12/tab.c3", MailView.Tab("c3", "Kita", "12").feedId)
    }

    @Test
    fun `with several inboxes the app opens on the first one's primary`() {
        assertEquals(
            MailView.Category(MailCategory.PRIMARY, "1"),
            MailView.restore(null, emptyList(), inboxes = listOf("1", "2")),
        )
        assertEquals(MailView.START, MailView.home(emptyList()))
    }

    @Test
    fun `a saved inbox tab comes back in its inbox`() {
        assertEquals(
            MailView.Category(MailCategory.SOCIAL, "2"),
            MailView.restore("category:social@2", emptyList(), inboxes = listOf("1", "2")),
        )
    }

    @Test
    fun `an inbox that is gone opens the first one`() {
        assertEquals(
            MailView.Category(MailCategory.PRIMARY, "1"),
            MailView.restore("category:social@9", emptyList(), inboxes = listOf("1", "2")),
        )
    }

    @Test
    fun `a tab saved for every account opens the first inbox once there are several`() {
        assertEquals(
            MailView.Category(MailCategory.PRIMARY, "1"),
            MailView.restore("category:social", emptyList(), inboxes = listOf("1", "2")),
        )
    }

    @Test
    fun `an inbox tab saved before the inboxes went back to one is every account's again`() {
        assertEquals(
            MailView.Category(MailCategory.SOCIAL),
            MailView.restore("category:social@2", emptyList()),
        )
    }

    @Test
    fun `labels stay across every account whatever the inboxes`() {
        assertEquals(
            MailView.Labelled(work),
            MailView.restore("label:lbl-7", listOf(work), inboxes = listOf("1", "2")),
        )
    }

    @Test
    fun `only the first inbox's primary is home where there are several`() {
        val home = MailView.home(listOf("1", "2"))

        assertTrue(MailView.Category(MailCategory.PRIMARY, "1").isHome(home))
        assertFalse(MailView.Category(MailCategory.PRIMARY, "2").isHome(home))
        assertFalse(MailView.START.isHome(home))
        assertTrue(MailView.START.isHome(MailView.START))
    }
}
