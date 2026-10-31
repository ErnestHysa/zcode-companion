package com.zcode.android.domain.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * G20 (vc51) — the coding-plan usage snapshot's CHART faces
 * (usage-stats.ts CodingPlanUsageSnapshot): the per-day stacked-tokens series
 * the desktop's CodingPlanUsageBarChart renders and the total-tokens trend
 * its CodingPlanUsageLineChart draws. Only the chart faces are decoded (the
 * activity/health/detail blocks ride the raw object, INV-8).
 */
data class CodingPlanUsageSnapshot(
    val range: String,
    val rangeStartDate: String?,
    val rangeEndDate: String?,
    val xTime: List<String>,
    val models: List<ModelSeries>,
) {
    /** One model's per-day token series (the stacked bar's band + the legend). */
    data class ModelSeries(
        val modelName: String,
        val tokensUsage: List<Long>,
        val totalTokens: Long,
    )

    /** The per-day TOTALS across models — the line chart's series. */
    val dailyTotals: List<Long>
        get() = xTime.indices.map { i -> models.sumOf { it.tokensUsage.getOrNull(i) ?: 0L } }

    companion object {
        fun decode(obj: JsonObject): CodingPlanUsageSnapshot {
            val modelUsage = obj.objOrNull("modelUsage")
                ?: throw RowDecodeException("codingPlanUsage.modelUsage missing")
            val xTime = (modelUsage["xTime"] as? JsonArray)
                ?.mapNotNull { el -> (el as? kotlinx.serialization.json.JsonPrimitive)?.content }
                ?: throw RowDecodeException("modelUsage.xTime wrong type")
            val models = (modelUsage["modelDataList"] as? JsonArray)
                ?.mapNotNull { el -> el as? JsonObject }
                ?.mapNotNull { m ->
                    val name = m.strOrNull("modelName") ?: return@mapNotNull null
                    val tokens = (m["tokensUsage"] as? JsonArray)
                        ?.mapNotNull { t -> (t as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() }
                        ?: emptyList()
                    ModelSeries(
                        modelName = name,
                        tokensUsage = tokens,
                        totalTokens = m.longOrNullN("totalTokens") ?: tokens.sum(),
                    )
                }
                ?: emptyList()
            return CodingPlanUsageSnapshot(
                range = obj.strOrNull("range") ?: "",
                rangeStartDate = obj.strOrNull("rangeStartDate"),
                rangeEndDate = obj.strOrNull("rangeEndDate"),
                xTime = xTime,
                models = models,
            )
        }
    }
}
