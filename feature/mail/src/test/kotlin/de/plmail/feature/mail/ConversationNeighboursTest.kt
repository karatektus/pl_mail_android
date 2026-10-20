package de.plmail.feature.mail

import de.plmail.core.data.ActionTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Which conversation a swipe in the reader opens. */
class ConversationNeighboursTest {

    private val a = ActionTarget("work", "t1")
    private val b = ActionTarget("work", "t2")
    private val c = ActionTarget("home", "t1")

    @Test
    fun `next is the row below and previous the row above`() {
        val rows = listOf(a, b, c)

        assertEquals(2, neighbourIndex(rows, b, next = true))
        assertEquals(0, neighbourIndex(rows, b, next = false))
    }

    @Test
    fun `there is nothing before the first row or after the last`() {
        val rows = listOf(a, b, c)

        assertNull(neighbourIndex(rows, a, next = false))
        assertNull(neighbourIndex(rows, c, next = true))
    }

    /** A notification can open mail the list on screen does not hold. */
    @Test
    fun `a conversation that is not in the list has no neighbours`() {
        assertNull(neighbourIndex(listOf(a, b), c, next = true))
        assertNull(neighbourIndex(listOf(a, b), c, next = false))
    }

    /** Same thread id, different account: a merged inbox holds both, and they are not the same. */
    @Test
    fun `the account is part of what identifies a conversation`() {
        // Matched on the thread id alone, `c` would be found at `a`'s row and
        // "next" would be `c` itself.
        assertEquals(2, neighbourIndex(listOf(a, c, b), c, next = true))
    }

    /** Reading the placeholder through LazyPagingItems is what makes Paging load it. */
    @Test
    fun `a neighbour Paging has not loaded yet is still returned`() {
        assertEquals(1, neighbourIndex(listOf(a, null), a, next = true))
    }
}
