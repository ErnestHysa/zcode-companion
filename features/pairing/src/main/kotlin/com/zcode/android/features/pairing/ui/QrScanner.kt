package com.zcode.android.features.pairing.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.zcode.android.features.pairing.qr.QrFrameDecoder
import java.util.concurrent.Executors

/**
 * F-34-C (vc52 Phase 8): the scanner's camera binding, behind a seam so the disposal
 * contract is testable without a camera HAL. The default resolves CameraX's
 * [ProcessCameraProvider] asynchronously (main executor).
 */
interface QrCameraBinding {
    fun bind(lifecycleOwner: LifecycleOwner, vararg useCases: UseCase)
    fun unbindAll()
}

/** Resolves a [QrCameraBinding] and hands it to `onReady` on the main thread (or never). */
typealias QrCameraBindingSource = (context: android.content.Context, onReady: (QrCameraBinding) -> Unit) -> Unit

val ProcessCameraBindingSource: QrCameraBindingSource = { ctx, onReady ->
    val providerFuture = ProcessCameraProvider.getInstance(ctx)
    providerFuture.addListener({
        // A camera-less environment (Robolectric, a failed HAL) degrades to a dead
        // preview + the paste-link fallback — never a crash.
        val provider = runCatching { providerFuture.get() }.getOrNull() ?: return@addListener
        onReady(object : QrCameraBinding {
            override fun bind(lifecycleOwner: LifecycleOwner, vararg useCases: UseCase) {
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, *useCases)
            }

            override fun unbindAll() = provider.unbindAll()
        })
    }, ContextCompat.getMainExecutor(ctx))
}

/**
 * QR scanner — CameraX preview + bundled ML Kit barcode analysis (§10.1: bundled
 * works off-GMS). Emits decoded raw strings through [onDecoded]; duplicate frames of
 * the same code are suppressed upstream by the flow state (Validating).
 *
 * F-34-C (privacy): the use cases are bound to the host's (Activity) lifecycle, which
 * outlives this composable — leaving the scanner for manual entry kept the camera AND
 * the analysis running ~7.5 minutes (through pairing, onto the paired Tasks screen; the
 * green camera indicator lit throughout). The camera now stops the moment the scanner
 * leaves composition.
 */
@Composable
fun QrScanner(
    lifecycleOwner: LifecycleOwner,
    onDecoded: (String) -> Unit,
    cameraBindingSource: QrCameraBindingSource = ProcessCameraBindingSource,
) {
    val context = LocalContext.current
    // FIELD FIX (2026-09-30): the host owns the permission flow (ScannerWithPermission
    // composes this only when CAMERA is granted). Bind without permission and CameraX
    // fails inside the provider future — a dead preview. Belt-and-braces: refuse to
    // compose the preview without the grant, and the host's own launcher stays the
    // single permission surface.
    val hasPermission = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }
    if (!hasPermission) return
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val disposed = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val bound = remember { java.util.concurrent.atomic.AtomicReference<QrCameraBinding?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            // F-34-C: leaving the scanner STOPS the camera — unbind the use cases (the
            // Activity lifecycle they were bound to would keep them running).
            disposed.set(true)
            bound.getAndSet(null)?.unbindAll()
            analysisExecutor.shutdown()
        }
    }
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(360.dp).testTag("qr-preview"),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            cameraBindingSource(ctx) { binding ->
                // The provider can resolve AFTER the user already left the scanner.
                if (disposed.get()) return@cameraBindingSource
                runCatching {
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(analysisExecutor) { proxy ->
                        decodeQr(proxy) { raw -> onDecoded(raw) }
                    }
                    binding.unbindAll()
                    binding.bind(lifecycleOwner, preview, analysis)
                    bound.set(binding)
                }
            }
            previewView
        },
    )
}

private fun decodeQr(proxy: ImageProxy, onDecoded: (String) -> Unit) {
    try {
        val media = proxy.image ?: return
        val plane = proxy.planes[0]
        val y = ByteArray(plane.buffer.remaining()).also { plane.buffer.get(it) }
        val text = QrFrameDecoder.decodeYPlane(
            y = y,
            rowStride = plane.rowStride,
            width = proxy.width,
            height = proxy.height,
            rotationDegrees = proxy.imageInfo.rotationDegrees,
        )
        if (text != null) onDecoded(text)
    } catch (_: Throwable) {
        // a single undecodable frame never kills the scanner — the next frame retries
    } finally {
        proxy.close()
    }
}
