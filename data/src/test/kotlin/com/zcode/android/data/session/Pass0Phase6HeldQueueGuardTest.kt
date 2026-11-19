package com.zcode.android.data.session

import com.zcode.android.core.protocol.v4.CommandAck
import com.zcode.android.core.transport.ConnectionStateMachine
import com.zcode.android.domain.ids.CommandId
import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.domain.outbox.OutboxEntry
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
 * Pass-0 Phase 6 — the held-queue guard family (F-P19-A + F-P20-A, rule-10 ONE PAIR).
 *
 * F-P19-A (pass 19, Part V): on a trio-bearing sendText whose
 * `expectedHeldQueueItemIds` no longer set-equals the live queue, the host answers
 * the desktop-faithful failure ack `{status:"failed",
 * reasonCode:"guard.heldQueueConfirmationStale"}` (applyHeldQueueDisposition — the
 * renderer then re-opens the confirmation with ids re-read from the queue
 * snapshot). The phone collapsed that ack into the generic refusal
 * (`Outcome.Failed`) — reasonCode undifferentiated, no re-confirmation loop. The
 * typed identity must propagate (`Outcome.ConfirmationRequired`) WITHOUT changing
 * the outbox verdict: the attempt IS a host-refused send — the row stays
 * FAILED_TERMINAL with the verbatim reasonCode (the V.C static shape), the overlay
 * drops, and the draft survives (only ACCEPTED clears it, ViewModel side).
 *
 * F-P20-A (passes 20-22, Parts W/X/Y): the host's sendGoalCommand handler rides the
 * SAME admission gate; under `choice` a disposition-less goal is refused
 * (`heldQueueDispositionRequired`). The phone's goal payload carried only
 * `{text, displayText}` — the trio's absence verbatim in the wire payload (the
 * pass-20 outbox arbiter). The command now carries the optional trio; the no-trio
 * shape stays byte-identical for every legacy caller.
 */
class Pass0Phase6HeldQueueGuardTest {

    /** Sender that answers a scripted ack while recording every envelope. */
    private class RecordingSender : CommandSender {
        var ack: CommandAck? = null
        val types = mutableListOf<String>()
        val payloads = mutableListOf<JsonObject>()

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
            return ack ?: CommandAck(commandId, "accepted", 1, null, null, null)
        }
    }

    /** In-memory outbox running the REAL state machine (the outbox verdict arbiter). */
    private class RealOutbox : OutboxRepository {
        val entries = LinkedHashMap<String, OutboxEntry>()

        override suspend fun accept(
            endpointId: EndpointId,
            sessionId: String?,
            type: String,
            payloadJson: String,
            baseRevision: Long?,
            baseLogEpoch: String?,
            issuedAtMs: Long,
        ): CommandId {
            val id = CommandId("cmd-${entries.size + 1}")
            entries[id.raw] = OutboxEntry(
                commandId = id, endpointId = endpointId.raw, sessionId = sessionId,
                type = type, payloadJson = payloadJson, baseRevision = baseRevision,
                baseLogEpoch = baseLogEpoch, issuedAtMs = issuedAtMs,
                state = OutboxStateMachine.State.ACCEPTED_LOCALLY, attempts = 0,
                lastTransitionAtMs = issuedAtMs,
            )
            return id
        }

        override suspend fun apply(commandId: CommandId, event: OutboxStateMachine.Event, atMs: Long): OutboxEntry? {
            val current = entries[commandId.raw] ?: return null
            val to = OutboxStateMachine.restore(current.state).on(event)
            val updated = current.copy(
                state = to,
                lastTransitionAtMs = atMs,
                attempts = current.attempts + 1,
                ackStatus = (event as? OutboxStateMachine.Event.Ack)?.ackStatus ?: current.ackStatus,
                ackReasonCode = (event as? OutboxStateMachine.Event.Ack)?.reasonCode ?: current.ackReasonCode,
                ackMessage = (event as? OutboxStateMachine.Event.Ack)?.message ?: current.ackMessage,
            )
            entries[commandId.raw] = updated
            return updated
        }

        override suspend fun get(commandId: CommandId): OutboxEntry? = entries[commandId.raw]
        override fun observePending(endpointId: EndpointId): Flow<List<OutboxEntry>> =
            kotlinx.coroutines.flow.flowOf(emptyList())
        override suspend fun sweepExpired(endpointId: EndpointId, nowMs: Long, ttlMs: Long): Int = 0
        override suspend fun sweepTerminal(endpointId: EndpointId, nowMs: Long, ttlMs: Long): Int = 0
        override suspend fun pendingRefs(endpointId: EndpointId) =
            emptyList<com.zcode.android.domain.journal.RecoveryJournal.PendingCommandRef>()
        override suspend fun countByState(endpointId: EndpointId, state: OutboxStateMachine.State): Int =
            entries.values.count { it.endpointId == endpointId.raw && it.state == state }
    }

    private fun coordinator(sender: RecordingSender, outbox: RealOutbox = RealOutbox()): Pair<CommandCoordinator, RealOutbox> =
        CommandCoordinator(
            endpointId = EndpointId("ep-1"),
            outbox = outbox,
            commands = sender,
            connectionState = { ConnectionStateMachine.ConnectionState.CONNECTED },
        ) to outbox

    private fun guardAck(commandId: String) = CommandAck(
        commandId = commandId,
        status = "failed",
        revisionAtDecision = 100,
        reasonCode = "guard.heldQueueConfirmationStale",
        message = "paused queue changed after send confirmation opened",
        result = null,
    )

    // ------------------------------------------------------------------ F-P19-A

    @Test
    fun `F-P19-A - the guard CAS-mismatch ack surfaces as ConfirmationRequired, not the generic refusal`() = runTest {
        val sender = RecordingSender()
        val (coord, outbox) = coordinator(sender)
        sender.ack = guardAck("will-be-overwritten")
        val outcome = coord.sendPrompt(
            sessionId = "sess-1",
            text = "p19cas",
            heldQueueDisposition = "keepQueueAndSend",
            expectedHeldQueueItemIds = listOf("q-rig-1", "q-rig-2"),
        )
        assertTrue(
            outcome is CommandCoordinator.Outcome.ConfirmationRequired,
            "the guard.* ack must carry the desktop's confirmationRequired identity; got $outcome",
        )
        assertEquals(
            "guard.heldQueueConfirmationStale",
            (outcome as CommandCoordinator.Outcome.ConfirmationRequired).ack.reasonCode,
        )
    }

    @Test
    fun `F-P19-A - the guard ack still stores FAILED_TERMINAL with the verbatim reasonCode (V-C static shape)`() = runTest {
        val sender = RecordingSender()
        val (coord, outbox) = coordinator(sender)
        sender.ack = guardAck("ack-id")
        coord.sendPrompt(sessionId = "sess-1", text = "p19cas", heldQueueDisposition = "keepQueueAndSend")
        val row = outbox.entries.values.single()
        assertEquals(OutboxStateMachine.State.FAILED_TERMINAL, row.state, "the attempt IS a host-refused send")
        assertEquals("failed", row.ackStatus)
        assertEquals("guard.heldQueueConfirmationStale", row.ackReasonCode)
        assertEquals("paused queue changed after send confirmation opened", row.ackMessage)
    }

    @Test
    fun `F-P19-A - other failed reasonCodes stay the generic Failed (control)`() = runTest {
        val sender = RecordingSender()
        val (coord, _) = coordinator(sender)
        sender.ack = CommandAck("x", "failed", 1, "fault.something.else", "nope", null)
        val outcome = coord.sendPrompt(sessionId = "sess-1", text = "plain refusal")
        assertTrue(outcome is CommandCoordinator.Outcome.Failed, "only the guard.* identity re-confirmation; got $outcome")
    }

    @Test
    fun `F-P19-A - the ConfirmationRequired send drops the pending overlay (host spoke - not unknown)`() = runTest {
        val sender = RecordingSender()
        val (coord, _) = coordinator(sender)
        sender.ack = guardAck("x")
        coord.sendPrompt(sessionId = "sess-1", text = "retry me", heldQueueDisposition = "keepQueueAndSend")
        assertTrue(
            coord.pendingSends.value.isEmpty(),
            "a guard ack is an authoritative verdict — the send is not stuck pending-unknown",
        )
    }

    // ------------------------------------------------------------------ F-P20-A

    @Test
    fun `F-P20-A - sendGoalCommand carries the answered trio into the payload when supplied`() = runTest {
        val sender = RecordingSender()
        val (coord, _) = coordinator(sender)
        coord.sendGoalCommand(
            sessionId = "sess-1",
            objective = "finish the ledger",
            displayText = "/goal finish the ledger",
            heldQueueDisposition = "keepQueueAndSend",
            expectedHeldQueueItemIds = listOf("q-rig-1", "q-rig-2"),
        )
        assertEquals("sendGoalCommand", sender.types.single())
        val payload = sender.payloads.single().jsonObject
        assertEquals("finish the ledger", (payload["text"] as JsonPrimitive).content)
        assertEquals("/goal finish the ledger", (payload["displayText"] as JsonPrimitive).content)
        assertEquals(
            "keepQueueAndSend",
            (payload["heldQueueDisposition"] as JsonPrimitive).content,
            "the answered disposition must ride the goal payload (the pass-20 arbiter's missing keys)",
        )
        val ids = payload["expectedHeldQueueItemIds"]
        assertTrue(ids is kotlinx.serialization.json.JsonArray, "the CAS ids ride the goal payload")
        assertEquals(
            listOf("q-rig-1", "q-rig-2"),
            (ids as kotlinx.serialization.json.JsonArray).map { (it as JsonPrimitive).content },
        )
    }

    @Test
    fun `F-P20-A - the goal payload is unchanged when no trio is supplied (legacy shape)`() = runTest {
        val sender = RecordingSender()
        val (coord, _) = coordinator(sender)
        coord.sendGoalCommand("sess-1", "ship it", "/goal ship it")
        val payload = sender.payloads.single().jsonObject
        assertEquals(
            setOf("text", "displayText"),
            payload.keys,
            "no trio keys on the legacy call — the wire shape stays byte-identical",
        )
    }

    @Test
    fun `F-P20-A - the goal CAS-mismatch guard ack also surfaces ConfirmationRequired (desktop re-opens on both send paths)`() = runTest {
        val sender = RecordingSender()
        val (coord, _) = coordinator(sender)
        sender.ack = guardAck("goal-cmd")
        val outcome = coord.sendGoalCommand(
            "sess-1", "goal", "/goal goal",
            heldQueueDisposition = "keepQueueAndSend",
            expectedHeldQueueItemIds = listOf("q-rig-1"),
        )
        assertTrue(
            outcome is CommandCoordinator.Outcome.ConfirmationRequired,
            "the desktop re-opens on the slash path too (V-B: both sendText and slash); got $outcome",
        )
    }
}
