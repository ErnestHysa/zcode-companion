package com.zcode.android.app.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.SkillCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * GAP-8 (android-native-gaps.md; docs/protocol/v4-wire.md §12.4) — the skills
 * catalog viewer rendered from the GENERATED fixtures. Pins: the catalog renders
 * name/description/source; the search box is LOCAL to the list and filters it;
 * the zero-skill empty state; and NO enable/disable affordance exists (the
 * reference catalog's `enabled` is literal true — the field is decoded nowhere).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class SkillsViewerUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun fixtureCatalog(name: String): SkillCatalog {
        val file = sequenceOf(File("."), File(".."), File("../.."), File("../../.."))
            .map { it.resolve("fixtures/protocol/phase3-wire-fixtures.json") }
            .firstOrNull { it.isFile } ?: error("phase3-wire-fixtures.json not found")
        val cases = Json.parseToJsonElement(file.readText()).jsonObject["cases"]!!
            as kotlinx.serialization.json.JsonArray
        val data = cases.map { it.jsonObject }
            .first { it["name"]!!.toString().trim('"') == name }["data"]!!.toString()
        return SkillCatalog.decode(Json.parseToJsonElement(data).jsonObject)
    }

    private fun fakePort(catalog: SkillCatalog?, error: String? = null) = object : SkillsPort {
        override suspend fun catalog(): SkillsPort.Result = SkillsPort.Result(catalog, error)
    }

    @Test
    fun rendersCatalog_fromFixture() {
        rule.setContent { ZCodeTheme { SkillsViewerScreen(port = fakePort(fixtureCatalog("skills-catalog-mixed"))) } }
        rule.onNodeWithTag("skills_list").assertIsDisplayed()
        rule.onNodeWithTag("skill_row_skill-1").assertIsDisplayed()
        rule.onNodeWithTag("skill_row_skill-3").assertExists()
        // source renders: scope (+ plugin name when plugin-sourced)
        rule.onNodeWithTag("skill_source_skill-3").assertExists()
        // NO enable/disable affordance (reference projection; enabled is literal true on the wire)
        rule.onAllNodesWithTag("skill_toggle").assertCountEquals(0)
    }

    @Test
    fun searchBox_filtersTheList_locally() {
        rule.setContent { ZCodeTheme { SkillsViewerScreen(port = fakePort(fixtureCatalog("skills-catalog-mixed"))) } }
        rule.onNodeWithTag("skills_search").performTextInput("release")
        rule.waitForIdle()
        rule.onAllNodesWithTag("skill_row_skill-2").assertCountEquals(1)
        rule.onAllNodesWithTag("skill_row_skill-1").assertCountEquals(0)
        rule.onAllNodesWithTag("skill_row_skill-3").assertCountEquals(0)
        // Case-insensitive: "GIT" matches the plugin skill's name.
        rule.onNodeWithTag("skills_search").performTextClearance()
        rule.onNodeWithTag("skills_search").performTextInput("git")
        rule.waitForIdle()
        rule.onAllNodesWithTag("skill_row_skill-3").assertCountEquals(1)
    }

    @Test
    fun emptyCatalog_rendersEmptyState() {
        rule.setContent { ZCodeTheme { SkillsViewerScreen(port = fakePort(fixtureCatalog("skills-catalog-empty"))) } }
        rule.onNodeWithTag("skills_empty").assertExists()
    }

    @Test
    fun portError_rendersUnavailableFace() {
        rule.setContent { ZCodeTheme { SkillsViewerScreen(port = fakePort(null, error = "refused")) } }
        rule.onNodeWithTag("skills_error").assertExists()
    }
}
