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
}
