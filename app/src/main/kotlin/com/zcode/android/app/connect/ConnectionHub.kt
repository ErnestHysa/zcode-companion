package com.zcode.android.app.connect

import com.zcode.android.app.pairing.wsUrlForOrigin
import com.zcode.android.core.common.error.AppError
import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.security.vault.CredentialVault
import com.zcode.android.core.transport.ConnectionStateMachine
import com.zcode.android.core.transport.EndpointDescriptor
import com.zcode.android.core.transport.EndpointKind
import com.zcode.android.core.transport.ErrorMapping
import com.zcode.android.core.transport.RelayTransport
import com.zcode.android.data.session.ConversationGateway
import com.zcode.android.data.session.RecoveryCoordinator
import com.zcode.android.data.session.RelayConversationGateway
import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.domain.ids.SessionId
import com.zcode.android.domain.ids.WorkspaceIdentityKey
import com.zcode.android.domain.model.Endpoint
import com.zcode.android.domain.model.Task
import com.zcode.android.domain.model.TaskStatus
import com.zcode.android.domain.repo.ConversationRepository
import com.zcode.android.domain.repo.EndpointRepository
import com.zcode.android.domain.repo.InteractionRepository
import com.zcode.android.domain.repo.JournalRepository
import com.zcode.android.domain.repo.TaskRepository
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.domain.state.TaskStateMachine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/**
 * App-level connection lifecycle owner (Phase 6 §43.2-6.1/6.5): after pairing, this
 * is what keeps the phone honestly connected —
 *
 *  cold start (paired) → connect+auth (RelayTransport owns the §14.4.1 machine and
 *  its reconnect loop) → bootstrap/workspace-list → task projection persistence
 *  (§17.3) → live `workspace-list-updated` pushes refresh the list (≤ 2 s of event,
 *  §48 U2).
 *
 * Conversation sessions are attached through the Phase 5 [RecoveryCoordinator]
 * (journal restore + subscribe + escalation). INV-2/INV-6: this is the ONLY app
 * component that touches the transport; features see state through ports only.
 */
class ConnectionHub(
    private val transport: RelayTransport,
    private val vault: CredentialVault,
    private val taskRepo: TaskRepository,
    private val conversationRepo: ConversationRepository,
    private val journalRepo: JournalRepository,
    private val interactions: InteractionRepository,
    private val endpointRepo: EndpointRepository,
    private val scope: CoroutineScope,
    internal val log: ZLog,
) {

    val gateway: ConversationGateway by lazy { RelayConversationGateway(transport, clientId, log) }

    private val clientId: String = "zcode-android-" + vault.loadMetadata()?.endpointId.let { it ?: "unpaired" }

    private val _phase = MutableStateFlow(ConnectionPhase.UNPAIRED)
    val phase: StateFlow<ConnectionPhase> = _phase.asStateFlow()

    private val _endpoint = MutableStateFlow<Endpoint?>(null)
    val endpoint: StateFlow<Endpoint?> = _endpoint.asStateFlow()

    private val _taskLoadError = MutableStateFlow<AppError?>(null)
    val taskLoadError: StateFlow<AppError?> = _taskLoadError.asStateFlow()

    private val _lastRefreshAtMs = MutableStateFlow<Long?>(null)
    val lastRefreshAtMs: StateFlow<Long?> = _lastRefreshAtMs.asStateFlow()

    private val _endpointName = MutableStateFlow<String?>(null)
    val endpointName: StateFlow<String?> = _endpointName.asStateFlow()

    /** Connection-center surface: latest transport-class error (takeover UX §15.3). */
    private val _lastError = MutableStateFlow<AppError?>(null)
    val lastError: StateFlow<AppError?> = _lastError.asStateFlow()

    /**
     * Round-4 field finding: the relay keeps accepting our auth while answering
     * every bridge-open with desktop-disconnected — the classic STALE PAIRING shape
     * (the desktop re-registered on QR refresh; the old sid/mid still authenticate
     * but point at a dead desktop session). When Degraded for exactly that reason
     * AND the sealed credential is older than the §12.2 QR staleness window, the UI
     * offers a direct Re-pair action instead of a pointless Retry.
     */
    private val sealedAtMs: Long? = vault.loadMetadata()?.sealedAtMs

    val suggestRepair: StateFlow<Boolean> = kotlinx.coroutines.flow.combine(
        _phase,
        _lastError,
    ) { phase, error ->
        // NOTE: combine with a finite flow (flow{emit}) FREEZES the stream when the
        // finite side completes — staleness is captured once as a value instead.
        phase == ConnectionPhase.DEGRADED &&
            error?.developerDiagnostic?.contains("desktop-disconnected") == true &&
            sealedAtMs != null &&
            (System.currentTimeMillis() - sealedAtMs) > PAIRING_STALE_MS
    }.stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)

    fun dismissError() {
        _lastError.value = null
    }

    /** Round-7c diagnostics: structured snapshot for the export report. */
    data class SessionDiag(
        val sessionId: String,
        val taskState: String,
        val stale: Boolean,
        val cachedRows: Int,
    )

    data class HubDiagnostics(
        val connectionId: String?,
        val capabilities: String?,
        val journalSubscriptions: List<String>,
        val sessions: List<SessionDiag>,
        val taskCount: Int,
        val desktopAppVersion: String?,
        val desktopAboveTested: Boolean,
    )

    suspend fun diagnosticsSnapshot(): HubDiagnostics {
        val endpointId = vault.loadMetadata()?.endpointId
        val caps = transport.capabilities.value
        val capsLine = caps?.let {
            "conn=${it.connectionId.take(12)}… binary=${it.host.binaryFrames} compression=${it.host.compression} desktop=${it.desktopAppVersion}"
        }
        val journal = endpointId?.let { journalRepo.load(EndpointId(it)) }
        val subs = journal?.subscriptions?.map { "session=${it.sessionId.take(16)} afterSeq=${it.afterSeq} epoch=${it.logEpoch.take(12)}" } ?: emptyList()
        val sessions = coordinatorOrNull()?.managedSessions().orEmpty().map { id ->
            val m = coordinatorOrNull()?.manager(id)
            SessionDiag(
                sessionId = id,
                taskState = m?.taskState?.value?.name ?: "?",
                stale = m?.stale?.value ?: false,
                cachedRows = runCatching {
                    kotlinx.coroutines.withTimeout(2_000) { conversationRepo.cachedRowCount(SessionId(id)) }
                }.getOrDefault(-1),
            )
        }
        val count = endpointId?.let {
            runCatching { kotlinx.coroutines.withTimeout(2_000) { taskRepo.observeAllTasks(EndpointId(it)).first().size } }.getOrDefault(-1)
        } ?: -1
        val desktopVersion = caps?.desktopAppVersion
        val aboveTested = desktopVersion?.let {
            com.zcode.android.core.security.pairing.CompatibilityGate.check(it) is com.zcode.android.core.security.pairing.CompatibilityGate.Verdict.AboveTested
        } ?: false
        return HubDiagnostics(caps?.connectionId, capsLine, subs, sessions, count, desktopVersion, aboveTested)
    }

    /** Round-7 diagnostics: cached task count without a suspend call. */
    fun cachedTaskCountForDiagnostics(): Int = runCatching {
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeout(2_000) { currentTasks().size }
        }
    }.getOrDefault(-1)

    companion object {
        /** §12.2 QR staleness window ("not older than 10 min by default"). */
        const val PAIRING_STALE_MS = 10L * 60 * 1000
    }

    /** Port accessors (features never see the coordinator itself). */

    /**
     * Bumped whenever a session manager attaches: the per-session observation flows
     * below re-bind to the REAL manager flows through flatMapLatest — an observer
     * that attached BEFORE [openConversation] (the normal first-composition order)
     * must never be stranded on a throwaway empty flow.
     */
    private val managersVersion = kotlinx.coroutines.flow.MutableStateFlow(0)

    fun coordinatorOrNull(): RecoveryCoordinator? = coordinator

    suspend fun currentTasks(): List<Task> {
        val endpointId = vault.loadMetadata()?.endpointId ?: return emptyList()
        return taskRepo.observeAllTasks(EndpointId(endpointId)).first()
    }

    private fun <T> sessionFlow(
        taskId: String,
        pick: (com.zcode.android.data.session.ConversationSessionManager) -> StateFlow<T>,
        fallback: T,
    ): kotlinx.coroutines.flow.Flow<T> =
        managersVersion.flatMapLatest {
            val m = coordinatorOrNull()?.manager(taskId)
            if (m != null) pick(m) else kotlinx.coroutines.flow.flowOf(fallback)
        }

    fun sessionStale(taskId: String): kotlinx.coroutines.flow.Flow<Boolean> =
        sessionFlow(taskId, { m -> m.stale }, false)

    /** §19.4 backward pagination: more history above the current tail window (vc11). */
    fun sessionHasMoreHistory(taskId: String): kotlinx.coroutines.flow.Flow<Boolean> =
        sessionFlow(taskId, { m -> m.hasMoreHistory }, false)

    /** Total rows in the session (window metadata; null when unknown). */
    fun sessionTotalRows(taskId: String): kotlinx.coroutines.flow.Flow<Long?> =
        sessionFlow(taskId, { m -> m.totalRows }, null)

    fun sessionTaskState(taskId: String): kotlinx.coroutines.flow.Flow<TaskStateMachine.State> =
        sessionFlow(taskId, { m -> m.taskState }, TaskStateMachine.State.UNKNOWN)

    fun sessionStreaming(taskId: String): kotlinx.coroutines.flow.Flow<List<com.zcode.android.domain.model.StreamingBuffer>> =
        sessionFlow(taskId, { m -> m.streaming }, emptyList())

    private var connectJob: Job? = null
    private var coordinator: RecoveryCoordinator? = null
    private var controlPlaneJob: Job? = null
    private var started = false

    /**
     * Invoked on EVERY transport CONNECTED transition. The composition root wires
     * this to the pairing port: a background self-heal success (e.g. the QR flow's
     * connect failed retryably, the recovery loop connected later) must flip the
     * port's paired state so the nav gate moves the user out of the pairing screen.
     */
    var onConnected: (() -> Unit)? = null

    /**
     * Round-7e: invoked when the machine ENTERS Degraded (not on repeats) — the
     * composition root persists a structured auto-report at the moment of failure,
     * so the state that caused the Degraded is captured even if the app is killed
     * before the user exports.
     */
    var onDegradedReport: ((String) -> Unit)? = null
    @Volatile private var lastReportedPhase: ConnectionPhase? = null

    /** Idempotent app-start entry: wire state mapping; connect when paired. */
    fun start() {
        if (started) return
        started = true
        val meta = vault.loadMetadata()
        _endpoint.value = meta?.let {
            Endpoint(
                endpointId = EndpointId(it.endpointId),
                kind = com.zcode.android.domain.model.EndpointKind.RELAY_ZAI,
                displayName = it.desktopName,
                wsUrl = wsUrlForOrigin(it.endpointOrigin),
                pairedAtMs = it.sealedAtMs,
                deviceSid = it.deviceSid,
                deviceMid = it.deviceMid,
                desktopAppVersion = it.desktopAppVersion,
            )
        }
        _phase.value = if (meta == null) ConnectionPhase.UNPAIRED else phaseOf(transport.states.value)
        _endpointName.value = meta?.desktopName
        scope.launch {
            var last: ConnectionStateMachine.ConnectionState? = null
            transport.states.collect { state ->
                if (state != last) {
                    // Every §14.4.1 machine transition, visible in field diagnostics.
                    log.info("hub.phase", "transport $last → $state")
                    last = state
                }
                val phase = if (vault.loadMetadata() == null) ConnectionPhase.UNPAIRED else phaseOf(state)
                _phase.value = phase
                // Round-7e: auto-report exactly once per Degraded ENTRY — the failure
                // state is captured at the moment it happens, even if the app dies
                // before the user exports.
                if (phase == ConnectionPhase.DEGRADED && lastReportedPhase != ConnectionPhase.DEGRADED) {
                    val report = runCatching {
                        val stats = transport.statsSnapshot()
                        "=== AUTO REPORT: DEGRADED (" + java.time.Instant.now() + ") ===\n" +
                            "desktopDisconnectedConsecutive: " + stats["desktopDisconnectedConsecutive"] + "\n" +
                            "bridgeErrors: " + stats["bridgeErrors"] + "\n" +
                            "lastError: " + (_lastError.value?.let { it.category.toString() + " :: " + it.developerDiagnostic.take(160) } ?: "none") + "\n" +
                            diagnosticsSnapshot().let { d ->
                                "capabilities: " + (d.capabilities ?: "none") + "\n" +
                                    "journal: " + d.journalSubscriptions.joinToString(" | ") + "\n" +
                                    "sessions: " + d.sessions.joinToString(" | ") { s ->
                                        s.sessionId.take(20) + " state=" + s.taskState + " stale=" + s.stale + " rows=" + s.cachedRows
                                    }
                            }
                    }.getOrDefault("auto-report generation failed")
                    onDegradedReport?.invoke(report)
                    log.info("hub.degradedAutoReport", "written len=${report.length}")
                }
                lastReportedPhase = phase
                onTransportState(state)
            }
        }
        scope.launch {
            transport.appErrors.collect { _lastError.value = it }
        }
        if (meta != null && transport.states.value != ConnectionStateMachine.ConnectionState.CONNECTED) {
            connect()
        }
        observeControlPlanePushes()
    }

    /** Pairing completed → adopt the new endpoint and ride the live transport. */
    fun onPairingCompleted() {
        started = true
        val meta = vault.loadMetadata() ?: return
        _endpoint.value = Endpoint(
            endpointId = EndpointId(meta.endpointId),
            kind = com.zcode.android.domain.model.EndpointKind.RELAY_ZAI,
            displayName = meta.desktopName,
            wsUrl = wsUrlForOrigin(meta.endpointOrigin),
            pairedAtMs = meta.sealedAtMs,
            deviceSid = meta.deviceSid,
            deviceMid = meta.deviceMid,
            desktopAppVersion = meta.desktopAppVersion,
        )
        _phase.value = phaseOf(transport.states.value)
        _endpointName.value = meta.desktopName
        observeControlPlanePushes()
    }

    /** User "Retry" from Degraded/Failed (§14.4.1 manual retry edges). */
    fun retry() {
        log.info("hub.retry", "user retry from phase=${transport.states.value} (single-flight → reconnectNow)")
        // Round-6 field fix (the DESKTOP-FREEZE culprit): user Retry while the
        // transport is mid-recovery (RECONNECTING/CONNECTING/AUTHENTICATING) used to
        // call connect() DIRECTLY — a SECOND concurrent full handshake racing the
        // transport's own recovery loop (field log: 3 hub.connectFailed
        // IllegalStateException in 1.4 s + the #328-class desktop freeze). ALL user
        // retries now route through reconnectNow(), which is single-flight-aware:
        // it arms the immediate flag on the RUNNING loop, or starts one from
        // Degraded with a fresh budget. connect() itself remains only for the
        // cold-start path (no live machine) and hard-FAILED (re-scan class).
        when (transport.states.value) {
            ConnectionStateMachine.ConnectionState.DEGRADED,
            ConnectionStateMachine.ConnectionState.RECONNECTING,
            ConnectionStateMachine.ConnectionState.CONNECTING,
            ConnectionStateMachine.ConnectionState.AUTHENTICATING,
            ConnectionStateMachine.ConnectionState.RECOVERING,
            -> transport.reconnectNow()
            ConnectionStateMachine.ConnectionState.FAILED -> connect()
            else -> if (connectJob?.isActive != true) connect()
        }
    }

    /** §15-S3 foreground wake → immediate reconnect, skip backoff. */
    fun foregroundTrigger() {
        coordinator?.foregroundTrigger() ?: transport.reconnectNow()
    }

    /** §15-S4 network restored → probe. */
    fun networkAvailable() {
        coordinator?.notifyNetworkAvailable() ?: transport.notifyNetworkAvailable()
    }

    // ------------------------------------------------------------------ task plane

    /**
     * bootstrap → workspace list + tasks → task projection (§17.3). Called on every
     * CONNECTED entry (fresh + re-connect) and by pull-to-refresh.
     */
    suspend fun refreshTasks(): Boolean {
        val meta = vault.loadMetadata() ?: return false
        return try {
            val payload = transport.bootstrap()
            applyBootstrap(payload)
            true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val error = (e as? RelayTransport.Failure)?.error
                ?: ErrorMapping.networkUnreachable("bootstrap failed: ${e.javaClass.simpleName}")
            _taskLoadError.value = error
            log.warn("hub.bootstrapFailed", "${error.category} ${error.developerDiagnostic.take(80)}")
            false
        }
    }

    private suspend fun applyBootstrap(payload: JsonObject?) {
        val parsed = BootstrapParser.parseBootstrap(payload)
        val meta = vault.loadMetadata() ?: return
        // Round-6 field fix: a RESTARTING desktop answers bootstrap with EMPTY view
        // (tasks=0 workspaces=0 — observed live between DEVICE_OFFLINE and the
        // desktop finishing its reload). Applying it would wipe the cached 482-task
        // projection and flash an empty list; keep the cache instead (the stale
        // label stays honest — the connection state is not Connected then).
        if (parsed.tasks.isEmpty() && parsed.workspaces.isEmpty()) {
            val cached = taskRepo.observeAllTasks(EndpointId(meta.endpointId)).first().isNotEmpty()
            if (cached) {
                log.warn("hub.bootstrapEmptySkipped", "desktop view empty (restarting?) — cached projection kept")
                return
            }
        }
        val endpointId = EndpointId(meta.endpointId)
        val now = System.currentTimeMillis()
        // Group tasks by §7.11 identity key; each group is one workspace projection.
        val byWorkspace = parsed.tasks.groupBy { it.workspaceIdentityKey }
        // FIELD (2026-09-30, vc13 crash): the desktop cross-lists a session under more
        // than one workspace — dedupe by taskId GLOBALLY (keep the most-recent updatedAt)
        // so the projection shows each task once.
        val seenTaskIds = HashMap<String, Long>() // taskId -> updatedAt of the kept copy
        val keptGroups = LinkedHashMap<String, List<BootstrapParser.ParsedTask>>()
        val flat = parsed.tasks.sortedByDescending { it.updatedAtMs }
        for (t in flat) {
            val prev = seenTaskIds[t.taskId]
            if (prev != null && prev >= t.updatedAtMs) continue
            seenTaskIds[t.taskId] = t.updatedAtMs
        }
        for ((key, group) in byWorkspace) {
            if (key.isBlank()) continue
            val tasks = group.filter { seenTaskIds[it.taskId] == it.updatedAtMs }
                .map { t ->
                Task(
                    taskId = t.taskId,
                    title = t.title,
                    status = TaskStatus.fromWire(t.statusWire),
                    workspace = WorkspaceIdentityKey(key),
                    createdAtMs = t.createdAtMs,
                    updatedAtMs = if (t.updatedAtMs > 0) t.updatedAtMs else t.createdAtMs,
                    workspaceLabel = t.workspaceLabel,
                )
            }
            taskRepo.replaceAll(endpointId, WorkspaceIdentityKey(key), tasks, etag = null, atMs = now)
        }
        // Workspaces that lost every task still deserve a section? No — the list is
        // task-driven in Phase 6 (workspace headers derive from task grouping).
        _taskLoadError.value = null
        _lastRefreshAtMs.value = now
        parsed.desktopAppVersion?.let { version ->
            _endpoint.value = _endpoint.value?.copy(desktopAppVersion = version)
        }
        log.info("hub.bootstrapApplied", "tasks=${parsed.tasks.size} workspaces=${byWorkspace.size} dropped=${parsed.droppedEntries}")
    }

    /**
     * §48 U2 live status: the desktop pushes `workspace-list-updated` (verified
     * control-plane vocabulary); refresh the projection from it (≤ 2 s of event).
     */
    private fun observeControlPlanePushes() {
        if (controlPlaneJob != null) return
        controlPlaneJob = scope.launch {
            transport.inbound.collect { frame ->
                if (frame is com.zcode.android.core.transport.InboundFrame.ControlPayload &&
                    frame.zcodeType == "workspace-list-updated"
                ) {
                    runCatching { applyBootstrap(frame.payload) }
                        .onFailure { e ->
                            log.warn("hub.workspaceListUpdatedFault", e.javaClass.simpleName)
                        }
                }
            }
        }
    }

    // ------------------------------------------------------------------ conversation plane

    /** Per-session open dedupe: a session is attached EXACTLY once (see below). */
    private val openedSessions = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val openMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * Attach a conversation session (Phase 5 coordinator; Phase 6 consumer). The
     * task's own workspace targets the v4 subscribe (workspace is carried per call).
     *
     * SINGLE-FLIGHT per session (§28.5): a destination recomposition can construct a
     * second ViewModel whose [open] races the first — without this mutex both calls
     * create a coordinator, both subscribe (double snapshot), and the surviving
     * manager misses every delta frame published before it attached (the gateway
     * SharedFlow has no replay). First open wins; later opens observe the SAME
     * manager through the [managersVersion]-rebinding flows.
     */
    suspend fun openConversation(task: Task): RecoveryCoordinator? {
        val meta = vault.loadMetadata() ?: return null
        return openMutex.withLock {
            val coordinator = ensureCoordinator()
            if (openedSessions.add(task.taskId)) {
                coordinator.startTargets(
                    listOf(
                        RecoveryCoordinator.SessionTarget(
                            sessionId = task.taskId,
                            workspacePath = taskWorkspacePath(task),
                            workspaceIdentity = null,
                        ),
                    ),
                )
                // FIELD-KILLER #10 (2026-09-30, vc14/vc15): managers are created HERE —
                // the managersVersion bump at coordinator creation ran BEFORE the session
                // managers existed, so sessionFlow's flatMapLatest held flowOf(fallback)
                // forever: hasMoreHistory/taskState/stale/streaming never reached the UI
                // for freshly-attached sessions. Re-bump after the attach.
                managersVersion.value++
            }
            log.info("hub.conversationOpen", "session=${task.taskId}")
            coordinator
        }
    }

    /** Best-effort workspace path for a task (identity key may BE the path). */
    private fun taskWorkspacePath(task: Task): String? = task.workspace.raw

    private fun ensureCoordinator(): RecoveryCoordinator {
        coordinator?.let { return it }
        val endpointId = EndpointId(vault.loadMetadata()?.endpointId ?: "unpaired")
        val caps = transport.capabilities.value
        val created = RecoveryCoordinator(
            endpointId = endpointId,
            gateway = gateway,
            store = conversationRepo,
            journalRepo = journalRepo,
            interactions = com.zcode.android.data.session.InteractionCoordinator(
                endpointId = endpointId,
                interactions = interactions,
                outbox = OutboxStub,
                commands = gateway,
            ),
            transport = transport,
            scope = scope,
            log = log,
            config = RecoveryCoordinator.Config(
                helloServerTimeMs = caps?.serverTimeMs,
                capabilitiesJson = null,
            ),
        )
        coordinator = created
        managersVersion.value++
        return created
    }

    // ------------------------------------------------------------------ internals

    private fun connect() {
        if (connectJob?.isActive == true) return
        log.info("hub.connect", "cold-start connect begin")
        connectJob = scope.launch {
            val meta = vault.loadMetadata() ?: return@launch
            val signer = vault.loadSigner() ?: run {
                _phase.value = ConnectionPhase.FAILED
                return@launch
            }
            val descriptor = EndpointDescriptor(
                id = meta.endpointId,
                kind = EndpointKind.RELAY_ZAI,
                displayName = meta.desktopName,
                wsUrl = wsUrlForOrigin(meta.endpointOrigin),
                deviceSid = meta.deviceSid,
                deviceMid = meta.deviceMid,
                desktopAppVersion = meta.desktopAppVersion,
            )
            try {
                transport.connect(descriptor) { nonce, role, sid -> signer.proof(nonce, role, sid) }
                refreshTasks()
                persistEndpoint(descriptor)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val err = (e as? RelayTransport.Failure)?.error
                // Log the CATEGORY + DIAGNOSTIC (the field-visible reason — never just
                // the category again). §14.4.1 self-heal: the transport's recovery loop
                // already launched itself on retryable failures and owns the EXPONENTIAL
                // BACKOFF (budget → Degraded → Retry). The hub must NOT poke
                // reconnectNow() here: requestImmediate collapses the next delay to 0,
                // and a 0 ms retry storm against the relay is the observed cause of
                // desktop-disconnected flapping (feedback #328 class).
                log.warn(
                    "hub.connectFailed",
                    err?.let { "${it.category} ${it.developerDiagnostic.take(140)}" }
                        // Diagnosability (field: bare "IllegalStateException" ×3 said
                        // nothing): non-Failure exceptions MUST carry their message.
                        ?: "${e.javaClass.simpleName}: ${e.message?.take(120)}",
                )
            }
        }
    }

    private suspend fun persistEndpoint(descriptor: EndpointDescriptor) {
        val existing = endpointRepo.endpoints().firstOrNull { it.deviceSid == descriptor.deviceSid }
        val endpoint = existing?.copy(
            displayName = descriptor.displayName,
            wsUrl = descriptor.wsUrl,
            desktopAppVersion = descriptor.desktopAppVersion,
            lastHealthyAtMs = System.currentTimeMillis(),
        ) ?: Endpoint(
            endpointId = EndpointId(descriptor.id),
            kind = com.zcode.android.domain.model.EndpointKind.RELAY_ZAI,
            displayName = descriptor.displayName,
            wsUrl = descriptor.wsUrl,
            pairedAtMs = System.currentTimeMillis(),
            deviceSid = descriptor.deviceSid,
            deviceMid = descriptor.deviceMid,
            desktopAppVersion = descriptor.desktopAppVersion,
        )
        endpointRepo.save(endpoint)
        _endpoint.value = endpoint
        _endpointName.value = endpoint.displayName
    }

    private fun onTransportState(state: ConnectionStateMachine.ConnectionState) {
        when (state) {
            ConnectionStateMachine.ConnectionState.CONNECTED -> scope.launch {
                onConnected?.invoke()
                refreshTasks()
            }
            else -> Unit
        }
    }

    private fun phaseOf(state: ConnectionStateMachine.ConnectionState): ConnectionPhase = connectionPhaseOf(state)

    /**
     * Phase 6 reads: the outbox is Phase 7 (send/approve). The InteractionCoordinator
     * requires a CommandSender-backed outbox for its submit path, but Phase 6 issues
     * NO commands — a closed stub keeps the type satisfied and fail-closed (any
     * accidental submit attempt surfaces as an illegal outbox transition, INV-4).
     */
    private object OutboxStub : com.zcode.android.domain.repo.OutboxRepository {
        override fun observePending(endpointId: EndpointId) =
            kotlinx.coroutines.flow.flowOf(emptyList<com.zcode.android.domain.outbox.OutboxEntry>())
        override suspend fun accept(
            endpointId: EndpointId,
            sessionId: String?,
            type: String,
            payloadJson: String,
            baseRevision: Long?,
            baseLogEpoch: String?,
            issuedAtMs: Long,
        ): com.zcode.android.domain.ids.CommandId =
            throw com.zcode.android.domain.outbox.OutboxStateMachine.IllegalOutboxTransition(
                com.zcode.android.domain.outbox.OutboxStateMachine.State.DRAFT,
                com.zcode.android.domain.outbox.OutboxStateMachine.Event.Accept,
                "Phase 6 is read-only: no command submission exists",
            )
        override suspend fun apply(
            commandId: com.zcode.android.domain.ids.CommandId,
            event: com.zcode.android.domain.outbox.OutboxStateMachine.Event,
            atMs: Long,
        ): com.zcode.android.domain.outbox.OutboxEntry? = null
        override suspend fun get(commandId: com.zcode.android.domain.ids.CommandId) = null
        override suspend fun sweepExpired(endpointId: EndpointId, nowMs: Long, ttlMs: Long) = 0
        override suspend fun pendingRefs(endpointId: EndpointId) = emptyList<com.zcode.android.domain.journal.RecoveryJournal.PendingCommandRef>()
        override suspend fun countByState(endpointId: EndpointId, state: com.zcode.android.domain.outbox.OutboxStateMachine.State) = 0
    }
}

/** Total §14.4.1 → UI mirror mapping (unit-tested: no state invented or dropped). */
internal fun connectionPhaseOf(state: ConnectionStateMachine.ConnectionState): ConnectionPhase = when (state) {
    ConnectionStateMachine.ConnectionState.UNPAIRED -> ConnectionPhase.UNPAIRED
    ConnectionStateMachine.ConnectionState.PAIRING -> ConnectionPhase.PAIRING
    ConnectionStateMachine.ConnectionState.AUTHENTICATING -> ConnectionPhase.AUTHENTICATING
    ConnectionStateMachine.ConnectionState.CONNECTING -> ConnectionPhase.CONNECTING
    ConnectionStateMachine.ConnectionState.CONNECTED -> ConnectionPhase.CONNECTED
    ConnectionStateMachine.ConnectionState.RECONNECTING -> ConnectionPhase.RECONNECTING
    ConnectionStateMachine.ConnectionState.RECOVERING -> ConnectionPhase.RECOVERING
    ConnectionStateMachine.ConnectionState.DEGRADED -> ConnectionPhase.DEGRADED
    ConnectionStateMachine.ConnectionState.FAILED -> ConnectionPhase.FAILED
    ConnectionStateMachine.ConnectionState.CLOSED -> ConnectionPhase.CLOSED
}
