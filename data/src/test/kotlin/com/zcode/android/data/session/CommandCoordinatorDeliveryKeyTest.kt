package com.zcode.android.data.session

import com.zcode.android.core.protocol.v4.CommandAck
import com.zcode.android.core.transport.ConnectionStateMachine
import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.domain.outbox.OutboxStateMachine
import com.zcode.android.domain.repo.OutboxRepository
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * G49+G50 (vc52 Phase 13): the per-send delivery keys. The coordinator has
 * carried `requestedDelivery` ("startNow"|"queue"|"guide"), `heldQueueDisposition`
 * ("clearQueueAndSend"|"keepQueueAndSend") and the `expectedHeldQueueItemIds` CAS
 * list since the G49/G50 audit (CommandCoordinator.kt:104-131) — but NO feature/UI
 * sender ever set them (the audit's "plumbed to the port, zero callers" shape).
 * This pin freezes the WIRE-KEY truth the UI threading rides:
 *
 *  - requestedDelivery rides the sendText payload verbatim (one-shot override);
 *  - heldQueueDisposition rides verbatim;
 *  - expectedHeldQueueItemIds is a JSON ARRAY of the ids (the desktop's CAS set —
 *    a null set is omitted, never an empty array).
 */
class CommandCoordinatorDeliveryKeyTest {

    private class RecordingSender : CommandSender {
        val payloads = mutableListOf<JsonObject>()
        override suspend fun send(type: String, sessionId: String?, payload: JsonObject, baseRevision: Long?, baseLogEpoch: String?, commandId: String, workspacePath: String?, workspaceIdentity: String?): CommandAck {
            payloads.add(payload)
            return CommandAck(commandId, "accepted", 1, null, null, null)
        }
    }

    private class FakeOutbox : OutboxRepository {
        val entries = LinkedHashMap<String, com.zcode.android.domain.outbox.OutboxEntry>()
        override suspend fun sweepTerminal(endpointId: com.zcode.android.domain.ids.EndpointId, nowMs: Long, ttlMs: Long): Int = 0
        override suspend fun accept(endpointId: com.zcode.android.domain.ids.EndpointId, sessionId: String?, type: String, payloadJson: String, baseRevision: Long?, baseLogEpoch: String?, issuedAtMs: Long): com.zcode.android.domain.ids.CommandId {
            val id = com.zcode.android.domain.ids.CommandId("cmd-d-${entries.size + 1}")
            entries[id.raw] = com.zcode.android.domain.outbox.OutboxEntry(
                commandId = id, endpointId = endpointId.raw, sessionId = sessionId, type = type, payloadJson = payloadJson,
                baseRevision = baseRevision, baseLogEpoch = baseLogEpoch, issuedAtMs = issuedAtMs,
                state = OutboxStateMachine.State.ACCEPTED_LOCALLY, attempts = 0, lastTransitionAtMs = issuedAtMs,
            )
            return id
        }
        override suspend fun get(commandId: com.zcode.android.domain.ids.CommandId) = entries[commandId.raw]
        override fun observePending(endpointId: com.zcode.android.domain.ids.EndpointId) = kotlinx.coroutines.flow.flowOf(emptyList<com.zcode.android.domain.outbox.OutboxEntry>())
        override suspend fun apply(commandId: com.zcode.android.domain.ids.CommandId, event: OutboxStateMachine.Event, atMs: Long) = entries[commandId.raw]
        override suspend fun sweepExpired(endpointId: com.zcode.android.domain.ids.EndpointId, nowMs: Long, ttlMs: Long) = 0
        override suspend fun pendingRefs(endpointId: com.zcode.android.domain.ids.EndpointId) = emptyList<com.zcode.android.domain.journal.RecoveryJournal.PendingCommandRef>()
        override suspend fun countByState(endpointId: com.zcode.android.domain.ids.EndpointId, state: OutboxStateMachine.State) = 0
    }

    private fun coordinator(sender: RecordingSender) = CommandCoordinator(
        endpointId = EndpointId("ep-1"), outbox = FakeOutbox(), commands = sender,
        connectionState = { ConnectionStateMachine.ConnectionState.CONNECTED },
    )

    @Test
    fun `requestedDelivery rides the sendText payload verbatim (all three desktop values)`() = runTest {
        val sender = RecordingSender()
        val c = coordinator(sender)
        for (delivery in listOf("startNow", "queue", "guide")) {
            val outcome = c.sendPrompt("s-1", "hello", requestedDelivery = delivery)
            assertTrue(outcome is CommandCoordinator.Outcome.Accepted)
        }
        assertEquals(listOf("startNow", "queue", "guide"), sender.payloads.map { (it["requestedDelivery"] as JsonPrimitive).content })
    }

    @Test
    fun `an unset requestedDelivery is omitted (the routing mode decides, like today)`() = runTest {
        val sender = RecordingSender()
        val c = coordinator(sender)
        c.sendPrompt("s-1", "hello")
        assertTrue(sender.payloads[0].containsKey("requestedDelivery").not())
    }

    @Test
    fun `heldQueueDisposition rides verbatim`() = runTest {
        val sender = RecordingSender()
        val c = coordinator(sender)
        c.sendPrompt("s-1", "hello", heldQueueDisposition = "keepQueueAndSend")
        assertEquals("keepQueueAndSend", (sender.payloads[0]["heldQueueDisposition"] as JsonPrimitive).content)
    }

    @Test
    fun `expectedHeldQueueItemIds is the CAS id array - absent when unset`() = runTest {
        val sender = RecordingSender()
        val c = coordinator(sender)
        c.sendPrompt("s-1", "hello", heldQueueDisposition = "clearQueueAndSend", expectedHeldQueueItemIds = listOf("q1", "q2"))
        val ids = sender.payloads[0]["expectedHeldQueueItemIds"]
        assertTrue(ids is JsonArray, "the CAS ids ride a JSON array")
        assertEquals(listOf("q1", "q2"), (ids as JsonArray).map { (it as JsonPrimitive).content })
        c.sendPrompt("s-2", "hello")
        assertTrue(sender.payloads[1].containsKey("expectedHeldQueueItemIds").not())
    }
}
