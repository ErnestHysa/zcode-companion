package com.zcode.android.features.tasks

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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

/**
 * GAP-A14 (pass 13, 2026-11-02, live-found on the rig with MOCK_AUTOMATIONS +
 * MOCK_OFFPEAK armed — the Phase-5 arms): the management sections composed UNBOUNDED
 * inside the body's non-scrollable Column, and the tasks Box (`fillMaxSize`, laid out
 * AFTER them) received ~zero remaining height — ZERO task rows composed, no scroll,
 * the search FAB below the fold. Every real desktop serves automations, so every real
 * user with ≥1 automation lost the task list. Pinned: with a hub that clearly exceeds
 * the A14 cap (6 automations + 2 off-peak rows), the task rows still compose AND are
 * on screen beside the capped, internally-scrolling hub.
 */
@RunWith(AndroidJUnit4::class)
// A phone's content height (PinnedInteractionBudgetTest's geometry) — the portrait
// shape the rig reproduced the loss on.
@Config(sdk = [30], qualifiers = "w411dp-h731dp")
class TaskListAutomationsVisibilityTest {

    @get:Rule
    val rule = createComposeRule()

    private fun task(id: String, status: TaskStatus) = Task(
        taskId = id,
        title = "Task $id",
        status = status,
        workspace = WorkspaceIdentityKey("/ws/demo-api"),
        createdAtMs = 1,
        updatedAtMs = System.currentTimeMillis() - 120_000,
    )

    /** Six automations + two off-peak rows: a management stack far taller than the cap. */
    private fun tallHub(): AutomationsPort = object : AutomationsPort {
        val autos = (1..6).map { i ->
            AutomationRow(
                automationId = "auto-$i",
                title = "Nightly build sweep $i",
                cronExpr = "0 3 * * *",
                prompt = "run the sweep",
                mode = "build",
                targetTaskId = null,
                enabled = true,
                lifecycleStatus = "active",
                nextRunAt = 1_700_000_600_000,
                lastRunAt = null,
                runCount = i,
                recurring = true,
                maxRuns = null,
                scheduleRule = null,
                workspaceKey = "/ws/demo-api",
                locationKind = "remote",
            )
        }
        val peaks = (1..2).map { i ->
            OffPeakTaskRow(
                offPeakTaskId = "peak-$i",
                title = "Off-peak index rebuild $i",
                status = "queued",
                queuePosition = i,
                sessionId = null,
                createdAt = 1_700_000_000_000,
                workspaceKey = "/ws/demo-api",
                failureReason = null,
            )
        }

        override suspend fun lists(): AutomationsPort.Lists = AutomationsPort.Lists(autos, peaks, null)
        override suspend fun create(workspaceKey: String, write: AutomationsPort.AutomationWrite): Boolean = false
        override suspend fun update(automationId: String, write: AutomationsPort.AutomationWrite): Boolean = false
        override suspend fun delete(automationId: String): Boolean = false
        override suspend fun setEnabled(automationId: String, enabled: Boolean): Boolean = false
        override suspend fun runNow(automationId: String): Boolean = false
    }

    @Test
    fun `A14 - task rows compose and stay visible when the automations hub has content`() {
        val state = TasksUiState(
            phase = ConnectionPhase.CONNECTED,
            loadState = TasksUiState.Load.READY,
            endpointName = "TestBook",
            lastRefreshAtMs = 1,
            groups = listOf(
                TaskGroup(
                    "/ws/demo-api",
                    "demo-api",
                    listOf(task("a", TaskStatus.RUNNING), task("b", TaskStatus.WAITING)),
                ),
            ),
            taskCount = 2,
        )
        rule.setContent {
            ZCodeTheme {
                TaskListScreen(
                    state,
                    onTaskClick = {}, onOpenSettings = {}, onOpenConnection = {}, onRetry = {}, onRefresh = {},
                    automationsPort = tallHub(),
                )
            }
        }
        rule.waitForIdle()
        // The hub still renders (capped + internally scrollable).
        rule.onNodeWithTag("automations_section").assertExists()
        // THE BUG: pre-fix the unbounded hub consumed the viewport and NO task row
        // composed (the tasks Box got ~zero height in the non-scrollable Column).
        rule.onNodeWithTag("task_row_a").assertIsDisplayed()
        // The search FAB rode the same loss (pushed below the fold).
        rule.onNodeWithTag("tasks_search_fab").assertIsDisplayed()
    }
}
