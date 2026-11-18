package com.zcode.android.data.session

import com.zcode.android.core.common.error.serverSpoke
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
    /** G71: the captured hook reviews reach the caller's PROCESS ledger (survives detach). */
    private val hookReviewSink: (sessionId: String, review: com.zcode.android.domain.model.WorkspaceHookReview) -> Unit = { _, _ -> },
) {

    data class Config(
        val holdTickMs: Long = 250,
        /**
         * FIELD (2026-10-02, vc24 device log): the relay PAUSED mid-generation
         * delivery (70 s of heartbeats-only while the desktop kept generating) and
         * resumed only on user action. While CONNECTED with a live session, >20 s
         * without a topic frame triggers ONE stateless base-resync (verified
         * re-send-safe; replay is gap-free) to re-kick delivery, rate-limited to
         * once per [streamWatchdogResyncGapMs].
         */
        // FIELD (vc35 device log): 20 s fired on IDLE running sessions between turns
        // (the agent thinking/tool-running legitimately quiet >20 s) — each kick was
        // a resync-notOwned → subscribe round that could cold-resume server-side.
        // 90 s still catches vc24-class stalls quickly; the gap throttles repeats.
        val streamWatchdogIdleMs: Long = 90_000,
        val streamWatchdogResyncGapMs: Long = 45_000,
        /**
         * F-37-E (vc52 Phase 10, pass-37 scale leg): the recovery answer to OUR kick
         * lands as topic frames within this grace window; head-advancing frames inside
         * it are the kick's own echo and must NOT reset the strike ladder (the echo
         * resetting strikes is why a compliant-but-passive desktop was re-pulled
         * forever — 14 re-kicks in one idle 30-minute window). Generous: a real
         * recovery answer is a same-socket push, well under a second.
         */
        val watchdogEchoGraceMs: Long = 10_000,
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

    /** F-P7-A (pass-0 Phase 2): the session's OWN workspace target for interaction-scoped answers. */
    fun sessionWorkspaceFor(sessionId: String): Pair<String?, String?>? =
        synchronized(sessionWorkspaces) { sessionWorkspaces[sessionId] }

    /** F-P13-A: the record-less re-anchor cooldown (a failing subscribe must not chase every delta). */
    private val RECORDLESS_RESUBSCRIBE_COOLDOWN_MS = 10_000L

    /**
     * Round-8 field fix: sessions whose attach SUBSCRIBE failed (e.g. attached while
     * Degraded — the user tapped a task during an outage). The manager exists and
     * routes events, but no subscription ack exists, so recover()'s re-subscribe
     * pass never re-anchors them — they stayed dark FOREVER, even after Retry
     * reached CONNECTED (field: "task can't load even after retry"). Drained on
     * every CONNECTED transition.
     */
    private val pendingAttach = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val recoveryMutex = Mutex()
    private var forwardJob: Job? = null
    private var tickJob: Job? = null
    private var stateJob: Job? = null
    @Volatile private var closed = false

    /** Sessions under management (diagnostics/tests). */
    fun managedSessions(): List<String> = synchronized(managers) { managers.keys.toList() }

    /** §14.2 command-issuer overlay for an ACCEPTED model/mode switch (echo wins). */
    fun applyLocalConfigOverlay(sessionId: String, provider: String, model: String, thought: String, mode: String? = null) {
        synchronized(managers) { managers[sessionId] }?.applyLocalConfigOverlay(provider, model, thought, mode)
    }

    fun manager(sessionId: String): ConversationSessionManager? = synchronized(managers) { managers[sessionId] }

    /** G71 (vc52 Phase 12): a stable snapshot of the live managers (id → manager). */
    fun managers(): Map<String, ConversationSessionManager> = synchronized(managers) { managers.toMap() }

    /**
     * GAP-A3/G14 (vc51): conversation teardown — unsubscribe the topic on the wire,
     * drop the transport's subscription record, and DETACH the manager so its
     * stream watchdog can never resubscribe (escalate() on a detached session
     * returned silently before; with the manager gone the whole state plane is
     * gone too). Re-opening the task re-attaches fresh through startTargets
     * (openConversation clears its own dedupe set).
     */
    open suspend fun detach(sessionId: String) {
        val ws = synchronized(sessionWorkspaces) { sessionWorkspaces.remove(sessionId) }
        synchronized(managers) { managers.remove(sessionId) }
        synchronized(sessionLastFrameAtMs) { sessionLastFrameAtMs.remove(sessionId) }
        synchronized(sessionLastWatchdogResyncMs) { sessionLastWatchdogResyncMs.remove(sessionId) }
        synchronized(sessionWatchdogStrikes) { sessionWatchdogStrikes.remove(sessionId) }
        synchronized(sessionKickHeads) { sessionKickHeads.remove(sessionId) }
        synchronized(sessionKickEchoUntilMs) { sessionKickEchoUntilMs.remove(sessionId) }
        synchronized(sessionIdleStoodDown) { sessionIdleStoodDown.remove(sessionId) }
        synchronized(escalationCounts) { escalationCounts.remove(sessionId) }
        pendingAttach.remove(sessionId)
        try {
            gateway.unsubscribe(sessionId, ws?.first, ws?.second)
            log.info("recovery.detached", "session=$sessionId (unsubscribed)")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("recovery.detachUnsubscribeFailed", "session=$sessionId ${e.javaClass.simpleName}")
        }
    }

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
                    onSessionState = { state ->
                        interactions.reconcileFromSessionState(t.sessionId, state)
                    },
                    // F-P45-B (pass-0 Phase 2): the sink key is the PAYLOAD's own taskId — a
                        // session snapshot may carry TWO workspaces' reviews; the opened
                        // session's id must never own a foreign task's review.
                        onHookReviewCaptured = { review -> hookReviewSink(review.taskId, review) },
                    clock = clock,
                    log = log,
                )
                wm?.let { managers[t.sessionId]!!.hydrate(it.logEpoch, it.afterSeq) }
                armWatchdog(t.sessionId)
            }
        }
        log.info("recovery.attach", "sessions=${targets.joinToString { it.sessionId }} fresh=${newlyAttached.map { it.sessionId }}")
        gateway.startForwarding(scope)
        // Anchor every NEWLY-attached session on the fresh connection (§9.8: subscription
        // state on a new connection is "fresh" → subscribe, snapshot expected). Already-
        // managed sessions keep their live subscription — a RE-subscribe would fault and
        // falsely label a healthy, still-streaming projection stale (Phase 6 finding).
        // The (logEpoch, seq) guard classifies what the snapshot replaces; later
        // escalations resync.
        // FIELD (2026-09-30, vc8): a subscribe issued while the transport is still
        // CONNECTING/RECOVERING reaches the desktop before the channel handshake and is
        // rejected `fault.connection.handshakeRequired` — and the failed subscribe used
        // to leave the task unloadable. Defer to the pending-attach drain (fires on
        // CONNECTED) instead of calling into a half-open channel.
        val transportConnected =
            transport?.states?.value == com.zcode.android.core.transport.ConnectionStateMachine.ConnectionState.CONNECTED
        for (t in newlyAttached) {
            if (!transportConnected) {
                pendingAttach.add(t.sessionId)
                manager(t.sessionId)?.setStale(true)
                log.info("recovery.deferredAttach", "session=${t.sessionId} transport=${transport?.states?.value ?: "null"}")
                continue
            }
            try {
                val ack = gateway.subscribe(t.sessionId, workspacePath = t.workspacePath, workspaceIdentity = t.workspaceIdentity)
                log.info(
                    "recovery.subscribed",
                    "session=${t.sessionId} mode=${ack.mode} epoch=${ack.logEpoch.take(24)} ws=${t.workspacePath ?: "pinned"}",
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                log.warn("recovery.subscribeFailed", "session=${t.sessionId} ${e.javaClass.simpleName}: ${e.message?.take(100)}")
                manager(t.sessionId)?.setStale(true)
                // Round-8b: queue for re-anchor on the next CONNECTED (§15 S18) —
                // without this the drain polls an empty queue and a session whose
                // attach failed while offline stays stale forever.
                pendingAttach.add(t.sessionId)
            }
        }
        // FIELD-KILLER #8b (2026-09-30, vc13): startTargets runs per attach — without a
        // guard, EVERY task open added another topicEvents collector (manager.deltas ×2
        // per frame in the field). One collector for the coordinator's lifetime.
        if (forwardJob?.isActive == true) {
            log.debug("recovery.forwardingAlive", "topicEvents collector already running")
        } else {
        // FIELD (2026-10-02, vc24 device log — 2,651 projection events on MAIN):
        // frame decode + reducer + store-op dispatch ran on the UI thread, competing
        // with rendering under generation load (the "1-2 s streaming delay" the web
        // remote doesn't have). The pipeline moves OFF-main on a single-thread
        // Default slice: sequential collect preserves event order, the limited
        // dispatcher preserves the §28.5 single-writer discipline.
        val projectionDispatcher = kotlinx.coroutines.Dispatchers.Default.limitedParallelism(1)
        forwardJob = scope.launch {
            gateway.topicEvents.collect { event ->
                kotlinx.coroutines.withContext(projectionDispatcher) {
                    // F-P13-A: a delta with no subscription record for a LIVE manager is
                    // a lost epoch (the close-cycle/recovery unlink, pass-13 ×2) —
                    // re-anchor the session instead of dropping every delta for the life
                    // of the connection. Manager-less sessions (detached) keep the
                    // honest drop: the reopen path re-subscribes (the pass-44 heal).
                    if (event is GatewayTopicEvent.RecordlessDelta) {
                        recordlessDelta(event)
                        return@withContext
                    }
                    markFrame(event.sessionId)
                    try {
                        val m = managerFor(event)
                        // F-37-E: classify the event against the projection head — a
                        // genuine head-advance (outside our own kick's echo window)
                        // re-arms the watchdog; the kick's own recovery echo does not.
                        val headBefore = m?.let { it.reducerForTest().watermark }
                        m?.handle(event)
                        classifyWatchdogDelta(event.sessionId, headBefore, m?.let { it.reducerForTest().watermark })
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
        }
    }

    tickJob = scope.launch {
            while (isActive && !closed) {
                delay(config.holdTickMs)
                val all = synchronized(managers) { managers.values.toList() }
                for (m in all) m.checkHold()
                maybeStreamWatchdog(all)
            }
    }
    transport?.let { t ->
            stateJob = scope.launch {
                if (System.getenv("ZCODE_SD_DEBUG") != null) println("[sd-x] stateJob launched")
                try {
                    t.states.collect { state -> onTransportState(state) }
                } catch (t: Throwable) {
                    println("[sd-x] stateJob collect THREW $t")
                    throw t
                }
            }.also { job ->
                job.invokeOnCompletion { cause -> if (System.getenv("ZCODE_SD_DEBUG") != null) println("[sd-x] stateJob completed cause=$cause") }
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
            // FIELD (2026-10-04, vc32 device log): a base from a PREVIOUS connection's
            // subscription (the hydrated journal watermark) is rejected by the server
            // with fault.subscription.notOwned — the current subscription has its own
            // generation. Any base that is not the CURRENT ack-stamped epoch forces a
            // snapshot: the fresh subscribe is the only base the server honors, and a
            // forced snapshot also re-kicks a paused delivery (the vc24 class).
            val currentEpoch = gateway.currentSubscriptionEpoch(sessionId)
            val baseIsCurrent = base != null && base == currentEpoch
            val forceSnapshot = base == null || count >= 2 || !baseIsCurrent
            log.warn(
                "recovery.escalate",
                "session=$sessionId reason=${reason.take(60)} base=$base/$seq attempt=$count force=$forceSnapshot " +
                    "currentEpoch=${currentEpoch?.take(24)} baseIsCurrent=$baseIsCurrent",
            )
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
                // FIELD (2026-10-04, vc33 device log): the desktop REJECTED the resync
                // itself (fault.subscription.notOwned — serverSpoke) and the old path
                // re-tried the SAME failing resync every watchdog window (5× in the
                // field, forever). §15-S10's own escape hatch: resync failed → FULL
                // SNAPSHOT — here a FRESH SUBSCRIBE (a new server-owned subscription
                // that re-anchors with a snapshot; the resync path is dead for this
                // session on this connection). Bounded: once per escalation; the
                // watchdog's per-session rate limit throttles repeats.
                val serverSpoke = e is com.zcode.android.core.transport.TransportFailure &&
                    e.error.serverSpoke
                if (serverSpoke) {
                    try {
                        gateway.subscribe(sessionId, workspacePath = ws?.first, workspaceIdentity = ws?.second)
                        log.warn("recovery.escalateFallback", "session=$sessionId resync refused — fresh subscribe anchored")
                        return
                    } catch (e2: kotlinx.coroutines.CancellationException) {
                        throw e2
                    } catch (e2: Exception) {
                        log.warn("recovery.escalateFallbackFailed", "session=$sessionId ${e2.javaClass.simpleName}")
                    }
                }
                m.setStale(true)
            }
    }
    }

    /** §14.4.2 overlay + §15.1 recovery completion. */
    private suspend fun onTransportState(state: ConnectionStateMachine.ConnectionState) {
    if (System.getenv("ZCODE_SD_DEBUG") != null) println("[sd-x] onTransportState $state stoodDown=${synchronized(sessionIdleStoodDown) { sessionIdleStoodDown.toList() }}")
    val all = synchronized(managers) { managers.values.toList() }
    when (state) {
            ConnectionStateMachine.ConnectionState.CONNECTED -> {
                if (System.getenv("ZCODE_SD_DEBUG") != null) println("[sd-x] CONNECTED branch: clearing")
                for (m in all) m.setStale(false)
                synchronized(sessionWatchdogStrikes) { sessionWatchdogStrikes.clear() }
                // F-37-E: a fresh connection re-anchors every session — the watchdog
                // re-arms from scratch (no stand-down, no stale kick heads).
                synchronized(sessionIdleStoodDown) { sessionIdleStoodDown.clear() }
                synchronized(sessionKickHeads) { sessionKickHeads.clear() }
                synchronized(sessionKickEchoUntilMs) { sessionKickEchoUntilMs.clear() }
                synchronized(recordlessResubscribeAtMs) { recordlessResubscribeAtMs.clear() }
                if (System.getenv("ZCODE_SD_DEBUG") != null) println("[sd-x] CONNECTED branch: reconcile next")
                reconcileAfterRecovery()
                if (System.getenv("ZCODE_SD_DEBUG") != null) println("[sd-x] CONNECTED branch: drained, done")
                // Round-8: re-anchor sessions whose original subscribe failed while
                // the transport was down (field: "task can't load even after retry").
                drainPendingAttach()
                // F-P13-A: recovery re-materialized conversations WITHOUT re-subscribing
                // any session whose local record the close-cycle had already dropped —
                // the epoch record never re-stamped and EVERY server-initiated delta
                // dropped at the gateway's null-epoch branch for the life of the
                // connection (pass-13 ×2). A managed session must never sit record-less
                // after CONNECTED; the stay-open boundary (pass 15) is untouched — a
                // record-bearing session is never re-subscribed.
                resubscribeRecordless()
            }
            ConnectionStateMachine.ConnectionState.RECONNECTING,
            ConnectionStateMachine.ConnectionState.DEGRADED,
            ConnectionStateMachine.ConnectionState.FAILED,
            -> for (m in all) m.setStale(true)
            else -> Unit
    }
    }

    /**
     * Re-issue gateway.subscribe for sessions whose attach failed earlier. Called
     * on every CONNECTED transition; failures re-enqueue (bounded by the caller's
     * retry flow — the transport recovery loop re-fires this on each recovery).
     */
    private suspend fun drainPendingAttach() {
    while (true) {
            val sessionId = pendingAttach.poll() ?: return
            val ws = synchronized(sessionWorkspaces) { sessionWorkspaces[sessionId] }
            try {
                gateway.subscribe(
                    sessionId,
                    workspacePath = ws?.first,
                    workspaceIdentity = ws?.second,
                )
                log.info("recovery.pendingAttached", "session=$sessionId")
            } catch (e: kotlinx.coroutines.CancellationException) {
                pendingAttach.add(sessionId)
                throw e
            } catch (e: Exception) {
                log.warn("recovery.pendingAttachFailed", "session=$sessionId ${e.javaClass.simpleName}: ${e.message?.take(80)}")
                pendingAttach.add(sessionId)
                return // still down — stop draining, the next CONNECTED re-fires
            }
    }
    }

    /**
     * F-P13-A: per-session cooldown for record-less re-anchors — one subscribe per
     * window; a failing subscribe must never chase every arriving delta.
     */
    private val recordlessResubscribeAtMs = HashMap<String, Long>()

    /** F-P13-A: the record-less delta handler (see [GatewayTopicEvent.RecordlessDelta]). */
    private suspend fun recordlessDelta(event: GatewayTopicEvent.RecordlessDelta) {
        val id = event.sessionId
        if (manager(id) == null) return // detached session: nothing consumes it (reopen re-subscribes)
        if (gateway.currentSubscriptionEpoch(id) != null) return // the ack raced the event in
        val now = clock()
        val last = synchronized(recordlessResubscribeAtMs) { recordlessResubscribeAtMs[id] ?: 0L }
        if (now - last < RECORDLESS_RESUBSCRIBE_COOLDOWN_MS) return
        synchronized(recordlessResubscribeAtMs) { recordlessResubscribeAtMs[id] = now }
        val ws = synchronized(sessionWorkspaces) { sessionWorkspaces[id] }
        log.warn(
            "recovery.recordlessResubscribe",
            "session=$id kind=${event.deliveryKind} seq=${event.fromSeq}..${event.toSeq} — a live pane lost its subscription record; re-anchoring",
        )
        try {
            gateway.subscribe(id, workspacePath = ws?.first, workspaceIdentity = ws?.second)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("recovery.recordlessResubscribeFailed", "session=$id ${e.javaClass.simpleName}: ${e.message?.take(80)}")
            pendingAttach.add(id) // the next CONNECTED re-fires (bounded retry)
        }
    }

    /**
     * F-P13-A: the CONNECTED sweep — every MANAGED session without a subscription
     * record on THIS connection gets a fresh subscribe (the epoch re-stamp that the
     * close-cycle/recovery handshake skipped). The transport's records feed the
     * gateway's epoch precedence, so a null record means every delta would drop.
     */
    private suspend fun resubscribeRecordless() {
        val all = synchronized(managers) { managers.values.toList() }
        for (m in all) {
            val id = m.sessionId.raw
            if (gateway.currentSubscriptionEpoch(id) != null) continue
            val ws = synchronized(sessionWorkspaces) { sessionWorkspaces[id] }
            try {
                gateway.subscribe(id, workspacePath = ws?.first, workspaceIdentity = ws?.second)
                log.warn(
                    "recovery.resubscribedAfterRecovery",
                    "session=$id (record-less after recovery — deltas would drop at the gateway)",
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("recovery.resubscribeFailed", "session=$id ${e.javaClass.simpleName}: ${e.message?.take(80)}")
                pendingAttach.add(id)
            }
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
            interactions.reconcileFromSnapshot(rows, m.sessionId.raw)
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
    interactions.reconcileFromSnapshot(rows, sessionId)
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
    // ---------------------------------------------------------------- stream watchdog

    /**
     * FIELD (2026-10-04, vc32 device log) — the watchdog was GLOBAL: one
     * `lastFrameAtMs` for the whole coordinator, so a quiet RUNNING session behind a
     * streaming one was NEVER watched, and the escalation re-used the reducer's
     * HYDRATED journal watermark (from a PREVIOUS connection) as the resync base —
     * the server rejected it with fault.subscription.notOwned and the ONE chance to
     * re-kick a paused delivery (the vc24 relay-pause class) was wasted. Now:
     * per-session last-frame stamps (initialized at attach so a stalled FIRST
     * snapshot is covered too), per-session resync rate-limit stamps, and the
     * escalation forces a snapshot whenever its base is not the CURRENT
     * connection's subscription epoch.
     */
    private val sessionLastFrameAtMs = HashMap<String, Long>()
    private val sessionLastWatchdogResyncMs = HashMap<String, Long>()
    private val sessionWatchdogStrikes = HashMap<String, Int>()

    /** F-37-E: the projection head at kick time — an unchanged head at the next
     *  evaluation means the kick's recovery returned no change → healthy-idle. */
    private val sessionKickHeads = HashMap<String, com.zcode.android.domain.reducer.ProjectionWatermark?>()
    /** F-37-E: until when a head-advancing event counts as OUR kick's echo. */
    private val sessionKickEchoUntilMs = HashMap<String, Long>()
    /** F-37-E: sessions whose recovery proved healthy-idle — skipped until a genuine delta. */
    private val sessionIdleStoodDown = HashSet<String>()

    /** FIELD (vc34): cap consecutive watchdog escalations per session — each round
     *  re-subscribes, and a desktop cold-resume on a wrong workspace pair can FORK
     *  the task server-side; hammering every 30 s multiplies the damage. After the
     *  cap the session stays honestly stale until a frame, a reconnect, or the user
     *  resets it. */
    private val watchdogMaxStrikes = 4

    private fun markFrame(sessionId: String) {
        synchronized(sessionLastFrameAtMs) { sessionLastFrameAtMs[sessionId] = clock() }
        // F-37-E: the strike ladder is NO LONGER reset here — a frame that merely
        // refreshes the activity stamp (OUR kick's own recovery echo) used to clear
        // the strikes, which is exactly why the 4-strike cap never engaged against a
        // compliant-but-passive desktop. Only classifyWatchdogDelta's GENUINE delta
        // (outside the kick's echo grace) resets the ladder now.
    }

    private fun armWatchdog(sessionId: String) {
        synchronized(sessionLastFrameAtMs) { sessionLastFrameAtMs[sessionId] = clock() }
    }

    private suspend fun maybeStreamWatchdog(all: List<ConversationSessionManager>) {
        if (transport?.states?.value != ConnectionStateMachine.ConnectionState.CONNECTED) return
        if (all.isEmpty()) return
        val now = clock()
        val stalled = all.firstOrNull { m ->
            // F-37-E: a stood-down (healthy-idle) session is skipped until a genuine
            // delta lifts it (classifyWatchdogDelta / a reconnect / detach).
            if (synchronized(sessionIdleStoodDown) { m.sessionId.raw in sessionIdleStoodDown }) {
                return@firstOrNull false
            }
            val state = m.taskState.value
            if (state !in setOf(
                    com.zcode.android.domain.state.TaskStateMachine.State.RUNNING,
                    com.zcode.android.domain.state.TaskStateMachine.State.LOADING,
                    com.zcode.android.domain.state.TaskStateMachine.State.UNKNOWN,
                )
            ) {
                return@firstOrNull false // terminal: nothing streams — never resync (field: completed tasks)
            }
            val lastFrame = synchronized(sessionLastFrameAtMs) { sessionLastFrameAtMs[m.sessionId.raw] } ?: return@firstOrNull false
            val lastResync = synchronized(sessionLastWatchdogResyncMs) { sessionLastWatchdogResyncMs[m.sessionId.raw] } ?: 0L
            // F-37-E: exponential backoff per strike — only a genuine delta resets it
            // (the kick's own recovery echo never does), so the 4-strike cap can
            // actually engage against a compliant-but-passive desktop.
            val strikes = synchronized(sessionWatchdogStrikes) { sessionWatchdogStrikes[m.sessionId.raw] } ?: 0
            val backoffGap = config.streamWatchdogResyncGapMs shl minOf(strikes, 6)
            now - lastFrame >= config.streamWatchdogIdleMs && now - lastResync >= backoffGap
        } ?: return
        // F-37-E: the previous kick's answer arrived with NO change (same epoch, same
        // seq head) — the recovery response itself proved the session healthy-idle.
        // Stand the watchdog down until a genuine delta; re-kicking would re-pull the
        // full snapshot forever (the recorded 14-re-kick loop over one idle window).
        val head = stalled.reducerForTest().watermark
        val lastKickHead = synchronized(sessionKickHeads) { sessionKickHeads[stalled.sessionId.raw] }
        if (lastKickHead != null && head != null && head.logEpoch == lastKickHead.logEpoch && head.seq == lastKickHead.seq) {
            synchronized(sessionIdleStoodDown) { sessionIdleStoodDown.add(stalled.sessionId.raw) }
            log.warn(
                "recovery.streamWatchdogIdle",
                "session=${stalled.sessionId.raw} recovery returned no change (head=${head.logEpoch.take(12)}/${head.seq}) — healthy-idle, watchdog stood down until a genuine delta",
            )
            return
        }
        val strikes = synchronized(sessionWatchdogStrikes) { (sessionWatchdogStrikes[stalled.sessionId.raw] ?: 0) + 1 }
        if (strikes > watchdogMaxStrikes) {
            // Cap reached: stop re-subscribing (each round can cold-resume server-side).
            // Honest staleness; a frame, a reconnect, or the user resets the session.
            if (strikes == watchdogMaxStrikes + 1) {
                log.warn("recovery.streamWatchdogCapped", "session=${stalled.sessionId.raw} strikes=$strikes — stopped until reconnect/frame")
                stalled.setStale(true)
            }
            return
        }
        synchronized(sessionWatchdogStrikes) { sessionWatchdogStrikes[stalled.sessionId.raw] = strikes }
        synchronized(sessionLastWatchdogResyncMs) { sessionLastWatchdogResyncMs[stalled.sessionId.raw] = now }
        synchronized(sessionKickHeads) { sessionKickHeads[stalled.sessionId.raw] = head }
        synchronized(sessionKickEchoUntilMs) { sessionKickEchoUntilMs[stalled.sessionId.raw] = now + config.watchdogEchoGraceMs }
        val idleMs = now - (synchronized(sessionLastFrameAtMs) { sessionLastFrameAtMs[stalled.sessionId.raw] } ?: now)
        log.warn("recovery.streamWatchdog", "no frames for ${idleMs}ms — re-kick session=${stalled.sessionId.raw} strikes=$strikes")
        escalate(stalled.sessionId.raw, reason = "stream watchdog (delivery stalled)", baseEpoch = null, baseSeq = null)
    }

    /**
     * F-37-E: a topic event whose head ADVANCED (epoch moved or seq grew) is stream
     * traffic. Inside our own kick's echo grace it is the recovery answer itself —
     * strike-neutral. Outside it, a genuine delta clears the strike ladder, lifts any
     * stand-down, and re-arms the watchdog (also lifting a capped session's honest
     * staleness — real traffic is the cure the cap names).
     */
    private fun classifyWatchdogDelta(
        sessionId: String,
        before: com.zcode.android.domain.reducer.ProjectionWatermark?,
        after: com.zcode.android.domain.reducer.ProjectionWatermark?,
    ) {
        val advanced = after != null && (before == null || before.logEpoch != after.logEpoch || after.seq > before.seq)
        if (!advanced) return // the echo class (head unchanged) — strike-neutral by design
        val echoUntil = synchronized(sessionKickEchoUntilMs) { sessionKickEchoUntilMs[sessionId] }
        if (echoUntil != null && clock() <= echoUntil) return // our kick's own recovery answer
        synchronized(sessionWatchdogStrikes) { sessionWatchdogStrikes.remove(sessionId) }
        synchronized(sessionKickHeads) { sessionKickHeads.remove(sessionId) }
        synchronized(sessionKickEchoUntilMs) { sessionKickEchoUntilMs.remove(sessionId) }
        val stoodDown = synchronized(sessionIdleStoodDown) { sessionIdleStoodDown.remove(sessionId) }
        if (stoodDown) {
            manager(sessionId)?.setStale(false)
            log.info("recovery.streamWatchdogReArm", "session=$sessionId genuine delta — watchdog re-armed, staleness lifted")
        }
    }
}
