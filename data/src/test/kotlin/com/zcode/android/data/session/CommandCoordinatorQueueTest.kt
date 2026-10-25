package com.zcode.android.data.session

import com.zcode.android.core.protocol.v4.CommandAck
import com.zcode.android.core.transport.ConnectionStateMachine
import com.zcode.android.domain.ids.CommandId
import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.domain.outbox.OutboxStateMachine
import com.zcode.android.domain.repo.OutboxRepository
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * I1 (gaps-and-polish Phase 5A): the two decoded-unsent queue words + the follow-up mode —
 * `reorderQueueItem {queueItemId, beforeQueueItemId|null}`, `setAutoDrain {autoDrain}`,
 * `setFollowupMode {mode: queue|guide}` — all three in COMMANDS_REQUIRING_BASE_REVISION
 * (chunk-LNGNUNBX `$r` + the CAS Set): baseRevision rides the envelope and a missing base is
 * refused LOCALLY; `beforeQueueItemId: null` is an explicit JSON null (move to the end);
 * an out-of-set follow-up mode never reaches the wire (fail-closed, the switchCollaborationMode precedent).
 */
class CommandCoordinatorQueueTest {

    private class RecordingSender : CommandSender {
        val types = mutableListOf<String>()
        val payloads = mutableListOf<JsonObject>()
        val revisions = mutableListOf<Long?>()
        override suspend fun send(type: String, sessionId: String?, payload: JsonObject, baseRevision: Long?, baseLogEpoch: String?, commandId: String, workspacePath: String?, workspaceIdentity: String?): CommandAck {
            types.add(type); payloads.add(payload); revisions.add(baseRevision)
            return CommandAck(commandId, "accepted", 1, null, null, null)
        }
    }

    private class FakeOutbox : OutboxRepository {
        val entries = LinkedHashMap<String, com.zcode.android.domain.outbox.OutboxEntry>()
        override suspend fun accept(endpointId: EndpointId, sessionId: String?, type: String, payloadJson: String, baseRevision: Long?, baseLogEpoch: String?, issuedAtMs: Long): CommandId {
            val id = CommandId("cmd-q-${entries.size + 1}")
            entries[id.raw] = com.zcode.android.domain.outbox.OutboxEntry(
                commandId = id, endpointId = endpointId.raw, sessionId = sessionId, type = type, payloadJson = payloadJson,
                baseRevision = baseRevision, baseLogEpoch = baseLogEpoch, issuedAtMs = issuedAtMs,
                state = OutboxStateMachine.State.ACCEPTED_LOCALLY, attempts = 0, lastTransitionAtMs = issuedAtMs,
            )
            return id
        }
        override suspend fun get(commandId: CommandId) = entries[commandId.raw]
        override fun observePending(endpointId: EndpointId) = kotlinx.coroutines.flow.flowOf(emptyList<com.zcode.android.domain.outbox.OutboxEntry>())
        override suspend fun apply(commandId: CommandId, event: OutboxStateMachine.Event, atMs: Long) = entries[commandId.raw]
        override suspend fun sweepExpired(endpointId: EndpointId, nowMs: Long, ttlMs: Long) = 0
        override suspend fun pendingRefs(endpointId: EndpointId) = emptyList<com.zcode.android.domain.journal.RecoveryJournal.PendingCommandRef>()
        override suspend fun countByState(endpointId: EndpointId, state: OutboxStateMachine.State) = 0
    }

    private fun coordinator(sender: RecordingSender) = CommandCoordinator(
        endpointId = EndpointId("ep-1"), outbox = FakeOutbox(), commands = sender,
        connectionState = { ConnectionStateMachine.ConnectionState.CONNECTED },
    )

    @Test
    fun reorder_carriesBeforeIdAndBaseRevision_andNullMeansEnd() = runTest {
        val sender = RecordingSender()
        val c = coordinator(sender)
        assertTrue(c.reorderQueueItem("s", "q2", "q1", baseRevision = 7) is CommandCoordinator.Outcome.Accepted)
        assertTrue(c.reorderQueueItem("s", "q1", null, baseRevision = 8) is CommandCoordinator.Outcome.Accepted)
        assertEquals(listOf("reorderQueueItem", "reorderQueueItem"), sender.types)
        assertEquals("q1", (sender.payloads[0]["beforeQueueItemId"] as JsonPrimitive).content)
        assertTrue(sender.payloads[1]["beforeQueueItemId"] is JsonNull, "null = move to the END, as an explicit JSON null")
        assertEquals(listOf<Long?>(7, 8), sender.revisions)
    }

    @Test
    fun casWords_refuseAMissingBaseLocally() = runTest {
        val sender = RecordingSender()
        val c = coordinator(sender)
        assertTrue(c.reorderQueueItem("s", "q1", null, baseRevision = null) is CommandCoordinator.Outcome.MissingBaseRevision)
        assertTrue(c.setAutoDrain("s", false, baseRevision = null) is CommandCoordinator.Outcome.MissingBaseRevision)
        assertTrue(c.setFollowupMode("s", "guide", baseRevision = null) is CommandCoordinator.Outcome.MissingBaseRevision)
        assertTrue(sender.types.isEmpty(), "nothing reached the wire")
    }

    @Test
    fun autoDrainAndFollowup_carryTheirPayloads_andOutOfSetModeNeverSends() = runTest {
        val sender = RecordingSender()
        val c = coordinator(sender)
        c.setAutoDrain("s", false, baseRevision = 2)
        c.setFollowupMode("s", "guide", baseRevision = 3)
        val refused = c.setFollowupMode("s", "yolo", baseRevision = 4)
        assertTrue(refused is CommandCoordinator.Outcome.Failed)
        assertEquals(listOf("setAutoDrain", "setFollowupMode"), sender.types)
        assertEquals("false", (sender.payloads[0]["autoDrain"] as JsonPrimitive).content)
        assertEquals("guide", (sender.payloads[1]["mode"] as JsonPrimitive).content)
        assertEquals(setOf("queue", "guide"), CommandCoordinator.FOLLOWUP_MODES)
    }
}
