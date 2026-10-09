package com.zcode.android.domain

import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.domain.model.RowDecodeException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * §43.2-4.1 model suite — typed row decode fixtures with unknown tolerance (INV-8).
 * Fixture shapes derive from the VERIFIED rows.ts schema (v3.14.3 @ 29628c9, read
 * 2026-09-28); unknown-field/unknown-kind/unknown-enum cases are INV-8/INV-14 probes.
 */
class ConversationRowDecoderTest {

    private val json = Json

    private fun obj(s: String): JsonObject = json.parseToJsonElement(s).jsonObject

    private val baseExtra = """
      "rowId": 7, "turnId": "turn-1", "createdAt": 1700000000000, "createdAtSeq": 12,
      "entityId": "ent-1", "productTurnId": "pt-1", "visibility": "visible"
    """.trimIndent()

    @Test
    fun `turnHeader decodes with file summary and actions`() {
        val row = ConversationRow.Decoder.decode(
            obj(
                """{$baseExtra, "kind":"turnHeader", "origin":"userInput", "executionKind":"agent",
                   "state":"running", "startedAt":1700000000001, "activeMs":4200,
                   "fileChanges":{"additions":12,"deletions":3,"files":2,"state":"active"},
                   "actions":{"canFork":true,"canEdit":true,"editDisposition":"rewind"},
                   "futureField":{"x":1}}""",
            ),
        )
        row as ConversationRow.TurnHeader
        assertEquals(7, row.rowId)
        assertEquals("running", row.state.wire)
        assertEquals(12, row.fileChanges?.additions)
        assertEquals(2, row.fileChanges?.files)
        assertEquals(true, row.base.actions?.canFork)
        assertEquals("rewind", row.base.actions?.editDisposition)
        // INV-8: unknown fields retained in raw
        assertTrue("futureField" in row.raw)
    }

    @Test
    fun `userInput decodes origin meta and attachments`() {
        val row = ConversationRow.Decoder.decode(
            obj(
                """{$baseExtra, "kind":"userInput", "text":"fix the bug", "origin":"realUser",
                   "epilogueStart":11,
                   "originMeta":{"backgroundSource":"workflow","workId":"w-1","senderLabel":"hub"},
                   "guided":true, "sourceCommandId":"cmd-9",
                   "attachments":[{"ref":"a-1","fileName":"log.txt","mime":"text/plain","bytes":33,"previewRef":"p-1"}]}""",
            ),
        )
        row as ConversationRow.UserInput
        assertEquals("fix the bug", row.text)
        assertEquals(11, row.epilogueStart)
        assertEquals("workflow", row.originMeta?.backgroundSource)
        assertTrue(row.guided)
        assertEquals(1, row.attachments.size)
        assertEquals("log.txt", row.attachments[0].fileName)
    }

    @Test
    fun `assistantText and reasoning decode with tolerant states`() {
        val at = ConversationRow.Decoder.decode(
            obj("""{$baseExtra, "kind":"assistantText", "text":"hello", "state":"streaming", "model":"glm-5.3"}"""),
        ) as ConversationRow.AssistantText
        assertEquals(ConversationRow.TextState.STREAMING, at.state)
        assertEquals("glm-5.3", at.model)

        // future state value → tolerant UNKNOWN carrying raw (INV-8)
        val future = ConversationRow.Decoder.decode(
            obj("""{$baseExtra, "kind":"assistantText", "text":"x", "state":"someFutureState"}"""),
        ) as ConversationRow.AssistantText
        assertEquals(ConversationRow.TextState.UNKNOWN, future.state)
        assertEquals("someFutureState", (future.raw["state"] as kotlinx.serialization.json.JsonPrimitive).content)

        val r = ConversationRow.Decoder.decode(
            obj("""{$baseExtra, "kind":"reasoning", "text":"thinking", "state":"complete", "durationMs":1200}"""),
        ) as ConversationRow.Reasoning
        assertEquals(1200, r.durationMs)
    }

    @Test
    fun `toolCall decodes status input error and interaction link`() {
        val row = ConversationRow.Decoder.decode(
            obj(
                """{$baseExtra, "kind":"toolCall", "toolCallId":"tc-1", "toolName":"Bash",
                   "status":"running", "inputText":"ls -la", "input":{"command":"ls -la"},
                   "assistantResponseId":"resp-1",
                   "error":{"code":"E","message":"boom"}, "approvalInteractionId":"int-1",
                   "startedAt":1700000000002}""",
            ),
        )
        row as ConversationRow.ToolCall
        assertEquals("Bash", row.toolName)
        assertEquals(ConversationRow.ToolCallStatus.RUNNING, row.status)
        assertEquals("ls -la", row.inputText)
        assertEquals("E", row.error?.code)
        assertEquals("int-1", row.approvalInteractionId)

        // unknown future status → tolerant (INV-8; §19.5 generic fallback)
        val future = ConversationRow.Decoder.decode(
            obj("""{$baseExtra, "kind":"toolCall", "toolCallId":"t", "toolName":"X", "status":"awaitingBudget", "inputText":""}"""),
        ) as ConversationRow.ToolCall
        assertEquals(ConversationRow.ToolCallStatus.UNKNOWN, future.status)
        assertNull(future.error)
    }

    @Test
    fun `artifact subagent hookInvocation timelineMarker decode`() {
        val a = ConversationRow.Decoder.decode(
            obj(
                """{$baseExtra, "kind":"artifact", "artifactVersionId":"av1", "logicalArtifactKey":"k",
                   "displayName":"report.pdf", "artifactType":"pdf", "mimeType":"application/pdf",
                   "sizeBytes":1234, "sha256":"${"a".repeat(64)}", "ref":"ref-1", "state":"current"}""",
            ),
        ) as ConversationRow.Artifact
        assertEquals("report.pdf", a.displayName)
        assertEquals(1234, a.sizeBytes)

        val s = ConversationRow.Decoder.decode(
            obj("""{$baseExtra, "kind":"subagent", "subagentType":"explore", "status":"success", "summaryText":"done", "childSessionId":"sess-2"}"""),
        ) as ConversationRow.Subagent
        assertEquals("sess-2", s.childSessionId)

        val h = ConversationRow.Decoder.decode(
            obj(
                """{$baseExtra, "kind":"hookInvocation", "hookInvocationId":"h1", "hookEventName":"PreToolUse",
                   "hookCount":2, "state":"completed", "lane":"toolBefore", "executions":[]}""",
            ),
        ) as ConversationRow.HookInvocation
        assertEquals(ConversationRow.HookState.COMPLETED, h.state)
        assertEquals(0, h.executions.size)

        val m = ConversationRow.Decoder.decode(
            obj("""{$baseExtra, "kind":"timelineMarker", "lane":"assistantWork", "marker":{"type":"compact","origin":"auto","status":"success"}}"""),
        ) as ConversationRow.TimelineMarker
        assertEquals("compact", m.markerType)
        assertEquals("assistantWork", m.lane)
    }

    @Test
    fun `unknown kind decodes as Unknown and is COUNTED (INV-8)`() {
        val before = ConversationRow.Decoder.unknownKindCount
        val row = ConversationRow.Decoder.decode(
            obj("""{$baseExtra, "kind":"workflowRunCard", "payload":{"anything":true}}"""),
        )
        assertIs<ConversationRow.Unknown>(row)
        assertEquals("workflowRunCard", row.unknownKind)
        assertEquals(7, row.rowId)
        assertTrue("payload" in row.raw)
        assertEquals(before + 1, ConversationRow.Decoder.unknownKindCount)
    }

    @Test
    fun `known kind with missing REQUIRED field fails typed (INV-14 fail closed)`() {
        // assistantText without required `text`
        assertFailsWith<RowDecodeException> {
            ConversationRow.Decoder.decode(obj("""{$baseExtra, "kind":"assistantText", "state":"complete"}"""))
        }
        // toolCall without required toolCallId
        assertFailsWith<RowDecodeException> {
            ConversationRow.Decoder.decode(obj("""{$baseExtra, "kind":"toolCall", "toolName":"Bash", "status":"running", "inputText":""}"""))
        }
        // turnHeader without required startedAt
        assertFailsWith<RowDecodeException> {
            ConversationRow.Decoder.decode(obj("""{$baseExtra, "kind":"turnHeader", "origin":"userInput", "state":"running"}"""))
        }
        // non-primitive where a string belongs
        assertFailsWith<RowDecodeException> {
            ConversationRow.Decoder.decode(obj("""{$baseExtra, "kind":"assistantText", "text":{"obj":true}, "state":"complete"}"""))
        }
    }

    @Test
    fun `every known kind constant matches the verified rows set`() {
        assertEquals(
            setOf(
                "turnHeader", "userInput", "assistantText", "reasoning", "toolCall",
                "artifact", "subagent", "hookInvocation", "timelineMarker",
            ),
            ConversationRow.Kinds.ALL,
        )
    }
}
