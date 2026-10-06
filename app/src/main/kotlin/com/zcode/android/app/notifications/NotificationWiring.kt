package com.zcode.android.app.notifications

import com.zcode.android.app.AppStores
import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.notifications.NotificationBroker
import com.zcode.android.core.notifications.StoreDiffs
import com.zcode.android.data.session.RecoveryCoordinator
import com.zcode.android.domain.model.NotificationRecord
import com.zcode.android.domain.model.TaskStatus
import com.zcode.android.domain.repo.InteractionRepository
import com.zcode.android.domain.state.ConnectionPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * NotificationWiring — the ONE place that connects the app's authoritative
 * repositories and the connection machine to the §24.1 broker (the broker itself
 * stays transport-independent; this class is the app's adapter). Event sources,
 * all event-driven (§29: no polling):
 *
 *  - interaction store (`observeOpen`) → APPROVAL_NEEDED / USER_INPUT_NEEDED on new
 *    REQUESTED interactions (§24.1);
 *  - task projection (`observeAllTasks`) → TASK_FINISHED / TASK_FAILED on status
 *    transitions INTO a terminal state (§24.1 turn.completed/failed cells as seen
 *    through the authoritative projection — the S12 channel);
 *  - connection phase → CONNECTION_LOST on Degraded entries while any task runs
 *    (§24.1 "connection Degraded while any task Running"), cleared on CONNECTED;
 *  - activity lifecycle → foreground/background transitions (§9.9 suppression and
 *    its background-entry complement, §15-S13 re-post).
 *
 * First emission of a store seeds the baseline WITHOUT notifying (a cold process
 * must not spam terminal states that were terminal yesterday).
 */
class NotificationWiring(
    private val stores: AppStores,
    private val broker: NotificationBroker,
    private val phase: kotlinx.coroutines.flow.StateFlow<ConnectionPhase>,
    /** Endpoint identity flow — the collectors KEY ON THIS, never on the phase:
     *  a phase change races persistEndpoint, so a phase-keyed flatMapLatest binds
     *  endpoint==null once and stays dead for the session (the DefaultTasksPort
     *  field lesson, §17.3-adjacent). */
    private val endpointFlow: kotlinx.coroutines.flow.StateFlow<com.zcode.android.domain.model.Endpoint?>,
    private val scope: CoroutineScope,
    private val log: ZLog,
) {

    /** §24.7 deep links land here (MainActivity) and are consumed by the nav graph. */
    private val pending = PendingDeepLink()
    val pendingLink: StateFlow<DeepLinks.Candidate?> get() = pending.value

    fun offerDeepLink(taskId: String?, interactionId: String?) {
        // MALFORMED links are dropped here (never crash, §24.7); the domain
        // DeepLinkRoute.parse re-validates inside resolve.
        if (taskId.isNullOrBlank()) return
        pending.offer(DeepLinks.Candidate(taskId, interactionId))
    }

    fun consumeDeepLink() = pending.consume()

    // ------------------------------------------------------------- collectors

    private var started = false
    private var interactionsJob: Job? = null
    private var tasksJob: Job? = null
    private var phaseJob: Job? = null

    @Volatile private var seenInteractionIds: Set<String> = emptySet()
    @Volatile private var taskStatuses: Map<String, TaskStatus> = emptyMap()

    /**
     * §24.7 routing inputs — the SAME projection snapshots the collectors maintain
     * for the broker diffs (what the task list actually renders), NOT a second
     * re-query path: a fresh resolve must never diverge from the visible truth.
     */
    fun knowsTask(taskId: String): Boolean = knownTaskIds.get().contains(taskId)

    private val knownTaskIds = java.util.concurrent.atomic.AtomicReference<Set<String>>(emptySet())
    private val knownInteractionIds = java.util.concurrent.atomic.AtomicReference<Set<String>>(emptySet())
    fun knowsInteraction(interactionId: String): Boolean = knownInteractionIds.get().contains(interactionId)

    /** Idempotent app-start entry (called from AppContainer init). */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        if (started) return
        started = true
        scope.launch {
            broker.load()
            broker.sweepRetention()
        }
        broker.contentLevelProvider = {
            runCatching { stores.preferencesRepository.current().notificationContentLevel }
                .getOrDefault(com.zcode.android.domain.model.AppPreferences.NotificationContentLevel.MINIMAL)
        }
        // §24.5 (vc35 user feedback): the Live-connection window is user-customizable
        // (0 = no auto-timeout). Live-updates the FGS while running; start() re-reads
        // it on every toggle-on.
        scope.launch {
            stores.preferencesRepository.observe().collect { prefs ->
                com.zcode.android.core.notifications.LiveConnectionService.autoTimeoutMs =
                    prefs.liveConnectionTimeoutMinutes.coerceAtLeast(0).toLong() * 60_000L
            }
        }

        // Endpoint-scoped collectors re-bind on pairing changes (the hub phase is
        // the endpoint lifecycle proxy: UNPAIRED → empty sources).
        interactionsJob?.cancel()
        interactionsJob = scope.launch {
            endpointFlow.flatMapLatest { e ->
                if (e == null) flowOf(emptyList<InteractionRepository.InteractionRecord>())
                else stores.interactionRepository.observeOpen(e.endpointId)
            }.collect { records ->
                val previousIds = seenInteractionIds
                val fresh = StoreDiffs.newOpenInteractions(previousIds, records)
                seenInteractionIds = StoreDiffs.interactionIds(records)
                knownInteractionIds.set(seenInteractionIds)
                for ((record, kind) in fresh) {
                    runCatching {
                        broker.onEvent(
                            kind = kind,
                            taskId = record.sessionId?.raw,
                            interactionId = record.interactionId.raw,
                            taskTitle = null,
                        )
                    }.onFailure { log.warn("notify.attentionFault", it.javaClass.simpleName) }
                }
                // FIELD (vc35): CLOSED interactions (answered/resolved/expired —
                // anything that left the authoritative open set) must dismiss their
                // broker records. Without this, a resolved interaction's tray record
                // stayed unseen and §15-S13 re-posted it on EVERY background entry
                // (the "all 4 notifications every time" report), and the badge kept
                // counting answered requests.
                val closed = StoreDiffs.closedInteractions(previousIds, records)
                if (closed.isNotEmpty()) {
                    runCatching { broker.dismiss(closed.map { "interaction:$it" }) }
                        .onFailure { log.warn("notify.closedDismiss", it.javaClass.simpleName) }
                }
            }
        }

        tasksJob?.cancel()
        tasksJob = scope.launch {
            endpointFlow.flatMapLatest { e ->
                if (e == null) flowOf(emptyList<com.zcode.android.domain.model.Task>())
                else stores.taskRepository.observeAllTasks(e.endpointId)
            }.collect { tasks ->
                val transitions = StoreDiffs.taskTerminalTransitions(taskStatuses, tasks)
                taskStatuses = StoreDiffs.statusIndex(tasks)
                knownTaskIds.set(taskStatuses.keys)
                for ((task, kind) in transitions) {
                    runCatching { broker.onEvent(kind, task.taskId, null, task.title) }
                        .onFailure { log.warn("notify.taskFault", it.javaClass.simpleName) }
                }
            }
        }

        phaseJob?.cancel()
        phaseJob = scope.launch {
            var last: ConnectionPhase? = null
            phase.collect { current ->
                val previous = last
                last = current
                if (current == ConnectionPhase.DEGRADED && previous != ConnectionPhase.DEGRADED) {
                    val anyRunning = taskStatuses.values.any { it == TaskStatus.RUNNING }
                    if (anyRunning) {
                        log.info("notify.connectionLost", "degraded while tasks running")
                        runCatching {
                            broker.onEvent(NotificationRecord.Kind.CONNECTION_LOST, null, null, null)
                        }
                    }
                }
                if (current == ConnectionPhase.CONNECTED && previous != null && previous != ConnectionPhase.CONNECTED) {
                    broker.onConnectionRestored()
                }
            }
        }
    }

    // ------------------------------------------------------------- lifecycle

    fun onForeground() {
        if (!started) start()
        scope.launch { runCatching { broker.onForegroundEntry() } }
    }

    fun onBackground() {
        scope.launch { runCatching { broker.onBackgroundEntry() } }
    }

    /** The RecoveryCoordinator's §15-S12 port — bound in AppContainer. */
    fun recordsPort(): RecoveryCoordinator.NotificationRecordPort = object : RecoveryCoordinator.NotificationRecordPort {
        override suspend fun visible(): List<NotificationRecord> = broker.visible()
        override suspend fun dismiss(keys: List<String>) = broker.dismiss(keys)
    }
}
