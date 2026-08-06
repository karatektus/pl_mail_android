package de.plmail.core.data

import de.plmail.jmap.protocol.FcmConfig
import de.plmail.jmap.protocol.PushCapability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/**
 * Which transports may be offered, and — when one may not — which of several different reasons
 * applies.
 *
 * The reasons are the whole point and are why this is not a boolean. "Your server does not know
 * about Firebase" and "your administrator switched Firebase off" send the reader to two different
 * places, and one of those places does not exist on the server they are running. Collapsing them
 * into "unavailable" is accurate and useless, and it is the collapse the session's shape was
 * designed to prevent: `fcm` is published even when false so a client can tell "no" from "never
 * heard of it".
 */
class PushTransportsTest {

    private val project =
        FcmConfig(
            projectId = "plmail-abc",
            applicationId = "1:1:android:1",
            apiKey = "AIza",
            senderId = "1",
        )

    @Test
    fun `a server predating fcm says so, rather than saying no`() {
        val support = ServerPushSupport.from(PushCapability(vapidPublicKey = "BN"))

        assertEquals(PushUnavailable.SERVER_TOO_OLD, support.fcmObjection)
    }

    @Test
    fun `a server with firebase switched off is a different answer`() {
        val support =
            ServerPushSupport.from(
                PushCapability(vapidPublicKey = "BN", fcm = false, knowsFcm = true)
            )

        assertEquals(PushUnavailable.SERVER_DISABLED, support.fcmObjection)
    }

    @Test
    fun `a server with firebase on and configured raises no objection`() {
        val support =
            ServerPushSupport.from(
                PushCapability(
                    vapidPublicKey = "BN",
                    fcm = true,
                    knowsFcm = true,
                    fcmConfig = project,
                )
            )

        assertNull(support.fcmObjection)
        assertEquals(project, support.fcmConfig)
    }

    /**
     * Three of four values is not a Firebase project, and failing here beats failing at
     * `FirebaseOptions.Builder` with a stack trace the user cannot act on.
     */
    @Test
    fun `an incomplete project is refused before Firebase is asked to start`() {
        val support =
            ServerPushSupport.from(
                PushCapability(
                    fcm = true,
                    knowsFcm = true,
                    fcmConfig = project.copy(apiKey = ""),
                )
            )

        assertEquals(PushUnavailable.SERVER_CONFIG_INCOMPLETE, support.fcmObjection)
    }

    /**
     * The config is read only after `fcm` has been checked.
     *
     * The server publishes `fcmConfig` as an absent key rather than a null one precisely so that
     * reaching for it first is impossible; this keeps the client's side of that bargain, so a stale
     * config left in a capability could not be used against a server that has since switched
     * Firebase off.
     */
    @Test
    fun `a project published beside fcm false is not used`() {
        val support =
            ServerPushSupport.from(
                PushCapability(fcm = false, knowsFcm = true, fcmConfig = project)
            )

        assertNull(support.fcmConfig)
        assertEquals(PushUnavailable.SERVER_DISABLED, support.fcmObjection)
    }

    @Test
    fun `an empty vapid key means Web Push is not on offer`() {
        assertEquals(false, ServerPushSupport.from(PushCapability()).webPush)
        assertEquals(true, ServerPushSupport.from(PushCapability(vapidPublicKey = "BN")).webPush)
    }

    /** An unreachable server supports nothing, which is what the picker should draw. */
    @Test
    fun `an unread session offers no transport but pull`() {
        val support = ServerPushSupport.from(null)

        assertEquals(false, support.webPush)
        assertEquals(PushUnavailable.SERVER_TOO_OLD, support.fcmObjection)
    }

    /** The `foss` flavour's answer, which has to be reachable without linking anything Google. */
    @Test
    fun `the flavour with no firebase says so rather than failing to start it`() = runTest {
        assertEquals(false, NoFcmSupport.isCompiledIn)
        assertEquals(
            FcmAvailability.Unavailable(PushUnavailable.NOT_IN_THIS_BUILD),
            NoFcmSupport.prepare(project),
        )
    }

    /** The wire names are what the server's own delivery log prints; they have to round-trip. */
    @Test
    fun `choices survive being stored as their wire names`() {
        PushChoice.entries.forEach { choice ->
            assertEquals(choice, PushChoice.of(choice.wire))
        }

        assertNull(PushChoice.of("something else"))
        assertNull(PushChoice.of(null))
    }
}
