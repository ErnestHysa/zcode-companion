package com.zcode.android.features.conversation

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.features.conversation.ui.AttachmentViewerDialog
import com.zcode.android.features.conversation.ui.YouBlock
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * G28 (vc51 Phase 4D) — the inbound attachment affordances. Pins: an image thumbnail
 * TAP opens the fullscreen zoom viewer (the recorded dead-end shape: no clickable
 * modifier at all); the viewer decodes the full bytes and its Close consumes it; a
 * PDF/video chip fires the external-viewer handoff; with no callbacks wired the
 * dead-end is preserved (the null default).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class AttachmentViewerUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val tinyPng = java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4z8AAAAMBAQDJ/pLvAAAAAElFTkSuQmCC",
    )

    private fun imageAtt() = ConversationRow.UserInput.AttachmentMeta(
        ref = "att-img-1", fileName = "diagram.png", mime = "image/png", bytes = tinyPng.size.toLong(), previewRef = null,
    )

    private fun pdfAtt() = ConversationRow.UserInput.AttachmentMeta(
        ref = "att-pdf-1", fileName = "report.pdf", mime = "application/pdf", bytes = 2048, previewRef = null,
    )

    @Test
    fun tappingTheImageThumbnail_opensTheZoomViewer() {
        val opened = mutableListOf<String>()
        rule.setContent {
            ZCodeTheme {
                Column {
                    YouBlock(
                        text = "see attached",
                        attachments = listOf(imageAtt()),
                        loadAttachment = { tinyPng },
                        onOpenAttachment = { opened += it.ref },
                    )
                }
            }
        }
        rule.onNodeWithTag("row_attachment_att-img-1").performClick()
        rule.waitForIdle()
        assertEquals(listOf("att-img-1"), opened)
    }

    @Test
    fun viewerDialog_decodesBytes_andCloseConsumes() {
        rule.setContent { ZCodeTheme { AttachmentViewerDialog(att = imageAtt(), loader = { tinyPng }, onDismiss = {}) } }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("attachment_viewer_image", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onAllNodesWithTag("attachment_viewer_image", useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun pdfChip_firesTheExternalHandoff() {
        val handed = mutableListOf<String>()
        rule.setContent {
            ZCodeTheme {
                YouBlock(
                    text = "the report",
                    attachments = listOf(pdfAtt()),
                    loadAttachment = { null },
                    onOpenAttachmentExternally = { handed += it.ref },
                )
            }
        }
        rule.onNodeWithTag("row_attachment_att-pdf-1").performClick()
        rule.waitForIdle()
        assertEquals(listOf("att-pdf-1"), handed)
        rule.onNodeWithText("report.pdf").assertIsDisplayed()
    }

    @Test
    fun noCallbacks_deadEndPreserved() {
        rule.setContent {
            ZCodeTheme {
                YouBlock(
                    text = "legacy",
                    attachments = listOf(imageAtt()),
                    loadAttachment = { tinyPng },
                )
            }
        }
        // no crash on tap with the callbacks absent — and no viewer opens (the default nulls;
        // without the clickable the thumbnail only exists on the unmerged tree)
        rule.onNodeWithTag("row_attachment_att-img-1", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        rule.onAllNodesWithTag("attachment_viewer", useUnmergedTree = true).assertCountEquals(0)
    }
}
