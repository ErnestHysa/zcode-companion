package com.zcode.android.features.tasks

import com.zcode.android.features.tasks.port.TaskSearchHit
import com.zcode.android.features.tasks.port.TasksPort
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.MutableStateFlow
import com.zcode.android.domain.model.Task
import com.zcode.android.domain.model.TaskStatus
import com.zcode.android.domain.ids.WorkspaceIdentityKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * L6.3+J4 + H1.1 (gaps-and-polish Phase 4D) ViewModel legs: the task lifecycle rides
 * the port and a SUCCESS refreshes (the projection replaces the row); a REFUSAL is
 * surfaced, never guessed. Search is a live port query returning the desktop's hits
 * with snippets.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskLifecycleVmTest {

    private class LifecyclePort : TasksPort {
        val calls = mutableListOf<String>()
        var outcome = true
        val hits = listOf(TaskSearchHit("t9", "Fix auth", "refresh token"))

        override val tasks = MutableStateFlow(
            listOf(
                Task("t1", "Fix auth", TaskStatus.IDLE, WorkspaceIdentityKey("/ws"), 1, 2),
            ),
        )
        override val phase = MutableStateFlow(com.zcode.android.domain.state.ConnectionPhase.CONNECTED)
        override val taskLoadError = MutableStateFlow<com.zcode.android.core.common.error.AppError?>(null)
        override val lastRefreshAtMs = MutableStateFlow<Long?>(null)
        override val endpointName = MutableStateFlow<String?>("TestBook")
        override val suggestRepair = MutableStateFlow(false)

        override suspend fun refresh() = outcome
        override fun retryConnection() {}
        override fun repairPairing() {}
        override fun observeWorkspaces() = MutableStateFlow(emptyList<com.zcode.android.domain.model.WorkspaceOption>())
        override suspend fun createTask(workspaceKey: String, firstText: String, provider: String?, model: String?) = outcome

        override suspend fun deleteTask(taskId: String): Boolean { calls += "delete:$taskId"; return outcome }
        override suspend fun renameTask(taskId: String, title: String): Boolean { calls += "rename:$taskId:$title"; return outcome }
        override suspend fun setTaskArchived(taskId: String, archived: Boolean): Boolean { calls += "archive:$taskId:$archived"; return outcome }
        override suspend fun searchTasks(query: String): List<TaskSearchHit> { calls += "search:$query"; return hits }
    }

    @Test
    fun `delete archive rename ride the port`() = runTest {
        val port = LifecyclePort()
        val vm = TasksViewModel(port, backgroundScope)
        vm.deleteTask("t1")
        vm.setTaskArchived("t1", true)
        vm.renameTask("t1", "New")
        runCurrent()
        assertEquals(listOf("delete:t1", "archive:t1:true", "rename:t1:New"), port.calls)
    }

    @Test
    fun `search queries the port and lands the hits with snippets`() = runTest {
        val port = LifecyclePort()
        val vm = TasksViewModel(port, backgroundScope)
        vm.search("auth")
        runCurrent()
        assertTrue("search:auth" in port.calls)
        val hits = vm.state.value.searchResults
        assertEquals(1, hits.size)
        assertEquals("Fix auth", hits.first().title)
        assertEquals("refresh token", hits.first().snippet)
    }

    @Test
    fun `a refused lifecycle action is surfaced in the notice`() = runTest {
        val port = LifecyclePort().apply { outcome = false }
        val vm = TasksViewModel(port, backgroundScope)
        vm.deleteTask("t1")
        runCurrent()
        assertTrue(vm.state.value.actionNotice != null, "refusal → honest notice")
        assertTrue("search:auth" !in port.calls)
    }

    @Test
    fun `clearing the query drops the results`() = runTest {
        val port = LifecyclePort()
        val vm = TasksViewModel(port, backgroundScope)
        vm.search("auth")
        runCurrent()
        vm.search("")
        runCurrent()
        assertTrue(vm.state.value.searchResults.isEmpty())
    }
}
