package com.zcode.android.features.conversation.port

import com.zcode.android.core.common.error.AppError
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.domain.model.StreamingBuffer
import com.zcode.android.domain.model.Task
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.domain.state.TaskStateMachine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Consumer-side port for the conversation surface (INV-2/INV-6: no transport types).
 * Phase 6 is READ/STREAM only — there is intentionally NO send/answer method; the
 * write path is Phase 7 (§43.2) and its absence here is the phase boundary made
 * structural.
 */
interface ConversationPort {
    /** App-level connection phase (§14.4.1 UI mirror) for the status line. */
    val phase: StateFlow<ConnectionPhase>

    suspend fun task(taskId: String): Task?

    fun observeRows(taskId: String): Flow<List<ConversationRow>>

    fun observeEpoch(taskId: String): Flow<String?>

    /** §14.4.2 Disconnected overlay (labeled staleness). */
    fun observeStale(taskId: String): Flow<Boolean>

    fun observeTaskState(taskId: String): Flow<TaskStateMachine.State>

    /** Reducer-held in-memory streaming buffers; consumers MUST coalesce (§19.2). */
    fun observeStreaming(taskId: String): Flow<List<StreamingBuffer>>

    /** More history above the current tail window (drives "load earlier"). */
    fun observeHasMoreHistory(taskId: String): Flow<Boolean>

    /** Total rows in the session (window metadata; null when unknown). */
    fun observeTotalRows(taskId: String): Flow<Long?>

    /** Subscribe (Phase 5 coordinator path; journal restore included). */
    suspend fun open(task: Task): Boolean

    /**
     * §19.4 scroll-back: rowsRange → page cache. `epochRejected=true` = §15-S9
     * epoch move — the UI shows the "history changed" notice and re-anchors to tail.
     */
    suspend fun loadOlder(taskId: String, beforeRowId: Long): LoadOlderResult

    data class LoadOlderResult(
        val rowsAdded: Int,
        val hasMore: Boolean,
        val error: AppError?,
        val epochRejected: Boolean,
    )
}
