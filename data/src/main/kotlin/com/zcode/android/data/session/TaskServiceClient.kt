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

    suspend fun listArchivedTasks(workspacePath: String, workspaceIdentity: String?): List<JsonObject> =
        taskListCall("listArchivedTasks", target(workspacePath, workspaceIdentity))

    /** Array-shaped task-list words (`listTaskMetas(...).map(Be)` on the host). */
    private suspend fun taskListCall(method: String, params: JsonObject): List<JsonObject> {
        val raw = transport.channelCall("zcode-task", method, RpcValue.Arr(listOf(RpcValue.Obj(params))), 30_000)
        val arr = (raw as? RpcValue.Arr)?.items
            ?: ((raw as? RpcValue.Obj)?.json as? JsonObject)?.get("tasks") as? kotlinx.serialization.json.JsonArray
            ?: throw TransportFailure(ErrorMapping.protocolFault("$method result not an array"))
        return arr.mapNotNull { (it as? RpcValue.Obj)?.json as? JsonObject ?: it as? JsonObject }
    }

    // ---------------------------------------------------------------- Phase 5A (A3.8–A3.10)

    /**
     * `setTaskPinned {workspacePath, workspaceIdentity?, taskId, pinned}` (host/index.js
     * ≈446555: persists the meta flag and emits task_pinned / task_unpinned). The echo
     * (the updated meta) is returned; the phone re-reads the projection anyway.
     */
    suspend fun setTaskPinned(workspacePath: String, workspaceIdentity: String?, taskId: String, pinned: Boolean): JsonObject =
        taskCall("setTaskPinned", buildJsonObject {
            target(workspacePath, workspaceIdentity).forEach { (k, v) -> put(k, v) }
            put("taskId", JsonPrimitive(taskId))
            put("pinned", JsonPrimitive(pinned))
        })

    /**
     * `setTaskUnread {…, taskId, unread, expectedUnreadAt?}` (≈446695): unread=true stamps
     * `unreadAt = now`; unread=false clears it (with the CAS-style `expectedUnreadAt` the
     * desktop's mark-read path sends so a newer unread marker is never clobbered).
     */
    suspend fun setTaskUnread(
        workspacePath: String,
        workspaceIdentity: String?,
        taskId: String,
        unread: Boolean,
        expectedUnreadAt: Long? = null,
    ): JsonObject = taskCall("setTaskUnread", buildJsonObject {
        target(workspacePath, workspaceIdentity).forEach { (k, v) -> put(k, v) }
        put("taskId", JsonPrimitive(taskId))
        put("unread", JsonPrimitive(unread))
        if (!unread && expectedUnreadAt != null) put("expectedUnreadAt", JsonPrimitive(expectedUnreadAt))
    })

    /**
     * `resumeTask {taskId, workspacePath, workspaceIdentity?, model?, thoughtLevel?}`
     * (≈434126): re-opens a completed/idle task's session (the desktop's "continue"
     * for finished tasks). Model/thought omitted → the host resolves them itself.
     */
    suspend fun resumeTask(workspacePath: String, workspaceIdentity: String?, taskId: String): JsonObject =
        taskCall("resumeTask", buildJsonObject {
            target(workspacePath, workspaceIdentity).forEach { (k, v) -> put(k, v) }
            put("taskId", JsonPrimitive(taskId))
        })

    suspend fun listPinnedTasks(workspacePath: String, workspaceIdentity: String?): List<JsonObject> =
        taskListCall("listPinnedTasks", target(workspacePath, workspaceIdentity))

    /** `deleteArchivedTask {…, taskId}` — the archived viewer's permanent delete. */
    suspend fun deleteArchivedTask(workspacePath: String, workspaceIdentity: String?, taskId: String): JsonObject =
        taskCall("deleteArchivedTask", buildJsonObject {
            target(workspacePath, workspaceIdentity).forEach { (k, v) -> put(k, v) }
            put("taskId", JsonPrimitive(taskId))
        })

    // ---- custom groups (host/chunk-IKTGKLU2.js task_groups): title + closed color set

    private fun scopes(workspacePath: String, workspaceIdentity: String?): kotlinx.serialization.json.JsonArray =
        kotlinx.serialization.json.JsonArray(listOf(target(workspacePath, workspaceIdentity)))

    /** `createTaskGroup {title?, color?, workspaceScopes}` → `{id, title, color, createdAt, updatedAt}`. */
    suspend fun createTaskGroup(workspacePath: String, workspaceIdentity: String?, title: String, color: String): JsonObject =
        taskCall("createTaskGroup", buildJsonObject {
            put("title", JsonPrimitive(title))
            put("color", JsonPrimitive(color))
            put("workspaceScopes", scopes(workspacePath, workspaceIdentity))
        })

    suspend fun renameTaskGroup(workspacePath: String, workspaceIdentity: String?, groupId: String, title: String): JsonObject =
        taskCall("renameTaskGroup", buildJsonObject {
            put("groupId", JsonPrimitive(groupId))
            put("title", JsonPrimitive(title))
            put("workspaceScopes", scopes(workspacePath, workspaceIdentity))
        })

    suspend fun updateTaskGroupColor(workspacePath: String, workspaceIdentity: String?, groupId: String, color: String): JsonObject =
        taskCall("updateTaskGroupColor", buildJsonObject {
            put("groupId", JsonPrimitive(groupId))
            put("color", JsonPrimitive(color))
            put("workspaceScopes", scopes(workspacePath, workspaceIdentity))
        })

    /** `deleteTaskGroup {groupId, workspaceScopes}` → Promise<void>; members fall back to top level. */
    suspend fun deleteTaskGroup(workspacePath: String, workspaceIdentity: String?, groupId: String) {
        transport.channelCall(
            "zcode-task", "deleteTaskGroup",
            RpcValue.Arr(listOf(RpcValue.Obj(buildJsonObject {
                put("groupId", JsonPrimitive(groupId))
                put("workspaceScopes", scopes(workspacePath, workspaceIdentity))
            }))),
            30_000,
        )
    }

    /** `listGroupedTaskViewStructure {workspaceScopes}` → `{nodes:[{type:"group",group,tasks}|{type:"task",task}]}`. */
    suspend fun listGroupedTaskViewStructure(workspacePath: String, workspaceIdentity: String?): JsonObject =
        taskCall("listGroupedTaskViewStructure", buildJsonObject {
            put("workspaceScopes", scopes(workspacePath, workspaceIdentity))
        })

    /**
     * `applyGroupedTaskViewOrder {workspaceScopes, topLevelNodes:[{type:"group",groupId}|
     * {type:"task", task:{workspacePath, workspaceIdentity?, taskId}}], groups:[{groupId,
     * taskRefs:[taskRef…]}]}` — the ONE write that moves tasks between groups; the host
     * refuses pinned/archived/deleted members ("Grouped task order 包含不可见 task").
     */
    suspend fun applyGroupedTaskViewOrder(
        workspacePath: String,
        workspaceIdentity: String?,
        topLevel: List<GroupedOrderNode>,
        groups: Map<String, List<String>>,
    ): JsonObject {
        fun ref(taskId: String) = buildJsonObject {
            target(workspacePath, workspaceIdentity).forEach { (k, v) -> put(k, v) }
            put("taskId", JsonPrimitive(taskId))
        }
        return taskCall("applyGroupedTaskViewOrder", buildJsonObject {
            put("workspaceScopes", scopes(workspacePath, workspaceIdentity))
            put("topLevelNodes", kotlinx.serialization.json.JsonArray(topLevel.map { node ->
                when (node) {
                    is GroupedOrderNode.Group -> buildJsonObject { put("type", JsonPrimitive("group")); put("groupId", JsonPrimitive(node.groupId)) }
                    is GroupedOrderNode.Task -> buildJsonObject { put("type", JsonPrimitive("task")); put("task", ref(node.taskId)) }
                }
            }))
            put("groups", kotlinx.serialization.json.JsonArray(groups.map { (groupId, taskIds) ->
                buildJsonObject {
                    put("groupId", JsonPrimitive(groupId))
                    put("taskRefs", kotlinx.serialization.json.JsonArray(taskIds.map(::ref)))
                }
            }))
        })
    }

    sealed class GroupedOrderNode {
        data class Group(val groupId: String) : GroupedOrderNode()
        data class Task(val taskId: String) : GroupedOrderNode()
    }

    // ---- off-peak pause / continue (the `off-peak-task` channel; A3.10 erratum)

    /**
     * A3.10 as shipped: `pauseTask` / `continueTask` are methods of the host's
     * `OffPeakTaskService` (host/index.js ≈1204961: `pauseTask(t){repo.setPaused(t,true)}`
     * / `continueTask(t){repo.setPaused(t,false) … batchStatus(ticket)}`), served on the
     * `off-peak-task` channel the phone already reads with `list`. They take the off-peak
     * task id as the bare positional arg (ProxyChannel array-wrapped). They are NOT
     * `zcode-task` words — the finding's attribution is recorded as an erratum.
     */
    suspend fun offPeakPause(offPeakTaskId: String): JsonObject? = offPeakCall("pauseTask", offPeakTaskId)

    suspend fun offPeakContinue(offPeakTaskId: String): JsonObject? = offPeakCall("continueTask", offPeakTaskId)

    private suspend fun offPeakCall(method: String, offPeakTaskId: String): JsonObject? {
        val raw = transport.channelCall("off-peak-task", method, RpcValue.Arr(listOf(RpcValue.Str(offPeakTaskId))), 30_000)
        return (raw as? RpcValue.Obj)?.json as? JsonObject
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
