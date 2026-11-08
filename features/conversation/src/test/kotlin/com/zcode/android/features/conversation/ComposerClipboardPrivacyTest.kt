package com.zcode.android.features.conversation

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.features.conversation.port.ConversationPort
import com.zcode.android.features.conversation.ui.ComposerBar
import com.zcode.android.features.conversation.ui.clipboardHasImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowClipboardManager
import java.io.File

/**
 * F-33-F (vc52 Phase 6, P3, privacy) — threat: every conversation open pulled the FULL
 * clipboard (passwords, OTPs, whatever another app copied) into this process just to test
 * a MIME type; on Android 12+ each read raises the system "pasted from your clipboard"
 * toast and notes the READ_CLIPBOARD app-op (pass 33: access time reset to 3 s, then 2 s,
 * after two opens). The open path must read only the clip's DESCRIPTION (no data, no
 * note, no toast); the data is read only on an explicit Paste tap.
 *
 * [CountingClipboard] counts every `getPrimaryClip()` — the framework's DATA read.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], shadows = [ComposerClipboardPrivacyTest.CountingClipboard::class])
class ComposerClipboardPrivacyTest {

    @Implements(ClipboardManager::class)
    class CountingClipboard : ShadowClipboardManager() {
        @Implementation
        override fun getPrimaryClip(): ClipData? {
            dataReads++
            return super.getPrimaryClip()
        }

        companion object {
            @Volatile @JvmStatic var dataReads = 0
        }
    }

    @get:Rule
    val rule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun resetCounter() {
        CountingClipboard.dataReads = 0
    }

    /** An image on the clipboard (a real file the paste path can read), counter zeroed. */
    private fun putImageClip(): File {
        val png = File(context.cacheDir, "clip-shot.png").apply { writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)) }
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
            ClipData(ClipDescription("shot", arrayOf("image/png")), ClipData.Item(Uri.fromFile(png))),
        )
        CountingClipboard.dataReads = 0
        return png
    }

    private fun composer(
        onUpload: suspend (String, String, ByteArray) -> ConversationPort.UploadResult,
    ): @androidx.compose.runtime.Composable () -> Unit = {
        ComposerBar(
            state = ConversationUiState(),
            onDraftChange = {},
            onSend = {},
            onStop = {},
            onRetrySend = {},
            onDismissNotice = {},
            onUploadAttachment = onUpload,
        )
    }

    @Test
    fun `F-33-F - the open-path probe reads only the clip description, never its data`() {
        putImageClip()
        assertTrue("an image clip must still be detected", clipboardHasImage(context))
        assertEquals(
            "the probe pulled the clip DATA into the process (READ_CLIPBOARD noted + the paste toast)",
            0,
            CountingClipboard.dataReads,
        )
    }

    @Test
    fun `F-33-F - ten conversation opens never read the clipboard data, and the paste chip still offers`() {
        putImageClip()
        val opens = mutableStateOf(0)
        val body = composer { _, _, _ -> ConversationPort.UploadResult.Failed("unused") }
        rule.setContent { ZCodeTheme { key(opens.value) { body() } } }
        rule.waitForIdle()
        repeat(9) {
            rule.runOnIdle { opens.value++ } // a fresh composer = a fresh open (its probe re-runs)
            rule.waitForIdle()
        }
        rule.onNodeWithTag("composer_attach_paste_chip").assertExists()
        assertEquals("ten opens read the clipboard data", 0, CountingClipboard.dataReads)
    }

    @Test
    fun `F-33-F - the Paste tap reads the clip exactly once and attaches the image`() {
        val png = putImageClip()
        var uploaded: Triple<String, String, Int>? = null
        val body = composer { name, mime, bytes ->
            uploaded = Triple(name, mime, bytes.size)
            ConversationPort.UploadResult.Committed("ref-1", bytes.size.toLong())
        }
        rule.setContent { ZCodeTheme { body() } }
        rule.waitForIdle()
        assertEquals("composing alone must not read the data", 0, CountingClipboard.dataReads)
        rule.onNodeWithTag("composer_attach_paste_chip").performClick()
        rule.waitUntil(5_000) { uploaded != null }
        assertEquals("the explicit Paste is the one data read", 1, CountingClipboard.dataReads)
        assertEquals("image/png", uploaded!!.second)
        assertEquals(png.length().toInt(), uploaded!!.third)
    }
}
