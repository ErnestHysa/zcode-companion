package com.zcode.android.features.tasks

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.ids.WorkspaceIdentityKey
import com.zcode.android.domain.model.Task
import com.zcode.android.domain.model.TaskGroupColor
import com.zcode.android.domain.model.TaskGroupInfo
import com.zcode.android.domain.model.TaskStatus
import com.zcode.android.domain.model.WorkspaceOption
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.features.tasks.ui.TaskListScreen
import com.zcode.android.features.tasks.ui.WorkspaceCollapseState
import com.zcode.android.features.tasks.ui.WorkspaceCollapseStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The three Phase-3 P4 smalls, pinned together (one surface — the task home):
 *
 * N-BU-1: the tasks ACTION NOTICE rendered in the STATUS-BAR zone (y≈25 over the
 * clock — the bare sibling drew at window level, no top inset; the CAS-refusal
 * notice rode the same slot). Pin: the notice's bounds start below the top bar
 * (the inset-padded body), never in the window's top band.
 *
 * N-BV-1: EMPTY custom groups were AFFORDANCE-LESS — the render skipped zero-
 * member groups, so the header (and the ONLY Edit/Delete affordances) never
 * rendered; the orphan group stayed in every move sheet forever (receipted live:
 * the created "shgrp" rendered NO header). Pin: an empty group renders its header
 * and is deletable through the editor from the list.
 *
 * N-BW-1: collapse-all from the TIMELINE organize NEVER survived a restart — the
 * persisted sentinel ("timeline") matched nothing in the always-reset WORKSPACE
 * organize (view-local, by design). Pin: collapsing in timeline persists the
 * workspace keys too; recomposing in workspace renders the collapsed faces.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class Pass0Phase3NoticesSmallsTest {

    @get:Rule
    val rule = createComposeRule()

    private fun task(id: String) = Task(
        taskId = id,
        title = "Task $id",
        status = TaskStatus.IDLE,
        workspace = WorkspaceIdentityKey("/synthetic/ws-one"),
        createdAtMs = 1,
        updatedAtMs = System.currentTimeMillis(),
    )

    // ---------------------------------------------------------------- N-BU-1

    @Test
    fun `the action notice renders below the top bar, never in the status-bar zone`() {
        rule.setContent {
            ZCodeTheme(themeMode = com.zcode.android.core.ui.theme.ThemeMode.LIGHT) {
                TaskListScreen(
                    state = TasksUiState(
                        phase = ConnectionPhase.CONNECTED,
                        loadState = TasksUiState.Load.READY,
                        groups = listOf(TaskGroup("/synthetic/ws-one", "Synthetic One", listOf(task("t1")))),
                        taskCount = 1,
                        actionNotice = "Group created",
                    ),
                    onTaskClick = {}, onOpenSettings = {}, onOpenConnection = {}, onRetry = {}, onRefresh = {},
                )
            }
        }
        rule.waitForIdle()
        val notice: androidx.compose.ui.unit.DpRect = rule.onNodeWithTag("tasks_action_notice").getBoundsInRoot()
        val titleBar: androidx.compose.ui.unit.DpRect = rule.onNodeWithTag("tasks-title").getBoundsInRoot()
        assertTrue(
            "the notice must start BELOW the inset-padded top bar (notice.top=${notice.top}, bar.bottom=${titleBar.bottom})",
            notice.top >= titleBar.bottom,
        )
    }

    @Test
    fun `the create notice renders below the top bar too (the shared slot)`() {
        rule.setContent {
            ZCodeTheme(themeMode = com.zcode.android.core.ui.theme.ThemeMode.LIGHT) {
                TaskListScreen(
                    state = TasksUiState(
                        phase = ConnectionPhase.CONNECTED,
                        loadState = TasksUiState.Load.READY,
                        groups = listOf(TaskGroup("/synthetic/ws-one", "Synthetic One", listOf(task("t1")))),
                        taskCount = 1,
                    ),
                    onTaskClick = {}, onOpenSettings = {}, onOpenConnection = {}, onRetry = {}, onRefresh = {},
                    createNotice = "Task created",
                )
            }
        }
        rule.waitForIdle()
        val notice: androidx.compose.ui.unit.DpRect = rule.onNodeWithTag("tasks_create_notice").getBoundsInRoot()
        val titleBar: androidx.compose.ui.unit.DpRect = rule.onNodeWithTag("tasks-title").getBoundsInRoot()
        assertTrue(
            "the create notice must start BELOW the inset-padded top bar",
            notice.top >= titleBar.bottom,
        )
    }

    // ---------------------------------------------------------------- N-BV-1

    @Test
    fun `an empty group renders its header and is deletable from the list`() {
        val empty = TaskGroupInfo("shgrp", "Rig p38", TaskGroupColor.GREEN)
        var deleted: String? = null
        rule.setContent {
            ZCodeTheme(themeMode = com.zcode.android.core.ui.theme.ThemeMode.LIGHT) {
                TaskListScreen(
                    state = TasksUiState(
                        phase = ConnectionPhase.CONNECTED,
                        loadState = TasksUiState.Load.READY,
                        groups = listOf(TaskGroup("/synthetic/ws-one", "Synthetic One", listOf(task("t1")))),
                        taskCount = 1,
                        taskGroups = listOf(empty),
                        groupMembership = emptyMap(), // ZERO members
                    ),
                    onTaskClick = {}, onOpenSettings = {}, onOpenConnection = {}, onRetry = {}, onRefresh = {},
                    onDeleteGroup = { deleted = it },
                )
            }
        }
        rule.waitForIdle()
        // The header (the ONLY Edit/Delete entry point) now renders.
        rule.onNodeWithTag("task_group_header_shgrp").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("task_group_edit_shgrp").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("group_edit_delete").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("group_delete_confirm").performClick()
        rule.waitForIdle()
        assertEquals("shgrp", deleted)
    }

    // ---------------------------------------------------------------- N-BW-1

    private class RecordingStore : WorkspaceCollapseStore {
        val saved = mutableListOf<Set<String>>()
        override fun load(): Set<String> = emptySet()
        override fun save(collapsed: Set<String>) {
            saved += collapsed
        }
    }

    private fun timelineState() = TasksUiState(
        phase = ConnectionPhase.CONNECTED,
        loadState = TasksUiState.Load.READY,
        organizeBy = TasksUiState.OrganizeBy.TIMELINE,
        groups = listOf(TaskGroup(TasksUiState.TIMELINE_GROUP_KEY, "Timeline", listOf(task("t1")))),
        taskCount = 1,
    )

    private fun workspaceState() = TasksUiState(
        phase = ConnectionPhase.CONNECTED,
        loadState = TasksUiState.Load.READY,
        organizeBy = TasksUiState.OrganizeBy.WORKSPACE,
        groups = listOf(TaskGroup("/synthetic/ws-one", "Synthetic One", listOf(task("t1")))),
        taskCount = 1,
    )

    @Test
    fun `collapse-all in timeline persists the workspace keys and the restart's sections render collapsed`() {
        val store = RecordingStore()
        val workspaces = listOf(WorkspaceOption("/synthetic/ws-one", "Synthetic One", "/synthetic/ws-one"))
        // The TIMELINE organize (the collapse-all the receipt drove); the "restart"
        // is the by-design WORKSPACE organize the next launch renders — the state
        // flips here (one composition, the same store).
        var state by androidx.compose.runtime.mutableStateOf(timelineState())
        rule.setContent {
            ZCodeTheme(themeMode = com.zcode.android.core.ui.theme.ThemeMode.LIGHT) {
                TaskListScreen(
                    state = state,
                    onTaskClick = {}, onOpenSettings = {}, onOpenConnection = {}, onRetry = {}, onRefresh = {},
                    workspaces = workspaces,
                    collapseStore = store,
                    collapseExecutor = java.util.concurrent.Executor { it.run() }, // deterministic persist
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("tasks_more").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("tasks_menu_collapse_all").performClick()
        rule.waitForIdle()
        // BOTH namespaces persisted (pre-fix: ONLY the "timeline" sentinel).
        val persisted = store.saved.last()
        assertTrue(
            "the workspace keys must ride the collapse-all (persisted=$persisted)",
            TasksUiState.TIMELINE_GROUP_KEY in persisted && "/synthetic/ws-one" in persisted,
        )
        // THE RESTART FACE: the WORKSPACE organize renders the collapsed header
        // (the expand word in the merged a11y description).
        state = workspaceState()
        rule.waitForIdle()
        val header = rule.onNodeWithTag("tasks_group_collapse_${"/synthetic/ws-one".hashCode()}")
            .fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
        assertTrue(
            "the restart's workspace section renders COLLAPSED (a11y=$header)",
            header.contains("expand"),
        )
    }

    @Test
    fun `expand-all in timeline clears the persisted workspace collapse too`() {
        val store = RecordingStore()
        val workspaces = listOf(WorkspaceOption("/synthetic/ws-one", "Synthetic One", "/synthetic/ws-one"))
        rule.setContent {
            ZCodeTheme(themeMode = com.zcode.android.core.ui.theme.ThemeMode.LIGHT) {
                TaskListScreen(
                    state = timelineState(),
                    onTaskClick = {}, onOpenSettings = {}, onOpenConnection = {}, onRetry = {}, onRefresh = {},
                    workspaces = workspaces,
                    collapseStore = store,
                    collapseExecutor = java.util.concurrent.Executor { it.run() },
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("tasks_more").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("tasks_menu_collapse_all").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("tasks_more").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("tasks_menu_expand_all").performClick()
        rule.waitForIdle()
        assertTrue(
            "expand-all clears BOTH namespaces",
            store.saved.last().isEmpty(),
        )
    }
}
