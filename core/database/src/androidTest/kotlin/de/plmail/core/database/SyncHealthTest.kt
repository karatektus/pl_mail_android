package de.plmail.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What an account row remembers about its own health.
 *
 * Two columns, two statements, and the separation is the entire content of this file. The first
 * version of this wrote both in one query with nullable parameters, so recording a *failure* passed
 * `at = null` and erased the timestamp of the last sync that had worked — deleting the one fact the
 * diagnostics screen exists to show.
 *
 * That failure is completely silent. Nothing throws, the screen renders, and what it renders is
 * "never synced" for a server that synced this morning — which sends whoever is debugging it
 * looking at their credential instead of at the two hours since. So the rule gets a test rather
 * than a comment: **a failure never clears the last success.**
 */
@RunWith(AndroidJUnit4::class)
class SyncHealthTest {

    private lateinit var database: PlMailDatabase

    @Before
    fun open() {
        database =
            Room.inMemoryDatabaseBuilder(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                    PlMailDatabase::class.java,
                )
                .build()
    }

    @After
    fun close() {
        database.close()
    }

    @Test
    fun aFailureLeavesTheLastSuccessfulSyncAlone() = runBlocking {
        val accounts = database.accounts()
        accounts.upsert(listOf(account()))

        accounts.recordSyncSucceeded(UID, at = 1_000L)
        accounts.recordSyncFailed(UID, "Could not reach nas.local.")

        val stored = accounts.byUid(UID)

        assertEquals(1_000L, stored?.lastSyncedAt)
        assertEquals("Could not reach nas.local.", stored?.lastSyncError)
    }

    /**
     * And the other direction: a sync that works clears the error, because it is no longer true. An
     * error left behind after a successful sync is a screen that reports a server as broken for as
     * long as nobody looks at it again.
     */
    @Test
    fun aSuccessClearsTheErrorItFollows() = runBlocking {
        val accounts = database.accounts()
        accounts.upsert(listOf(account()))

        accounts.recordSyncFailed(UID, "Connection refused.")
        accounts.recordSyncSucceeded(UID, at = 2_000L)

        val stored = accounts.byUid(UID)

        assertEquals(2_000L, stored?.lastSyncedAt)
        assertNull(stored?.lastSyncError)
    }

    /**
     * An account that has never synced reports null rather than zero.
     *
     * Zero is a real timestamp — 1 January 1970 — and a screen formatting it would say the account
     * last synced fifty-six years ago, which reads as a bug in the clock rather than as a client
     * that has not run yet.
     */
    @Test
    fun anAccountThatHasNeverSyncedSaysSo() = runBlocking {
        database.accounts().upsert(listOf(account()))

        assertNull(database.accounts().byUid(UID)?.lastSyncedAt)
    }

    private fun account() =
        AccountEntity(
            uid = UID,
            serverId = "https://nas.local",
            accountId = "1",
            name = "someone@example.com",
        )

    private companion object {
        const val UID = "https://nas.local/1"
    }
}
