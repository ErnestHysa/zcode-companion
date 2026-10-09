package com.zcode.android.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GAP-1/GAP-2 (the project roadmap; docs/protocol/v4-wire.md §11.1) — the
 * snapshot `usage` state key decoded from the GENERATED golden fixtures
 * (fixtures/protocol/phase2-wire-fixtures.json; rule 5 — generated, never
 * hand-written). Pins:
 *  - the fixture payload decodes into [com.zcode.android.domain.model.SessionUsageState]
 *    with used/size/threshold + the 7-source breakdown + cumulative tokens;
 *  - `contextWindow: null` decodes to a PRESENT key with no window data;
 *  - absent cache/breakdown stay absent (no invention);
 *  - the §9 key-level merge: whole replacement on `state.updated`, absence keeps
 *    the prior value (INV-8), a malformed KNOWN key drops THAT KEY + counts;
 *  - unknown breakdown sources are retained raw (INV-8), never guessed;
 *  - the desktop renderable-usage gate (contextUsage.tsx getRenderableTaskUsage):
 *    used <= 0 || size <= 0 is NOT renderable.
 */
class SessionUsageStateTest {

    private fun fixture(name: String): kotlinx.serialization.json.JsonObject {
        val file = sequenceOf(File("."), File(".."), File("../.."))
            .map { it.resolve("fixtures/protocol/phase2-wire-fixtures.json") }
            .firstOrNull { it.isFile }
            ?: error("phase2-wire-fixtures.json not found")
        val cases = Json.parseToJsonElement(file.readText()).jsonObject["cases"]!!
            as kotlinx.serialization.json.JsonArray
        return cases.map { it.jsonObject }
            .first { it["name"]!!.toString().trim('"') == name }["data"]!!.jsonObject
    }

    private val model = com.zcode.android.domain.model.SessionUsageState

    @Test
    fun usageFullFixtureDecodes_usedSizeThresholdAndSevenSources() {
        val usage = model.decode(fixture("usage-full"))
        val window = usage.contextWindow
        assertTrue(window != null, "contextWindow present")
        assertEquals(148234L, window!!.usedTokens)
        assertEquals(1_000_000L, window.maxTokens)
        assertEquals(800_000L, window.autoCompactThresholdTokens)
        assertEquals(7, window.breakdown.size)
        assertEquals("system_prompt" to 12000L, window.breakdown[0].source to window.breakdown[0].chars)
        assertEquals("messages" to 106034L, window.breakdown.last().source to window.breakdown.last().chars)
        assertEquals(1_704_000L, usage.cumulative?.inputTokens)
        assertEquals(42_300L, usage.cumulative?.outputTokens)
    }

    @Test
    fun nullContextWindow_isPresentKeyWithNoWindowData() {
        val usage = model.decode(fixture("usage-null-window"))
        assertNull(usage.contextWindow, "contextWindow: null decodes to null (key PRESENT)")
        assertEquals(4200L, usage.cumulative?.inputTokens)
    }

    @Test
    fun minimalFixture_absentOptionalFieldsStayAbsent() {
        val usage = model.decode(fixture("usage-minimal"))
        val window = usage.contextWindow!!
        assertEquals(500L, window.usedTokens)
        assertEquals(200_000L, window.maxTokens)
        assertNull(window.autoCompactThresholdTokens)
        assertTrue(window.breakdown.isEmpty())
    }

    @Test
    fun snapshotMerge_usageKeyWholeReplaces() {
        val usage = model.decode(fixture("usage-full"))
        val patch = kotlinx.serialization.json.buildJsonObject {
            put("usage", kotlinx.serialization.json.Json.parseToJsonElement(fixture("usage-full").toString()))
        }
        val merged = com.zcode.android.domain.model.SessionProjectionState(revision = 7).mergedWith(patch)
        assertEquals(148234L, merged.usage?.contextWindow?.usedTokens)
        assertEquals(7L, merged.revision, "unrelated keys untouched")
    }

    @Test
    fun snapshotMerge_absentUsageKey_keepsPriorValue_inAbsenceTolerance() {
        val usage = model.decode(fixture("usage-full"))
        val withUsage = com.zcode.android.domain.model.SessionProjectionState(
            revision = 7,
            usage = usage,
        )
        // Conflation (snapshot.ts:188 "值未变不下发"): patches without the key are NORMAL.
        val merged = withUsage.mergedWith(
            kotlinx.serialization.json.buildJsonObject {
                put("revision", kotlinx.serialization.json.JsonPrimitive(9))
            },
        )
        assertEquals(9L, merged.revision)
        assertEquals(148234L, merged.usage?.contextWindow?.usedTokens, "absent usage key keeps prior (INV-8)")
    }

    @Test
    fun fromSnapshot_usageOnlySnapshot_decodesUsage() {
        val snapshot = kotlinx.serialization.json.buildJsonObject {
            put("usage", kotlinx.serialization.json.Json.parseToJsonElement(fixture("usage-full").toString()))
        }
        val state = com.zcode.android.domain.model.SessionProjectionState.fromSnapshot(snapshot)
        assertEquals(148234L, state.usage?.contextWindow?.usedTokens)
        assertTrue(state.hasDecodedStateKeys, "usage-only snapshot counts as a decoded state block")
    }

    @Test
    fun fromSnapshot_withoutUsageKey_usageStaysNull() {
        val snapshot = kotlinx.serialization.json.buildJsonObject {
            put("revision", kotlinx.serialization.json.JsonPrimitive(3))
        }
        val state = com.zcode.android.domain.model.SessionProjectionState.fromSnapshot(snapshot)
        assertNull(state.usage, "no usage key → null usage (INV-8 absence)")
        assertEquals(3L, state.revision)
    }

    @Test
    fun mergedWith_malformedUsageKey_dropsKeyAndCounts() {
        val before = com.zcode.android.domain.model.SessionProjectionState.malformedKeyDrops
        val merged = com.zcode.android.domain.model.SessionProjectionState().mergedWith(
            kotlinx.serialization.json.buildJsonObject {
                put("usage", kotlinx.serialization.json.JsonPrimitive("not-an-object"))
            },
        )
        assertNull(merged.usage, "malformed KNOWN key drops THAT KEY (§9 policy)")
        assertTrue(
            com.zcode.android.domain.model.SessionProjectionState.malformedKeyDrops > before,
            "drop is COUNTED (INV-8 observability)",
        )
    }

    @Test
    fun unknownBreakdownSource_retainedRaw_neverGuessed() {
        val usage = model.decode(
            kotlinx.serialization.json.buildJsonObject {
                put("contextWindow", kotlinx.serialization.json.buildJsonObject {
                    put("usedTokens", kotlinx.serialization.json.JsonPrimitive(10))
                    put("maxTokens", kotlinx.serialization.json.JsonPrimitive(100))
                    put("autoCompactThresholdTokens", kotlinx.serialization.json.JsonNull)
                    put("breakdown", kotlinx.serialization.json.buildJsonArray {
                        add(kotlinx.serialization.json.buildJsonObject {
                            put("source", kotlinx.serialization.json.JsonPrimitive("future_source"))
                            put("chars", kotlinx.serialization.json.JsonPrimitive(99))
                        })
                    })
                })
                put("cumulative", kotlinx.serialization.json.buildJsonObject {
                    put("inputTokens", kotlinx.serialization.json.JsonPrimitive(0))
                    put("outputTokens", kotlinx.serialization.json.JsonPrimitive(0))
                    put("cacheReadTokens", kotlinx.serialization.json.JsonPrimitive(0))
                    put("cacheWriteTokens", kotlinx.serialization.json.JsonPrimitive(0))
                })
            },
        )
        assertEquals("future_source", usage.contextWindow?.breakdown?.single()?.source)
    }

    @Test
    fun renderableGate_usedOrSizeNonPositive_isNotRenderable() {
        // Desktop parity (contextUsage.tsx:209-220): used=0 is an init/exception
        // fallback — never rendered as a misleading 0%.
        fun usageWith(used: Long, size: Long) = model.decode(
            kotlinx.serialization.json.buildJsonObject {
                put("contextWindow", kotlinx.serialization.json.buildJsonObject {
                    put("usedTokens", kotlinx.serialization.json.JsonPrimitive(used))
                    put("maxTokens", kotlinx.serialization.json.JsonPrimitive(size))
                    put("autoCompactThresholdTokens", kotlinx.serialization.json.JsonNull)
                })
                put("cumulative", kotlinx.serialization.json.buildJsonObject {
                    put("inputTokens", kotlinx.serialization.json.JsonPrimitive(0))
                    put("outputTokens", kotlinx.serialization.json.JsonPrimitive(0))
                    put("cacheReadTokens", kotlinx.serialization.json.JsonPrimitive(0))
                    put("cacheWriteTokens", kotlinx.serialization.json.JsonPrimitive(0))
                })
            },
        )
        assertTrue(usageWith(148234, 1_000_000).renderable)
        assertTrue(!usageWith(0, 1_000_000).renderable, "used=0 must not render (desktop gate)")
        assertTrue(!usageWith(148234, 0).renderable, "size=0 must not render")
        assertTrue(!usageWith(-5, 1_000_000).renderable)
    }

    @Test
    fun breakdownSegments_desktopAggregationOrder() {
        // Desktop buildContextUsageBreakdownSegments (contextUsage.tsx:166-202):
        // chars <= 0 dropped, per-source aggregation, percent = chars/total,
        // sort chars DESC with a stable source-order tiebreak.
        val usage = model.decode(
            kotlinx.serialization.json.buildJsonObject {
                put("contextWindow", kotlinx.serialization.json.buildJsonObject {
                    put("usedTokens", kotlinx.serialization.json.JsonPrimitive(1000))
                    put("maxTokens", kotlinx.serialization.json.JsonPrimitive(2000))
                    put("autoCompactThresholdTokens", kotlinx.serialization.json.JsonNull)
                    put("breakdown", kotlinx.serialization.json.buildJsonArray {
                        add(kotlinx.serialization.json.buildJsonObject {
                            put("source", kotlinx.serialization.json.JsonPrimitive("messages"))
                            put("chars", kotlinx.serialization.json.JsonPrimitive(500))
                        })
                        add(kotlinx.serialization.json.buildJsonObject {
                            put("source", kotlinx.serialization.json.JsonPrimitive("skills"))
                            put("chars", kotlinx.serialization.json.JsonPrimitive(250))
                        })
                        add(kotlinx.serialization.json.buildJsonObject {
                            put("source", kotlinx.serialization.json.JsonPrimitive("system_prompt"))
                            put("chars", kotlinx.serialization.json.JsonPrimitive(250))
                        })
                        add(kotlinx.serialization.json.buildJsonObject {
                            put("source", kotlinx.serialization.json.JsonPrimitive("tool_prompt"))
                            put("chars", kotlinx.serialization.json.JsonPrimitive(0))
                        })
                    })
                })
                put("cumulative", kotlinx.serialization.json.buildJsonObject {
                    put("inputTokens", kotlinx.serialization.json.JsonPrimitive(0))
                    put("outputTokens", kotlinx.serialization.json.JsonPrimitive(0))
                    put("cacheReadTokens", kotlinx.serialization.json.JsonPrimitive(0))
                    put("cacheWriteTokens", kotlinx.serialization.json.JsonPrimitive(0))
                })
            },
        )
        val segments = usage.contextWindowBreakdownSegments
        assertEquals(3, segments.size, "chars <= 0 dropped")
        assertEquals("messages", segments[0].source)
        assertEquals(0.5, segments[0].percent, 1e-9)
        // Tie at 250 chars: stable source-order tiebreak (system_prompt=1 before skills=3).
        assertEquals("system_prompt", segments[1].source)
        assertEquals("skills", segments[2].source)
    }
}
