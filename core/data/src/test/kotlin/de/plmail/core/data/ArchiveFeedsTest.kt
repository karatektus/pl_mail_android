package de.plmail.core.data

import de.plmail.core.database.PlMailDatabase
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
 * Which lists an archived conversation leaves the moment it is swiped.
 *
 * The bug this pins showed as a row that stayed parked off-screen over its icon after a swipe: the
 * conversation was listed somewhere the local half of the action did not clear, so nothing took the
 * row away.
 */
@RunWith(RobolectricTestRunner::class)
// sdk = 36 for the reason FeedProjectionTest gives.
@Config(sdk = [36])
class ArchiveFeedsTest {

    private lateinit var database: PlMailDatabase

    @Before
    fun open() {
        database = inMemoryDatabase()
    }

    @After
    fun close() {
        database.close()
    }

    @Test
    fun `an archived conversation leaves every inbox list, whichever it was paged into`() =
        runTest {
            val inboxLists =
                listOf(
                    Feed.UNIFIED_INBOX.id,
                    MailCategory.PRIMARY.feedId,
                    MailCategory.PROMOTIONS.feedId,
                    tabFeedId("c12"),
                )
            inboxLists.forEach { database.seedEntry(it, "t1") }

            database.clearFromInboxLists(testAccountKey, "t1")

            inboxLists.forEach { assertTrue(database.entryIds(it).isEmpty(), it) }
        }

    @Test
    fun `it stays under its labels and in search, which archiving does not touch`() = runTest {
        val kept = listOf(labelFeedId("receipts"), Feed.SEARCH.id)
        kept.forEach { database.seedEntry(it, "t1") }

        database.clearFromInboxLists(testAccountKey, "t1")

        kept.forEach { assertEquals(1, database.entryIds(it).size, it) }
    }

    @Test
    fun `only the archived conversation leaves`() = runTest {
        database.seedEntry(Feed.UNIFIED_INBOX.id, "t1")
        database.seedEntry(Feed.UNIFIED_INBOX.id, "t2")

        database.clearFromInboxLists(testAccountKey, "t1")

        assertEquals(
            listOf("unified.inbox#https://nas.local/13#t2"),
            database.entryIds(Feed.UNIFIED_INBOX.id),
        )
    }
}
