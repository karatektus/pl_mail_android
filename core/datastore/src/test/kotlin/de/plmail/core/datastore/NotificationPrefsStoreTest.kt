package de.plmail.core.datastore

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * The two sets, and why there have to be two.
 *
 * This store deliberately knows nothing about what a scope key means — the defaults live in
 * `:core:data`. What it must get right is the three-state distinction underneath them: switched on,
 * switched off, and never asked. Collapsing the last two is the bug that makes a switch appear to
 * work and then undo itself on the next launch, and it is invisible in the file.
 */
class NotificationPrefsStoreTest {

    private val primary = "category:primary"
    private val work = "label:label-work"

    @Test
    fun `nothing stored is neither on nor off`() = runTest {
        val prefs = NotificationPrefsStore(EmittingDataStore()).prefs.first()

        assertTrue(prefs.enabled.isEmpty())
        assertTrue(prefs.disabled.isEmpty())
    }

    @Test
    fun `switching on records the key as enabled`() = runTest {
        val store = NotificationPrefsStore(EmittingDataStore())

        store.setEnabled(work, enabled = true)

        assertEquals(setOf(work), store.prefs.first().enabled)
        assertTrue(store.prefs.first().disabled.isEmpty())
    }

    /**
     * **The reason a second set exists.**
     *
     * Switching Primary off has to leave a trace. With an "enabled" set alone the store would be
     * back to empty, which is what a fresh install looks like, and the default would switch Primary
     * on again behind the user's back.
     */
    @Test
    fun `switching a default-on scope off is remembered as a decision`() = runTest {
        val store = NotificationPrefsStore(EmittingDataStore())

        store.setEnabled(primary, enabled = false)

        assertEquals(setOf(primary), store.prefs.first().disabled)
    }

    /**
     * A key in both sets would be answered by whichever check ran first, and the two callers that
     * ask do not run in the same order.
     */
    @Test
    fun `a key is never in both sets, whichever way it is flipped`() = runTest {
        val store = NotificationPrefsStore(EmittingDataStore())

        store.setEnabled(work, enabled = true)
        store.setEnabled(work, enabled = false)

        assertFalse(work in store.prefs.first().enabled)
        assertTrue(work in store.prefs.first().disabled)

        store.setEnabled(work, enabled = true)

        assertTrue(work in store.prefs.first().enabled)
        assertFalse(work in store.prefs.first().disabled)
    }

    /** One switch is one switch; the others keep whatever they were. */
    @Test
    fun `switching one scope leaves the rest alone`() = runTest {
        val store = NotificationPrefsStore(EmittingDataStore())

        store.setEnabled(work, enabled = true)
        store.setEnabled(primary, enabled = false)

        val prefs = store.prefs.first()

        assertEquals(setOf(work), prefs.enabled)
        assertEquals(setOf(primary), prefs.disabled)
    }
}
