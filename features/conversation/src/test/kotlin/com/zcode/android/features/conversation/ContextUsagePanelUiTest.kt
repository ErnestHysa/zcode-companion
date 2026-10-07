package com.zcode.android.features.conversation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.features.conversation.ui.ContextUsagePanel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * GAP-1/GAP-2 (android-native-gaps.md; docs/protocol/v4-wire.md §11.1) — the
 * conversation-header context panel rendered from the GENERATED usage fixtures.
 * Desktop parity (contextUsage.tsx): the label is `used/size (pct%)` with compact
 * token numbers (formatCompactTokenNumber) and a 1-fraction-digit percent; the
 * breakdown renders per-source segments when present; used<=0/size<=0 renders
 * NOTHING (getRenderableTaskUsage gate — never a misleading 0%).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class ContextUsagePanelUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun fixtureUsage(name: String): com.zcode.android.domain.model.SessionUsageState {
        val file = sequenceOf(File("."), File(".."), File("../.."), File("../../.."))
            .map { it.resolve("fixtures/protocol/phase2-wire-fixtures.json") }
            .firstOrNull { it.isFile }
            ?: error("phase2-wire-fixtures.json not found")
        val cases = Json.parseToJsonElement(file.readText()).jsonObject["cases"]!!
            as kotlinx.serialization.json.JsonArray
        val data = cases.map { it.jsonObject }
            .first { it["name"]!!.toString().trim('"') == name }["data"]!!.toString()
        return com.zcode.android.domain.model.SessionUsageState.decode(
            Json.parseToJsonElement(data).jsonObject,
        )
    }

    private fun renderedText(tag: String): String =
        rule.onAllNodesWithTag(tag)[0].fetchSemanticsNode().config.get(SemanticsProperties.Text).single().text

    @Test
    fun rendersUsedSizePercent_matchingFixture() {
        val usage = fixtureUsage("usage-full")
        rule.setContent { ZCodeTheme { ContextUsagePanel(usage = usage) } }
        rule.onNodeWithTag("context_usage_label").assertExists()
        // 148234/1000000 → compact "148.2K / 1M (14.8%)" (desktop formatter port).
        val text = renderedText("context_usage_label")
        assertTrue("compact used tokens in label, got: $text", "148.2K" in text)
        assertTrue("compact size tokens in label, got: $text", "1M" in text)
        assertTrue("1-fraction-digit percent in label, got: $text", "14.8%" in text)
    }

    @Test
    fun breakdownSegments_renderPerSourceEstimates() {
        val usage = fixtureUsage("usage-full")
        rule.setContent { ZCodeTheme { ContextUsagePanel(usage = usage) } }
        // GAP-2: one row per aggregated source — 7 sources in the fixture, sorted
        // chars-desc (messages first at 106034).
        val rows = rule.onAllNodesWithTag("context_usage_breakdown_row").fetchSemanticsNodes()
        assertEquals(7, rows.size)
        val first = rows.first().config.get(SemanticsProperties.Text).single().text
        assertTrue("largest source first (chars desc), got: $first", "messages" in first)
    }

    @Test
    fun noBreakdown_panelValidWithLabelOnly() {
        val usage = fixtureUsage("usage-minimal")
        rule.setContent { ZCodeTheme { ContextUsagePanel(usage = usage) } }
        rule.onNodeWithTag("context_usage_label").assertExists()
        // Absent breakdown leaves the GAP-1 panel valid.
        rule.onAllNodesWithTag("context_usage_breakdown_row").assertCountEquals(0)
    }

    @Test
    fun nullUsageOrNonRenderable_rendersNothing() {
        rule.setContent { ZCodeTheme { ContextUsagePanel(usage = null) } }
        rule.onAllNodesWithTag("context_usage_panel").assertCountEquals(0)
        // Desktop gate: used=0 is an init fallback, never a 0% meter.
        val zero = fixtureUsage("usage-minimal").let {
            it.copy(contextWindow = it.contextWindow?.copy(usedTokens = 0L))
        }
        rule.setContent { ZCodeTheme { ContextUsagePanel(usage = zero) } }
        rule.onAllNodesWithTag("context_usage_panel").assertCountEquals(0)
    }
}
