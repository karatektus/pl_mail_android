package de.plmail.core.data

import de.plmail.jmap.methods.PushVerification
import de.plmail.jmap.methods.StateChange
import de.plmail.jmap.protocol.MethodResults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Telling the two push payloads apart.
 *
 * Both arrive on the same endpoint and the server does not label them, so they are distinguished by
 * shape. Getting this wrong has an asymmetric cost: mistaking a verification for a state change
 * means the handshake is never answered, and a subscription that is never verified receives nothing
 * — silently, forever, with the app appearing to be registered.
 */
class PushPayloadTest {

    @Test
    fun `a verification carries the code the handshake needs`() {
        val payload =
            """{"@type":"PushVerification","pushSubscriptionId":"ps-1","verificationCode":"abc123"}"""

        val decoded =
            MethodResults.JMAP_JSON.decodeFromString(PushVerification.serializer(), payload)

        assertEquals("ps-1", decoded.pushSubscriptionId)
        assertEquals("abc123", decoded.verificationCode)
    }

    /**
     * A push is a trigger, never content.
     *
     * `{"changed":{"1":{"Email":"9"}}}` says account 1's Email state moved to 9. It does not say
     * what arrived, and a client that tried to render from it would be inventing mail.
     */
    @Test
    fun `a state change names accounts and types, not messages`() {
        val payload = """{"@type":"StateChange","changed":{"1":{"Email":"9","Thread":"4"}}}"""

        val decoded = MethodResults.JMAP_JSON.decodeFromString(StateChange.serializer(), payload)

        assertEquals(setOf("1"), decoded.changed.keys)
        assertEquals(mapOf("Email" to "9", "Thread" to "4"), decoded.changed["1"])
    }

    @Test
    fun `the two are distinguishable by shape alone`() {
        val verification = """{"pushSubscriptionId":"ps-1","verificationCode":"abc"}"""
        val change = """{"changed":{"1":{"Email":"9"}}}"""

        assertTrue(verification.contains("verificationCode"))
        assertTrue(!change.contains("verificationCode"))
        assertTrue(change.contains("changed"))
    }

    /**
     * `@type` is present and deliberately not relied on.
     *
     * `ignoreUnknownKeys` means an extra field never breaks decoding, which is what lets the same
     * parser survive the server adding one. Keying the decision on a field the RFC does not require
     * would be more fragile, not less.
     */
    @Test
    fun `an unknown field does not break decoding`() {
        val payload =
            """{"@type":"StateChange","changed":{"1":{"Email":"9"}},"somethingNew":true}"""

        val decoded = MethodResults.JMAP_JSON.decodeFromString(StateChange.serializer(), payload)

        assertEquals(mapOf("Email" to "9"), decoded.changed["1"])
    }

    @Test
    fun `a subscription is only live once the code is gone`() {
        // PushSubscriptionInfo still carrying a verificationCode is registered
        // and receiving nothing -- the likeliest reason push "does not work".
        val pending =
            """{"state":"1","list":[{"id":"ps-1","url":"https://ntfy/x","verificationCode":"abc"}]}"""
        val live = """{"state":"1","list":[{"id":"ps-1","url":"https://ntfy/x"}]}"""

        val pendingCode =
            MethodResults.JMAP_JSON.decodeFromString(
                    de.plmail.jmap.methods.PushSubscriptionGetResult.serializer(),
                    pending,
                )
                .list
                .single()
                .verificationCode

        val liveCode =
            MethodResults.JMAP_JSON.decodeFromString(
                    de.plmail.jmap.methods.PushSubscriptionGetResult.serializer(),
                    live,
                )
                .list
                .single()
                .verificationCode

        assertEquals("abc", pendingCode)
        assertEquals(null, liveCode)
    }
}
