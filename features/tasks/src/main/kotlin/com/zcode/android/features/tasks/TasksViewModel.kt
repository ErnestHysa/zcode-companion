package com.zcode.android.features.tasks

import com.zcode.android.core.common.error.AppError
import com.zcode.android.domain.model.Task
import com.zcode.android.domain.model.TaskStatus
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.features.tasks.port.TasksPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Task-list screen state (§18.3: loading skeletons / empty / ready / offline-stale;
 * INV-11: every state below is rendered by TaskListScreen — the state matrix test
 * pins each one).
 */
data class TasksUiState(
    val phase: ConnectionPhase = ConnectionPhase.UNPAIRED,
    val loadState: Load = Load.LOADING,
    val groups: List<TaskGroup> = emptyList(),
    val taskCount: Int = 0,
    val error: AppError? = null,
    val refreshing: Boolean = false,
    val lastRefreshAtMs: Long? = null,
    val endpointName: String? = null,
    /** Round-4: show the Re-pair affordance (stale pairing, desktop-disconnected). */
    val suggestRepair: Boolean = false,
    /** Phase 7 field: workspaces offered by the desktop (new-task picker). */
    val workspaces: List<com.zcode.android.domain.model.WorkspaceOption> = emptyList(),
    /** Non-null while a create is in flight; result surfaces once via [createNotice]. */
    val creatingTask: Boolean = false,
    val createNotice: String? = null,
    // ---- Phase 4D (L6.3+J4 / H1.1) ----
    /** H1.1: live task-search results (empty = no query / no matches). */
    val searchResults: List<com.zcode.android.features.tasks.port.TaskSearchHit> = emptyList(),
    /** The active search query (drives the results surface). */
    val searchQuery: String = "",
    /** One-shot lifecycle-action notice (delete/archive/rename outcome). */
    val actionNotice: String? = null,
) {
    enum class Load { LOADING, EMPTY, READY }

    /** Count of rows the user can unblock by looking (Phase 6 observes only). */
    val needsAttention: Int
        get() = groups.sumOf { g -> g.tasks.count { it.status == TaskStatus.WAITING || it.status == TaskStatus.ERROR } }

    /** §14.4.2 Disconnected overlay — cached rows stay visible, LABELED stale (§2.5-5). */
    val stale: Boolean
        get() = (phase == ConnectionPhase.RECONNECTING ||
            phase == ConnectionPhase.DEGRADED ||
            phase == ConnectionPhase.FAILED) && groups.isNotEmpty()
}

data class TaskGroup(
    val workspaceKey: String,
    val label: String,
    val tasks: List<Task>,
)

class TasksViewModel(private val port: TasksPort, private val scope: CoroutineScope) {

    // ---- Phase 4D: task lifecycle + search ----
    private val searchResults = kotlinx.coroutines.flow.MutableStateFlow<List<com.zcode.android.features.tasks.port.TaskSearchHit>>(emptyList())
    private val searchQuery = kotlinx.coroutines.flow.MutableStateFlow("")
    private val actionNotice = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)


    private val refreshing = MutableStateFlow(false)

    private val primary = combine(port.tasks, port.phase, port.taskLoadError) { tasks, phase, error ->
        Triple(tasks, phase, error)
    }
    private val creating = MutableStateFlow(false)
    private val createNotice = MutableStateFlow<String?>(null)

    private val secondary = combine(
        refreshing, port.lastRefreshAtMs, port.endpointName, port.suggestRepair,
    ) { r, l, e, sr ->
        Quad(r, l, e, sr)
    }

    private data class Quad(
        val refreshing: Boolean,
        val lastRefresh: Long?,
        val endpointName: String?,
        val suggestRepair: Boolean,
    )

    @Suppress("UNCHECKED_CAST")
    private val quaternary = kotlinx.coroutines.flow.combine(
        port.observeWorkspaces(),
        creating,
        createNotice,
        searchResults,
        searchQuery,
        actionNotice,
    ) { arr ->
        @kotlin.Suppress("UNCHECKED_CAST")
        Sextet(
            arr[0] as List<com.zcode.android.domain.model.WorkspaceOption>,
            arr[1] as Boolean,
            arr[2] as String?,
            arr[3] as List<com.zcode.android.features.tasks.port.TaskSearchHit>,
            arr[4] as String,
            arr[5] as String?,
        )
    }

    private data class Sextet(
        val workspaces: List<com.zcode.android.domain.model.WorkspaceOption>,
        val creating: Boolean,
        val notice: String?,
        val searchResults: List<com.zcode.android.features.tasks.port.TaskSearchHit>,
        val searchQuery: String,
        val actionNotice: String?,
    )

    val state: StateFlow<TasksUiState> = combine(primary, secondary, quaternary) { (tasks, phase, error), extra, q ->
        TasksUiState(
            searchResults = q.searchResults,
            searchQuery = q.searchQuery,
            actionNotice = q.actionNotice,
            phase = phase,
            loadState = when {
                tasks.isNotEmpty() -> TasksUiState.Load.READY
                error != null -> TasksUiState.Load.EMPTY // cached-empty + error strip — never a dead end
                phase.inFlight() -> TasksUiState.Load.LOADING
                else -> TasksUiState.Load.EMPTY
            },
            groups = groupTasks(tasks),
            taskCount = tasks.size,
            error = error,
            workspaces = q.workspaces,
            creatingTask = q.creating,
            createNotice = q.notice,
            refreshing = extra.refreshing,
            lastRefreshAtMs = extra.lastRefresh,
            endpointName = extra.endpointName,
            suggestRepair = extra.suggestRepair,
        )
    }.stateIn(
        // Eagerly: the VM is screen-scoped (activity-lifecycle scope in this app's
        // manual DI); the list must be live from construction, not first subscription.
        scope,
        SharingStarted.Eagerly,
        TasksUiState(),
    )

    private val _refreshInProgress get() = refreshing
    val isRefreshing: StateFlow<Boolean> = refreshing.asStateFlow()

    /**
     * Create a task in an existing workspace (§6.1 parity; verified createSession
     * wire shape). The desktop's workspace-list push (≤ 2 s) + the explicit
     * refresh surface the new row; the notice is one-shot user copy.
     */
    fun createTask(workspaceKey: String, firstText: String, provider: String? = null, model: String? = null) {
        if (firstText.isBlank() || workspaceKey.isBlank()) return
        scope.launch { createTaskAndWait(workspaceKey, firstText, provider, model) }
    }

    /** Suspend core (the nav layer may drive it directly). Truthful one-shot notice. */
    suspend fun createTaskAndWait(
        workspaceKey: String,
        firstText: String,
        provider: String? = null,
        model: String? = null,
    ): Boolean {
        if (firstText.isBlank() || workspaceKey.isBlank()) return false
        creating.value = true
        try {
            val ok = port.createTask(workspaceKey, firstText, provider, model)
            createNotice.value = if (ok) "Task created" else "Couldn't create the task — try again"
            return ok
        } finally {
            creating.value = false
        }
    }

    fun consumeCreateNotice() {
        createNotice.value = null
    }

    // ---------------------------------------------------------------- Phase 4D lifecycle + search

    /** Delete ("records cannot be recovered" — the UI confirms BEFORE this). */
    fun deleteTask(taskId: String) {
        scope.launch {
            val ok = port.deleteTask(taskId)
            actionNotice.value = if (ok) {
                refresh()
                "Task deleted"
            } else {
                "Couldn't delete the task — try again"
            }
        }
    }

    fun renameTask(taskId: String, title: String) {
        if (title.isBlank()) return
        scope.launch {
            val ok = port.renameTask(taskId, title)
            actionNotice.value = if (ok) {
                refresh()
                "Task renamed"
            } else {
                "Couldn't rename the task — try again"
            }
        }
    }

    fun setTaskArchived(taskId: String, archived: Boolean) {
        scope.launch {
            val ok = port.setTaskArchived(taskId, archived)
            actionNotice.value = if (ok) {
                refresh()
                if (archived) "Task archived — restore it on the desktop" else "Task unarchived"
            } else {
                "Couldn't update the task — try again"
            }
        }
    }

    fun consumeActionNotice() {
        actionNotice.value = null
    }

    /** H1.1: a blank query clears; anything else is a live port search. */
    fun search(query: String) {
        searchQuery.value = query
        if (query.isBlank()) {
            searchResults.value = emptyList()
            return
        }
        scope.launch {
            searchResults.value = port.searchTasks(query)
        }
    }

    fun refresh() {
        if (_refreshInProgress.value) return
        scope.launch {
            _refreshInProgress.value = true
            try {
                port.refresh()
            } finally {
                _refreshInProgress.value = false
            }
        }
    }

    fun retryConnection() = port.retryConnection()

    fun repairPairing() = port.repairPairing()

    private fun ConnectionPhase.inFlight(): Boolean = this == ConnectionPhase.CONNECTING ||
        this == ConnectionPhase.AUTHENTICATING ||
        this == ConnectionPhase.RECOVERING ||
        this == ConnectionPhase.RECONNECTING

    /**
     * §7.11/INV-12: group by the workspace IDENTITY key; the display label is derived
     * conservatively from the key's last segment (paths are display-only metadata).
     * Within a group: waiting tasks first, then live ordering by updatedAt (§18.3).
     */
    private fun groupTasks(tasks: List<Task>): List<TaskGroup> =
        tasks.groupBy { it.workspace }
            .map { (workspace, groupTasks) ->
                TaskGroup(
                    workspaceKey = workspace.raw,
                    label = groupTasks.firstNotNullOfOrNull { it.workspaceLabel }
                        ?: workspace.raw.substringAfterLast('/').ifBlank { workspace.raw },
                    tasks = groupTasks.sortedWith(
                        compareByDescending<Task> { it.status == TaskStatus.WAITING }
                            .thenByDescending { it.updatedAtMs },
                    ),
                )
            }
            .sortedByDescending { g -> g.tasks.maxOfOrNull { it.updatedAtMs } ?: 0L }
}
