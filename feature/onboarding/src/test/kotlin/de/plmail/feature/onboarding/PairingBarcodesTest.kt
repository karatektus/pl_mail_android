package de.plmail.feature.onboarding

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The one rule the scanner has: act on a pairing code exactly once.
 *
 * A pairing code is single-use and burned on redemption, and an analyser runs many times a second
 * over whatever is in frame. A scanner that reported every decode would redeem the code and then
 * immediately retry with one the server had already destroyed — telling the user it had expired at
 * the very moment it worked.
 */
class PairingBarcodesTest {

    private val invitation = "plmail://pair?host=https%3A%2F%2Fnas.local&code=abc123"

    @Test
    fun `a pairing uri is accepted once`() {
        val barcodes = PairingBarcodes()

        val first = barcodes.accept(listOf(invitation))
        assertNotNull(first)
        assertEquals("abc123", first.code)
        assertEquals("https://nas.local", first.address.display)
        assertTrue(barcodes.hasClaimed)
    }

    @Test
    fun `every later frame is ignored`() {
        val barcodes = PairingBarcodes()

        assertNotNull(barcodes.accept(listOf(invitation)))
        // The QR is still in front of the camera; these are the frames that
        // would double-redeem.
        assertNull(barcodes.accept(listOf(invitation)))
        assertNull(barcodes.accept(listOf(invitation)))
    }

    @Test
    fun `other barcodes are skipped without claiming`() {
        val barcodes = PairingBarcodes()

        assertNull(
            barcodes.accept(
                listOf(
                    "WIFI:S:somenetwork;T:WPA;P:hunter2;;",
                    "https://example.com",
                    "plmail://open?thread=4",
                )
            )
        )
        assertFalse(barcodes.hasClaimed, "a stranger's barcode must not consume the one shot")

        // And the real one still works afterwards.
        assertNotNull(barcodes.accept(listOf(invitation)))
    }

    @Test
    fun `an undecodable barcode is not a failure`() {
        // ML Kit reports a barcode it located but could not read as a null raw
        // value, which is the ordinary state of a QR half out of frame.
        val barcodes = PairingBarcodes()

        assertNull(barcodes.accept(listOf(null, null)))
        assertFalse(barcodes.hasClaimed)
        assertNotNull(barcodes.accept(listOf(null, invitation)))
    }

    @Test
    fun `the pairing code is found among other barcodes in frame`() {
        val barcodes = PairingBarcodes()

        val found = barcodes.accept(listOf("https://example.com", invitation, "tel:+123"))

        assertEquals("abc123", found?.code)
    }

    /**
     * The reason the latch is atomic.
     *
     * `ImageAnalysis` delivers on a background executor, so two frames decoded either side of a
     * non-atomic check would both pass it — and this only misbehaves on a fast device holding a
     * steady QR, which is the case where the scanner is working best.
     */
    @Test
    fun `concurrent frames yield exactly one invitation`() {
        val barcodes = PairingBarcodes()
        val threads = 16
        val pool = Executors.newFixedThreadPool(threads)

        try {
            val results =
                pool.invokeAll(List(threads) { Callable { barcodes.accept(listOf(invitation)) } })

            val accepted = results.count { it.get(5, TimeUnit.SECONDS) != null }

            assertEquals(1, accepted, "the code would have been redeemed $accepted times")
        } finally {
            pool.shutdownNow()
        }
    }
}
