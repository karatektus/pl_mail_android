package de.plmail.feature.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import de.plmail.jmap.client.PairingInvitation
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The camera half of pairing.
 *
 * Worth being clear about what this screen is *for*: it exists to remove the worst moment in
 * onboarding, which is copying 71 characters of base16 off a laptop screen onto a phone keyboard.
 * It is not the only way in — a `plmail://` link tapped on the same device skips the camera
 * entirely, and pasting always works — so nothing here is allowed to become a dead end. A refused
 * permission returns to the form rather than trapping the user on a black rectangle.
 *
 * @param onScanned called once, with the invitation, the first time a pairing code is decoded.
 */
@Composable
fun QrScannerScreen(onScanned: (PairingInvitation) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(context.hasCameraPermission()) }
    var refused by remember { mutableStateOf(false) }

    val request =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasPermission = granted
            refused = !granted
        }

    // Asked at the moment the camera is needed rather than at launch, which is
    // the only point at which the request explains itself.
    LaunchedEffect(Unit) {
        if (!hasPermission) request.launch(Manifest.permission.CAMERA)
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { insets ->
        Box(modifier = Modifier.fillMaxSize().padding(insets)) {
            if (hasPermission) {
                CameraPreview(onScanned = onScanned)

                Column(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.scanner_hint),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.scanner_enter_manually))
                    }
                }
            } else {
                PermissionRefused(refused = refused, onBack = onCancel)
            }
        }
    }
}

/**
 * What to show when there is no camera to show.
 *
 * Deliberately not a retry-only screen. Someone who has refused the permission once, or refused it
 * permanently, still has a working route to pairing, and the button that takes them there is the
 * point of this screen rather than an afterthought.
 */
@Composable
private fun PermissionRefused(refused: Boolean, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text =
                stringResource(
                    if (refused) R.string.scanner_no_camera_permission
                    else R.string.scanner_requesting_camera
                ),
            style = MaterialTheme.typography.bodyLarge,
        )
        Button(onClick = onBack) { Text(stringResource(R.string.scanner_enter_manually)) }
    }
}

/**
 * CameraX preview with a QR analyser bound to it.
 *
 * The analyser is configured for QR codes only. Every extra format is another decoder run on every
 * frame, and the ones we would be paying for — barcodes on packaging, mostly — are exactly the ones
 * we would then have to filter out again.
 */
@Composable
private fun CameraPreview(onScanned: (PairingInvitation) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnScanned by rememberUpdatedState(onScanned)

    val previewView = remember { PreviewView(context) }
    val barcodes = remember { PairingBarcodes() }
    val executor: ExecutorService = remember { Executors.newSingleThreadExecutor() }

    // Shut down explicitly. An ImageAnalysis executor that outlives the screen
    // keeps a camera thread alive, and on a device that has already navigated
    // away that shows up as the preview never releasing the camera for the
    // next app that wants it.
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    LaunchedEffect(Unit) {
        // getInstance rather than a suspend helper: the coroutine-friendly
        // variants have moved between CameraX releases, and this one has been
        // the stable entry point throughout.
        val provider = ProcessCameraProvider.getInstance(context).awaitProvider()

        val preview =
            Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }

        val scanner =
            BarcodeScanning.getClient(
                BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
            )

        val analysis =
            ImageAnalysis.Builder()
                // Frames that arrive while one is being decoded are dropped
                // rather than queued. A queue would make the scanner lag behind
                // what the camera is pointed at, which reads as the code not
                // being recognised.
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(executor, QrAnalyzer(scanner, barcodes) { currentOnScanned(it) })
                }

        provider.unbindAll()
        provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            analysis,
        )
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

/**
 * Bridges ML Kit to [PairingBarcodes].
 *
 * The `close()` in the completion handler is not tidiness: `ImageAnalysis` hands out a fixed pool
 * of buffers, and an `ImageProxy` that is never closed stalls the whole pipeline after a frame or
 * two. The symptom is a preview that runs perfectly while nothing is ever decoded again.
 */
private class QrAnalyzer(
    private val scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    private val barcodes: PairingBarcodes,
    private val onInvitation: (PairingInvitation) -> Unit,
) : ImageAnalysis.Analyzer {

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        val frame = image.image
        if (frame == null) {
            image.close()
            return
        }

        scanner
            .process(InputImage.fromMediaImage(frame, image.imageInfo.rotationDegrees))
            .addOnSuccessListener { found ->
                val invitation: PairingInvitation? = barcodes.accept(found.map { it.rawValue })

                // Handed on already parsed. Re-encoding it into a URI only for
                // the ViewModel to parse it again would be a second place for
                // the two to disagree.
                invitation?.let(onInvitation)
            }
            .addOnCompleteListener { image.close() }
    }
}

private fun Context.hasCameraPermission(): Boolean =
    checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/**
 * Awaits a `ListenableFuture` without dragging in coroutines-guava for one call.
 *
 * Cancellable, so leaving the screen while the provider is still initialising does not leave a
 * continuation waiting on a callback nothing will deliver.
 */
private suspend fun <T> ListenableFuture<T>.awaitProvider(): T =
    suspendCancellableCoroutine { continuation ->
        addListener(
            {
                try {
                    continuation.resume(get())
                } catch (failed: ExecutionException) {
                    continuation.resumeWithException(failed.cause ?: failed)
                } catch (interrupted: InterruptedException) {
                    continuation.resumeWithException(interrupted)
                }
            },
            Runnable::run,
        )

        continuation.invokeOnCancellation { cancel(false) }
    }
