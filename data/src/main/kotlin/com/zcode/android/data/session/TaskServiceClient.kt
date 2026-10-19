package com.zcode.android.data.session

import com.zcode.android.core.protocol.bytes.RpcSerialization.RpcValue
import com.zcode.android.core.transport.AppTransport
import com.zcode.android.core.transport.ErrorMapping
import com.zcode.android.core.transport.TransportFailure
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * L6.3+J4 + H1.1 (gaps-and-polish Phase 4D): the task lifecycle + search plane on
 * the `zcode-task` service channel (the shipped 3.14.5 facade: deleteTask/renameTask/
 * archiveTask/unarchiveTask/listArchivedTasks at bundle offsets ~436469–447279 — the
 * SAME object literal as the verified `listTasks`; and `listTaskList({kind,
 * workspaceScopes, sortBy, search?, limit?})` → {items(with searchSnippet), total,
 * hasMore}, zcodeTaskListTypes.ts). All calls take the workspace-target form.
 */
class TaskServiceClient(private val transport: AppTransport) {

    private suspend fun taskCall(method: String, params: JsonObject): JsonObject {
        val raw = transport.channelCall(
            "zcode-task", method,
            RpcValue.Arr(listOf(RpcValue.Obj(params))),
            30_000,
        )
        return (raw as? RpcValue.Obj)?.json as? JsonObject
            ?: throw TransportFailure(ErrorMapping.protocolFault("zcode-task.$method result not an object"))
    }

    private fun target(workspacePath: String, workspaceIdentity: String?): JsonObject = buildJsonObject {
        put("workspacePath", JsonPrimitive(workspacePath))
        workspaceIdentity?.let { put("workspaceIdentity", JsonPrimitive(it)) }
    }

    /** Desktop confirmDialog.taskDelete*: "records cannot be recovered" — a typed confirm precedes this. */
    suspend fun deleteTask(workspacePath: String, workspaceIdentity: String?, taskId: String): JsonObject =
        taskCall("deleteTask", buildJsonObject {
            target(workspacePath, workspaceIdentity).forEach { (k, v) -> put(k, v) }
            put("taskId", JsonPrimitive(taskId))
        })

    suspend fun renameTask(workspacePath: String, workspaceIdentity: String?, taskId: String, title: String): JsonObject =
        taskCall("renameTask", buildJsonObject {
            target(workspacePath, workspaceIdentity).forEach { (k, v) -> put(k, v) }
            put("taskId", JsonPrimitive(taskId))
            put("title", JsonPrimitive(title))
        })

    /** archived=false is the unarchive path (desktop archiveTask/unarchiveTask are one flag). */
    suspend fun archiveTask(workspacePath: String, workspaceIdentity: String?, taskId: String, archived: Boolean): JsonObject =
        taskCall(if (archived) "archiveTask" else "unarchiveTask", buildJsonObject {
            target(workspacePath, workspaceIdentity).forEach { (k, v) -> put(k, v) }
            put("taskId", JsonPrimitive(taskId))
        })

    suspend fun listArchivedTasks(workspacePath: String, workspaceIdentity: String?): List<JsonObject> {
        val raw = transport.channelCall(
            "zcode-task", "listArchivedTasks",
            RpcValue.Arr(listOf(RpcValue.Obj(target(workspacePath, workspaceIdentity)))),
            30_000,
        )
        val arr = (raw as? RpcValue.Arr)?.items
            ?: ((raw as? RpcValue.Obj)?.json as? JsonObject)?.get("tasks") as? kotlinx.serialization.json.JsonArray
            ?: throw TransportFailure(ErrorMapping.protocolFault("listArchivedTasks result not an array"))
        return arr.mapNotNull { (it as? RpcValue.Obj)?.json as? JsonObject }
    }

    data class SearchResult(val items: List<Hit>, val total: Int, val hasMore: Boolean)

    data class Hit(val taskId: String, val title: String, val snippets: List<String>)

    /** H1.1: the desktop's full-text task search (searchable_text/snippets on the index). */
    suspend fun searchTasks(workspacePath: String, workspaceIdentity: String?, query: String, limit: Int = 20): SearchResult {
        val params = buildJsonObject {
            put("kind", JsonPrimitive("active"))
            put("workspaceScopes", kotlinx.serialization.json.JsonArray(listOf(target(workspacePath, workspaceIdentity))))
            put("sortBy", JsonPrimitive("updated"))
            put("search", JsonPrimitive(query))
            put("limit", JsonPrimitive(limit))
        }
        val obj = taskCall("listTaskList", params)
        val items = (obj["items"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.mapNotNull { o ->
                val taskId = (o["taskId"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                Hit(
                    taskId = taskId,
                    title = (o["title"] as? JsonPrimitive)?.content ?: "",
                    snippets = (o["searchSnippets"] as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.content }
                        ?: (o["searchSnippet"] as? JsonPrimitive)?.content?.let { listOf(it) }
                        ?: emptyList(),
                )
            }
            ?: emptyList()
        return SearchResult(
            items = items,
            total = (obj["total"] as? JsonPrimitive)?.content?.toIntOrNull() ?: items.size,
            hasMore = (obj["hasMore"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false,
        )
    }
}
