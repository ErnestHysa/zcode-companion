package com.zcode.android.features.pairing.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F-QR: the pairing scanner's own decoder must round-trip the ONLY thing it will
 * ever be asked to read — the desktop's pairing-link QR — including through the
 * Y-plane + rotation path the camera actually delivers.
 */
class QrFrameDecoderTest {

    private val link =
        "http://10.0.2.2:18100/remote/v4?sid=d_SyntheticSid000001&hash=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8%3D&t=1792803505987&mid=mock-desktop-mid&name=MockBook&app_version=3.14.3"

    private fun qrPixels(text: String, size: Int): IntArray {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        val pixels = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            pixels[y * size + x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        return pixels
    }

    @Test
    fun `the pairing link QR round-trips through the ARGB decoder`() {
        val size = 480
        val text = QrFrameDecoder.decodeArgb(qrPixels(link, size), size, size)
        assertEquals(link, text)
    }

    @Test
    fun `a 90-degree-rotated camera frame still decodes through the Y-plane path`() {
        val size = 64
        val pixels = qrPixels("zcode-pair-1", size)
        // The camera delivers rows rotated 90 degrees CLOCK-WISE; pack them as a
        // Y plane (stride = size) and tell the decoder the frame is rotated.
        val rotated = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            rotated[x * size + (size - 1 - y)] = pixels[y * size + x]
        }
        val y = ByteArray(size * size)
        for (i in y.indices) y[i] = (if (rotated[i] == 0xFF000000.toInt()) 0 else 255).toByte()
        val text = QrFrameDecoder.decodeYPlane(y, rowStride = size, width = size, height = size, rotationDegrees = 90)
        assertEquals("zcode-pair-1", text)
    }

    @Test
    fun `a frame without a QR decodes to null - the analyzer retries, never throws`() {
        val size = 64
        val grey = IntArray(size * size) { 0xFF808080.toInt() }
        assertEquals(null, QrFrameDecoder.decodeArgb(grey, size, size))
    }
}
