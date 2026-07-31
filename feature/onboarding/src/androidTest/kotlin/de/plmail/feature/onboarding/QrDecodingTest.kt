package de.plmail.feature.onboarding

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The decode path, end to end, against a real QR image.
 *
 * `PairingBarcodesTest` covers which barcode gets acted on, but it hands the class strings — it can
 * never show that ML Kit, configured the way the scanner configures it, actually reads a code the
 * server would produce. This does: the asset is a genuine QR encoding a real
 * `plmail://pair?host=…&code=…` URI, decoded by the same scanner options the camera uses.
 *
 * It runs without a camera on purpose. The emulator's `hw.camera.back = emulated` source renders
 * nothing useful, so a test that went through the preview would be testing the emulator rather than
 * the app.
 */
@RunWith(AndroidJUnit4::class)
class QrDecodingTest {

    private val scanner =
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        )

    @Test
    fun aRealPairingQrDecodesIntoAnInvitation() {
        val raw = decode("pairing-qr.png")
        val invitation = PairingBarcodes().accept(raw)

        assertNotNull("the QR did not yield a pairing invitation: $raw", invitation)
        assertEquals("https://nas.local:8443", invitation!!.address.display)
        assertEquals("vJ8kQ2mR-tZ4wX7yB_1nD6pS0aL3fG5h", invitation.code)

        // The address survives the round trip through percent-encoding, which
        // is the thing a naive decoder turns into "https:/nas.local".
        assertEquals(
            "https://nas.local:8443/.well-known/jmap",
            invitation.address.discoveryUrl,
        )
    }

    @Test
    fun theSameQrIsOnlyActedOnOnce() {
        // The camera sees the code in many consecutive frames; a pairing code
        // is single-use, so only the first may be reported.
        val raw = decode("pairing-qr.png")
        val barcodes = PairingBarcodes()

        assertNotNull(barcodes.accept(raw))
        assertNull(barcodes.accept(raw))
        assertNull(barcodes.accept(raw))
    }

    /** Runs the real scanner over an asset and returns what it read. */
    private fun decode(asset: String): List<String?> {
        val context = InstrumentationRegistry.getInstrumentation().context
        val bitmap = context.assets.open(asset).use(BitmapFactory::decodeStream)

        val barcodes =
            Tasks.await(scanner.process(InputImage.fromBitmap(bitmap, 0)), 30, TimeUnit.SECONDS)

        return barcodes.map { it.rawValue }
    }
}
