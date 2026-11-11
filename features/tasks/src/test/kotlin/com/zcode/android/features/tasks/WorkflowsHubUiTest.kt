package com.zcode.android.features.tasks

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.ids.WorkspaceIdentityKey
import com.zcode.android.domain.model.SavedWorkflow
import com.zcode.android.domain.model.SavedWorkflowArg
import com.zcode.android.domain.model.SavedWorkflowList
import com.zcode.android.domain.model.SavedWorkflowRun
import com.zcode.android.domain.model.Task
import com.zcode.android.domain.model.TaskStatus
import com.zcode.android.domain.model.WorkflowRunStatus
import com.zcode.android.features.tasks.port.AutomationsPort
import com.zcode.android.features.tasks.ui.WorkflowsHub
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * L6.4 (Phase 5A) UI pins: the hub lists saved workflows with scope + last-run state
 * (newest run per name), warns about invalid files, hides entirely when unavailable,
 * renders the empty line for zero workflows; Run… gates the start on the required
 * args and the target task and sends name/scope/args/taskId.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class WorkflowsHubUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val started = mutableListOf<String>()

    private fun wf(name: String, scope: String = "project", required: List<String> = emptyList(), optional: List<String> = emptyList()) = SavedWorkflow(
        name = name, description = "Does $name", whenToUse = null, scope = scope, path = "/ws/$name.ts",
        args = required.map { SavedWorkflowArg(it, "string", null, required = true, default = null) } +
            optional.map { SavedWorkflowArg(it, "boolean", null, required = false, default = null) },
    )

    private fun run(name: String, status: WorkflowRunStatus, updated: Long) = SavedWorkflowRun(
        runId = "r-$name-$updated", name = name, status = status, statusRaw = status.wire, stopReason = null,
        createdAtMs = updated, updatedAtMs = updated, spentTokens = 1, parentSessionId = null, cwd = null,
    )

    private val management = mutableListOf<String>()

    private fun port(
        list: SavedWorkflowList?,
        runs: List<SavedWorkflowRun>?,
        managementOutcome: AutomationsPort.ManagementResult? = AutomationsPort.ManagementResult(true, null),
    ) = object : AutomationsPort {
        override suspend fun lists() = AutomationsPort.Lists(emptyList(), emptyList(), null)
        override suspend fun create(workspaceKey: String, write: AutomationsPort.AutomationWrite) = false
        override suspend fun update(automationId: String, write: AutomationsPort.AutomationWrite) = false
        override suspend fun delete(automationId: String) = false
        override suspend fun setEnabled(automationId: String, enabled: Boolean) = false
        override suspend fun runNow(automationId: String) = false
        override suspend fun savedWorkflows() = list
        override suspend fun savedWorkflowRuns(name: String?, limit: Int) = runs
        // ---- G42-P23 ----
        override suspend fun deleteSavedWorkflow(name: String): AutomationsPort.ManagementResult? {
            management += "delete:$name"
            return managementOutcome
        }
        override suspend fun renameSavedWorkflow(name: String, newDescription: String, whenToUse: String?): AutomationsPort.ManagementResult? {
            management += "rename:$name:$newDescription"
            return managementOutcome
        }
        override suspend fun moveSavedWorkflow(name: String, targetWorkspacePath: String, targetWorkspaceIdentity: String?): AutomationsPort.ManagementResult? {
            management += "move:$name:${targetWorkspacePath}"
            return managementOutcome
        }
    }

    private fun setContent(
        list: SavedWorkflowList?,
        runs: List<SavedWorkflowRun>? = emptyList(),
        tasks: List<Task> = listOf(task("t1", "Fix auth")),
        managementOutcome: AutomationsPort.ManagementResult? = AutomationsPort.ManagementResult(true, null),
        workspaces: List<com.zcode.android.domain.model.WorkspaceOption> = emptyList(),
    ) {
        started.clear(); management.clear()
        rule.setContent {
            ZCodeTheme {
                WorkflowsHub(
                    port = port(list, runs, managementOutcome),
                    tasks = tasks,
                    onStartWorkflow = { tid, name, scope, args -> started += "$tid:$name:$scope:$args" },
                    workspaces = workspaces,
                )
            }
        }
        rule.waitForIdle()
    }

    private fun task(id: String, title: String) = Task(id, title, TaskStatus.IDLE, WorkspaceIdentityKey("/ws"), 1, 2)

    @Test
    fun `hub renders rows with scope and the newest last-run state and warns about invalid files`() {
        setContent(
            SavedWorkflowList(listOf(wf("release-notes"), wf("lint-all", scope = "global")), invalid = listOf("/ws/broken.ts" to "parse error"), dir = "/ws"),
            runs = listOf(run("release-notes", WorkflowRunStatus.ERRORED, 10), run("release-notes", WorkflowRunStatus.COMPLETED, 20)),
        )
        rule.onNodeWithTag("workflows_hub").assertIsDisplayed()
        rule.onNodeWithTag("workflow_row_release-notes").assertIsDisplayed()
        rule.onNodeWithText("global").assertIsDisplayed()
        rule.onNodeWithText("Last run completed", substring = true).assertIsDisplayed() // the NEWEST run wins
        rule.onNodeWithText("Never run").assertIsDisplayed()
        rule.onNodeWithTag("workflows_invalid").assertIsDisplayed()
    }

    @Test
    fun `hub hides when unavailable and shows the empty line for zero workflows`() {
        setContent(null)
        rule.onAllNodesWithTag("workflows_hub").assertCountEquals(0)
    }

    @Test
    fun `empty line renders for zero workflows`() {
        setContent(SavedWorkflowList(emptyList(), emptyList(), "/ws"))
        rule.onNodeWithTag("workflows_empty").assertIsDisplayed()
    }

    @Test
    fun `run sheet gates on required args and sends name scope args and the target task`() {
        setContent(SavedWorkflowList(listOf(wf("release-notes", required = listOf("version"), optional = listOf("dryRun"))), emptyList(), "/ws"))
        rule.onNodeWithTag("workflow_run_release-notes").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("run_workflow_sheet").assertIsDisplayed()
        rule.onNodeWithTag("workflow_start_confirm").assertIsNotEnabled()
        rule.onNodeWithTag("workflow_arg_version").performTextInput("1.2.0")
        rule.onNodeWithTag("workflow_start_confirm").assertIsEnabled()
        rule.onNodeWithTag("workflow_target_t1").performClick()
        rule.onNodeWithTag("workflow_start_confirm").performClick(); rule.waitForIdle()
        assertEquals(listOf("t1:release-notes:project:{version=1.2.0}"), started)
    }

    @Test
    fun `run sheet with no tasks disables start and explains`() {
        setContent(SavedWorkflowList(listOf(wf("lint-all")), emptyList(), "/ws"), tasks = emptyList())
        rule.onNodeWithTag("workflow_run_lint-all").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("workflow_no_tasks").assertIsDisplayed()
        rule.onNodeWithTag("workflow_start_confirm").assertIsNotEnabled()
    }

    // ---- G42-P23 (vc52 Phase 12): the management verbs ----

    private fun openManageMenu(name: String) {
        rule.onNodeWithTag("workflow_manage_$name").performClick()
        rule.waitForIdle()
    }

    @Test
    fun `delete confirms then fires the verb and the refused reason surfaces`() {
        val list = SavedWorkflowList(listOf(wf("release-notes")), emptyList(), "/ws")
        setContent(list, workspaces = listOf(com.zcode.android.domain.model.WorkspaceOption("/ws-two", "Second WS", null)))
        openManageMenu("release-notes")
        rule.onNodeWithTag("workflow_menu_delete").performClick()
        rule.waitForIdle()
        // §20.2: Delete CONFIRMS first.
        org.junit.Assert.assertTrue("Delete CONFIRMS first", management.isEmpty())
        rule.onNodeWithTag("workflow_delete_confirm").performClick()
        rule.waitForIdle()
        assertEquals(listOf("delete:release-notes"), management)
    }

    @Test
    fun `rename saves the new description through updateMeta`() {
        val list = SavedWorkflowList(listOf(wf("lint-all")), emptyList(), "/ws")
        setContent(list)
        openManageMenu("lint-all")
        rule.onNodeWithTag("workflow_menu_rename").performClick()
        rule.waitForIdle()
        // The field seeds with the workflow's CURRENT description — clear it first
        // (backspaces; the recorded IME pattern), then type the new one.
        // The field seeds with the workflow's CURRENT description — REPLACE it via
        // the semantics' SetText action (performTextReplacement replaces the whole
        // editable text where supported; fall back to selecting-all via key events
        // is not available in this API).
        rule.onNodeWithTag("workflow_rename_field").performTextReplacement("Lint every module")
        rule.onNodeWithTag("workflow_rename_save").performClick()
        rule.waitForIdle()
        assertEquals(listOf("rename:lint-all:Lint every module"), management)
    }

    @Test
    fun `move lists the received workspaces and fires with the target key`() {
        val list = SavedWorkflowList(listOf(wf("deploy")), emptyList(), "/ws")
        setContent(
            list,
            workspaces = listOf(
                com.zcode.android.domain.model.WorkspaceOption("/ws-two", "Second WS", null),
                com.zcode.android.domain.model.WorkspaceOption("/ws-three", "Third WS", null),
            ),
        )
        openManageMenu("deploy")
        rule.onNodeWithTag("workflow_menu_move").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("workflow_move_target_/ws-two").performClick()
        rule.waitForIdle()
        assertEquals(listOf("move:deploy:/ws-two"), management)
    }

    @Test
    fun `a refused management outcome renders the typed reason inline`() {
        val list = SavedWorkflowList(listOf(wf("release-notes")), emptyList(), "/ws")
        setContent(list, managementOutcome = AutomationsPort.ManagementResult(false, "not_found"))
        openManageMenu("release-notes")
        rule.onNodeWithTag("workflow_menu_delete").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("workflow_delete_confirm").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("workflow_management_error").assertIsDisplayed()
        rule.onNodeWithText("The desktop refused: not_found").assertExists()
    }

    @Test
    fun `move hides without workspaces (the action disables in the menu)`() {
        val list = SavedWorkflowList(listOf(wf("deploy")), emptyList(), "/ws")
        setContent(list, workspaces = emptyList())
        openManageMenu("deploy")
        rule.onNodeWithTag("workflow_menu_move").assertIsNotEnabled()
        rule.onNodeWithTag("workflow_menu_delete").assertIsEnabled()
    }
}
