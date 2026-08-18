package de.plmail.core.data

import de.plmail.core.datastore.AppearanceStore
import de.plmail.core.datastore.CredentialStore
import de.plmail.core.datastore.DensityOverride
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
 * Appearance, which is the one repository where the server is the source and the device layers on
 * top of it — and the one that must never write.
 *
 * Two halves. The first is [resolve], one line of policy the whole feature rests on: a local choice
 * outranks the account's value *per property*, so one tap cannot flatten the three settings beside
 * it back to whatever this device last read, and a `DensityOverride` holding a null is a deliberate
 * "follow the overall density" rather than an absence.
 *
 * The second is the direction of travel, and it is the half that had a bug in it. The app used to
 * push `Appearance/set`: a theme chosen on a phone rewrote the browser's, complete with a
 * `stateMismatch` retry to make sure it landed. That path is gone rather than gated — see
 * `AppearanceRepository` — so what is asserted here is an absence, against the recorded requests,
 * because the store cannot tell the difference between a value that was sent and one that was not.
 *
 * Robolectric because `AppearanceStore` is DataStore and `AccountClients` is the real one over a
 * scripted transport; nothing here needs a device.
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

    @Test
    fun `a surface density that says follow is not the same as one nobody has set`() {
        // The `?:` bug the whole `DensityOverride` wrapper exists to make
        // unspellable. Both records below hold a null for the list, and they
        // mean opposite things: the local one is a user who has just tapped
        // "Follow the overall density" and the remote one is the compact
        // override they are trying to get rid of. Written `local?.wire ?: remote`
        // the tap resolves back to "compact" and the control does nothing at all.
        val cleared =
            resolve(
                local = StoredAppearance(listDensity = DensityOverride.Follow),
                remote = RemoteAppearance(listDensity = "compact", sidebarDensity = "cosy"),
            )

        assertNull(cleared.listDensity)

        // And the untouched surface beside it still takes the server's answer,
        // which is what says the clear was per-property rather than wholesale.
        assertEquals("cosy", cleared.sidebarDensity)
    }

    // ------------------------------------------------------- the one direction

    /**
     * The single most important assertion in this file.
     *
     * It is also the only promise the appearance screen makes that cannot be checked by looking at
     * the phone. The app used to push `Appearance/set`, so choosing a darker theme on a train
     * restyled the browser open on somebody's desk — and no amount of care in the patch builder
     * makes that the right behaviour, because theming a phone is not a statement about a desktop.
     *
     * Asserted with the sync **on**, which is the mode where the write existed. The off case is
     * below and is now the weaker of the two: with no write path at all, an off switch can only
     * stop a read.
     */
    @Test
    fun `choosing an appearance sends nothing, with the sync on`() = runTest {
        val store = AppearanceStore(InMemoryPreferences())

        // Deliberately empty. Anything the repository sends past discovery fails
        // the script rather than being quietly answered, so this asserts the
        // absence twice over.
        val transport = scripted {}
        val repository = repository(store, transport)

        store.setRemote(RemoteAppearance(theme = "light", state = "s1"))

        repository.setTheme("nord")
        repository.setLayout("boxed")
        repository.setPaneAlpha(0.2f)
        repository.setFontScale(1.25f)
        repository.setSidebarDensity(DensityOverride("compact"))

        assertEquals(emptyList(), transport.methodNames())
        assertTrue(repository.settings.first().syncWithServer, "with the sync still on")

        // On screen immediately, because the store is the preview and a choice
        // with nowhere to go is still the answer.
        val settings = repository.settings.first()
        assertEquals("nord", settings.theme)
        assertEquals("compact", settings.sidebarDensity)
    }

    @Test
    fun `nor with the sync off, which also stops the reading`() = runTest {
        val store = AppearanceStore(InMemoryPreferences())
        val transport = scripted {}
        val repository = repository(store, transport)

        store.setRemote(RemoteAppearance(theme = "light", state = "s1"))
        repository.setSyncWithServer(false)

        repository.setTheme("nord")
        repository.setListAvatars(false)
        // The half the switch is actually for now: a phone running its own
        // appearance must not have the browser's values land on top of it every
        // time a sync fires.
        repository.refresh()

        assertEquals(emptyList(), transport.methodNames())
        assertEquals("nord", repository.settings.first().theme)
    }

    /**
     * A local choice outlives every read, and that is what the switch is for.
     *
     * While the app pushed its changes an override was *pending* and was dropped the moment the
     * server confirmed it. Nothing is sent now, so an override is not pending — it is the answer,
     * and a refresh may not quietly take it back. What the account's value still gets to decide is
     * everything the user has not touched here, which is the whole of "match the web".
     */
    @Test
    fun `a refresh takes the account's values only where this phone has none`() = runTest {
        val store = AppearanceStore(InMemoryPreferences())
        val transport = scripted { getting(theme = "solar", layout = "boxed") }
        val repository = repository(store, transport)

        repository.setTheme("nord")
        repository.refresh()

        val settings = repository.settings.first()

        assertEquals("nord", settings.theme, "the phone's own choice survives the read")
        assertEquals("boxed", settings.layout, "and the untouched half follows the account")
        assertEquals(listOf("get"), transport.methodNames())
    }

    @Test
    fun `turning the sync back on discards this device's own appearance`() = runTest {
        // The reset button, and the only way back to "whatever the browser
        // says": every override is dropped *before* the read, so there is no
        // merge rule to get subtly wrong.
        val store = AppearanceStore(InMemoryPreferences())
        val transport = scripted { getting(theme = "solar", layout = "boxed") }
        val repository = repository(store, transport)

        store.setRemote(RemoteAppearance(theme = "light", state = "s1"))
        repository.setSyncWithServer(false)
        repository.setTheme("nord")
        repository.setFontScale(1.25f)

        repository.setSyncWithServer(true)

        // One `Appearance/get` and no `Appearance/set`: the phone asked what the
        // account looks like and told it nothing.
        assertEquals(listOf("get"), transport.methodNames())

        val settings = repository.settings.first()

        assertEquals("solar", settings.theme)
        assertNull(settings.fontScale)
        assertNull(store.appearance.first().theme)
    }

    @Test
    fun `the local-only switches survive a re-enable`() = runTest {
        // They are not overrides and there is nothing for the server to win:
        // Material You and reduce-transparency answer questions it does not ask,
        // so clearing them here would be losing a setting to a sync that has no
        // opinion about it.
        val store = AppearanceStore(InMemoryPreferences())
        val transport = scripted { getting(theme = "solar", layout = "boxed") }
        val repository = repository(store, transport)

        repository.setDynamicColor(true)
        repository.setReduceTransparency(true)
        repository.setSyncWithServer(false)
        repository.setSyncWithServer(true)

        val settings = repository.settings.first()

        assertTrue(settings.dynamicColor)
        assertTrue(settings.reduceTransparency)
        assertTrue(settings.syncWithServer)
    }

    @Test
    fun `choosing an appearance touches the network at all, which it must not`() = runTest {
        // A setter is a DataStore write and nothing else, so a NAS that is
        // asleep is not even a case. Kept as a test because it was one -- the
        // old path swallowed a throw here, and a setter that started making a
        // request again would be caught by the transport blowing up rather than
        // by anybody noticing a slower tap.
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
        // remote record would erase what the phone falls back to.
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
     * Written as a queue of bodies rather than a router because *what was sent at all* is what
     * these tests are about, and an empty script is the strongest assertion here: any request past
     * discovery fails rather than being quietly answered.
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

    private fun MutableList<String>.getting(theme: String, layout: String) {
        add(getResult(theme = theme, layout = layout, state = "s7"))
    }

    private fun getResult(theme: String, layout: String, state: String) =
        """
        {"methodResponses":[["Appearance/get",{"accountId":null,"state":"$state",
         "list":[{"id":"singleton","theme":"$theme","layout":"$layout",
                  "density":"comfortable","paneAlpha":1.0}],"notFound":[]},"c0"]]}
        """

    private companion object {
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
