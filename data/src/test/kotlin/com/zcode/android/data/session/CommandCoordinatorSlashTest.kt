package com.zcode.android.data.session

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
 * GAP-12 slash-command command plane (docs/protocol/v4-wire.md §11.3): /compact →
 * `compact {}` NOT CAS-guarded; /goal <objective> → `sendGoalCommand {text,
 * displayText}` not CAS-guarded; /goal resume → `resumeGoal {}` CAS-guarded
 * (verified set §5). All three ride the ONE outbox gate (INV-4) and are
 * BLOCKED offline (§17.6 — never silently queued).
 */
class CommandCoordinatorSlashTest {

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

    private class RecordingOutbox : OutboxRepository {
        val rows = mutableListOf<AcceptedRow>()

        data class AcceptedRow(
            val type: String,
            val baseRevision: Long?,
            val baseLogEpoch: String?,
        )

        override suspend fun accept(
            endpointId: EndpointId,
            sessionId: String?,
            type: String,
            payloadJson: String,
            baseRevision: Long?,
            baseLogEpoch: String?,
            issuedAtMs: Long,
        ): CommandId {
            rows.add(AcceptedRow(type, baseRevision, baseLogEpoch))
            return CommandId("cmd-${rows.size}")
        }

        override fun observePending(endpointId: EndpointId): Flow<List<com.zcode.android.domain.outbox.OutboxEntry>> =
            kotlinx.coroutines.flow.flowOf(emptyList())

        override suspend fun get(commandId: CommandId): com.zcode.android.domain.outbox.OutboxEntry? = null

        override suspend fun apply(commandId: CommandId, event: OutboxStateMachine.Event, atMs: Long): com.zcode.android.domain.outbox.OutboxEntry? =
            null

        override suspend fun sweepExpired(endpointId: EndpointId, nowMs: Long, ttlMs: Long): Int = 0

        override suspend fun pendingRefs(endpointId: EndpointId): List<com.zcode.android.domain.journal.RecoveryJournal.PendingCommandRef> =
            emptyList()

        override suspend fun countByState(endpointId: EndpointId, state: OutboxStateMachine.State): Int = 0
    }

    private fun coordinator(sender: RecordingSender): CommandCoordinator = CommandCoordinator(
        endpointId = EndpointId("ep-1"),
        outbox = RecordingOutbox(),
        commands = sender,
        connectionState = { ConnectionStateMachine.ConnectionState.CONNECTED },
    )

    @Test
    fun compact_sendsEmptyPayload_withoutCas() = runTest {
        val sender = RecordingSender()
        val outcome = coordinator(sender).compact("sess-1")
        assertTrue(outcome is CommandCoordinator.Outcome.Accepted)
        assertEquals("compact", sender.types.single())
        assertEquals(0, sender.payloads.single().size, "compact payload is the empty object")
        assertNull(sender.revisions.single(), "compact is NOT CAS-guarded (command.ts:148-150)")
        assertNull(sender.logEpochs.single())
    }

    @Test
    fun sendGoalCommand_carriesTextAndDisplayText_withoutCas() = runTest {
        val sender = RecordingSender()
        val outcome = coordinator(sender).sendGoalCommand("sess-1", "ship the release", "/goal ship the release")
        assertTrue(outcome is CommandCoordinator.Outcome.Accepted)
        assertEquals("sendGoalCommand", sender.types.single())
        val payload = sender.payloads.single().jsonObject
        assertEquals("ship the release", (payload["text"] as JsonPrimitive).content)
        assertEquals("/goal ship the release", (payload["displayText"] as JsonPrimitive).content)
        assertNull(sender.revisions.single())
    }

    @Test
    fun resumeGoal_isCasGuarded_baseRevisionRequired() = runTest {
        val sender = RecordingSender()
        val outcome = coordinator(sender).resumeGoal("sess-1", baseRevision = null)
        assertTrue(outcome is CommandCoordinator.Outcome.MissingBaseRevision, "no CAS currency → blocked pre-wire")
        assertEquals(0, sender.types.size, "nothing went on the wire")
        val ok = coordinator(sender).resumeGoal("sess-1", baseRevision = 42)
        assertTrue(ok is CommandCoordinator.Outcome.Accepted)
        assertEquals("resumeGoal", sender.types.single())
        assertEquals(42L, sender.revisions.single())
    }

    @Test
    fun slashCommands_blockedOffline_neverQueued() = runTest {
        val sender = RecordingSender()
        val offline = CommandCoordinator(
            endpointId = EndpointId("ep-1"),
            outbox = RecordingOutbox(),
            commands = sender,
            connectionState = { ConnectionStateMachine.ConnectionState.RECOVERING },
        )
        assertTrue(offline.compact("sess-1") is CommandCoordinator.Outcome.BlockedOffline)
        assertTrue(
            offline.sendGoalCommand("sess-1", "x", "/goal x") is CommandCoordinator.Outcome.BlockedOffline,
        )
        assertTrue(offline.resumeGoal("sess-1", 1) is CommandCoordinator.Outcome.BlockedOffline)
        assertEquals(0, sender.types.size)
    }
}
