package de.plmail.feature.onboarding

import de.plmail.jmap.client.PairingInvitation
import de.plmail.jmap.client.PairingUri
import de.plmail.jmap.client.ParsedInvitation
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decides which barcode, out of everything the camera sees, is the one to act on.
 *
 * Separated from the camera because this is the part with a rule worth testing and the camera is
 * the part that cannot be: an analyser runs many times a second over whatever is in frame, and a
 * pairing code is **single-use**. Handing every decode upward would redeem the code and then
 * immediately try again with a code the server has already burned — and the second attempt fails
 * with the same message as an expired one, so the user would be told their code had expired at the
 * exact moment it worked.
 *
 * So this latches. The first valid invitation wins and every later frame is ignored, including
 * frames still in flight when the first one was accepted.
 */
class PairingBarcodes {

    private val claimed = AtomicBoolean(false)

    /**
     * Atomic rather than a plain `Boolean`, because the check and the set have to be one step.
     *
     * `ImageAnalysis` delivers on a background executor, and two frames decoded either side of a
     * non-atomic check would both pass it. That is precisely the double-redeem this class exists to
     * prevent, and it would only ever show up on a fast device holding a steady QR — the case where
     * the scanner works *best*.
     */
    val hasClaimed: Boolean
        get() = claimed.get()

    /**
     * The first pairing invitation among [rawValues], or null.
     *
     * Nulls in the list are expected: ML Kit reports a barcode it located but could not decode as a
     * null raw value, which is the ordinary state of a QR half out of frame.
     */
    fun accept(rawValues: List<String?>): PairingInvitation? {
        if (claimed.get()) return null

        for (raw in rawValues) {
            val text = raw ?: continue

            // Anything that is not ours is skipped rather than reported: the
            // camera sees the wifi QR on the same sheet of paper and the URL
            // printed on the box behind it, and neither is a failure the user
            // caused. PairingUri already draws that distinction.
            val invitation =
                when (val parsed = PairingUri.parse(text)) {
                    is ParsedInvitation.Valid -> parsed.invitation
                    is ParsedInvitation.Incomplete,
                    ParsedInvitation.NotAPairingUri -> continue
                }

            // compareAndSet, so of two threads holding a valid invitation only
            // one returns it.
            if (claimed.compareAndSet(false, true)) return invitation

            return null
        }

        return null
    }
}
