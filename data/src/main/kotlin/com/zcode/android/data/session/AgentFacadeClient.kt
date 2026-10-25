package com.zcode.android.data.session

import com.zcode.android.core.protocol.bytes.RpcSerialization.RpcValue
import com.zcode.android.core.transport.AppTransport
import com.zcode.android.core.transport.ErrorMapping
import com.zcode.android.core.transport.TransportFailure
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Phase 5 (gaps-and-polish) READ words of the shipped 3.14.5 `zcode-agent` facade that the
 * phone never called — workflow runs (A3.3), saved workflows (L6.4), plans (A3.12), the
 * rewind preview (A3.11), trajectory + session reads (A3.13). Every shape below is
 * bundle-extracted (host/index.js: `conversationWorkflowRunsV4` ≈366624 …
 * `conversationFileRewindPreviewV4` ≈368423, `listSavedWorkflows` ≈342388,
 * `getModelTrajectory` ≈443516, `readSessionMessages` ≈338628; result zods in
 * host/chunk-LNGNUNBX.js `wl`/`Rl`/`nt` and host/chunk-4RHDQOPX.js `BJ`/`FJ`/`JJ`).
 *
 * Call convention = the plane's own `v4Call` (ProxyChannel ARRAY-wrapped single object
 * on the `zcode-agent` channel, Phase-1 finding 5); the workspace-target keys ride every
 * params object exactly like `conversationFileChangesV4` does (the host's `ae(m)` resolves
 * the connection from them). INV-9: this class exposes READ words only — the one write
 * neighbour (`startSavedWorkflow`) is a v4 COMMAND and lives in CommandCoordinator.
 */
class AgentFacadeClient(private val transport: AppTransport) {

    private suspend fun call(method: String, params: JsonObject): JsonObject {
        val raw = transport.channelCall("zcode-agent", method, RpcValue.Arr(listOf(RpcValue.Obj(params))), 30_000)
        return (raw as? RpcValue.Obj)?.json as? JsonObject
            ?: throw TransportFailure(ErrorMapping.protocolFault("zcode-agent.$method result not an object"))
    }

    /** Array-shaped results (the host returns `.messages` / `.events` bare arrays). */
    private suspend fun callArray(method: String, params: JsonObject): List<JsonObject> {
        val raw = transport.channelCall("zcode-agent", method, RpcValue.Arr(listOf(RpcValue.Obj(params))), 30_000)
        val items = (raw as? RpcValue.Arr)?.items
            ?: ((raw as? RpcValue.Obj)?.json as? JsonObject)?.let { obj ->
                (obj["messages"] ?: obj["events"] ?: obj["items"]) as? JsonArray
            }?.map { RpcValue.Obj(it) }
            ?: throw TransportFailure(ErrorMapping.protocolFault("zcode-agent.$method result not an array"))
        return items.mapNotNull { (it as? RpcValue.Obj)?.json as? JsonObject }
    }

    private fun base(sessionId: String?, workspacePath: String?, workspaceIdentity: String?): JsonObject = buildJsonObject {
        val target = transport.activeWorkspaceTarget
        put("workspacePath", JsonPrimitive(workspacePath ?: target?.first ?: ""))
        (workspaceIdentity ?: target?.second)?.let { put("workspaceIdentity", JsonPrimitive(it)) }
        sessionId?.let { put("sessionId", JsonPrimitive(it)) }
    }

    private fun JsonObject.plus(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject {
        this@plus.forEach { (k, v) -> put(k, v) }
        build()
    }

    // ---------------------------------------------------------------- A3.3 workflow runs

    /** `conversationWorkflowRunsV4 {sessionId, limit? (≤64)}` → `{runs:[{runId, label?, status, …, resumable}]}`. */
    suspend fun workflowRuns(sessionId: String, limit: Int? = null, workspacePath: String? = null, workspaceIdentity: String? = null): JsonObject =
        call("conversationWorkflowRunsV4", base(sessionId, workspacePath, workspaceIdentity).plus { limit?.let { put("limit", JsonPrimitive(it)) } })

    /** `conversationWorkflowRunEventsV4 {sessionId, runId, afterSequence?, limit? (≤500)}` → `{events:[{sequence,type,payload,truncated?}], hasMore}`. */
    suspend fun workflowRunEvents(sessionId: String, runId: String, afterSequence: Long? = null, limit: Int? = null, workspacePath: String? = null, workspaceIdentity: String? = null): JsonObject =
        call("conversationWorkflowRunEventsV4", base(sessionId, workspacePath, workspaceIdentity).plus {
            put("runId", JsonPrimitive(runId))
            afterSequence?.let { put("afterSequence", JsonPrimitive(it)) }
            limit?.let { put("limit", JsonPrimitive(it)) }
        })

    /** `conversationWorkflowRunNodeResultV4 {sessionId, runId, siteId, ordinal, maxBytes?}`. */
    suspend fun workflowRunNodeResult(sessionId: String, runId: String, siteId: String, ordinal: Int, maxBytes: Int? = null, workspacePath: String? = null, workspaceIdentity: String? = null): JsonObject =
        call("conversationWorkflowRunNodeResultV4", base(sessionId, workspacePath, workspaceIdentity).plus {
            put("runId", JsonPrimitive(runId)); put("siteId", JsonPrimitive(siteId)); put("ordinal", JsonPrimitive(ordinal))
            maxBytes?.let { put("maxBytes", JsonPrimitive(it)) }
        })

    /** `conversationWorkflowRunArtifactsV4 {sessionId, runId}` → `{artifacts:[{id,kind,title?,version,versions[],itemCount,primary?}]}`. */
    suspend fun workflowRunArtifacts(sessionId: String, runId: String, workspacePath: String? = null, workspaceIdentity: String? = null): JsonObject =
        call("conversationWorkflowRunArtifactsV4", base(sessionId, workspacePath, workspaceIdentity).plus { put("runId", JsonPrimitive(runId)) })

    /** `conversationWorkflowRunArtifactReadV4 {sessionId, runId, artifactId, version, offset, limit}` → `{dataBase64, mediaType, totalBytes, nextOffset}`. */
    suspend fun workflowRunArtifactRead(sessionId: String, runId: String, artifactId: String, version: Int, offset: Long, limit: Int, workspacePath: String? = null, workspaceIdentity: String? = null): JsonObject =
        call("conversationWorkflowRunArtifactReadV4", base(sessionId, workspacePath, workspaceIdentity).plus {
            put("runId", JsonPrimitive(runId)); put("artifactId", JsonPrimitive(artifactId)); put("version", JsonPrimitive(version))
            put("offset", JsonPrimitive(offset)); put("limit", JsonPrimitive(limit))
        })

    /** `conversationWorkflowRunArtifactDataV4 {sessionId, runId, artifactId, afterSequence?, limit?}` → `{items:[{sequence,siteId,ordinal,item}], hasMore}`. */
    suspend fun workflowRunArtifactData(sessionId: String, runId: String, artifactId: String, afterSequence: Long? = null, limit: Int? = null, workspacePath: String? = null, workspaceIdentity: String? = null): JsonObject =
        call("conversationWorkflowRunArtifactDataV4", base(sessionId, workspacePath, workspaceIdentity).plus {
            put("runId", JsonPrimitive(runId)); put("artifactId", JsonPrimitive(artifactId))
            afterSequence?.let { put("afterSequence", JsonPrimitive(it)) }
            limit?.let { put("limit", JsonPrimitive(it)) }
        })

    /** `conversationWorkflowRunWorkspaceV4 {sessionId, runId}` — the run's workspace (cwd/files). */
    suspend fun workflowRunWorkspace(sessionId: String, runId: String, workspacePath: String? = null, workspaceIdentity: String? = null): JsonObject =
        call("conversationWorkflowRunWorkspaceV4", base(sessionId, workspacePath, workspaceIdentity).plus { put("runId", JsonPrimitive(runId)) })

    // ---------------------------------------------------------------- L6.4 saved workflows (workspace-level)

    /** `listSavedWorkflows {workspacePath, workspaceIdentity?, scope?}` → `{workflows:[{name,description,whenToUse?,args?,scope,path}], invalid:[{path,reason}], dir}`. */
    suspend fun listSavedWorkflows(workspacePath: String? = null, workspaceIdentity: String? = null, scope: String? = null): JsonObject =
        call("listSavedWorkflows", base(null, workspacePath, workspaceIdentity).plus { scope?.let { put("scope", JsonPrimitive(it)) } })

    /** `getSavedWorkflow {…, name, scope?}` → `{ok:true, name, path, scope, meta, script} | {ok:false, reason, detail?}`. */
    suspend fun getSavedWorkflow(name: String, scope: String? = null, workspacePath: String? = null, workspaceIdentity: String? = null): JsonObject =
        call("getSavedWorkflow", base(null, workspacePath, workspaceIdentity).plus {
            put("name", JsonPrimitive(name)); scope?.let { put("scope", JsonPrimitive(it)) }
        })

    /** `listSavedWorkflowRuns {…, name?, limit (1..50), scope?}` → `{runs:[{runId,name?,status,stopReason?,createdAt,updatedAt,spentTokens,…}], truncated?}`. */
    suspend fun listSavedWorkflowRuns(name: String? = null, limit: Int = 20, scope: String? = null, workspacePath: String? = null, workspaceIdentity: String? = null): JsonObject =
        call("listSavedWorkflowRuns", base(null, workspacePath, workspaceIdentity).plus {
            name?.let { put("name", JsonPrimitive(it)) }
            put("limit", JsonPrimitive(limit.coerceIn(1, 50)))
            scope?.let { put("scope", JsonPrimitive(it)) }
        })

    // ---------------------------------------------------------------- A3.12 plans · A3.11 rewind preview

    /** `conversationPlansV4 {sessionId}` → the session's plans projection. */
    suspend fun plans(sessionId: String, workspacePath: String? = null, workspaceIdentity: String? = null): JsonObject =
        call("conversationPlansV4", base(sessionId, workspacePath, workspaceIdentity))

    /**
     * `conversationFileRewindPreviewV4 {sessionId, target:{rowId,entityId}, baseRevision, baseLogEpoch}`
     * → `{canApply, safeFiles:[{action,path,operationCount,toolNames}], unsafeFiles:[{path,reason,…}],
     * ignoredFiles:[{path,reason:"bash_ignored",…}]}` (chunk-LNGNUNBX `nt`).
     */
    suspend fun fileRewindPreview(sessionId: String, rowId: Long, entityId: String, baseRevision: Long, baseLogEpoch: String, workspacePath: String? = null, workspaceIdentity: String? = null): JsonObject =
        call("conversationFileRewindPreviewV4", base(sessionId, workspacePath, workspaceIdentity).plus {
            put("target", buildJsonObject { put("rowId", JsonPrimitive(rowId)); put("entityId", JsonPrimitive(entityId)) })
            put("baseRevision", JsonPrimitive(baseRevision)); put("baseLogEpoch", JsonPrimitive(baseLogEpoch))
        })

    // ---------------------------------------------------------------- A3.13 trajectory + session reads

    /** `getModelTrajectory {taskId, limit?}` → `{records[], sourceFiles[], truncated}` (a zcode-task word). */
    suspend fun modelTrajectory(taskId: String, limit: Int? = null, workspacePath: String? = null, workspaceIdentity: String? = null): JsonObject {
        val params = base(null, workspacePath, workspaceIdentity).plus {
            put("taskId", JsonPrimitive(taskId)); limit?.let { put("limit", JsonPrimitive(it)) }
        }
        val raw = transport.channelCall("zcode-task", "getModelTrajectory", RpcValue.Arr(listOf(RpcValue.Obj(params))), 30_000)
        return (raw as? RpcValue.Obj)?.json as? JsonObject
            ?: throw TransportFailure(ErrorMapping.protocolFault("zcode-task.getModelTrajectory result not an object"))
    }

    /** `readSessionMessages {sessionId, afterMessageId?, limit}` → messages[] (the host unwraps `.messages`). */
    suspend fun sessionMessages(sessionId: String, afterMessageId: String? = null, limit: Int = 200, workspacePath: String? = null, workspaceIdentity: String? = null): List<JsonObject> =
        callArray("readSessionMessages", base(sessionId, workspacePath, workspaceIdentity).plus {
            afterMessageId?.let { put("afterMessageId", JsonPrimitive(it)) }
            put("limit", JsonPrimitive(limit))
        })

    /** `readSessionEvents {sessionId, afterSeq?, limit}` → events[] (the host unwraps `.events`). */
    suspend fun sessionEvents(sessionId: String, afterSeq: Long? = null, limit: Int = 200, workspacePath: String? = null, workspaceIdentity: String? = null): List<JsonObject> =
        callArray("readSessionEvents", base(sessionId, workspacePath, workspaceIdentity).plus {
            afterSeq?.let { put("afterSeq", JsonPrimitive(it)) }
            put("limit", JsonPrimitive(limit))
        })

    /** `readSessionDebug {sessionId}` — the debug projection (existing-only runtime policy host-side). */
    suspend fun sessionDebug(sessionId: String, workspacePath: String? = null, workspaceIdentity: String? = null): JsonObject =
        call("readSessionDebug", base(sessionId, workspacePath, workspaceIdentity))
}
