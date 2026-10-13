package com.zcode.android.app.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.AppUsageSnapshot
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
 * GAP-5 (android-native-gaps.md; docs/protocol/v4-wire.md §12.1) — the Settings →
 * Usage screen rendered from the GENERATED usage fixtures. Desktop parity
 * (AppUsagePanel.tsx): a lifetime summary strip (totalTokens / peakDay /
 * longestSession / current+longest streak — lines 138-156), the heatmap when
 * weeks exist (:86), the daily-model trend, the per-model list, the tools list;
 * an honest empty state when the snapshot carries zero activity; loading and
 * error faces. Interactions (range tabs, refresh) are covered by the
 * instrumented suite — these Robolectric tests pin RENDERED STATE.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class UsageScreenUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun fixtureUsage(name: String): AppUsageSnapshot {
        val file = sequenceOf(File("."), File(".."), File("../.."), File("../../.."))
            .map { it.resolve("fixtures/protocol/phase3-wire-fixtures.json") }
            .firstOrNull { it.isFile } ?: error("phase3-wire-fixtures.json not found")
        val cases = Json.parseToJsonElement(file.readText()).jsonObject["cases"]!!
            as kotlinx.serialization.json.JsonArray
        val data = cases.map { it.jsonObject }
            .first { it["name"]!!.toString().trim('"') == name }["data"]!!.toString()
        return AppUsageSnapshot.decode(Json.parseToJsonElement(data).jsonObject)
    }

    private fun fakePort(snapshot: AppUsageSnapshot?, error: String? = null) = object : UsagePort {
        override suspend fun appUsage(range: String): UsagePort.Result =
            UsagePort.Result(snapshot, error)
    }

    private fun renderedText(tag: String): String =
        renderedTextDeep(rule.onAllNodesWithTag(tag)[0].fetchSemanticsNode())

    /** The row tags sit on CONTAINERS — collect Text from the node + descendants. */
    private fun renderedTextDeep(node: androidx.compose.ui.semantics.SemanticsNode): String {
        val own = node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }
        return own.joinToString(" ") { it.text }.ifBlank {
            node.children.joinToString(" ") { renderedTextDeep(it) }
        }
    }

    @Test
    fun rendersAllFiveSections_fromFixture() {
        rule.setContent { ZCodeTheme { UsageScreen(port = fakePort(fixtureUsage("app-usage-full"))) } }
        rule.onNodeWithTag("usage_summary").assertExists()
        rule.onNodeWithTag("usage_heatmap").assertExists()
        rule.onNodeWithTag("usage_daily").assertExists()
        rule.onNodeWithTag("usage_models").assertExists()
        rule.onNodeWithTag("usage_tools").assertExists()
        // Compact token formatting (formatCompactTokenCount port): 184600 → "184.6K".
        assertEquals("184.6K", renderedText("usage_total_tokens"))
        // 51200 → "51.2K" peak day card.
        assertEquals("51.2K", renderedText("usage_peak_day"))
        // Per-model rows: the favorite model id renders with its share.
        val modelsText = renderedText("usage_model_row_glm-5.3")
        assertTrue(
            "share missing from model row: $modelsText",
            "62%" in modelsText || "0.62" in modelsText || "62.0%" in modelsText,
        )
        // Tools rows render the tool name verbatim.
        rule.onNodeWithTag("usage_tool_row_Bash").assertExists()
    }

    @Test
    fun zeroSnapshot_rendersHonestEmptyState_andNoSections() {
        rule.setContent { ZCodeTheme { UsageScreen(port = fakePort(fixtureUsage("app-usage-zero"))) } }
        rule.onNodeWithTag("usage_empty").assertExists()
        rule.onAllNodesWithTag("usage_heatmap").assertCountEquals(0)
        rule.onAllNodesWithTag("usage_daily").assertCountEquals(0)
        rule.onAllNodesWithTag("usage_models").assertCountEquals(0)
        rule.onAllNodesWithTag("usage_tools").assertCountEquals(0)
    }

    @Test
    fun portError_rendersErrorFace_notABlankScreen() {
        rule.setContent { ZCodeTheme { UsageScreen(port = fakePort(null, error = "channel refused")) } }
        rule.onNodeWithTag("usage_error").assertExists()
    }

    @Test
    fun loadingFace_rendersWhileTheReadIsInFlight() {
        rule.setContent {
            ZCodeTheme {
                UsageScreen(
                    port = object : UsagePort {
                        override suspend fun appUsage(range: String): UsagePort.Result =
                            kotlinx.coroutines.awaitCancellation()
                    },
                )
            }
        }
        rule.onNodeWithTag("usage_loading").assertExists()
    }
}
