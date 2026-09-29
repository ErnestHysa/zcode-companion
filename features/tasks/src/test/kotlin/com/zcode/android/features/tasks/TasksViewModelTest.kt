package com.zcode.android.features.tasks

import com.zcode.android.core.common.error.AppError
import com.zcode.android.core.common.error.Correlation
import com.zcode.android.core.common.error.ErrorCategory
import com.zcode.android.core.common.error.RecoveryAction
import com.zcode.android.domain.ids.WorkspaceIdentityKey
import com.zcode.android.domain.model.Task
import com.zcode.android.domain.model.TaskStatus
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.features.tasks.port.TasksPort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * §18.3 task-list state machine (INV-11 state matrix at the ViewModel level):
 * loading skeletons / empty / ready / stale / error, workspace grouping (§7.11),
 * "needs attention first" ordering, and the ≤2 s live-status path shape.
 */
class TasksViewModelTest {

    private class FakePort : TasksPort {
        val tasksFlow = MutableStateFlow<List<Task>>(emptyList())
        override val tasks: Flow<List<Task>> get() = tasksFlow
        override val phase = MutableStateFlow(ConnectionPhase.CONNECTING)
        override val taskLoadError = MutableStateFlow<AppError?>(null)
        override val lastRefreshAtMs = MutableStateFlow<Long?>(null)
        override val endpointName = MutableStateFlow<String?>("TestBook")
        var refreshCalls = 0
        override suspend fun refresh(): Boolean {
            refreshCalls++
            return true
        }
        var retryCalls = 0
        override fun retryConnection() {
            retryCalls++
        }
        override val suggestRepair = MutableStateFlow(false)
        var repairCalls = 0
        override fun repairPairing() {
            repairCalls++
        }
    }

    private fun task(
        id: String,
        status: TaskStatus,
        workspace: String = "/ws/a",
        updated: Long = 1_000L,
        created: Long = 900L,
    ) = Task(
        taskId = id,
        title = "Task $id",
        status = status,
        workspace = WorkspaceIdentityKey(workspace),
        createdAtMs = created,
        updatedAtMs = updated,
    )

    private fun TestScope.buildVm(port: FakePort): TasksViewModel = TasksViewModel(port, backgroundScope)

    /**
     * The stateIn(Eagerly) share-start hops real dispatchers on its way up; virtual
     * time alone under-advances it. Advance virtual time AND yield real time until
     * the condition holds (bounded — a silent hang is worse than a failure).
     */
    private suspend fun TestScope.eventually(timeoutMs: Long = 2_000, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            advanceUntilIdle()
            if (cond()) return true
            withContext(Dispatchers.Default) { delay(10) }
        }
        advanceUntilIdle()
        return cond()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `in-flight phase with no data renders loading skeletons`() = runTest(StandardTestDispatcher()) {
        val port = FakePort()
        val vm = buildVm(port)
        advanceUntilIdle()
        assertEquals(TasksUiState.Load.LOADING, vm.state.value.loadState)
        assertTrue(eventually { vm.state.value.loadState == TasksUiState.Load.LOADING })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `ready state groups by workspace identity and orders waiting first`() = runTest(StandardTestDispatcher()) {
        val port = FakePort()
        port.phase.value = ConnectionPhase.CONNECTED
        port.tasksFlow.value = listOf(
            task("a", TaskStatus.COMPLETED, workspace = "/ws/a", updated = 5_000),
            task("b", TaskStatus.WAITING, workspace = "/ws/a", updated = 1_000),
            task("c", TaskStatus.RUNNING, workspace = "/ws/b", updated = 9_000),
        )
        val vm = buildVm(port)
        assertTrue(eventually { vm.state.value.loadState == TasksUiState.Load.READY })
        val state = vm.state.value
        assertEquals(TasksUiState.Load.READY, state.loadState)
        assertEquals(3, state.taskCount)
        assertEquals(2, state.groups.size)
        val groupA = state.groups.single { it.workspaceKey == "/ws/a" }
        assertEquals(listOf("b", "a"), groupA.tasks.map { it.taskId }) // waiting pinned above completed
        assertTrue(state.groups[0].workspaceKey == "/ws/b") // most recently updated group first
        assertEquals("b", state.groups[1].tasks.first().taskId)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `empty when connected but no tasks`() = runTest(StandardTestDispatcher()) {
        val port = FakePort()
        port.phase.value = ConnectionPhase.CONNECTED
        val vm = buildVm(port)
        assertTrue(eventually { vm.state.value.loadState == TasksUiState.Load.EMPTY })
        assertEquals(TasksUiState.Load.EMPTY, vm.state.value.loadState)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `reconnecting with cached rows is stale-labeled (never silent stale)`() = runTest(StandardTestDispatcher()) {
        val port = FakePort()
        port.phase.value = ConnectionPhase.CONNECTED
        port.tasksFlow.value = listOf(task("a", TaskStatus.RUNNING))
        val vm = buildVm(port)
        assertTrue(eventually { !vm.state.value.stale })
        port.phase.value = ConnectionPhase.RECONNECTING
        assertTrue(eventually { vm.state.value.stale })
        port.phase.value = ConnectionPhase.DEGRADED
        assertTrue(eventually { vm.state.value.stale })
        port.phase.value = ConnectionPhase.CONNECTED
        assertTrue(eventually { !vm.state.value.stale })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `bootstrap error surfaces while keeping cache visible (error strip + rows)`() = runTest(StandardTestDispatcher()) {
        val port = FakePort()
        port.phase.value = ConnectionPhase.CONNECTED
        port.tasksFlow.value = listOf(task("a", TaskStatus.RUNNING))
        val vm = buildVm(port)
        assertTrue(eventually { vm.state.value.loadState == TasksUiState.Load.READY })
        port.taskLoadError.value = AppError(
            ErrorCategory.SERVER, "ZCode relay error — retrying", "diag",
            AppError.Retryable.AUTO, recoverable = true,
            autoRecovery = RecoveryAction.RECONNECT, correlation = Correlation(),
        )
        assertTrue(eventually { vm.state.value.error?.category == ErrorCategory.SERVER })
        val state = vm.state.value
        assertEquals(TasksUiState.Load.READY, state.loadState)
        assertEquals(ErrorCategory.SERVER, state.error?.category)
        assertTrue(state.groups.isNotEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `refresh and retry funnel to the port`() = runTest(StandardTestDispatcher()) {
        val port = FakePort()
        val vm = TasksViewModel(port, backgroundScope)
        vm.refresh()
        assertTrue(eventually { port.refreshCalls == 1 })
        vm.retryConnection()
        assertEquals(1, port.retryCalls)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `needsAttention counts waiting and failed rows`() {
        val state = TasksUiState(
            phase = ConnectionPhase.CONNECTED,
            groups = listOf(
                TaskGroup("/ws/a", "a", listOf(task("w", TaskStatus.WAITING), task("f", TaskStatus.ERROR), task("ok", TaskStatus.COMPLETED))),
            ),
        )
        assertEquals(2, state.needsAttention)
    }
}
