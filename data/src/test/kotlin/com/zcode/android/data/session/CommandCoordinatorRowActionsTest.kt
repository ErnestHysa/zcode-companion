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
 * E3.2+I1+N5 (gaps-and-polish Phase 4A): the four row commands the protocol layer
 * modeled since Phase 2 with ZERO senders — `retryTurn`, `forkAssistant`,
 * `applyFileRewind`, `setAssistantFeedback`. All four are ROW_TARGETING_COMMANDS
 * AND in COMMANDS_REQUIRING_BASE_REVISION (command.ts:296/314): the envelope
 * carries `{target: {rowId, entityId}}` (+ `feedback` for the last) and BOTH
 * CAS currencies, validated LOCALLY before the wire (the editUserQuery precedent).
 */
class CommandCoordinatorRowActionsTest {

    private class RecordingSender : CommandSender {
        var status: String = "accepted"
        var result: JsonObject? = null
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
            return CommandAck(commandId, status, 1, null, null, result)
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
            val id = CommandId("cmd-row-${entries.size + 1}")
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

    private fun coordinator(
        sender: RecordingSender,
        state: ConnectionStateMachine.ConnectionState = ConnectionStateMachine.ConnectionState.CONNECTED,
    ) = CommandCoordinator(
        endpointId = EndpointId("ep-1"),
        outbox = FakeOutbox(),
        commands = sender,
        connectionState = { state },
    )

    private fun JsonObject.target(): JsonObject = this["target"]!!.jsonObject

    @Test
    fun retryTurn_sendsRowTargetWithBothCasCurrencies() = runTest {
        val sender = RecordingSender()
        val outcome = coordinator(sender).retryTurn("sess-1", rowId = 9, entityId = "ent-9", baseRevision = 3, baseLogEpoch = "e1")
        assertTrue(outcome is CommandCoordinator.Outcome.Accepted)
        assertEquals("retryTurn", sender.types.single())
        val target = sender.payloads.single().target()
        assertEquals(9L, (target["rowId"] as JsonPrimitive).content.toLong())
        assertEquals("ent-9", (target["entityId"] as JsonPrimitive).content)
        assertEquals(1, sender.payloads.single().size, "retryTurn payload is `{target}` only (command.ts:161)")
        assertEquals(3L, sender.revisions.single())
        assertEquals("e1", sender.logEpochs.single())
    }

    @Test
    fun forkAssistant_sendsRowTarget_andSurfacesTheChildSessionId() = runTest {
        val sender = RecordingSender().apply {
            result = buildJsonObject { put("type", "forkAssistant"); put("sessionId", "sess-child-7") }
        }
        val outcome = coordinator(sender).forkAssistant("sess-1", rowId = 12, entityId = "ent-12", baseRevision = 5, baseLogEpoch = "e2")
        assertTrue(outcome is CommandCoordinator.Outcome.Accepted)
        assertEquals("forkAssistant", sender.types.single())
        assertEquals(12L, (sender.payloads.single().target()["rowId"] as JsonPrimitive).content.toLong())
        // The desktop navigates to ack.result.sessionId (SessionPane.tsx handleFork) —
        // the coordinator exposes the typed child id for the same navigation.
        assertEquals("sess-child-7", CommandCoordinator.forkedSessionId((outcome as CommandCoordinator.Outcome.Accepted).ack))
    }

    @Test
    fun applyFileRewind_sendsRowTargetOnly() = runTest {
        val sender = RecordingSender()
        val outcome = coordinator(sender).applyFileRewind("sess-1", rowId = 4, entityId = "ent-turn-4", baseRevision = 2, baseLogEpoch = "e3")
        assertTrue(outcome is CommandCoordinator.Outcome.Accepted)
        assertEquals("applyFileRewind", sender.types.single())
        assertEquals("ent-turn-4", (sender.payloads.single().target()["entityId"] as JsonPrimitive).content)
        assertEquals(1, sender.payloads.single().size)
    }

    @Test
    fun setAssistantFeedback_carriesLikeDislikeOrExplicitNull() = runTest {
        val sender = RecordingSender()
        val c = coordinator(sender)
        c.setAssistantFeedback("s", 1, "ent-1", feedback = "like", baseRevision = 1, baseLogEpoch = "e")
        c.setAssistantFeedback("s", 1, "ent-1", feedback = "dislike", baseRevision = 1, baseLogEpoch = "e")
        c.setAssistantFeedback("s", 1, "ent-1", feedback = null, baseRevision = 1, baseLogEpoch = "e")
        assertEquals(listOf("setAssistantFeedback", "setAssistantFeedback", "setAssistantFeedback"), sender.types)
        assertEquals("like", (sender.payloads[0]["feedback"] as JsonPrimitive).content)
        assertEquals("dislike", (sender.payloads[1]["feedback"] as JsonPrimitive).content)
        // Schema: `feedback: z.enum(["like","dislike"]).nullable()` — clearing is an
        // EXPLICIT null on the wire (omitting the key would fail the strict schema).
        assertTrue(sender.payloads[2]["feedback"] is JsonNull, "clear = explicit JSON null")
    }

    @Test
    fun setAssistantFeedback_refusesUnknownVocabulary() = runTest {
        val sender = RecordingSender()
        val outcome = coordinator(sender).setAssistantFeedback("s", 1, "ent-1", feedback = "meh", baseRevision = 1, baseLogEpoch = "e")
        assertTrue(outcome is CommandCoordinator.Outcome.Failed, "out-of-enum feedback never goes on the wire")
        assertEquals(0, sender.types.size)
    }

    @Test
    fun rowCommands_missingEitherCurrency_blockedPreWire() = runTest {
        val sender = RecordingSender()
        val c = coordinator(sender)
        assertTrue(c.retryTurn("s", 1, "e", baseRevision = null, baseLogEpoch = "e1") is CommandCoordinator.Outcome.MissingBaseRevision)
        assertTrue(c.forkAssistant("s", 1, "e", baseRevision = 1, baseLogEpoch = null) is CommandCoordinator.Outcome.MissingBaseRevision)
        assertTrue(c.applyFileRewind("s", 1, "e", baseRevision = null, baseLogEpoch = null) is CommandCoordinator.Outcome.MissingBaseRevision)
        assertTrue(c.setAssistantFeedback("s", 1, "e", "like", baseRevision = 1, baseLogEpoch = "") is CommandCoordinator.Outcome.MissingBaseRevision)
        assertEquals(0, sender.types.size, "nothing went on the wire half-currencied")
    }

    @Test
    fun rowCommands_offline_blockedNotQueued() = runTest {
        val sender = RecordingSender()
        val offline = coordinator(sender, ConnectionStateMachine.ConnectionState.RECOVERING)
        assertTrue(offline.retryTurn("s", 1, "e", 1, "e1") is CommandCoordinator.Outcome.BlockedOffline)
        assertTrue(offline.forkAssistant("s", 1, "e", 1, "e1") is CommandCoordinator.Outcome.BlockedOffline)
        assertEquals(0, sender.types.size)
    }

    @Test
    fun forkedSessionId_isNullForNonForkResults() {
        val plain = CommandAck("c", "accepted", 1, null, null, null)
        assertEquals(null, CommandCoordinator.forkedSessionId(plain))
        val otherType = CommandAck("c", "accepted", 1, null, null, buildJsonObject { put("type", "inputAccepted"); put("sessionId", "x") })
        assertEquals(null, CommandCoordinator.forkedSessionId(otherType), "only a forkAssistant-typed result names the child")
    }
}
