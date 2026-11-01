package com.zcode.android.features.tasks

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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

    private fun fixtureAutomations(name: String): List<AutomationRow> {
        val data = fixtureData(name)
        return AutomationRow.decodeList(Json.parseToJsonElement(data).jsonObject)
    }

    private fun fixtureOffPeak(name: String): List<OffPeakTaskRow> {
        val data = fixtureData(name)
        return OffPeakTaskRow.decodeList(Json.parseToJsonElement(data).jsonObject)
    }

    private fun fixtureData(name: String): String =
        sequenceOf(File("."), File(".."), File("../.."), File("../../.."))
            .map { it.resolve("fixtures/protocol/phase3-wire-fixtures.json") }
            .firstOrNull { it.isFile }!!
            .let { file ->
                val cases = Json.parseToJsonElement(file.readText()).jsonObject["cases"]!!
                    as kotlinx.serialization.json.JsonArray
                cases.map { it.jsonObject }
                    .first { it["name"]!!.toString().trim('"') == name }["data"]!!
                    .toString()
            }

    private fun port(
        automations: List<AutomationRow>? = null,
        offPeak: List<OffPeakTaskRow>? = null,
        error: String? = null,
    ): AutomationsPort = portWithRecording(automations, offPeak, error)

    private class Recorder {
        var created: Pair<String, String>? = null
        var deleted: String? = null
    }

    private fun portWithRecording(
        automations: List<AutomationRow>? = null,
        offPeak: List<OffPeakTaskRow>? = null,
        error: String? = null,
        recorder: Recorder = Recorder(),
    ): AutomationsPort {
        val autos = automations
        val peaks = offPeak
        return object : AutomationsPort {
            override suspend fun lists(): AutomationsPort.Lists =
                AutomationsPort.Lists(autos, peaks, error)

            override suspend fun create(workspaceKey: String, write: AutomationsPort.AutomationWrite): Boolean = false
            override suspend fun update(automationId: String, write: AutomationsPort.AutomationWrite): Boolean = false
            override suspend fun delete(automationId: String): Boolean = false
            override suspend fun setEnabled(automationId: String, enabled: Boolean): Boolean = false
            override suspend fun runNow(automationId: String): Boolean = false

            // G29 (vc51 Phase 4C): the off-peak authoring verbs
            override suspend fun createOffPeakTask(title: String, prompt: String): Boolean {
                recorder.created = title to prompt
                return true
            }

            override suspend fun deleteOffPeakTask(offPeakTaskId: String): Boolean {
                recorder.deleted = offPeakTaskId
                return true
            }
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
                automations = fixtureAutomations("automation-list-mixed"),
                offPeak = fixtureOffPeak("offpeak-list-mixed").filter { it.workspaceKey == "/synthetic/ws" },
            ),
        )
        rule.onNodeWithTag("automations_section").assertIsDisplayed()
        rule.onNodeWithTag("automation_row_auto-1").assertIsDisplayed()
        rule.onNodeWithText("Nightly dependency review", substring = true).assertExists()
        rule.onNodeWithText("Weekly report", substring = true).assertExists()
        rule.onNodeWithTag("offpeak_row_opt-1").assertExists() // below the fold in the test viewport is fine
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
    fun writeAffordances_areExactlyTheSanctionedSurface() {
        // SUPERSEDED by G29 (vc51 Phase 4C) — the batch that breaks a pin owns it: the
        // off-peak section now exposes its AUTHORING half (the create entry + per-row
        // cancel/delete), so the old "no write affordance anywhere" gate narrows to the
        // still-unsanctioned verbs.
        setContent(
            portWithRecording(
                automations = fixtureAutomations("automation-list-mixed"),
                offPeak = fixtureOffPeak("offpeak-list-mixed"),
            ),
        )
        rule.onNodeWithTag("offpeak_new").assertExists()
        listOf(
            "automation_enable", "automation_run", "automation_delete", "offpeak_edit",
        ).forEach { tag ->
            rule.onAllNodesWithTag(tag).assertCountEquals(0)
        }
    }

    // --------------------------------------------- G29 (vc51 Phase 4C) pins

    @Test
    fun offPeakHistory_facesRenders_withDeleteAffordance() {
        setContent(
            port(
                offPeak = listOf(
                    OffPeakTaskRow("op-active", "Active sweep", "queued", 1, null, 1L, "/synthetic/ws", null),
                    OffPeakTaskRow("op-done", "Old bump", "completed", null, "sess-1", 1L, "/synthetic/ws", null),
                    OffPeakTaskRow("op-fail", "Dead job", "failed", null, null, 1L, "/synthetic/ws", "boom"),
                ),
            ),
        )
        rule.onNodeWithTag("offpeak_history_header").assertExists()
        rule.onNodeWithTag("offpeak_row_op-done").assertExists()
        // the terminal row offers Delete; the ACTIVE row keeps its monitor verb
        rule.onNodeWithTag("offpeak_delete_op-done").assertExists()
        rule.onNodeWithTag("offpeak_pause_op-active").assertExists()
    }

    @Test
    fun offPeakCreate_flow_reachesThePort() {
        val recorder = Recorder()
        setContent(portWithRecording(offPeak = listOf(OffPeakTaskRow("op-1", "Idle job", "queued", 1, null, 1L, "/synthetic/ws", null)), recorder = recorder))
        rule.onNodeWithTag("offpeak_new").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("offpeak_title_field").performTextInput("Rig sweep")
        rule.onNodeWithTag("offpeak_prompt_field").performTextInput("Refactor the parsers")
        rule.onNodeWithTag("offpeak_create_confirm").performClick()
        rule.waitForIdle()
        org.junit.Assert.assertEquals("Rig sweep" to "Refactor the parsers", recorder.created)
    }

    @Test
    fun offPeakDelete_flow_reachesThePort() {
        val recorder = Recorder()
        setContent(portWithRecording(offPeak = listOf(OffPeakTaskRow("op-done", "Old bump", "completed", null, null, 1L, "/synthetic/ws", null)), recorder = recorder))
        rule.onNodeWithTag("offpeak_delete_op-done").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("offpeak_delete_confirm").performClick()
        rule.waitForIdle()
        org.junit.Assert.assertEquals("op-done", recorder.deleted)
    }
}

// Small local aliases to keep the assertions readable without extra imports.
private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertExists() = assertExists()
