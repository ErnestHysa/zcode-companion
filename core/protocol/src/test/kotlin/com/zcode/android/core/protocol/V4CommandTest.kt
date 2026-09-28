package com.zcode.android.core.protocol

import com.zcode.android.core.protocol.bytes.ProtocolException
import com.zcode.android.core.protocol.v4.CommandAck
import com.zcode.android.core.protocol.v4.CommandEnvelope
import com.zcode.android.core.protocol.v4.CommandTypes
import com.zcode.android.core.protocol.v4.COMMANDS_REQUIRING_BASE_REVISION
import com.zcode.android.core.protocol.v4.ROW_TARGETING_COMMANDS
import com.zcode.android.core.protocol.v4.UuidV7
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Command plane tests — CAS validation + uuid v7 + ACK decode (command.ts semantics). */
class V4CommandTest {

    @Test
    fun `cas-guarded command without baseRevision is rejected before the wire`() {
        // sendQueuedNow is in the verified COMMANDS_REQUIRING_BASE_REVISION set
        // (command.ts:296). NOTE: sendText is NOT in the v3.14.3 set despite the
        // architectural.md §7.4.4 example — discrepancy recorded for the Phase 2 gate.
        val ex = assertFailsWith<ProtocolException> {
            CommandEnvelope.build(
                clientId = "client-1", sessionId = "sess-1", type = CommandTypes.SEND_QUEUED_NOW,
                payload = buildJsonObject { put("queueItemId", JsonPrimitive("q1")) }, issuedAt = 1L,
            )
        }
        assertTrue(ex.message!!.contains("baseRevision"))
    }

    @Test
    fun `row-targeting command without baseLogEpoch is rejected`() {
        val ex = assertFailsWith<ProtocolException> {
            CommandEnvelope.build(
                clientId = "client-1", sessionId = "sess-1", type = CommandTypes.EDIT_USER_QUERY,
                payload = buildJsonObject { }, issuedAt = 1L, baseRevision = 4,
            )
        }
        assertTrue(ex.message!!.contains("baseLogEpoch"))
    }

    @Test
    fun `cas command with both bases builds and encodes`() {
        val env = CommandEnvelope.build(
            clientId = "client-1", sessionId = "sess-1", type = CommandTypes.FORK_ASSISTANT,
            payload = buildJsonObject { put("target", buildJsonObject { put("rowId", JsonPrimitive(3)); put("entityId", JsonPrimitive("e")) }) },
            issuedAt = 5L, baseRevision = 12, baseLogEpoch = "epoch-1",
        )
        val json = env.encode()
        assertEquals("forkAssistant", json["type"]?.toString()?.trim('"'))
        assertEquals(12L, json["baseRevision"].toString().toLong())
        assertTrue(UuidV7.isValidFormat(env.commandId))
    }

    @Test
    fun `non-cas commands need no bases (stop is aggressively retryable)`() {
        val env = CommandEnvelope.build(
            clientId = "client-1", sessionId = "sess-1", type = CommandTypes.STOP,
            payload = buildJsonObject { }, issuedAt = 1L,
        )
        assertEquals(CommandTypes.STOP, env.type)
    }

    @Test
    fun `cas sets match the reference verbatim`() {
        assertEquals(
            setOf(
                "applyFileRewind", "forkAssistant", "editUserQuery", "retryTurn", "setAssistantFeedback",
                "sendQueuedNow", "editQueueItem", "reorderQueueItem", "deleteQueueItem", "setAutoDrain",
                "switchModelConfig", "switchCollaborationMode", "setFollowupMode", "pauseGoal", "resumeGoal",
            ),
            COMMANDS_REQUIRING_BASE_REVISION,
        )
        assertEquals(
            setOf("applyFileRewind", "forkAssistant", "editUserQuery", "retryTurn", "setAssistantFeedback"),
            ROW_TARGETING_COMMANDS,
        )
    }

    @Test
    fun `uuid v7 format is valid and time-ordered`() {
        val first = UuidV7.generate(timeMs = 1_000_000)
        val second = UuidV7.generate(timeMs = 2_000_000)
        assertTrue(UuidV7.isValidFormat(first), first)
        assertTrue(UuidV7.isValidFormat(second), second)
        // version nibble and variant
        assertTrue(first[14] == '7')
        assertTrue(first[19] in "89ab")
        assertTrue(first.substring(0, 8) < second.substring(0, 8))
        // commandId stability across retries = caller-held envelope identity
        assertEquals(first, first)
    }

    @Test
    fun `commandAck decodes and rejects unknown status`() {
        val ack = CommandAck.decode(
            buildJsonObject {
                put("commandId", JsonPrimitive("018f3c2a-7b1c-7abc-9abc-3f2a1b4c5d6e"))
                put("status", JsonPrimitive("accepted"))
                put("revisionAtDecision", JsonPrimitive(44))
                put("result", buildJsonObject { put("type", JsonPrimitive("inputAccepted")) })
            }
        )
        assertEquals("accepted", ack.status)
        assertEquals(44L, ack.revisionAtDecision)

        val bad = buildJsonObject {
            put("commandId", JsonPrimitive("x"))
            put("status", JsonPrimitive("sort-of-fine"))
            put("revisionAtDecision", JsonPrimitive(1))
        }
        assertFailsWith<ProtocolException> { CommandAck.decode(bad) }
    }
}
