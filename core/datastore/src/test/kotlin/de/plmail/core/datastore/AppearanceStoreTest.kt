package de.plmail.core.datastore

import androidx.datastore.preferences.core.stringPreferencesKey
import app.cash.turbine.test
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * The appearance settings, and the one thing about them that fails quietly.
 *
 * Reading back what was written is table stakes and is checked here because it is cheap. The test
 * worth having is [a write to an unrelated key does not re-emit], because the failure it guards has
 * happened in this app before in another form: a DataStore flow that emits more often than its
 * contents change, feeding something expensive.
 *
 * Here the expensive thing is the whole app. `PlMailAppTheme` collects this flow above every
 * screen, so one spurious emission recomposes the entire tree — and this preferences file is shared
 * with the credential, the push subscription id and the recent searches, all of which are written
 * during an ordinary sync. Without the `distinctUntilChanged`, a background sync would re-theme the
 * app several times a minute and the symptom would be "scrolling stutters sometimes".
 */
class AppearanceStoreTest {

    @Test
    fun `what was chosen reads back`() = runTest {
        val store = AppearanceStore(EmittingDataStore())

        store.setTheme("nord")
        store.setLayout("boxed")
        store.setDensity("compact")
        store.setDynamicColor(true)
        store.setReduceTransparency(true)

        val stored = store.appearance.first()

        assertEquals("nord", stored.theme)
        assertEquals("boxed", stored.layout)
        assertEquals("compact", stored.density)
        assertEquals(true, stored.dynamicColor)
        assertEquals(true, stored.reduceTransparency)
    }

    @Test
    fun `nothing chosen reads as absent rather than as a default`() = runTest {
        val stored = AppearanceStore(EmittingDataStore()).appearance.first()

        // Null, not "system". The difference matters the moment `Appearance`
        // arrives from the server: a value nobody chose here is one the server
        // may fill in, and a value that had been defaulted on read would look
        // like a deliberate local override of it.
        assertNull(stored.theme)
        assertNull(stored.layout)
        assertNull(stored.density)
        assertFalse(stored.dynamicColor)
    }

    @Test
    fun `pane alpha is clamped to something still readable`() = runTest {
        val store = AppearanceStore(EmittingDataStore())

        store.setPaneAlpha(0f)

        // A slider that reaches zero can make the app unreadable and then hide
        // the screen that would undo it.
        assertEquals(0.5f, store.appearance.first().paneAlpha?.toFloat())
    }

    @Test
    fun `a write to an unrelated key does not re-emit`() = runTest {
        val preferences = EmittingDataStore()
        val store = AppearanceStore(preferences)

        store.appearance.test {
            assertEquals(StoredAppearance(), awaitItem())

            store.setTheme("dusk")
            assertEquals("dusk", awaitItem().theme)

            // What a sync does: a timestamp into the same file, several times a
            // minute, forever.
            preferences.write(stringPreferencesKey("last_sync"), "2026-08-01T05:21:00Z")
            preferences.write(stringPreferencesKey("last_sync"), "2026-08-01T05:36:00Z")

            expectNoEvents()
        }
    }
}
