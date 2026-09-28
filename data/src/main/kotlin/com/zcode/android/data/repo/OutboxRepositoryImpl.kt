package com.zcode.android.data.repo

import com.zcode.android.core.protocol.v4.UuidV7
import com.zcode.android.data.db.OutboxEntity
import com.zcode.android.data.StorageCorruption
import com.zcode.android.data.db.ZcodeDatabase
import com.zcode.android.domain.ids.CommandId
import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.domain.journal.RecoveryJournal
import com.zcode.android.domain.outbox.OutboxEntry
import com.zcode.android.domain.outbox.OutboxStateMachine
import com.zcode.android.domain.repo.OutboxRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed command outbox (§17.5; ADR-008). INV-4 enforcement point: every state
 * change passes through the domain [OutboxStateMachine] — the store NEVER mutates a
 * state column directly, so no persistence path can duplicate a server-contacted
 * command or mint a second commandId for a live envelope.
 */
class OutboxRepositoryImpl(
    private val db: ZcodeDatabase,
    private val newCommandId: () -> String = UuidV7::generate,
) : OutboxRepository {

    private val dao = db.outboxDao()

    override fun observePending(endpointId: EndpointId): Flow<List<OutboxEntry>> =
        dao.observePending(endpointId.raw).map { list -> list.map { it.toDomain() } }

    override suspend fun accept(
        endpointId: EndpointId,
        sessionId: String?,
        type: String,
        payloadJson: String,
        baseRevision: Long?,
        baseLogEpoch: String?,
        issuedAtMs: Long,
    ): CommandId {
        val commandId = CommandId(newCommandId())
        dao.upsert(
            OutboxEntity(
                commandId = commandId.raw,
                endpointId = endpointId.raw,
                sessionId = sessionId,
                type = type,
                payloadJson = payloadJson,
                baseRevision = baseRevision,
                baseLogEpoch = baseLogEpoch,
                issuedAtMs = issuedAtMs,
                state = OutboxStateMachine.State.ACCEPTED_LOCALLY.name,
                attempts = 0,
                lastTransitionAtMs = issuedAtMs,
                ackStatus = null,
                ackReasonCode = null,
                ackMessage = null,
                ackRevisionAtDecision = null,
            ),
        )
        return commandId
    }

    override suspend fun apply(commandId: CommandId, event: OutboxStateMachine.Event, atMs: Long): OutboxEntry? {
        val current = dao.get(commandId.raw) ?: return null
        val machine = OutboxStateMachine.restore(parseState(current.state))
        val to = machine.on(event) // throws IllegalOutboxTransition on INV-4 violations
        val updated = current.copy(
            state = to.name,
            lastTransitionAtMs = atMs,
            attempts = if (event is OutboxStateMachine.Event.Transmit || event is OutboxStateMachine.Event.UserGatedRetry) {
                current.attempts + 1
            } else {
                current.attempts
            },
            ackStatus = (event as? OutboxStateMachine.Event.Ack)?.ackStatus ?: current.ackStatus,
            ackReasonCode = (event as? OutboxStateMachine.Event.Ack)?.reasonCode ?: current.ackReasonCode,
            ackMessage = (event as? OutboxStateMachine.Event.Ack)?.message ?: current.ackMessage,
            ackRevisionAtDecision = (event as? OutboxStateMachine.Event.Ack)?.revisionAtDecision
                ?: current.ackRevisionAtDecision,
        )
        dao.upsert(updated)
        return updated.toDomain()
    }

    override suspend fun get(commandId: CommandId): OutboxEntry? = dao.get(commandId.raw)?.toDomain()

    override suspend fun sweepExpired(endpointId: EndpointId, nowMs: Long, ttlMs: Long): Int =
        dao.expireOlderThan(endpointId.raw, nowMs - ttlMs, nowMs)

    override suspend fun pendingRefs(endpointId: EndpointId): List<RecoveryJournal.PendingCommandRef> =
        dao.liveList(endpointId.raw).map {
            RecoveryJournal.PendingCommandRef(
                commandId = it.commandId,
                type = it.type,
                payloadRef = it.commandId, // opaque ref into the outbox store (§15.4)
                issuedAtMs = it.issuedAtMs,
            )
        }

    override suspend fun countByState(endpointId: EndpointId, state: OutboxStateMachine.State): Int =
        dao.countByState(endpointId.raw, state.name)

    // ------------------------------------------------------------------ mapping

    private fun parseState(name: String): OutboxStateMachine.State =
        runCatching { OutboxStateMachine.State.valueOf(name) }.getOrElse {
            throw StorageCorruption("outbox state `$name` unknown (§33 storage: quarantine + reinit)")
        }

    private fun OutboxEntity.toDomain() = OutboxEntry(
        commandId = CommandId(commandId),
        endpointId = endpointId,
        sessionId = sessionId,
        type = type,
        payloadJson = payloadJson,
        baseRevision = baseRevision,
        baseLogEpoch = baseLogEpoch,
        issuedAtMs = issuedAtMs,
        state = parseState(state),
        attempts = attempts,
        lastTransitionAtMs = lastTransitionAtMs,
        ackStatus = ackStatus,
        ackReasonCode = ackReasonCode,
        ackMessage = ackMessage,
        ackRevisionAtDecision = ackRevisionAtDecision,
    )
}
