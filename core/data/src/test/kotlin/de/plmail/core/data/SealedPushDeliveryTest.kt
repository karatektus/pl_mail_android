package de.plmail.core.data

import de.plmail.core.database.PlMailDatabase
import de.plmail.core.datastore.CredentialStore
import de.plmail.core.datastore.PushKeyStore
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
import kotlin.test.assertIs
import kotlin.test.assertNull
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
 * Reminders, which reach this app sealed, and the keys that make that possible.
 *
 * The server used to push a calendar reminder through Firebase as readable JSON — the event's
 * title, to Google — and this app dropped it, because it knew two payload types and that was not
 * one of them. Both halves are fixed together: the server now seals anything with content in it to
 * keys the device registers and sends nothing readable, and the app registers them, opens what
 * arrives and shows it.
 *
 * What is pinned here is the app's half of that contract, and each test is a way for it to fail
 * without anything looking wrong:
 * - keys sent to a server that does not expect them make the whole registration fail;
 * - a device that registered before keys existed never registers again, so it has to be given a
 *   second way to hand them over;
 * - a reminder's words must reach the notification and must not reach the diagnostics log.
 */
@RunWith(RobolectricTestRunner::class)
// sdk = 36, for the reason `core/ui`'s screenshot tests give.
@Config(sdk = [36])
class SealedPushDeliveryTest {

    private lateinit var database: PlMailDatabase

    private val deviceClientId = "plmail-ab12cd34"

    @Before
    fun open() {
        database = inMemoryDatabase()
    }

    @After
    fun close() {
        database.close()
    }

    // ── registering the keys ────────────────────────────────────────────────

    @Test
    fun `a server that seals is handed the keys with the registration`() = runTest {
        val transport = server(sealing = true)
        val state = PushStateStore(InMemoryPreferences())
        val keys = PushKeyStore(InMemoryPreferences(), PlainCipher)

        repository(transport, state, keys).subscribeFcm("tok", deviceClientId)

        val create = transport.bodyContaining("\"create\"")
        val generated = requireNotNull(keys.keys()) { "the key pair is kept for the next launch" }

        assertTrue(create.contains("\"p256dh\":\"${generated.p256dh}\""), create)
        assertTrue(create.contains("\"auth\":\"${generated.authSecret}\""), create)
        assertEquals("ps-new:${generated.p256dh}", state.state.first().sealingKeysRegistered)
    }

    /**
     * An older server refuses `keys` on an FCM subscription, and on a create the refusal takes the
     * registration with it. A phone pointed at one must register exactly as it always did.
     */
    @Test
    fun `a server that does not seal is sent no keys`() = runTest {
        val transport = server(sealing = false)
        val state = PushStateStore(InMemoryPreferences())
        val keys = PushKeyStore(InMemoryPreferences(), PlainCipher)

        val outcome = repository(transport, state, keys).subscribeFcm("tok", deviceClientId)

        assertEquals(SubscribeOutcome.Registered("ps-new"), outcome)
        assertFalse(transport.bodyContaining("\"create\"").contains("\"keys\""))
        assertNull(state.state.first().sealingKeysRegistered)
        assertNull(keys.keys(), "no key pair is made for a server that would not use it")
    }

    /**
     * The device this exists for: live on Firebase since before the app sent keys, and therefore
     * never registering again. Its subscription is updated in place — once.
     */
    @Test
    fun `a device already registered hands its keys over by update, and only once`() = runTest {
        val transport = server(sealing = true)
        val state = PushStateStore(InMemoryPreferences())
        val keys = PushKeyStore(InMemoryPreferences(), PlainCipher)
        val push = repository(transport, state, keys)

        state.registered("ps-old", PushChoice.FCM.wire, endpoint = null, fcmToken = "tok", at = 1)
        state.verified(2)

        push.ensureSealingKeys()

        val update = transport.bodyContaining("\"update\"")
        val generated = requireNotNull(keys.keys())

        assertTrue(update.contains("\"ps-old\""), update)
        assertTrue(update.contains("\"p256dh\":\"${generated.p256dh}\""), update)
        assertFalse(
            update.contains("fcmToken"),
            "the token is not touched: that re-arms the handshake",
        )
        assertTrue(
            state.state.first().isLive,
            "handing over keys does not un-verify the subscription",
        )

        push.ensureSealingKeys()
        push.ensureSealingKeys()

        assertEquals(1, transport.bodies().count { it.contains("\"update\"") })
    }

    @Test
    fun `a web push subscription is left alone`() = runTest {
        val transport = server(sealing = true)
        val state = PushStateStore(InMemoryPreferences())
        val push = repository(transport, state, PushKeyStore(InMemoryPreferences(), PlainCipher))

        state.registered("ps-up", PushChoice.WEB_PUSH.wire, "https://up.example/x", null, at = 1)

        push.ensureSealingKeys()

        assertEquals(0, transport.bodies().count { it.contains("\"update\"") })
    }

    // ── receiving ────────────────────────────────────────────────────────────

    /**
     * End to end on this side: a body the server's own code sealed (the fixture `SealedPushTest`
     * documents) is opened with the stored keys, recognised as a reminder and handed to whoever
     * draws reminders — and the log records that one arrived and none of what it said.
     */
    @Test
    fun `a sealed reminder is opened, shown, and logged without its words`() = runTest {
        val keys = PushKeyStore(storedKeys(), PlainCipher)
        val logStore = PushLogStore(InMemoryPreferences())
        val shown = mutableListOf<Reminder>()
        val push =
            repository(
                server(sealing = true),
                PushStateStore(InMemoryPreferences()),
                keys,
                log = PushLog(logStore),
                reminders =
                    setOf(
                        object : ReminderListener {
                            override suspend fun onReminder(reminder: Reminder) {
                                shown += reminder
                            }
                        }
                    ),
            )

        val parsed = push.deliverSealed(SEALED, PushDelivery.FCM)

        assertIs<PushPayload.Alert>(parsed)
        assertEquals(
            listOf(
                Reminder(
                    title = "Dentist — Praxis Dr. Ilg",
                    body = "in 15 minutes",
                    tag = "412/display-15m/2026-10-16T09:00:00Z",
                    url = "/calendar/day/2026-10-16",
                )
            ),
            shown,
        )

        val entry = logStore.entries.first().single()

        assertTrue(entry.contains("CalendarAlert"), entry)
        assertFalse(
            entry.contains("Dentist"),
            "the diagnostics log is not the user's diary: $entry",
        )
    }

    /**
     * Keys that are not the ones it was sealed to — regenerated since, or another device's.
     * Recorded and dropped: nothing is shown, and nothing is thrown at the messaging service.
     */
    @Test
    fun `a sealed push that will not open is recorded and shows nothing`() = runTest {
        val keys = PushKeyStore(InMemoryPreferences(), PlainCipher).also { it.keysOrCreate() }
        val logStore = PushLogStore(InMemoryPreferences())
        val shown = mutableListOf<Reminder>()
        val push =
            repository(
                server(sealing = true),
                PushStateStore(InMemoryPreferences()),
                keys,
                log = PushLog(logStore),
                reminders =
                    setOf(
                        object : ReminderListener {
                            override suspend fun onReminder(reminder: Reminder) {
                                shown += reminder
                            }
                        }
                    ),
            )

        assertEquals(PushPayload.Unrecognised, push.deliverSealed(SEALED, PushDelivery.FCM))
        assertEquals(
            PushPayload.Unrecognised,
            push.deliverSealed("not even base64", PushDelivery.FCM),
        )

        assertTrue(shown.isEmpty())
        assertTrue(logStore.entries.first().first().contains("did not open"))
    }

    /** A reminder with no title or nothing to tell it apart by is not shown as an empty banner. */
    @Test
    fun `a reminder missing its title or its tag is not a reminder`() = runTest {
        val push =
            repository(
                server(sealing = true),
                PushStateStore(InMemoryPreferences()),
                PushKeyStore(InMemoryPreferences(), PlainCipher),
            )

        for (payload in
            listOf(
                """{"@type":"CalendarAlert","body":"in 15 minutes","tag":"t"}""",
                """{"@type":"CalendarAlert","title":"Dentist"}""",
            )) {
            assertEquals(PushPayload.Unrecognised, push.deliver(payload, PushDelivery.UNIFIEDPUSH))
        }
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    /** The preferences a device holds after generating the key pair `SealedPushTest` documents. */
    private suspend fun storedKeys(): InMemoryPreferences =
        InMemoryPreferences().also { preferences ->
            preferences.updateData { stored ->
                stored.toMutablePreferences().apply {
                    this[
                        androidx.datastore.preferences.core.stringPreferencesKey(
                            "push_sealing_private"
                        )] = "e21UbNQzEkZmbLtKTbjwzHK01YqM6pL7OUDT3S9F60s"
                    this[
                        androidx.datastore.preferences.core.stringPreferencesKey(
                            "push_sealing_public"
                        )] =
                        "BOZk5elS66ra3hjK92gpnB0vcTNToEuIIgAM9e9rT_h_" +
                            "anKqp-LhZdAgsxjQClEeaTQt4L3t9aOY_oI5tUDxXTw"
                    this[
                        androidx.datastore.preferences.core.stringPreferencesKey(
                            "push_sealing_auth"
                        )] = "x8jbNfS-1Do_nOkA_REtuA"
                }
            }
        }

    private fun server(sealing: Boolean): RecordingTransport = RecordingTransport { request ->
        val body = request.body?.decodeToString().orEmpty()

        val answer =
            when {
                request.url.contains("well-known") -> session(sealing)
                body.contains("PushSubscription/get") ->
                    """{"methodResponses":[["PushSubscription/get",{"state":"1","list":[]},"c0"]]}"""
                body.contains("\"update\"") ->
                    """{"methodResponses":[["PushSubscription/set",{"updated":{"ps-old":null}},"c0"]]}"""
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

    private fun session(sealing: Boolean) =
        """
        {
          "capabilities": {
            "urn:ietf:params:jmap:core": {},
            "urn:plmail:params:jmap:push": {"vapidPublicKey": "", "fcm": true${if (sealing) ", \"fcmEncryption\": true" else ""}}
          },
          "accounts": {"$TEST_ACCOUNT_ID": {"name": "someone@example.com"}},
          "username": "someone@example.com",
          "apiUrl": "$TEST_SERVER/jmap/api",
          "downloadUrl": "$TEST_SERVER/jmap/download",
          "uploadUrl": "$TEST_SERVER/jmap/upload"
        }
        """

    private fun RecordingTransport.bodies(): List<String> = requests.mapNotNull {
        it.body?.decodeToString()
    }

    private fun RecordingTransport.bodyContaining(fragment: String): String =
        bodies().firstOrNull { it.contains(fragment) }
            ?: error("no request carried $fragment; sent ${bodies()}")

    private suspend fun repository(
        transport: JmapTransport,
        state: PushStateStore,
        keys: PushKeyStore,
        log: PushLog = PushLog(PushLogStore(InMemoryPreferences())),
        reminders: Set<ReminderListener> = emptySet(),
    ): PushRepository {
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

        return PushRepository(
            clients = AccountClients(credentials, transports),
            changes =
                StateChangeApplier(
                    database,
                    syncStack(database, transport),
                    bodyPrefetcher(database, transport),
                ),
            log = log,
            state = state,
            sealingKeys = keys,
            reminders = reminders,
        )
    }

    private companion object {
        /** Sealed on a plMail test server by `FcmPayloadCipher`; see `SealedPushTest`. */
        const val SEALED =
            "3Qpo0-DTg7E45N3q6-b21wAAEABBBO6ba-saNRgHR1etwr9md7JQHSVatmWGEvvbyOb0IegRCxRXwoO9leUT7oG8" +
                "Ut24O9FUbED5v_phdpuHCo_qblwZWxkkvCCPfwuMP0RCHtGe67diPq6Cm6ReK5UWIk5WrP1VoSA2HY-_CZjn" +
                "QBgrc05ORxPRwreObo5yBFwlo8LVFm_vEleKOW_DozYxzwGnC_aY5nKwCJGS1sS-WwVmRh0p0xck6fHIggrc" +
                "8BqsKIU8xIVn_tFJaFFvVEYav-oJpp12WRSvA3DdjI0CKlCNykwFHz_NR2dWlXGfhUNrrVcaftIy-LTIoWLS" +
                "PkP_o0HIViKSG1Y"
    }
}
