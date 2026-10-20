package com.zcode.android.domain.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A3.11 (gaps-and-polish Phase 5B): the rewind preview of `conversationFileRewindPreviewV4`
 * (chunk-LNGNUNBX `nt`, .strict()): `{canApply, safeFiles:[{action: restore|delete,
 * operationCount, path, toolNames}], unsafeFiles:[{path, reason: checkpoint_missing|
 * checkpoint_unreadable|external_modified|file_read_failed|unsupported_checkpoint,
 * currentHash?, expectedHash?, message?, toolNames}], ignoredFiles:[{path,
 * reason: "bash_ignored", …}]}`. Unsafe files are NEVER auto-applied — the sheet shows
 * the reason and the Confirm is disabled unless `canApply` (the desktop's own gate).
 */
data class RewindPreview(
    val canApply: Boolean,
    val safeFiles: List<FileAction>,
    val unsafeFiles: List<UnsafeFile>,
    val ignoredFiles: List<IgnoredFile>,
) {
    data class FileAction(val action: String, val path: String, val operationCount: Int, val toolNames: List<String>)
    data class UnsafeFile(val path: String, val reason: String, val message: String?, val toolNames: List<String>)
    data class IgnoredFile(val path: String, val reason: String)

    companion object {
        fun decode(o: JsonObject): RewindPreview = RewindPreview(
            canApply = o.rpBool("canApply") ?: false,
            safeFiles = (o["safeFiles"] as? JsonArray)?.mapNotNull { f ->
                (f as? JsonObject)?.let { f2 ->
                    val path = f2.rpStr("path") ?: return@mapNotNull null
                    FileAction(f2.rpStr("action") ?: "restore", path, f2.rpLong("operationCount")?.toInt() ?: 0, f2.rpStrList("toolNames"))
                }
            } ?: emptyList(),
            unsafeFiles = (o["unsafeFiles"] as? JsonArray)?.mapNotNull { f ->
                (f as? JsonObject)?.let { f2 ->
                    val path = f2.rpStr("path") ?: return@mapNotNull null
                    UnsafeFile(path, f2.rpStr("reason") ?: "unknown", f2.rpStr("message"), f2.rpStrList("toolNames"))
                }
            } ?: emptyList(),
            ignoredFiles = (o["ignoredFiles"] as? JsonArray)?.mapNotNull { f ->
                (f as? JsonObject)?.let { f2 ->
                    val path = f2.rpStr("path") ?: return@mapNotNull null
                    IgnoredFile(path, f2.rpStr("reason") ?: "unknown")
                }
            } ?: emptyList(),
        )
    }
}

private fun JsonObject.rpPrim(key: String): JsonPrimitive? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }
private fun JsonObject.rpStr(key: String): String? = rpPrim(key)?.content?.takeIf { it.isNotBlank() }
private fun JsonObject.rpLong(key: String): Long? = rpPrim(key)?.content?.toDoubleOrNull()?.toLong()
private fun JsonObject.rpBool(key: String): Boolean? = rpPrim(key)?.content?.toBooleanStrictOrNull()
private fun JsonObject.rpStrList(key: String): List<String> = (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content } ?: emptyList()

/**
 * A3.11: the `git-checkpoint` shapes — `isGitCheckpointMeta` (host/index.js ≈219600:
 * checkpointId/workspacePath/repoRoot/workspaceInRepoPath/createdAt/refName/commitOid,
 * scope:"workspace"), the checkpoint diff `{files:[{kind, repoRelativePath,…}]}` and the
 * restore result `{success, restoredPaths}` | `{success:false, conflicts}`.
 */
data class CheckpointMeta(
    val checkpointId: String,
    val refName: String?,
    val commitOid: String?,
    val createdAtMs: Long,
) {
    companion object {
        fun decode(o: JsonObject): CheckpointMeta? {
            val id = (o["checkpointId"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: return null
            return CheckpointMeta(
                checkpointId = id,
                refName = (o["refName"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content,
                commitOid = (o["commitOid"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content,
                createdAtMs = (o["createdAt"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toDoubleOrNull()?.toLong() ?: 0,
            )
        }
    }
}

data class CheckpointDiff(val files: List<String>) {
    companion object {
        fun decode(o: JsonObject): CheckpointDiff = CheckpointDiff(
            (o["files"] as? JsonArray)?.mapNotNull { f ->
                (f as? JsonObject)?.let { (it["repoRelativePath"] as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content }
            } ?: emptyList(),
        )
    }
}

data class CheckpointRestore(val success: Boolean, val restoredPaths: List<String>, val conflicts: List<String>) {
    companion object {
        fun decode(o: JsonObject): CheckpointRestore = CheckpointRestore(
            success = (o["success"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toBooleanStrictOrNull() ?: false,
            restoredPaths = (o["restoredPaths"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content } ?: emptyList(),
            conflicts = (o["conflicts"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content } ?: emptyList(),
        )
    }
}
