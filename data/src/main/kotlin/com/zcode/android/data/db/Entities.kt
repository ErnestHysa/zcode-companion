package com.zcode.android.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room schema v1 — ADR-008 structured durable stores (§43.2-4.2): tasks (ETag-validated
 * projections), conversation rows/page cache, recovery journal, command outbox,
 * interaction store, drafts. JSON payloads are stored as TEXT and decoded through the
 * domain's INV-8-tolerant decoders on read (unknown shapes never crash).
 *
 * §32.3: every future change ships a schema export + a migration test from v1 —
 * NEVER destructive for the outbox/journal/credential classes.
 */

@Entity(
    tableName = "tasks",
    primaryKeys = ["endpointId", "workspaceKey", "taskId"],
    indices = [Index(value = ["endpointId", "workspaceKey", "updatedAtMs"])],
)
data class TaskEntity(
    val endpointId: String,
    val workspaceKey: String,
    val taskId: String,
    val title: String,
    /** Raw wire status — tolerant parse at the mapper (INV-8 unknown-tolerant). */
    val statusWire: String,
    val mode: String?,
    val model: String?,
    val archivedAtMs: Long?,
    val additions: Long?,
    val deletions: Long?,
    val files: Long?,
    val pinned: Boolean,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    /** Display-only workspace label (schema v2; INV-12 — never an identity). */
    val workspaceLabel: String? = null,
)

/** Per-workspace snapshot validator (§7.8 getTaskSnapshotWithEtag; §17.3 cache row 1). */
@Entity(tableName = "task_workspace_meta", primaryKeys = ["endpointId", "workspaceKey"])
data class TaskWorkspaceMetaEntity(
    val endpointId: String,
    val workspaceKey: String,
    val etag: String?,
    val lastSyncAtMs: Long,
)

@Entity(tableName = "conversation_session_meta")
data class ConversationSessionMetaEntity(
    @PrimaryKey val sessionId: String,
    /** Stale-guard currency for the cached pages (§9.13/§19.4 epoch invalidation). */
    val logEpoch: String,
    val updatedAtMs: Long,
)

@Entity(
    tableName = "conversation_rows",
    primaryKeys = ["sessionId", "rowId"],
)
data class ConversationRowEntity(
    val sessionId: String,
    val rowId: Long,
    val turnId: String,
    val kind: String,
    /** Full raw row JSON — decoded via the tolerant domain decoder on read (INV-8). */
    val json: String,
    val createdAtSeq: Long,
)

@Entity(
    tableName = "interactions",
    primaryKeys = ["endpointId", "interactionId"],
    indices = [Index(value = ["endpointId", "resolvedAtMs"]), Index(value = ["endpointId", "createdAtMs"])],
)
data class InteractionEntity(
    val endpointId: String,
    val interactionId: String,
    val sessionId: String?,
    /** PERMISSION | USER_INPUT */
    val kind: String,
    /** InteractionLifecycle verbatim (§14.4.3 store column). */
    val lifecycle: String,
    val toolName: String?,
    /** Full payload JSON (open-set options preserved; fail-closed at the mapper). */
    val payloadJson: String,
    val createdAtMs: Long,
    val resolvedAtMs: Long?,
)

@Entity(
    tableName = "outbox",
    indices = [Index(value = ["endpointId", "state"]), Index(value = ["endpointId", "lastTransitionAtMs"])],
)
data class OutboxEntity(
    /** Client-generated uuid v7 — INVARIANT across retries (INV-4). */
    @PrimaryKey val commandId: String,
    val endpointId: String,
    val sessionId: String?,
    val type: String,
    val payloadJson: String,
    val baseRevision: Long?,
    val baseLogEpoch: String?,
    val issuedAtMs: Long,
    /** OutboxStateMachine.State verbatim. */
    val state: String,
    val attempts: Int,
    val lastTransitionAtMs: Long,
    val ackStatus: String?,
    val ackReasonCode: String?,
    val ackMessage: String?,
    val ackRevisionAtDecision: Long?,
)

@Entity(tableName = "journal_state")
data class JournalStateEntity(
    @PrimaryKey val endpointId: String,
    val helloServerTimeMs: Double?,
    val capabilitiesJson: String?,
    val version: Int,
    val updatedAtMs: Long,
)

@Entity(tableName = "journal_subscriptions", primaryKeys = ["endpointId", "topic"])
data class JournalSubscriptionEntity(
    val endpointId: String,
    val topic: String,
    val sessionId: String,
    val afterSeq: Long,
    val logEpoch: String,
)

@Entity(tableName = "journal_pending_commands")
data class JournalPendingCommandEntity(
    @PrimaryKey val commandId: String,
    val endpointId: String,
    val type: String,
    val payloadRef: String,
    val issuedAtMs: Long,
)

@Entity(tableName = "drafts", primaryKeys = ["endpointId", "workspaceKey", "taskId"])
data class DraftEntity(
    val endpointId: String,
    val workspaceKey: String,
    val taskId: String,
    val text: String,
    val updatedAtMs: Long,
)
