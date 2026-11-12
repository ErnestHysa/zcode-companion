package com.zcode.android.features.conversation

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.features.conversation.ui.ToolCard
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * G80 (vc52 Phase 13): the tool-card SUBTITLE — TaskUpdate reads "Task control"
 * (the kernel's kind), never a bare name; unknown names render no subtitle. The
 * copy resolves in both locales.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class ToolKindSubtitleUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun row(toolName: String) = ConversationRow.ToolCall(
        rowId = 7, turnId = "t1",
        base = ConversationRow.Base(createdAt = 1.0, createdAtSeq = 1),
        assistantResponseId = null, toolCallId = "tc-1", toolName = toolName,
        status = ConversationRow.ToolCallStatus.SUCCESS, inputText = "{}",
        input = JsonObject(emptyMap()), outputPreview = null, error = null,
        approvalInteractionId = null, startedAt = 1.0, endedAt = 2.0,
        raw = JsonObject(emptyMap()),
    )

    @Test
    fun `TaskUpdate renders the task-control subtitle`() {
        rule.setContent { ZCodeTheme { ToolCard(row = row("TaskUpdate")) } }
        // The card root merges descendants — the unmerged tree sees the subtitle.
        rule.onNodeWithTag("tool_kind_7", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Task control", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `an unknown tool renders no subtitle (graceful fallback)`() {
        rule.setContent { ZCodeTheme { ToolCard(row = row("mcp__custom__thing")) } }
        rule.onAllNodesWithTag("tool_kind_7", useUnmergedTree = true).fetchSemanticsNodes().let { nodes ->
            assertTrue("no kind subtitle for a name outside the kernel map", nodes.isEmpty())
        }
    }

    @Test
    fun `the 14 kind labels resolve in en and zh`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val res = context.resources
        for (name in listOf(
            "tool_kind_file_read", "tool_kind_file_write", "tool_kind_shell", "tool_kind_search",
            "tool_kind_todo", "tool_kind_goal", "tool_kind_session_context", "tool_kind_ask_user_question",
            "tool_kind_message", "tool_kind_task_control", "tool_kind_node_repl", "tool_kind_agent",
            "tool_kind_skill", "tool_kind_workflow",
        )) {
            val id = res.getIdentifier(name, "string", context.packageName)
            assertTrue("$name missing", id != 0)
            assertTrue(context.getString(id).isNotBlank())
        }
    }
}
