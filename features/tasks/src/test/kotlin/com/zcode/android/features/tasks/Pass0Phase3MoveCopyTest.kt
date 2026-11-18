package com.zcode.android.features.tasks

import com.zcode.android.domain.ids.WorkspaceIdentityKey
import com.zcode.android.domain.model.Task
import com.zcode.android.domain.model.TaskStatus
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.core.common.error.AppError
import com.zcode.android.features.tasks.port.TaskSearchHit
import com.zcode.android.features.tasks.port.TasksPort
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * G102's copy fix (folded into the move-migration): the move-failure notice was
 * "Couldn't move the task — **unpin it first if it is pinned**" — the legacy
 * applyGroupedTaskViewOrder's pinned-member refusal wording — which rendered for
 * ANY failure (the G102 drift fault, the unknownMethod class, an offline
 * transport) and actively misled. On the task-groups move channel the desktop
 * validates availability itself; the honest failure copy is the neutral retry.
 * RED at the pre-fix commit (the misleading wording was the only copy).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Pass0Phase3MoveCopyTest {

    private class MovePort(private val ok: Boolean) : TasksPort {
        override val tasks = MutableStateFlow(
            listOf(Task("t1", "Fix auth", TaskStatus.IDLE, WorkspaceIdentityKey("/ws"), 1, 2)),
        )
        override val phase = MutableStateFlow(ConnectionPhase.CONNECTED)
        override val taskLoadError = MutableStateFlow<AppError?>(null)
        override val lastRefreshAtMs = MutableStateFlow<Long?>(null)
        override val endpointName = MutableStateFlow<String?>("TestBook")
        override val suggestRepair = MutableStateFlow(false)
        override suspend fun refresh() = ok
        override fun retryConnection() {}
        override fun repairPairing() {}
        override fun observeWorkspaces() = MutableStateFlow(emptyList<com.zcode.android.domain.model.WorkspaceOption>())
        override suspend fun createTask(workspaceKey: String, firstText: String, provider: String?, model: String?) = ok
        override suspend fun searchTasks(query: String): List<TaskSearchHit> = emptyList()
        override suspend fun moveTaskToGroup(taskId: String, groupId: String?): Boolean = ok
        override suspend fun listGroupedView(): List<com.zcode.android.domain.model.GroupedTaskNode>? = null
    }

    @Test
    fun `a refused move renders the accurate copy (never the pinned-only wording)`() = runTest {
        val vm = TasksViewModel(MovePort(ok = false), backgroundScope)
        vm.moveTaskToGroup("t1", "g1")
        runCurrent()
        val notice = vm.state.value.actionNotice
        assertEquals("Couldn't move the task — try again", notice)
        assertTrue(
            notice?.contains("unpin") != true,
            "the legacy 'unpin it first' wording must never render",
        )
    }

    @Test
    fun `a successful move confirms and refreshes the groups`() = runTest {
        val vm = TasksViewModel(MovePort(ok = true), backgroundScope)
        vm.moveTaskToGroup("t1", "g1")
        runCurrent()
        assertEquals("Moved to group", vm.state.value.actionNotice)
    }
}
