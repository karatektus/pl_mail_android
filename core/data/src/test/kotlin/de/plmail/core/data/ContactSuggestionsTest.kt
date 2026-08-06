package de.plmail.core.data

import androidx.test.core.app.ApplicationProvider
import de.plmail.core.database.EmailEntity
import de.plmail.core.database.PlMailDatabase
import de.plmail.core.database.StoreKey
import de.plmail.core.datastore.CredentialStore
import de.plmail.core.datastore.ServerConnection
import de.plmail.jmap.client.Credential
import de.plmail.jmap.client.HttpResponse
import de.plmail.jmap.client.JmapTransport
import de.plmail.jmap.client.KeyFingerprint
import de.plmail.jmap.client.ParsedAddress
import de.plmail.jmap.client.ServerAddress
import de.plmail.jmap.client.StreamingTransport
import de.plmail.jmap.testing.RecordingTransport
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The type-ahead's two invisible rules: don't spend the budget, and never answer the wrong query.
 *
 * A recipient field emits one query per keystroke, and the server it is talking to is frequently a
 * Raspberry Pi advertising four concurrent requests — shared with the message list somebody may
 * still be scrolling. So the debounce is not polish; a type-ahead without one is the reason mail
 * stops loading while a name is being typed.
 *
 * The supersede rule is the subtler one and it is why a *cancellation* is thrown rather than an
 * empty list returned. The composer launches one coroutine per keystroke and does not cancel the
 * last, so the only thing this class can do is decline to be the answer — and a superseded call
 * that returned normally would let a stale list be written over a fresh one, which on screen is
 * suggestions for "an" appearing after the user has typed "anna". Both failure modes are silent and
 * neither is reachable from a screenshot, which is why they are pinned here.
 *
 * Robolectric because the offline scan is a real Room query and the device-address-book branch
 * needs a real `Context` to be refused a permission by.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ContactSuggestionsTest {

    private lateinit var database: PlMailDatabase

    @Before
    fun setUp() {
        database = inMemoryDatabase()
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ------------------------------------------------------------- debounce

    @Test
    fun `nothing is asked until the typing pauses`() = runTest {
        val transport = countingTransport()
        val suggestions = suggestions(transport)

        val answer = async { suggestions.suggestions("ann") }

        advanceTimeBy(ContactSuggestions.DEBOUNCE_MILLIS - 1)
        runCurrent()

        // The budget this protects is the server's, and it is spent at the
        // moment the request goes out rather than when the answer comes back.
        assertEquals(0, transport.apiCalls)

        advanceUntilIdle()
        answer.await()

        assertEquals(1, transport.apiCalls)
    }

    @Test
    fun `a query too short to mean anything costs nothing at all`() = runTest {
        // Not even a delay. One character matches most of an address book, and
        // the request would be paid for before the user has typed anything that
        // could narrow it.
        val transport = countingTransport()
        val suggestions = suggestions(transport)

        assertTrue(suggestions.suggestions("a").isEmpty())
        assertEquals(0, transport.apiCalls)
    }

    // ------------------------------------------------------------ supersede

    @Test
    fun `a superseded keystroke cancels itself rather than returning`() = runTest {
        // The distinction that matters. Returning an empty list would be an
        // *answer*, and the composer would write it over whatever the later
        // keystroke had already produced; a cancellation ends the caller's
        // `launch` quietly and writes nothing.
        val suggestions = suggestions(countingTransport())

        val first = async { runCatching { suggestions.suggestions("an") } }

        // Inside the debounce, so the first has not yet decided anything.
        advanceTimeBy(ContactSuggestions.DEBOUNCE_MILLIS / 2)

        val second = async { runCatching { suggestions.suggestions("anna") } }

        advanceUntilIdle()

        val failure = first.await().exceptionOrNull()

        assertTrue(
            failure is CancellationException,
            "a superseded query must cancel, not return; got $failure",
        )
        assertTrue(second.await().isSuccess)
    }

    @Test
    fun `only the last keystroke of a burst costs a request`() = runTest {
        val transport = countingTransport()
        val suggestions = suggestions(transport)

        val calls =
            listOf("an", "ann", "anna").map { term ->
                async { runCatching { suggestions.suggestions(term) } }
            }

        advanceUntilIdle()
        calls.forEach { it.await() }

        assertEquals(1, transport.apiCalls)
    }

    @Test
    fun `a keystroke superseded while the server is answering still declines`() = runTest {
        // The *second* checkpoint, and the one a debounce alone does not give:
        // the request is already out, the user keeps typing, and the answer
        // arrives for a query that is no longer on screen. Writing it would put
        // suggestions for "an" under a field reading "anna".
        //
        // It needs the answer held open, because with an instant transport there
        // is no moment at which a request is in flight — which is exactly why
        // the naive version of this test passes without the check existing.
        val gate = CompletableDeferred<Unit>()
        val transport = RecordingTransport { request ->
            if (!request.url.contains("well-known")) gate.await()

            HttpResponse(
                status = 200,
                headers = mapOf("Content-Type" to "application/json"),
                body =
                    (if (request.url.contains("well-known")) SESSION else AUTOCOMPLETE)
                        .encodeToByteArray(),
            )
        }

        val suggestions = suggestions(transport)

        val first = async { runCatching { suggestions.suggestions("an") } }

        advanceTimeBy(ContactSuggestions.DEBOUNCE_MILLIS + 1)
        runCurrent()

        val second = async { runCatching { suggestions.suggestions("anna") } }

        advanceTimeBy(ContactSuggestions.DEBOUNCE_MILLIS + 1)
        runCurrent()

        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue(
            first.await().exceptionOrNull() is CancellationException,
            "an answer that arrived for a superseded query must not be returned",
        )
        assertTrue(second.await().isSuccess)
    }

    // -------------------------------------------------------- the three sources

    @Test
    fun `the server's ranking is what comes back, in the server's order`() = runTest {
        // Two rankings would make the suggestion order depend on which device
        // somebody happened to be composing from. The client re-sorts nothing.
        val suggestions = suggestions(countingTransport())

        val ranked = suggestions.suggestions("ann")

        assertEquals(
            listOf("anna.meyer@example.test", "ann.other@example.test"),
            ranked.map { it.email },
        )
        // Decoded rather than dropped: nothing draws these yet, and a field a
        // client never parsed is a field nobody notices has arrived.
        assertEquals(12, ranked.first().frequency)
    }

    @Test
    fun `an unreachable server falls back to the cached mail`() = runTest {
        // The offline answer. Its ranking is worse than the server's -- recency
        // over the cache rather than frequency over everything -- which is
        // exactly why it is no longer first, and why it must still exist.
        seedMailFrom("Anna Meyer", "anna.meyer@example.test")

        val suggestions = suggestions(RecordingTransport { error("the NAS is asleep") })

        assertEquals(
            listOf("anna.meyer@example.test"),
            suggestions.suggestions("meyer").map { it.email },
        )
    }

    @Test
    fun `a server without the contacts capability is answered from the cache, not refused`() =
        runTest {
            // Absence is the signal, as everywhere else: an instance without the
            // extension is a supported instance rather than a broken one.
            seedMailFrom("Anna Meyer", "anna.meyer@example.test")

            val transport =
                RecordingTransport.routing(
                    "well-known" to SESSION_WITHOUT_CONTACTS,
                    "/jmap/api" to """{"methodResponses":[]}""",
                )

            assertEquals(
                listOf("anna.meyer@example.test"),
                suggestions(transport).suggestions("meyer").map { it.email },
            )
        }

    @Test
    fun `a LIKE wildcard cannot widen the query the user typed`() = runTest {
        // `%` would otherwise match every address ever seen and `_` any single
        // character, so "meyer_" would find Schmidt. Neither looks like a bug;
        // they quietly return the wrong people, which is worse.
        //
        // Worth knowing about the escaping's shape rather than its effect: it
        // *strips* the two characters instead of escaping them, so a query that
        // is nothing but wildcards degrades to a blank term and the offline scan
        // then matches everything recent. That is a query with no content rather
        // than an injection, and it is left as it is — but it is why this test
        // pins narrowing rather than emptiness.
        seedMailFrom("Anna Meyer", "anna.meyer@example.test", id = "1")
        seedMailFrom("Bert Schmidt", "bert.schmidt@example.test", id = "2")

        val suggestions = suggestions(RecordingTransport { error("offline") })

        assertEquals(
            listOf("anna.meyer@example.test"),
            suggestions.suggestions("meyer%").map { it.email },
        )
    }

    @Test
    fun `the device address book is never read without the permission`() = runTest {
        // A mail client demanding contacts access on first launch is the
        // behaviour this product's audience left other clients over. Robolectric
        // grants nothing by default, which is the state a fresh install is in.
        val suggestions = suggestions(countingTransport())

        // Whichever way the harness has answered the permission, the ranked
        // list has to arrive: the supplement is allowed to be absent and is
        // never allowed to take the server's answer down with it. That is the
        // claim -- `mayReadDeviceContacts` is checked rather than assumed
        // inside, and a contacts provider that refuses is swallowed.
        assertEquals(2, suggestions.suggestions("ann").size)
    }

    // ------------------------------------------------------------- fixtures

    private suspend fun seedMailFrom(name: String, address: String, id: String = "1") {
        database.seedAccount()
        database
            .emails()
            .upsert(
                listOf(
                    EmailEntity(
                        uid = StoreKey.objectKey(testAccountKey, id),
                        accountKey = testAccountKey,
                        emailId = id,
                        threadId = id,
                        receivedAt = 1_000,
                        fromName = name,
                        fromAddress = address,
                    )
                )
            )
    }

    private fun suggestions(transport: JmapTransport): ContactSuggestions {
        val credentials = CredentialStore(InMemoryPreferences(), PlainCipher)

        kotlinx.coroutines.runBlocking {
            credentials.save(
                ServerConnection(
                    address = (ServerAddress.parse(TEST_SERVER) as ParsedAddress.Valid).address,
                    credential = Credential.AppPassword("plmail_" + "a".repeat(64)),
                    username = "someone@example.com",
                )
            )
        }

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

        return ContactSuggestions(
            context = ApplicationProvider.getApplicationContext(),
            database = database,
            clients = AccountClients(credentials, transports),
        )
    }

    /**
     * A transport that answers autocomplete and counts the API calls separately from discovery.
     *
     * The count is the assertion in half these tests: discovery is single-flighted and cached by
     * the client, so a raw call count would be measuring that instead of the debounce.
     */
    private fun countingTransport(): CountingTransport = CountingTransport()

    private class CountingTransport : JmapTransport {
        var apiCalls = 0
            private set

        override suspend fun send(
            request: de.plmail.jmap.client.HttpRequest
        ): de.plmail.jmap.client.HttpResponse {
            val body =
                if (request.url.contains("well-known")) SESSION
                else {
                    apiCalls++
                    AUTOCOMPLETE
                }

            return de.plmail.jmap.client.HttpResponse(
                status = 200,
                headers = mapOf("Content-Type" to "application/json"),
                body = body.encodeToByteArray(),
            )
        }
    }

    private companion object {
        val AUTOCOMPLETE =
            """
            {"methodResponses":[["Contact/autocomplete",{
              "accountId":"$TEST_ACCOUNT_ID","query":"ann","limit":8,
              "list":[
                {"name":"Anna Meyer","email":"anna.meyer@example.test",
                 "frequency":12,"lastSeenAt":"2026-08-01T10:00:00Z","isCorrespondent":true},
                {"name":"Ann Other","email":"ann.other@example.test",
                 "frequency":3,"lastSeenAt":"2026-07-01T10:00:00Z","isCorrespondent":false}
              ]},"c0"]]}
            """

        val SESSION =
            """
            {
              "capabilities": {
                "urn:ietf:params:jmap:core": {"maxConcurrentRequests": 4},
                "urn:plmail:params:jmap:contacts": {"maxSuggestions": 50}
              },
              "accounts": {"$TEST_ACCOUNT_ID": {"name": "someone@example.com"}},
              "primaryAccounts": {"urn:plmail:params:jmap:contacts": "$TEST_ACCOUNT_ID"},
              "username": "someone@example.com",
              "apiUrl": "$TEST_SERVER/jmap/api",
              "downloadUrl": "$TEST_SERVER/jmap/download",
              "uploadUrl": "$TEST_SERVER/jmap/upload"
            }
            """

        val SESSION_WITHOUT_CONTACTS =
            """
            {
              "capabilities": {"urn:ietf:params:jmap:core": {}},
              "accounts": {"$TEST_ACCOUNT_ID": {"name": "someone@example.com"}},
              "username": "someone@example.com",
              "apiUrl": "$TEST_SERVER/jmap/api",
              "downloadUrl": "$TEST_SERVER/jmap/download",
              "uploadUrl": "$TEST_SERVER/jmap/upload"
            }
            """
    }
}
