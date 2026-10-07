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
