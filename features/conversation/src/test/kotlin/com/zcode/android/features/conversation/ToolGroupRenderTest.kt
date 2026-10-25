package com.zcode.android.features.conversation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.features.conversation.ui.TimelineEntry
import com.zcode.android.features.conversation.ui.ToolCard
import com.zcode.android.features.conversation.ui.ToolGroupBlock
import com.zcode.android.features.conversation.ui.groupTimeline
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * I2.7+N2.4+C7 (gaps-and-polish Phase 3C): rendered grouping (collapsed header →
 * expand), the metadata line (durations), and "show full output" that loads LAZILY —
 * through the port's slice call when the desktop answers, else the row's own payload
 * (3.14.5.7961 ships `getTaskSnapshotToolCallsSlice` as a `return null` stub — see the
 * appended finding).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class ToolGroupRenderTest {

    @get:Rule
    val rule = createComposeRule()

    /** Scrollable host (the reader scrolls; lazy controls can live below the fold). */
    private fun setContent(content: @androidx.compose.runtime.Composable () -> Unit) {
        rule.setContent {
            ZCodeTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) { content() }
            }
        }
        rule.waitForIdle()
    }

    private fun tool(id: Long, name: String, preview: String? = null, started: Double? = 1000.0, ended: Double? = 2000.0) =
        ConversationRow.ToolCall(
            rowId = id,
            turnId = "t",
            base = ConversationRow.Base(createdAt = id.toDouble(), createdAtSeq = id),
            assistantResponseId = null,
            toolCallId = "tool-$id",
            toolName = name,
            status = ConversationRow.ToolCallStatus.SUCCESS,
            inputText = "ls",
            input = null,
            outputPreview = preview?.let { buildJsonObject { put("text", it) } },
            error = null,
            approvalInteractionId = null,
            startedAt = started,
            endedAt = ended,
            raw = buildJsonObject { },
        )

    @Test
    fun `a settled group renders collapsed - header with counts, children hidden - and expands on tap`() {
        val group = groupTimeline(listOf(tool(1, "Grep"), tool(2, "Grep"), tool(3, "Read"))).single() as TimelineEntry.ToolGroup
        setContent { ToolGroupBlock(group = group) }
        rule.onNodeWithTag("tool_group_g-1").assertIsDisplayed().assertTextContains("3", substring = true)
        assertTrue(rule.onAllNodesWithTag("row_tool_Grep", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        rule.onNodeWithTag("tool_group_g-1").performClick()
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithTag("row_tool_Grep", useUnmergedTree = true).fetchSemanticsNodes().size == 2)
        rule.onNodeWithTag("row_tool_Read", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the group header carries the summed duration`() {
        val group = groupTimeline(listOf(tool(1, "Bash", started = 0.0, ended = 1500.0), tool(2, "Bash", started = 0.0, ended = 1500.0))).single() as TimelineEntry.ToolGroup
        setContent { ToolGroupBlock(group = group) }
        rule.onNodeWithTag("tool_group_g-1").assertTextContains("3.0", substring = true)
    }

    @Test
    fun `a tool card shows its duration on the metadata line`() {
        setContent { ToolCard(row = tool(7, "Bash", started = 0.0, ended = 2300.0)) }
        rule.onNodeWithText("2.3s", substring = true).assertIsDisplayed()
    }

    @Test
    fun `show full output loads lazily - the row payload when the slice is null`() {
        val long = "x".repeat(2_500) + "END-OF-OUTPUT"
        setContent { ToolCard(row = tool(9, "Bash", preview = long), fullOutputProvider = { null }) }
        rule.onNodeWithTag("row_tool_Bash", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        // Capped preview first; the full text is NOT rendered until asked.
        assertTrue(rule.onAllNodesWithTag("tool_full_output_text_9", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        rule.onNodeWithTag("tool_full_output_9", useUnmergedTree = true).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("tool_full_output_text_9", useUnmergedTree = true)
            .performScrollTo().assertTextContains("END-OF-OUTPUT", substring = true)
    }

    @Test
    fun `show full output prefers the slice when the desktop answers`() {
        val long = "y".repeat(2_500)
        setContent { ToolCard(row = tool(11, "Bash", preview = long), fullOutputProvider = { "FROM-SLICE-BODY" }) }
        rule.onNodeWithTag("row_tool_Bash", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("tool_full_output_11", useUnmergedTree = true).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("tool_full_output_text_11", useUnmergedTree = true)
            .performScrollTo().assertTextContains("FROM-SLICE-BODY", substring = true)
    }

    @Test
    fun `a short output offers no full-output button`() {
        setContent { ToolCard(row = tool(13, "Bash", preview = "short"), fullOutputProvider = { null }) }
        rule.onNodeWithTag("row_tool_Bash", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithTag("tool_full_output_13", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }
}
