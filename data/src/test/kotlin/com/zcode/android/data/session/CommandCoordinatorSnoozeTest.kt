package com.zcode.android.data.session

import com.zcode.android.core.protocol.v4.CommandAck
import com.zcode.android.core.transport.ConnectionStateMachine
import com.zcode.android.domain.ids.CommandId
import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.domain.journal.RecoveryJournal
import com.zcode.android.domain.outbox.OutboxStateMachine
import com.zcode.android.domain.outbox.OutboxEntry
import com.zcode.android.domain.repo.OutboxRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * G6 (vc51) — the interaction-snooze pins (command.ts:205):
 * `snoozeInteractionAutoResolution {interactionId}` — the desktop's soft gate
 * that was MODELED and NEVER SENT on the phone. Pins: the wire shape (exact
 * type + payload, not CAS-guarded), the accepted outcome, and the offline gate.
 */
class CommandCoordinatorSnoozeTest {

    private class RecordingSender(
        var status: String = "accepted",
        var connected: ConnectionStateMachine.ConnectionState = ConnectionStateMachine.ConnectionState.CONNECTED,
    ) : CommandSender {
        val payloads = mutableListOf<Pair<String, JsonObject?>>()
        override suspend fun send(
            type: String,
            sessionId: String?,
            payload: JsonObject,
            baseRevision: Long?,
            baseLogEpoch: String?,
            commandId: String,
            workspacePath: String?,
            workspaceIdentity: String?,
        ): CommandAck {
            payloads.add(type to payload)
            return CommandAck(commandId, status, 1, null, null, null)
        }
    }

    /** Minimal in-memory outbox (the coordinator accepts one entry per call). */
    private fun minimalOutbox(): OutboxRepository = object : OutboxRepository {
        override fun observePending(endpointId: EndpointId): Flow<List<OutboxEntry>> = MutableStateFlow(emptyList())
        override suspend fun accept(
            endpointId: EndpointId, sessionId: String?, type: String, payloadJson: String,
            baseRevision: Long?, baseLogEpoch: String?, issuedAtMs: Long,
        ): CommandId = CommandId(java.util.UUID.randomUUID().toString())
        override suspend fun apply(commandId: CommandId, event: OutboxStateMachine.Event, atMs: Long): OutboxEntry? = null
        override suspend fun get(commandId: CommandId): OutboxEntry? = null
        override suspend fun sweepExpired(endpointId: EndpointId, nowMs: Long, ttlMs: Long): Int = 0
        override suspend fun pendingRefs(endpointId: EndpointId): List<RecoveryJournal.PendingCommandRef> = emptyList()
        override suspend fun countByState(endpointId: EndpointId, state: OutboxStateMachine.State): Int = 0
    }

    private fun coordinator(sender: RecordingSender): CommandCoordinator = CommandCoordinator(
        endpointId = EndpointId("ep-snooze"),
        outbox = minimalOutbox(),
        commands = sender,
        connectionState = { sender.connected },
    )

    @Test
    fun `snooze rides snoozeInteractionAutoResolution with the interactionId payload`() = runBlocking {
        val sender = RecordingSender()
        val outcome = coordinator(sender).snoozeInteractionAutoResolution("sess-1", "i-1")
        assertTrue(outcome is CommandCoordinator.Outcome.Accepted)
        assertEquals(1, sender.payloads.size)
        val (type, payload) = sender.payloads.single()
        assertEquals("snoozeInteractionAutoResolution", type)
        assertEquals("i-1", payload?.get("interactionId")?.toString()?.trim('"'))
    }

    @Test
    fun `snooze offline is blocked - nothing goes on the wire`() = runBlocking {
        val sender = RecordingSender(connected = ConnectionStateMachine.ConnectionState.DEGRADED)
        val outcome = coordinator(sender).snoozeInteractionAutoResolution("sess-1", "i-1")
        assertTrue(outcome is CommandCoordinator.Outcome.BlockedOffline)
        assertTrue(sender.payloads.isEmpty())
    }

    @Test
    fun `a repeated snooze landing noop is still accepted-class (the desktop's idempotent gate)`() = runBlocking {
        val sender = RecordingSender(status = "noop")
        val outcome = coordinator(sender).snoozeInteractionAutoResolution("sess-1", "i-1")
        assertTrue(outcome is CommandCoordinator.Outcome.Accepted, "noop = accepted-class (command.ts: 'idempotent noop')")
    }
}
