package com.zcode.android.domain.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A3.8–A3.10 (gaps-and-polish Phase 5A): the task-management vocabulary of the
 * shipped 3.14.5 `zcode-task` facade — custom groups with colors, the grouped
 * view structure, and the task META shape the pinned/archived lists return.
 * Shapes are bundle-extracted (host/chunk-IKTGKLU2.js `createTaskGroup` /
 * `queryGroupedTaskViewStructure` / `applyGroupedTaskViewOrder`; host/index.js
 * `listPinnedTasks` / `listArchivedTasks` → `listTaskMetas(...).map(Be)`).
 * Decoding is tolerant (INV-8): unknown colors fall back to GRAY exactly like
 * the desktop's own `rowToTaskGroup`, unknown node types are dropped.
 */

/** `isTaskGroupColor` — the desktop's closed set; GRAY is the default (`Ct`). */
enum class TaskGroupColor(val wire: String) {
    GRAY("gray"), RED("red"), ORANGE("orange"), YELLOW("yellow"), GREEN("green"), BLUE("blue"), PURPLE("purple");

    companion object {
        fun fromWire(value: String?): TaskGroupColor = entries.firstOrNull { it.wire == value } ?: GRAY

        /** The pickable palette (every color but the default is a choice too). */
        val PALETTE: List<TaskGroupColor> = entries
    }
}

/** `rowToTaskGroup`: `{id, title, color, createdAt, updatedAt}`. */
data class TaskGroupInfo(
    val id: String,
    val title: String,
    val color: TaskGroupColor,
    val createdAtMs: Long = 0,
    val updatedAtMs: Long = 0,
) {
    companion object {
        fun decode(obj: JsonObject): TaskGroupInfo? {
            val id = obj.tmStr("id") ?: obj.tmStr("groupId") ?: return null
            return TaskGroupInfo(
                id = id,
                title = obj.tmStr("title")?.takeIf { it.isNotBlank() } ?: "New Group",
                color = TaskGroupColor.fromWire(obj.tmStr("color")),
                createdAtMs = obj.tmLong("createdAt") ?: 0,
                updatedAtMs = obj.tmLong("updatedAt") ?: 0,
            )
        }
    }
}

/**
 * One top-level node of `listGroupedTaskViewStructure` → `{nodes:[…]}`:
 * `{type:"group", group, tasks:[meta…]}` or `{type:"task", task: meta}`.
 */
sealed class GroupedTaskNode {
    data class Group(val group: TaskGroupInfo, val taskIds: List<String>) : GroupedTaskNode()
    data class Single(val taskId: String) : GroupedTaskNode()

    companion object {
        fun decodeAll(obj: JsonObject): List<GroupedTaskNode> =
            (obj["nodes"] as? JsonArray)?.mapNotNull { node ->
                val n = node as? JsonObject ?: return@mapNotNull null
                when (n.tmStr("type")) {
                    "group" -> {
                        val group = (n["group"] as? JsonObject)?.let(TaskGroupInfo::decode) ?: return@mapNotNull null
                        Group(
                            group,
                            (n["tasks"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.tmStr("taskId") } ?: emptyList(),
                        )
                    }
                    "task" -> (n["task"] as? JsonObject)?.tmStr("taskId")?.let(::Single)
                    else -> null
                }
            } ?: emptyList()
    }
}

/**
 * The task META item of the pinned/archived lists (tolerant: the desktop's
 * `ZCodeTaskMeta` carries more; only the display fields are read).
 */
data class TaskMeta(
    val taskId: String,
    val title: String,
    val statusWire: String?,
    val archived: Boolean,
    val pinned: Boolean,
    val unreadAtMs: Long?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val workspacePath: String?,
    val workspaceIdentity: String?,
    val workspaceLabel: String?,
) {
    companion object {
        fun decode(obj: JsonObject): TaskMeta? {
            val taskId = obj.tmStr("taskId") ?: return null
            val archivedAt = obj.tmLong("archivedAt")
            return TaskMeta(
                taskId = taskId,
                title = obj.tmStr("title")?.takeIf { it.isNotBlank() } ?: taskId,
                statusWire = obj.tmStr("displayStatus") ?: obj.tmStr("status"),
                archived = obj.tmBool("archived") ?: (archivedAt != null),
                pinned = obj.tmBool("pinned") ?: false,
                unreadAtMs = obj.tmLong("unreadAt"),
                createdAtMs = obj.tmLong("createdAt") ?: 0,
                updatedAtMs = obj.tmLong("updatedAt") ?: 0,
                workspacePath = obj.tmStr("workspacePath"),
                workspaceIdentity = obj.tmStr("workspaceIdentity"),
                workspaceLabel = obj.tmStr("workspaceLabel"),
            )
        }

        fun decodeAll(arr: JsonArray?): List<TaskMeta> = arr?.mapNotNull { (it as? JsonObject)?.let(::decode) } ?: emptyList()
    }
}

private fun JsonObject.tmPrim(key: String): JsonPrimitive? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }
private fun JsonObject.tmStr(key: String): String? = tmPrim(key)?.content?.takeIf { it.isNotBlank() }
private fun JsonObject.tmLong(key: String): Long? = tmPrim(key)?.content?.toDoubleOrNull()?.toLong()
private fun JsonObject.tmBool(key: String): Boolean? = tmPrim(key)?.content?.toBooleanStrictOrNull()
