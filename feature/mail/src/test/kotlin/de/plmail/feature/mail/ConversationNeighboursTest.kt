package de.plmail.feature.mail

import de.plmail.core.database.ThreadEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Which conversation a swipe in the reader opens. */
class ConversationNeighboursTest {

    private val a = ThreadEntity(uid = "work:t1", accountKey = "work", threadId = "t1")
    private val b = ThreadEntity(uid = "work:t2", accountKey = "work", threadId = "t2")
    private val c = ThreadEntity(uid = "home:t1", accountKey = "home", threadId = "t1")

    private fun List<ThreadEntity?>.around(row: ThreadEntity) =
        neighboursOf(this, row.accountKey, row.threadId)

    @Test
    fun `next is the row below and previous the row above`() {
        assertEquals(Neighbours(at = 1, previous = 0, next = 2), listOf(a, b, c).around(b))
    }

    @Test
    fun `there is nothing before the first row or after the last`() {
        val rows = listOf(a, b, c)

        assertNull(rows.around(a)?.previous)
        assertNull(rows.around(c)?.next)
    }

    /** A notification can open mail the list on screen does not hold. */
    @Test
    fun `a conversation that is not in the list has no neighbours`() {
        assertNull(listOf(a, b).around(c))
    }

    /** Same thread id, different account: a merged inbox holds both, and they are not the same. */
    @Test
    fun `the account is part of what identifies a conversation`() {
        // Matched on the thread id alone, `c` would be found at `a`'s row and
        // "next" would be `c` itself.
        assertEquals(2, listOf(a, c, b).around(c)?.next)
    }

    /** Reading the placeholder through LazyPagingItems is what makes Paging load it. */
    @Test
    fun `a neighbour Paging has not loaded yet is still returned`() {
        assertEquals(1, listOf(a, null).around(a)?.next)
    }

    @Test
    fun `placeholders before the open row are passed over, not matched`() {
        assertEquals(
            Neighbours(at = 2, previous = 1, next = null),
            listOf(null, null, b).around(b),
        )
    }
}
