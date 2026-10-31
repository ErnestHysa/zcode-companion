package com.zcode.android.data.session

import com.zcode.android.core.protocol.v4.CommandAck
import com.zcode.android.core.transport.ConnectionStateMachine
import com.zcode.android.domain.ids.CommandId
import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.domain.journal.RecoveryJournal
import com.zcode.android.domain.outbox.OutboxStateMachine
import com.zcode.android.domain.outbox.OutboxEntry
import com.zcode.android.domain.repo.OutboxRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * G7+G8 (vc51) — the selection side session (command.ts:69): MODELED and NEVER
 * SENT on the phone. Pins: the wire payload (firstInput only with text; omitted
 * = keep the parent draft empty), the child-session parse across the
 * commandResultSchema trio, and the offline gate.
 */
class CommandCoordinatorSelectionSideSessionTest {

    private class RecordingSender(
        var ack: CommandAck? = null,
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
            return ack ?: CommandAck(commandId, "accepted", 1, null, null, null)
        }
    }

    private fun outbox(): OutboxRepository = object : OutboxRepository {
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

    private fun ackWithChild(type: String) = CommandAck(
        commandId = "cmd-x", status = "accepted", revisionAtDecision = 1, reasonCode = null, message = null,
        result = kotlinx.serialization.json.buildJsonObject {
            put("type", JsonPrimitive(type))
            put("sessionId", JsonPrimitive("child-sess-1"))
        },
    )

    @Test
    fun `the selection side session rides createSelectionSideSession with the firstInput text`() = runBlocking {
        val sender = RecordingSender(ack = ackWithChild("createSelectionSideSession"))
        val outcome = CommandCoordinator(
            EndpointId("ep-sel"), outbox(), sender,
            { ConnectionStateMachine.ConnectionState.CONNECTED },
        ).createSelectionSideSession("sess-1", "explain this traceback")
        assertTrue(outcome is CommandCoordinator.Outcome.Accepted)
        val (type, payload) = sender.payloads.single()
        assertEquals("createSelectionSideSession", type)
        val firstInput = payload?.get("firstInput") as? JsonObject
        assertEquals("explain this traceback", (firstInput?.get("text") as? JsonPrimitive)?.content)
    }

    @Test
    fun `a null selection omits firstInput entirely (the parent stays an empty side pane)`() = runBlocking {
        val sender = RecordingSender(ack = ackWithChild("createSelectionSideSession"))
        CommandCoordinator(
            EndpointId("ep-sel"), outbox(), sender,
            { ConnectionStateMachine.ConnectionState.CONNECTED },
        ).createSelectionSideSession("sess-1", null)
        val payload = sender.payloads.single().second
        assertTrue(payload?.containsKey("firstInput") != true, "no selection = no firstInput key")
    }

    @Test
    fun `the child parser accepts the commandResultSchema trio and nothing else`() {
        assertEquals("child-sess-1", CommandCoordinator.createdSessionId(ackWithChild("createSelectionSideSession")))
        assertEquals("child-sess-1", CommandCoordinator.createdSessionId(ackWithChild("forkAssistant")))
        assertEquals("child-sess-1", CommandCoordinator.createdSessionId(ackWithChild("createSession")))
        assertNull(CommandCoordinator.createdSessionId(ackWithChild("sendText")))
    }

    @Test
    fun `offline blocks the side session - nothing goes on the wire`() = runBlocking {
        val sender = RecordingSender(connected = ConnectionStateMachine.ConnectionState.FAILED)
        val outcome = CommandCoordinator(
            EndpointId("ep-sel"), outbox(), sender,
            { ConnectionStateMachine.ConnectionState.FAILED },
        ).createSelectionSideSession("sess-1", "text")
        assertTrue(outcome is CommandCoordinator.Outcome.BlockedOffline)
        assertTrue(sender.payloads.isEmpty())
    }
}
