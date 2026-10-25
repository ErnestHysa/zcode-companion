package com.zcode.android.features.pairing.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * Pure-ZXing QR decoder for the pairing camera frames (F-QR, field 2026-10-25).
 * The GMS-delivered ML Kit detector crashed unrecoverably inside its own
 * downloaded code (NPE in the dynamite module, release AND debug, two devices) —
 * code nothing app-side can pin or patch. Pairing scans exactly one format (the
 * desktop's pairing QR), so the app now ships its own scanner: one small
 * dependency, no native code, no Play services, deterministic on every device.
 */
object QrFrameDecoder {

    private val reader = QRCodeReader()
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )

    /**
     * Decode a QR from a camera Y plane (YUV_420_888 plane 0). [rowStride] is the
     * plane's row stride (cameras pad rows); [rotationDegrees] is the frame's
     * clock-wise rotation from the upright scan orientation. Returns the raw QR
     * payload, or null when this frame holds no readable QR (the analyzer simply
     * retries on the next frame).
     */
    fun decodeYPlane(
        y: ByteArray,
        rowStride: Int,
        width: Int,
        height: Int,
        rotationDegrees: Int,
    ): String? {
        // Un-pad the plane into packed rows (copy row-by-row over the stride).
        val packed = ByteArray(width * height)
        var inIdx = 0
        var outIdx = 0
        for (row in 0 until height) {
            System.arraycopy(y, inIdx, packed, outIdx, width)
            inIdx += rowStride
            outIdx += width
        }
        // PlanarYUVLuminanceSource cannot rotate — orient the luminance matrix here.
        var matrix = packed
        var matrixWidth = width
        var matrixHeight = height
        repeat(((rotationDegrees % 360) + 360) % 360 / 90) {
            val dst = ByteArray(matrix.size)
            val nw = matrixHeight
            val nh = matrixWidth
            for (r in 0 until nh) for (c in 0 until nw) {
                dst[r * nw + c] = matrix[c * matrixWidth + (matrixWidth - 1 - r)]
            }
            matrix = dst
            matrixWidth = nw
            matrixHeight = nh
        }
        return decode(PlanarYUVLuminanceSource(matrix, matrixWidth, matrixHeight, 0, 0, matrixWidth, matrixHeight, false))
    }

    /** Decode a QR from an ARGB pixel grid — the JVM round-trip pin's seam. */
    fun decodeArgb(pixels: IntArray, width: Int, height: Int): String? =
        decode(RGBLuminanceSource(width, height, pixels))

    private fun decode(source: LuminanceSource): String? {
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        return try {
            reader.decode(bitmap, hints).text
        } catch (_: Exception) {
            null
        } finally {
            reader.reset()
        }
    }
}
