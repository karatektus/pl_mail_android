package de.plmail.feature.compose

import de.plmail.jmap.protocol.SubmissionCapability
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What "send later" is allowed to offer.
 *
 * The rule underneath all of it is that **the ceiling is the server's**. A preset filtered out for
 * being past `maxDelayedSend` is a refusal this client can make honestly, because it is reading the
 * session; a limit written here as a constant would start refusing sends the server would happily
 * accept on the day somebody raised it. The other half is the one the snooze menu already learned:
 * a preset that has already passed today must not be offered, because a control that appears to do
 * nothing is worse than one that is missing.
 */
class SendLaterTest {

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")

    /** 2026-08-06, 09:00 local. */
    private val morning: Instant = Instant.parse("2026-08-06T07:00:00Z")

    private val thirtyDays: Instant = morning.plusSeconds(2_592_000)

    private fun capability(seconds: Long, vararg parameters: String) =
        SubmissionCapability(
            maxDelayedSend = seconds,
            extensions =
                if (parameters.isEmpty()) emptyMap()
                else mapOf("FUTURERELEASE" to parameters.toList()),
        )

    @Test
    fun `presets resolve to the user's own morning and evening, not to UTC`() {
        val later = SendLaterPreset.LATER_TODAY.resolve(morning, zone, thirtyDays)
        val tomorrow = SendLaterPreset.TOMORROW.resolve(morning, zone, thirtyDays)

        // 18:00 and 08:00 in Berlin, which in August is UTC+2.
        assertEquals(Instant.parse("2026-08-06T16:00:00Z"), later)
        assertEquals(Instant.parse("2026-08-07T06:00:00Z"), tomorrow)
    }

    @Test
    fun `a preset that has already passed today is not offered`() {
        // 23:00 local. "Later today" would mean five hours ago, and a send that
        // goes out immediately is a control that appears to do nothing.
        val night = Instant.parse("2026-08-06T21:00:00Z")

        assertNull(SendLaterPreset.LATER_TODAY.resolve(night, zone, night.plusSeconds(2_592_000)))
    }

    @Test
    fun `a preset past the server's ceiling is dropped rather than refused later`() {
        // A one-hour ceiling: nothing further out than that is offerable, and
        // the refusal would otherwise arrive with the composer already closed.
        val hour = morning.plusSeconds(3_600)

        assertNull(SendLaterPreset.TOMORROW.resolve(morning, zone, hour))
        assertNull(SendLaterPreset.MONDAY.resolve(morning, zone, hour))
    }

    @Test
    fun `the session decides whether the feature exists at all`() {
        val advertised = capability(2_592_000, "HOLDFOR", "HOLDUNTIL")

        assertTrue(ComposeUiState(submission = advertised).canScheduleSend)
        assertTrue(advertised.supportsHoldFor)
        assertEquals(2_592_000, advertised.maxDelayedSend)

        // A ceiling with no FUTURERELEASE is a server that would refuse the
        // parameter, and the extension with a ceiling of zero is one that would
        // refuse every value of it. Either way there is nothing to offer.
        assertFalse(ComposeUiState(submission = SubmissionCapability()).canScheduleSend)
        assertFalse(ComposeUiState(submission = capability(2_592_000)).canScheduleSend)
        assertFalse(ComposeUiState(submission = capability(0, "HOLDFOR")).canScheduleSend)
    }

    @Test
    fun `the picker's window is the ceiling and nothing else`() {
        val state = ComposeUiState(submission = capability(86_400, "HOLDUNTIL"))

        assertEquals(morning.plusSeconds(86_400), state.latestSendAt(morning))
    }
}
