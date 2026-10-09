package com.zcode.android.features.pairing.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
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
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/**
 * QR scanner — CameraX preview + bundled ML Kit barcode analysis (§10.1: bundled
 * works off-GMS). Emits decoded raw strings through [onDecoded]; duplicate frames of
 * the same code are suppressed upstream by the flow state (Validating).
 */
@Composable
fun QrScanner(lifecycleOwner: LifecycleOwner, onDecoded: (String) -> Unit) {
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
    // The ML Kit scanner is created inside the view factory (native init — must not run
    // in composition, which Robolectric tests exercise); released with the view.
    val scannerHolder = remember { arrayOfNulls<com.google.mlkit.vision.barcode.BarcodeScanner>(1) }
    DisposableEffect(Unit) {
        onDispose {
            analysisExecutor.shutdown()
            scannerHolder[0]?.close()
        }
    }
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(360.dp).testTag("qr-preview"),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                // ML Kit init rides the camera-bind callback (native-only path): never
                // during composition, so headless/Robolectric environments that compose
                // this view without a camera HAL cannot crash on MlKitContext init.
                val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().build())
                scannerHolder[0] = scanner
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { proxy ->
                    decodeQr(scanner, proxy) { raw -> onDecoded(raw) }
                }
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )
}

private fun decodeQr(
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    proxy: ImageProxy,
    onDecoded: (String) -> Unit,
) {
    val media = proxy.image
    if (media == null) {
        proxy.close()
        return
    }
    val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
    scanner.process(input)
        .addOnSuccessListener { barcodes ->
            for (b in barcodes) {
                b.rawBytes ?: continue
                b.rawValue?.let(onDecoded)
            }
        }
        .addOnCompleteListener { proxy.close() }
}
