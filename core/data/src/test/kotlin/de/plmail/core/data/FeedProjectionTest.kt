package de.plmail.core.data

import de.plmail.core.database.PlMailDatabase
import de.plmail.core.database.StoreKey
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The class that makes a sync visible, and the rules that stop it lying.
 *
 * Until [FeedProjection] existed a delta sync could ask `Email/changes` correctly, receive a
 * genuinely new message, store it, summarise its conversation — and change nothing anybody could
 * see, because every list in the app reads `feed_entries` and nothing on that path wrote it. So the
 * first test here is the whole bug stated at the smallest scale it can be stated at, and the second
 * is the same bug from the other end: a conversation archived in the browser has to *leave* this
 * phone's inbox, and nothing did that either.
 *
 * The rest are the rules that keep the fix from being worse than the bug. Each one has a failure
 * mode that is invisible from the device — mail on a tab the web does not have it on, a
 * conversation reappearing below the last row a list has paged, an archive made offline undone by
 * the server's stale copy of the same conversation — and none of them can be checked without a real
 * database, because every one of them is a statement about what is in a table.
 */
@RunWith(RobolectricTestRunner::class)
// sdk = 36 for the reason `core/ui`'s screenshot tests give: a library module
// declares no targetSdk, so it inherits compileSdk 37 and Robolectric has no
// Android 37 to emulate. 36 is what :app targets anyway.
@Config(sdk = [36])
class FeedProjectionTest {

    private lateinit var database: PlMailDatabase

    private val inbox = Feed.UNIFIED_INBOX.id
    private val promotions = MailCategory.PROMOTIONS.feedId
    private val uid = StoreKey.objectKey(testAccountKey, "t1")

    @Before
    fun open() {
        database = inMemoryDatabase()
    }

    @After
    fun close() {
        database.close()
    }

    /**
     * The fix, in one assertion.
     *
     * The key is asserted as a literal rather than through a helper, because the key *is* the
     * contract: [FeedMediator] writes `"$feedId#${StoreKey.objectKey(accountKey, threadId)}"` and
     * `FeedDao.clearThread` deletes exactly that. A projection keying rows any other way would put
     * a second copy of every synced conversation into the list, and one that no archive could ever
     * remove — which is the bug `MailActions.restoreToInbox` was written to recover from.
     */
    @Test
    fun `a conversation carrying the inbox binding gets a row, keyed as the pager writes it`() =
        runTest {
            seed()
            database.seedThread("t1")

            projection().reconcile(testAccountKey, listOf("t1"))

            assertEquals(
                listOf("unified.inbox#https://nas.local/13#t1"),
                database.entryIds(inbox),
            )
        }

    /**
     * Archived in the browser.
     *
     * The delete is not a bonus half of the feature: a list that only ever gains rows shows mail
     * that has been dealt with elsewhere until something else happens to re-page it, which on a
     * list that fits the screen is never.
     */
    @Test
    fun `a conversation whose inbox binding has gone loses its row`() = runTest {
        seed()
        database.seedEntry(inbox, "t1")
        // Stored by `storeEmails` moments before from the server's own answer,
        // which no longer names the Inbox binding.
        database.seedThread("t1", labelKeys = "")

        projection().reconcile(testAccountKey, listOf("t1"))

        assertEquals(emptyList<String>(), database.entryIds(inbox))
    }

    /** A tab is a slice of the inbox, so it needs both halves of the answer. */
    @Test
    fun `a tab receives a conversation that is in the inbox and classified`() = runTest {
        seed()
        database.seedCursor(promotions)
        database.seedThread("t1", category = "promotions")

        projection().reconcile(testAccountKey, listOf("t1"))

        assertEquals(listOf("$promotions#$uid"), database.entryIds(promotions))
        assertEquals(listOf("$inbox#$uid"), database.entryIds(inbox), "and the inbox itself")
    }

    /**
     * The server classifies mail as it arrives and never unclassifies it, so a conversation in
     * Trash keeps its category. A tab that ignored the inbox binding would show the bin's
     * promotions.
     */
    @Test
    fun `a classified conversation that has left the inbox reaches no tab`() = runTest {
        seed()
        database.seedCursor(promotions)
        database.seedEntry(promotions, "t1")
        database.seedThread("t1", labelKeys = "", category = "promotions")

        projection().reconcile(testAccountKey, listOf("t1"))

        assertEquals(emptyList<String>(), database.entryIds(promotions))
    }

    /**
     * **Null is not Primary**, and neither is a token this build has never heard of.
     *
     * The server's own inbox query puts an unclassified conversation in no tab at all, so folding
     * null into Primary here would put mail on this phone's Primary that the browser's does not
     * have — every conversation on a plMail that predates the classifier, in fact. `fromWire`
     * answers null for an unknown token on purpose, and it has to land in the same place.
     */
    @Test
    fun `an unclassified conversation lands in no tab at all`() = runTest {
        seed()
        MailCategory.entries.forEach { database.seedCursor(it.feedId) }

        database.seedThread("t1", category = null)
        database.seedThread("t2", category = "newsletters")

        projection().reconcile(testAccountKey, listOf("t1", "t2"))

        MailCategory.entries.forEach {
            assertEquals(emptyList<String>(), database.entryIds(it.feedId), it.wire)
        }

        assertEquals(2, database.feed().count(inbox), "both are still inbox mail")
    }

    /**
     * Liveness, and it keys on the cursor rather than on the rows.
     *
     * The two answers differ for exactly one list — a Promotions tab that has been opened and is
     * genuinely empty — and that is the list which must not be skipped when a promotion finally
     * arrives. A list nobody has ever opened gets nothing, because writing rows into it would
     * produce a list holding one conversation and claiming to be complete.
     */
    @Test
    fun `a list this account has never paged receives nothing`() = runTest {
        seed()
        // No cursor for Promotions: the tab has never been opened.
        database.seedThread("t1", category = "promotions")

        projection().reconcile(testAccountKey, listOf("t1"))

        assertEquals(emptyList<String>(), database.entryIds(promotions))
        assertEquals(
            1,
            database.feed().count(inbox),
            "the inbox has been paged, so it gets the row",
        )
    }

    /**
     * Search is a question, not a place.
     *
     * Its rows answer the query that was last typed, and a sync writing into them would put mail
     * into a result list that never matched — which arrives instantly and looks authoritative. The
     * feed table is shared so results page and draw like the inbox; that is the price of sharing
     * it.
     */
    @Test
    fun `search results are never written`() = runTest {
        seed()
        database.seedCursor(Feed.SEARCH.id)
        database.seedThread("t1")

        projection().reconcile(testAccountKey, listOf("t1"))

        assertEquals(emptyList<String>(), database.entryIds(Feed.SEARCH.id))
    }

    /**
     * The insert is windowed and the delete is not, and the asymmetry is deliberate.
     *
     * A list is paged only as far down as somebody has scrolled, so a conversation older than its
     * tail belongs *below* the last row it holds — inserting it there would put a message from 2019
     * immediately under last week's mail. Which is an ordinary case rather than a hypothetical: a
     * read receipt or a label applied on the web brings old conversations back through
     * `Email/changes` all the time.
     */
    @Test
    fun `a conversation older than the tail of a paged list is not inserted`() = runTest {
        seed(inboxLastSortDate = 4_000)
        database.seedThread("t1", receivedAt = 1_000)

        projection().reconcile(testAccountKey, listOf("t1"))

        assertEquals(emptyList<String>(), database.entryIds(inbox))
    }

    /** A list that has reached the end of its account has no tail to fall below. */
    @Test
    fun `an exhausted list takes the same conversation`() = runTest {
        seed(inboxLastSortDate = 4_000, inboxExhausted = true)
        database.seedThread("t1", receivedAt = 1_000)

        projection().reconcile(testAccountKey, listOf("t1"))

        assertEquals(listOf("$inbox#$uid"), database.entryIds(inbox))
    }

    /**
     * The other half of the asymmetry.
     *
     * A row that should not be there goes whether or not it is inside the window: it is already on
     * screen if it is above the tail, and harmless to delete if it is not. Windowing the delete
     * would leave an archived conversation in the list of anyone who had scrolled past it.
     */
    @Test
    fun `a row that should not be there is removed even from outside the window`() = runTest {
        seed(inboxLastSortDate = 4_000)
        database.seedEntry(inbox, "t1", sortDate = 1_000)
        database.seedThread("t1", labelKeys = "", receivedAt = 1_000)

        projection().reconcile(testAccountKey, listOf("t1"))

        assertEquals(emptyList<String>(), database.entryIds(inbox))
    }

    /**
     * The highest-risk path in the whole change, and the reason the queue is consulted at all.
     *
     * The user archived this conversation with no network. The cache already carries that change —
     * the row is gone from the inbox and the mutation is queued rather than applied twice — so the
     * server's copy is out of date by exactly the gesture they made. `storeEmails` then rewrites
     * the thread row from that stale copy, inbox binding and all, and a projection that trusted it
     * would put the conversation back into the list they watched it leave. Not a stale list: a list
     * that visibly undoes what somebody did.
     */
    @Test
    fun `a conversation with a change still queued is left completely alone`() = runTest {
        seed()
        // The server's answer, restored by storeEmails: still in the inbox,
        // because the archive has never reached it.
        database.seedThread("t1")

        val outbox = emptyOutbox()
        outbox.enqueue(
            MailAction.Archive,
            listOf(ActionTarget(testAccountKey, "t1")),
            at = 1_000,
        )

        projection(outbox).reconcile(testAccountKey, listOf("t1"))

        assertEquals(emptyList<String>(), database.entryIds(inbox), "the archive must survive it")

        // The same conversation and the same tables with an empty queue, so
        // that the assertion above is the guard's doing rather than a fixture
        // that never projects anything.
        projection().reconcile(testAccountKey, listOf("t1"))

        assertTrue(database.entryIds(inbox).isNotEmpty())
    }

    /** And the same guard from the other side: a queued change may not be *undone* either. */
    @Test
    fun `a queued change does not lose the row it is about`() = runTest {
        seed()
        database.seedEntry(inbox, "t1")
        // Moved back to the inbox offline, so the cache holds the row and the
        // server's copy does not name the binding yet.
        database.seedThread("t1", labelKeys = "")

        val outbox = emptyOutbox()
        outbox.enqueue(
            MailAction.MoveToInbox,
            listOf(ActionTarget(testAccountKey, "t1")),
            at = 1_000,
        )

        projection(outbox).reconcile(testAccountKey, listOf("t1"))

        assertTrue(database.entryIds(inbox).isNotEmpty())
    }

    /** A conversation the cache no longer holds cannot be a row: the list joins on the thread. */
    @Test
    fun `a destroyed conversation is removed from every list`() = runTest {
        seed()
        database.seedEntry(inbox, "t1")

        projection().reconcile(testAccountKey, listOf("t1"))

        assertEquals(emptyList<String>(), database.entryIds(inbox))
    }

    // -- helpers -----------------------------------------------------------

    private suspend fun seed(inboxLastSortDate: Long? = null, inboxExhausted: Boolean = false) {
        database.seedAccount()
        database.seedInbox()
        database.seedCursor(inbox, lastSortDate = inboxLastSortDate, isExhausted = inboxExhausted)
    }

    private fun projection(outbox: Outbox = emptyOutbox()) = FeedProjection(database, outbox)
}
