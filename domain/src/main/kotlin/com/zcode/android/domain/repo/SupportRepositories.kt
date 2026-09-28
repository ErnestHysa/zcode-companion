package com.zcode.android.domain.repo

import com.zcode.android.domain.ids.CommandId
import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.domain.ids.InteractionId
import com.zcode.android.domain.ids.SessionId
import com.zcode.android.domain.ids.WorkspaceIdentityKey
import com.zcode.android.domain.model.InteractionLifecycle
import com.zcode.android.domain.model.PermissionRequest
import com.zcode.android.domain.model.UserInputRequest
import com.zcode.android.domain.outbox.OutboxEntry
import com.zcode.android.domain.outbox.OutboxStateMachine
import kotlinx.coroutines.flow.Flow

/**
 * Interaction store (§17.3: open/recent interactions; resolved > 24 h dropped;
 * ≤ 64 bound). Card STATE MACHINERY is Phase 5.4; this is the durable store contract.
 */
interface InteractionRepository {
    fun observeOpen(endpointId: EndpointId): Flow<List<InteractionRecord>>

    /** Persist a request (permission or user-input) — idempotent by interactionId. */
    suspend fun upsertPermission(request: PermissionRequest, endpointId: EndpointId, lifecycle: InteractionLifecycle)
    suspend fun upsertUserInput(request: UserInputRequest, endpointId: EndpointId, lifecycle: InteractionLifecycle)

    /** Authoritative resolution arrived (event or reconcile) — timestamped. */
    suspend fun markResolved(endpointId: EndpointId, interactionId: InteractionId, atMs: Long, resolvedBySelf: Boolean)

    /** §17.3 sweep: resolved > 24 h dropped; ≤ 64 total enforced. Returns dropped count. */
    suspend fun sweep(endpointId: EndpointId, nowMs: Long): Int

    /** Forensics/diagnostics: current stored count (bound observability, INV-5). */
    suspend fun count(endpointId: EndpointId): Int

    data class InteractionRecord(
        val interactionId: InteractionId,
        val sessionId: SessionId?,
        val kind: Kind,
        val lifecycle: InteractionLifecycle,
        val toolName: String?,
        val createdAtMs: Long,
        val resolvedAtMs: Long?,
    ) {
        enum class Kind { PERMISSION, USER_INPUT }
    }
}

/**
 * Outbox persistence (§17.5; Room per ADR-008). Every transition passes through the
 * domain state machine — persistence NEVER mutates state directly (INV-4).
 */
interface OutboxRepository {
    fun observePending(endpointId: EndpointId): Flow<List<OutboxEntry>>

    /** ACCEPT: mint commandId here (once), persist ACCEPTED_LOCALLY. */
    suspend fun accept(endpointId: EndpointId, sessionId: String?, type: String, payloadJson: String, baseRevision: Long?, baseLogEpoch: String?, issuedAtMs: Long): CommandId

    /** Apply one state-machine event to a persisted entry (validates + persists). */
    suspend fun apply(commandId: CommandId, event: OutboxStateMachine.Event, atMs: Long): OutboxEntry?

    /** Load one entry (restore path). */
    suspend fun get(commandId: CommandId): OutboxEntry?

    /** TTL sweep (§17.5: 24 h verified server TTL). Returns expired count. */
    suspend fun sweepExpired(endpointId: EndpointId, nowMs: Long, ttlMs: Long = OutboxStateMachine.SERVER_TTL_MS): Int

    /** Entries awaiting authoritative outcome (journal pendingOutbox projection). */
    suspend fun pendingRefs(endpointId: EndpointId): List<com.zcode.android.domain.journal.RecoveryJournal.PendingCommandRef>

    /** Diagnostics: entries per state (INV-5 observability). */
    suspend fun countByState(endpointId: EndpointId, state: OutboxStateMachine.State): Int
}

/** Recovery journal persistence (§15.4/§4.5): transactional writes + cold replay. */
interface JournalRepository {
    /** Record a full journal snapshot transactionally (§15.4 "persisted transactionally at each transition"). */
    suspend fun record(journal: com.zcode.android.domain.journal.RecoveryJournal)

    /** Cold-start replay (§15-S2): last recorded journal or null. */
    suspend fun load(endpointId: EndpointId): com.zcode.android.domain.journal.RecoveryJournal?

    /** Unpair/endpoint removal: journal destroyed with the identity (§14.4.1 Closed). */
    suspend fun clear(endpointId: EndpointId)
}

/** Prompt drafts (§17.4): per (endpoint, workspace, task), explicit discard only. */
interface DraftRepository {
    fun observe(scope: DraftScope): Flow<String?>
    suspend fun save(scope: DraftScope, text: String, atMs: Long)
    suspend fun clear(scope: DraftScope)

    data class DraftScope(val endpointId: EndpointId, val workspace: WorkspaceIdentityKey, val taskId: String)
}
