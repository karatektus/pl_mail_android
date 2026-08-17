package de.plmail.core.data

import de.plmail.core.database.PlMailDatabase
import de.plmail.core.database.StoreKey
import de.plmail.core.datastore.CredentialStore
import de.plmail.core.datastore.ServerConnection
import de.plmail.jmap.client.Credential
import de.plmail.jmap.client.JmapTransport
import de.plmail.jmap.client.KeyFingerprint
import de.plmail.jmap.client.ParsedAddress
import de.plmail.jmap.client.ServerAddress
import de.plmail.jmap.client.StreamingTransport
import de.plmail.jmap.testing.RecordingTransport
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Reporting displays back to the server, which is the half that makes the marker *shared*.
 *
 * Reading `Thread.isNew` alone would give a phone that agrees with the browser until the moment the
 * user acts, and then diverges for ever: the badge would clear on one surface and stay on the
 * other. So the interesting cases here are all about what gets sent and what does not.
 *
 * Two failures matter more than the rest, and both are silent. Reporting a conversation that was
 * merely *fetched* retires a marker for mail nobody has seen — the browser then never mentions it,
 * and the mail is effectively lost. Reporting the same rows on every recomposition turns a scroll
 * into a request per frame against somebody's NAS.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ShownThreadsTest {

    private lateinit var database: PlMailDatabase

    @Before
    fun open() {
        database = inMemoryDatabase()
    }

    @After
    fun close() {
        database.close()
    }

    /** The plain case: a row is drawn, the server is told, the marker goes. */
    @Test
    fun `a drawn conversation is reported and stops being new here`() = runTest {
        seed("t1", isNew = true)

        val transport = accepting()
        shownThreads(transport).reportNow(testAccountKey, listOf("t1"))

        assertTrue(transport.calls > 0, "nothing was sent")
        assertTrue(transport.lastBody!!.contains("Thread/set"), transport.lastBody!!)
        assertTrue(transport.lastBody!!.contains("\"isNew\":false"), transport.lastBody!!)
        assertFalse(newLocally("t1"))
    }

    /**
     * **The report is `isNew: false` and never `true`.**
     *
     * The server refuses the other direction, and a client that sent it would be asking to
     * un-display something. Pinned on the wire because that is where it would be got wrong.
     */
    @Test
    fun `the report only ever retires`() = runTest {
        seed("t1", isNew = true)

        val transport = accepting()
        shownThreads(transport).reportNow(testAccountKey, listOf("t1"))

        assertFalse(transport.lastBody!!.contains("\"isNew\":true"), transport.lastBody!!)
    }

    /**
     * **A list redrawing the same rows must not talk to the server again.**
     *
     * A row recomposes for a swipe offset, a selection, a theme change. Without narrowing against
     * the cache first, a scroll becomes one request per frame against a machine that advertises
     * four concurrent connections and is frequently a Raspberry Pi.
     */
    @Test
    fun `a conversation already reported is not reported again`() = runTest {
        seed("t1", isNew = true)

        val transport = accepting()
        val reporter = shownThreads(transport)

        reporter.reportNow(testAccountKey, listOf("t1"))

        val afterFirst = transport.calls

        repeat(5) { reporter.reportNow(testAccountKey, listOf("t1")) }

        assertEquals(afterFirst, transport.calls)
    }

    /**
     * **Mail the server does not call new is never reported.**
     *
     * This is the guard against retiring a marker for something nobody saw. A conversation the
     * cache holds but the server has already retired — or one that was never new — must produce no
     * request, however often the list draws it.
     */
    @Test
    fun `a conversation that is not new is never reported`() = runTest {
        seed("t1", isNew = false)

        val transport = accepting()
        shownThreads(transport).reportNow(testAccountKey, listOf("t1"))

        assertEquals(0, transport.calls)
    }

    /** Several rows at once become one request, because that is how a page arrives. */
    @Test
    fun `a page of rows is one request rather than one each`() = runTest {
        (1..5).forEach { seed("t$it", isNew = true) }

        val transport = accepting()
        shownThreads(transport).reportNow(testAccountKey, (1..5).map { "t$it" })

        // One session fetch plus one API call: the point is that five rows did
        // not become five API calls.
        assertTrue(transport.calls <= 2, "sent ${transport.calls} requests")
        (1..5).forEach { assertFalse(newLocally("t$it"), "t$it still new") }
    }

    /** Nothing to say, nothing sent — the common case on every list that is up to date. */
    @Test
    fun `an empty report touches nothing`() = runTest {
        val transport = accepting()
        shownThreads(transport).reportNow(testAccountKey, emptyList())

        assertEquals(0, transport.calls)
    }

    /**
     * A server that cannot be reached must not leave the row claiming to be new for ever, nor
     * throw: the marker is not something anybody is waiting on, and the report is idempotent, so
     * clearing locally and letting the next draw try again is the cheap correct answer.
     */
    @Test
    fun `a failed report does not throw`() = runTest {
        seed("t1", isNew = true)

        val transport = RecordingTransport { error("the NAS is asleep") }

        shownThreads(transport).reportNow(testAccountKey, listOf("t1"))
    }

    /**
     * Suspending rather than wrapping the save in `runBlocking`, which is the idiom elsewhere in
     * this suite: there is a real coroutine context here to save in, and borrowing one is cheaper
     * than nesting a blocking event loop inside the test's own.
     */
    private suspend fun TestScope.shownThreads(transport: JmapTransport): ShownThreads =
        ShownThreads(database = database, clients = clients(transport), scope = this)

    private suspend fun clients(transport: JmapTransport): AccountClients {
        val credentials = CredentialStore(InMemoryPreferences(), PlainCipher)

        credentials.save(
            ServerConnection(
                address = (ServerAddress.parse(TEST_SERVER) as ParsedAddress.Valid).address,
                credential = Credential.AppPassword("plmail_" + "a".repeat(64)),
                username = "someone@example.com",
            )
        )

        val transports =
            object : TransportFactory {
                override fun create(
                    address: ServerAddress,
                    pinned: KeyFingerprint?,
                ): JmapTransport = transport

                override fun createStreaming(
                    address: ServerAddress,
                    pinned: KeyFingerprint?,
                ): StreamingTransport = error("no stream is opened on this path")
            }

        return AccountClients(credentials, transports)
    }

    private suspend fun seed(threadId: String, isNew: Boolean) {
        database.seedAccount()
        database.seedThread(threadId, isNew = isNew)
    }

    private suspend fun newLocally(threadId: String): Boolean =
        database.threads().byUid(StoreKey.objectKey(testAccountKey, threadId))?.isNew ?: false

    private fun accepting(): RecordingTransport =
        RecordingTransport.routing(
            "/.well-known/jmap" to TEST_SESSION,
            "/jmap/api" to """{"methodResponses":[["Thread/set",{"updated":{}},"c0"]]}""",
        )
}
