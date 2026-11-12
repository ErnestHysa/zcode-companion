package com.zcode.android.features.tasks

import com.zcode.android.core.common.error.AppError
import com.zcode.android.domain.ids.WorkspaceIdentityKey
import com.zcode.android.domain.model.Task
import com.zcode.android.domain.model.TaskStatus
import com.zcode.android.domain.model.WorkspaceOption
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.features.tasks.port.TasksPort
import com.zcode.android.features.tasks.ui.WorkspaceCollapseState
import com.zcode.android.features.tasks.ui.WorkspaceCollapseStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * G69 (vc52 Phase 13): task-home organisation. The desktop's own browser
 * companion offers organize by workspace/timeline, sort created/updated,
 * collapse all, workspace kind badges (Local/Remote/Conversation) and
 * mark-read-on-open; the phone had per-workspace collapse only, no organize /
 * sort, no kind badge, and opening an unread task left it unread on the desktop
 * (only the manual mark-read existed).
 *
 * Pins (ViewModel level): the organize/sort state flows + the derived groups;
 * mark-read-on-open fires setTaskUnread(false, expectedUnreadAt) EXACTLY for
 * unread tasks; a kind-less workspace renders no badge.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
@org.robolectric.annotation.Config(sdk = [30])
class TasksOrganisationTest {

    private class FakePort : TasksPort {
        val tasksFlow = MutableStateFlow<List<Task>>(emptyList())
        val workspacesFlow = MutableStateFlow<List<WorkspaceOption>>(emptyList())
        override val tasks: Flow<List<Task>> get() = tasksFlow
        override val phase = MutableStateFlow(ConnectionPhase.CONNECTED)
        override val taskLoadError = MutableStateFlow<AppError?>(null)
        override val lastRefreshAtMs = MutableStateFlow<Long?>(null)
        override val endpointName = MutableStateFlow<String?>("TestBook")
        override val suggestRepair = MutableStateFlow(false)
        override suspend fun refresh() = true
        override fun retryConnection() {}
        override fun repairPairing() {}
        override fun observeWorkspaces(): Flow<List<WorkspaceOption>> = workspacesFlow
        override suspend fun createTask(workspaceKey: String, firstText: String, provider: String?, model: String?) = true

        val unreadCalls = mutableListOf<Triple<String, Boolean, Long?>>()
        var unreadResult = true
        override suspend fun setTaskUnread(taskId: String, unread: Boolean, expectedUnreadAt: Long?): Boolean {
            unreadCalls.add(Triple(taskId, unread, expectedUnreadAt))
            return unreadResult
        }
    }

    private fun task(
        id: String,
        workspace: String = "/ws/a",
        updated: Long,
        created: Long = updated - 100,
        unreadAt: Long? = null,
    ) = Task(
        taskId = id,
        title = "Task $id",
        status = TaskStatus.IDLE,
        workspace = WorkspaceIdentityKey(workspace),
        createdAtMs = created,
        updatedAtMs = updated,
        unreadAt = unreadAt,
    )

    private fun TestScope.buildVm(port: FakePort): TasksViewModel = TasksViewModel(port, backgroundScope)

    private suspend fun TestScope.eventually(timeoutMs: Long = 2_000, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            advanceUntilIdle()
            if (cond()) return true
            withContext(Dispatchers.Default) { delay(10) }
        }
        return false
    }

    @Test
    fun `defaults are workspace organize + updated sort (the historical layout)`() = runTest(StandardTestDispatcher()) {
        val port = FakePort()
        val vm = buildVm(port)
        assertTrue(eventually { vm.state.value.groups.isNotEmpty() || vm.state.value.taskCount == 0 })
        assertEquals(TasksUiState.OrganizeBy.WORKSPACE, vm.state.value.organizeBy)
        assertEquals(TasksUiState.SortBy.UPDATED, vm.state.value.sortBy)
    }

    @Test
    fun `sort CREATED orders within a group by createdAt - UPDATED by updatedAt`() = runTest(StandardTestDispatcher()) {
        val port = FakePort()
        // Same workspace; createdAt order REVERSES updatedAt order.
        port.tasksFlow.value = listOf(
            task("a", updated = 3_000, created = 100),
            task("b", updated = 2_000, created = 200),
            task("c", updated = 1_000, created = 300),
        )
        val vm = buildVm(port)
        assertTrue(eventually { vm.state.value.groups.firstOrNull()?.tasks?.size == 3 })
        // Default (UPDATED): a(3000), b(2000), c(1000).
        assertEquals(listOf("a", "b", "c"), vm.state.value.groups[0].tasks.map { it.taskId })
        vm.sortBy(TasksUiState.SortBy.CREATED)
        assertTrue(eventually { vm.state.value.sortBy == TasksUiState.SortBy.CREATED })
        assertTrue(eventually { vm.state.value.groups[0].tasks.map { it.taskId } == listOf("c", "b", "a") })
        vm.sortBy(TasksUiState.SortBy.UPDATED)
        assertTrue(eventually { vm.state.value.groups[0].tasks.map { it.taskId } == listOf("a", "b", "c") })
    }

    @Test
    fun `organize TIMELINE flattens every workspace into one group`() = runTest(StandardTestDispatcher()) {
        val port = FakePort()
        port.tasksFlow.value = listOf(
            task("a", workspace = "/ws/a", updated = 1_000),
            task("b", workspace = "/ws/b", updated = 2_000),
            task("c", workspace = "/ws/c", updated = 3_000),
        )
        val vm = buildVm(port)
        assertTrue(eventually { vm.state.value.groups.size == 3 })
        vm.organizeBy(TasksUiState.OrganizeBy.TIMELINE)
        assertTrue(eventually { vm.state.value.organizeBy == TasksUiState.OrganizeBy.TIMELINE })
        assertTrue(eventually { vm.state.value.groups.size == 1 })
        val timeline = vm.state.value.groups[0]
        assertEquals(TasksUiState.TIMELINE_GROUP_KEY, timeline.workspaceKey)
        // Timeline order: most recent FIRST (the desktop's own timeline organize).
        assertEquals(listOf("c", "b", "a"), timeline.tasks.map { it.taskId })
        vm.organizeBy(TasksUiState.OrganizeBy.WORKSPACE)
        assertTrue(eventually { vm.state.value.groups.size == 3 })
    }

    @Test
    fun `workspace kind badge rides the group when the desktop declares it`() = runTest(StandardTestDispatcher()) {
        val port = FakePort()
        port.workspacesFlow.value = listOf(
            WorkspaceOption("/ws/a", "alpha", "/ws/a", "local"),
            WorkspaceOption("/ws/b", "beta", "/ws/b", "remote"),
        )
        port.tasksFlow.value = listOf(
            task("a", workspace = "/ws/a", updated = 1_000),
            task("b", workspace = "/ws/b", updated = 2_000),
            task("c", workspace = "/ws/unknown", updated = 3_000),
        )
        val vm = buildVm(port)
        assertTrue(eventually { vm.state.value.groups.size == 3 })
        val byKey = vm.state.value.groups.associateBy { it.workspaceKey }
        assertEquals("local", byKey["/ws/a"]?.workspaceKind)
        assertEquals("remote", byKey["/ws/b"]?.workspaceKind)
        assertNull(byKey["/ws/unknown"]?.workspaceKind)
    }

    @Test
    fun `mark-read-on-open fires setTaskUnread(false, expectedUnreadAt) ONLY for unread tasks`() = runTest(StandardTestDispatcher()) {
        val port = FakePort()
        port.tasksFlow.value = listOf(
            task("unread-1", updated = 1_000, unreadAt = 42),
            task("read-1", updated = 2_000),
        )
        val vm = buildVm(port)
        assertTrue(eventually { vm.state.value.groups.isNotEmpty() })
        val unreadRow = port.tasksFlow.value.first { it.taskId == "unread-1" }
        assertTrue(unreadRow.isUnread, "fixture sanity: the unread row IS unread (unreadAt=42)")
        vm.markReadOnOpen(unreadRow)
        vm.markReadOnOpen(port.tasksFlow.value.first { it.taskId == "read-1" })
        // runCurrent (not advanceUntilIdle): the same recipe the VM's other
        // scope.launch pins use (TaskManagementVmTest).
        runCurrent()
        assertEquals(1, port.unreadCalls.size, "launch ran: ${port.unreadCalls}")
        assertEquals("unread-1", port.unreadCalls[0].first)
        assertFalse(port.unreadCalls[0].second)
        assertEquals(42L, port.unreadCalls[0].third)
    }

    @Test
    fun `collapse-all marks every group key collapsed through the store`() {
        val store = object : WorkspaceCollapseStore {
            var saved: Set<String> = emptySet()
            override fun load(): Set<String> = emptySet()
            override fun save(collapsed: Set<String>) { saved = collapsed }
        }
        val state = WorkspaceCollapseState(store, java.util.concurrent.Executor { it.run() })
        state.collapseAll(listOf("/ws/a", "/ws/b"))
        assertTrue(state.isCollapsed("/ws/a"))
        assertTrue(state.isCollapsed("/ws/b"))
        assertEquals(setOf("/ws/a", "/ws/b"), store.saved)
        state.expandAll(listOf("/ws/a", "/ws/b"))
        assertFalse(state.isCollapsed("/ws/a"))
        assertFalse(state.isCollapsed("/ws/b"))
        assertEquals(emptySet<String>(), store.saved)
    }
}
