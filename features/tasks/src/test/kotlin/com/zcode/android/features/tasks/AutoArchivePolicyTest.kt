package com.zcode.android.features.tasks

import com.zcode.android.domain.model.Task
import com.zcode.android.domain.model.TaskStatus
import com.zcode.android.domain.ids.WorkspaceIdentityKey
import com.zcode.android.features.tasks.port.TasksPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G39 (vc51 Phase 4D): the task AGING policy over the archive verb — idle rows older
 * than the policy archive ONCE on refresh (archived rows leave, so the sweep is
 * naturally idempotent); RUNNING rows never archive; 0 = off (the default) never calls
 * the archive verb at all.
 */
class AutoArchivePolicyTest {

    private class RecordingPort(tasks: List<Task>) : TasksPort {
        val current = kotlinx.coroutines.flow.MutableStateFlow(tasks)
        val archived = mutableListOf<String>()
        val refreshed = kotlinx.coroutines.flow.MutableStateFlow(0)
        override val tasks: kotlinx.coroutines.flow.Flow<List<Task>> = current
        override val phase: kotlinx.coroutines.flow.StateFlow<com.zcode.android.domain.state.ConnectionPhase> =
            kotlinx.coroutines.flow.MutableStateFlow(com.zcode.android.domain.state.ConnectionPhase.CONNECTED)
        override val taskLoadError: kotlinx.coroutines.flow.StateFlow<com.zcode.android.core.common.error.AppError?> =
            kotlinx.coroutines.flow.MutableStateFlow(null)
        override val lastRefreshAtMs: kotlinx.coroutines.flow.StateFlow<Long?> =
            kotlinx.coroutines.flow.MutableStateFlow(null)
        override val endpointName: kotlinx.coroutines.flow.StateFlow<String?> =
            kotlinx.coroutines.flow.MutableStateFlow("PolicyBook")
        override val suggestRepair: kotlinx.coroutines.flow.StateFlow<Boolean> =
            kotlinx.coroutines.flow.MutableStateFlow(false)
        override fun observeWorkspaces(): kotlinx.coroutines.flow.Flow<List<com.zcode.android.domain.model.WorkspaceOption>> =
            kotlinx.coroutines.flow.flowOf(emptyList())
        override suspend fun createTask(workspaceKey: String, firstText: String, provider: String?, model: String?): Boolean = true
        override suspend fun refresh(): Boolean {
            refreshed.value += 1
            return true
        }
        override fun retryConnection() {}
        override fun repairPairing() {}
        override suspend fun setTaskArchived(taskId: String, archived: Boolean): Boolean {
            if (archived) this.archived += taskId
            current.value = current.value.map { if (it.taskId == taskId) it.copy(archivedAtMs = 1L) else it }
            return true
        }
        override suspend fun listGroupedView(): List<com.zcode.android.domain.model.GroupedTaskNode>? = emptyList()
    }

    private fun task(id: String, ageDays: Long, status: TaskStatus = TaskStatus.IDLE) = Task(
        taskId = id,
        title = id,
        status = status,
        workspace = WorkspaceIdentityKey("/synthetic/ws"),
        createdAtMs = 0L,
        updatedAtMs = System.currentTimeMillis() - ageDays * 24L * 3600_000L,
    )

    @Test
    fun `idle rows older than the policy archive on refresh`() = runBlocking {
        val port = RecordingPort(listOf(task("old-1", 8), task("old-2", 10), task("fresh", 1)))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val vm = TasksViewModel(port, scope)
        vm.autoArchiveDays = 7
        vm.refresh()
        // wait for the policy sweep
        var waited = 0
        while (port.archived.size < 2 && waited < 200) { Thread.sleep(10); waited += 1 }
        assertEquals(setOf("old-1", "old-2"), port.archived.toSet())
        scope.coroutineContext.cancelChildren()
    }

    @Test
    fun `running rows never archive and the sweep is idempotent`() = runBlocking {
        val port = RecordingPort(listOf(task("running", 30, TaskStatus.RUNNING), task("idle-old", 30)))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val vm = TasksViewModel(port, scope)
        vm.autoArchiveDays = 14
        vm.refresh()
        var waited = 0
        while (port.archived.isEmpty() && waited < 200) { Thread.sleep(10); waited += 1 }
        assertEquals(1, port.archived.size)
        assertEquals("idle-old", port.archived.single())
        // a second refresh sees the row already archived (archivedAtMs set) — no re-fire
        vm.refresh()
        waited = 0
        while (port.refreshed.value < 2 && waited < 200) { Thread.sleep(10); waited += 1 }
        assertEquals(1, port.archived.size)
        scope.coroutineContext.cancelChildren()
    }

    @Test
    fun `policy off (the default) never calls the archive verb`() = runBlocking {
        val port = RecordingPort(listOf(task("ancient", 400)))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val vm = TasksViewModel(port, scope)
        assertEquals(0, vm.autoArchiveDays)
        vm.refresh()
        var waited = 0
        while (port.refreshed.value < 1 && waited < 200) { Thread.sleep(10); waited += 1 }
        assertEquals(0, port.archived.size)
        scope.coroutineContext.cancelChildren()
    }
}
