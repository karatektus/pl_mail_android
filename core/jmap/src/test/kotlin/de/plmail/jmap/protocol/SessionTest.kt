package de.plmail.jmap.protocol

import de.plmail.jmap.Fixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
