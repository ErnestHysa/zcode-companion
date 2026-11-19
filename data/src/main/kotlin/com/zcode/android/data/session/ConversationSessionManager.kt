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
    /** G51 (vc52 Phase 11): the declared workflow-run delta consumer (the run plane). */
    private val onWorkflowRunDelta: suspend (delta: com.zcode.android.domain.model.WorkflowRunDelta) -> Unit = {},
    /**
     * G71 (vc52 Phase 12): the captured hook review ALSO reaches the process ledger —
     * the manager dies on conversation exit (GAP-A3) and the Settings → Hooks viewer
     * must still serve what the workspace runs AFTER you leave (the exit-gate rig leg
     * caught the empty-after-close face).
     */
    val onHookReviewCaptured: (review: com.zcode.android.domain.model.WorkspaceHookReview) -> Unit = {},
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
     * §14.2 command-issuer overlay (model/mode row: "Android = command issuer +
     * echo; runtime echo wins"): an ACCEPTED switchModelConfig/switchCollaborationMode
     * is reflected locally so the picker state is truthful immediately; the next
     * authoritative snapshot/`state.updated` patch REPLACES it wholesale (the overlay
     * never merges into rows and never survives an echo — INV-1: desktop wins).
     * FIELD (2026-10-04, vc33): accepted switches showed no change until (and unless)
     * the echo patch arrived.
     */
    fun applyLocalConfigOverlay(provider: String, model: String, thought: String, mode: String? = null) {
        val current = _sessionState.value
        // Empty args mean "not part of this switch" (a mode-only overlay must not
        // clobber the model fields) — keep the current values then.
        val effProvider = provider.ifBlank { current.config?.provider ?: "" }
        val effModel = model.ifBlank { current.config?.model ?: "" }
        val effThought = thought.ifBlank { current.config?.thought ?: "" }
        val base = current.config ?: com.zcode.android.domain.model.SessionConfigState(
            provider = effProvider, model = effModel, thought = effThought,
            thoughtLevels = listOf(effThought), followupMode = null, mode = mode ?: "build",
            planEnabled = null, modelSelectionProviderId = null, modelSelectionModelId = null,
        )
        _sessionState.value = current.copy(
            config = base.copy(
                provider = effProvider, model = effModel, thought = effThought,
                modelSelectionProviderId = effProvider.ifBlank { null } ?: base.modelSelectionProviderId,
                modelSelectionModelId = effModel.ifBlank { null } ?: base.modelSelectionModelId,
            ).let { if (mode != null) it.copy(mode = mode) else it },
        )
    }

    /**
     * Phase 7 A-side projection (§9): stop control, queue, open interactions, input
     * routing, CAS revision. Published on every snapshot/patch apply (single-writer
     * mutation inside the reducer; the publish rides the same critical section).
     */
    private val _sessionState =
        MutableStateFlow(com.zcode.android.domain.model.SessionProjectionState())
    val sessionState: StateFlow<com.zcode.android.domain.model.SessionProjectionState> =
        _sessionState.asStateFlow()

    /**
     * G51 (vc52 Phase 11): the session's workflow-run DELTA plane — the step graphs
     * assembled from `workflowRun.updated`/`workflowRun.removed` ops plus the
     * deltas-flowing face (the consumer stops the run poll ticker once this is up).
     */
    private val _workflowRunPlane =
        MutableStateFlow(com.zcode.android.domain.model.WorkflowRunPlane.EMPTY)
    val workflowRunPlane: StateFlow<com.zcode.android.domain.model.WorkflowRunPlane> =
        _workflowRunPlane.asStateFlow()

    /**
     * G71 (vc52 Phase 12): the LAST workspace hook review this session has seen,
     * retained after the trust card resolves (the review interaction leaves
     * `pendingInteractions`, but Settings → Hooks must still show what the workspace
     * runs and each item's trust state). Replaced — never nulled — by later reviews.
     */
    private val _lastHookReview =
        MutableStateFlow<com.zcode.android.domain.model.WorkspaceHookReview?>(null)
    val lastHookReview: StateFlow<com.zcode.android.domain.model.WorkspaceHookReview?> =
        _lastHookReview.asStateFlow()

    /**
     * F-P45-B (pass-0 Phase 2; the pass-45 AV.I live receipt): EVERY hook review
     * this session has seen, keyed by the PAYLOAD's own `review.taskId` — a
     * snapshot may carry TWO workspaces' pending reviews at once (the desktop's
     * normal multi-workspace pending state; MOCK_HOOKS_SECOND seeds the second).
     * The old `lastOrNull` capture lost all-but-last and filed the survivor
     * under the OPENED session's id: synth-task-1's manager rendered ONE hybrid
     * group (workspace-Two's payload under synth-task-1's key) and its Refresh
     * re-issued the WRONG workspace's review args. Latest per key wins (the
     * desktop re-pends the same task with a fresh generation).
     */
    private val _lastHookReviews =
        MutableStateFlow<Map<String, com.zcode.android.domain.model.WorkspaceHookReview>>(emptyMap())
    val lastHookReviews: StateFlow<Map<String, com.zcode.android.domain.model.WorkspaceHookReview>> =
        _lastHookReviews.asStateFlow()

    private fun captureHookReview(state: com.zcode.android.domain.model.SessionProjectionState) {
        val reviews = state.pendingInteractions
            ?.mapNotNull { pi ->
                if (pi.kind == com.zcode.android.domain.model.PendingInteraction.Kind.WORKSPACE_HOOK_REVIEW) pi.hookReview else null
            }
            .orEmpty()
        if (reviews.isEmpty()) return
        val current = _lastHookReviews.value
        var changed = false
        val next = current.toMutableMap()
        var lastChanged: com.zcode.android.domain.model.WorkspaceHookReview? = null
        for (review in reviews) {
            val existing = next[review.taskId]
            if (existing == null || review.generation > existing.generation ||
                (review.generation == existing.generation && review.createdAtMs >= existing.createdAtMs && review != existing)
            ) {
                next[review.taskId] = review
                changed = true
                lastChanged = review
            }
        }
        if (!changed) return
        _lastHookReviews.value = next
        // Compat face (G71's single-review contract): the latest change wins.
        lastChanged?.let { _lastHookReview.value = it }
        for (review in next.values) {
            if (current[review.taskId] != review) {
                runCatching { onHookReviewCaptured(review) }
            }
        }
    }

    /** The epoch the run plane is anchored on (snapshot re-anchor resets it). */
    @Volatile
    private var planeEpoch: String? = null

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
            // Phase 2 (§11.2): the workspace-config catalog is NOT session-plane
            // state — the hub's own collector consumes it; the manager ignores it
            // (never stale-flags the session, never touches the reducer).
            is GatewayTopicEvent.WorkspaceConfig -> Unit
            // F-P13-A: the record-less delta event is consumed by the coordinator's
            // collector (the re-anchor); the manager never sees a frame it cannot
            // classify. Defensive branch — the collector intercepts before this.
            is GatewayTopicEvent.RecordlessDelta -> Unit
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
     * F-P36-A: the snapshot window's first rowId (the session's true top). The store's
     * cache bound vs this decides "more history above the cache" — the WIRE-window
     * derivation goes stale the moment a bound trim re-opens history a page had closed
     * (the receipted stranded hasMore=false: head rows unreachable for the session).
     */
    @Volatile
    private var windowFirstRowId: Long? = null

    /**
     * F-P36-A + N-BM-1: re-derive the paging plane from the STORE's actual bounds.
     * Called after every store apply that can change them (snapshot / row delta / a
     * page the port applied directly), so the flow the UI mirrors is always current:
     *
     *  - hasMore ⇔ minCachedRowId > windowFirstRowId (the cache no longer reaches the
     *    session top — reachable head rows exist);
     *  - total = max(snapshot totalCount, maxCachedRowId - windowFirstRowId + 1) — the
     *    snapshot total is subscribe-time stale while deltas append (the receipted
     *    "370 of 339" / "420 of 339" faces); the extent derivation never regresses it,
     *    and a rewind snapshot RESETS the total before this merge (authoritative).
     */
    suspend fun rederivePagingFromStore() {
        val first = windowFirstRowId
        val min = store.minCachedRowId(sessionId)
        val max = store.maxCachedRowId(sessionId)
        // No bounds signal (empty store / an inert fake): keep the last derivation.
        if (min == null && max == null) return
        if (first != null) {
            _hasMoreHistory.value = min == null || min > first
        }
        val derived = max?.let { it - (first ?: 1L) + 1L }
        _totalRows.value = listOfNotNull(_totalRows.value, derived).maxOrNull()
    }

    /**
     * Defense-in-depth duplicate guard (round 16): the same (epoch, toSeq) snapshot can
     * legitimately repeat with NEW content (S12/S13 reconciliation), so the key includes
     * a rows-content hash. Identical content + identical position ⇒ re-applying 60 rows
     * to Room is pure churn — skip. Different content always applies.
     */
    private var lastAppliedSnapshotKey: String? = null

    private suspend fun handleSnapshot(e: GatewayTopicEvent.Snapshot) {
        // G51: the run-delta plane anchors on the CURRENT epoch — a new epoch
        // (recovery/resync re-subscribe) re-serves the runs; stale graphs from the
        // dead epoch are reset (the deltas-flowing face resets with them, so the
        // poll ticker resumes until deltas flow again — honest reconnect freshness).
        if (_workflowRunPlane.value.deltasSeen || _workflowRunPlane.value.graphs.isNotEmpty()) {
            if (planeEpoch != e.logEpoch) {
                _workflowRunPlane.value = com.zcode.android.domain.model.WorkflowRunPlane.EMPTY
            }
        }
        planeEpoch = e.logEpoch
        val dedupeKey = "${e.logEpoch}/${e.toSeq}/${e.firstRowId}/${e.rows.size}/${e.rows.hashCode()}"
        if (dedupeKey == lastAppliedSnapshotKey) {
            log.debug("manager.snapshotDup", "session=${sessionId.raw} identical snapshot skipped (epoch=${e.logEpoch.take(16)} seq=${e.toSeq})")
            return
        }
        lastAppliedSnapshotKey = dedupeKey
        log.debug("manager.snapshot", "session=${sessionId.raw} rows=${e.rows.size} epoch=${e.logEpoch.take(24)}")
        lastSnapshotRows = e.rows
        // The snapshot's own totalCount is AUTHORITATIVE (a rewind lowers it) — the
        // re-derive below only ever raises it with the store's live extent.
        _totalRows.value = e.totalCount
        windowFirstRowId = e.firstRowId
        e.sessionState?.let {
            _sessionState.value = it
            captureHookReview(it)
            onSessionState(it)
        }
        // rowsWindowSchema: the window's first row vs the SESSION top — but F-P36-A:
        // derive from the STORE's post-trim bounds, not the wire window (the snapshot's
        // own enforceBound may have evicted the head). Runs after applyEffects.
        applyEffects(reducer.onSnapshot(TopicSnapshotOf(e)))
        rederivePagingFromStore()
        log.debug(
            "manager.hasMore",
            "session=${sessionId.raw} firstRowId=${e.firstRowId} total=${e.totalCount} firstCached=${e.rows.firstOrNull()?.rowId} hasMore=${_hasMoreHistory.value}",
        )
        observeRowsForTaskState(e.rows)
        onSnapshotApplied(e.rows)
    }

    private suspend fun handleDeltas(e: GatewayTopicEvent.Deltas) {
        log.debug("manager.deltas", "session=${sessionId.raw} seq=${e.fromSeq}..${e.toSeq} ops=${e.ops.size}")
        val staleBefore = _stale.value
        val effects = reducer.onDeltas(TopicDeltasOf(e))
        applyEffects(effects)
        // FIELD (2026-10-01, vc23 device): contiguous deltas APPLIED while the stale
        // overlay is up are live evidence — the projection is flowing again even if
        // the transport machine is still RECOVERING. Clear the overlay: §2.5-5 —
        // "cached/recovering" must never be shown over provably-live data.
        if (staleBefore && effects.any { it is ConversationTopicReducer.Effect.CursorAdvanced }) {
            log.info("manager.staleCleared", "live deltas applied while stale — overlay cleared")
            setStale(false)
        }
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
            captureHookReview(reducer.sessionProjection)
            onSessionState(reducer.sessionProjection)
        }
        // G51 (vc52 Phase 11): the declared workflow-run delta plane — fold the ops
        // into the per-session plane and publish (the consumer merges the run
        // summaries + stops the poll ticker once deltas flow).
        val runDeltas = e.ops.mapNotNull { op ->
            when (op) {
                is com.zcode.android.domain.reducer.ProjectionOp.WorkflowRunUpdated -> op.delta
                is com.zcode.android.domain.reducer.ProjectionOp.WorkflowRunRemoved ->
                    com.zcode.android.domain.model.WorkflowRunDelta.Removed(op.runId, op.revision, kotlinx.serialization.json.JsonObject(emptyMap()))
                else -> null
            }
        }
        for (delta in runDeltas) {
            _workflowRunPlane.value = com.zcode.android.domain.model.WorkflowRunPlane.apply(_workflowRunPlane.value, delta)
            planeEpoch = e.logEpoch
            log.debug("manager.runDelta", "runId=" + delta.runId + " count=" + _workflowRunPlane.value.deltaCount + " graphs=" + _workflowRunPlane.value.graphs.keys)
            onWorkflowRunDelta(delta)
        }
    }

    private suspend fun applyEffects(effects: List<ConversationTopicReducer.Effect>) {
        var escalateRequest: Pair<String, Pair<String, Long>?>? = null
        var pagingBoundsChanged = false
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
                        // F-P36-A: these are the ops whose apply path runs enforceBound
                        // (upserts append the tail / removals truncate) — a trim may have
                        // re-opened history above the cache.
                        if (op is ConversationStoreOp.RowUpserted || op is ConversationStoreOp.RowRemoved) {
                            pagingBoundsChanged = true
                        }
                    }
                }
                is ConversationTopicReducer.Effect.CursorAdvanced ->
                    journalSink(topic, sessionId.raw, effect.seq, effect.epoch)
                is ConversationTopicReducer.Effect.EpochAdopted -> Unit // manager applies via adoptEpoch
                is ConversationTopicReducer.Effect.StreamingCleared,
                is ConversationTopicReducer.Effect.StreamingTrimmedFrom,
                is ConversationTopicReducer.Effect.StreamingUpserted,
                is ConversationTopicReducer.Effect.StreamingDiscarded,
                -> publishStreaming() // §19.2 Phase 6 UI surface
                is ConversationTopicReducer.Effect.EscalateResync -> {
                    escalateRequest = effect.reason to effect.base?.let { it.logEpoch to it.seq }
                }
            }
        }
        _taskState.value = taskMachine.state
        if (pagingBoundsChanged) rederivePagingFromStore()
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
