package com.zcode.android.features.conversation

import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.features.conversation.ui.ToolCard
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * G36 (vc51) — the presentation-mode gate (lib/interfaceMode.ts): OFFICE collapses
 * tool cards to their summaries (name/status/meta — no expand into INPUT/output);
 * CODING (default) renders everything. The desktop's catalog copy: "Office mode
 * focuses on summaries and results. Coding mode shows commands, output, and code
 * changes."
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class InterfaceModeGateTest {

    @get:Rule
    val rule = createComposeRule()

    private fun toolRow() = ConversationRow.ToolCall(
        rowId = 41L,
        turnId = "t-1",
        base = ConversationRow.Base(createdAt = 1.0, createdAtSeq = 41L),
        assistantResponseId = null,
        toolCallId = "tc-41",
        toolName = "Bash",
        status = ConversationRow.ToolCallStatus.SUCCESS,
        inputText = "gradle test",
        input = kotlinx.serialization.json.buildJsonObject { },
        outputPreview = kotlinx.serialization.json.buildJsonObject {
            put("text", kotlinx.serialization.json.JsonPrimitive("BUILD SUCCESSFUL"))
        },
        error = null,
        approvalInteractionId = null,
        startedAt = 1.0,
        endedAt = 2.0,
        raw = kotlinx.serialization.json.JsonObject(emptyMap()),
    )

    @Test
    fun `coding mode expands the tool card into its INPUT and output detail`() {
        rule.setContent {
            ZCodeTheme(themeMode = com.zcode.android.core.ui.theme.ThemeMode.LIGHT) {
                ToolCard(row = toolRow(), showDetails = true)
            }
        }
        rule.onNodeWithTag("row_tool_Bash").performClick()
        rule.waitForIdle()
        // The expand reveals the INPUT block (the coding presentation).
        rule.onNodeWithTag("tool_input_41", useUnmergedTree = true).fetchSemanticsNode()
    }

    @Test
    fun `office mode keeps the card collapsed at its summary - no detail ever`() {
        rule.setContent {
            ZCodeTheme(themeMode = com.zcode.android.core.ui.theme.ThemeMode.LIGHT) {
                ToolCard(row = toolRow(), showDetails = false)
            }
        }
        // Taps do nothing: the card stays summary-only (name/status/meta).
        rule.onNodeWithTag("row_tool_Bash").performClick()
        rule.waitForIdle()
        assertTrue(
            rule.onAllNodesWithTag("tool_input_41", useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
        )
    }
}

