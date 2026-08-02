package de.plmail.core.data

import de.plmail.core.database.PlMailDatabase
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The smallest test in this suite, guarding the least visible bug in it.
 *
 * `recordEmailState` is called from every page load, and it used to write the column
 * unconditionally. A page reports the Email state its answer was read at, which is *now* — so
 * scrolling deep into a list stepped the delta cursor over everything that had changed since the
 * last `Email/changes`, and those changes could then never be reported. Nothing failed, nothing was
 * logged, and the only symptom was mail that quietly did not arrive on a phone that had recently
 * been scrolled. The documentation on the method had said "only ever moves from absent to set" for
 * as long as the code had not done it.
 *
 * It is also what makes `StateChangeApplier`'s comparison safe. While the stored token meant
 * "wherever the last page happened to be read at" it could sit *ahead* of changes that had never
 * been fetched, so an applier skipping on equality would have answered "up to date" for an account
 * that was missing mail.
 */
@RunWith(RobolectricTestRunner::class)
// sdk = 36, for the reason `core/ui`'s screenshot tests give.
@Config(sdk = [36])
class EmailStateCursorTest {

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
    fun `the first page an account is paged gives it its cursor`() = runTest {
        database.seedAccount(emailState = null)

        MailRepository(database).recordEmailState(testAccountKey, "s7")

        assertEquals("s7", stored())
    }

    /** The whole point. A later page must not step the cursor over unfetched changes. */
    @Test
    fun `a later page does not move a cursor delta sync already owns`() = runTest {
        database.seedAccount(emailState = "s7")

        MailRepository(database).recordEmailState(testAccountKey, "s99")

        assertEquals("s7", stored(), "delta sync owns this column once it is set")
    }

    /** `""` is not a starting point; `Email/changes` cannot answer from it. */
    @Test
    fun `a blank state is not a cursor`() = runTest {
        database.seedAccount(emailState = null)

        MailRepository(database).recordEmailState(testAccountKey, "  ")

        assertNull(stored())
    }

    private suspend fun stored(): String? = database.accounts().byUid(testAccountKey)?.emailState
}
