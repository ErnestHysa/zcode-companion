package com.zcode.android.app.connect

import com.zcode.android.core.protocol.v4.UuidV7
import com.zcode.android.domain.ids.CommandId
import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.domain.outbox.OutboxEntry
import com.zcode.android.domain.outbox.OutboxStateMachine
import com.zcode.android.domain.repo.DraftRepository
import com.zcode.android.domain.repo.OutboxRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Phase 7 test-support stores (app-module unit/integration tests): in-memory
 * outbox + drafts enforcing the REAL domain state machine (INV-4 parity).
 */

/** In-memory outbox — every transition passes through [OutboxStateMachine] (INV-4). */
internal class InMemoryOutboxRepository(
    private val newCommandId: () -> String = UuidV7::generate,
) : OutboxRepository {

    val entries = LinkedHashMap<String, OutboxEntry>()
    val transmitted = mutableListOf<String>()

    override fun observePending(endpointId: EndpointId): Flow<List<OutboxEntry>> =
        MutableStateFlow(entries.values.filter { it.endpointId == endpointId.raw })

    override suspend fun accept(
        endpointId: EndpointId,
        sessionId: String?,
        type: String,
        payloadJson: String,
        baseRevision: Long?,
        baseLogEpoch: String?,
        issuedAtMs: Long,
    ): CommandId {
        val id = CommandId(newCommandId())
        entries[id.raw] = OutboxEntry(
            commandId = id, endpointId = endpointId.raw, sessionId = sessionId, type = type,
            payloadJson = payloadJson, baseRevision = baseRevision, baseLogEpoch = baseLogEpoch,
            issuedAtMs = issuedAtMs, state = OutboxStateMachine.State.ACCEPTED_LOCALLY,
            attempts = 0, lastTransitionAtMs = issuedAtMs,
        )
        return id
    }

    override suspend fun apply(commandId: CommandId, event: OutboxStateMachine.Event, atMs: Long): OutboxEntry? {
        val current = entries[commandId.raw] ?: return null
        val machine = OutboxStateMachine.restore(current.state)
        val to = machine.on(event)
        if (event is OutboxStateMachine.Event.Transmit || event is OutboxStateMachine.Event.UserGatedRetry ||
            event is OutboxStateMachine.Event.AutoRetransmit
        ) {
            transmitted.add(commandId.raw)
        }
        val updated = current.copy(
            state = to,
            lastTransitionAtMs = atMs,
            attempts = current.attempts + 1,
            ackStatus = (event as? OutboxStateMachine.Event.Ack)?.ackStatus ?: current.ackStatus,
            ackReasonCode = (event as? OutboxStateMachine.Event.Ack)?.reasonCode ?: current.ackReasonCode,
            ackMessage = (event as? OutboxStateMachine.Event.Ack)?.message ?: current.ackMessage,
            ackRevisionAtDecision = (event as? OutboxStateMachine.Event.Ack)?.revisionAtDecision ?: current.ackRevisionAtDecision,
        )
        entries[commandId.raw] = updated
        return updated
    }

    override suspend fun get(commandId: CommandId): OutboxEntry? = entries[commandId.raw]

    override suspend fun sweepExpired(endpointId: EndpointId, nowMs: Long, ttlMs: Long): Int = 0

    override suspend fun pendingRefs(endpointId: EndpointId) = emptyList<com.zcode.android.domain.journal.RecoveryJournal.PendingCommandRef>()

    override suspend fun countByState(endpointId: EndpointId, state: OutboxStateMachine.State): Int =
        entries.values.count { it.endpointId == endpointId.raw && it.state == state }
}

internal class InMemoryDraftRepository : DraftRepository {
    private val flows = HashMap<Triple<String, String, String>, MutableStateFlow<String?>>()
    private fun flowOf(scope: DraftRepository.DraftScope) =
        flows.getOrPut(scope.key()) { MutableStateFlow(null) }

    override fun observe(scope: DraftRepository.DraftScope): Flow<String?> = flowOf(scope)

    override suspend fun save(scope: DraftRepository.DraftScope, text: String, atMs: Long) {
        flowOf(scope).value = text
    }

    override suspend fun clear(scope: DraftRepository.DraftScope) {
        flowOf(scope).value = null
    }

    private fun DraftRepository.DraftScope.key() = Triple(endpointId.raw, workspace.raw, taskId)
}
