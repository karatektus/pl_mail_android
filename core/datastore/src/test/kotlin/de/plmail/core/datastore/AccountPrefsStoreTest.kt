package de.plmail.core.datastore

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * The two decisions about an account that the server does not know it made.
 *
 * The reason these are worth a test rather than being obvious: the values are *account uids*, and a
 * uid is `"<server>/<accountId>"` — a URL with slashes and colons in it, joined into one string for
 * storage. Picking a separator that can appear in the value is the classic way a preference file
 * quietly starts describing accounts that do not exist, and it would present as "the order resets
 * itself sometimes".
 */
class AccountPrefsStoreTest {

    private val first = "http://10.0.2.2:8002/1"
    private val second = "http://10.0.2.2:8002/2"

    @Test
    fun `an order reads back exactly, uids and all`() = runTest {
        val store = AccountPrefsStore(EmittingDataStore())

        store.setOrder(listOf(second, first))

        assertEquals(listOf(second, first), store.prefs.first().order)
    }

    @Test
    fun `nothing stored is an empty order rather than a list with a blank in it`() = runTest {
        // The naive `"".split("\n")` is `[""]`, one entry, which resolves to an
        // account nobody has — and then every real account is treated as a
        // newcomer and appended in the session's order. That looks exactly like
        // the ordering not working, with nothing to see in the file.
        assertEquals(emptyList(), AccountPrefsStore(EmittingDataStore()).prefs.first().order)
    }

    @Test
    fun `muting is per account and reversible`() = runTest {
        val store = AccountPrefsStore(EmittingDataStore())

        store.setMuted(first, muted = true)

        assertTrue(first in store.prefs.first().muted)
        assertFalse(second in store.prefs.first().muted)

        store.setMuted(first, muted = false)

        assertTrue(store.prefs.first().muted.isEmpty())
    }

    /**
     * Absence means "notifies", not "muted".
     *
     * The direction is load-bearing: a mailbox added on the server has no entry here, and storing
     * *notifying* accounts instead would leave it silent for ever — which is indistinguishable from
     * push being broken, and is the one failure this product cannot afford to fake.
     */
    @Test
    fun `an account nobody has muted is not muted`() = runTest {
        val store = AccountPrefsStore(EmittingDataStore())

        store.setMuted(first, muted = true)

        assertFalse(second in store.prefs.first().muted)
    }
}
