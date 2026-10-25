package com.zcode.android.features.conversation

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.features.conversation.ui.YouBlock
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * W1 (verification pass 3, 2026-10-22, live-found on the rig): a YOU message
 * carrying an attachment rendered the message text INVISIBLE — the bubble's
 * content stacked the text and the attachment thumbnail in ONE Box (children
 * share the top-start origin; the Spacer between them is meaningless there), so
 * the thumbnail drew over the text and only its last glyph escaped past the
 * image's right edge (live: "here is the screenshot" reduced to a 9px "t").
 * The bubble contract is chat-standard: the text first, the attachments BELOW
 * it — never overlapping.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class YouAttachmentLayoutTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun attachmentThumbnailRendersBelowTheMessageText_neverOverlapping() {
        rule.setContent {
            ZCodeTheme {
                YouBlock(
                    text = "here is the screenshot",
                    createdAtMs = null,
                    attachments = listOf(
                        ConversationRow.UserInput.AttachmentMeta(
                            ref = "r1", fileName = "mock.png", mime = "image/png",
                            bytes = 64, previewRef = null,
                        ),
                    ),
                    loadAttachment = { null },
                )
            }
        }
        rule.waitForIdle()
        // The bubble merges descendants (long-press semantics) — read BOTH the text
        // and the thumbnail from the unmerged tree (the merged text node's bounds
        // are the whole bubble's).
        val textBounds = rule.onNodeWithText("here is the screenshot", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val attBounds = rule.onAllNodesWithTag("row_attachment_r1", useUnmergedTree = true)[0]
            .fetchSemanticsNode().boundsInRoot
        // The attachment sits BELOW the text (its top at or under the text's bottom,
        // modulo the 8dp gap) and shares no vertical band with it.
        assertTrue(
            attBounds.top >= textBounds.bottom - 1f,
            "attachment top ${attBounds.top} must be at or below the text bottom ${textBounds.bottom} " +
                "(text=$textBounds attachment=$attBounds)",
        )
    }
}
