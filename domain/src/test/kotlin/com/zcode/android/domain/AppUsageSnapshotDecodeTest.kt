package com.zcode.android.domain

import com.zcode.android.domain.model.RowDecodeException
import com.zcode.android.domain.model.AppUsageSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * GAP-5 (android-native-gaps.md; docs/protocol/v4-wire.md §12.1) — the app usage
 * snapshot decode, pinned to the GENERATED fixtures (rule 5). Shape fidelity:
 * exact field values from the reference-validated payload, the nullable faces
 * (favoriteModel / avg durations / heatmap day cells), the literal "agent-db"
 * source, and the INV-8 decode policy (required key missing → typed fault;
 * unknown fields tolerated — the source schema is a NON-strict z.object).
 */
class AppUsageSnapshotDecodeTest {

    private fun fixture(name: String) = sequenceOf(File("."), File(".."), File("../.."), File("../../.."))
        .map { it.resolve("fixtures/protocol/phase3-wire-fixtures.json") }
        .firstOrNull { it.isFile }
        ?.let { file ->
            val cases = Json.parseToJsonElement(file.readText()).jsonObject["cases"]!!
                as kotlinx.serialization.json.JsonArray
            cases.map { it.jsonObject }
                .first { it["name"]!!.toString().trim('"') == name }["data"]!!.toString()
        }
        ?.let { Json.parseToJsonElement(it).jsonObject }
        ?: error("phase3-wire-fixtures.json not found")

    @Test
    fun decodesFullSnapshot_matchingFixtureValues() {
        val s = AppUsageSnapshot.decode(fixture("app-usage-full"))
        assertEquals("7d", s.range)
        assertEquals(1791440000000L, s.generatedAt)
        assertEquals("Europe/London", s.timeZone)
        assertEquals("agent-db", s.source)
        // summary — exact values from the fixture
        assertEquals(184600L, s.summary.totalTokens)
        assertEquals(120000L, s.summary.inputTokens)
        assertEquals(42000L, s.summary.outputTokens)
        assertEquals(8600L, s.summary.reasoningTokens)
        assertEquals(0.42, s.summary.cacheHitRate, 1e-9)
        assertEquals(14, s.summary.totalSessions)
        assertEquals(96, s.summary.totalTurns)
        assertEquals(210, s.summary.toolCallCount)
        assertEquals(1420L, s.summary.avgTimeToFirstTokenMs)
        assertEquals(41000L, s.summary.avgTurnDurationMs)
        assertEquals(5, s.summary.activeDays)
        assertEquals(3, s.summary.currentStreakDays)
        assertEquals(5400000L, s.summary.longestSessionMs)
        assertEquals(9, s.summary.longestStreakDays)
        assertEquals(51200L, s.summary.peakDayTokens)
        assertEquals("glm-5.3", s.summary.favoriteModel?.modelId)
        assertEquals(114452L, s.summary.favoriteModel?.totalTokens)
        assertEquals(0.62, s.summary.favoriteModel?.share ?: 0.0, 1e-9)
        // heatmap — nullable day cells preserved, level enum intact
        assertEquals(2, s.heatmap.weeks.size)
        assertEquals("2026-09-28", s.heatmap.startDate)
        assertEquals(51200L, s.heatmap.maxTokens)
        val week1 = s.heatmap.weeks[0]
        assertEquals(0, week1.weekIndex)
        assertEquals(7, week1.days.size)
        assertNull(week1.days[2])
        assertEquals("2026-09-29", week1.days[1]!!.date)
        assertEquals(3, week1.days[1]!!.level)
        assertEquals(30100L, week1.days[1]!!.totalTokens)
        assertEquals(48, week1.days[1]!!.toolCallCount)
        // dailyModelUsage — null modelId tolerated (unattributed tokens)
        assertEquals(2, s.dailyModelUsage.size)
        assertEquals("2026-10-06", s.dailyModelUsage[1].date)
        assertTrue(s.dailyModelUsage[1].models.any { it.modelId == null && it.totalTokens == 3900L })
        // models + tools
        assertEquals(2, s.models.size)
        assertEquals("glm-5.3-flash", s.models[1].modelId)
        assertEquals(420, s.models[0].requestCount)
        assertEquals(0.38, s.models[1].share, 1e-9)
        assertEquals(2, s.tools.size)
        assertEquals("Bash", s.tools[0].toolName)
        assertEquals(120, s.tools[0].callCount)
        assertEquals(6, s.tools[0].errorCount)
        assertEquals(3200L, s.tools[0].avgDurationMs)
        assertEquals("Read", s.tools[1].toolName)
        assertEquals(0, s.tools[1].errorCount)
        assertEquals(0.0, s.tools[1].errorRate, 1e-9)
        assertEquals(210L, s.tools[1].avgDurationMs)
    }

    @Test
    fun decodesZeroSnapshot_honestZeroFace() {
        val s = AppUsageSnapshot.decode(fixture("app-usage-zero"))
        assertEquals("all", s.range)
        assertEquals(0L, s.summary.totalTokens)
        assertNull(s.summary.favoriteModel)
        assertNull(s.summary.avgTimeToFirstTokenMs)
        assertNull(s.summary.avgTurnDurationMs)
        assertEquals(0, s.summary.activeDays)
        assertEquals(0, s.summary.currentStreakDays)
        assertTrue(s.heatmap.weeks.isEmpty())
        assertNull(s.heatmap.startDate)
        assertTrue(s.dailyModelUsage.isEmpty())
        assertTrue(s.models.isEmpty())
        assertTrue(s.tools.isEmpty())
    }

    @Test
    fun missingRequiredSummary_throwsTypedFault() {
        val broken = fixture("app-usage-full").toMutableMap().apply { remove("summary") }
        try {
            AppUsageSnapshot.decode(kotlinx.serialization.json.JsonObject(broken))
            throw AssertionError("expected RowDecodeException for missing summary")
        } catch (e: RowDecodeException) {
            assertTrue("names the key: ${e.message}", e.message!!.contains("summary"))
        }
    }

    @Test
    fun unknownFields_areTolerated() {
        // The source schema (appUsageSnapshotSchema) is a NON-strict z.object —
        // additive fields arrive on newer hosts and must decode (INV-8).
        val withFutureField = kotlinx.serialization.json.buildJsonObject {
            fixture("app-usage-full").forEach { (k, v) -> put(k, v) }
            put("someFutureField", JsonPrimitive(42))
        }
        val s = AppUsageSnapshot.decode(withFutureField)
        assertEquals(184600L, s.summary.totalTokens)
    }
}
