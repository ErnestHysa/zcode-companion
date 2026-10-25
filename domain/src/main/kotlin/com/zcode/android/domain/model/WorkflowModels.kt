package com.zcode.android.domain.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A3.3 + L6.4 (gaps-and-polish Phase 5A): the workflow vocabulary of the shipped 3.14.5
 * desktop — the per-session run list (`conversationWorkflowRunsV4` → chunk-LNGNUNBX `wl`
 * / `Br`), the run event stream (`Rl`), and the saved-workflow hub (`listSavedWorkflows`
 * → chunk-4RHDQOPX `BJ`/`nz`, `listSavedWorkflowRuns` → `JJ`/`cz`). Tolerant decode
 * (INV-8): unknown statuses are kept RAW on the model (`statusRaw`) and classified
 * UNKNOWN, never dropped.
 */

/** `Br.status` / `cz.status` / `az`: pending | running | completed | errored | stopped. */
enum class WorkflowRunStatus(val wire: String) {
    PENDING("pending"), RUNNING("running"), COMPLETED("completed"), ERRORED("errored"), STOPPED("stopped"), UNKNOWN("");

    val isLive: Boolean get() = this == PENDING || this == RUNNING

    companion object {
        fun fromWire(value: String?): WorkflowRunStatus = entries.firstOrNull { it.wire == value && it != UNKNOWN } ?: UNKNOWN
    }
}

/** One run of the session's run list (`Br`). `label` is the desktop's display name. */
data class WorkflowRunSummary(
    val runId: String,
    val label: String?,
    val status: WorkflowRunStatus,
    val statusRaw: String?,
    val stopReason: String?,
    val toolCallId: String?,
    val updatedAtMs: Long?,
    val resumedFrom: String?,
    val supersededBy: String?,
    val failureCode: String?,
    val failureMessage: String?,
    val resumable: Boolean,
) {
    companion object {
        fun decode(obj: JsonObject): WorkflowRunSummary? {
            val runId = obj.wfStr("runId") ?: return null
            val raw = obj.wfStr("status")
            return WorkflowRunSummary(
                runId = runId,
                label = obj.wfStr("label"),
                status = WorkflowRunStatus.fromWire(raw),
                statusRaw = raw,
                stopReason = obj.wfStr("stopReason"),
                toolCallId = obj.wfStr("toolCallId"),
                updatedAtMs = obj.wfLong("updatedAt"),
                resumedFrom = obj.wfStr("resumedFrom"),
                supersededBy = obj.wfStr("supersededBy"),
                failureCode = obj.wfStr("failureCode"),
                failureMessage = obj.wfStr("failureMessage"),
                resumable = obj.wfBool("resumable") ?: false,
            )
        }

        /** `{runs:[…]}` — tolerant of a bare array too. */
        fun decodeList(result: JsonElement?): List<WorkflowRunSummary> {
            val arr = (result as? JsonArray) ?: ((result as? JsonObject)?.get("runs") as? JsonArray) ?: return emptyList()
            return arr.mapNotNull { (it as? JsonObject)?.let(::decode) }
        }
    }
}

/** One event of `conversationWorkflowRunEventsV4` (`Rl.events[]`); `payload` is kept raw. */
data class WorkflowRunEvent(
    val sequence: Long,
    val type: String,
    val payload: JsonObject,
    val truncated: Boolean,
) {
    /** A short human line for the timeline: the payload's `message`/`summary`/`name`/`label`, else the type. */
    val headline: String
        get() = payload.wfStr("message") ?: payload.wfStr("summary") ?: payload.wfStr("name") ?: payload.wfStr("label") ?: type

    companion object {
        fun decode(obj: JsonObject): WorkflowRunEvent? {
            val seq = obj.wfLong("sequence") ?: return null
            val type = obj.wfStr("type") ?: return null
            return WorkflowRunEvent(
                sequence = seq,
                type = type,
                payload = obj["payload"] as? JsonObject ?: JsonObject(emptyMap()),
                truncated = obj.wfBool("truncated") ?: false,
            )
        }
    }
}

/** `{events:[…], hasMore}` page. */
data class WorkflowRunEventsPage(val events: List<WorkflowRunEvent>, val hasMore: Boolean) {
    companion object {
        fun decode(obj: JsonObject): WorkflowRunEventsPage = WorkflowRunEventsPage(
            events = (obj["events"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(WorkflowRunEvent::decode) } ?: emptyList(),
            hasMore = obj.wfBool("hasMore") ?: false,
        )
    }
}

/** A saved workflow's declared argument (`tz`): type string|number|boolean|json, required?, default?. */
data class SavedWorkflowArg(
    val name: String,
    val type: String,
    val description: String?,
    val required: Boolean,
    val default: JsonElement?,
)

/** `listSavedWorkflows` item (`nz`): the hub row. */
data class SavedWorkflow(
    val name: String,
    val description: String,
    val whenToUse: String?,
    val scope: String,
    val path: String,
    val args: List<SavedWorkflowArg>,
) {
    val requiredArgs: List<SavedWorkflowArg> get() = args.filter { it.required }

    companion object {
        fun decode(obj: JsonObject): SavedWorkflow? {
            val name = obj.wfStr("name") ?: return null
            val args = (obj["args"] as? JsonObject)?.mapNotNull { (argName, spec) ->
                val s = spec as? JsonObject ?: return@mapNotNull null
                SavedWorkflowArg(
                    name = argName,
                    type = s.wfStr("type") ?: "string",
                    description = s.wfStr("description"),
                    required = s.wfBool("required") ?: false,
                    default = s["default"]?.takeIf { it !is JsonNull },
                )
            } ?: (obj["argNames"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
                ?.map { SavedWorkflowArg(it, "string", null, required = false, default = null) }
                ?: emptyList()
            return SavedWorkflow(
                name = name,
                description = obj.wfStr("description") ?: "",
                whenToUse = obj.wfStr("whenToUse"),
                scope = obj.wfStr("scope") ?: "project",
                path = obj.wfStr("path") ?: "",
                args = args,
            )
        }
    }
}

/** `listSavedWorkflows` result (`BJ`): workflows + the files that failed to parse. */
data class SavedWorkflowList(
    val workflows: List<SavedWorkflow>,
    val invalid: List<Pair<String, String?>>,
    val dir: String?,
) {
    companion object {
        fun decode(obj: JsonObject): SavedWorkflowList = SavedWorkflowList(
            workflows = (obj["workflows"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(SavedWorkflow::decode) } ?: emptyList(),
            invalid = (obj["invalid"] as? JsonArray)?.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                (o.wfStr("path") ?: return@mapNotNull null) to o.wfStr("reason")
            } ?: emptyList(),
            dir = obj.wfStr("dir"),
        )
    }
}

/** `listSavedWorkflowRuns` item (`cz`) — the hub's last-run state per workflow. */
data class SavedWorkflowRun(
    val runId: String,
    val name: String?,
    val status: WorkflowRunStatus,
    val statusRaw: String?,
    val stopReason: String?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val spentTokens: Long,
    val parentSessionId: String?,
    val cwd: String?,
) {
    companion object {
        fun decode(obj: JsonObject): SavedWorkflowRun? {
            val runId = obj.wfStr("runId") ?: return null
            val raw = obj.wfStr("status")
            return SavedWorkflowRun(
                runId = runId,
                name = obj.wfStr("name"),
                status = WorkflowRunStatus.fromWire(raw),
                statusRaw = raw,
                stopReason = obj.wfStr("stopReason"),
                createdAtMs = obj.wfLong("createdAt") ?: 0,
                updatedAtMs = obj.wfLong("updatedAt") ?: 0,
                spentTokens = obj.wfLong("spentTokens") ?: 0,
                parentSessionId = obj.wfStr("parentSessionId"),
                cwd = obj.wfStr("cwd"),
            )
        }

        fun decodeList(obj: JsonObject): List<SavedWorkflowRun> =
            (obj["runs"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::decode) } ?: emptyList()
    }
}

/** `startSavedWorkflow` ack error codes (chunk-LNGNUNBX `Bu`). */
object StartSavedWorkflowErrors {
    const val INVALID_NAME = "invalid_name"
    const val NOT_FOUND = "not_found"
    const val INVALID_ARGS = "invalid_args"
    const val COMPILE_FAILED = "compile_failed"
    const val SESSION_BUSY = "session_busy"
    const val START_FAILED = "start_failed"
    val ALL = setOf(INVALID_NAME, NOT_FOUND, INVALID_ARGS, COMPILE_FAILED, SESSION_BUSY, START_FAILED)
}

private fun JsonObject.wfPrim(key: String): JsonPrimitive? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }
internal fun JsonObject.wfStr(key: String): String? = wfPrim(key)?.content?.takeIf { it.isNotBlank() }
internal fun JsonObject.wfLong(key: String): Long? = wfPrim(key)?.content?.toDoubleOrNull()?.toLong()
internal fun JsonObject.wfBool(key: String): Boolean? = wfPrim(key)?.content?.toBooleanStrictOrNull()
