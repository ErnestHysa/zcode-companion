package com.zcode.android.data.session

import com.zcode.android.core.protocol.bytes.RpcSerialization.RpcValue
import com.zcode.android.core.transport.AppTransport
import com.zcode.android.core.transport.ErrorMapping
import com.zcode.android.core.transport.TransportFailure
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * A3.11 (gaps-and-polish Phase 5B): the `git-checkpoint` channel trio —
 * `createCheckpoint {workspacePath}` → GitCheckpointMeta (checkpointId/refName/commitOid/…),
 * `diffCheckpoints {workspacePath, fromCheckpointId, toCheckpointId}` → `{files:[…]}`,
 * `restoreBetweenCheckpoints {…, force?}` → `{success, restoredPaths}` | `{success:false,
 * conflicts}` (host/index.js ≈218759). ADR-021 class: restore is a DESTRUCTIVE worktree
 * write — the port exposes it only behind the Git screen's warned confirm (restore = ⚠).
 */
class GitCheckpointClient(private val transport: AppTransport) {

    private suspend fun call(method: String, params: JsonObject): JsonObject {
        val raw = transport.channelCall("git-checkpoint", method, RpcValue.Arr(listOf(RpcValue.Obj(params))), 60_000)
        return (raw as? RpcValue.Obj)?.json as? JsonObject
            ?: throw TransportFailure(ErrorMapping.protocolFault("git-checkpoint.$method result not an object"))
    }

    /** The new checkpoint's meta (the phone keeps the id for the diff/restore pair). */
    suspend fun createCheckpoint(workspacePath: String): JsonObject =
        call("createCheckpoint", buildJsonObject { put("workspacePath", JsonPrimitive(workspacePath)) })

    suspend fun diffCheckpoints(workspacePath: String, fromCheckpointId: String, toCheckpointId: String): JsonObject =
        call("diffCheckpoints", buildJsonObject {
            put("workspacePath", JsonPrimitive(workspacePath))
            put("fromCheckpointId", JsonPrimitive(fromCheckpointId))
            put("toCheckpointId", JsonPrimitive(toCheckpointId))
        })

    /** force=true overwrites local modifications — reachable ONLY through the second confirm. */
    suspend fun restoreBetweenCheckpoints(
        workspacePath: String,
        fromCheckpointId: String,
        toCheckpointId: String,
        force: Boolean = false,
    ): JsonObject = call("restoreBetweenCheckpoints", buildJsonObject {
        put("workspacePath", JsonPrimitive(workspacePath))
        put("fromCheckpointId", JsonPrimitive(fromCheckpointId))
        put("toCheckpointId", JsonPrimitive(toCheckpointId))
        if (force) put("force", JsonPrimitive(true))
    })
}
