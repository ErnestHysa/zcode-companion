package com.zcode.android.domain.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Session STATE projection — the A-side keys of the VERIFIED conversation snapshot
 * (docs/protocol/v4-wire.md §9; reference clone @ 29628c9, snapshot.ts/delta.ts,
 * read 2026-09-30). This is the Phase 7 command/permission data plane: the stop
 * control, the queue, the open interactions, and the CAS revision currency.
 *
 * Merge semantics are the protocol's own (delta.ts, verbatim): KEY-LEVEL WHOLE
 * REPLACEMENT (Object.assign), closed key set, NEVER a deep merge. Unknown keys
 * are tolerated and counted (INV-8 — old desktops strip them by design); a
 * malformed KNOWN key drops THAT KEY + counts (state is auxiliary — it must never
 * corrupt the row projection nor kill the frame; §39.3 graded by data class).
 */
data class SessionProjectionState(
    val control: SessionControl? = null,
    val inputRouting: InputRouting? = null,
    val queue: QueueState? = null,
    /** Authoritative open interactions (Q11 RESOLVED — snapshots carry them). */
    val pendingInteractions: List<PendingInteraction>? = null,
    val backgroundWorks: List<BackgroundWork>? = null,
    /** Phase 9 (§10.3): model/mode/thought config — the switch commands' echo plane. */
    val config: SessionConfigState? = null,
    /** Phase 2 (§11.1): context-window usage — the GAP-1/GAP-2 header panel source. */
    val usage: SessionUsageState? = null,
    /** CAS currency for baseRevision-carrying commands (§7.4.4 verified set). */
    val revision: Long? = null,
) {
    /**
     * True when the snapshot carried ANY decoded A-side state key — the gateway
     * emits the block only then ("no state keys" stays distinguishable from "state
     * keys all absent").
     */
    val hasDecodedStateKeys: Boolean
        get() = control != null || inputRouting != null || queue != null ||
            pendingInteractions != null || backgroundWorks != null || config != null ||
            usage != null || revision != null

    /** Key-level merge (the verified Object.assign semantics — no deep merge). */
    fun mergedWith(patch: JsonObject): SessionProjectionState {
        var next = this
        for (key in patch.keys) {
            when (key) {
                "control" -> asObject(patch[key])?.let { next = next.copy(control = SessionControl.decode(it)) }
                "inputRouting" -> asObject(patch[key])?.let { next = next.copy(inputRouting = InputRouting.decode(it)) }
                "queue" -> asObject(patch[key])?.let { next = next.copy(queue = QueueState.decode(it)) }
                "pendingInteractions" -> asArray(patch[key])?.let {
                    next = next.copy(pendingInteractions = PendingInteraction.decodeList(it))
                }
                "backgroundWorks" -> asArray(patch[key])?.let {
                    next = next.copy(backgroundWorks = BackgroundWork.decodeList(it))
                }
                "config" -> asObject(patch[key])?.let { o -> SessionConfigState.decodeOrNull(o)?.let { next = next.copy(config = it) } }
                "usage" -> asObject(patch[key])?.let { o -> SessionUsageState.decodeOrNull(o)?.let { next = next.copy(usage = it) } }
                "revision" -> (patch[key] as? JsonPrimitive)?.content?.toLongOrNull()?.let {
                    next = next.copy(revision = it)
                }
                // meta/pendingCommands/subagents/workflowRuns/goal/plan/
                // sharedContextImport/modelTransition/workspaceHookAdmission: not consumed
                // by any surface — ignored keys, counted, never merged (INV-8).
                // (`config` joined in Phase 9 — §10.3; `usage` in Phase 2 — §11.1 of
                // docs/protocol/v4-wire.md.)
                else -> ignoredKeys++
            }
        }
        return next
    }

    companion object {
        /** INV-8 observability: stripped/ignored/unknown-key counters (§34 metrics class). */
        var ignoredKeys: Long = 0
            private set
        var malformedKeyDrops: Long = 0
            internal set

        /** Full replace from a snapshot object; a key that fails decode drops + counts. */
        fun fromSnapshot(snapshot: JsonObject): SessionProjectionState {
            var state = SessionProjectionState()
            for (key in snapshot.keys) {
                when (key) {
                    "control" -> asObject(snapshot[key])?.let { state = state.copy(control = SessionControl.decode(it)) }
                    "inputRouting" -> asObject(snapshot[key])?.let { state = state.copy(inputRouting = InputRouting.decode(it)) }
                    "queue" -> asObject(snapshot[key])?.let { state = state.copy(queue = QueueState.decode(it)) }
                    "pendingInteractions" -> asArray(snapshot[key])?.let {
                        state = state.copy(pendingInteractions = PendingInteraction.decodeList(it))
                    }
                    "backgroundWorks" -> asArray(snapshot[key])?.let {
                        state = state.copy(backgroundWorks = BackgroundWork.decodeList(it))
                    }
                    "config" -> asObject(snapshot[key])?.let { o -> SessionConfigState.decodeOrNull(o)?.let { state = state.copy(config = it) } }
                    "usage" -> asObject(snapshot[key])?.let { o -> SessionUsageState.decodeOrNull(o)?.let { state = state.copy(usage = it) } }
                    "revision" -> (snapshot[key] as? JsonPrimitive)?.content?.toLongOrNull()?.let {
                        state = state.copy(revision = it)
                    }
                    else -> ignoredKeys++
                }
            }
            return state
        }

        private fun asObject(el: kotlinx.serialization.json.JsonElement?): JsonObject? {
            if (el is JsonObject) return el
            malformedKeyDrops++
            return null
        }

        private fun asArray(el: kotlinx.serialization.json.JsonElement?): JsonArray? {
            if (el is JsonArray) return el
            malformedKeyDrops++
            return null
        }
    }
}

/** snapshot.ts sessionControlSchema — Phase 7 consumes canStop/stopState/activeWorks. */
data class SessionControl(
    val phase: String?,
    val sessionEnded: Boolean?,
    val canStop: Boolean?,
    val stopState: StopState,
    /** `{kind, foregroundExecutionId?, startedAt}` — stop's optional CAS evidence. */
    val activeWorks: List<ActiveWork>,
) {
    enum class StopState(val wire: String) {
        IDLE("idle"), STOPPABLE("stoppable"), STOPPING("stopping"), UNKNOWN("");
        companion object {
            fun fromWire(v: String?): StopState = entries.firstOrNull { it.wire == v } ?: UNKNOWN
        }
    }

    data class ActiveWork(val kind: String?, val foregroundExecutionId: String?)

    companion object {
        fun decode(obj: JsonObject): SessionControl = SessionControl(
            phase = obj.strOrNull("phase"),
            sessionEnded = obj.primitiveBool("sessionEnded"),
            canStop = obj.primitiveBool("canStop"),
            stopState = StopState.fromWire(obj.strOrNull("stopState")),
            activeWorks = (obj["activeWorks"] as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                ?.map { ActiveWork(it.strOrNull("kind"), it.strOrNull("foregroundExecutionId")) }
                ?: emptyList(),
        )
    }
}

/** snapshot.ts inputRoutingSchema — what the desktop will do with the next input. */
data class InputRouting(val mode: Mode, val reasonCode: String?) {
    enum class Mode(val wire: String) {
        START_NOW("startNow"), ENQUEUE("enqueue"), GUIDE("guide"), REJECT("reject"),
        /** Held queue: composer must offer heldQueueDisposition (clear/keep). */
        CHOICE("choice"), UNKNOWN("");

        companion object {
            fun fromWire(v: String?): Mode = entries.firstOrNull { it.wire == v } ?: UNKNOWN
        }
    }

    companion object {
        fun decode(obj: JsonObject): InputRouting =
            InputRouting(Mode.fromWire(obj.strOrNull("mode")), obj.strOrNull("reasonCode"))
    }
}

/** snapshot.ts queueStateSchema — queue display source (never persisted client-side). */
data class QueueState(
    val items: List<QueueItem>,
    val autoDrain: Boolean?,
    val pauseReason: String?,
) {
    data class QueueItem(
        val queueItemId: String,
        val text: String,
        val kind: String?,
        /** verified dispatch.state: queued | reserved | promoting */
        val dispatchState: String?,
    )

    companion object {
        fun decode(obj: JsonObject): QueueState = QueueState(
            items = (obj["items"] as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                ?.map { item ->
                    QueueItem(
                        queueItemId = item.strOrNull("queueItemId") ?: "",
                        text = item.strOrNull("text") ?: "",
                        kind = item.strOrNull("kind"),
                        dispatchState = ((item["dispatch"] as? JsonObject)?.get("state") as? JsonPrimitive)?.content,
                    )
                }
                ?: emptyList(),
            autoDrain = obj.primitiveBool("autoDrain"),
            pauseReason = obj.strOrNull("pauseReason"),
        )
    }
}

/**
 * snapshot.ts pendingInteractionSchema (permission | userInput payloads). Decoded
 * here as the raw + typed pair: the coordinator maps [permission]/[userInput]
 * payloads onto the §14.4.3 card models; unknown KINDS are retained raw + counted
 * (INV-8) and NEVER actionable (INV-14 — fail closed like unknown option ids).
 */
data class PendingInteraction(
    val interactionId: String,
    val kind: Kind,
    val anchorRowId: Long?,
    val createdAtMs: Long?,
    val permission: PermissionPayload?,
    val userInput: UserInputPayload?,
    val raw: JsonObject,
) {
    enum class Kind(val wire: String) {
        PERMISSION("permission"), USER_INPUT("userInput"),
        WORKSPACE_HOOK_REVIEW("workspaceHookReview"), UNKNOWN("");
        companion object {
            fun fromWire(v: String?): Kind = entries.firstOrNull { it.wire == v } ?: UNKNOWN
        }
    }

    /** permissionRequestPayloadSchema — the §20.2 decision context, verbatim fields. */
    data class PermissionPayload(
        val toolCallId: String?,
        val toolName: String,
        val summary: String?,
        val detail: JsonObject?,
        val freeText: Boolean?,
        /** Verified options: `{optionId, label, kind}` — semantics stay optionId-classified (INV-14). */
        val options: List<PermissionOption>,
        val raw: JsonObject,
    )

    /** userInputRequestPayloadSchema — the U9 question plane. */
    data class UserInputPayload(
        val prompt: String?,
        val freeText: Boolean?,
        val sensitive: Boolean?,
        val options: List<PermissionOption>,
        val questions: List<Question>,
        val toolName: String?,
        val raw: JsonObject,
    ) {
        data class Option(val value: String, val label: String)

        data class Question(
            val question: String,
            val header: String,
            val options: List<Option>,
            val multiSelect: Boolean?,
        )
    }

    companion object {
        fun decodeList(arr: JsonArray): List<PendingInteraction> = arr
            .mapNotNull { it as? JsonObject }
            .mapNotNull { decode(it) }

        fun decode(obj: JsonObject): PendingInteraction? {
            val interactionId = obj.strOrNull("interactionId") ?: run {
                SessionProjectionState.malformedKeyDrops++
                return null
            }
            val kind = Kind.fromWire(obj.strOrNull("kind"))
            val payload = obj["payload"] as? JsonObject
            return PendingInteraction(
                interactionId = interactionId,
                kind = kind,
                anchorRowId = obj.longOrNull("anchorRowId"),
                createdAtMs = obj.doubleOrNull("createdAt")?.toLong(),
                permission = payload?.takeIf { it.strOrNull("kind") == "permission" }?.let { p ->
                    PermissionPayload(
                        toolCallId = p.strOrNull("toolCallId"),
                        toolName = p.strOrNull("toolName") ?: "",
                        summary = p.strOrNull("summary"),
                        detail = p["detail"] as? JsonObject,
                        freeText = p.primitiveBool("freeText"),
                        options = decodeOptions(p),
                        raw = p,
                    )
                },
                userInput = payload?.takeIf { it.strOrNull("kind") == "userInput" }?.let { p ->
                    UserInputPayload(
                        prompt = p.strOrNull("prompt"),
                        freeText = p.primitiveBool("freeText"),
                        sensitive = p.primitiveBool("sensitive"),
                        options = decodeOptions(p),
                        questions = (p["questions"] as? JsonArray)?.mapNotNull { q ->
                            q as? JsonObject
                        }?.map { q ->
                            UserInputPayload.Question(
                                question = q.strOrNull("question") ?: "",
                                header = q.strOrNull("header") ?: "",
                                options = ((q["options"] as? JsonArray)?.mapNotNull { o ->
                                    (o as? JsonObject)
                                }?.map { o ->
                                    UserInputPayload.Option(
                                        value = o.strOrNull("value") ?: "",
                                        label = o.strOrNull("label") ?: "",
                                    )
                                } ?: emptyList()),
                                multiSelect = q.primitiveBool("multiSelect"),
                            )
                        } ?: emptyList(),
                        toolName = p.strOrNull("toolName"),
                        raw = p,
                    )
                },
                raw = obj,
            )
        }

        /** `{optionId, label, kind}` → the domain fail-closed option model (optionId semantics). */
        private fun decodeOptions(payload: JsonObject): List<PermissionOption> =
            (payload["options"] as? JsonArray)?.mapNotNull { o ->
                (o as? JsonObject)?.strOrNull("optionId")
            }?.map { PermissionOption(it) } ?: emptyList()
    }
}

/** snapshot.ts backgroundWorkSummarySchema — cancelBackgroundWork targets. */
data class BackgroundWork(
    val workId: String,
    val kind: String?,
    val title: String,
    val status: String?,
    val cancellable: Boolean?,
) {
    companion object {
        fun decodeList(arr: JsonArray): List<BackgroundWork> = arr
            .mapNotNull { it as? JsonObject }
            .mapNotNull { obj ->
                val workId = obj.strOrNull("workId") ?: return@mapNotNull null
                BackgroundWork(
                    workId = workId,
                    kind = obj.strOrNull("kind"),
                    title = obj.strOrNull("title") ?: "",
                    status = obj.strOrNull("status"),
                    cancellable = obj.primitiveBool("cancellable"),
                )
            }
    }
}

/**
 * snapshot.ts `config` key (session-config.ts sessionConfigStateSchema) — Phase 9
 * (§10.3 of docs/protocol/v4-wire.md). The model/mode/thought echo plane: the
 * CURRENT effective selection the switch commands reconcile against. INV-8
 * tolerance: `mode` outside the verified switchable set stays a raw string (the
 * renderer shows it verbatim; the picker only offers the verified enum); a
 * malformed KNOWN field drops the whole key + counts (fail closed — the picker
 * renders "unknown" rather than a guessed selection).
 */
data class SessionConfigState(
    val provider: String,
    val model: String,
    val thought: String,
    /** The CURRENT model's actual thought-level set (runtime-projected, not a UI pref). */
    val thoughtLevels: List<String>,
    val followupMode: String?,
    /** Collaboration mode — verified switchable set: build|edit|plan|yolo (§10.3). */
    val mode: String,
    val planEnabled: Boolean?,
    val modelSelectionProviderId: String?,
    val modelSelectionModelId: String?,
) {
    val modeIsSwitchable: Boolean get() = mode in SWITCHABLE_MODES

    companion object {
        /** command.ts switchCollaborationMode enum — `auto` deliberately excluded (schema comment). */
        val SWITCHABLE_MODES = listOf("build", "edit", "plan", "yolo")

        fun decode(obj: JsonObject): SessionConfigState =
            SessionConfigState(
                provider = obj.string("provider"),
                model = obj.string("model"),
                thought = obj.string("thought"),
                thoughtLevels = (obj["thoughtLevels"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.content }
                    ?: emptyList(),
                followupMode = obj.strOrNull("followupMode"),
                // Verified default "build" (schema `.default("build")`); absent → default.
                mode = obj.strOrNull("mode") ?: "build",
                planEnabled = obj.primitiveBool("planEnabled"),
                modelSelectionProviderId = (obj["modelSelection"] as? JsonObject)?.strOrNull("providerId"),
                modelSelectionModelId = (obj["modelSelection"] as? JsonObject)?.strOrNull("modelId"),
            )

        /**
         * Key-drop variant for the state merge (the SessionState policy: a malformed
         * KNOWN key drops THAT KEY + counts — state is auxiliary, it never kills the
         * frame; §9 decode policy in docs/protocol/v4-wire.md).
         */
        fun decodeOrNull(obj: JsonObject): SessionConfigState? = try {
            decode(obj)
        } catch (e: RowDecodeException) {
            SessionProjectionState.malformedKeyDrops++
            null
        }
    }
}

// ------------------------------------------------------------------ json helpers
// strOrNull/longOrNull/doubleOrNull are the package-local helpers declared in
// ConversationRow.kt; only the nullable-bool probe is new here.

internal fun JsonObject.primitiveBool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

/**
 * snapshot.ts `usage` key (sessionUsageStateSchema) — Phase 2 (§11.1 of
 * docs/protocol/v4-wire.md), ported for GAP-1/GAP-2. The context-window meter +
 * the per-source breakdown (chars are per-source ESTIMATES for ratio display,
 * never a token ledger — schema comment). Conflation: the key is omitted from
 * patches when the value is unchanged — absence keeps the prior value (INV-8);
 * a malformed KNOWN key drops THAT KEY + counts (the §9 policy).
 *
 * Desktop renderable gate ported verbatim (contextUsage.tsx getRenderableTaskUsage):
 * used <= 0 || size <= 0 is NOT renderable — "used=0 或非法值…不能渲染成可用的
 * context meter" (never a misleading 0%). The `cache` block is KNOWN but not
 * consumed by the Phase-2 panel (roadmap scope) — it rides `raw` for forward
 * compatibility.
 */
data class SessionUsageState(
    /** `contextWindow: null` = a PRESENT key with no window data (panel hidden). */
    val contextWindow: ContextWindow?,
    val cumulative: Cumulative?,
    /** Full wire object — unknown fields ride along here (INV-8). */
    val raw: JsonObject,
) {
    data class ContextWindow(
        val usedTokens: Long?,
        val maxTokens: Long?,
        val autoCompactThresholdTokens: Long?,
        val breakdown: List<BreakdownItem>,
        val raw: JsonObject,
    )

    /** `{source, chars}` — source retained as the RAW wire string (INV-8: unknown
     *  values are tolerated, never guessed; the UI maps known sources to labels). */
    data class BreakdownItem(val source: String, val chars: Long)

    data class Cumulative(
        val inputTokens: Long?,
        val outputTokens: Long?,
        val cacheReadTokens: Long?,
        val cacheWriteTokens: Long?,
    )

    /**
     * Aggregated per-source segments — the desktop buildContextUsageBreakdownSegments
     * port (contextUsage.tsx:166-202): drop chars <= 0, aggregate per source,
     * percent = chars/totalChars, sort chars DESC with a stable source-order
     * tiebreak (messages=0, system_prompt=1, meta_user_context=2, skills=3,
     * tool_prompt=4, system_tool_schemas=5, mcp_tool_schemas=6).
     */
    data class Segment(val source: String, val chars: Long, val percent: Double)

    val renderable: Boolean
        get() {
            val window = contextWindow ?: return false
            val used = window.usedTokens ?: return false
            val size = window.maxTokens ?: return false
            return used > 0 && size > 0
        }

    /** used/size as a 0..1 fraction; null when not renderable. */
    val usedFraction: Double?
        get() = contextWindow?.let { w ->
            val used = w.usedTokens ?: return@let null
            val size = w.maxTokens ?: return@let null
            if (used <= 0 || size <= 0) null else used.toDouble() / size.toDouble()
        }

    val contextWindowBreakdownSegments: List<Segment>
        get() {
            val items = contextWindow?.breakdown ?: return emptyList()
            val charsBySource = LinkedHashMap<String, Long>()
            for (item in items) {
                if (item.chars <= 0) continue
                charsBySource[item.source] = (charsBySource[item.source] ?: 0L) + item.chars
            }
            val total = charsBySource.values.sum()
            if (total <= 0) return emptyList()
            return charsBySource.entries
                .map { Segment(it.key, it.value, it.value.toDouble() / total.toDouble()) }
                .sortedWith(
                    compareByDescending<Segment> { it.chars }
                        .thenBy { BREAKDOWN_SOURCE_ORDER[it.source] ?: Int.MAX_VALUE },
                )
        }

    companion object {
        /** contextUsage.tsx BREAKDOWN_SOURCE_ORDER — the desktop's stable tiebreak. */
        val BREAKDOWN_SOURCE_ORDER = mapOf(
            "messages" to 0,
            "system_prompt" to 1,
            "meta_user_context" to 2,
            "skills" to 3,
            "tool_prompt" to 4,
            "system_tool_schemas" to 5,
            "mcp_tool_schemas" to 6,
        )

        fun decode(obj: JsonObject): SessionUsageState {
            val windowObj = obj["contextWindow"]
            val contextWindow = when (windowObj) {
                null -> throw RowDecodeException("usage.contextWindow missing (required, nullable)")
                is JsonObject -> ContextWindow(
                    usedTokens = windowObj.longOrNull("usedTokens")
                        ?: throw RowDecodeException("usage.contextWindow.usedTokens missing"),
                    maxTokens = windowObj.longOrNull("maxTokens")
                        ?: throw RowDecodeException("usage.contextWindow.maxTokens missing"),
                    autoCompactThresholdTokens = windowObj.longOrNull("autoCompactThresholdTokens"),
                    breakdown = (windowObj["breakdown"] as? JsonArray)
                        ?.mapNotNull { it as? JsonObject }
                        ?.map { item ->
                            BreakdownItem(
                                source = item.string("source"),
                                chars = item.long("chars"),
                            )
                        }
                        ?: emptyList(),
                    raw = windowObj,
                )
                // Explicit wire null (JsonNull) — a PRESENT key with no window data.
                is JsonNull -> null
                else -> throw RowDecodeException("usage.contextWindow wrong type")
            }
            val cumulativeObj = obj["cumulative"] as? JsonObject
                ?: throw RowDecodeException("usage.cumulative missing")
            return SessionUsageState(
                contextWindow = contextWindow,
                cumulative = Cumulative(
                    inputTokens = cumulativeObj.longOrNull("inputTokens"),
                    outputTokens = cumulativeObj.longOrNull("outputTokens"),
                    cacheReadTokens = cumulativeObj.longOrNull("cacheReadTokens"),
                    cacheWriteTokens = cumulativeObj.longOrNull("cacheWriteTokens"),
                ),
                raw = obj,
            )
        }

        /** Key-drop variant for the state merge (§9 policy — drop THAT key + count). */
        fun decodeOrNull(obj: JsonObject): SessionUsageState? = try {
            decode(obj)
        } catch (e: RowDecodeException) {
            SessionProjectionState.malformedKeyDrops++
            null
        }
    }
}
