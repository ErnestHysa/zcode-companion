package com.zcode.android.features.conversation

import com.zcode.android.domain.model.ConversationRow
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A3.16 + L6.1 (Phase 5C) pins: the share-OUT transcript — You/Assistant prose verbatim,
 * tool rows collapsed to one quiet line, reasoning rows OMITTED (a transcript is a reading
 * artifact, not a log), turn separators preserved, attachments named; and the typed
 * share-error taxonomy carries the desktop's client kinds verbatim.
 */
class TranscriptBuilderTest {

    private fun base(seq: Long) = ConversationRow.Base(createdAt = seq.toDouble(), createdAtSeq = seq)

    @Test
    fun `transcript keeps prose collapses tools and omits reasoning`() {
        val rows = listOf(
            ConversationRow.UserInput(
                rowId = 1, turnId = "t1", base = base(1), text = "Ship the release",
                epilogueStart = null, origin = "user", originMeta = null, guided = false, sourceCommandId = null,
                attachments = listOf(ConversationRow.UserInput.AttachmentMeta(ref = "r1", fileName = "notes.md", mime = "text/markdown", bytes = 10, previewRef = null)),
                raw = JsonObject(emptyMap()),
            ),
            ConversationRow.TurnHeader(
                rowId = 2, turnId = "t1", base = base(2), origin = "user", executionKind = null,
                state = ConversationRow.TurnState.COMPLETED_SUCCESS, startedAt = 1.0, endedAt = 2.0,
                activeMs = 4200, sourceCommandId = null, historyRoundCount = null,
                fileChanges = null, raw = JsonObject(emptyMap()),
            ),
            ConversationRow.Reasoning(rowId = 3, turnId = "t1", base = base(3), assistantResponseId = null, text = "secret thoughts", state = ConversationRow.TextState.COMPLETE, durationMs = null, raw = JsonObject(emptyMap())),
            ConversationRow.ToolCall(
                rowId = 4, turnId = "t1", base = base(4), assistantResponseId = null, toolCallId = "tc1",
                toolName = "Bash", status = ConversationRow.ToolCallStatus.SUCCESS, inputText = "", input = null,
                outputPreview = null, error = null, approvalInteractionId = null, startedAt = null, endedAt = null,
                raw = JsonObject(emptyMap()),
            ),
            ConversationRow.AssistantText(rowId = 5, turnId = "t1", base = base(5), assistantResponseId = null, text = "Done — tagged.", state = ConversationRow.TextState.COMPLETE, model = null, raw = JsonObject(emptyMap())),
        )
        val t = TranscriptBuilder.build("Release task", rows)
        assertTrue(t.startsWith("Release task\n"))
        assertTrue("You:\nShip the release" in t)
        assertTrue("[attached: notes.md]" in t)
        assertTrue("Assistant:\nDone — tagged." in t)
        assertTrue("[tool: Bash]" in t)
        assertFalse("secret thoughts" in t, "reasoning stays out of the share artifact")
        assertEquals("Done — tagged.", t.trim().lines().last())
    }

    @Test
    fun `empty rows still render the title header`() {
        val t = TranscriptBuilder.build(null, emptyList())
        assertTrue(t.startsWith("Conversation\n"))
    }

    @Test
    fun `the typed share taxonomy carries the desktop's client kinds`() {
        assertEquals(15, com.zcode.android.domain.model.ConversationShareErrors.ALL.size)
        assertTrue("feature_disabled" in com.zcode.android.domain.model.ConversationShareErrors.ALL, "the PROBE-L6 refusal kind is part of the taxonomy")
        assertTrue("safety_check_pending" in com.zcode.android.domain.model.ConversationShareErrors.ALL)
    }
}
