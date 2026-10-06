package com.zcode.android.app.connect

import kotlinx.serialization.json.JsonObject

/**
 * Tolerant decoder for the relay control-plane task/workspace payloads
 * (§9.5 Q9-verified shape; production fields confirmed by the sanitized live
 * corpus `fixtures/relay/live-subscribe-16.fixture.json`).
 *
 * Tolerance rules (INV-8): unknown fields are ignored, missing optional fields
 * fall back to safe defaults, and BOTH observed shapes decode:
 *  - production bootstrap-response tasks: {taskId, title, displayStatus, createdAt,
 *    updatedAt, workspacePath, workspaceLabel, workspaceKind, provider?, unreadAt?}
 *  - MockZCode/test tasks: {id, title, status} (simplified test shape)
 * A malformed task entry is DROPPED (never crashes, never fabricates a task) and
 * counted — a task list that cannot be parsed is empty + reported, not invented.
 */
object BootstrapParser {

    data class ParsedWorkspace(
        val identityKey: String,
        val label: String,
        val path: String?,
        val kind: String?,
    )

    data class ParsedTask(
        val taskId: String,
        val title: String,
        val statusWire: String?,
        val workspaceIdentityKey: String,
        val workspaceLabel: String?,
        val workspacePath: String?,
        val createdAtMs: Long,
        val updatedAtMs: Long,
        /** Raw desktop-reported workspace identity ("" when absent — diagnostics). */
        val workspaceIdentityRaw: String = "",
    )

    data class Result(
        val workspaces: List<ParsedWorkspace>,
        val tasks: List<ParsedTask>,
        val droppedEntries: Int,
        val desktopAppVersion: String?,
    )

    /**
     * @param payload the FULL control-plane payload envelope ({zcode_type, result});
     * the `result` object is located tolerantly (payload.result / payload itself).
     */
    fun parseBootstrap(payload: JsonObject?): Result {
        val result = payload?.get("result") as? JsonObject ?: payload ?: JsonObject(emptyMap())
        return parse(result)
    }

    /** workspace-list-response result: {workspaces[], tasks?, activeWorkspaceKey?}. */
    fun parseWorkspaceList(payload: JsonObject?): Result {
        val result = payload?.get("result") as? JsonObject ?: payload ?: JsonObject(emptyMap())
        return parse(result)
    }

    private fun parse(result: JsonObject): Result {
        var dropped = 0
        val workspaces = ArrayList<ParsedWorkspace>()
        (result["workspaces"] as? kotlinx.serialization.json.JsonArray)?.forEach { entry ->
            val obj = entry as? JsonObject
            if (obj == null) {
                dropped++
                return@forEach
            }
            val path = obj.str("workspacePath")
            val identity = obj.strOrNull("workspaceIdentity")
            val key = identity?.trim()?.takeIf { it.isNotEmpty() } ?: path
            if (key.isNullOrBlank()) {
                dropped++
                return@forEach
            }
            workspaces.add(
                ParsedWorkspace(
                    identityKey = key,
                    label = obj.strOrNull("label") ?: path ?: key,
                    path = path,
                    kind = obj.strOrNull("kind"),
                ),
            )
        }
        val tasks = ArrayList<ParsedTask>()
        (result["tasks"] as? kotlinx.serialization.json.JsonArray)?.forEach { entry ->
            val obj = entry as? JsonObject
            if (obj == null) {
                dropped++
                return@forEach
            }
            val id = obj.strOrNull("taskId") ?: obj.strOrNull("id")
            if (id.isNullOrBlank()) {
                dropped++
                return@forEach
            }
            val path = obj.strOrNull("workspacePath")
            val identity = obj.strOrNull("workspaceIdentity")
            val key = identity?.trim()?.takeIf { it.isNotEmpty() } ?: path ?: ""
            tasks.add(
                ParsedTask(
                    taskId = id,
                    title = obj.strOrNull("title")?.takeIf { it.isNotBlank() } ?: id,
                    statusWire = obj.strOrNull("displayStatus") ?: obj.strOrNull("status"),
                    workspaceIdentityKey = key,
                    workspaceLabel = obj.strOrNull("workspaceLabel"),
                    workspacePath = path,
                    createdAtMs = obj.longOrZero("createdAt"),
                    updatedAtMs = obj.longOrZero("updatedAt"),
                    workspaceIdentityRaw = identity ?: "",
                ),
            )
        }
        return Result(
            workspaces = workspaces,
            tasks = tasks,
            droppedEntries = dropped,
            desktopAppVersion = result.strOrNull("desktopAppVersion"),
        )
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content

    private fun JsonObject.strOrNull(key: String): String? = str(key)?.takeIf { it.isNotBlank() }

    private fun JsonObject.longOrZero(key: String): Long = runCatching {
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content?.toLong()
    }.getOrNull() ?: 0L
}
