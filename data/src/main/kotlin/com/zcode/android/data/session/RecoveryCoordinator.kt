package com.zcode.android.data.session

import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.transport.ConnectionStateMachine
import com.zcode.android.core.transport.Transport
import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.domain.ids.SessionId
import com.zcode.android.domain.journal.RecoveryJournal
import com.zcode.android.domain.model.NotificationRecord
import com.zcode.android.domain.recovery.NotificationReconciler
import com.zcode.android.domain.repo.JournalRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Recovery coordinator — Phase 5.3 (§15.1/§15.4, §28.5): journal-driven
 * resubscribe/resync/snapshot escalation per endpoint.
 *
 * Flow on start (cold or warm): restore the §15.4 journal → per session, hydrate the
 * persisted watermark and SUBSCRIBE (the relay re-anchor; the store/guard machinery
 * then classifies snapshot-replace vs gap-free) → forward gateway events into the
 * per-session single-writer managers → tick hold windows → observe the transport
 * machine for stale overlays and the reconcile-on-recovery pass (S12/S13).
 *
 * Escalations (S8/S10): a manager's resync request re-enters through [escalate]
 * under the per-endpoint recovery mutex (§28.5 "reconnect/recovery takes a
 * per-endpoint mutex; no double resync") and issues v4/conversation/resync —
 * resume-from-watermark or forced snapshot. No local fill exists anywhere.
 */
class RecoveryCoordinator(
    private val endpointId: EndpointId,
    private val gateway: ConversationGateway,
    private val store: com.zcode.android.domain.repo.ConversationRepository,
    private val journalRepo: JournalRepository,
    private val interactions: InteractionCoordinator,
    private val transport: Transport? = null,
    private val notificationRecords: NotificationRecordPort? = null,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: ZLog = ZLog(),
    private val config: Config = Config(),
) {

    data class Config(
        val holdTickMs: Long = 250,
        val helloServerTimeMs: Double? = null,
        val capabilitiesJson: kotlinx.serialization.json.JsonObject? = null,
    )

    /**
     * S12's notification side — the Phase 8 broker owns delivery; Phase 5 owns the
     * reconciliation DECISIONS through this port (in-memory/broker bindings later).
     */
    interface NotificationRecordPort {
        suspend fun visible(): List<NotificationRecord>
        suspend fun dismiss(keys: List<String>)
    }

    private val managers = LinkedHashMap<String, ConversationSessionManager>()
    /** Phase 6: per-session workspace target (§ v4 wire carries the workspace per call). */
    private val sessionWorkspaces = HashMap<String, Pair<String?, String?>>()
    private val recoveryMutex = Mutex()
    private var forwardJob: Job? = null
    private var tickJob: Job? = null
    private var stateJob: Job? = null
    @Volatile private var closed = false

    /** Sessions under management (diagnostics/tests). */
    fun managedSessions(): List<String> = synchronized(managers) { managers.keys.toList() }

    fun manager(sessionId: String): ConversationSessionManager? = synchronized(managers) { managers[sessionId] }

    /** Phase 6: a conversation may live outside the endpoint-pinned (active) workspace. */
    data class SessionTarget(
        val sessionId: String,
        val workspacePath: String? = null,
        val workspaceIdentity: String? = null,
    )

    open suspend fun start(sessionIds: List<String>) = startTargets(sessionIds.map { SessionTarget(it) })

    // ---------------------------------------------------------------- lifecycle

    /**
     * Attach sessions: journal restore + subscribe. Sessions with a persisted
     * watermark hydrate the cursor FIRST (S2) so the guard can classify continuity;
     * fresh sessions take the plain subscribe path (snapshot expected).
     */
    open suspend fun startTargets(targets: List<SessionTarget>) {
        val journal = journalRepo.load(endpointId)
        val watermarks = journal?.subscriptions?.associateBy({ it.sessionId }, { it }) ?: emptyMap()
        recordJournalBase(journal)
        val newlyAttached = ArrayList<SessionTarget>()
        synchronized(managers) {
            for (t in targets) {
                if (managers.containsKey(t.sessionId)) continue
                sessionWorkspaces[t.sessionId] = t.workspacePath to t.workspaceIdentity
                newlyAttached.add(t)
                val wm = watermarks[t.sessionId]
                managers[t.sessionId] = ConversationSessionManager(
                    sessionId = SessionId(t.sessionId),
                    endpointId = endpointId,
                    store = store,
                    journalSink = { topic, sessionId, afterSeq, epoch -> recordWatermark(topic, sessionId, afterSeq, epoch) },
                    escalate = { reason, baseEpoch, baseSeq -> escalate(t.sessionId, reason, baseEpoch, baseSeq) },
                    onSnapshotApplied = { rows -> onSnapshotReconcile(t.sessionId, rows) },
                    clock = clock,
                    log = log,
                )
                wm?.let { managers[t.sessionId]!!.hydrate(it.logEpoch, it.afterSeq) }
            }
        }
        gateway.startForwarding(scope)
        // Anchor every NEWLY-attached session on the fresh connection (§9.8: subscription
        // state on a new connection is "fresh" → subscribe, snapshot expected). Already-
        // managed sessions keep their live subscription — a RE-subscribe would fault and
        // falsely label a healthy, still-streaming projection stale (Phase 6 finding).
        // The (logEpoch, seq) guard classifies what the snapshot replaces; later
        // escalations resync.
        for (t in newlyAttached) {
            try {
                gateway.subscribe(t.sessionId, workspacePath = t.workspacePath, workspaceIdentity = t.workspaceIdentity)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                log.warn("recovery.subscribeFailed", "session=${t.sessionId} ${e.javaClass.simpleName}")
                manager(t.sessionId)?.setStale(true)
            }
        }
        forwardJob = scope.launch {
            gateway.topicEvents.collect { event ->
                try {
                    managerFor(event)?.handle(event)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // §28.4/INV-8 parity with the transport parser: a fault in ONE event
                    // never kills the ordered projection pipeline — surface, count, and
                    // continue degraded (the stale label + guards keep truth honest).
                    log.error("recovery.eventFault", "session=${event.sessionId} ${e.javaClass.simpleName}: ${e.message?.take(120)}")
                    managerFor(event)?.setStale(true)
                }
            }
        }
        tickJob = scope.launch {
            while (isActive && !closed) {
                delay(config.holdTickMs)
                val all = synchronized(managers) { managers.values.toList() }
                for (m in all) m.checkHold()
            }
        }
        transport?.let { t ->
            stateJob = scope.launch {
                t.states.collect { state -> onTransportState(state) }
            }
        }
    }

    suspend fun close() {
        closed = true
        forwardJob?.cancel()
        tickJob?.cancel()
        stateJob?.cancel()
    }

    // ---------------------------------------------------------------- triggers (S3/S4)

    /** §15-S3: foreground wake → immediate reconnect, skip backoff. */
    fun foregroundTrigger() {
        transport?.reconnectNow()
    }

    /** §15-S4: network change → probe via the transport's reconnect policy. */
    fun notifyNetworkAvailable() {
        transport?.notifyNetworkAvailable()
    }

    // ---------------------------------------------------------------- internals

    private fun managerFor(event: GatewayTopicEvent): ConversationSessionManager? =
        synchronized(managers) { managers[event.sessionId] }

    private suspend fun recordWatermark(topic: String, sessionId: String, afterSeq: Long, logEpoch: String) {
        // Journal record (§15.4 "persisted transactionally at each transition"): the
        // whole-snapshot write keeps hello/caps/pending refs coherent.
        val journal = journalRepo.load(endpointId) ?: RecoveryJournal(
            endpointId = endpointId.raw,
            helloServerTimeMs = config.helloServerTimeMs,
            capabilitiesSeen = config.capabilitiesJson,
            subscriptions = emptyList(),
            pendingOutbox = emptyList(),
            lastAppliedPerTopic = emptyMap(),
        )
        val others = journal.subscriptions.filter { it.sessionId != sessionId }
        val updated = journal.copy(
            subscriptions = others + RecoveryJournal.Subscription(topic, sessionId, afterSeq, logEpoch),
            helloServerTimeMs = journal.helloServerTimeMs ?: config.helloServerTimeMs,
            capabilitiesSeen = journal.capabilitiesSeen ?: config.capabilitiesJson,
            lastAppliedPerTopic = journal.lastAppliedPerTopic + (topic to afterSeq),
        )
        journalRepo.record(updated)
    }

    private suspend fun recordJournalBase(journal: RecoveryJournal?) {
        if (journal == null) return
        journalRepo.record(
            journal.copy(
                helloServerTimeMs = journal.helloServerTimeMs ?: config.helloServerTimeMs,
                capabilitiesSeen = journal.capabilitiesSeen ?: config.capabilitiesJson,
            )
        )
    }

    /** S8/S10 escalation path — per-endpoint mutex, resume-or-snapshot resync. */
    private suspend fun escalate(sessionId: String, reason: String, baseEpoch: String?, baseSeq: Long?) {
        if (closed) return
        recoveryMutex.withLock {
            val m = manager(sessionId) ?: return
            val base = baseEpoch ?: m.reducerForTest().watermark?.logEpoch
            val seq = baseSeq ?: m.reducerForTest().watermark?.seq
            val count = synchronized(escalationCounts) {
                val next = (escalationCounts[sessionId] ?: 0) + 1
                escalationCounts[sessionId] = next
                next
            }
            // §15-S10: "resync {afterSeq}; failure → full snapshot" — a repeat
            // escalation for the same cursor means the resume could not repair; force
            // the authoritative snapshot (never loop, never a local fill).
            val forceSnapshot = base == null || count >= 2
            log.warn("recovery.escalate", "session=$sessionId reason=${reason.take(60)} base=$base/$seq attempt=$count force=$forceSnapshot")
            val ws = synchronized(sessionWorkspaces) { sessionWorkspaces[sessionId] }
            try {
                gateway.resync(
                    sessionId,
                    base,
                    seq,
                    forceSnapshot = forceSnapshot,
                    workspacePath = ws?.first,
                    workspaceIdentity = ws?.second,
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Escalation transport failure: the connection machinery owns retry; the
                // projection stays labeled stale — never silently "recovered" (§15.5).
                log.warn("recovery.escalateFailed", e.javaClass.simpleName)
                m.setStale(true)
            }
        }
    }

    /** §14.4.2 overlay + §15.1 recovery completion. */
    private suspend fun onTransportState(state: ConnectionStateMachine.ConnectionState) {
        val all = synchronized(managers) { managers.values.toList() }
        when (state) {
            ConnectionStateMachine.ConnectionState.CONNECTED -> {
                for (m in all) m.setStale(false)
                reconcileAfterRecovery()
            }
            ConnectionStateMachine.ConnectionState.RECONNECTING,
            ConnectionStateMachine.ConnectionState.DEGRADED,
            ConnectionStateMachine.ConnectionState.FAILED,
            -> for (m in all) m.setStale(true)
            else -> Unit
        }
    }

    /**
     * §15-S12/S13 reconcile pass, run when the machine returns to CONNECTED:
     * the S13 interaction reconcile rides each manager's latest authoritative
     * snapshot; S12 collapses notifications for authoritative terminal states.
     */
    private suspend fun reconcileAfterRecovery() {
        val all = synchronized(managers) { managers.values.toList() }
        val consumedTasks = HashSet<String>()
        val resolvedInteractions = HashSet<String>()
        for (m in all) {
            val rows = m.lastSnapshotRows
            interactions.reconcileFromSnapshot(rows)
            for (row in rows.filterIsInstance<com.zcode.android.domain.model.ConversationRow.TurnHeader>()) {
                when (row.state) {
                    com.zcode.android.domain.model.ConversationRow.TurnState.COMPLETED_SUCCESS,
                    com.zcode.android.domain.model.ConversationRow.TurnState.COMPLETED_INTERRUPTED,
                    com.zcode.android.domain.model.ConversationRow.TurnState.FAILED,
                    -> consumedTasks.add(m.sessionId.raw)
                    else -> Unit
                }
            }
            for (row in rows.filterIsInstance<com.zcode.android.domain.model.ConversationRow.ToolCall>()) {
                if (row.approvalInteractionId != null &&
                    row.status != com.zcode.android.domain.model.ConversationRow.ToolCallStatus.PENDING_APPROVAL
                ) {
                    resolvedInteractions.add(row.approvalInteractionId!!)
                }
            }
        }
        val port = notificationRecords ?: return
        val dismissals = NotificationReconciler.dismissals(
            records = port.visible(),
            consumedTaskIds = consumedTasks,
            resolvedInteractionIds = resolvedInteractions,
        )
        if (dismissals.isNotEmpty()) port.dismiss(dismissals)
    }

    /**
     * Per-snapshot reconcile hook — fires on EVERY authoritative snapshot apply:
     * S13 interaction reconcile + S12 notification reconciliation (the CONNECTED
     * transition can precede the snapshot frame, so the snapshot path reconciles too;
     * both passes are idempotent, §24.3).
     */
    private suspend fun onSnapshotReconcile(sessionId: String, rows: List<com.zcode.android.domain.model.ConversationRow>) {
        synchronized(escalationCounts) { escalationCounts.remove(sessionId) } // healthy base restored
        log.debug("recovery.snapshotReconcile", "session=$sessionId rows=${rows.size}")
        interactions.reconcileFromSnapshot(rows)
        val port = notificationRecords ?: return
        val consumed = rows.filterIsInstance<com.zcode.android.domain.model.ConversationRow.TurnHeader>().mapNotNull { row ->
            when (row.state) {
                com.zcode.android.domain.model.ConversationRow.TurnState.COMPLETED_SUCCESS,
                com.zcode.android.domain.model.ConversationRow.TurnState.COMPLETED_INTERRUPTED,
                com.zcode.android.domain.model.ConversationRow.TurnState.FAILED,
                -> sessionId
                else -> null
            }
        }.toSet()
        val resolvedInteractions = rows.filterIsInstance<com.zcode.android.domain.model.ConversationRow.ToolCall>()
            .filter { it.approvalInteractionId != null && it.status != com.zcode.android.domain.model.ConversationRow.ToolCallStatus.PENDING_APPROVAL }
            .mapNotNull { it.approvalInteractionId }
            .toSet()
        if (consumed.isEmpty() && resolvedInteractions.isEmpty()) return
        val dismissals = NotificationReconciler.dismissals(port.visible(), consumed, resolvedInteractions)
        if (dismissals.isNotEmpty()) port.dismiss(dismissals)
    }

    private val escalationCounts = HashMap<String, Int>()
}
