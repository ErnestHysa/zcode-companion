package com.zcode.android.data.session

import com.zcode.android.core.common.diff.DiffHunk
import com.zcode.android.core.common.diff.UnifiedDiffParser
import com.zcode.android.core.protocol.bytes.RpcSerialization.RpcValue
import com.zcode.android.core.protocol.v4.AttachmentLimits
import com.zcode.android.core.protocol.v4.FileChangesResult
import com.zcode.android.core.protocol.v4.V4Attachment
import com.zcode.android.core.transport.AppTransport
import com.zcode.android.domain.model.GitChangeEntry
import com.zcode.android.domain.model.GitCommit
import com.zcode.android.domain.model.GitFileDiff
import com.zcode.android.domain.model.GitSummary
import com.zcode.android.domain.model.ModelProviderOption
import com.zcode.android.domain.model.ModelOption
import com.zcode.android.domain.model.TurnFileChange
import com.zcode.android.domain.model.TurnFileChanges
import com.zcode.android.domain.model.WorkspaceFileEntry
import com.zcode.android.domain.model.WorkspaceTextSlice
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Phase 9 read/write tool plane (§21–§23 + model switch) — the ONE place the new
 * transport surfaces map onto domain models (§40.2: data → protocol). All surfaces
 * are read-only except the VERIFIED write paths: attachment upload (v4 transaction
 * plane, §23.2) and the switch commands (via the outbox gate, not here). INV-9/16
 * are enforced by the transport's typed surface; paths stay display-only.
 */
class Phase9Tools(private val transport: AppTransport) {

    // ---------------------------------------------------------------- turn diffs (9.1)

    /** `conversationFileChangesV4` → pre-parsed hunks mapped onto the §22 model. */
    suspend fun turnFileChanges(
        sessionId: String,
        targetRowId: Long,
        targetEntityId: String,
        baseRevision: Long,
        baseLogEpoch: String,
        workspacePath: String? = null,
        workspaceIdentity: String? = null,
    ): TurnFileChanges {
        val raw = transport.conversationFileChanges(
            sessionId, targetRowId, targetEntityId, baseRevision, baseLogEpoch,
            workspacePath, workspaceIdentity,
        )
        val decoded = FileChangesResult.decode(raw)
        return TurnFileChanges(
            files = decoded.files,
            additions = decoded.additions,
            deletions = decoded.deletions,
            state = decoded.state,
            items = decoded.items.map { item ->
                TurnFileChange(
                    path = item.path,
                    additions = item.additions,
                    deletions = item.deletions,
                    writeCount = item.writeCount,
                    toolNames = item.toolNames,
                    hunks = item.patches.map { hunk ->
                        UnifiedDiffParser.hunkFromWire(hunk.oldStart, hunk.oldLines, hunk.newStart, hunk.newLines, hunk.lines)
                    },
                )
            },
        )
    }

    // ---------------------------------------------------------------- attachments (9.2)

    class AttachmentRejectedException(message: String) : Exception(message)

    /**
     * Validate + BEGIN an attachment upload (§23.2). Client-side gates BEFORE any
     * wire traffic (fail closed): the 20 MiB cap, the MIME grammar, the checksum
     * form. Returns the begin discriminator — `committed` means the server already
     * held the full content (idempotent retry of a finished transaction).
     */
    suspend fun attachmentBegin(
        uploadId: String,
        sessionId: String,
        fileName: String,
        mime: String,
        totalBytes: Long,
        checksum: String,
        workspacePath: String? = null,
        workspaceIdentity: String? = null,
    ): V4Attachment.BeginResult {
        val totalChunks = if (totalBytes == 0L) 0 else (((totalBytes + AttachmentLimits.CHUNK_MAX_BYTES - 1) / AttachmentLimits.CHUNK_MAX_BYTES).toInt())
        val params = V4Attachment.BeginParams(
            connectionId = "", // injected by the transport from the live hello
            uploadId = uploadId, sessionId = sessionId, fileName = fileName, mime = mime,
            totalBytes = totalBytes, totalChunks = totalChunks, checksum = checksum,
        )
        if (totalBytes > AttachmentLimits.MAX_BYTES) {
            throw AttachmentRejectedException("attachment exceeds 20 MiB client-side cap")
        }
        params.validate()
        val raw = transport.attachmentBegin(
            uploadId, sessionId, fileName, mime, totalBytes, totalChunks, checksum,
            workspacePath, workspaceIdentity,
        )
        return V4Attachment.BeginResult.decode(raw)
    }

    /** One chunk (≤ 512 KiB decoded — enforced here BEFORE base64, INV-5). */
    suspend fun attachmentChunk(
        uploadId: String,
        sessionId: String,
        chunkIndex: Int,
        bytes: ByteArray,
        workspacePath: String? = null,
        workspaceIdentity: String? = null,
    ): V4Attachment.ChunkResult {
        if (bytes.size > AttachmentLimits.CHUNK_MAX_BYTES) {
            throw AttachmentRejectedException("chunk exceeds 512 KiB")
        }
        val raw = transport.attachmentChunk(
            uploadId, sessionId, chunkIndex,
            java.util.Base64.getEncoder().encodeToString(bytes),
            workspacePath, workspaceIdentity,
        )
        return V4Attachment.ChunkResult.decode(raw)
    }

    suspend fun attachmentCommit(uploadId: String, sessionId: String): String =
        V4Attachment.CommitResult.decode(transport.attachmentCommit(uploadId, sessionId)).ref

    suspend fun attachmentAbort(uploadId: String, sessionId: String) {
        transport.attachmentAbort(uploadId, sessionId)
    }

    /**
     * Chunked attachment read (§23.3): loops `attachmentRead` until `nextOffset ==
     * null`, streaming decoded bytes into [sink]. The verified media gate (image/
     * video/pdf only) re-fails closed here on every chunk (INV-14).
     */
    suspend fun attachmentReadAll(
        sessionId: String,
        ref: String,
        targetRowId: Long? = null,
        targetEntityId: String? = null,
        attachmentIndex: Int? = null,
        workspacePath: String? = null,
        workspaceIdentity: String? = null,
        sink: (ByteArray) -> Unit,
    ): Pair<String, Long> {
        var offset = 0L
        var mediaType = ""
        var totalBytes = 0L
        var guard = 0
        while (true) {
            if (++guard > AttachmentLimits.MAX_CHUNKS * 8) {
                throw AttachmentRejectedException("attachment read exceeded chunk budget")
            }
            val params = V4Attachment.ReadParams(
                sessionId = sessionId, ref = ref, targetRowId = targetRowId,
                targetEntityId = targetEntityId, attachmentIndex = attachmentIndex,
                offset = offset, limit = AttachmentLimits.CHUNK_MAX_BYTES,
            )
            params.validate()
            val raw = transport.attachmentRead(
                sessionId, ref, offset, params.limit, targetRowId, targetEntityId, attachmentIndex,
                workspacePath, workspaceIdentity,
            )
            val result = V4Attachment.ReadResult.decode(raw)
            mediaType = result.mediaType
            totalBytes = result.totalBytes
            sink(java.util.Base64.getDecoder().decode(result.dataBase64))
            val next = result.nextOffset ?: break
            if (next <= offset) throw AttachmentRejectedException("attachment read cursor did not advance")
            offset = next
        }
        return mediaType to totalBytes
    }

    // ---------------------------------------------------------------- git (9.4)

    suspend fun gitSummary(workspacePath: String): GitSummary {
        val o = transport.gitRepositorySummary(workspacePath)
        return GitSummary(
            repoRoot = o.str("repoRoot"),
            branchName = o.strOrNull("branchName"),
            trackingBranchName = o.strOrNull("trackingBranchName"),
            headRefType = o.strOrNull("headRefType") ?: "branch",
            ahead = o.intOr("ahead"),
            behind = o.intOr("behind"),
            isDirty = o.boolOr("isDirty"),
            isGitAvailable = o.boolOr("isGitAvailable"),
            isRepository = o.boolOr("isRepository"),
        )
    }

    suspend fun gitChanges(workspacePath: String, sourceId: String): List<GitChangeEntry> {
        val o = transport.gitChanges(workspacePath, sourceId)
        val arr = o["items"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val it = el as? JsonObject ?: return@mapNotNull null
            GitChangeEntry(
                path = it.strOrNull("path") ?: return@mapNotNull null,
                repoRelativePath = it.strOrNull("workspaceRelativePath") ?: it.strOrNull("repoRelativePath") ?: "",
                kind = it.strOrNull("kind") ?: "modified",
                section = it.strOrNull("section") ?: sourceId,
                added = it.longOr("added"),
                removed = it.longOr("removed"),
            )
        }
    }

    suspend fun gitDiff(workspacePath: String, path: String, staged: Boolean? = null): GitFileDiff {
        val o = transport.gitDiff(workspacePath, path, staged)
        return GitFileDiff(
            path = o.strOrNull("path") ?: path,
            availability = o.strOrNull("availability") ?: "unavailable",
            patch = o.strOrNull("patch"),
        )
    }

    suspend fun gitCommits(workspacePath: String, maxCount: Int, skip: Int): Pair<List<GitCommit>, Boolean> {
        val o = transport.gitCommitGraph(workspacePath, maxCount, skip)
        val arr = o["commits"] as? JsonArray ?: return emptyList<GitCommit>() to false
        val commits = arr.mapNotNull { el ->
            val c = el as? JsonObject ?: return@mapNotNull null
            GitCommit(
                hash = c.strOrNull("hash") ?: return@mapNotNull null,
                parents = (c["parents"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList(),
                refs = (c["refs"] as? JsonArray)?.mapNotNull { r -> (r as? JsonObject)?.strOrNull("name") } ?: emptyList(),
                subject = c.strOrNull("subject") ?: "",
                authorName = c.strOrNull("authorName"),
                authoredAtMs = c.longOrNull("authoredAtMs"),
            )
        }
        return commits to (o.boolOr("hasMore"))
    }

    // ---------------------------------------------------------------- files (9.2 viewer)

    suspend fun fileEntries(path: String, includeHidden: Boolean = false): List<WorkspaceFileEntry> {
        val raw = transport.fileReaddir(path, includeHidden)
        val objs: List<JsonObject> = when (raw) {
            is RpcValue.Arr -> raw.items.mapNotNull { (it as? RpcValue.Obj)?.json as? JsonObject }
            is RpcValue.Obj -> ((raw.json as? JsonObject)?.get("items") as? JsonArray)
                ?.mapNotNull { it as? JsonObject } ?: emptyList()
            else -> emptyList()
        }
        return objs.mapNotNull { e ->
            val name = e.strOrNull("name") ?: return@mapNotNull null
            WorkspaceFileEntry(
                name = name,
                path = e.strOrNull("path") ?: return@mapNotNull null,
                isDirectory = e.strOrNull("type") == "directory",
                isSymbolicLink = e.boolOr("isSymbolicLink"),
            )
        }
    }

    suspend fun fileText(path: String, offset: Long? = null, length: Long? = null): WorkspaceTextSlice {
        val o = transport.fileReadText(path, offset, length)
        return WorkspaceTextSlice(
            path = o.strOrNull("path") ?: path,
            content = o.strOrNull("content") ?: "",
            offset = o.longOr("offset"),
            bytesRead = o.longOr("bytesRead"),
            totalBytes = o.longOr("totalBytes"),
            truncated = o.boolOr("truncated"),
            isBinary = o.boolOr("isBinary"),
        )
    }

    suspend fun fileBytes(path: String, offset: Long, length: Long): ByteArray =
        transport.fileReadBytes(path, offset, length)

    // ---------------------------------------------------------------- model picker (9.5)

    /**
     * `model-selection.getView()` → provider/model options (graceful on refusal).
     * D4 (Phase 3A): the desktop picker's badge/display fields ride along when present —
     * provider `labelBadge`, model `config.badgeLabel` / `config.label` — tolerant (INV-8):
     * absent → null/modelId, never synthesized.
     */
    suspend fun modelOptions(): List<ModelProviderOption> {
        val o = transport.modelSelectionView()
        val providers = o["providers"] as? JsonArray ?: return emptyList()
        return providers.mapNotNull { el ->
            val p = el as? JsonObject ?: return@mapNotNull null
            val providerId = p.strOrNull("providerId") ?: return@mapNotNull null
            val models = (p["models"] as? JsonArray)?.mapNotNull { m ->
                val mo = m as? JsonObject ?: return@mapNotNull null
                val modelId = mo.strOrNull("modelId") ?: return@mapNotNull null
                val config = mo["config"] as? JsonObject
                ModelOption(
                    id = modelId,
                    label = config?.strOrNull("label") ?: config?.strOrNull("displayName") ?: mo.strOrNull("label") ?: modelId,
                    badge = mo.strOrNull("badgeLabel") ?: config?.strOrNull("badgeLabel"),
                )
            } ?: emptyList()
            ModelProviderOption(
                providerId = providerId,
                providerName = p.strOrNull("providerName"),
                models = models,
                badge = p.strOrNull("labelBadge") ?: p.strOrNull("badgeLabel"),
            )
        }
    }

    // ---------------------------------------------------------------- json helpers

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.content ?: ""

    private fun JsonObject.strOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.content

    private fun JsonObject.intOr(key: String): Int = (this[key] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0

    private fun JsonObject.longOr(key: String): Long = (this[key] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L

    private fun JsonObject.longOrNull(key: String): Long? = (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

    private fun JsonObject.boolOr(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
}
