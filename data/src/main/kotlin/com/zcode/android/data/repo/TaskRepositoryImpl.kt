package com.zcode.android.data.repo

import androidx.room.withTransaction
import com.zcode.android.data.db.TaskDao
import com.zcode.android.data.db.TaskEntity
import com.zcode.android.data.db.TaskWorkspaceMetaEntity
import com.zcode.android.data.db.ZcodeDatabase
import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.domain.ids.WorkspaceIdentityKey
import com.zcode.android.domain.model.SnapshotEtag
import com.zcode.android.domain.model.Task
import com.zcode.android.domain.model.TaskStatus
import com.zcode.android.domain.repo.TaskRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Task projection cache (§17.3 row 1): ETag-validated list snapshots
 * (getTaskSnapshotWithEtag), per-workspace LRU ≤ 500 tasks, stale > 7 d dropped.
 * Android never authoritative — this is a cache of desktop truth (INV-1).
 */
class TaskRepositoryImpl(
    private val db: ZcodeDatabase,
    private val bounds: Bounds = Bounds(),
    private val clock: () -> Long = System::currentTimeMillis,
) : TaskRepository {

    data class Bounds(
        /** §17.3: ≤ 500 tasks/workspace. */
        val maxTasksPerWorkspace: Int = 500,
        /** §17.3: stale > 7 d dropped. */
        val staleTtlMs: Long = 7L * 24 * 60 * 60 * 1000,
    )

    private val dao = db.taskDao()

    override fun observeTasks(endpointId: EndpointId, workspace: WorkspaceIdentityKey): Flow<List<Task>> =
        dao.observe(endpointId.raw, workspace.raw).map { list -> list.map { it.toDomain() } }

    override suspend fun replaceAll(
        endpointId: EndpointId,
        workspace: WorkspaceIdentityKey,
        tasks: List<Task>,
        etag: SnapshotEtag?,
        atMs: Long,
    ): Boolean {
        val cached = dao.meta(endpointId.raw, workspace.raw)
        // ETag flow (§7.8): identical validator → 304-class skip; never rewrite.
        if (cached != null && etag != null && cached.etag != null && cached.etag == etag.raw) {
            return false
        }
        db.withTransaction {
            // Full-snapshot semantics: the workspace list is replaced, not merged —
            // removals on the desktop must reflect here (§15-S17).
            dao.deleteByIds(endpointId.raw, workspace.raw, dao.list(endpointId.raw, workspace.raw).map { it.taskId })
            dao.upsertAll(tasks.map { it.toEntity(endpointId, workspace) })
            dao.putMeta(TaskWorkspaceMetaEntity(endpointId.raw, workspace.raw, etag?.raw, atMs))
            evictAndSweep(endpointId, workspace, atMs)
        }
        return true
    }

    override suspend fun cachedEtag(endpointId: EndpointId, workspace: WorkspaceIdentityKey): SnapshotEtag? =
        dao.meta(endpointId.raw, workspace.raw)?.etag?.let { SnapshotEtag(it) }

    override suspend fun upsert(endpointId: EndpointId, workspace: WorkspaceIdentityKey, task: Task) {
        dao.upsertAll(listOf(task.toEntity(endpointId, workspace)))
    }

    override suspend fun remove(endpointId: EndpointId, workspace: WorkspaceIdentityKey, taskId: String) {
        dao.deleteByIds(endpointId.raw, workspace.raw, listOf(taskId))
    }

    override suspend fun evictAndSweep(endpointId: EndpointId, workspace: WorkspaceIdentityKey, nowMs: Long): Int {
        var dropped = dao.deleteStale(endpointId.raw, workspace.raw, nowMs - bounds.staleTtlMs)
        val victims = dao.beyondCap(endpointId.raw, workspace.raw, bounds.maxTasksPerWorkspace)
        if (victims.isNotEmpty()) {
            dao.deleteByIds(endpointId.raw, workspace.raw, victims)
            dropped += victims.size
        }
        return dropped
    }

    suspend fun count(endpointId: EndpointId, workspace: WorkspaceIdentityKey): Int =
        dao.count(endpointId.raw, workspace.raw)

    // ------------------------------------------------------------------ mapping

    private fun TaskEntity.toDomain(): Task = Task(
        taskId = taskId,
        title = title,
        status = TaskStatus.fromWire(statusWire),
        workspace = WorkspaceIdentityKey(workspaceKey),
        createdAtMs = createdAtMs,
        updatedAtMs = updatedAtMs,
        mode = mode,
        model = model,
        archivedAtMs = archivedAtMs,
        summary = if (additions != null || deletions != null || files != null) {
            Task.TaskSummary(additions ?: 0, deletions ?: 0, files ?: 0)
        } else {
            null
        },
        pinned = pinned,
        rawStatus = if (TaskStatus.fromWire(statusWire) == TaskStatus.UNKNOWN) statusWire else null,
    )

    private fun Task.toEntity(endpointId: EndpointId, workspace: WorkspaceIdentityKey): TaskEntity = TaskEntity(
        endpointId = endpointId.raw,
        workspaceKey = workspace.raw,
        taskId = taskId,
        title = title,
        statusWire = rawStatus ?: status.wire,
        mode = mode,
        model = model,
        archivedAtMs = archivedAtMs,
        additions = summary?.additions,
        deletions = summary?.deletions,
        files = summary?.files,
        pinned = pinned,
        createdAtMs = createdAtMs,
        updatedAtMs = updatedAtMs,
    )
}
