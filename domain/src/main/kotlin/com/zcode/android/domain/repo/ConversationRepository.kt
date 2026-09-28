package com.zcode.android.domain.repo

import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.domain.ids.SessionId
import com.zcode.android.domain.ids.WorkspaceIdentityKey
import com.zcode.android.domain.model.ConversationRow
import kotlinx.coroutines.flow.Flow

/**
 * Store-level conversation operations — the PERSISTENT cache contract (§17.3
 * conversation rows cache). These are cache-apply operations, NOT the runtime
 * projection reducers: the (logEpoch, seq) stale-guard reducers (Phase 5.1) sit in
 * front and call these after their own guards. The store's own epoch rule is
 * mechanical: any op whose logEpoch differs from the cached session epoch is
 * REJECTED without mutation (never merge ambiguous state, §15.5); snapshots
 * REPLACE atomically (§15.5).
 */
sealed class ConversationStoreOp {
    abstract val sessionId: SessionId
    abstract val logEpoch: String

    /** Snapshot → atomic replacement of the session's cached rows (§15.5). */
    data class SnapshotArrived(
        override val sessionId: SessionId,
        override val logEpoch: String,
        val rows: List<ConversationRow>,
    ) : ConversationStoreOp()

    /** rowsRange page (read-only, safe to re-send; keyed by anchor). */
    data class PageFetched(
        override val sessionId: SessionId,
        override val logEpoch: String,
        val rows: List<ConversationRow>,
    ) : ConversationStoreOp()

    /** row.upserted / row.appended class ops (idempotent by rowId). */
    data class RowUpserted(
        override val sessionId: SessionId,
        override val logEpoch: String,
        val row: ConversationRow,
    ) : ConversationStoreOp()

    /** row.removed — truncates from `fromRowId` upward (delta.ts semantics). */
    data class RowRemoved(
        override val sessionId: SessionId,
        override val logEpoch: String,
        val fromRowId: Long,
    ) : ConversationStoreOp()

    /** row.delta text append onto a cached row (delta.ts `row.delta {rowId, append}`). */
    data class RowTextAppended(
        override val sessionId: SessionId,
        override val logEpoch: String,
        val rowId: Long,
        val append: String,
    ) : ConversationStoreOp()
}

/** Mechanical store outcome — callers escalate mismatches to resync (never merge). */
sealed class StoreApplyResult {
    /** Applied cleanly. */
    data object Applied : StoreApplyResult()

    /** Snapshot replaced an existing projection (epoch moved or fresh). */
    data class Replaced(val previousEpoch: String?) : StoreApplyResult()

    /** Epoch mismatch — NOTHING was mutated; caller must resync (§15-S9/S10). */
    data class EpochRejected(val currentEpoch: String) : StoreApplyResult()

    /** Row-level no-op (append target missing → dropped, §15-S11 class). */
    data object Dropped : StoreApplyResult()
}

/** §17.3 conversation cache bounds (verified limits, §7.4.5 + §19.2). */
object ConversationCacheBounds {
    /** Render window = verified snapshotTailWindowRows (§19.2). */
    const val RENDER_WINDOW_ROWS = 60
    /** rowsRange page cap — verified protocol limit (INV-13). */
    const val PAGE_MAX_ROWS = 200
    /**
     * Per-session cached-row bound — §17.3: "≤ 2 × render window + 1 page".
     * Overflow evicts the OLDEST cached rows (lowest rowIds) beyond the newest bound.
     */
    const val MAX_CACHED_ROWS_PER_SESSION = 2 * RENDER_WINDOW_ROWS + PAGE_MAX_ROWS
}

interface ConversationRepository {
    fun observeRows(sessionId: SessionId): Flow<List<ConversationRow>>
    fun observeEpoch(sessionId: SessionId): Flow<String?>
    suspend fun currentEpoch(sessionId: SessionId): String?

    /** Apply one store op; epoch mismatches reject mechanically (never merge). */
    suspend fun apply(op: ConversationStoreOp): StoreApplyResult

    /** Diagnostics: cached row count for a session (bounds observability, INV-5). */
    suspend fun cachedRowCount(sessionId: SessionId): Int
}

/** Task list cache — ETag-validated projections (§17.3, §7.8). */
interface TaskRepository {
    fun observeTasks(endpointId: EndpointId, workspace: WorkspaceIdentityKey): Flow<List<com.zcode.android.domain.model.Task>>

    /**
     * Replace the workspace's cached task list. ETag flow (§7.8
     * getTaskSnapshotWithEtag): when [etag] equals the cached validator the write is
     * SKIPPED (304-class) and `false` returned.
     */
    suspend fun replaceAll(
        endpointId: EndpointId,
        workspace: WorkspaceIdentityKey,
        tasks: List<com.zcode.android.domain.model.Task>,
        etag: com.zcode.android.domain.model.SnapshotEtag?,
        atMs: Long,
    ): Boolean

    /** ETag flow: cached validator for the workspace (null = never synced). */
    suspend fun cachedEtag(endpointId: EndpointId, workspace: WorkspaceIdentityKey): com.zcode.android.domain.model.SnapshotEtag?

    /** Single-task upsert (status transitions arrive as deltas). Idempotent by taskId. */
    suspend fun upsert(endpointId: EndpointId, workspace: WorkspaceIdentityKey, task: com.zcode.android.domain.model.Task)

    /** Task removed/archived on desktop (§15-S17) — projection reflects removal. */
    suspend fun remove(endpointId: EndpointId, workspace: WorkspaceIdentityKey, taskId: String)

    /** §17.3 bounds: ≤ 500 tasks/workspace, stale > 7 d dropped. */
    suspend fun evictAndSweep(endpointId: EndpointId, workspace: WorkspaceIdentityKey, nowMs: Long): Int
}
