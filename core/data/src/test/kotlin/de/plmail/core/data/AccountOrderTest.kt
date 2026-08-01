package de.plmail.core.data

import de.plmail.core.database.AccountEntity
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What happens when the stored order and the account list disagree.
 *
 * They will, routinely, and neither of them is wrong when they do: the order is written when
 * somebody arranges a screen and the account list is rewritten from the session on every launch. A
 * mailbox added on the server has never been ordered; one removed from it is still named in an
 * order somebody set last year. Every case below is one of those, and the failure mode in each is
 * silent — an account that vanishes from the app because it was not in a list, or a newcomer that
 * inserts itself at the top and quietly becomes the mailbox new mail is written from.
 */
class AccountOrderTest {

    private fun account(uid: String) =
        AccountEntity(uid = uid, serverId = "https://mail.test", accountId = uid, name = uid)

    private val a = account("a")
    private val b = account("b")
    private val c = account("c")

    @Test
    fun `no stored order leaves the session's order alone`() {
        assertEquals(listOf(a, b, c), ordered(listOf(a, b, c), emptyList()))
    }

    @Test
    fun `a stored order is applied`() {
        assertEquals(listOf(c, a, b), ordered(listOf(a, b, c), listOf("c", "a", "b")))
    }

    /**
     * The case that decides whether adding a mailbox on the server can move somebody's main one.
     * Appended, never inserted: the arrangement is about the accounts that were arranged.
     */
    @Test
    fun `an account nobody has ordered goes last, in the session's order`() {
        assertEquals(listOf(c, a, b), ordered(listOf(a, b, c), listOf("c", "a")))
    }

    @Test
    fun `an ordered account that no longer exists is dropped rather than leaving a hole`() {
        assertEquals(listOf(b, a), ordered(listOf(a, b), listOf("b", "gone", "a")))
    }

    /**
     * An order naming *only* accounts that have gone is the state after re-pairing against a
     * different server, and the whole list has to survive it. Returning nothing here would empty
     * the sidebar, the From picker and the settings screen at once, with no error anywhere.
     */
    @Test
    fun `an order that matches nothing falls back to the whole list`() {
        assertEquals(listOf(a, b), ordered(listOf(a, b), listOf("x", "y")))
    }

    @Test
    fun `a duplicate in the stored order is not a duplicate row`() {
        assertEquals(listOf(a, b), ordered(listOf(a, b), listOf("a", "a", "b")))
    }
}
