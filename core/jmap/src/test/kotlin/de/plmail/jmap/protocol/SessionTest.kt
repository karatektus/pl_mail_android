package de.plmail.jmap.protocol

import de.plmail.jmap.Fixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class SessionTest {

    private val session = Fixture.decode<Session>("session.json")

    @Test
    fun `discovers the api url the server generated, rather than one we built`() {
        // The server derives these from the request's Host header. A client
        // that appends "/jmap/api" to what the user typed happens to agree
        // today and stops agreeing the moment a reverse proxy is involved.
        assertEquals("http://127.0.0.1:8002/jmap/api", session.apiUrl)
        assertEquals("http://127.0.0.1:8002/jmap/upload/{accountId}", session.uploadUrl)
    }

    @Test
    fun `reads the vapid key out of the vendor push capability`() {
        // RFC 8620 defines no standard place for this, so it arrives under a
        // plMail-specific URN and would be invisible to a strict parser.
        assertEquals(
            "BGhIG_D4wVHpADF70-Sjztf1F1opcsNzjSeEsiX7Byg1ek0dTbFgmPxQmUB6TaKDZB6g3qK275kgvHMGGJhPnjk",
            session.vapidPublicKey,
        )
    }

    @Test
    fun `treats a missing push capability as push being unconfigured`() {
        val withoutPush = session.copy(capabilities = session.capabilities - Capability.PUSH)

        assertNull(withoutPush.vapidPublicKey, "no key means do not offer Web Push")
    }

    /**
     * The fixture is a real plMail that predates the FCM work, and that is the point of this test.
     *
     * A server saying nothing about Firebase and one saying `"fcm": false` are different situations
     * with opposite fixes — upgrade the server, versus switch it on in the server's admin page —
     * and the session publishes `fcm` even when false precisely so a client can tell them apart.
     * Collapsing them into `fcm == true` loses that, and the sentence the app shows sends somebody
     * to a settings page their build does not have.
     */
    @Test
    fun `a server that says nothing about fcm is not the same as one that says no`() {
        val silent = session.push

        assertNotNull(silent)
        assertFalse(silent.knowsFcm, "this server predates FCM")
        assertFalse(silent.fcm)
        assertNull(silent.fcmConfig)
    }

    @Test
    fun `reads the firebase project the server publishes for this install`() {
        val withFcm =
            LENIENT_JSON.decodeFromString(
                Session.serializer(),
                """
                {
                  "apiUrl": "https://mail.example.com/jmap/api",
                  "downloadUrl": "https://mail.example.com/jmap/download",
                  "uploadUrl": "https://mail.example.com/jmap/upload",
                  "capabilities": {
                    "urn:plmail:params:jmap:push": {
                      "vapidPublicKey": "BN",
                      "fcm": true,
                      "fcmConfig": {
                        "projectId": "plmail-abc123",
                        "applicationId": "1:1234567890:android:0123456789abcdef",
                        "apiKey": "AIza",
                        "senderId": "1234567890"
                      }
                    }
                  }
                }
                """
                    .trimIndent(),
            )

        val push = withFcm.push

        assertNotNull(push)
        assertTrue(push.knowsFcm)
        assertTrue(push.fcm)
        assertEquals("plmail-abc123", push.fcmConfig?.projectId)
        assertEquals("1234567890", push.fcmConfig?.senderId)
        assertTrue(push.fcmConfig?.isComplete == true)
    }

    /**
     * `fcm: false` publishes no `fcmConfig` **key at all**, which is the opposite rule to `fcm`'s
     * and is deliberate: a null object invites a client to read `.projectId` off it and get null,
     * and an absent key cannot be dereferenced.
     */
    @Test
    fun `an instance with firebase switched off publishes no project`() {
        val off =
            LENIENT_JSON.decodeFromString(
                Session.serializer(),
                """
                {
                  "apiUrl": "https://mail.example.com/jmap/api",
                  "downloadUrl": "https://mail.example.com/jmap/download",
                  "uploadUrl": "https://mail.example.com/jmap/upload",
                  "capabilities": {
                    "urn:plmail:params:jmap:push": { "vapidPublicKey": "", "fcm": false }
                  }
                }
                """
                    .trimIndent(),
            )

        val push = off.push

        assertNotNull(push)
        assertTrue(push.knowsFcm, "the server answered the question")
        assertFalse(push.fcm)
        assertNull(push.fcmConfig)
        assertNull(off.vapidPublicKey, "an empty key means Web Push is unconfigured too")
    }

    @Test
    fun `exposes one account per connected mailbox`() {
        assertEquals(listOf(AccountId("1")), session.accountIds)
        assertEquals("E2E Mailbox", session.account(AccountId("1"))?.name)
        assertEquals(AccountId("1"), session.primaryMailAccount)
    }

    @Test
    fun `reads core limits from the session rather than assuming them`() {
        // Hardcoding these would second-guess an instance configured for
        // larger uploads, and maxCallsInRequest in particular differs from the
        // spec's own default here (32, not 16).
        assertEquals(50_000_000, session.core.maxSizeUpload)
        assertEquals(4, session.core.maxConcurrentRequests)
        assertEquals(32, session.core.maxCallsInRequest)
        assertEquals(500, session.core.maxObjectsInGet)
    }

    @Test
    fun `falls back to spec defaults when the server omits a limit`() {
        val bare = session.copy(capabilities = session.capabilities - Capability.CORE)

        assertEquals(16, bare.core.maxCallsInRequest, "the RFC 8620 default")
        assertTrue(bare.core.maxSizeUpload > 0)
    }
}

/** One lenient codec: the compiler rejects a `Json { }` built per call site. */
private val LENIENT_JSON = Json { ignoreUnknownKeys = true }
