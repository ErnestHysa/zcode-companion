package com.zcode.android.data.repo

import com.zcode.android.data.db.DraftEntity
import com.zcode.android.data.db.ZcodeDatabase
import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.domain.repo.DraftRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Prompt drafts (§17.4): per (endpoint, workspace, task), persisted immediately on
 * save (the 300 ms keystroke debounce is the CALLER's concern), survive process
 * death, explicit discard only.
 *
 * N-BH-2 (vc52 Phase 10): STAGED attachment refs share the row. Text and staged
 * saves are READ-MODIFY-WRITE against the column they do NOT own — the naive
 * full-row upsert clobbered the sibling column on every keystroke (the GAP-A2
 * resolvedAtMs clobber class, rule 14).
 */
class DraftRepositoryImpl(private val db: ZcodeDatabase) : DraftRepository {

    private val dao = db.draftDao()
    private val json = Json { ignoreUnknownKeys = true }

    override fun observe(scope: DraftRepository.DraftScope): Flow<String?> =
        dao.observe(scope.endpointId.raw, scope.workspace.raw, scope.taskId).map { it?.text }

    override suspend fun save(scope: DraftRepository.DraftScope, text: String, atMs: Long) {
        val existing = dao.get(scope.endpointId.raw, scope.workspace.raw, scope.taskId)
        dao.upsert(
            DraftEntity(
                endpointId = scope.endpointId.raw,
                workspaceKey = scope.workspace.raw,
                taskId = scope.taskId,
                text = text,
                updatedAtMs = atMs,
                // N-BH-2: a text save preserves the staged refs it does not own.
                attachmentsJson = existing?.attachmentsJson,
            ),
        )
    }

    override suspend fun clear(scope: DraftRepository.DraftScope) {
        dao.delete(scope.endpointId.raw, scope.workspace.raw, scope.taskId)
    }

    override fun observeStaged(scope: DraftRepository.DraftScope): Flow<List<DraftRepository.StagedAttachment>> =
        dao.observe(scope.endpointId.raw, scope.workspace.raw, scope.taskId).map { it?.attachmentsJson?.let(::decodeStaged) ?: emptyList() }

    override suspend fun saveStaged(scope: DraftRepository.DraftScope, list: List<DraftRepository.StagedAttachment>, atMs: Long) {
        val id = scope.endpointId.raw
        val ws = scope.workspace.raw
        val existing = dao.get(id, ws, scope.taskId)
        // An empty staged list with NO row yet is a no-op — never mint a draft row.
        if (existing == null && list.isEmpty()) return
        dao.upsert(
            DraftEntity(
                endpointId = id,
                workspaceKey = ws,
                taskId = scope.taskId,
                text = existing?.text ?: "",
                updatedAtMs = atMs,
                attachmentsJson = encodeStaged(list),
            ),
        )
    }

    // ---------------------------------------------------------------- staged JSON

    private fun encodeStaged(list: List<DraftRepository.StagedAttachment>): String = if (list.isEmpty()) {
        "[]"
    } else {
        buildJsonArray {
            for (a in list) {
                add(
                    buildJsonObject {
                        put("uploadId", JsonPrimitive(a.uploadId))
                        put("fileName", JsonPrimitive(a.fileName))
                        put("mime", JsonPrimitive(a.mime))
                        put("bytes", JsonPrimitive(a.bytes))
                        put("ref", JsonPrimitive(a.ref))
                    },
                )
            }
        }.toString()
    }

    private fun decodeStaged(raw: String): List<DraftRepository.StagedAttachment> =
        runCatching {
            json.parseToJsonElement(raw).jsonArray.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                fun str(key: String): String? = (obj[key] as? JsonPrimitive)?.content
                DraftRepository.StagedAttachment(
                    uploadId = str("uploadId") ?: return@mapNotNull null,
                    fileName = str("fileName") ?: return@mapNotNull null,
                    mime = str("mime") ?: return@mapNotNull null,
                    bytes = (obj["bytes"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L,
                    ref = str("ref") ?: return@mapNotNull null,
                )
            }
        }.getOrDefault(emptyList())
}
