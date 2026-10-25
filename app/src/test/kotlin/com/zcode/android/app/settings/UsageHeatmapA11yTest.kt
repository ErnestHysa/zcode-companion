package com.zcode.android.app.settings

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.AppUsageSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * B3-a11y (Phase 2E): the Usage heatmap was HUE-ONLY (color intensity cells, no legend,
 * no cell semantics). M5.8's Connection Center legend is the pattern: a text-labelled
 * legend + a per-cell description so color is never the only signal (§30.6).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class UsageHeatmapA11yTest {

    @get:Rule
    val rule = createComposeRule()

    private fun fixtureUsage(name: String): AppUsageSnapshot {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { it.resolve("fixtures/protocol/phase3-wire-fixtures.json") }
            .firstOrNull { it.isFile } ?: error("phase3-wire-fixtures.json not found")
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        val data = root["cases"]!!.jsonArray
            .map { it.jsonObject }
            .first { it["name"]!!.toString().trim('"') == name }["data"]!!
            .toString()
        return AppUsageSnapshot.decode(Json.parseToJsonElement(data).jsonObject)
    }

    @Test
    fun `heatmap has a text legend and per-cell descriptions`() {
        rule.setContent {
            ZCodeTheme {
                UsageScreen(port = object : UsagePort {
                    override suspend fun appUsage(range: String) = UsagePort.Result(fixtureUsage("app-usage-full"), null)
                })
            }
        }
        rule.waitForIdle()
        assertTrue("a text legend must exist (M5.8 pattern)", rule.onAllNodesWithTag("usage_heatmap_legend").fetchSemanticsNodes().isNotEmpty())
        val cells = rule.onAllNodesWithTag("usage_heatmap_cell").fetchSemanticsNodes()
        assertTrue("heatmap cells must exist", cells.isNotEmpty())
        val undescribed = cells.count { n -> n.config.getOrNull(SemanticsProperties.ContentDescription).isNullOrEmpty() }
        assertTrue("every cell must carry a description (date+level, or 'No activity'), $undescribed undescribed", undescribed == 0)
        val dated = cells.count { n ->
            n.config.getOrNull(SemanticsProperties.ContentDescription)?.any { Regex("""\d{4}-\d{2}-\d{2}""").containsMatchIn(it) } == true
        }
        assertTrue("day cells describe their date + level (got $dated dated of ${cells.size})", dated > 0)
    }
}
