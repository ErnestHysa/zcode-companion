package com.zcode.android.domain.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Phase 3 viewer models (android-native-gaps.md GAP-5…GAP-9; docs/protocol/
 * v4-wire.md §12) — read-only R-class decodes of §13.3-sanctioned channel
 * payloads. Same decode discipline as [SessionUsageState] (the Phase-2
 * precedent): required-field violations of KNOWN shapes throw
 * [RowDecodeException] (fail closed, never lenient); unknown fields ride the
 * raw object (INV-8 — the payloads are additive on newer hosts). Closed enums
 * stay RAW strings: unknown values render verbatim, never guessed (§9 policy).
 */

// ================================================================== GAP-5: app usage

/**
 * `getAppUsageStats` result (= the CLI `v4/usage/stats` result,
 * appUsageSnapshotSchema — usage-stats.ts, NON-strict z.object; v4-wire.md
 * §12.1). The agent-DB real usage aggregate: lifetime range summary, weekly
 * heatmap, per-day per-model trend series, per-model and per-tool tables.
 */
data class AppUsageSnapshot(
    val range: String,
    val generatedAt: Long,
    val timeZone: String,
    /** Literal "agent-db" today; rendered verbatim (tolerant of future sources). */
    val source: String,
    val summary: Summary,
    val heatmap: Heatmap,
    val dailyModelUsage: List<DailyModelUsage>,
    val models: List<ModelUsage>,
    val tools: List<ToolUsage>,
) {
    data class Summary(
        val totalTokens: Long,
        val inputTokens: Long,
        val outputTokens: Long,
        val reasoningTokens: Long,
        val cacheCreationTokens: Long,
        val cacheReadTokens: Long,
        val cacheHitRate: Double,
        val totalSessions: Int,
        val totalTurns: Int,
        val toolCallCount: Int,
        val toolErrorRate: Double,
        val modelErrorRate: Double,
        val avgTimeToFirstTokenMs: Long?,
        val avgTurnDurationMs: Long?,
        val activeDays: Int,
        val currentStreakDays: Int,
        val longestSessionMs: Long,
        val longestStreakDays: Int,
        val peakDayTokens: Long,
        val favoriteModel: FavoriteModel?,
    )

    data class FavoriteModel(val modelId: String?, val totalTokens: Long, val share: Double)

    data class Heatmap(
        val startDate: String?,
        val endDate: String?,
        val maxTokens: Long,
        /** Null cells are REAL payload values (unfilled week tails) — preserved. */
        val weeks: List<Week>,
    )

    data class Week(val weekIndex: Int, val days: List<Day?>)

    data class Day(
        val date: String,
        /** 0..4 intensity bucket on the wire; unknown values render verbatim. */
        val level: Int,
        val totalTokens: Long,
        val turnCount: Int,
        val toolCallCount: Int,
    )

    data class DailyModelUsage(val date: String, val models: List<DailyModelItem>)

    data class DailyModelItem(val modelId: String?, val totalTokens: Long)

    data class ModelUsage(
        val modelId: String?,
        val totalTokens: Long,
        val inputTokens: Long,
        val outputTokens: Long,
        val requestCount: Int,
        val share: Double,
    )

    data class ToolUsage(
        val toolName: String,
        val callCount: Int,
        val errorCount: Int,
        val errorRate: Double,
        val avgDurationMs: Long?,
    )

    companion object {
        fun decode(obj: JsonObject): AppUsageSnapshot {
            val summaryObj = obj.obj("summary")
            val heatmapObj = obj.obj("heatmap")
            return AppUsageSnapshot(
                range = obj.strReq("range"),
                generatedAt = obj.longReq("generatedAt"),
                timeZone = obj.strReq("timeZone"),
                source = obj.strReq("source"),
                summary = Summary(
                    totalTokens = summaryObj.longReq("totalTokens"),
                    inputTokens = summaryObj.longReq("inputTokens"),
                    outputTokens = summaryObj.longReq("outputTokens"),
                    reasoningTokens = summaryObj.longReq("reasoningTokens"),
                    cacheCreationTokens = summaryObj.longReq("cacheCreationTokens"),
                    cacheReadTokens = summaryObj.longReq("cacheReadTokens"),
                    cacheHitRate = summaryObj.doubleReq("cacheHitRate"),
                    totalSessions = summaryObj.intReq("totalSessions"),
                    totalTurns = summaryObj.intReq("totalTurns"),
                    toolCallCount = summaryObj.intReq("toolCallCount"),
                    toolErrorRate = summaryObj.doubleReq("toolErrorRate"),
                    modelErrorRate = summaryObj.doubleReq("modelErrorRate"),
                    avgTimeToFirstTokenMs = summaryObj.longOrNullN("avgTimeToFirstTokenMs"),
                    avgTurnDurationMs = summaryObj.longOrNullN("avgTurnDurationMs"),
                    activeDays = summaryObj.intReq("activeDays"),
                    currentStreakDays = summaryObj.intReq("currentStreakDays"),
                    longestSessionMs = summaryObj.longReq("longestSessionMs"),
                    longestStreakDays = summaryObj.intReq("longestStreakDays"),
                    peakDayTokens = summaryObj.longReq("peakDayTokens"),
                    favoriteModel = summaryObj.objOrNull("favoriteModel")?.let { f ->
                        FavoriteModel(
                            modelId = f.strOrNullN("modelId"),
                            totalTokens = f.longReq("totalTokens"),
                            share = f.doubleReq("share"),
                        )
                    },
                ),
                heatmap = Heatmap(
                    startDate = heatmapObj.strOrNullN("startDate"),
                    endDate = heatmapObj.strOrNullN("endDate"),
                    maxTokens = heatmapObj.longReq("maxTokens"),
                    weeks = heatmapObj.arrayReq("weeks").map { w ->
                        val weekObj = w.asObject("heatmap week")
                        Week(
                            weekIndex = weekObj.intReq("weekIndex"),
                            days = weekObj.arrayReq("days").map { d ->
                                d.asJsonObjectOrNull()?.let { cell ->
                                    Day(
                                        date = cell.strReq("date"),
                                        level = cell.intReq("level"),
                                        totalTokens = cell.longReq("totalTokens"),
                                        turnCount = cell.intReq("turnCount"),
                                        toolCallCount = cell.intReq("toolCallCount"),
                                    )
                                }
                            },
                        )
                    },
                ),
                dailyModelUsage = obj.arrayReq("dailyModelUsage").map { d ->
                    val dayObj = d.asObject("dailyModelUsage day")
                    DailyModelUsage(
                        date = dayObj.strReq("date"),
                        models = dayObj.arrayReq("models").map { m ->
                            val itemObj = m.asObject("daily model item")
                            DailyModelItem(
                                modelId = itemObj.strOrNullN("modelId"),
                                totalTokens = itemObj.longReq("totalTokens"),
                            )
                        },
                    )
                },
                models = obj.arrayReq("models").map { m ->
                    val modelObj = m.asObject("model usage")
                    ModelUsage(
                        modelId = modelObj.strOrNullN("modelId"),
                        totalTokens = modelObj.longReq("totalTokens"),
                        inputTokens = modelObj.longReq("inputTokens"),
                        outputTokens = modelObj.longReq("outputTokens"),
                        requestCount = modelObj.intReq("requestCount"),
                        share = modelObj.doubleReq("share"),
                    )
                },
                tools = obj.arrayReq("tools").map { t ->
                    val toolObj = t.asObject("tool usage")
                    ToolUsage(
                        toolName = toolObj.strReq("toolName"),
                        callCount = toolObj.intReq("callCount"),
                        errorCount = toolObj.intReq("errorCount"),
                        errorRate = toolObj.doubleReq("errorRate"),
                        avgDurationMs = toolObj.longOrNullN("avgDurationMs"),
                    )
                },
            )
        }
    }
}

// ---------------------------------------------------------------- shared decode helpers

/** Null-aware readers for the schema's `X|null` fields — JsonNull → Kotlin null. */
internal fun JsonObject.strOrNullN(key: String): String? =
    when (val v = this[key]) {
        null, is kotlinx.serialization.json.JsonNull -> null
        is JsonPrimitive -> v.content
        else -> throw RowDecodeException("$key wrong type")
    }

internal fun JsonObject.longOrNullN(key: String): Long? =
    when (val v = this[key]) {
        null, is kotlinx.serialization.json.JsonNull -> null
        is JsonPrimitive -> v.content.toLongOrNull()
        else -> null
    }

// `strOrNull` / `longOrNull` / `double` / `long` reuse the ConversationRow.kt
// domain-local helpers (same package); the names below fill the remaining gaps.

internal fun JsonObject.obj(key: String): JsonObject =
    this[key] as? JsonObject ?: throw RowDecodeException("$key missing (required object)")

internal fun JsonObject.objOrNull(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arrayReq(key: String): JsonArray =
    this[key] as? JsonArray ?: throw RowDecodeException("$key missing (required array)")

internal fun JsonObject.strReq(key: String): String =
    this.string(key)

internal fun JsonObject.longReq(key: String): Long =
    this.long(key)

internal fun JsonObject.intReq(key: String): Int =
    (this[key] as? JsonPrimitive)?.content?.toIntOrNull() ?: throw RowDecodeException("$key missing (required int)")

internal fun JsonObject.doubleReq(key: String): Double =
    this.double(key)

internal fun kotlinx.serialization.json.JsonElement.asObject(where: String): JsonObject =
    this as? JsonObject ?: throw RowDecodeException("$where not an object")

internal fun kotlinx.serialization.json.JsonElement.asJsonObjectOrNull(): JsonObject? =
    this as? JsonObject

internal fun JsonObject.boolReq(key: String): Boolean =
    (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
        ?: throw RowDecodeException("$key missing (required boolean)")

internal fun JsonObject.intOrNull(key: String): Int? =
    when (val v = this[key]) {
        null, is kotlinx.serialization.json.JsonNull -> null
        is JsonPrimitive -> v.content.toIntOrNull()
        else -> null
    }

internal fun JsonObject.intList(key: String): List<Int> =
    (this[key] as? JsonArray)?.map { el ->
        (el as? JsonPrimitive)?.content?.toIntOrNull() ?: throw RowDecodeException("$key entry not an int")
    } ?: emptyList()

internal fun JsonObject.strList(key: String): List<String> =
    (this[key] as? JsonArray)?.map { el ->
        (el as? JsonPrimitive)?.content ?: throw RowDecodeException("$key entry not a string")
    } ?: emptyList()


// ================================================================== GAP-6: automations + off-peak

/**
 * One automation of the `zcode-agent` → `listAutomations` channel payload
 * (v4-wire.md §12.2): the CLI protocol face (zcodeAutomationProtocolSchema,
 * strict) PLUS the channel-only fields of the desktop type (automation-types.ts:65-113).
 * `lifecycleStatus`/`unit` stay RAW — unknown values render verbatim, never guessed.
 */
data class AutomationRow(
    val automationId: String,
    val title: String,
    val cronExpr: String,
    val prompt: String,
    val mode: String?,
    val targetTaskId: String?,
    val enabled: Boolean,
    /** "active" | "completed" | "failed" | "paused" (closed enum, kept raw). */
    val lifecycleStatus: String,
    val nextRunAt: Long?,
    val lastRunAt: Long?,
    val runCount: Int,
    val recurring: Boolean,
    val maxRuns: Int?,
    /** Present when the real interval is NOT expressible in cron (schema comment) — display it then. */
    val scheduleRule: ScheduleRule?,
    val workspaceKey: String,
    /** "local" | "remote" (closed enum, kept raw). */
    val locationKind: String,
) {
    data class ScheduleRule(
        val unit: String,
        val interval: Int,
        val hour: Int,
        val minute: Int,
        val anchorAt: Long,
        val weekdays: List<Int>,
        val monthDays: List<Int>,
    )

    companion object {
        fun decodeList(obj: JsonObject): List<AutomationRow> =
            obj.arrayReq("automations").map { el ->
                val o = el.asObject("automation")
                AutomationRow(
                    automationId = o.strReq("automationId"),
                    title = o.strReq("title"),
                    cronExpr = o.strReq("cronExpr"),
                    prompt = o.strReq("prompt"),
                    mode = o.strOrNullN("mode"),
                    targetTaskId = o.strOrNullN("targetTaskId"),
                    enabled = o.boolReq("enabled"),
                    lifecycleStatus = o.strReq("lifecycleStatus"),
                    nextRunAt = o.longOrNullN("nextRunAt"),
                    lastRunAt = o.longOrNullN("lastRunAt"),
                    runCount = o.intReq("runCount"),
                    recurring = o.boolReq("recurring"),
                    maxRuns = o.intOrNull("maxRuns"),
                    scheduleRule = o.objOrNull("scheduleRule")?.let { r ->
                        ScheduleRule(
                            unit = r.strReq("unit"),
                            interval = r.intReq("interval"),
                            hour = r.intReq("hour"),
                            minute = r.intReq("minute"),
                            anchorAt = r.longReq("anchorAt"),
                            weekdays = r.intList("weekdays"),
                            monthDays = r.intList("monthDays"),
                        )
                    },
                    workspaceKey = o.strReq("workspaceKey"),
                    locationKind = o.strReq("locationKind"),
                )
            }
        }
}

/**
 * One off-peak (闲时) task of the `off-peak-task` channel `list` payload
 * (v4-wire.md §12.2): the protocol snapshot face (zcodeOffPeakTaskSnapshotSchema —
 * "最小字段面。不暴露 serverTicketId") plus the display fields of the desktop
 * channel type (off-peak-types.ts:102-165). `status` stays RAW (closed enum).
 */
data class OffPeakTaskRow(
    val offPeakTaskId: String,
    val title: String,
    /** "queued"|"paused"|"running"|"completed"|"failed"|"cancelled" (kept raw). */
    val status: String,
    val queuePosition: Int?,
    val sessionId: String?,
    val createdAt: Long,
    val workspaceKey: String,
    val failureReason: String?,
) {
    companion object {
        fun decodeList(obj: JsonObject): List<OffPeakTaskRow> =
            obj.arrayReq("tasks").map { el ->
                val o = el.asObject("off-peak task")
                OffPeakTaskRow(
                    offPeakTaskId = o.strReq("offPeakTaskId"),
                    title = o.strReq("title"),
                    status = o.strReq("status"),
                    queuePosition = o.intOrNull("queuePosition"),
                    sessionId = o.strOrNullN("sessionId"),
                    createdAt = o.longReq("createdAt"),
                    workspaceKey = o.strReq("workspaceKey"),
                    failureReason = o.strOrNullN("failureReason"),
                )
            }
        }
}

// ================================================================== GAP-7: plugins

/**
 * The `zcode-agent` → `listPlugins` result (= the CLI `plugins/list` result,
 * zcodePluginsListResultSchema — STRICT; v4-wire.md §12.3). The viewer displays
 * the installed list + the enable/disable STATE; the write half (install/
 * uninstall/update/enable) is Deferred scope and decoded nowhere.
 */
data class PluginsView(
    val plugins: List<PluginInfo>,
    val diagnostics: List<PluginDiagnostic>,
) {
    data class PluginInfo(
        val id: String,
        val name: String,
        val description: String?,
        val version: String?,
        val enabled: Boolean,
        val source: String,
        val marketplace: String,
        val skillRootCount: Int,
        val commandRootCount: Int,
        val mcpServerNames: List<String>,
        val rootPath: String,
    )

    data class PluginDiagnostic(
        val code: String,
        val message: String,
        /** "warning" | "error" | unknown (kept raw). */
        val severity: String?,
        val pluginId: String?,
    )

    companion object {
        fun decode(obj: JsonObject): PluginsView = PluginsView(
            plugins = obj.arrayReq("plugins").map { el ->
                val o = el.asObject("plugin")
                PluginInfo(
                    id = o.strReq("id"),
                    name = o.strReq("name"),
                    description = o.strOrNullN("description"),
                    version = o.strOrNullN("version"),
                    enabled = o.boolReq("enabled"),
                    source = o.strReq("source"),
                    marketplace = o.strReq("marketplace"),
                    skillRootCount = o.intReq("skillRootCount"),
                    commandRootCount = o.intReq("commandRootCount"),
                    mcpServerNames = o.strList("mcpServerNames"),
                    rootPath = o.strReq("rootPath"),
                )
            },
            diagnostics = obj.arrayReq("diagnostics").map { el ->
                val o = el.asObject("plugin diagnostic")
                PluginDiagnostic(
                    code = o.strReq("code"),
                    message = o.strReq("message"),
                    severity = o.strOrNullN("severity"),
                    pluginId = o.strOrNullN("pluginId"),
                )
            },
        )
    }
}

// ================================================================== GAP-8: skills catalog

/**
 * The `zcode-agent` → `getSkillReferenceCatalog` result (= the CLI
 * `skills/referenceCatalog` result, zcodeSkillsReferenceCatalogResultSchema —
 * STRICT; v4-wire.md §12.4). This is the CONVERSATION REFERENCE projection —
 * `enabled` is z.literal(true) on this plane, so the model carries no enabled
 * field at all and a viewer must NOT render an enable/disable affordance from
 * it ("该协议只承载 Composer 的只读引用投影"). `scope` stays RAW.
 */
data class SkillCatalog(
    /** "session" (frozen at session creation) | "workspace" (current scan) — kept raw. */
    val authority: String,
    val skills: List<SkillEntry>,
) {
    data class SkillEntry(
        val id: String,
        val name: String,
        val description: String,
        /** Desktop path — display-only metadata (INV-16), never joined locally. */
        val path: String,
        /** "workspace" | "user" | "plugin" (closed enum, kept raw). */
        val scope: String,
        val pluginName: String?,
    )

    companion object {
        fun decode(obj: JsonObject): SkillCatalog = SkillCatalog(
            authority = obj.strReq("authority"),
            skills = obj.arrayReq("skills").map { el ->
                val o = el.asObject("skill entry")
                SkillEntry(
                    id = o.strReq("id"),
                    name = o.strReq("name"),
                    description = o.strReq("description"),
                    path = o.strReq("path"),
                    scope = o.strReq("scope"),
                    pluginName = o.strOrNullN("pluginName"),
                )
            },
        )
    }
}

// ================================================================== GAP-9: mcp servers

/**
 * The `zcode-agent` → `listMcpServerStatuses` result (= the CLI `mcp/list`
 * result, zcodeMcpListResultSchema — STRICT; v4-wire.md §12.5). Status display
 * renders these fields VERBATIM — no invented states (roadmap accept): the
 * 6-value status enum and 19-value failureKind enum are closed on the wire and
 * kept RAW here so unknown future values still display truthfully.
 */
data class McpStatuses(
    val statuses: Map<String, McpServerStatus>,
) {
    data class McpServerStatus(
        /** connecting|connected|disabled|disconnected|failed|untrusted (kept raw). */
        val status: String,
        /** "stdio" | "http" | "sse" (kept raw). */
        val transport: String,
        val toolCount: Int,
        val updatedAt: String,
        val error: String?,
        val failureKind: String?,
        /** "legacy" | "modern" (kept raw). */
        val protocolEra: String?,
    )

    companion object {
        fun decode(obj: JsonObject): McpStatuses {
            val record = obj.obj("statuses")
            return McpStatuses(
                statuses = record.keys.associateWith { name ->
                    val o = record.obj(name)
                    McpServerStatus(
                        status = o.strReq("status"),
                        transport = o.strReq("transport"),
                        toolCount = o.intReq("toolCount"),
                        updatedAt = o.strReq("updatedAt"),
                        error = o.strOrNullN("error"),
                        failureKind = o.strOrNullN("failureKind"),
                        protocolEra = o.strOrNullN("protocolEra"),
                    )
                },
            )
        }
    }
}
