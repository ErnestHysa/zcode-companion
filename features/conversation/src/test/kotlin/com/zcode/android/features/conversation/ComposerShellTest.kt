package com.zcode.android.features.conversation

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.features.conversation.ui.ComposerPasteChip
import com.zcode.android.features.conversation.ui.YouBlock
import com.zcode.android.features.conversation.ui.clipboardHasImage
import com.zcode.android.features.conversation.ui.readPickedAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * K3+N2.3+N2.2+A3.1+I5+B2.14 (gaps-and-polish Phase 3D): the composer becomes ONE
 * designed shell — a single add-menu (photo/file/camera/paste/voice), ONE primary
 * control that MORPHS send↔stop on the authoritative `control.canStop`, the queue
 * state in the desktop's TWO places (placeholder swap + inline pill), attachments
 * rendered in-conversation, and picker bytes read OFF the main thread (B2.14 ANR).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class ComposerShellTest {

    @get:Rule
    val rule = createComposeRule()

    // ------------------------------------------------------------------ ONE control morphs

    @Test
    fun `K3 - the primary control morphs - send when idle STOP when canStop - never both`() {
        val src = File(repoRoot(), "features/conversation/src/main/kotlin/com/zcode/android/features/conversation/ui/WriteComponents.kt").readText()
        val composer = src.substringAfter("fun ComposerBar(").substringBefore("fun PendingSendsStrip(")
        // The stop control lives in the send slot: ONE primary button, not a second icon.
        assertTrue("the send/stop morph must be one slot", Regex("""canStop[\s\S]{0,900}composer_stop""").containsMatchIn(composer))
        // The old flat five-button row is gone: attach-doc and voice are menu ITEMS, not siblings.
        val row = composer.substringAfter("Row(verticalAlignment", "").substringBefore("if (voice?.listening")
        assertTrue("attach-doc must be a menu item", "composer_attach_doc" in composer)
        assertTrue("no second flat doc button beside the field", Regex("""IconButton\([\s\S]{0,300}composer_attach_doc""").containsMatchIn(row).not())
    }

    @Test
    fun `K3 - the add menu opens the three pickers as items`() {
        val src = File(repoRoot(), "features/conversation/src/main/kotlin/com/zcode/android/features/conversation/ui/WriteComponents.kt").readText()
        for (tag in listOf("composer_attach", "composer_attach_photo", "composer_attach_doc", "composer_attach_camera", "composer_attach_paste", "composer_voice_menu")) {
            assertTrue("missing $tag", tag in src)
        }
    }

    // ------------------------------------------------------------------ N2.2 queue in two places

    @Test
    fun `N22 - the queue state rides the placeholder and a pill instead of a third strip`() {
        val src = File(repoRoot(), "features/conversation/src/main/kotlin/com/zcode/android/features/conversation/ui/WriteComponents.kt").readText()
        val composer = src.substringAfter("fun ComposerBar(").substringBefore("fun PendingSendsStrip(")
        assertTrue("placeholder keyed on the routing mode", "composer_placeholder_queue" in composer)
        assertTrue("the inline queued pill exists", "composer_queue_pill" in composer)
        assertTrue("the standalone busy strip is replaced", "composer_route_hint" !in composer)
    }

    // ------------------------------------------------------------------ B2.14 off-main I/O

    @Test
    fun `B214 - picker bytes are read off the main thread`() {
        val src = File(repoRoot(), "features/conversation/src/main/kotlin/com/zcode/android/features/conversation/ui/WriteComponents.kt").readText()
        assertTrue("the picker callback must hop to Dispatchers.IO", "withContext(Dispatchers.IO" in src)
    }

    @Test
    fun `B214 - the reader works end to end off main`() = runBlocking {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val tmp = File(context.cacheDir, "picked.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val uri = android.net.Uri.fromFile(tmp)
        val (name, mime, bytes) = withContext(Dispatchers.IO) { context.readPickedAttachment(uri) }
        assertEquals("picked.png", name)
        assertEquals("image/png", mime)
        assertEquals(3, bytes.size)
        tmp.delete()
        Unit
    }

    // ------------------------------------------------------------------ I5 paste chip

    @Test
    fun `I5 - the clipboard probe detects an image and the chip renders for it`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val clip = ClipData(ClipDescription("img", arrayOf("image/png")), ClipData.Item(android.net.Uri.parse("content://x/img")))
        context.getSystemService(Context.CLIPBOARD_SERVICE).let { it as android.content.ClipboardManager }.setPrimaryClip(clip)
        assertEquals(true, clipboardHasImage(context))
        rule.setContent { ZCodeTheme { ComposerPasteChip(visible = true, onPaste = {}) } }
        rule.waitForIdle()
        rule.onNodeWithTag("composer_attach_paste_chip").assertIsDisplayed()
    }

    @Test
    fun `I5 - a text clipboard is not an image probe hit`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val clip = ClipData(ClipDescription("txt", arrayOf("text/plain")), ClipData.Item("hi"))
        context.getSystemService(Context.CLIPBOARD_SERVICE).let { it as android.content.ClipboardManager }.setPrimaryClip(clip)
        assertEquals(false, clipboardHasImage(context))
        rule.setContent { ZCodeTheme { ComposerPasteChip(visible = false, onPaste = {}) } }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithTag("composer_attach_paste_chip").fetchSemanticsNodes().isEmpty())
    }

    // ------------------------------------------------------------------ A3.1 in-conversation attachments

    private fun attachment(mime: String = "image/png", name: String = "shot.png") =
        ConversationRow.UserInput.AttachmentMeta(ref = "att-1", fileName = name, mime = mime, bytes = 1234L, previewRef = null)

    @Test
    fun `A31 - a sent image renders in-conversation`() {
        rule.setContent {
            ZCodeTheme {
                YouBlock(
                    text = "look at this",
                    attachments = listOf(attachment()),
                    loadAttachment = { "bytes".toByteArray() },
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("row_attachment_att-1", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `A31 - a document attachment renders as a file chip with its name`() {
        rule.setContent {
            ZCodeTheme {
                YouBlock(
                    text = "the log",
                    attachments = listOf(attachment(mime = "text/plain", name = "error.log")),
                    loadAttachment = { "bytes".toByteArray() },
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("row_attachment_att-1", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("error.log", substring = true).assertIsDisplayed()
    }

    @Test
    fun `A31 - no attachments means no attachment row`() {
        rule.setContent { ZCodeTheme { YouBlock(text = "plain", attachments = emptyList(), loadAttachment = { null }) } }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithTag("row_attachment_att-1", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `A31 - a failed load still renders the chip`() {
        rule.setContent {
            ZCodeTheme {
                YouBlock(
                    text = "img",
                    attachments = listOf(attachment()),
                    loadAttachment = { null },
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("row_attachment_att-1", useUnmergedTree = true).assertExists()
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return requireNotNull(dir)
    }
}
