package com.zcode.android.features.conversation

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.features.conversation.ui.decodeAttachmentThumbnail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * N-BJ-1 (pass-0 Phase 7) decode-cap unit pins over the new `decodeAttachmentThumbnail`
 * sampler (names the new function — lands with the fix; the pre-fix RED is the
 * source-shape pin in Pass0Phase7AttachmentChipFacesTest). The report's 1×1 seed
 * stays exact (never upscaled); a 3200×2400 photo decodes bounded with the aspect
 * preserved; garbage bytes return null (the honest-empty family).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class Pass0Phase7AttachmentDecodeCapTest {

    private val tinyPng = java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4z8AAAAMBAQDJ/pLvAAAAAElFTkSuQmCC",
    )

    /** A solid-color WxH PNG via android.graphics (Robolectric-supported). */
    private fun bigPng(w: Int, h: Int): ByteArray {
        val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.RED)
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    @Test
    fun `the sampler downscales a huge image within the max dimension`() {
        val decoded = decodeAttachmentThumbnail(bigPng(3200, 2400))
        assertNotNull("the big image must decode", decoded)
        assertTrue(
            "the sampled decode must respect the max dimension: ${decoded!!.width}×${decoded.height}",
            decoded.width <= 1024 && decoded.height <= 1024,
        )
        assertTrue(
            "aspect must be preserved (±1 sample rounding): ${decoded.width}×${decoded.height}",
            Math.abs((decoded.width.toFloat() / decoded.height) - (3200f / 2400f)) < 0.1f,
        )
    }

    @Test
    fun `the report's 1x1 seed stays exact - never upscaled`() {
        val decoded = decodeAttachmentThumbnail(tinyPng)
        assertNotNull(decoded)
        assertEquals(1, decoded!!.width)
        assertEquals(1, decoded.height)
    }

    @Test
    fun `a portrait image keeps its aspect`() {
        val decoded = decodeAttachmentThumbnail(bigPng(1200, 3000))
        assertNotNull(decoded)
        assertTrue(
            "portrait sampled decode must stay bounded: ${decoded!!.width}×${decoded.height}",
            decoded.width <= 1024 && decoded.height <= 1024,
        )
        assertTrue("the portrait orientation must survive the sample", decoded.height > decoded.width)
    }

    // NOTE: garbage/empty bytes → null (the honest-empty family, the outWidth<=0 guard)
    // is NOT pinnable here — Robolectric's BitmapFactory shadow "decodes" any byte
    // array into a dummy bitmap, so the guard is a device-behavior path only.
}
