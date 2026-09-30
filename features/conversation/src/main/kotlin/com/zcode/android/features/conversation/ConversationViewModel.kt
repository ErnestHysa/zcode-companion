package com.zcode.android.features.conversation

import androidx.annotation.VisibleForTesting
import com.zcode.android.core.common.error.AppError
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.domain.model.StreamingBuffer
import com.zcode.android.domain.model.Task
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.domain.state.TaskStateMachine
import com.zcode.android.features.conversation.port.ConversationPort
import com.zcode.android.features.conversation.stream.coalesceForUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Conversation screen state (§18.3 row: loading/streaming/offline/error/empty;
 * §19.2 render pipeline). Phase 6: read/stream only — there is deliberately no
 * composer state (Phase 7).
 */
data class ConversationUiState(
    val task: Task? = null,
    val rows: List<ConversationRow> = emptyList(),
    /** rowId → streamed text so far (coalesced ≤ ~30 Hz; §19.2). */
    val streaming: Map<Long, String> = emptyMap(),
    val epoch: String? = null,
    val stale: Boolean = false,
    val taskState: TaskStateMachine.State = TaskStateMachine.State.UNKNOWN,
    val phase: ConnectionPhase = ConnectionPhase.UNPAIRED,
    val loading: Boolean = true,
    val hasMoreHistory: Boolean = false,
    /** Total rows in the session (window metadata; null when unknown) — shown in the load-earlier strip. */
    val totalRows: Long? = null,
    val loadingOlder: Boolean = false,
    val loadOlderError: AppError? = null,
    val historyChanged: Boolean = false,
) {
    val empty: Boolean get() = !loading && rows.isEmpty() && streaming.isEmpty()
}

class ConversationViewModel(
    private val port: ConversationPort,
    private val scope: CoroutineScope,
    private val taskId: String,
) {

    private val _historyChanged = MutableStateFlow(false)
    private val _hasMore = MutableStateFlow(false)
    private val _totalRows = MutableStateFlow<Long?>(null)

    init {
        // FIELD (2026-09-30, vc11): seed §19.4 pagination from the authoritative window
        // metadata (the desktop truncates the initial snapshot — 60-row tail windows over
        // 60k+-seq sessions). Without this the scroll-up prefetch never armed.
        scope.launch {
            port.observeHasMoreHistory(taskId).collect { _hasMore.value = it }
        }
        scope.launch {
            port.observeTotalRows(taskId).collect { _totalRows.value = it }
        }
    }
    private val _loadingOlder = MutableStateFlow(false)
    private val _loadOlderError = MutableStateFlow<AppError?>(null)
    private val _task = MutableStateFlow<Task?>(null)

    /** Raw streaming snapshots, throttled to the render budget (§19.2). */
    @VisibleForTesting
    val streamingCoalesced: StateFlow<Map<Long, String>> = port.observeStreaming(taskId)
        .coalesceForUi(STREAM_FRAME_MS)
        .mapToTextMap()
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private data class RowsEpoch(val rows: List<ConversationRow>, val epoch: String?)
    private data class StaleState(val stale: Boolean, val taskState: TaskStateMachine.State)
    private data class StreamPhase(val streaming: Map<Long, String>, val task: Task?, val phase: ConnectionPhase)
    private data class Flags(
        val hasMore: Boolean,
        val loadingOlder: Boolean,
        val error: AppError?,
        val historyChanged: Boolean,
    )

    val state: StateFlow<ConversationUiState> = combine(
        combine(port.observeRows(taskId), port.observeEpoch(taskId)) { rows, epoch -> RowsEpoch(rows, epoch) },
        combine(port.observeStale(taskId), port.observeTaskState(taskId)) { s, t -> StaleState(s, t) },
        combine(streamingCoalesced, _task, port.phase) { streaming, task, phase -> StreamPhase(streaming, task, phase) },
        combine(_hasMore, _loadingOlder, _loadOlderError, _historyChanged) { h, lo, err, hc -> Flags(h, lo, err, hc) },
    ) { rowsEpoch, staleState, streamPhase, flags ->
        ConversationUiState(
            task = streamPhase.task,
            rows = rowsEpoch.rows,
            streaming = streamPhase.streaming,
            epoch = rowsEpoch.epoch,
            stale = staleState.stale,
            taskState = staleState.taskState,
            phase = streamPhase.phase,
            loading = rowsEpoch.rows.isEmpty() && streamPhase.streaming.isEmpty() &&
                staleState.taskState == TaskStateMachine.State.UNKNOWN && rowsEpoch.epoch == null,
            hasMoreHistory = flags.hasMore,
            totalRows = _totalRows.value,
            loadingOlder = flags.loadingOlder,
            loadOlderError = flags.error,
            historyChanged = flags.historyChanged,
        )
    }.stateIn(scope, SharingStarted.Eagerly, ConversationUiState())

    init {
        scope.launch {
            val task = port.task(taskId)
            _task.value = task
            if (task != null) port.open(task)
        }
    }

    /**
     * §19.2/§19.4 scroll-back: fired by the screen at the top page edge.
     * Prepending shifts indices, so the screen re-anchors by row key after a
     * successful page (the ViewModel returns rowsAdded for that).
     */
    fun loadOlder(beforeRowId: Long, onResult: (rowsAdded: Int) -> Unit = {}) {
        if (_loadingOlder.value) return
        scope.launch {
            _loadingOlder.value = true
            _loadOlderError.value = null
            try {
                val result = port.loadOlder(taskId, beforeRowId)
                if (result.epochRejected) {
                    // §15-S9: the desktop rewound history while we paged — the page was
                    // rejected with zero mutation; re-anchor to the authoritative tail.
                    _historyChanged.value = true
                    _hasMore.value = false
                    onResult(0) // defined contract: zero rows added, no scroll adjust
                } else {
                    _hasMore.value = result.hasMore
                    _loadOlderError.value = result.error
                    onResult(result.rowsAdded)
                }
            } finally {
                _loadingOlder.value = false
            }
        }
    }

    fun dismissHistoryNotice() {
        _historyChanged.value = false
    }

    fun retryOlder(beforeRowId: Long) = loadOlder(beforeRowId)

    private fun kotlinx.coroutines.flow.Flow<List<StreamingBuffer>>.mapToTextMap(): kotlinx.coroutines.flow.Flow<Map<Long, String>> =
        this.map { buffers -> buffers.associate { it.rowId to it.text } }

    companion object {
        /** ≈30 Hz coalescing budget (§19.2 frame-budget aligned). */
        const val STREAM_FRAME_MS = 33L
    }
}

/** Compact label for the app-bar trailing status ("Connected · Running"). */
fun taskStateLabel(state: TaskStateMachine.State): String? = when (state) {
    TaskStateMachine.State.IDLE -> "Idle"
    TaskStateMachine.State.RUNNING, TaskStateMachine.State.LOADING -> "Running"
    TaskStateMachine.State.WAITING_FOR_PERMISSION, TaskStateMachine.State.WAITING_FOR_USER -> "Waiting"
    TaskStateMachine.State.COMPLETED -> "Completed"
    TaskStateMachine.State.FAILED -> "Failed"
    TaskStateMachine.State.CANCELLED -> "Stopped"
    TaskStateMachine.State.UNKNOWN -> null
}
