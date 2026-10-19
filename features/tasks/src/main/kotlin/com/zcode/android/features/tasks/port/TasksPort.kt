package com.zcode.android.features.tasks.port

import com.zcode.android.core.common.error.AppError
import com.zcode.android.domain.model.Task
import com.zcode.android.domain.state.ConnectionPhase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Consumer-side port for the task list (INV-2/INV-6: the feature never sees
 * `core:transport` — the composition root implements this against the hub).
 */
interface TasksPort {
    /** Every task of the paired endpoint across workspaces (grouped in the UI). */
    val tasks: Flow<List<Task>>

    val phase: StateFlow<ConnectionPhase>

    /** Bootstrap/task-load failure — the list shows cached rows + an error strip. */
    val taskLoadError: StateFlow<AppError?>

    val lastRefreshAtMs: StateFlow<Long?>

    val endpointName: StateFlow<String?>

    /** Pull-to-refresh: bootstrap → projection replace (ETag-validated where the plane offers one). */
    suspend fun refresh(): Boolean

    /** Degraded/Failed → manual retry (§14.4.1). */
    fun retryConnection()

    /** Round-4: stale-pairing repair (desktop-disconnected Degraded + old QR) → unpair → re-scan. */
    val suggestRepair: StateFlow<Boolean>

    /** User accepted the re-pair suggestion: wipe the stale pairing (gate flips to pairing). */
    fun repairPairing()

    /** Workspaces offered by the desktop (the new-task picker). */
    fun observeWorkspaces(): Flow<List<com.zcode.android.domain.model.WorkspaceOption>>

    /** Phase 7 field: create a task in an existing workspace; first message starts the turn. */
    suspend fun createTask(
        workspaceKey: String,
        firstText: String,
        provider: String? = null,
        model: String? = null,
    ): Boolean

    /**
     * Phase 4C (C7): `createScratchWorkspace` on the shipped facade (`{name}` →
     * `{path}`) — the phone could never START a scratch workspace. Null = refused
     * or unavailable (the sheet keeps the regular picker).
     */
    suspend fun createScratchWorkspace(name: String): String? = null

    // ---------------------------------------------------------------- Phase 4D (L6.3+J4 / H1.1)

    /**
     * The task lifecycle on the `zcode-task` channel (shipped 3.14.5 facade): delete
     * ("records cannot be recovered" — a typed confirm precedes), rename, and
     * archive/unarchive ("can be restored later"). Null/false results are honest
     * refusals; the UI toasts and refreshes, never guesses.
     */
    suspend fun deleteTask(taskId: String): Boolean = false
    suspend fun renameTask(taskId: String, title: String): Boolean = false
    suspend fun setTaskArchived(taskId: String, archived: Boolean): Boolean = false

    /** H1.1: the desktop's full-text task search (listTaskList; snippets included). */
    suspend fun searchTasks(query: String): List<TaskSearchHit> = emptyList()
}

/** A `listTaskList` hit — the snippet is the desktop's searchable_text projection. */
data class TaskSearchHit(val taskId: String, val title: String, val snippet: String?)
