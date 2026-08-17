package de.plmail.core.data

import de.plmail.core.database.PlMailDatabase
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The rows above Primary, and the dots beside the categories.
 *
 * This is the whole justification for retiring the whole-inbox view: without it, mail landing in
 * Promotions is mail the user never finds out about, because the list they open no longer contains
 * it. So every rule here fails in the direction of somebody losing mail, and none of them throws
 * when it is wrong.
 *
 * **Newness is the server's answer, not this device's.** `Thread.isNew` means never displayed and
 * inside plMail's own window, and both halves are decided server-side — so there is no clock here
 * and no local "last opened" timestamp, and there should never be one again. The tests that used to
 * pin the 24-hour window and the visit bookkeeping are gone with the approximation they described;
 * `ThreadNewMarkerTest` on the server owns those rules now, which is the point of the change.
 *
 * It runs against a real database rather than a fake DAO, for the reason `FeedProjectionTest`
 * gives: the digest is a statement about what is in `threads` joined to `feed_entries`, and a fake
 * would assert only that the test agrees with itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CategoryDigestTest {

    private lateinit var database: PlMailDatabase
    private lateinit var digest: CategoryDigest

    @Before
    fun open() {
        database = inMemoryDatabase()
        digest = CategoryDigest(database)
    }

    @After
    fun close() {
        database.close()
    }

    /** The case the feature exists for: mail arrives somewhere the user is not looking. */
    @Test
    fun `new mail in another category is announced with its senders`() = runTest {
        arrived("t1", MailCategory.PROMOTIONS, sender = "Rail Europe")
        arrived("t2", MailCategory.PROMOTIONS, sender = "Duolingo")

        val bundle = digest.arrivals.first().single()

        assertEquals(MailCategory.PROMOTIONS, bundle.category)
        assertEquals(2, bundle.count)
        assertEquals(listOf("Duolingo", "Rail Europe"), bundle.senders.sorted())
        assertEquals(0, bundle.moreSenders)
    }

    /**
     * **Primary never gets a bundle.**
     *
     * Its mail is the list underneath. A row saying "3 new" above three unread rows saying the same
     * thing is the app talking to itself, and it would be the first thing anybody noticed.
     */
    @Test
    fun `primary is never announced above its own list`() = runTest {
        arrived("t1", MailCategory.PRIMARY, sender = "Ada")

        assertTrue(digest.arrivals.first().isEmpty())
    }

    /**
     * **The marker the server has retired is gone from here too.**
     *
     * This is what "in sync" means: the browser drawing the row, or this device reporting that it
     * drew one, clears `isNew`, and the bundle goes with it. There is no separate local notion of
     * having looked left to disagree.
     */
    @Test
    fun `a conversation the server no longer calls new is not announced`() = runTest {
        arrived("t1", MailCategory.PROMOTIONS, sender = "Rail Europe", isNew = false)

        assertTrue(digest.arrivals.first().isEmpty())
    }

    /**
     * **Newness is not unreadness**, and the two are allowed to disagree — that is the server's
     * model, and now the phone's. A conversation read on a laptop is still new to a device that has
     * never drawn its row, so it still deserves the bundle that says where it went.
     */
    @Test
    fun `mail read elsewhere is still new until its row has been shown`() = runTest {
        arrived("t1", MailCategory.PROMOTIONS, sender = "Rail Europe", isUnread = false)

        assertEquals(listOf(MailCategory.PROMOTIONS), digest.arrivals.first().map { it.category })
    }

    /**
     * A category is an *inbox* idea. The server never unclassifies mail, so a conversation dragged
     * to Trash keeps saying "promotions" — and announcing the bin's contents would send somebody to
     * a tab that does not have them.
     */
    @Test
    fun `mail that has left the inbox is not announced`() = runTest {
        database.seedThread(
            "t1",
            category = MailCategory.PROMOTIONS.wire,
            isNew = true,
            isInInbox = false,
            sender = "Rail Europe",
        )

        assertTrue(digest.arrivals.first().isEmpty())
    }

    /** Three names and a count, because past that the row stops being readable at a glance. */
    @Test
    fun `senders are capped and the rest are counted`() = runTest {
        listOf("A", "B", "C", "D", "E").forEachIndexed { index, sender ->
            arrived("t$index", MailCategory.UPDATES, sender = sender)
        }

        val bundle = digest.arrivals.first().single()

        assertEquals(5, bundle.count)
        assertEquals(3, bundle.senders.size)
        assertEquals(2, bundle.moreSenders)
    }

    /** Three mails from one shop is one sender, so "and 2 more" counts people. */
    @Test
    fun `one sender writing repeatedly is counted once`() = runTest {
        repeat(3) { arrived("t$it", MailCategory.UPDATES, sender = "Rail Europe") }

        val bundle = digest.arrivals.first().single()

        assertEquals(3, bundle.count)
        assertEquals(listOf("Rail Europe"), bundle.senders)
        assertEquals(0, bundle.moreSenders)
    }

    /** The enum's order, which is the order the web's tab strip and this app's sidebar both use. */
    @Test
    fun `bundles are drawn in the categories' own order`() = runTest {
        arrived("t1", MailCategory.FORUMS, sender = "A")
        arrived("t2", MailCategory.SOCIAL, sender = "B")
        arrived("t3", MailCategory.UPDATES, sender = "C")

        assertEquals(
            listOf(MailCategory.SOCIAL, MailCategory.UPDATES, MailCategory.FORUMS),
            digest.arrivals.first().map { it.category },
        )
    }

    /** A category a newer server invents has no row here to tap through to. */
    @Test
    fun `a category this build cannot name is not announced`() = runTest {
        database.seedThread(
            "t1",
            category = "purchases",
            isNew = true,
            isInInbox = true,
            sender = "A Shop",
        )

        assertTrue(digest.arrivals.first().isEmpty())
    }

    // --- Which categories are offered at all ---------------------------------

    /**
     * The web's rule for its tab strip, copied so the two surfaces show the same set: a category is
     * drawn while it holds a conversation, new or not.
     */
    @Test
    fun `a category holding only old mail is still a place to go`() = runTest {
        arrived("t1", MailCategory.PROMOTIONS, sender = "Rail Europe", isNew = false)

        assertTrue(MailCategory.PROMOTIONS in digest.populated.first())
    }

    /**
     * **Primary is always offered**, whatever the cache holds. It is where the app opens, so a set
     * that could omit it is a drawer with nowhere to go home to.
     */
    @Test
    fun `primary is offered even with nothing cached`() = runTest {
        assertEquals(setOf(MailCategory.PRIMARY), digest.populated.first())
    }

    @Test
    fun `a category with no mail is not offered`() = runTest {
        arrived("t1", MailCategory.PROMOTIONS, sender = "Rail Europe")

        val offered = digest.populated.first()

        assertTrue(MailCategory.PROMOTIONS in offered)
        assertTrue(MailCategory.FORUMS !in offered)
    }

    /** A conversation the inbox no longer holds takes its tab with it. */
    @Test
    fun `a category is not offered for mail that has left the inbox`() = runTest {
        database.seedThread("t1", category = MailCategory.PROMOTIONS.wire, isInInbox = false)

        assertTrue(MailCategory.PROMOTIONS !in digest.populated.first())
    }

    /**
     * Mail arriving in the inbox, and **nothing else** — no feed rows, no cursors, no list ever
     * opened.
     *
     * That absence is the assertion hiding in the fixture. These queries used to join against the
     * unified-inbox feed, which held only while something paged it; retiring that pager left both
     * of them answering nothing at all, on every install, and no test noticed because every fixture
     * here obligingly seeded the feed row by hand. A conversation on the device is enough.
     */
    private suspend fun arrived(
        threadId: String,
        category: MailCategory,
        sender: String,
        isNew: Boolean = true,
        isUnread: Boolean = true,
    ) {
        database.seedThread(
            threadId,
            category = category.wire,
            isUnread = isUnread,
            isNew = isNew,
            isInInbox = true,
            sender = sender,
        )
    }
}
