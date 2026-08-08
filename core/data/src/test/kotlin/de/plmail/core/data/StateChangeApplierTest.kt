package de.plmail.core.data

import de.plmail.core.database.AccountEntity
import de.plmail.core.database.PlMailDatabase
import de.plmail.core.database.StoreKey
import de.plmail.jmap.client.HttpResponse
import de.plmail.jmap.testing.RecordingTransport
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What an announcement is worth acting on.
 *
 * A Web Push payload and an EventSource `state` event are the same JMAP `StateChange`, and the
 * comparison this class performs is the whole reason it is worth having: an announcement says where
 * the server's Email state *now* is, the account row says where this device's cursor is, and equal
 * means the sync it would start has already been made. Without it a chatty stream — one bulk label
 * edit in the browser is dozens of events within a few seconds — becomes one `Email/changes` per
 * event against a machine that advertises four concurrent requests and is frequently a Raspberry
 * Pi.
 *
 * Counted in requests rather than in calls to a double, deliberately. "Runs no sync" is a claim
 * about what reaches the server, and a mocked `DeltaSync` would let the two agree about a method
 * name while disagreeing about whether anybody was asked anything.
 */
@RunWith(RobolectricTestRunner::class)
// sdk = 36, for the reason `core/ui`'s screenshot tests give.
@Config(sdk = [36])
class StateChangeApplierTest {

    private lateinit var database: PlMailDatabase

    private val second = StoreKey.account(TEST_SERVER, "14")

    @Before
    fun open() {
        database = inMemoryDatabase()
    }

    @After
    fun close() {
        database.close()
    }

    @Test
    fun `an announcement this device has already caught up with asks the server nothing`() =
        runTest {
            database.seedAccount(emailState = "s5")

            val transport = quiet()
            applier(transport).apply(mapOf(TEST_ACCOUNT_ID to mapOf("Email" to "s5")))

            assertEquals(0, transport.calls, "the changes call would answer with an empty list")
        }

    @Test
    fun `an announcement ahead of the stored cursor syncs exactly the accounts it names`() =
        runTest {
            database.seedAccount(emailState = "s5")
            seedSecondAccount(emailState = "s5")

            val transport = quiet()
            applier(transport).apply(mapOf("14" to mapOf("Email" to "s9")))

            val api = transport.requests.filter { it.url.endsWith("/jmap/api") }
            assertEquals(1, api.size, "one account was named, so one account is synced")

            val body = api.single().body!!.decodeToString()
            assertTrue(body.contains("\"accountId\":\"14\""), body)
            assertTrue(body.contains("\"sinceState\":\"s5\""), "resumed from this device's cursor")
        }

    /**
     * Only the mail cursor may skip.
     *
     * A `Mailbox` or `Thread` state moving on its own is rare and cheap to act on, and refusing to
     * sync for it would leave a label renamed in the browser waiting for the next unrelated
     * message.
     */
    @Test
    fun `an announcement carrying no Email token is acted on anyway`() = runTest {
        database.seedAccount(emailState = "s5")

        val transport = quiet()
        applier(transport).apply(mapOf(TEST_ACCOUNT_ID to mapOf("Mailbox" to "m2")))

        assertTrue(transport.requests.any { it.url.endsWith("/jmap/api") })
    }

    // -- helpers -----------------------------------------------------------

    private suspend fun applier(transport: RecordingTransport) =
        StateChangeApplier(
            database,
            syncStack(database, transport),
            // Real rather than a double, so the request count these tests assert
            // on includes anything the prefetch adds. It finds nothing to fetch
            // here — the canned syncs store no messages — which is the point:
            // "nothing missing a body" must cost no request.
            bodyPrefetcher(database, transport),
        )

    /** A server with nothing to report, so the only thing under test is whether it was asked. */
    private fun quiet(): RecordingTransport = RecordingTransport { request ->
        val body =
            if (request.url.endsWith("/.well-known/jmap")) TEST_SESSION
            else
                """
                {"sessionState":"s","methodResponses":[
                  ["Email/changes",
                   {"accountId":"1","oldState":"s5","newState":"s9","hasMoreChanges":false,
                    "created":[],"updated":[],"destroyed":[]},"c0"]]}
                """

        HttpResponse(200, mapOf("Content-Type" to "application/json"), body.encodeToByteArray())
    }

    private suspend fun seedSecondAccount(emailState: String?) {
        database
            .accounts()
            .upsert(
                listOf(
                    AccountEntity(
                        uid = second,
                        serverId = TEST_SERVER,
                        accountId = "14",
                        name = "other@example.com",
                        emailState = emailState,
                        sortIndex = 1,
                    )
                )
            )
    }
}
