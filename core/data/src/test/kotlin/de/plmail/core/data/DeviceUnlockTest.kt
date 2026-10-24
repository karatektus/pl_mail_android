package de.plmail.core.data

import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Who gets asked to confirm it is them, and how often (#9, phase 5): only through an activity on
 * screen, and once for a whole conversation of locked messages opening together.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DeviceUnlockTest {

    private val unlock = DeviceUnlock(ApplicationProvider.getApplicationContext())

    @Test
    fun `with no activity on screen nobody is asked`() = runTest {
        assertFalse(unlock.confirm())
    }

    @Test
    fun `messages opening together are one prompt`() = runTest {
        var prompts = 0
        unlock.attach {
            prompts++
            true
        }

        val answers = List(3) { async { unlock.confirm() } }.awaitAll()

        assertEquals(listOf(true, true, true), answers)
        assertEquals(1, prompts)
    }

    @Test
    fun `a declined prompt is asked again next time`() = runTest {
        var prompts = 0
        unlock.attach {
            prompts++
            false
        }

        assertFalse(unlock.confirm())
        assertFalse(unlock.confirm())
        assertEquals(2, prompts)
    }

    @Test
    fun `an activity that went away asks no more`() = runTest {
        val detach = unlock.attach { true }
        detach()

        assertFalse(unlock.confirm())
    }

    @Test
    fun `a newer activity is not detached by an older one going`() = runTest {
        val older = unlock.attach { false }
        unlock.attach { true }
        older()

        assertTrue(unlock.confirm())
    }
}
