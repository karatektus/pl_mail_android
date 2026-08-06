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

    /**
     * The server never returns the verification code, so the app cannot ask whether it is verified.
     *
     * This replaces a test that asserted the opposite, and the old code it pinned was wrong in the
     * way that is hardest to notice: `PushRepository.isLive` read `verificationCode` back off a
     * `PushSubscription/get` and called a null one verified — but the property is write-only on
     * both `main` and the FCM branch, by design, because echoing it would hand the handshake to
     * whoever could read one response. So the check could not return false, and the diagnostics
     * line it powered said "verified" about every subscription, including the ones receiving
     * nothing.
     *
     * The handshake is now recorded on the device that completed it, which is the only place the
     * fact exists.
     */
    @Test
    fun `a get answers nothing about verification, so the device has to remember`() {
        val response = """{"state":"1","list":[{"id":"ps-1","transport":"fcm","url":null}]}"""

        val subscription =
            MethodResults.JMAP_JSON.decodeFromString(
                    de.plmail.jmap.methods.PushSubscriptionGetResult.serializer(),
                    response,
                )
                .list
                .single()

        // What it does answer, and both are useful: the row still exists, and
        // which kind it is. An FCM token reported UNREGISTERED destroys the row
        // server-side, so a `notFound` here is the one failure a device cannot
        // notice on its own.
        assertEquals("ps-1", subscription.id)
        assertEquals(
            de.plmail.jmap.methods.PushSubscriptionTransport.FCM,
            subscription.transportKind,
        )
        assertEquals(null, subscription.url, "an FCM subscription has no URL")
    }

    /**
     * Over FCM the same JSON arrives as the string value of one data key.
     *
     * Pinned because it is the join between two transports that must not drift: the Firebase
     * service reads `data["payload"]` and hands the string to the same parser the UnifiedPush
     * receiver hands its decrypted bytes to. If the payload were reshaped for FCM, a state change
     * would apply on one transport and not the other — on different *devices*, so no single
     * person's testing would see it.
     */
    @Test
    fun `the fcm data payload is byte for byte the web push one`() {
        val overFcm = """{"@type":"StateChange","changed":{"7":{"Email":"9"}}}"""

        val decoded = MethodResults.JMAP_JSON.decodeFromString(StateChange.serializer(), overFcm)

        assertEquals(mapOf("Email" to "9"), decoded.changed["7"])
    }
}
