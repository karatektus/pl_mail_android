package de.plmail.core.datastore

import androidx.datastore.preferences.core.stringPreferencesKey
import app.cash.turbine.test
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * The one thing about the calendar the server does not know.
 *
 * Which view somebody reads their calendar in is not reconstructible from anything on the server —
 * which is why it is here and not in Room, whose whole recovery strategy is "drop it and re-sync".
 * A schema bump must not quietly put a week-reader back on the agenda.
 *
 * The raw string is deliberate and is what the second test is about: decoding belongs to the
 * caller, so a value written by a newer build reaches `CalendarViewMode.fromWire` and degrades to
 * the default rather than throwing on the first frame. A calendar is not worth a launch loop.
 */
class CalendarPrefsStoreTest {

    @Test
    fun `a chosen view reads back`() = runTest {
        val store = CalendarPrefsStore(EmittingDataStore())

        store.setView("week")

        assertEquals("week", store.view.first())
    }

    @Test
    fun `the latest choice wins`() = runTest {
        val store = CalendarPrefsStore(EmittingDataStore())

        store.setView("month")
        store.setView("day")

        assertEquals("day", store.view.first())
    }

    /**
     * Nothing chosen is null rather than a guess, so the caller's own default is the one default.
     */
    @Test
    fun `nothing stored is nothing, not a default invented here`() = runTest {
        assertNull(CalendarPrefsStore(EmittingDataStore()).view.first())
    }

    /**
     * An unrelated write to the shared file must not move the calendar.
     *
     * This store is the **fifth** writer of one preferences file — the credential, the outbox, the
     * appearance overrides and the push state are the others — so a sync recording a push timestamp
     * emits here too. Without the `distinctUntilChanged` that would re-derive the window and re-run
     * the whole query against a Raspberry Pi, and this is the assertion that says so: the shape of
     * the test is `expectNoEvents`, because what is being pinned is an emission *not* happening.
     */
    @Test
    fun `an unrelated write to the same file does not re-emit`() = runTest {
        val preferences = EmittingDataStore()
        val store = CalendarPrefsStore(preferences)

        store.view.test {
            assertNull(awaitItem())

            preferences.write(stringPreferencesKey("push_last_received"), "2026-08-07T09:00:00Z")

            expectNoEvents()
        }
    }
}
