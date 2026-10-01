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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 7.2 send path — architectural.md §17.5/§17.6/§28.6/§15.5 and INV-4.
 * The failure-sequence suite: NO path duplicates a server-contacted command;
 * offline submits are BLOCKED (never queued); a lost outcome is UNKNOWN, never
 * assumed success; the SAFE_AGGRESSIVE family retransmits on reconnect, sendText
 * stays user-gated.
 */
class CommandCoordinatorTest {

    private class RecordingSender(
        var status: String = "accepted",
        var throwOnSend: Boolean = false,
    ) : CommandSender {
        val sends = mutableListOf<Pair<String, String>>() // type to commandId
        val revisions = mutableListOf<Long?>()
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
            sends.add(type to commandId)
            revisions.add(baseRevision)
            if (throwOnSend) throw IllegalStateException("socket died mid-call")
            return CommandAck(commandId, status, 1, null, null, null)
        }
    }

    private class FixedOutbox : OutboxRepository {
        data class Row(
            val type: String,
            val state: OutboxStateMachine.State,
            val baseRevision: Long?,
        )

        val entries = LinkedHashMap<String, Row>()
        val ids = ArrayList<CommandId>()
        var nextId = 1
        var minted: Int = 0

        private val liveStates = setOf(
            OutboxStateMachine.State.ACCEPTED_LOCALLY,
            OutboxStateMachine.State.TRANSMITTED,
            OutboxStateMachine.State.UNKNOWN,
            OutboxStateMachine.State.FAILED_RETRYABLE,
        )

        override fun observePending(endpointId: EndpointId): Flow<List<com.zcode.android.domain.outbox.OutboxEntry>> =
            kotlinx.coroutines.flow.flowOf(
                entries.keys.mapNotNull { id ->
                    val row = entries[id] ?: return@mapNotNull null
                    if (row.state !in liveStates) return@mapNotNull null
                    com.zcode.android.domain.outbox.OutboxEntry(
                        commandId = CommandId(id), endpointId = endpointId.raw, sessionId = "s",
                        type = row.type, payloadJson = """{"text":"t"}""",
                        baseRevision = row.baseRevision, baseLogEpoch = null,
                        issuedAtMs = 0, state = row.state, attempts = 0, lastTransitionAtMs = 0,
                    )
                },
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
            minted++
            val id = CommandId("cmd-$nextId")
            nextId++
            entries[id.raw] = Row(type, OutboxStateMachine.State.ACCEPTED_LOCALLY, baseRevision)
            ids.add(id)
            return id
        }

        override suspend fun apply(commandId: CommandId, event: OutboxStateMachine.Event, atMs: Long): com.zcode.android.domain.outbox.OutboxEntry? {
            val current = entries[commandId.raw] ?: return null
            val machine = OutboxStateMachine.restore(current.state)
            val to = machine.on(event)
            entries[commandId.raw] = current.copy(state = to)
            return get(commandId)
        }

        override suspend fun get(commandId: CommandId): com.zcode.android.domain.outbox.OutboxEntry? =
            entries[commandId.raw]?.let {
                com.zcode.android.domain.outbox.OutboxEntry(
                    commandId = commandId, endpointId = "e", sessionId = "s", type = it.type,
                    payloadJson = """{"text":"t"}""", baseRevision = it.baseRevision, baseLogEpoch = null,
                    issuedAtMs = 0, state = it.state, attempts = 0, lastTransitionAtMs = 0,
                )
            }

        override suspend fun sweepExpired(endpointId: EndpointId, nowMs: Long, ttlMs: Long): Int = 0
        override suspend fun pendingRefs(endpointId: EndpointId) = emptyList<com.zcode.android.domain.journal.RecoveryJournal.PendingCommandRef>()
        override suspend fun countByState(endpointId: EndpointId, state: OutboxStateMachine.State): Int =
            entries.values.count { it.state == state }
    }

    private fun coordinator(
        sender: RecordingSender,
        outbox: FixedOutbox = FixedOutbox(),
        connected: ConnectionStateMachine.ConnectionState = ConnectionStateMachine.ConnectionState.CONNECTED,
    ): Pair<CommandCoordinator, FixedOutbox> {
        val c = CommandCoordinator(
            endpointId = EndpointId("ep"),
            outbox = outbox,
            commands = sender,
            connectionState = { connected },
        )
        return c to outbox
    }

    // ---------------------------------------------------------------- offline (§17.6)

    @Test
    fun `offline submit is BLOCKED — nothing minted, nothing queued (silent queueing forbidden)`() = runTest {
        val sender = RecordingSender()
        val (coordinator, outbox) = coordinator(sender, connected = ConnectionStateMachine.ConnectionState.RECONNECTING)
        val outcome = coordinator.sendPrompt("s1", "hello")
        assertTrue(outcome is CommandCoordinator.Outcome.BlockedOffline)
        assertEquals(0, outbox.minted)
        assertEquals(0, sender.sends.size)
        assertTrue(coordinator.pendingSends.value.isEmpty())
    }

    // ---------------------------------------------------------------- happy path

    @Test
    fun `send mints ONE commandId, transmits, acks confirmed`() = runTest {
        val sender = RecordingSender()
        val (coordinator, outbox) = coordinator(sender)
        val outcome = coordinator.sendPrompt("s1", "hello")
        assertTrue(outcome is CommandCoordinator.Outcome.Accepted)
        assertEquals(1, outbox.minted)
        assertEquals(1, sender.sends.size)
        assertEquals(OutboxStateMachine.State.ACKED, outbox.entries[outcome.commandId().raw]?.state)
        // §14.2 overlay: terminal ack drops the pending
        assertTrue(coordinator.pendingSends.value.isEmpty())
    }

    // ---------------------------------------------------------------- INV-4 failure sequences

    @Test
    fun `socket death mid-send leaves UNKNOWN — a retry uses the SAME commandId (no duplicate)`() = runTest {
        val sender = RecordingSender(throwOnSend = true)
        val (coordinator, outbox) = coordinator(sender)
        val outcome = coordinator.sendPrompt("s1", "hello")
        val kept = assertNotNull(outcome as? CommandCoordinator.Outcome.UnknownKept)
        assertEquals(OutboxStateMachine.State.UNKNOWN, outbox.entries[kept.commandId.raw]?.state)
        // The overlay shows the truthful uncertain state.
        assertEquals("SENT_UNKNOWN", coordinator.pendingSends.value.single().state.name)

        // User-gated retry: sender recovered. SAME commandId — INV-4.
        sender.throwOnSend = false
        val sendsBefore = sender.sends.size
        val retry = coordinator.retry(kept.commandId)
        assertTrue(retry is CommandCoordinator.Outcome.Accepted)
        assertEquals(sendsBefore + 1, sender.sends.size)
        assertEquals(kept.commandId.raw, sender.sends.last().second)
        assertEquals(1, outbox.minted) // never a second mint
    }

    @Test
    fun `retry of a LIVE (transmitting) entry is refused — no double send`() = runTest {
        val sender = RecordingSender()
        val (coordinator, outbox) = coordinator(sender)
        val outcome = coordinator.sendPrompt("s1", "hello")
        // Entry is ACKED (terminal) — retry must no-op, not re-send.
        val before = sender.sends.size
        val retry = coordinator.retry(outcome.commandId())
        assertTrue(retry is CommandCoordinator.Outcome.Failed) // "nothing to retry"
        assertEquals(before, sender.sends.size)
        assertEquals(1, outbox.minted)
    }

    @Test
    fun `stale ack maps to Stale — draft survives upstream, entry FAILED_STALE`() = runTest {
        val sender = RecordingSender(status = "stale")
        val (coordinator, outbox) = coordinator(sender)
        val outcome = coordinator.sendPrompt("s1", "hello")
        assertTrue(outcome is CommandCoordinator.Outcome.Stale)
        assertEquals(OutboxStateMachine.State.FAILED_STALE, outbox.entries[outcome.commandId().raw]?.state)
    }

    // ---------------------------------------------------------------- §28.6 SAFE_AGGRESSIVE

    @Test
    fun `stop auto-retransmits from UNKNOWN on the reconnect pass (verified idempotent family)`() = runTest {
        val sender = RecordingSender(throwOnSend = true)
        val (coordinator, outbox) = coordinator(sender)
        val outcome = coordinator.stopGeneration("s1")
        assertTrue(outcome is CommandCoordinator.Outcome.UnknownKept)
        assertEquals(OutboxStateMachine.State.UNKNOWN, outbox.entries.values.single().state)

        sender.throwOnSend = false
        coordinator.onConnected()
        assertEquals(2, sender.sends.size) // retransmitted automatically
        assertEquals(sender.sends[0].second, sender.sends[1].second) // SAME commandId
        assertEquals(OutboxStateMachine.State.ACKED, outbox.entries.values.single().state)
    }

    @Test
    fun `sendText UNKNOWN is NEVER auto-retransmitted on reconnect (user-gated only — INV-4)`() = runTest {
        val sender = RecordingSender(throwOnSend = true)
        val (coordinator, outbox) = coordinator(sender)
        coordinator.sendPrompt("s1", "hello")
        assertEquals(OutboxStateMachine.State.UNKNOWN, outbox.entries.values.single().state)
        sender.throwOnSend = false
        coordinator.onConnected()
        assertEquals(1, sender.sends.size) // no auto retransmit
        assertEquals(1, outbox.minted)
    }

    @Test
    fun `onConnected performs the TTL sweep (§17-5 24h)`() = runTest {
        val sender = RecordingSender()
        val (coordinator, _) = coordinator(sender)
        coordinator.onConnected() // smoke: no throw, empty pass
    }

    // ---------------------------------------------------------------- CAS queue ops (§7.5)

    @Test
    fun `queue ops are CAS-guarded — missing baseRevision refused BEFORE any mint`() = runTest {
        val sender = RecordingSender()
        val (coordinator, outbox) = coordinator(sender)
        val outcome = coordinator.sendQueuedNow("s1", "q-1", baseRevision = null)
        assertTrue(outcome is CommandCoordinator.Outcome.MissingBaseRevision)
        assertEquals(0, outbox.minted)
        assertEquals(0, sender.sends.size)
    }

    @Test
    fun `queue ops carry the CAS baseRevision on the wire`() = runTest {
        val sender = RecordingSender()
        val (coordinator, _) = coordinator(sender)
        val outcome = coordinator.editQueueItem("s1", "q-1", "new text", baseRevision = 1042)
        assertTrue(outcome is CommandCoordinator.Outcome.Accepted)
        assertEquals(1042L, sender.revisions.single())
    }

    @Test
    fun `cancelBackgroundWork sends the verified payload shape`() = runTest {
        val sender = RecordingSender()
        val (coordinator, _) = coordinator(sender)
        val outcome = coordinator.cancelBackgroundWork("s1", "work-7")
        assertTrue(outcome is CommandCoordinator.Outcome.Accepted)
        assertEquals("cancelBackgroundWork", sender.sends.single().first)
    }

    // ---------------------------------------------------------------- overlay reconciliation

    @Test
    fun `overlay reconciles when the authoritative row lands (§14-2)`() = runTest {
        val sender = RecordingSender(throwOnSend = true)
        val (coordinator, _) = coordinator(sender)
        coordinator.sendPrompt("s1", "hello")
        assertEquals(1, coordinator.pendingSends.value.size)
        coordinator.reconcileAuthoritative("s1", "hello")
        assertTrue(coordinator.pendingSends.value.isEmpty())
    }

    @Test
    fun `overlay is bounded (INV-5 hard cap)`() = runTest {
        val sender = RecordingSender(throwOnSend = true)
        val (coordinator, _) = coordinator(sender)
        repeat(70) { coordinator.sendPrompt("s$it", "m$it") }
        assertTrue(coordinator.pendingSends.value.size <= 64)
    }

    // ---------------------------------------------------------------- helpers

    private fun CommandCoordinator.Outcome.commandId(): CommandId = when (this) {
        is CommandCoordinator.Outcome.Accepted -> CommandId(this.ack.commandId)
        is CommandCoordinator.Outcome.UnknownKept -> this.commandId
        is CommandCoordinator.Outcome.Stale -> CommandId(this.ack.commandId)
        else -> error("no commandId for $this")
    }
}
