package de.plmail.core.data

import de.plmail.core.database.PlMailDatabase
import de.plmail.core.datastore.CredentialStore
import de.plmail.core.datastore.PushLogStore
import de.plmail.core.datastore.PushStateStore
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The one-time removal of the subscription the pre-hash builds left behind.
 *
 * **The bug this pins is delivery over two transports at once.** The server's radio semantics are
 * per `deviceClientId` — re-registering replaces the row matching it — and until recently that id
 * was the constant `plmail` on every install. A phone that upgraded registered under
 * `plmail-<hash>`, the replace never looked at the old row, and the server went on delivering to
 * both. Nothing in the picker can see that: it enforces one-of by relying on a replace that only
 * ever matches one id.
 *
 * Asserted against the *wire* rather than against a double, because every claim here is about what
 * the server is asked. "Destroys the legacy row" is a `PushSubscription/set` carrying that row's id
 * and no other; "does not look twice" is the absence of a second `PushSubscription/get`; and a
 * mocked repository would let the test and the code agree about a method name while disagreeing
 * about the request.
 */
@RunWith(RobolectricTestRunner::class)
// sdk = 36, for the reason `core/ui`'s screenshot tests give.
@Config(sdk = [36])
class LegacySubscriptionSweepTest {

    private lateinit var database: PlMailDatabase

    /** This device under the fixed scheme: a hashed per-install id, not the constant. */
    private val deviceClientId = "plmail-ab12cd34"

    @Before
    fun open() {
        database = inMemoryDatabase()
    }

    @After
    fun close() {
        database.close()
    }

    @Test
    fun `the legacy row is destroyed and the registration still happens`() = runTest {
        val transport = server(subscriptions = legacyAnd(deviceClientId))
        val state = PushStateStore(InMemoryPreferences())
        val push = repository(transport, state)

        val outcome = push.subscribeFcm(token = "tok", deviceClientId = deviceClientId)

        // The lookup asks for everything, because the orphan is registered
        // under an id this device no longer uses and cannot ask for by id.
        val get = transport.bodyContaining("PushSubscription/get")
        assertTrue(get.contains("\"ids\":null"), "the sweep asks for every subscription: $get")

        // Exactly the legacy row. `ps-mine` belongs to this device under the
        // new scheme and destroying it would be the bug with the sign flipped.
        val destroy = transport.bodyContaining("\"destroy\"")
        assertTrue(destroy.contains("\"ps-legacy\""), destroy)
        assertFalse(destroy.contains("ps-mine"), "the device's own subscription survives: $destroy")

        // And the reason the sweep is not allowed to be clever: the create is
        // what the caller asked for.
        assertEquals(SubscribeOutcome.Registered("ps-new"), outcome)
        assertTrue(transport.bodyContaining("\"create\"").contains(deviceClientId))

        assertTrue(state.state.first().hasSweptLegacySubscriptions)
    }

    /**
     * The ordinary case: a clean install, or a device already swept.
     *
     * The flag is still set. The sweep is "we have looked", not "we have deleted" — a device that
     * asked, found nothing, and asked again on every registration would be paying a round trip
     * forever for an answer that cannot change.
     */
    @Test
    fun `an account with no legacy row is not asked to destroy anything`() = runTest {
        val transport = server(subscriptions = onlyMine(deviceClientId))
        val state = PushStateStore(InMemoryPreferences())
        val push = repository(transport, state)

        push.subscribeWebPush(registration(), deviceClientId)

        assertEquals(
            0,
            transport.bodies().count { it.contains("\"destroy\"") },
            "there was nothing to destroy",
        )
        assertTrue(state.state.first().hasSweptLegacySubscriptions)
    }

    /**
     * A refused destroy is a note, not a failure.
     *
     * The device still has to register with this server, and a sweep that blocked the create would
     * turn a stale row into no push at all. The flag stays unset so the next registration retries,
     * and the refusal goes where somebody debugging push is already looking.
     */
    @Test
    fun `a refused destroy leaves the registration alone and the flag unset`() = runTest {
        val transport =
            server(
                subscriptions = legacyAnd(deviceClientId),
                destroyResponse =
                    """
                    {"methodResponses":[["PushSubscription/set",
                      {"notDestroyed":{"ps-legacy":{"type":"forbidden",
                        "description":"not yours"}}},"c0"]]}
                    """,
            )
        val state = PushStateStore(InMemoryPreferences())
        val logStore = PushLogStore(InMemoryPreferences())
        val log = PushLog(logStore)
        val push = repository(transport, state, log)

        val outcome = push.subscribeFcm(token = "tok", deviceClientId = deviceClientId)

        assertEquals(SubscribeOutcome.Registered("ps-new"), outcome)
        assertFalse(
            state.state.first().hasSweptLegacySubscriptions,
            "an unset flag is what makes the next registration try again",
        )

        val note = log.entries.first().single()

        assertEquals("LegacySubscriptionSweep", note.type)
        assertTrue(note.note.orEmpty().contains("forbidden"), note.note.orEmpty())
    }

    /** A `get` that fails outright is the same story: logged, retried, never in the way. */
    @Test
    fun `a lookup that fails does not stop the registration`() = runTest {
        val transport =
            server(
                subscriptions = legacyAnd(deviceClientId),
                getResponse = """{"methodResponses":[["error",{"type":"unknownMethod"},"c0"]]}""",
            )
        val state = PushStateStore(InMemoryPreferences())
        val push = repository(transport, state)

        val outcome = push.subscribeFcm(token = "tok", deviceClientId = deviceClientId)

        assertEquals(SubscribeOutcome.Registered("ps-new"), outcome)
        assertFalse(state.state.first().hasSweptLegacySubscriptions)
    }

    @Test
    fun `a device that has already swept does not look again`() = runTest {
        val transport = server(subscriptions = legacyAnd(deviceClientId))
        val state = PushStateStore(InMemoryPreferences())
        val push = repository(transport, state)

        push.subscribeFcm(token = "tok", deviceClientId = deviceClientId)
        push.subscribeFcm(token = "tok2", deviceClientId = deviceClientId)

        assertEquals(
            1,
            transport.bodies().count { it.contains("PushSubscription/get") },
            "the sweep is once per install, not once per registration",
        )
        assertEquals(2, transport.bodies().count { it.contains("\"create\"") })
    }

    /**
     * The one device for which the `plmail` row is not a corpse.
     *
     * `DeviceClientId` falls back to the bare constant when the device answers neither `ANDROID_ID`
     * nor `Build.MODEL`. Sweeping there would delete the subscription the same call is about to
     * create — a phone that destroys its own push on every registration, which is a worse bug than
     * the one this fixes.
     */
    @Test
    fun `a device still registering as plmail sweeps nothing`() = runTest {
        val transport = server(subscriptions = legacyAnd(deviceClientId))
        val state = PushStateStore(InMemoryPreferences())
        val push = repository(transport, state)

        push.subscribeFcm(token = "tok", deviceClientId = "plmail")

        assertEquals(0, transport.bodies().count { it.contains("PushSubscription/get") })
        assertFalse(state.state.first().hasSweptLegacySubscriptions)
    }

    /**
     * The device the whole fix is for, and the one a create-time hook alone would never reach.
     *
     * It upgraded, registered once under the hashed id, and has been live on Firebase ever since —
     * so `reapply` returns early on every launch and `tokenRotated` answers `Unchanged` for a token
     * that has not moved. Nothing ever calls a create again, and it goes on receiving over the
     * abandoned `plmail` Web Push row forever. The sweep therefore runs *before* those early
     * returns.
     */
    @Test
    fun `a device that is already live is swept without being re-registered`() = runTest {
        val transport = server(subscriptions = legacyAnd(deviceClientId))
        val state = PushStateStore(InMemoryPreferences())

        state.chose(PushChoice.FCM.wire)
        state.registered(
            subscriptionId = "ps-mine",
            transport = PushChoice.FCM.wire,
            endpoint = null,
            fcmToken = "tok",
            at = 1,
        )
        state.verified(at = 2)

        manager(transport, state).reapply()

        val destroy = transport.bodyContaining("\"destroy\"")

        assertTrue(destroy.contains("\"ps-legacy\""), destroy)
        assertEquals(
            0,
            transport.bodies().count { it.contains("\"create\"") },
            "a live device is left alone; only the orphan goes",
        )
        assertTrue(state.state.first().hasSweptLegacySubscriptions)
    }

    // ------------------------------------------------------------- fixtures

    private fun registration() = PushRegistration(endpoint = "https://up.example/e", "p", "a")

    private fun legacyAnd(mine: String) =
        """
        {"id":"ps-legacy","deviceClientId":"plmail","transport":"webpush",
         "url":"https://up.example/old"},
        {"id":"ps-mine","deviceClientId":"$mine","transport":"fcm","url":null}
        """

    private fun onlyMine(mine: String) =
        """{"id":"ps-mine","deviceClientId":"$mine","transport":"fcm","url":null}"""

    /**
     * A server that answers each `PushSubscription` call by what the request asked for.
     *
     * Routed on the body rather than scripted in order, because the order is what two of these
     * tests are about and a queue would make "did not look twice" pass by accident.
     */
    private fun server(
        subscriptions: String,
        getResponse: String? = null,
        destroyResponse: String? = null,
    ): RecordingTransport = RecordingTransport { request ->
        val body = request.body?.decodeToString().orEmpty()

        val answer =
            when {
                request.url.contains("well-known") -> TEST_SESSION
                body.contains("PushSubscription/get") ->
                    getResponse
                        ?: """
                        {"methodResponses":[["PushSubscription/get",
                          {"state":"1","list":[$subscriptions]},"c0"]]}
                        """
                body.contains("\"destroy\"") ->
                    destroyResponse
                        ?: """
                        {"methodResponses":[["PushSubscription/set",
                          {"destroyed":["ps-legacy"]},"c0"]]}
                        """
                else ->
                    """
                    {"methodResponses":[["PushSubscription/set",
                      {"created":{"device":{"id":"ps-new"}}},"c0"]]}
                    """
            }

        HttpResponse(
            status = 200,
            headers = mapOf("Content-Type" to "application/json"),
            body = answer.encodeToByteArray(),
        )
    }

    private fun RecordingTransport.bodies(): List<String> = requests.mapNotNull {
        it.body?.decodeToString()
    }

    private fun RecordingTransport.bodyContaining(fragment: String): String =
        bodies().firstOrNull { it.contains(fragment) }
            ?: error("no request carried $fragment; sent ${bodies()}")

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

    private suspend fun repository(
        transport: JmapTransport,
        state: PushStateStore,
        log: PushLog = PushLog(PushLogStore(InMemoryPreferences())),
        clients: AccountClients? = null,
    ): PushRepository =
        PushRepository(
            clients = clients ?: clients(transport),
            // Real, because nothing here delivers a push -- but constructed
            // rather than doubled so the class under test is the shipping one.
            changes = StateChangeApplier(database, syncStack(database, transport)),
            log = log,
            state = state,
        )

    private suspend fun manager(
        transport: JmapTransport,
        state: PushStateStore,
    ): PushTransportManager {
        val clients = clients(transport)

        return PushTransportManager(
            clients = clients,
            state = state,
            push = repository(transport, state, clients = clients),
            webPush = NoDistributor,
            fcm = NoFcmSupport,
            deviceClientId = DeviceClientId(deviceClientId),
        )
    }

    /** A device with nothing installed; `reapply` on a live subscription touches neither. */
    private object NoDistributor : PushTransport {
        override fun distributor(): String? = null

        override fun installed(): List<String> = emptyList()

        override fun register(): Boolean = false

        override fun unregister() = Unit
    }
}
