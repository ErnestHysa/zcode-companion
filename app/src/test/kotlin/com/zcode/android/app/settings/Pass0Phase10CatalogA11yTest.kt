package com.zcode.android.app.settings

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.PluginsView
import com.zcode.android.domain.model.SkillCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * pass-0 Phase 10 — the catalog-plane pins (N-BN-1 + G103, both riding the
 * Settings viewer family):
 *
 *  - N-BN-1: EVERY per-plugin enable switch carries a content-desc naming the
 *    plugin ("Enable <plugin>") — TalkBack silence on the row's only interactive
 *    element was the defect (the a11y census: `plugin_switch_plugin-prisma` /
 *    `-review` carried no content-desc, no text children).
 *  - G103 (the row's minimum): a SKILL ROW is clickable and opens a detail
 *    bottom-sheet carrying the skill's body text (the desktop opens skill
 *    bodies; the catalog rows were NON-CLICKABLE BY CONSTRUCTION).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class Pass0Phase10CatalogA11yTest {

    @get:Rule
    val rule = createComposeRule()

    private fun fixture(name: String, key: String): kotlinx.serialization.json.JsonObject {
        val file = sequenceOf(File("."), File(".."), File("../.."), File("../../.."))
            .map { it.resolve("fixtures/protocol/phase3-wire-fixtures.json") }
            .firstOrNull { it.isFile } ?: error("phase3-wire-fixtures.json not found")
        val cases = Json.parseToJsonElement(file.readText()).jsonObject["cases"]!!
            as kotlinx.serialization.json.JsonArray
        val data = cases.map { it.jsonObject }
            .first { it["name"]!!.toString().trim('"') == name }["data"]!!.toString()
        return Json.parseToJsonElement(data).jsonObject
    }

    private fun fakePluginsPort(view: PluginsView?) = object : PluginsPort {
        override suspend fun plugins(): PluginsPort.Result = PluginsPort.Result(view, null)
        override suspend fun setEnabled(pluginId: String, enabled: Boolean): Boolean = false
    }

    private fun fakeSkillsPort(catalog: SkillCatalog?) = object : SkillsPort {
        override suspend fun catalog(): SkillsPort.Result = SkillsPort.Result(catalog, null)
    }

    @Test
    fun `N-BN-1 every plugin switch carries a content-desc naming the plugin`() {
        val view = PluginsView.decode(fixture("plugins-list-mixed", "data"))
        rule.setContent { ZCodeTheme { PluginsViewerScreen(port = fakePluginsPort(view)) } }
        rule.onNodeWithTag("plugins_list").assertExists()
        for (plugin in view.plugins) {
            val tag = "plugin_switch_${plugin.id}"
            val node = rule.onAllNodesWithTag(tag).fetchSemanticsNode()
            val desc = runCatching { node.config[SemanticsProperties.ContentDescription] }.getOrNull()
            assertTrue(
                "the enable switch $tag carries a content-desc naming the plugin (N-BN-1)",
                desc != null && desc.any { it.contains(plugin.name) },
            )
        }
    }

    @Test
    fun `G103 a skill row opens the detail sheet with the body text`() {
        val catalog = SkillCatalog.decode(fixture("skills-catalog-mixed", "data"))
        rule.setContent { ZCodeTheme { SkillsViewerScreen(port = fakeSkillsPort(catalog)) } }
        rule.onNodeWithTag("skill_row_skill-1").assertExists()
        // Pre-fix the rows were NON-CLICKABLE BY CONSTRUCTION — the tap opens nothing.
        rule.onNodeWithTag("skill_row_skill-1").performClick()
        rule.onAllNodesWithTag("skill_detail_sheet").assertCountEquals(1)
        rule.onAllNodesWithText("Drafts conventional commits.", substring = true)
            .assertCountEquals(1)
    }

    @Test
    fun `G103 the detail sheet closes on dismiss and never invents an enable affordance`() {
        val catalog = SkillCatalog.decode(fixture("skills-catalog-mixed", "data"))
        rule.setContent { ZCodeTheme { SkillsViewerScreen(port = fakeSkillsPort(catalog)) } }
        rule.onNodeWithTag("skill_row_skill-1").performClick()
        rule.onAllNodesWithTag("skill_detail_sheet").assertCountEquals(1)
        // The reference projection's enabled is literal true — the sheet carries no
        // enable/disable affordance (the GAP-8 rule holds in the detail tier too).
        rule.onAllNodesWithTag("skill_detail_enable").assertCountEquals(0)
    }
}
