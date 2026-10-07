package com.zcode.android.data.session

import com.zcode.android.core.protocol.v4.CommandAck
import com.zcode.android.core.transport.ConnectionStateMachine
import com.zcode.android.domain.ids.CommandId
import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.domain.outbox.OutboxStateMachine
import com.zcode.android.domain.repo.OutboxRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GAP-10 (docs/protocol/v4-wire.md §11.4) — the editUserQuery command plane:
 * row-targeted (ROW_TARGETING_COMMANDS) AND CAS-guarded
 * (COMMANDS_REQUIRING_BASE_REVISION) — the envelope must carry BOTH baseRevision
 * AND baseLogEpoch, locally validated BEFORE anything goes on the wire. Payload:
 * `{target: {rowId, entityId}, newText}` with workspaceMode OMITTED (= the
 * desktop's `preserve` default — branch switch only); attachments omitted =
 * preserve canonical originals.
 */
class CommandCoordinatorEditTest {

    private class RecordingSender : CommandSender {
        var status: String = "accepted"
        val types = mutableListOf<String>()
        val payloads = mutableListOf<JsonObject>()
        val revisions = mutableListOf<Long?>()
        val logEpochs = mutableListOf<String?>()

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
            types.add(type)
            payloads.add(payload)
            revisions.add(baseRevision)
            logEpochs.add(baseLogEpoch)
            return CommandAck(commandId, status, 1, null, null, null)
        }
    }

    private class FakeOutbox : OutboxRepository {
        val entries = LinkedHashMap<String, com.zcode.android.domain.outbox.OutboxEntry>()

        override suspend fun accept(
            endpointId: EndpointId,
            sessionId: String?,
            type: String,
            payloadJson: String,
            baseRevision: Long?,
            baseLogEpoch: String?,
            issuedAtMs: Long,
        ): CommandId {
            val id = CommandId("cmd-edit-${entries.size + 1}")
            entries[id.raw] = com.zcode.android.domain.outbox.OutboxEntry(
                commandId = id, endpointId = endpointId.raw, sessionId = sessionId,
                type = type, payloadJson = payloadJson, baseRevision = baseRevision,
                baseLogEpoch = baseLogEpoch, issuedAtMs = issuedAtMs,
                state = OutboxStateMachine.State.ACCEPTED_LOCALLY, attempts = 0,
                lastTransitionAtMs = issuedAtMs,
            )
            return id
        }

        override suspend fun get(commandId: CommandId) = entries[commandId.raw]
        override fun observePending(endpointId: EndpointId) =
            kotlinx.coroutines.flow.flowOf(emptyList<com.zcode.android.domain.outbox.OutboxEntry>())

        override suspend fun apply(commandId: CommandId, event: OutboxStateMachine.Event, atMs: Long) =
            entries[commandId.raw]

        override suspend fun sweepExpired(endpointId: EndpointId, nowMs: Long, ttlMs: Long) = 0
        override suspend fun pendingRefs(endpointId: EndpointId) =
            emptyList<com.zcode.android.domain.journal.RecoveryJournal.PendingCommandRef>()

        override suspend fun countByState(endpointId: EndpointId, state: OutboxStateMachine.State) = 0
    }

    private fun coordinator(sender: RecordingSender): CommandCoordinator = CommandCoordinator(
        endpointId = EndpointId("ep-1"),
        outbox = FakeOutbox(),
        commands = sender,
        connectionState = { ConnectionStateMachine.ConnectionState.CONNECTED },
    )

    @Test
    fun editUserQuery_sendsRowTargetWithBothCasCurrencies() = runTest {
        val sender = RecordingSender()
        val outcome = coordinator(sender).editUserQuery(
            sessionId = "sess-1",
            rowId = 42,
            entityId = "ent-user-row-42",
            newText = "edited question text",
            baseRevision = 7,
            baseLogEpoch = "epoch-e1",
        )
        assertTrue(outcome is CommandCoordinator.Outcome.Accepted)
        assertEquals("editUserQuery", sender.types.single())
        val payload = sender.payloads.single().jsonObject
        val target = payload["target"]!!.jsonObject
        assertEquals(42L, (target["rowId"] as JsonPrimitive).content.toLong())
        assertEquals("ent-user-row-42", (target["entityId"] as JsonPrimitive).content)
        assertEquals("edited question text", (payload["newText"] as JsonPrimitive).content)
        assertNull(payload["workspaceMode"], "omitted = the desktop's preserve default — never sent explicitly")
        assertNull(payload["attachments"], "attachments omitted = preserve canonical originals (desktop semantics)")
        assertEquals(7L, sender.revisions.single(), "CAS baseRevision REQUIRED (verified set §5)")
        assertEquals("epoch-e1", sender.logEpochs.single(), "row-targeting baseLogEpoch REQUIRED (§5)")
    }

    @Test
    fun editUserQuery_missingCasCurrency_blockedPreWire() = runTest {
        val sender = RecordingSender()
        assertTrue(
            coordinator(sender).editUserQuery("s", 1, "ent", "x", baseRevision = null, baseLogEpoch = "e1")
                is CommandCoordinator.Outcome.MissingBaseRevision,
            "missing revision → blocked locally",
        )
        assertTrue(
            coordinator(sender).editUserQuery("s", 1, "ent", "x", baseRevision = 1, baseLogEpoch = null)
                is CommandCoordinator.Outcome.MissingBaseRevision,
            "missing logEpoch (row-targeting) → blocked locally — never a lenient send",
        )
        assertEquals(0, sender.types.size, "nothing went on the wire")
    }

    @Test
    fun editUserQuery_blankText_refused() = runTest {
        val sender = RecordingSender()
        val outcome = coordinator(sender).editUserQuery("s", 1, "ent", "   ", baseRevision = 1, baseLogEpoch = "e1")
        assertTrue(outcome is CommandCoordinator.Outcome.Failed, "blank edit text is skipped (desktop gate)")
        assertEquals(0, sender.types.size)
    }

    @Test
    fun editUserQuery_offline_blockedNotQueued() = runTest {
        val sender = RecordingSender()
        val offline = CommandCoordinator(
            endpointId = EndpointId("ep-1"),
            outbox = FakeOutbox(),
            commands = sender,
            connectionState = { ConnectionStateMachine.ConnectionState.RECOVERING },
        )
        assertTrue(
            offline.editUserQuery("s", 1, "ent", "x", baseRevision = 1, baseLogEpoch = "e1")
                is CommandCoordinator.Outcome.BlockedOffline,
        )
        assertEquals(0, sender.types.size)
    }
}
