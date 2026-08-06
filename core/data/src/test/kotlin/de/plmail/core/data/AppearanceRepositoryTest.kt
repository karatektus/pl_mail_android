package de.plmail.core.data

import de.plmail.core.datastore.AppearanceStore
import de.plmail.core.datastore.CredentialStore
import de.plmail.core.datastore.RemoteAppearance
import de.plmail.core.datastore.ServerConnection
import de.plmail.core.datastore.StoredAppearance
import de.plmail.jmap.client.Credential
import de.plmail.jmap.client.HttpResponse
import de.plmail.jmap.client.JmapTransport
import de.plmail.jmap.client.KeyFingerprint
import de.plmail.jmap.client.ParsedAddress
import de.plmail.jmap.client.ServerAddress
import de.plmail.jmap.client.StreamingTransport
import de.plmail.jmap.testing.RecordingTransport
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The flush loop: what this device owes the server, and what happens when the answer is not "yes".
 *
 * Appearance is the one repository where the *server* is the source and the device holds overrides,
 * and everything hard about it is in three answers that are not a plain success:
 *
 * - **`stateMismatch`.** Somebody moved a slider in a browser tab between the read and the write.
 *   The retry has to be a re-read rather than a blind resend, and the user's own pending change has
 *   to survive it — their tap is the newer intent, and everything they did not touch takes the
 *   browser's new value. Get this wrong in the obvious direction and a theme changed on a laptop is
 *   silently reverted by a phone that was mid-write.
 * - **A refusal.** A value this build believes in and this server does not. The override has to be
 *   *dropped*, because keeping it re-sends the same refused patch on every sync for ever.
 * - **No server at all.** The override stays pending on disk, exactly as an offline change does,
 *   and nothing throws — a theme is not worth failing a sync over.
 *
 * None of that was covered. It runs under Robolectric because `AppearanceStore` is DataStore and
 * `AccountClients` is the real one over a scripted transport; nothing here needs a device.
 */
@RunWith(RobolectricTestRunner::class)
// sdk = 36 for the reason the other Robolectric suites here give: a library
// module inherits compileSdk 37 and Robolectric has no Android 37 to emulate.
@Config(sdk = [36])
class AppearanceRepositoryTest {

    // ------------------------------------------------ the precedence function

    @Test
    fun `a local override wins over the server, per property`() {
        // One line of policy that the whole feature rests on, and the reason it
        // is a function: the app has to re-theme under the finger with no round
        // trip, so an unconfirmed local value beats the server's -- but only for
        // the property that was touched, or one tap would flatten the other
        // three back to whatever this device last read.
        val settings =
            resolve(
                local = StoredAppearance(theme = "nord"),
                remote =
                    RemoteAppearance(
                        theme = "solar",
                        layout = "boxed",
                        density = "cosy",
                        paneAlpha = "0.8",
                    ),
            )

        assertEquals("nord", settings.theme)
        assertEquals("boxed", settings.layout)
        assertEquals("cosy", settings.density)
        assertEquals("0.8", settings.paneAlpha)
    }

    @Test
    fun `the two Android-only switches are never the server's to answer`() {
        // Material You and reduce-transparency are answers to questions the
        // server does not ask, so they come from the local record whatever the
        // remote one holds -- and a `false` there must not read as "unset".
        val settings =
            resolve(
                local = StoredAppearance(dynamicColor = true, reduceTransparency = true),
                remote = RemoteAppearance(theme = "solar"),
            )

        assertTrue(settings.dynamicColor)
        assertTrue(settings.reduceTransparency)
        assertEquals("solar", settings.theme)
    }

    // ------------------------------------------------------------ the flush

    @Test
    fun `a stateMismatch is retried once, re-read, and the user's own change survives it`() =
        runTest {
            // The case the retry exists for. This device wants Nord; a browser
            // tab has meanwhile set Solar *and* the boxed layout and moved the
            // state on. The re-read has to take the browser's layout and the
            // user's theme -- their tap is the newer intent, and the layout is
            // not something they touched.
            val store = AppearanceStore(InMemoryPreferences())
            val transport = scripted {
                mismatchThenAccept(
                    rereadTheme = "solar",
                    rereadLayout = "boxed",
                    acceptedState = "s9",
                )
            }

            val repository = repository(store, transport)

            store.setRemote(RemoteAppearance(theme = "light", state = "s1"))
            repository.setTheme("nord")

            val settings = repository.settings.first()

            assertEquals("nord", settings.theme)
            assertEquals("boxed", settings.layout)

            // Two `AppearanceSet`s and one `AppearanceGet` between them: the
            // retry is a re-read rather than the same patch sent twice.
            assertEquals(listOf("set", "get", "set"), transport.methodNames())

            // Confirmed, so the override is gone and the app now reads the
            // server's copy -- which is what makes a later browser change
            // visible at all.
            assertNull(store.appearance.first().theme)
            assertEquals("nord", store.remote.first().theme)
        }

    @Test
    fun `a second stateMismatch gives up and leaves the change pending`() = runTest {
        // One retry, not a loop. A server whose state is moving faster than this
        // client can read it is a server the next sync should try, not one to
        // hold a coroutine against.
        val store = AppearanceStore(InMemoryPreferences())
        val transport = scripted { alwaysMismatching() }
        val repository = repository(store, transport)

        store.setRemote(RemoteAppearance(theme = "light", state = "s1"))
        repository.setTheme("nord")

        // Still pending on disk, which is the same place an offline change
        // waits, and still on screen, because the store is the screen's preview.
        assertEquals("nord", store.appearance.first().theme)
        assertEquals("nord", repository.settings.first().theme)
        assertEquals(listOf("set", "get", "set"), transport.methodNames())
    }

    @Test
    fun `a refused value drops the override rather than resending it for ever`() = runTest {
        // A theme this build knows and this server does not. Keeping the
        // override would re-send the same refused patch on every sync until
        // somebody cleared the app's data; dropping it lets the next refresh put
        // the server's own value back on screen, which is the honest answer to
        // "that theme does not exist here".
        val store = AppearanceStore(InMemoryPreferences())
        val transport = scripted { refusing() }
        val repository = repository(store, transport)

        store.setRemote(RemoteAppearance(theme = "light", state = "s1"))
        repository.setTheme("paper")

        assertNull(store.appearance.first().theme)
        assertEquals("light", repository.settings.first().theme)
    }

    @Test
    fun `what the server decided differently is what gets stored`() = runTest {
        // Clamps are applied from the *answer*, never from the request: a slider
        // pulled into range, or the knob preset a change of layout seeds. A
        // client that stored what it sent would draw a value the server is not
        // holding.
        val store = AppearanceStore(InMemoryPreferences())
        val transport = scripted { clamping(reportedAlpha = 0.6f) }
        val repository = repository(store, transport)

        store.setRemote(RemoteAppearance(theme = "light", state = "s1"))
        repository.setPaneAlpha(0.2f)

        assertEquals("0.6", store.remote.first().paneAlpha)
        assertEquals("0.6", repository.settings.first().paneAlpha)
    }

    @Test
    fun `an unreachable server leaves the choice pending and does not throw`() = runTest {
        // The offline path, and it is the same path as a NAS that is asleep. The
        // screen is its own preview, so the store changed before the finger
        // lifted; all that is left is for nothing to blow up.
        val store = AppearanceStore(InMemoryPreferences())
        val transport = RecordingTransport { error("the server is asleep") }
        val repository = repository(store, transport)

        repository.setTheme("nord")

        assertEquals("nord", store.appearance.first().theme)
        assertEquals("nord", repository.settings.first().theme)
    }

    @Test
    fun `refresh on a server without the capability writes nothing`() = runTest {
        // Absence is the signal, as everywhere else: an instance without the
        // extension is a supported instance. A refresh that stored an empty
        // remote record would erase the user's own local choices' backing.
        val store = AppearanceStore(InMemoryPreferences())
        val transport = RecordingTransport.alwaysReturning(SESSION_WITHOUT_APPEARANCE)
        val repository = repository(store, transport)

        store.setRemote(RemoteAppearance(theme = "nord", state = "s1"))
        repository.refresh()

        assertEquals("nord", store.remote.first().theme)
    }

    @Test
    fun `the session hint fills gaps and never overwrites the authoritative read`() = runTest {
        // Discovery has already happened before anything is drawn, so the
        // capability's compact read is a correct first paint for free. Gaps
        // only: the stored record came from `Appearance/get` and the hint must
        // not put three of its four fields over it.
        val store = AppearanceStore(InMemoryPreferences())
        val transport = scripted { getting(theme = "dusk", layout = "flat") }
        val repository = repository(store, transport)

        store.setRemote(RemoteAppearance(theme = "nord", state = "s1"))
        repository.refresh()

        // The hint says solar/boxed/cosy; the stored theme was already nord, so
        // only the two it had nothing for are primed -- and then the real
        // `Appearance/get` answer replaces the lot.
        assertEquals("dusk", store.remote.first().theme)
        assertEquals("flat", store.remote.first().layout)
    }

    // ------------------------------------------------------------- fixtures

    private fun repository(
        store: AppearanceStore,
        transport: JmapTransport,
    ): AppearanceRepository {
        val credentials = CredentialStore(InMemoryPreferences(), PlainCipher)
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

        kotlinx.coroutines.runBlocking {
            credentials.save(
                ServerConnection(
                    address = (ServerAddress.parse(TEST_SERVER) as ParsedAddress.Valid).address,
                    credential = Credential.AppPassword("plmail_" + "a".repeat(64)),
                    username = "someone@example.com",
                )
            )
        }

        return AppearanceRepository(store, AccountClients(credentials, transports))
    }

    /**
     * A transport that serves discovery and then reads a script for the API calls.
     *
     * Written as a queue of bodies rather than a router because the *order* is what several of
     * these tests are about — set, then get, then set is the retry, and set, set is the bug.
     */
    private fun scripted(script: MutableList<String>.() -> Unit): RecordingTransport {
        val bodies = mutableListOf<String>().apply(script)
        var index = 0

        return RecordingTransport { request ->
            val body =
                if (request.url.contains("well-known")) SESSION
                else bodies.getOrNull(index++) ?: error("no scripted response left")

            HttpResponse(
                status = 200,
                headers = mapOf("Content-Type" to "application/json"),
                body = body.encodeToByteArray(),
            )
        }
    }

    /** The API method names the client actually sent, in order. */
    private fun RecordingTransport.methodNames(): List<String> =
        requests
            .filterNot { it.url.contains("well-known") }
            .map { request ->
                val body = request.body?.decodeToString().orEmpty()

                when {
                    body.contains("Appearance/set") -> "set"
                    body.contains("Appearance/get") -> "get"
                    else -> "other"
                }
            }

    private fun MutableList<String>.mismatchThenAccept(
        rereadTheme: String,
        rereadLayout: String,
        acceptedState: String,
    ) {
        add(MISMATCH)
        add(getResult(theme = rereadTheme, layout = rereadLayout, state = "s7"))
        add(setResult(state = acceptedState))
    }

    private fun MutableList<String>.alwaysMismatching() {
        add(MISMATCH)
        add(getResult(theme = "light", layout = "flat", state = "s7"))
        add(MISMATCH)
    }

    private fun MutableList<String>.refusing() {
        add(
            """
            {"methodResponses":[["Appearance/set",{"accountId":null,"oldState":"s1",
             "newState":"s1","updated":{},
             "notUpdated":{"singleton":{"type":"invalidProperties",
                                        "description":"theme must be one of …"}}},"c0"]]}
            """
        )
    }

    private fun MutableList<String>.clamping(reportedAlpha: Float) {
        add(
            """
            {"methodResponses":[["Appearance/set",{"accountId":null,"oldState":"s1",
             "newState":"s9","updated":{"singleton":{"paneAlpha":$reportedAlpha}},
             "notUpdated":{}},"c0"]]}
            """
        )
    }

    private fun MutableList<String>.getting(theme: String, layout: String) {
        add(getResult(theme = theme, layout = layout, state = "s7"))
    }

    private fun getResult(theme: String, layout: String, state: String) =
        """
        {"methodResponses":[["Appearance/get",{"accountId":null,"state":"$state",
         "list":[{"id":"singleton","theme":"$theme","layout":"$layout",
                  "density":"comfortable","paneAlpha":1.0}],"notFound":[]},"c0"]]}
        """

    private fun setResult(state: String) =
        """
        {"methodResponses":[["Appearance/set",{"accountId":null,"oldState":"s1",
         "newState":"$state","updated":{"singleton":null},"notUpdated":{}},"c0"]]}
        """

    private companion object {
        /** The method-level error `ifInState` loses to, which is not a per-object refusal. */
        const val MISMATCH = """{"methodResponses":[["error",{"type":"stateMismatch"},"c0"]]}"""

        val SESSION =
            """
            {
              "capabilities": {
                "urn:ietf:params:jmap:core": {},
                "urn:plmail:params:jmap:appearance": {
                  "theme":"solar","layout":"boxed","accent":"blue","density":"cosy"
                }
              },
              "accounts": {"$TEST_ACCOUNT_ID": {"name": "someone@example.com"}},
              "username": "someone@example.com",
              "apiUrl": "$TEST_SERVER/jmap/api",
              "downloadUrl": "$TEST_SERVER/jmap/download",
              "uploadUrl": "$TEST_SERVER/jmap/upload"
            }
            """

        val SESSION_WITHOUT_APPEARANCE =
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
