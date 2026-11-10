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
    /** G17 (vc51 Phase 4D): the run's generated artifacts (INV-8-tolerant; often absent). */
    val artifacts: List<WorkflowArtifact> = emptyList(),
) {
    /** G17: one generated artifact — the preview card + open handoff needs ref/name/mime. */
    data class WorkflowArtifact(val ref: String, val name: String, val mime: String?) {
        companion object {
            fun decode(o: JsonObject): WorkflowArtifact? {
                val ref = o.wfStr("ref") ?: return null
                return WorkflowArtifact(
                    ref = ref,
                    name = o.wfStr("name") ?: ref,
                    mime = o.wfStr("mime") ?: o.wfStr("mediaType"),
                )
            }
        }
    }

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
                artifacts = (obj["artifacts"] as? JsonArray)?.mapNotNull { el ->
                    (el as? JsonObject)?.let(WorkflowArtifact.Companion::decode)
                } ?: emptyList(),
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

// ---------------------------------------------------------------- G51 (vc52 Phase 11): the delta plane

/**
 * G51 (vc52 Phase 11) — one workflow-run delta op of the phone-declared
 * `workflowRunDeltas` capability (the host's `clientSupportsWorkflowRunDeltas`
 * gate, byte-verified on the shipped asar `12a2fc93…`:
 * `workflowRun.updated {op, runId, revision, run?, cleared?, removedActors?,
 * removedNodes?, actors?, nodes?}` / `workflowRun.removed {op, runId, revision}`).
 * `run` is the desktop's run-patch (partial of the run schema minus actors/nodes);
 * `cleared` names patch keys to CLEAR (the run's own "un-set" vocabulary);
 * `removedActors`/`removedNodes` are {siteId, ordinal} keys of steps to drop;
 * `actors`/`nodes` are full step upserts. INV-8 tolerance: unknown fields ride
 * `raw`; a step that fails decode is SKIPPED (never drops the whole op).
 */
sealed class WorkflowRunDelta {
    abstract val runId: String
    abstract val revision: Long?

    /** `{siteId, ordinal}` — the keyed step identity (the desktop's `k1` pick). */
    data class RunSite(val siteId: String, val ordinal: Long)

    data class Updated(
        override val runId: String,
        override val revision: Long?,
        /** The run-patch object as received (raw — typed faces are read on demand). */
        val run: JsonObject?,
        /** Patch keys the desktop explicitly cleared (defaulted fields re-unset). */
        val cleared: List<String>,
        val removedActors: List<RunSite>,
        val removedNodes: List<RunSite>,
        val actors: List<WorkflowRunStep>,
        val nodes: List<WorkflowRunStep>,
        val raw: JsonObject,
    ) : WorkflowRunDelta()

    data class Removed(
        override val runId: String,
        override val revision: Long?,
        val raw: JsonObject,
    ) : WorkflowRunDelta()
}

/**
 * One actor/node step of a run's step graph. The desktop's schema evolved between
 * the pass-26 census (`99e280fb`: kind world-read|world-run + op/args + status
 * running|completed|failed + summary{resultBytes,…} + createdAt/updatedAt) and the
 * current ship (`12a2fc93`: actor = {siteId, ordinal, name?, sessionId?, status
 * waiting|running|completed, phaseName?, model?}; node = {siteId, ordinal, kind?
 * ask|world-read, phase? queued|dispatched|executing|waiting|repairing|nudged|
 * settled, outcome? ok|failed|cancelled, cached?, actorSiteId?, actorOrdinal?,
 * phaseName?, instructionsHead?, turn?, toolCalls?, lastTool? {name, target?}}).
 * The phone types BOTH shapes tolerantly (INV-8 — old-desktop rows stay decodable)
 * and keeps every field raw-verifiable on [raw].
 */
data class WorkflowRunStep(
    val siteId: String,
    val ordinal: Long,
    /** world-read | world-run | ask (raw — the vocabulary evolved). */
    val kind: String?,
    val op: String?,
    val args: List<JsonElement>,
    /** running | completed | failed | waiting (raw). */
    val status: String?,
    /** queued | dispatched | executing | waiting | repairing | nudged | settled (raw). */
    val phase: String?,
    /** ok | failed | cancelled (raw). */
    val outcome: String?,
    val cached: Boolean?,
    val actorSiteId: String?,
    val actorOrdinal: Long?,
    val phaseName: String?,
    val name: String?,
    val sessionId: String?,
    val model: String?,
    val instructionsHead: String?,
    val turn: Long?,
    val toolCalls: Long?,
    val lastToolName: String?,
    val lastToolTarget: String?,
    /** Old-desktop step summary faces (resultBytes/exitCode/stdoutBytes/…). */
    val resultBytes: Long?,
    val resultCount: Long?,
    val exitCode: Long?,
    val stdoutBytes: Long?,
    val stderrBytes: Long?,
    val errorText: String?,
    val createdAtMs: Long?,
    val updatedAtMs: Long?,
    val raw: JsonObject,
) {
    val siteKey: String get() = "$siteId#$ordinal"

    /** The one-line face the sheet renders: op/name/instructionsHead/lastTool, else the kind. */
    val headline: String
        get() = op ?: name ?: lastToolName?.let { "$it" + (lastToolTarget?.let { t -> " → $t" } ?: "") }
            ?: instructionsHead?.take(60) ?: kind ?: phase ?: "step"

    /** The status face: status, else phase, else outcome — raw string (INV-8). */
    val stateLabel: String?
        get() = status ?: phase ?: outcome ?: null

    companion object {
        fun decode(obj: JsonObject): WorkflowRunStep? {
            val siteId = obj.wfStr("siteId") ?: return null
            val ordinal = obj.wfLong("ordinal") ?: return null
            val lastTool = obj["lastTool"] as? JsonObject
            val summary = obj["summary"] as? JsonObject
            val error = obj["error"] as? JsonObject
            return WorkflowRunStep(
                siteId = siteId,
                ordinal = ordinal,
                kind = obj.wfStr("kind"),
                op = obj.wfStr("op"),
                args = (obj["args"] as? JsonArray) ?: emptyList(),
                status = obj.wfStr("status"),
                phase = obj.wfStr("phase"),
                outcome = obj.wfStr("outcome"),
                cached = obj.wfBool("cached"),
                actorSiteId = obj.wfStr("actorSiteId"),
                actorOrdinal = obj.wfLong("actorOrdinal"),
                phaseName = obj.wfStr("phaseName"),
                name = obj.wfStr("name"),
                sessionId = obj.wfStr("sessionId"),
                model = obj.wfStr("model"),
                instructionsHead = obj.wfStr("instructionsHead"),
                turn = obj.wfLong("turn"),
                toolCalls = obj.wfLong("toolCalls"),
                lastToolName = lastTool?.wfStr("name"),
                lastToolTarget = lastTool?.wfStr("target"),
                resultBytes = summary?.wfLong("resultBytes"),
                resultCount = summary?.wfLong("resultCount"),
                exitCode = summary?.wfLong("exitCode"),
                stdoutBytes = summary?.wfLong("stdoutBytes"),
                stderrBytes = summary?.wfLong("stderrBytes"),
                errorText = error?.wfStr("message") ?: obj.wfStr("error"),
                createdAtMs = obj.wfLong("createdAt"),
                updatedAtMs = obj.wfLong("updatedAt"),
                raw = obj,
            )
        }

        fun decodeList(arr: JsonArray): List<WorkflowRunStep> =
            arr.mapNotNull { (it as? JsonObject)?.let(::decode) }
    }
}

/**
 * The per-run step graph as assembled from `workflowRun.updated` deltas (the
 * phone's projection of the desktop's actor/node plane). `lastEventSequence`
 * rides the run patch when present. `truncated` mirrors the desktop's marker.
 */
data class WorkflowRunGraph(
    val runId: String,
    val revision: Long? = null,
    val actors: Map<String, WorkflowRunStep> = emptyMap(),
    val nodes: Map<String, WorkflowRunStep> = emptyMap(),
    val lastEventSequence: Long? = null,
    val truncated: Boolean = false,
) {
    /** Ordered steps for the sheet (actors by ordinal, then nodes by ordinal). */
    val steps: List<WorkflowRunStep>
        get() = actors.values.sortedBy { it.ordinal } + nodes.values.sortedBy { it.ordinal }

    companion object {
        /** Apply one `workflowRun.updated` delta to the graph (upsert + removals). */
        fun applyDelta(graph: WorkflowRunGraph?, delta: WorkflowRunDelta.Updated): WorkflowRunGraph {
            var actors = graph?.actors ?: emptyMap()
            var nodes = graph?.nodes ?: emptyMap()
            for (site in delta.removedActors) actors = actors - "${site.siteId}#${site.ordinal}"
            for (site in delta.removedNodes) nodes = nodes - "${site.siteId}#${site.ordinal}"
            for (step in delta.actors) actors = actors + (step.siteKey to step)
            for (step in delta.nodes) nodes = nodes + (step.siteKey to step)
            return WorkflowRunGraph(
                runId = delta.runId,
                revision = delta.revision ?: graph?.revision,
                actors = actors,
                nodes = nodes,
                lastEventSequence = delta.run?.wfLong("lastEventSequence") ?: graph?.lastEventSequence,
                truncated = (delta.run?.wfBool("truncated")) ?: graph?.truncated ?: false,
            )
        }
    }
}

/** The phone's workflow-run delta plane for one session (manager-published). */
data class WorkflowRunPlane(
    /** The assembled step graphs keyed by runId. */
    val graphs: Map<String, WorkflowRunGraph> = emptyMap(),
    /** True once ANY workflow-run delta op has arrived (the "deltas flow" face). */
    val deltasSeen: Boolean = false,
    /** Monotonic count of workflow-run delta ops consumed (INV-8 observability). */
    val deltaCount: Long = 0,
    /**
     * The bounded RECENT-delta tail (last [RECENT_TAIL] ops in order) — the
     * summary-merge consumer folds unseen deltas forward; a consumer that attaches
     * late (applied < deltaCount - tail) resyncs from the poll plane once instead.
     */
    val recentDeltas: List<WorkflowRunDelta> = emptyList(),
) {
    companion object {
        /** The bounded tail size (the consumer's fold window). */
        const val RECENT_TAIL = 32

        val EMPTY = WorkflowRunPlane()

        /** Fold one op into the plane (pure; the manager publishes the result). */
        fun apply(plane: WorkflowRunPlane, delta: WorkflowRunDelta): WorkflowRunPlane {
            val tail = (plane.recentDeltas + delta).takeLast(RECENT_TAIL)
            return when (delta) {
                is WorkflowRunDelta.Updated -> plane.copy(
                    graphs = plane.graphs + (delta.runId to WorkflowRunGraph.applyDelta(plane.graphs[delta.runId], delta)),
                    deltasSeen = true,
                    deltaCount = plane.deltaCount + 1,
                    recentDeltas = tail,
                )
                is WorkflowRunDelta.Removed -> plane.copy(
                    graphs = plane.graphs - delta.runId,
                    deltasSeen = true,
                    deltaCount = plane.deltaCount + 1,
                    recentDeltas = tail,
                )
            }
        }
    }
}

/**
 * G51 consumer half: fold a run delta into the run-SUMMARY list (the poll plane's
 * shape). The desktop's delta run-patch uses the run-GRAPH schema (usage/actors/
 * nodes/lastEventSequence), the poll plane the run-summary schema (label/updatedAt/
 * failureCode/failureMessage) — the merge maps the SHARED faces (runId/status/
 * stopReason/toolCallId/resumable/error→failureMessage/resumedFrom/supersededBy)
 * and keeps the summary-only fields when the run is already known. A patch for an
 * unknown runId appends a minimal summary (the desktop's delta can pre-seed a run
 * before the next poll would list it). `cleared` un-sets the mapped faces.
 */
fun applyWorkflowRunDeltaToSummaries(
    runs: List<WorkflowRunSummary>?,
    delta: WorkflowRunDelta,
): List<WorkflowRunSummary>? {
    if (runs == null) return null
    return when (delta) {
        is WorkflowRunDelta.Removed -> runs.filterNot { it.runId == delta.runId }
        is WorkflowRunDelta.Updated -> {
            val patch = delta.run
            val statusRaw = patch?.wfStr("status")
            val existing = runs.firstOrNull { it.runId == delta.runId }
            val cleared = delta.cleared.toSet()
            fun face(current: String?, patched: String?, wasCleared: Boolean) =
                if (wasCleared) null else patched ?: current
            val merged = WorkflowRunSummary(
                runId = delta.runId,
                label = existing?.label,
                status = WorkflowRunStatus.fromWire(statusRaw ?: existing?.status?.wire),
                statusRaw = statusRaw ?: existing?.statusRaw,
                stopReason = face(existing?.stopReason, patch?.wfStr("stopReason"), "stopReason" in cleared),
                toolCallId = face(existing?.toolCallId, patch?.wfStr("toolCallId"), "toolCallId" in cleared),
                updatedAtMs = existing?.updatedAtMs,
                resumedFrom = face(existing?.resumedFrom, patch?.wfStr("resumedFrom"), "resumedFrom" in cleared),
                supersededBy = face(existing?.supersededBy, patch?.wfStr("supersededBy"), "supersededBy" in cleared),
                failureCode = face(existing?.failureCode, null, "failureCode" in cleared),
                failureMessage = face(existing?.failureMessage, patch?.wfStr("error"), "error" in cleared),
                resumable = patch?.wfBool("resumable") ?: existing?.resumable ?: false,
                artifacts = existing?.artifacts ?: emptyList(),
            )
            val without = runs.filterNot { it.runId == delta.runId }
            without + merged
        }
    }
}
