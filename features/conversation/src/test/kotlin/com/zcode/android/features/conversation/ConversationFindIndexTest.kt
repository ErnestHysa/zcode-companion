package com.zcode.android.features.conversation

import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.features.conversation.ui.annotatedWithHighlights
import com.zcode.android.features.conversation.ui.buildConversationFindIndex
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * G5 (vc51) — the roadmap's find-index pin OVER TIMELINE ROWS: matches span user inputs
 * and assistant prose in timeline order, every occurrence per row, case-insensitive;
 * non-text rows never match; an empty/blank query is an empty index; the highlight
 * annotator wraps exactly the matched ranges.
 */
class ConversationFindIndexTest {

    private var seq = 0L
    private fun base() = ConversationRow.Base(createdAt = 1.0, createdAtSeq = ++seq)

    private fun user(id: Long, text: String) = ConversationRow.UserInput(
        rowId = id, turnId = "t$id", base = base(), text = text, epilogueStart = null,
        origin = "user", originMeta = null, guided = false, sourceCommandId = null,
        attachments = emptyList(), raw = buildJsonObject {},
    )

    private fun agent(id: Long, text: String) = ConversationRow.AssistantText(
        rowId = id, turnId = "t$id", base = base(), assistantResponseId = null,
        text = text, state = ConversationRow.TextState.COMPLETE, model = null, raw = buildJsonObject {},
    )

    private fun tool(id: Long) = ConversationRow.ToolCall(
        rowId = id, turnId = "t$id", base = base(), assistantResponseId = null,
        toolCallId = "tc$id", toolName = "bash", status = ConversationRow.ToolCallStatus.SUCCESS,
        inputText = "echo release", input = null, outputPreview = null, error = null,
        approvalInteractionId = null, startedAt = null, endedAt = null,
        raw = buildJsonObject {},
    )

    @Test
    fun `matches span user and assistant rows in timeline order`() {
        val rows = listOf(user(1, "ship the release"), agent(2, "The RELEASE is shipped"), tool(3), user(4, "release notes too"))
        val matches = buildConversationFindIndex(rows, "release")
        assertEquals(listOf(1L, 2L, 4L), matches.map { it.rowId })
        assertTrue(matches.all { it.rowKind != "bash" })
    }

    @Test
    fun `every occurrence per row is indexed with offsets`() {
        val matches = buildConversationFindIndex(listOf(user(1, "run run run")), "run")
        assertEquals(3, matches.size)
        assertEquals(listOf(0, 1, 2), matches.map { it.occurrence })
        assertEquals(listOf(0 to 3, 4 to 7, 8 to 11), matches.map { it.start to it.end })
    }

    @Test
    fun `blank query and no-hit query produce an empty index`() {
        assertTrue(buildConversationFindIndex(listOf(user(1, "hello")), "").isEmpty())
        assertTrue(buildConversationFindIndex(listOf(user(1, "hello")), "   ").isEmpty())
        assertTrue(buildConversationFindIndex(listOf(user(1, "hello")), "zzz").isEmpty())
    }

    @Test
    fun `highlight annotator wraps exactly the matched ranges`() {
        val styled = annotatedWithHighlights("Ship the release", "release", androidx.compose.ui.text.SpanStyle(background = androidx.compose.ui.graphics.Color.Red))
        assertEquals("Ship the release", styled.text)
        assertEquals(1, styled.spanStyles.size)
        assertEquals(9, styled.spanStyles.first().start)
        assertEquals(16, styled.spanStyles.first().end)
        assertEquals(0, annotatedWithHighlights("text", "", androidx.compose.ui.text.SpanStyle()).spanStyles.size)
    }
}
