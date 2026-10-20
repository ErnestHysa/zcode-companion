package com.zcode.android.features.tasks

import com.zcode.android.domain.ids.WorkspaceIdentityKey
import com.zcode.android.domain.model.GroupedTaskNode
import com.zcode.android.domain.model.Task
import com.zcode.android.domain.model.TaskGroupColor
import com.zcode.android.domain.model.TaskGroupInfo
import com.zcode.android.domain.model.TaskMeta
import com.zcode.android.domain.model.TaskStatus
import com.zcode.android.features.tasks.port.TaskSearchHit
import com.zcode.android.features.tasks.port.TasksPort
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A3.8–A3.10 (gaps-and-polish Phase 5A) ViewModel legs: pin/unread/resume ride the
 * port and a SUCCESS refreshes (the projection is the truth); mark-read carries the
 * row's `unreadAt` as the CAS expectation; the pinned section is derived from the wire
 * flag and pinned rows leave the workspace sections; groups come from the grouped
 * structure and move-to-group is a port word; the archived viewer loads lazily and a
 * REFUSAL renders as the unavailable face (null), never an empty list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskManagementVmTest {

    private class ManagementPort : TasksPort {
        val calls = mutableListOf<String>()
        var outcome = true
        var archivedResult: List<TaskMeta>? = listOf(
            TaskMeta("a1", "Old task", "completed", archived = true, pinned = false, unreadAtMs = null, createdAtMs = 1, updatedAtMs = 2, workspacePath = "/ws", workspaceIdentity = null, workspaceLabel = "demo"),
        )
        var grouped: List<GroupedTaskNode> = listOf(
            GroupedTaskNode.Group(TaskGroupInfo("g1", "Backend", TaskGroupColor.GREEN), listOf("t2")),
            GroupedTaskNode.Single("t1"),
        )

        override val tasks = MutableStateFlow(
            listOf(
                Task("t1", "Fix auth", TaskStatus.IDLE, WorkspaceIdentityKey("/ws"), 1, 2, pinned = true, unreadAt = 1700000000000),
                Task("t2", "Write docs", TaskStatus.COMPLETED, WorkspaceIdentityKey("/ws"), 3, 4),
            ),
        )
        override val phase = MutableStateFlow(com.zcode.android.domain.state.ConnectionPhase.CONNECTED)
        override val taskLoadError = MutableStateFlow<com.zcode.android.core.common.error.AppError?>(null)
        override val lastRefreshAtMs = MutableStateFlow<Long?>(null)
        override val endpointName = MutableStateFlow<String?>("TestBook")
        override val suggestRepair = MutableStateFlow(false)

        override suspend fun refresh(): Boolean { calls += "refresh"; return outcome }
        override fun retryConnection() {}
        override fun repairPairing() {}
        override fun observeWorkspaces() = MutableStateFlow(emptyList<com.zcode.android.domain.model.WorkspaceOption>())
        override suspend fun createTask(workspaceKey: String, firstText: String, provider: String?, model: String?) = outcome
        override suspend fun searchTasks(query: String): List<TaskSearchHit> = emptyList()

        override suspend fun setTaskPinned(taskId: String, pinned: Boolean): Boolean { calls += "pin:$taskId:$pinned"; return outcome }
        override suspend fun setTaskUnread(taskId: String, unread: Boolean, expectedUnreadAt: Long?): Boolean { calls += "unread:$taskId:$unread:$expectedUnreadAt"; return outcome }
        override suspend fun resumeTask(taskId: String): Boolean { calls += "resume:$taskId"; return outcome }
        override suspend fun setTaskArchived(taskId: String, archived: Boolean): Boolean { calls += "archive:$taskId:$archived"; return outcome }
        override suspend fun listArchivedTasks(): List<TaskMeta>? { calls += "listArchived"; return archivedResult }
        override suspend fun deleteArchivedTask(taskId: String): Boolean { calls += "deleteArchived:$taskId"; return outcome }
        override suspend fun listGroupedView(): List<GroupedTaskNode>? { calls += "groups"; return grouped }
        override suspend fun createTaskGroup(title: String, color: TaskGroupColor): TaskGroupInfo? { calls += "createGroup:$title:${color.wire}"; return if (outcome) TaskGroupInfo("g2", title, color) else null }
        override suspend fun renameTaskGroup(groupId: String, title: String): Boolean { calls += "renameGroup:$groupId:$title"; return outcome }
        override suspend fun updateTaskGroupColor(groupId: String, color: TaskGroupColor): Boolean { calls += "recolorGroup:$groupId:${color.wire}"; return outcome }
        override suspend fun deleteTaskGroup(groupId: String): Boolean { calls += "deleteGroup:$groupId"; return outcome }
        override suspend fun moveTaskToGroup(taskId: String, groupId: String?): Boolean { calls += "move:$taskId:$groupId"; return outcome }
    }

    @Test
    fun `pin unpin and resume ride the port and refresh on success`() = runTest {
        val port = ManagementPort()
        val vm = TasksViewModel(port, backgroundScope)
        vm.setTaskPinned("t2", true)
        vm.setTaskPinned("t1", false)
        vm.resumeTask("t2")
        runCurrent()
        assertEquals(listOf("pin:t2:true", "refresh", "pin:t1:false", "refresh", "resume:t2", "refresh"), port.calls.filter { !it.startsWith("groups") })
        assertEquals("Task resumed", vm.state.value.actionNotice)
    }

    @Test
    fun `mark read carries the row's unreadAt as the CAS expectation and mark unread carries none`() = runTest {
        val port = ManagementPort()
        val vm = TasksViewModel(port, backgroundScope)
        val unreadRow = port.tasks.value.first { it.taskId == "t1" }
        vm.setTaskUnread(unreadRow, unread = false)
        vm.setTaskUnread(port.tasks.value.first { it.taskId == "t2" }, unread = true)
        runCurrent()
        assertTrue("unread:t1:false:1700000000000" in port.calls, "mark-read passes expectedUnreadAt: ${port.calls}")
        assertTrue("unread:t2:true:null" in port.calls, "mark-unread passes no expectation: ${port.calls}")
    }

    @Test
    fun `pinned rows form the pinned section and leave the workspace sections`() = runTest {
        val port = ManagementPort()
        val vm = TasksViewModel(port, backgroundScope)
        runCurrent()
        val state = vm.state.value
        assertEquals(listOf("t1"), state.pinned.map { it.taskId })
        assertEquals(listOf("t2"), state.groups.flatMap { it.tasks }.map { it.taskId })
        assertEquals(1, state.unreadCount, "the pinned unread row counts")
    }

    @Test
    fun `refresh loads the grouped structure into groups and membership`() = runTest {
        val port = ManagementPort()
        val vm = TasksViewModel(port, backgroundScope)
        vm.refresh()
        runCurrent()
        val state = vm.state.value
        assertEquals(listOf("Backend"), state.taskGroups.map { it.title })
        assertEquals(mapOf("t2" to "g1"), state.groupMembership)
    }

    @Test
    fun `group create rename recolor delete and move ride the port`() = runTest {
        val port = ManagementPort()
        val vm = TasksViewModel(port, backgroundScope)
        vm.createGroup("", TaskGroupColor.BLUE)
        vm.renameGroup("g1", "Core")
        vm.recolorGroup("g1", TaskGroupColor.RED)
        vm.moveTaskToGroup("t2", "g1")
        vm.moveTaskToGroup("t2", null)
        vm.deleteGroup("g1")
        runCurrent()
        val calls = port.calls.filter { !it.startsWith("groups") && it != "refresh" }
        assertEquals(
            listOf("createGroup:New Group:blue", "renameGroup:g1:Core", "recolorGroup:g1:red", "move:t2:g1", "move:t2:null", "deleteGroup:g1"),
            calls,
        )
        assertEquals("Group deleted — its tasks are back in the list", vm.state.value.actionNotice)
    }

    @Test
    fun `move refusal surfaces the pinned remedy copy`() = runTest {
        val port = ManagementPort().apply { outcome = false }
        val vm = TasksViewModel(port, backgroundScope)
        vm.moveTaskToGroup("t1", "g1")
        runCurrent()
        assertEquals("Couldn't move the task — unpin it first if it is pinned", vm.state.value.actionNotice)
    }

    @Test
    fun `archived viewer loads lazily unarchives and deletes permanently`() = runTest {
        val port = ManagementPort()
        val vm = TasksViewModel(port, backgroundScope)
        runCurrent()
        assertNull(vm.state.value.archived, "not loaded until the viewer opens")
        vm.loadArchived()
        runCurrent()
        assertEquals(listOf("a1"), vm.state.value.archived?.map { it.taskId })
        vm.unarchiveFromViewer("a1")
        runCurrent()
        assertTrue("archive:a1:false" in port.calls)
        assertEquals(emptyList(), vm.state.value.archived)
        vm.deleteArchivedTask("zz")
        runCurrent()
        assertTrue("deleteArchived:zz" in port.calls)
        assertEquals("Task deleted permanently", vm.state.value.actionNotice)
    }

    @Test
    fun `archived refusal is the unavailable face not an empty list`() = runTest {
        val port = ManagementPort().apply { archivedResult = null }
        val vm = TasksViewModel(port, backgroundScope)
        vm.loadArchived()
        runCurrent()
        assertNull(vm.state.value.archived)
        assertEquals(false, vm.state.value.archivedLoading)
    }
}
