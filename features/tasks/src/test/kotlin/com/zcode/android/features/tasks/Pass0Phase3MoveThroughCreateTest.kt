package com.zcode.android.features.tasks

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.ids.WorkspaceIdentityKey
import com.zcode.android.domain.model.Task
import com.zcode.android.domain.model.TaskGroupColor
import com.zcode.android.domain.model.TaskStatus
import com.zcode.android.domain.model.TaskGroupInfo
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.features.tasks.ui.TaskListScreen
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * F-P39-A (pass-39 P3): the move sheet's "New group…" path silently DROPPED the
 * pending move — the sheet cleared moveFor BEFORE opening the editor and the
 * editor wired ONLY the create, so "Move to group… → New group… → create" ended
 * with the task UNMOVED (zero applyGroupedTaskViewOrder on the wire; a11y + DB
 * still loose) and the new group INVISIBLE while a SUCCESS notice fired. Receipted
 * live: "shgrp" created ok → no move wire → no header.
 *
 * The pinned shape (the row's named pin): move sheet → New group → create asserts
 * the MOVE chain fires (create-then-move — the created id resolves in the VM, so
 * the screen hands the task to onCreateGroupAndMove and the plain create callback
 * stays silent), the plain new-group path keeps its create-only callback, and the
 * VM chain moves the created group by its OWN id. RED at the pre-fix commit is
 * compile-shape (the carried-move seam did not exist).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class Pass0Phase3MoveThroughCreateTest {

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

    private class Recorder {
        var createArgs: Pair<String, TaskGroupColor>? = null
        var createAndMove: Triple<String, String, TaskGroupColor>? = null
    }

    @Test
    fun `move sheet - new group - create completes the pending move`() {
        val rec = Recorder()
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
                    onMoveTaskToGroup = { _, _ -> },
                    onCreateGroup = { t, c -> rec.createArgs = t to c },
                    onCreateGroupAndMove = { taskId, t, c -> rec.createAndMove = Triple(taskId, t, c) },
                )
            }
        }
        rule.waitForIdle()
        // Row menu → Move → move sheet → New group → editor create.
        rule.onNodeWithTag("task_actions_t1").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("task_menu_move_t1").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("move_group_new").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("group_title_field").performTextInput("Backend")
        rule.onNodeWithTag("group_create_confirm").performClick(); rule.waitForIdle()
        assertEquals(
            "the carried move completes through the create-then-move chain",
            Triple("t1", "Backend", TaskGroupColor.GRAY),
            rec.createAndMove,
        )
        assertEquals("the plain create callback stays silent on the carried path", null, rec.createArgs)
    }

    @Test
    fun `the plain new-group path keeps its create-only callback`() {
        val rec = Recorder()
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
                    onCreateGroup = { t, c -> rec.createArgs = t to c },
                    onCreateGroupAndMove = { taskId, t, c -> rec.createAndMove = Triple(taskId, t, c) },
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("tasks_more").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("tasks_menu_new_group").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("group_title_field").performTextInput("Standalone")
        rule.onNodeWithTag("group_create_confirm").performClick(); rule.waitForIdle()
        assertEquals("Standalone" to TaskGroupColor.GRAY, rec.createArgs)
        assertEquals("no carried move on the plain path", null, rec.createAndMove)
    }
}

/**
 * The VM half: the create-then-move chain (the created id resolves here) —
 * create → move(created.id) → refresh; a create refusal names the create; a move
 * refusal after a successful create still refreshes (the group exists).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class Pass0Phase3CreateGroupAndMoveVmTest {

    private class GroupMovePort : com.zcode.android.features.tasks.port.TasksPort {
        val calls = mutableListOf<String>()
        var createOutcome: TaskGroupInfo? = TaskGroupInfo("g2", "Backend", TaskGroupColor.GREEN)
        var moveOutcome = true
        override val tasks = kotlinx.coroutines.flow.MutableStateFlow(
            listOf(Task("t1", "Fix auth", TaskStatus.IDLE, WorkspaceIdentityKey("/ws"), 1, 2)),
        )
        override val phase = kotlinx.coroutines.flow.MutableStateFlow(ConnectionPhase.CONNECTED)
        override val taskLoadError = kotlinx.coroutines.flow.MutableStateFlow<com.zcode.android.core.common.error.AppError?>(null)
        override val lastRefreshAtMs = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)
        override val endpointName = kotlinx.coroutines.flow.MutableStateFlow<String?>("TestBook")
        override val suggestRepair = kotlinx.coroutines.flow.MutableStateFlow(false)
        override suspend fun refresh() = true
        override fun retryConnection() {}
        override fun repairPairing() {}
        override fun observeWorkspaces() = kotlinx.coroutines.flow.MutableStateFlow(emptyList<com.zcode.android.domain.model.WorkspaceOption>())
        override suspend fun createTask(workspaceKey: String, firstText: String, provider: String?, model: String?) = true
        override suspend fun searchTasks(query: String, limit: Int): com.zcode.android.features.tasks.port.TaskSearchResult =
            com.zcode.android.features.tasks.port.TaskSearchResult(emptyList(), 0, false)
        override suspend fun createTaskGroup(title: String, color: TaskGroupColor): TaskGroupInfo? {
            calls += "create:$title"
            return createOutcome
        }
        override suspend fun moveTaskToGroup(taskId: String, groupId: String?): Boolean {
            calls += "move:$taskId:$groupId"
            return moveOutcome
        }
        override suspend fun listGroupedView(): List<com.zcode.android.domain.model.GroupedTaskNode>? = null
    }

    @Test
    fun `create then move carries the CREATED id and confirms`() = kotlinx.coroutines.test.runTest {
        val port = GroupMovePort()
        val vm = TasksViewModel(port, backgroundScope)
        vm.createGroupAndMove("t1", "Backend", TaskGroupColor.GREEN)
        runCurrent()
        assertTrue(
            "move carries the CREATED group id",
            "move:t1:g2" in port.calls && "create:Backend" in port.calls,
        )
        assertEquals("Moved to group", vm.state.value.actionNotice)
    }

    @Test
    fun `a create refusal names the create and never moves`() = kotlinx.coroutines.test.runTest {
        val port = GroupMovePort().apply { createOutcome = null }
        val vm = TasksViewModel(port, backgroundScope)
        vm.createGroupAndMove("t1", "Backend", TaskGroupColor.GREEN)
        runCurrent()
        assertTrue(port.calls.none { it.startsWith("move:") })
        assertEquals("Couldn't create the group — try again", vm.state.value.actionNotice)
    }

    @Test
    fun `a move refusal after a successful create still refreshes (the group exists)`() = kotlinx.coroutines.test.runTest {
        val port = GroupMovePort().apply { moveOutcome = false }
        val vm = TasksViewModel(port, backgroundScope)
        vm.createGroupAndMove("t1", "Backend", TaskGroupColor.GREEN)
        runCurrent()
        assertTrue("move:t1:g2" in port.calls)
        assertEquals("Couldn't move the task — try again", vm.state.value.actionNotice)
    }
}
