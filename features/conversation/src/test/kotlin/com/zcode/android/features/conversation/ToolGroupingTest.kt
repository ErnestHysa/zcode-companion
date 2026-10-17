package com.zcode.android.features.conversation

import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.features.conversation.ui.TimelineEntry
import com.zcode.android.features.conversation.ui.ToolCategory
import com.zcode.android.features.conversation.ui.ToolGroupUi
import com.zcode.android.features.conversation.ui.groupTimeline
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * I2.7+N2.4 (gaps-and-polish Phase 3C): the phone rendered every tool as a flat
 * ungrouped full-output row; the desktop groups (changesGroup/executeGroup/explore) and
 * carries row metadata ("Explore · 2 searches", durations). The grouping is a PURE map
 * over the timeline so the LazyColumn keeps one item per entry (auto-scroll intact).
 *
 * Group rules pinned here: only ≥2 consecutive terminal ToolCall rows of one category
 * group; RUNNING and PENDING_APPROVAL rows never hide in a group (an approval must
 * always be visible).
 */
class ToolGroupingTest {

    private fun tool(
        id: Long,
        name: String,
        status: ConversationRow.ToolCallStatus = ConversationRow.ToolCallStatus.SUCCESS,
        started: Double? = 1000.0,
        ended: Double? = 2000.0,
        file: String? = null,
    ): ConversationRow.ToolCall = ConversationRow.ToolCall(
        rowId = id,
        turnId = "t",
        base = ConversationRow.Base(createdAt = id.toDouble(), createdAtSeq = id),
        assistantResponseId = null,
        toolCallId = "tool-$id",
        toolName = name,
        status = status,
        inputText = file ?: "",
        input = file?.let { buildJsonObject { put("file_path", it) } },
        outputPreview = null,
        error = null,
        approvalInteractionId = null,
        startedAt = started,
        endedAt = ended,
        raw = buildJsonObject { },
    )

    private fun user(id: Long) = ConversationRow.UserInput(
        rowId = id,
        turnId = "t",
        base = ConversationRow.Base(createdAt = id.toDouble(), createdAtSeq = id),
        text = "go",
        epilogueStart = null,
        origin = "typed",
        originMeta = null,
        guided = false,
        sourceCommandId = null,
        attachments = emptyList(),
        raw = buildJsonObject { },
    )

    @Test
    fun `consecutive terminal tools of one category group under a header`() {
        val rows = listOf(
            user(1),
            tool(2, "Read"), tool(3, "Grep"), tool(4, "Glob"),
            tool(5, "Write", file = "/a.kt"), tool(6, "Edit", file = "/a.kt"), tool(7, "Edit", file = "/b.kt"),
            tool(8, "Bash"), tool(9, "Bash"),
        )
        val entries = groupTimeline(rows)
        val groups = entries.filterIsInstance<TimelineEntry.ToolGroup>()
        assertEquals(listOf(ToolCategory.EXPLORE, ToolCategory.CHANGES, ToolCategory.EXECUTE), groups.map { it.category })
        assertEquals(listOf(3, 3, 2), groups.map { it.rows.size })
        assertEquals(listOf("g-2", "g-5", "g-8"), groups.map { it.key })
    }

    @Test
    fun `running and pending rows never group - approvals stay visible`() {
        val rows = listOf(
            tool(1, "Read", status = ConversationRow.ToolCallStatus.RUNNING),
            tool(2, "Read", status = ConversationRow.ToolCallStatus.RUNNING),
            tool(3, "Read", status = ConversationRow.ToolCallStatus.PENDING_APPROVAL),
            tool(4, "Read", status = ConversationRow.ToolCallStatus.PENDING_APPROVAL),
        )
        val entries = groupTimeline(rows)
        assertTrue(entries.all { it is TimelineEntry.Single })
    }

    @Test
    fun `a single tool stays a single row and category changes split runs`() {
        val entries = groupTimeline(listOf(tool(1, "Read"), tool(2, "Write"), tool(3, "Read")))
        assertEquals(3, entries.size)
        assertTrue(entries.all { it is TimelineEntry.Single })
    }

    @Test
    fun `category vocabulary - changes before execute before explore, known non-file tools stay other`() {
        assertEquals(ToolCategory.CHANGES, ToolCategory.of("Write"))
        assertEquals(ToolCategory.CHANGES, ToolCategory.of("MultiEdit"))
        assertEquals(ToolCategory.EXECUTE, ToolCategory.of("Bash"))
        assertEquals(ToolCategory.EXECUTE, ToolCategory.of("run_command"))
        assertEquals(ToolCategory.EXPLORE, ToolCategory.of("WebFetch"))
        assertEquals(ToolCategory.OTHER, ToolCategory.of("TodoWrite"))
        assertEquals(ToolCategory.OTHER, ToolCategory.of("mcp__notes__add"))
        assertEquals(ToolCategory.OTHER, ToolCategory.of("Skill"))
    }

    @Test
    fun `group ui model counts files for changes and durations for all`() {
        val changes = ToolGroupUi.of(listOf(tool(1, "Write", file = "/a.kt"), tool(2, "Edit", file = "/a.kt"), tool(3, "Edit", file = "/b.kt")))
        assertEquals(2, changes.fileCount)
        assertEquals(3.0, changes.durationSeconds, 0.001)
        val explore = ToolGroupUi.of(listOf(tool(1, "Grep"), tool(2, "Grep")))
        assertNull(explore.fileCount)
        assertEquals(2.0, explore.durationSeconds, 0.001)
        val noTimes = ToolGroupUi.of(listOf(tool(1, "Bash", started = null, ended = null)))
        assertNull(noTimes.durationSeconds)
    }
}
