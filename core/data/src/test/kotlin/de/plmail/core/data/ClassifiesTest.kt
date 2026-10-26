package de.plmail.core.data

import de.plmail.core.database.PlMailDatabase
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Whether an account's inbox is divided into categories, which decides what Primary holds.
 *
 * The bug this pins: after the cache was emptied, Primary was built as the whole inbox — nothing
 * cached had a category yet — and held the promotions until the Promotions tab was opened.
 */
@RunWith(RobolectricTestRunner::class)
// sdk = 36 for the reason FeedProjectionTest gives.
@Config(sdk = [36])
class ClassifiesTest {

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
    fun `a server with inbox tabs classifies before anything is cached`() = runTest {
        database.seedTabs()

        assertTrue(database.classifies(testAccountKey))
    }

    @Test
    fun `an older server classifies once a classified conversation is cached`() = runTest {
        assertFalse(database.classifies(testAccountKey))

        database.seedThread("t1", category = MailCategory.PROMOTIONS.wire)

        assertTrue(database.classifies(testAccountKey))
    }
}
