package com.zcode.android.app.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.PluginsView
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * GAP-7 (the project roadmap; docs/protocol/v4-wire.md §12.3) — the plugins
 * viewer rendered from the GENERATED fixtures. Pins: installed plugins render
 * with their enable/disable STATE (the §13.3 accept), the empty list face, and
 * the Deferred-scope gate — NO install/uninstall/update affordance exists
 * anywhere in the viewer tree (the grep-level check is additionally noted in
 * the phase summary; the transport's typed surface test pins INV-9).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class PluginsViewerUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun fixtureView(name: String): PluginsView {
        val file = sequenceOf(File("."), File(".."), File("../.."), File("../../.."))
            .map { it.resolve("fixtures/protocol/phase3-wire-fixtures.json") }
            .firstOrNull { it.isFile } ?: error("phase3-wire-fixtures.json not found")
        val cases = Json.parseToJsonElement(file.readText()).jsonObject["cases"]!!
            as kotlinx.serialization.json.JsonArray
        val data = cases.map { it.jsonObject }
            .first { it["name"]!!.toString().trim('"') == name }["data"]!!.toString()
        return PluginsView.decode(Json.parseToJsonElement(data).jsonObject)
    }

    private fun fakePort(view: PluginsView?, error: String? = null) = object : PluginsPort {
        override suspend fun plugins(): PluginsPort.Result = PluginsPort.Result(view, error)
    }

    @Test
    fun rendersPlugins_withEnabledState_fromFixture() {
        rule.setContent { ZCodeTheme { PluginsViewerScreen(port = fakePort(fixtureView("plugins-list-mixed"))) } }
        rule.onNodeWithTag("plugins_list").assertIsDisplayed()
        rule.onNodeWithTag("plugin_row_com.example.commit-aid").assertExists()
        rule.onAllNodesWithText("commit-aid", substring = true).assertCountEquals(2) // title + marketplace line
        rule.onAllNodesWithText("1.4.0", substring = true).assertCountEquals(1)
        rule.onNodeWithTag("plugin_row_builtin.core").assertExists()
        // The disable STATE renders (text tag carries enabled/disabled vocabulary).
        rule.onNodeWithTag("plugin_state_com.example.commit-aid").assertExists()
        rule.onNodeWithTag("plugin_state_builtin.core").assertExists()
        // NO write affordance (Deferred scope): no install/uninstall/update targets.
        listOf("plugin_install", "plugin_uninstall", "plugin_update", "plugin_enable", "plugin_configure")
            .forEach { tag -> rule.onAllNodesWithTag(tag).assertCountEquals(0) }
    }

    @Test
    fun emptyList_rendersEmptyState() {
        rule.setContent { ZCodeTheme { PluginsViewerScreen(port = fakePort(fixtureView("plugins-list-empty"))) } }
        rule.onNodeWithTag("plugins_empty").assertExists()
    }

    @Test
    fun portError_rendersUnavailableFace() {
        rule.setContent { ZCodeTheme { PluginsViewerScreen(port = fakePort(null, error = "refused")) } }
        rule.onNodeWithTag("plugins_error").assertExists()
    }
}