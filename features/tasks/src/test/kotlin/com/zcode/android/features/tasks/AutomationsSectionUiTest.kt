package com.zcode.android.features.tasks

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.fetchSemanticsNodes
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.ids.WorkspaceIdentityKey
import com.zcode.android.domain.model.AutomationRow
import com.zcode.android.domain.model.OffPeakTaskRow
import com.zcode.android.domain.model.Task
import com.zcode.android.domain.model.TaskStatus
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.features.tasks.port.AutomationsPort
import com.zcode.android.features.tasks.ui.TaskListScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * GAP-6 (android-native-gaps.md; docs/protocol/v4-wire.md §12.2) — the Tasks-tab
 * AUTOMATIONS + OFF-PEAK read-only section, rendered from the GENERATED fixtures.
 * Pins: rows render with title/state/schedule; the zero-automation empty state;
 * and the DEFERRED-SCOPE gate — the section exposes NO write affordance (no
 * create/edit/delete/enable targets exist anywhere in the tree).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class AutomationsSectionUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun fixtureList(name: String, key: String) = sequenceOf(File("."), File(".."), File("../.."), File("../../.."))
        .map { it.resolve("fixtures/protocol/phase3-wire-fixtures.json") }
        .firstOrNull { it.isFile }!!
        .let { file ->
            val cases = Json.parseToJsonElement(file.readText()).jsonObject["cases"]!!
                as kotlinx.serialization.json.JsonArray
            val data = cases.map { it.jsonObject }
                .first { it["name"]!!.toString().trim('"') == name }["data"]!!
                .toString()
            when (key) {
                "automations" -> AutomationRow.decodeList(Json.parseToJsonElement(data).jsonObject)
                else -> OffPeakTaskRow.decodeList(Json.parseToJsonElement(data).jsonObject)
            }
        }

    @Suppress("UNCHECKED_CAST")
    private fun port(
        automations: List<AutomationRow>? = null,
        offPeak: List<OffPeakTaskRow>? = null,
        error: String? = null,
    ): AutomationsPort {
        val autos = automations
        val peaks = offPeak
        return object : AutomationsPort {
            override suspend fun lists(): AutomationsPort.Lists =
                AutomationsPort.Lists(autos, peaks, error)
        }
    }

    private fun setContent(p: AutomationsPort) {
        rule.setContent {
            ZCodeTheme {
                TaskListScreen(
                    state = TasksUiState(
                        phase = ConnectionPhase.CONNECTED,
                        loadState = TasksUiState.Load.READY,
                        groups = emptyList(),
                    ),
                    onTaskClick = {},
                    onOpenSettings = {},
                    onOpenConnection = {},
                    onRetry = {},
                    onRefresh = {},
                    automationsPort = p,
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun rendersAutomationsAndOffPeakRows_fromFixtures() {
        setContent(
            port(
                automations = fixtureList("automation-list-mixed", "automations"),
                offPeak = fixtureList("offpeak-list-mixed", "offpeak").filter { it.workspaceKey == "/synthetic/ws" },
            ),
        )
        rule.onNodeWithTag("automations_section").assertIsDisplayed()
        rule.onNodeWithTag("automation_row_auto-1").assertIsDisplayed()
        rule.onNodeWithText("Nightly dependency review", substring = true).assertExists()
        rule.onNodeWithText("Weekly report", substring = true).assertExists()
        rule.onNodeWithTag("offpeak_row_opt-1").assertIsDisplayed()
        rule.onNodeWithText("Idle-time refactor", substring = true).assertExists()
    }

    @Test
    fun zeroAutomations_rendersEmptyState() {
        setContent(port(automations = emptyList(), offPeak = emptyList()))
        rule.onNodeWithTag("automations_empty").assertExists()
    }

    @Test
    fun portError_rendersUnavailableFace() {
        setContent(port(error = "channel refused"))
        rule.onNodeWithTag("automations_error").assertExists()
    }

    @Test
    fun noWriteAffordance_existsAnywhereInTheSection() {
        setContent(
            port(
                automations = fixtureList("automation-list-mixed", "automations"),
                offPeak = fixtureList("offpeak-list-mixed", "offpeak"),
            ),
        )
        // Deferred scope (roadmap): NO create/edit/delete/enable/pause targets.
        listOf(
            "automation_create", "automation_edit", "automation_delete",
            "automation_enable", "automation_run", "offpeak_create", "offpeak_cancel",
        ).forEach { tag ->
            org.junit.Assert.assertEquals(0, rule.onAllNodesWithTag(tag).fetchSemanticsNodes().size)
        }
    }
}

// Small local aliases to keep the assertions readable without extra imports.
private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertExists() = assertExists()
