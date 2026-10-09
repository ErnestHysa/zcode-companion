package com.zcode.android.data.repo

import com.zcode.android.data.db.DraftEntity
import com.zcode.android.data.db.ZcodeDatabase
import com.zcode.android.domain.repo.DraftRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Prompt drafts (§17.4): per (endpoint, workspace, task), persisted immediately on
 * save (the 300 ms keystroke debounce is the CALLER's concern), survive process
 * death, explicit discard only.
 */
class DraftRepositoryImpl(private val db: ZcodeDatabase) : DraftRepository {

    private val dao = db.draftDao()

    override fun observe(scope: DraftRepository.DraftScope): Flow<String?> =
        dao.observe(scope.endpointId.raw, scope.workspace.raw, scope.taskId).map { it?.text }

    override suspend fun save(scope: DraftRepository.DraftScope, text: String, atMs: Long) {
        dao.upsert(
            DraftEntity(
                endpointId = scope.endpointId.raw,
                workspaceKey = scope.workspace.raw,
                taskId = scope.taskId,
                text = text,
                updatedAtMs = atMs,
            ),
        )
    }

    override suspend fun clear(scope: DraftRepository.DraftScope) {
        dao.delete(scope.endpointId.raw, scope.workspace.raw, scope.taskId)
    }
}
