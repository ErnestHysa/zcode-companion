package com.zcode.android.domain.model

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * G20 (vc51) — the coding-plan usage snapshot's CHART faces
 * (usage-stats.ts CodingPlanUsageSnapshot.modelUsage): the per-model daily
 * series decode and the daily-totals derivation the two plan charts render.
 * A malformed KNOWN face fails closed (RowDecodeException) — the section
 * hides, never a guessed chart.
 */
class CodingPlanUsageSnapshotTest {

    private fun snapshot(vararg models: Pair<String, List<Long>>) = buildJsonObject {
        val days = models.maxOfOrNull { it.second.size } ?: 0
        put("range", JsonPrimitive("7d"))
        put("rangeStartDate", JsonPrimitive("2026-10-25"))
        put("rangeEndDate", JsonPrimitive("2026-10-31"))
        putJsonObject("modelUsage") {
            putJsonArray("xTime") { (1..days).forEach { add(JsonPrimitive("day-$it")) } }
            putJsonArray("modelDataList") {
                models.forEach { (name, series) ->
                    add(buildJsonObject {
                        put("modelName", JsonPrimitive(name))
                        putJsonArray("tokensUsage") { series.forEach { add(JsonPrimitive(it)) } }
                        put("totalTokens", JsonPrimitive(series.sum()))
                    })
                }
            }
        }
    }

    @Test
    fun `the chart faces decode - per-model series plus the x axis`() {
        val snap = CodingPlanUsageSnapshot.decode(
            snapshot("glm-5.1" to listOf(100L, 200L, 0L, 50L, 0L, 0L, 10L)),
        )
        assertEquals("7d", snap.range)
        assertEquals(7, snap.xTime.size) // the first pin passes a 7-slot series
        assertEquals(1, snap.models.size)
        assertEquals("glm-5.1", snap.models.single().modelName)
        assertEquals(listOf(100L, 200L, 0L, 50L, 0L, 0L, 10L), snap.models.single().tokensUsage)
        assertEquals(360L, snap.models.single().totalTokens)
    }

    @Test
    fun `dailyTotals sums the models per day - the line chart's series`() {
        val snap = CodingPlanUsageSnapshot.decode(
            snapshot(
                "glm-5.1" to listOf(100L, 0L, 30L),
                "glm-4.6" to listOf(50L, 70L, 0L),
            ),
        )
        assertEquals(listOf(150L, 70L, 30L), snap.dailyTotals)
    }

    @Test
    fun `a missing modelUsage face fails closed`() {
        assertFailsWith<RowDecodeException> {
            CodingPlanUsageSnapshot.decode(buildJsonObject { put("range", JsonPrimitive("7d")) })
        }
    }

    @Test
    fun `a malformed model entry drops that entry only (INV-8)`() {
        val raw = buildJsonObject {
            putJsonObject("modelUsage") {
                putJsonArray("xTime") { add(JsonPrimitive("d1")) }
                putJsonArray("modelDataList") {
                    add(buildJsonObject { put("nope", JsonPrimitive(1)) })
                    add(buildJsonObject {
                        put("modelName", JsonPrimitive("glm-5.1"))
                        putJsonArray("tokensUsage") { add(JsonPrimitive(5L)) }
                    })
                }
            }
        }
        val snap = CodingPlanUsageSnapshot.decode(raw)
        assertEquals(listOf("glm-5.1"), snap.models.map { it.modelName })
        assertTrue(snap.dailyTotals == listOf(5L))
    }
}
