package com.zcode.android.data.session

import com.zcode.android.core.common.log.ZLog
import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.domain.ids.SessionId
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.domain.model.StreamingBuffer
import com.zcode.android.domain.reducer.ConversationTopicReducer
import com.zcode.android.domain.repo.ConversationRepository
import com.zcode.android.domain.repo.ConversationStoreOp
import com.zcode.android.domain.state.TaskStateMachine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Per-session projection driver — Phase 5.1/5.2 integration (§19.2 pipeline:
 * "domain events → coalescer → state store"; §28.5 "single-writer per topic").
 *
 * SINGLE-WRITER discipline (§28.5): all state mutations enter through [handle] /
 * [checkHold], which serialize on a per-topic mutex — the two entry points are the
 * message-passing sources (event collector + hold ticker), the mutex preserves the
 * one-writer invariant. No shared mutable collection is touched without it.
 *
 * Responsibilities: run the (logEpoch, seq) stale-guard reducer, persist its store
 * effects through the conversation cache, advance the recovery journal watermark
 * after durability, feed the §14.4.2 task machine from authoritative turnHeader rows
 * (desktop wins — INV-1: an event-class refusal escalates to the authoritative
 * snapshot path, never a local fabrication), and surface the stale overlay.
 */
class ConversationSessionManager(
    val sessionId: SessionId,
    private val endpointId: EndpointId,
    private val store: ConversationRepository,
    private val journalSink: suspend (topic: String, sessionId: String, afterSeq: Long, logEpoch: String) -> Unit,
    private val escalate: suspend (reason: String, baseEpoch: String?, baseSeq: Long?) -> Unit,
    private val onSnapshotApplied: suspend (rows: List<ConversationRow>) -> Unit = {},
    /** Phase 7: authoritative A-side state (pendingInteractions/queue/control) consumer. */
    private val onSessionState: suspend (state: com.zcode.android.domain.model.SessionProjectionState) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: ZLog = ZLog(),
    config: ConversationTopicReducer.Config = ConversationTopicReducer.Config(),
) {

    private val reducer = ConversationTopicReducer(sessionId, clock, config)
    private val mutex = Mutex()
    private val taskMachine = TaskStateMachine()
    private val topic = "conversation/${sessionId.raw}"

    private val _stale = MutableStateFlow(false)
    /** §14.4.2 Disconnected overlay — labeled staleness, never silent stale data (§2.5-5). */
    val stale: StateFlow<Boolean> = _stale.asStateFlow()

    private val _taskState = MutableStateFlow(TaskStateMachine.State.UNKNOWN)
    val taskState: StateFlow<TaskStateMachine.State> = _taskState.asStateFlow()

    /**
     * §19.2 Phase 6 surface: the reducer-held in-memory streaming buffers, published
     * on every streaming-class effect (Phase 5 note: "UI consumers arrive Phase 6").
     * NEVER persisted (§14: streaming deltas are transient); consumers must coalesce
     * (§19.2 pipeline — the UI never observes raw network cadence directly).
     */
    private val _streaming = MutableStateFlow<List<StreamingBuffer>>(emptyList())
    val streaming: StateFlow<List<StreamingBuffer>> = _streaming.asStateFlow()

    /** Rows of the most recent authoritative snapshot (S13 interaction reconcile input). */
    @Volatile
    var lastSnapshotRows: List<ConversationRow> = emptyList()
        private set

    /**
     * Phase 7 A-side projection (§9): stop control, queue, open interactions, input
     * routing, CAS revision. Published on every snapshot/patch apply (single-writer
     * mutation inside the reducer; the publish rides the same critical section).
     */
    private val _sessionState =
        MutableStateFlow(com.zcode.android.domain.model.SessionProjectionState())
    val sessionState: StateFlow<com.zcode.android.domain.model.SessionProjectionState> =
        _sessionState.asStateFlow()

    // ---------------------------------------------------------------- entry points

    suspend fun handle(event: GatewayTopicEvent) = mutex.withLock {
        when (event) {
            is GatewayTopicEvent.Snapshot -> handleSnapshot(event)
            is GatewayTopicEvent.Deltas -> handleDeltas(event)
            // FIELD (2026-09-30, vc9): a DECODE fault is a schema/data fault — resync
            // cannot fix it and the desktop rejects the resync with
            // fault.subscription.notOwned (4 spammed attempts per snapshot in the field).
            // Honest handling: surface + stale label; the next authoritative snapshot
            // (or app fix) recovers — no resync loop.
            is GatewayTopicEvent.DecodeFaulted -> {
                log.warn("session.decodeFault", event.reason.take(120))
                setStale(true)
            }
        }
    }

    /** Hold-window tick (S8): the ticker's only job; serialized with the collector. */
    suspend fun checkHold() = mutex.withLock {
        applyEffects(reducer.onHoldExpired())
    }

    /** S2 journal restore: seed the cursor BEFORE any frame is applied. */
    fun hydrate(logEpoch: String, seq: Long) {
        reducer.adoptEpoch(logEpoch, floorSeq = seq)
    }

    /** §14.4.2 overlay control (driven by the transport state machine). */
    fun setStale(flag: Boolean) {
        _stale.value = flag
        if (flag) taskMachine.markStale() else taskMachine.markFresh()
        _taskState.value = taskMachine.state
    }

    // ---------------------------------------------------------------- transitions

    /**
     * NOTE (2026-09-30): snapshot re-applies were deduped here by (epoch, toSeq) —
     * REVERTED: reconciliation snapshots can repeat the key with NEW content
     * (S12/S13: interaction cards / terminal state reconciled while disconnected).
     * The duplicate-delivery churn was fixed at the SOURCE (path-only listener scope
     * keys — one listener per workspace); re-applying stays always-safe.
     */

    /** §19.4 backward pagination: more history above the current tail window. */
    private val _hasMoreHistory = kotlinx.coroutines.flow.MutableStateFlow(false)
    val hasMoreHistory: kotlinx.coroutines.flow.StateFlow<Boolean> = _hasMoreHistory.asStateFlow()

    /** Total rows in the session (from the window metadata; null when unknown). */
    private val _totalRows = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)
    val totalRows: kotlinx.coroutines.flow.StateFlow<Long?> = _totalRows.asStateFlow()

    /**
     * Defense-in-depth duplicate guard (round 16): the same (epoch, toSeq) snapshot can
     * legitimately repeat with NEW content (S12/S13 reconciliation), so the key includes
     * a rows-content hash. Identical content + identical position ⇒ re-applying 60 rows
     * to Room is pure churn — skip. Different content always applies.
     */
    private var lastAppliedSnapshotKey: String? = null

    private suspend fun handleSnapshot(e: GatewayTopicEvent.Snapshot) {
        val dedupeKey = "${e.logEpoch}/${e.toSeq}/${e.firstRowId}/${e.rows.size}/${e.rows.hashCode()}"
        if (dedupeKey == lastAppliedSnapshotKey) {
            log.debug("manager.snapshotDup", "session=${sessionId.raw} identical snapshot skipped (epoch=${e.logEpoch.take(16)} seq=${e.toSeq})")
            return
        }
        lastAppliedSnapshotKey = dedupeKey
        log.debug("manager.snapshot", "session=${sessionId.raw} rows=${e.rows.size} epoch=${e.logEpoch.take(24)}")
        lastSnapshotRows = e.rows
        _totalRows.value = e.totalCount
        e.sessionState?.let {
            _sessionState.value = it
            onSessionState(it)
        }
        // rowsWindowSchema: window's first row == firstRowId ⇔ at top (null = no window info).
        _hasMoreHistory.value = e.firstRowId != null &&
            (e.rows.isEmpty() || e.rows.first().rowId != e.firstRowId)
        log.debug(
            "manager.hasMore",
            "session=${sessionId.raw} firstRowId=${e.firstRowId} total=${e.totalCount} firstCached=${e.rows.firstOrNull()?.rowId} hasMore=${_hasMoreHistory.value}",
        )
        applyEffects(reducer.onSnapshot(TopicSnapshotOf(e)))
        observeRowsForTaskState(e.rows)
        onSnapshotApplied(e.rows)
    }

    private suspend fun handleDeltas(e: GatewayTopicEvent.Deltas) {
        log.debug("manager.deltas", "session=${sessionId.raw} seq=${e.fromSeq}..${e.toSeq} ops=${e.ops.size}")
        val effects = reducer.onDeltas(TopicDeltasOf(e))
        applyEffects(effects)
        // Task-machine observations ride the applied ops (authoritative turnHeader rows).
        for (effect in effects) {
            if (effect is ConversationTopicReducer.Effect.Persist) {
                for (op in effect.ops) {
                    if (op is ConversationStoreOp.RowUpserted) observeRowForTaskState(op.row)
                }
            }
        }
        // Phase 7: publish the A-side projection after any state.updated op.
        if (e.ops.any { it is com.zcode.android.domain.reducer.ProjectionOp.StateUpdated }) {
            _sessionState.value = reducer.sessionProjection
            onSessionState(reducer.sessionProjection)
        }
    }

    private suspend fun applyEffects(effects: List<ConversationTopicReducer.Effect>) {
        var escalateRequest: Pair<String, Pair<String, Long>?>? = null
        for (effect in effects) {
            when (effect) {
                is ConversationTopicReducer.Effect.Persist -> {
                    for (op in effect.ops) {
                        val result = store.apply(op)
                        // Store-level epoch rejection = generation skew — escalate rather
                        // than merge (§15-S9/S10; the store rejected with zero mutation).
                        if (result is com.zcode.android.domain.repo.StoreApplyResult.EpochRejected) {
                            escalateRequest = "store epoch reject (cached=${result.currentEpoch.take(24)})" to null
                        }
                    }
                }
                is ConversationTopicReducer.Effect.CursorAdvanced ->
                    journalSink(topic, sessionId.raw, effect.seq, effect.epoch)
                is ConversationTopicReducer.Effect.EpochAdopted -> Unit // manager applies via adoptEpoch
                is ConversationTopicReducer.Effect.StreamingCleared,
                is ConversationTopicReducer.Effect.StreamingTrimmedFrom,
                is ConversationTopicReducer.Effect.StreamingUpserted,
                -> publishStreaming() // §19.2 Phase 6 UI surface
                is ConversationTopicReducer.Effect.EscalateResync -> {
                    escalateRequest = effect.reason to effect.base?.let { it.logEpoch to it.seq }
                }
            }
        }
        _taskState.value = taskMachine.state
        escalateRequest?.let { (reason, base) -> escalate(reason, base?.first, base?.second) }
    }

    // ---------------------------------------------------------------- task machine (§14.4.2)

    /** Snapshot the reducer's in-memory streaming buffers into the UI-facing flow. */
    private fun publishStreaming() {
        _streaming.value = reducer.streamingBuffersForTest().toList()
    }

    private fun observeRowsForTaskState(rows: List<ConversationRow>) {
        val latestTurn = rows.filterIsInstance<ConversationRow.TurnHeader>().maxByOrNull { it.createdAtSeq }
        latestTurn?.let { observeTurnState(it.state, authoritative = true) }
        _taskState.value = taskMachine.state
    }

    private fun observeRowForTaskState(row: ConversationRow) {
        if (row is ConversationRow.TurnHeader) observeTurnState(row.state, authoritative = false)
    }

    /**
     * Authoritative turnHeader state → machine event (INV-1: desktop wins). Event-class
     * illegal transitions (the §14.4.2 overlay protections) escalate to the snapshot
     * path — an authoritative observation REPLACES the overlay, it never merges.
     */
    private fun observeTurnState(state: ConversationRow.TurnState, authoritative: Boolean) {
        // FIELD (2026-10-01, vc20 device log): opening a COMPLETED conversation from a
        // cold start hit `UNKNOWN --TurnCompleted-->` — illegal on the event path, then
        // "fixed" by an authoritative replace — producing a spurious WARN for a perfectly
        // normal first observation. The FIRST authoritative observation of an
        // un-started machine is a SNAPSHOT (§14.4.2 "Unknown → any per status"), not an
        // event; only genuinely running machines take the event path.
        if (authoritative && taskMachine.state in setOf(
                TaskStateMachine.State.UNKNOWN, TaskStateMachine.State.LOADING, TaskStateMachine.State.IDLE,
            )
        ) {
            if (state != ConversationRow.TurnState.UNKNOWN) {
                taskMachine.applySnapshot(mapTurnToTaskState(state))
                _taskState.value = taskMachine.state
            }
            return
        }
        try {
            when (state) {
                ConversationRow.TurnState.RUNNING -> {
                    if (taskMachine.state in setOf(
                            TaskStateMachine.State.UNKNOWN, TaskStateMachine.State.LOADING,
                            TaskStateMachine.State.IDLE,
                        )
                    ) {
                        taskMachine.applySnapshot(TaskStateMachine.State.RUNNING)
                    }
                }
                ConversationRow.TurnState.COMPLETED_SUCCESS, ConversationRow.TurnState.COMPLETED_INTERRUPTED ->
                    taskMachine.on(TaskStateMachine.Event.TurnCompleted)
                ConversationRow.TurnState.FAILED -> taskMachine.on(TaskStateMachine.Event.TurnFailed)
                ConversationRow.TurnState.UNKNOWN -> Unit
            }
        } catch (e: TaskStateMachine.IllegalTaskTransition) {
            if (authoritative) {
                taskMachine.applySnapshot(mapTurnToTaskState(state))
                log.warn("task.authoritativeReplace", e.message?.take(80))
            } else {
                log.warn("task.eventRefused", e.message?.take(80)) // overlay protections hold
            }
        }
    }

    private fun mapTurnToTaskState(state: ConversationRow.TurnState): TaskStateMachine.State = when (state) {
        ConversationRow.TurnState.RUNNING -> TaskStateMachine.State.RUNNING
        ConversationRow.TurnState.COMPLETED_SUCCESS, ConversationRow.TurnState.COMPLETED_INTERRUPTED ->
            TaskStateMachine.State.COMPLETED
        ConversationRow.TurnState.FAILED -> TaskStateMachine.State.FAILED
        ConversationRow.TurnState.UNKNOWN -> TaskStateMachine.State.UNKNOWN
    }

    // ---------------------------------------------------------------- mapping helpers

    private fun TopicSnapshotOf(e: GatewayTopicEvent.Snapshot): com.zcode.android.domain.reducer.TopicEvent.Snapshot =
        com.zcode.android.domain.reducer.TopicEvent.Snapshot(
            topic = e.topic, sessionId = e.sessionId, logEpoch = e.logEpoch,
            fromSeq = e.fromSeq, toSeq = e.toSeq, rows = e.rows,
            sessionState = e.sessionState,
        )

    private fun TopicDeltasOf(e: GatewayTopicEvent.Deltas): com.zcode.android.domain.reducer.TopicEvent.Deltas =
        com.zcode.android.domain.reducer.TopicEvent.Deltas(
            topic = e.topic, sessionId = e.sessionId, logEpoch = e.logEpoch,
            fromSeq = e.fromSeq, toSeq = e.toSeq, ops = e.ops,
        )

    /** Diagnostics (INV-5 observability). */
    suspend fun cachedRowCount(): Int = store.cachedRowCount(sessionId)

    fun reducerForTest(): ConversationTopicReducer = reducer
}
