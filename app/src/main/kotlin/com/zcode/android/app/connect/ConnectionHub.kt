package com.zcode.android.app.connect

import com.zcode.android.app.pairing.wsUrlForOrigin
import com.zcode.android.core.common.error.AppError
import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.security.vault.CredentialVault
import com.zcode.android.core.transport.ConnectionStateMachine
import com.zcode.android.core.transport.EndpointDescriptor
import com.zcode.android.core.transport.EndpointKind
import com.zcode.android.core.transport.ErrorMapping
import com.zcode.android.core.transport.AppTransport
import com.zcode.android.core.transport.DirectTransport
import com.zcode.android.core.transport.RelayTransport
import com.zcode.android.core.transport.ServerInfo
import com.zcode.android.core.transport.TransportFailure
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
import kotlinx.coroutines.flow.map
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
    /** The RELAY transport (§11.4 Option A). The relay pairing/credential flows bind here. */
    private val transport: RelayTransport,
    /** The DIRECT transport (§11.4 Option B, Phase 10) — token capability from the vault. */
    private val directTransport: DirectTransport,
    private val vault: CredentialVault,
    private val taskRepo: TaskRepository,
    private val conversationRepo: ConversationRepository,
    private val journalRepo: JournalRepository,
    private val interactions: InteractionRepository,
    private val endpointRepo: EndpointRepository,
    private val preferences: com.zcode.android.domain.repo.PreferencesRepository,
    /** Phase 7: the REAL outbox + drafts (the Phase 6 OutboxStub is retired). */
    private val outbox: com.zcode.android.domain.repo.OutboxRepository,
    private val drafts: com.zcode.android.domain.repo.DraftRepository,
    private val scope: CoroutineScope,
    internal val log: ZLog,
) {

    /**
     * Phase 8: the §15-S12 notification reconciliation port (the §24 broker). Set by
     * the composition root right after construction — the recovery coordinator is
     * created LAZILY (ensureCoordinator), so the port is always bound before use.
     */
    var notificationRecords: RecoveryCoordinator.NotificationRecordPort? = null

    /**
     * Phase 10: per-transport gateway cache (INV-6 — the gateway binds to the ACTIVE
     * transport; switching endpoints swaps it; §8.3.1 both share the v4 plane).
     */
    private val gateways = java.util.concurrent.ConcurrentHashMap<AppTransport, ConversationGateway>()

    private fun gatewayFor(t: AppTransport = activeTransport()): ConversationGateway =
        gateways.getOrPut(t) { RelayConversationGateway(t, clientIdFor(), log) }

    /** Port-facing gateway of the ACTIVE transport (INV-6: one transport downstream). */
    fun gateway(): ConversationGateway = gatewayFor()

    private fun clientIdFor(): String =
        "zcode-android-" + activeEndpointIdRaw().let { it ?: vault.loadMetadata()?.endpointId ?: "unpaired" }

    private val _phase = MutableStateFlow(ConnectionPhase.UNPAIRED)
    val phase: StateFlow<ConnectionPhase> = _phase.asStateFlow()

    private val _endpoint = MutableStateFlow<Endpoint?>(null)
    val endpoint: StateFlow<Endpoint?> = _endpoint.asStateFlow()

    // ------------------------------------------------------------------ Phase 10 registry
    //
    // §11.6: multiple paired endpoints (1 relay + N direct), ONE active connection at
    // a time (the relay slot is single-terminal anyway). Active selection persists in
    // preferences (selectedEndpointId); the registry lives in endpointRepo.

    private val _endpoints = MutableStateFlow<List<Endpoint>>(emptyList())
    /** The endpoint registry (§11.3) — every saved endpoint across both kinds. */
    val endpoints: StateFlow<List<Endpoint>> = _endpoints.asStateFlow()

    private val _directServerInfo = MutableStateFlow<ServerInfo?>(null)
    /** server-info of the ACTIVE direct endpoint (null on relay / before first connect). */
    val directServerInfo: StateFlow<ServerInfo?> = _directServerInfo.asStateFlow()

    private val _switching = MutableStateFlow(false)
    /** True while an endpoint switch tears down + reconnects (UI shows the hub state honestly). */
    val switching: StateFlow<Boolean> = _switching.asStateFlow()

    private fun activeKind(): EndpointKind = when (_endpoint.value?.kind) {
        com.zcode.android.domain.model.EndpointKind.DIRECT_SERVER -> EndpointKind.DIRECT_SERVER
        else -> EndpointKind.RELAY_ZAI
    }

    private fun activeEndpointIdRaw(): String? = _endpoint.value?.endpointId?.raw

    /** The transport the ACTIVE endpoint is bound to (INV-6: downstream sees ONE transport). */
    fun activeTransport(): AppTransport =
        if (activeKind() == EndpointKind.DIRECT_SERVER) directTransport else transport

    private suspend fun refreshRegistry() {
        _endpoints.value = endpointRepo.endpoints()
    }

    private val _taskLoadError = MutableStateFlow<AppError?>(null)
    val taskLoadError: StateFlow<AppError?> = _taskLoadError.asStateFlow()

    /** Workspaces offered by the desktop (bootstrap) — the new-task picker's source. */
    private val _workspaces = MutableStateFlow<List<com.zcode.android.domain.model.WorkspaceOption>>(emptyList())
    val workspaces: StateFlow<List<com.zcode.android.domain.model.WorkspaceOption>> = _workspaces.asStateFlow()

    /**
     * Phase 2 (§11.2, GAP-12): the workspace-level config catalog per §7.11
     * identity key — slash commands + config options, WHOLE replacement per
     * workspace-config frame (conflated latest-wins). Cleared only on endpoint/
     * workspace lifecycle (the desktop's preserve-for-replay rule: session
     * re-attach never backfills it to empty).
     */
    private val _workspaceCatalogs =
        MutableStateFlow<Map<String, com.zcode.android.core.protocol.v4.WorkspaceConfigState>>(emptyMap())
    private var configCollectorJob: kotlinx.coroutines.Job? = null

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
        val caps = activeTransport().capabilities.value
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
        val endpointId = activeEndpointIdRaw() ?: vault.loadMetadata()?.endpointId ?: return emptyList()
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

    /** Phase 7 A-side projection (control/queue/pendingInteractions/inputRouting). */
    fun sessionProjectionState(taskId: String): kotlinx.coroutines.flow.Flow<com.zcode.android.domain.model.SessionProjectionState> =
        sessionFlow(taskId, { m -> m.sessionState }, com.zcode.android.domain.model.SessionProjectionState())

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

    /** Idempotent app-start entry: wire state mapping; connect the ACTIVE endpoint when paired. */
    fun start() {
        if (started) return
        started = true
        // SYNCHRONOUS seed (first-frame correctness): the nav gate reads the paired
        // state during the first composition — a relay pairing must be visible
        // IMMEDIATELY (DefaultPairingPort's initializer had the same property; the
        // Phase-10 async-only seed flashed the pairing screen and raced the nav on
        // recreate — device-caught 2026-10-03). The §11.6 selection below refines
        // this to a direct endpoint when one is persisted (second nav flip only on
        // that rare path).
        val seededMeta = vault.loadMetadata()
        _endpoint.value = seededMeta?.let(::relayEndpointFromMeta)
        _endpointName.value = seededMeta?.desktopName
        _phase.value = if (seededMeta == null) ConnectionPhase.UNPAIRED else phaseOf(activeTransport().states.value)
        scope.launch {
            refreshRegistry()
            // §11.6 active-endpoint selection: prefs.selectedEndpointId → registry;
            // fall back to the paired relay endpoint (legacy single-endpoint behavior).
            val selectedId = runCatching { preferences.current().selectedEndpointId }.getOrNull()
            val meta = vault.loadMetadata()
            // §12.6 fail-closed: a selection whose credential no longer decrypts
            // (vault wiped/rotated) is DEAD — ignore it and fall back to the relay
            // pairing. Otherwise a stale selection hijacks cold start (found by the
            // HistoryPagination instrumented suite: a wiped direct token left the
            // persisted id pointing at a dead endpoint, 2026-10-03).
            val selected = selectedId?.let { id ->
                _endpoints.value.firstOrNull { it.endpointId.raw == id }?.takeUnless { ep ->
                    ep.kind == com.zcode.android.domain.model.EndpointKind.DIRECT_SERVER &&
                        !vault.hasDirectToken(ep.endpointId.raw)
                }
            }
            _endpoint.value = when {
                selected?.kind == com.zcode.android.domain.model.EndpointKind.DIRECT_SERVER -> selected
                meta != null -> relayEndpointFromMeta(meta)
                else -> selected
            }
            _endpointName.value = _endpoint.value?.displayName
            _phase.value = if (_endpoint.value == null) ConnectionPhase.UNPAIRED else phaseOf(activeTransport().states.value)
            val active = _endpoint.value
            if (active != null && activeTransport().states.value != ConnectionStateMachine.ConnectionState.CONNECTED) {
                connect()
            }
        }
        // Dual collectors gated by the ACTIVE kind: only the active transport drives
        // the phase (the inactive one is quiet — it was closed on switch, §11.6).
        scope.launch {
            transport.states.collect { state -> onRelayState(state) }
        }
        scope.launch {
            directTransport.states.collect { state -> onDirectState(state) }
        }
        scope.launch {
            transport.appErrors.collect { if (activeKind() == EndpointKind.RELAY_ZAI) _lastError.value = it }
        }
        scope.launch {
            directTransport.appErrors.collect { if (activeKind() == EndpointKind.DIRECT_SERVER) _lastError.value = it }
        }
        observeControlPlanePushes()
    }

    private fun relayEndpointFromMeta(meta: com.zcode.android.core.security.vault.CredentialMetadata): Endpoint =
        Endpoint(
            endpointId = EndpointId(meta.endpointId),
            kind = com.zcode.android.domain.model.EndpointKind.RELAY_ZAI,
            displayName = meta.desktopName,
            wsUrl = wsUrlForOrigin(meta.endpointOrigin),
            pairedAtMs = meta.sealedAtMs,
            deviceSid = meta.deviceSid,
            deviceMid = meta.deviceMid,
            desktopAppVersion = meta.desktopAppVersion,
        )

    private suspend fun onRelayState(state: ConnectionStateMachine.ConnectionState) {
        onActiveState(EndpointKind.RELAY_ZAI, state, transport.statsSnapshot())
    }

    private suspend fun onDirectState(state: ConnectionStateMachine.ConnectionState) {
        onActiveState(EndpointKind.DIRECT_SERVER, state, directTransport.statsSnapshot())
    }

    private suspend fun onActiveState(
        sourceKind: EndpointKind,
        state: ConnectionStateMachine.ConnectionState,
        stats: Map<String, Long>,
    ) {
        if (sourceKind != activeKind()) return // inactive transport: quiet (closed on switch)
        log.info("hub.phase", "transport ($sourceKind) → $state")
        val paired = _endpoint.value != null || vault.loadMetadata() != null
        val phase = if (!paired) ConnectionPhase.UNPAIRED else phaseOf(state)
        _phase.value = phase
        // Round-7e: auto-report exactly once per Degraded ENTRY — the failure state is
        // captured at the moment it happens, even if the app dies before the user exports.
        if (phase == ConnectionPhase.DEGRADED && lastReportedPhase != ConnectionPhase.DEGRADED) {
            val report = runCatching {
                "=== AUTO REPORT: DEGRADED (" + java.time.Instant.now() + ") ===\n" +
                    "transport: $sourceKind\n" +
                    ("desktopDisconnectedConsecutive: " + (stats["desktopDisconnectedConsecutive"] ?: 0L)) + "\n" +
                    "bridgeErrors: " + (stats["bridgeErrors"] ?: 0L) + "\n" +
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

    /**
     * Pairing completed → adopt the RELAY endpoint. §11.6-aware: when the ACTIVE
     * endpoint is ALREADY this relay endpoint (the pairing flow connects its own
     * transport), the live connection is KEPT (Phase-9 semantics — a teardown here
     * killed the just-established pairing, found by the HistoryPagination suite);
     * a DIFFERENT active endpoint (a direct one) does a real switch.
     */
    fun onPairingCompleted() {
        started = true
        val meta = vault.loadMetadata() ?: return
        val current = _endpoint.value
        if (current != null && current.endpointId.raw != meta.endpointId) {
            scope.launch { switchTo(meta.endpointId) }
            return
        }
        _endpoint.value = relayEndpointFromMeta(meta)
        _endpointName.value = meta.desktopName
        _phase.value = phaseOf(transport.states.value)
        scope.launch {
            refreshRegistry()
            persistEndpoint(
                EndpointDescriptor(
                    id = meta.endpointId,
                    kind = EndpointKind.RELAY_ZAI,
                    displayName = meta.desktopName,
                    wsUrl = wsUrlForOrigin(meta.endpointOrigin),
                    deviceSid = meta.deviceSid,
                    deviceMid = meta.deviceMid,
                    desktopAppVersion = meta.desktopAppVersion,
                ),
            )
        }
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
        // retries route through reconnectNow(), which is single-flight-aware:
        // it arms the immediate flag on the RUNNING loop, or starts one from
        // Degraded/FAILED (the F2 arm — an AUTH-failed state recovers via the
        // transport's own user-retry path with a fresh budget; connect() here used
        // to throw "connect() is the first-connection path"). connect() itself
        // remains only for the cold-start path (no live machine).
        when (activeTransport().states.value) {
            ConnectionStateMachine.ConnectionState.DEGRADED,
            ConnectionStateMachine.ConnectionState.FAILED,
            ConnectionStateMachine.ConnectionState.RECONNECTING,
            ConnectionStateMachine.ConnectionState.CONNECTING,
            ConnectionStateMachine.ConnectionState.AUTHENTICATING,
            ConnectionStateMachine.ConnectionState.RECOVERING,
            ConnectionStateMachine.ConnectionState.CONNECTED,
            -> activeTransport().reconnectNow()
            else -> if (connectJob?.isActive != true) connect()
        }
    }

    /** §15-S3 foreground wake → immediate reconnect, skip backoff. */
    fun foregroundTrigger() {
        coordinator?.foregroundTrigger() ?: activeTransport().reconnectNow()
    }

    /** §15-S4 network restored → probe. */
    fun networkAvailable() {
        coordinator?.notifyNetworkAvailable() ?: activeTransport().notifyNetworkAvailable()
    }

    // ------------------------------------------------------------------ task plane

    /**
     * bootstrap → workspace list + tasks → task projection (§17.3). Called on every
     * CONNECTED entry (fresh + re-connect) and by pull-to-refresh.
     */
    private val bootstrapInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    suspend fun refreshTasks(): Boolean {
        val activeId = activeEndpointIdRaw() ?: vault.loadMetadata()?.endpointId ?: return false
        // FIELD (2026-10-01, vc21 device log): connect() and the CONNECTED
        // transition both refreshed — TWO ~150 KB bootstrap round trips + two
        // 510-task Room rewrites per connect (log: bootstrapApplied ×2). The
        // in-flight guard keeps one.
        if (!bootstrapInFlight.compareAndSet(false, true)) {
            log.info("hub.bootstrapSkipped", "already in flight")
            return true
        }
        val ok = try {
            if (activeKind() == EndpointKind.DIRECT_SERVER) {
                refreshDirectTasks()
            } else {
                val payload = transport.bootstrap()
                applyBootstrap(payload)
            }
            true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val error = (e as? TransportFailure)?.error
                ?: ErrorMapping.networkUnreachable("task refresh failed: ${e.javaClass.simpleName}")
            _taskLoadError.value = error
            log.warn("hub.bootstrapFailed", "${error.category} ${error.developerDiagnostic.take(80)}")
            false
        }
        bootstrapInFlight.set(false)
        return ok
    }

    /**
     * Direct task plane (phase10-direct.md §6): server-info workspaces → per-workspace
     * `zcode-task.listTasks` → the SAME Room projection the relay bootstrap feeds
     * (INV-12 identity keys via §7.11 derivation). No relay control plane is used.
     */
    private suspend fun refreshDirectTasks() {
        val activeId = activeEndpointIdRaw() ?: return
        val info = directTransport.serverInfo ?: directTransport.refreshServerInfo() ?: return
        _directServerInfo.value = info
        val endpointId = EndpointId(activeId)
        val now = System.currentTimeMillis()
        val options = ArrayList<com.zcode.android.domain.model.WorkspaceOption>()
        var total = 0
        for (wsInfo in info.workspaces) {
            val path = wsInfo.path ?: continue
            val metas = runCatching { directTransport.listTasks(path) }
                .onFailure { log.warn("hub.directListTasks", "${it.javaClass.simpleName} for ${path.take(24)}") }
                .getOrDefault(emptyList())
            val identityKey = com.zcode.android.domain.ids.WorkspaceIdentityKey
                .of(null, path).raw // server-info carries no workspaceIdentity (§7.11: path fallback)
            options += com.zcode.android.domain.model.WorkspaceOption(
                key = identityKey,
                label = wsInfo.label ?: path.substringAfterLast('/'),
                path = path,
            )
            val tasks = metas
                .sortedByDescending { it.updatedAtMs }
                .distinctBy { it.taskId }
                .map { m ->
                    Task(
                        taskId = m.taskId,
                        title = m.title,
                        status = TaskStatus.fromWire(m.statusWire),
                        workspace = WorkspaceIdentityKey(
                            com.zcode.android.domain.ids.WorkspaceIdentityKey
                                .of(m.workspaceIdentity, m.workspacePath ?: path).raw
                        ),
                        createdAtMs = m.createdAtMs,
                        updatedAtMs = if (m.updatedAtMs > 0) m.updatedAtMs else m.createdAtMs,
                        workspaceLabel = wsInfo.label,
                    )
                }
            total += tasks.size
            taskRepo.replaceAll(endpointId, WorkspaceIdentityKey(identityKey), tasks, etag = null, atMs = now)
        }
        _workspaces.value = options
        _taskLoadError.value = null
        _lastRefreshAtMs.value = now
        info.version?.let { v -> _endpoint.value = _endpoint.value?.copy(desktopAppVersion = v) }
        log.info("hub.directTasksApplied", "tasks=$total workspaces=${options.size}")
    }

    private suspend fun applyBootstrap(payload: JsonObject?) {
        val parsed = BootstrapParser.parseBootstrap(payload)
        val activeId = activeEndpointIdRaw() ?: vault.loadMetadata()?.endpointId ?: return
        // Round-6 field fix: a RESTARTING desktop answers bootstrap with EMPTY view
        // (tasks=0 workspaces=0 — observed live between DEVICE_OFFLINE and the
        // desktop finishing its reload). Applying it would wipe the cached 482-task
        // projection and flash an empty list; keep the cache instead (the stale
        // label stays honest — the connection state is not Connected then).
        if (parsed.tasks.isEmpty() && parsed.workspaces.isEmpty()) {
            val cached = taskRepo.observeAllTasks(EndpointId(activeId)).first().isNotEmpty()
            if (cached) {
                log.warn("hub.bootstrapEmptySkipped", "desktop view empty (restarting?) — cached projection kept")
                return
            }
        }
        val endpointId = EndpointId(activeId)
        val now = System.currentTimeMillis()
        // Group tasks by §7.11 identity key; each group is one workspace projection.
        val byWorkspace = parsed.tasks.groupBy { it.workspaceIdentityKey }
        // FIELD (2026-09-30, vc13 crash): the desktop cross-lists a session under more
        // than one workspace — dedupe by taskId GLOBALLY (keep the most-recent updatedAt)
        // so the projection shows each task once.
        // FIELD (2026-10-01, vc20 crash on device): the vc13 filter
        // (`seenTaskIds[taskId] == updatedAtMs`) kept BOTH copies when the duplicates
        // carried EQUAL updatedAtMs — two rows with one LazyColumn key →
        // IllegalArgumentException "Key was already used" (device crash log). The dedupe
        // now pins the EXACT kept copy (newest first) and filters each group by identity.
        val keptByTask = LinkedHashMap<String, BootstrapParser.ParsedTask>()
        for (t in parsed.tasks.sortedByDescending { it.updatedAtMs }) {
            keptByTask.putIfAbsent(t.taskId, t)
        }
        // Phase 7 field: keep the workspace menu fresh (labels + §7.11 keys).
        if (parsed.workspaces.isNotEmpty()) {
            _workspaces.value = parsed.workspaces.map { ws ->
                com.zcode.android.domain.model.WorkspaceOption(ws.identityKey, ws.label, ws.path)
            }
        }
        for ((key, group) in byWorkspace) {
            if (key.isBlank()) continue
            val tasks = group.filter { keptByTask[it.taskId] === it }
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
        val endpointKnown = activeEndpointIdRaw() ?: vault.loadMetadata()?.endpointId ?: return null
        return openMutex.withLock {
            val coordinator = ensureCoordinator()
            if (openedSessions.add(task.taskId)) {
                // FIELD (2026-10-06, vc34 device — TASK DUPLICATION round 2): the
                // subscribe carries `workspacePath` and the desktop COLD-RESUMES the
                // session into the given workspace when the pair doesn't match its own
                // tasks-index (transport.ts: "cold resume 优先使用它恢复身份"). We were
                // sending the §7.11 IDENTITY KEY where a PATH belongs — unresolvable
                // for workspaces whose key isn't a path (e.g. the "default"/"Tasks"
                // workspace) → the desktop re-homed the task into the BRIDGE's
                // workspace (the observed default→android-native duplicates) and
                // routed the original session's frames to nobody (the freeze/watchdog
                // loops). Resolve the key to the desktop-reported PATH and send BOTH.
                val ws = _workspaces.value.firstOrNull { it.key == task.workspace.raw }
                val wsPath = ws?.path?.takeIf { it.isNotBlank() } ?: taskWorkspacePath(task)
                coordinator.startTargets(
                    listOf(
                        RecoveryCoordinator.SessionTarget(
                            sessionId = task.taskId,
                            workspacePath = wsPath,
                            workspaceIdentity = task.workspace.raw,
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
            // Phase 2 (§11.2, GAP-12): the workspace catalog rides the session attach —
            // the scoped listen is up (startTargets) and the desktop routes config
            // frames per workspace. Best-effort: a refusal leaves the catalog absent.
            ensureWorkspaceConfigCollector()
            val cfgPath = _workspaces.value.firstOrNull { it.key == task.workspace.raw }?.path
                ?.takeIf { it.isNotBlank() } ?: taskWorkspacePath(task)
            launchWorkspaceConfigSubscribe(cfgPath, task.workspace.raw)
            log.info("hub.conversationOpen", "session=${task.taskId}")
            coordinator
        }
    }

    /** Best-effort workspace path for a task (identity key may BE the path). */
    private fun taskWorkspacePath(task: Task): String? = task.workspace.raw

    /**
     * Create a task in an existing workspace (parity with the web remote, §6.1):
     * verified `createSession` command — first message starts the turn; the new
     * session surfaces via the desktop's `workspace-list-updated` push, with an
     * explicit refresh as the immediate-path backstop.
     */
    suspend fun createTask(
        workspaceKey: String,
        firstText: String,
        provider: String? = null,
        model: String? = null,
    ): Boolean {
        val wsPath = _workspaces.value.firstOrNull { it.key == workspaceKey }?.path ?: workspaceKey
        val coordinator = commandCoordinatorOrNull() ?: return false
        val outcome = coordinator.createSession(wsPath, null, firstText, provider, model)
        val created = outcome is com.zcode.android.data.session.CommandCoordinator.Outcome.Accepted
        log.info(
            "hub.createTask",
            "workspace=${workspaceKey.take(24)} outcome=${outcome.javaClass.simpleName}",
        )
        if (created) refreshTasks()
        return created
    }

    /**
     * Phase 7 field finding (write-path E2E): ensureCoordinator raced ITSELF — the
     * CONNECTED hook's `commandCoordinatorOrNull()?.onConnected()` ran concurrently
     * with `openConversation`'s ensureCoordinator, BOTH created a RecoveryCoordinator,
     * and the field kept the EMPTY one while the session managers lived in the orphaned
     * one (every session projection flow read the empty coordinator: state/queue/
     * interaction surfaces dead). Single-flight under a mutex — §28.5 discipline.
     */
    private val coordinatorMutex = kotlinx.coroutines.sync.Mutex()

    private suspend fun ensureCoordinator(): RecoveryCoordinator = coordinatorMutex.withLock {
        coordinator?.let { return it }
        val endpointId = EndpointId(activeEndpointIdRaw() ?: vault.loadMetadata()?.endpointId ?: "unpaired")
        val caps = activeTransport().capabilities.value
        val created = RecoveryCoordinator(
            endpointId = endpointId,
            gateway = gatewayFor(),
            store = conversationRepo,
            journalRepo = journalRepo,
            interactions = InteractionCoordinatorFor(endpointId),
            transport = activeTransport(),
            notificationRecords = notificationRecords,
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


    /**
     * Phase 7 write path (§43.2): the endpoint-scoped coordinators. Created lazily
     * WITH the recovery coordinator (one instance per endpoint per process) and
     * exposed to the ports — features never see the hub's internals (INV-2/6).
     */
    private var interactionCoordinator: com.zcode.android.data.session.InteractionCoordinator? = null
    private var commandCoordinator: com.zcode.android.data.session.CommandCoordinator? = null

    private fun InteractionCoordinatorFor(endpointId: EndpointId): com.zcode.android.data.session.InteractionCoordinator {
        interactionCoordinator?.let { return it }
        val created = com.zcode.android.data.session.InteractionCoordinator(
            endpointId = endpointId,
            interactions = interactions,
            outbox = outbox,
            commands = gatewayFor(),
            log = log,
        )
        interactionCoordinator = created
        return created
    }

    /** Approvals-inbox / composer submission gate (fail-closed; §20.4). */
    suspend fun interactionCoordinatorOrNull(): com.zcode.android.data.session.InteractionCoordinator? {
        ensureCoordinator()
        return interactionCoordinator
    }

    /** Send-path gate (§17.5): one instance per endpoint, wired to the REAL outbox. */
    suspend fun commandCoordinatorOrNull(): com.zcode.android.data.session.CommandCoordinator? {
        ensureCoordinator()
        if (commandCoordinator == null) {
            commandCoordinator = com.zcode.android.data.session.CommandCoordinator(
                endpointId = EndpointId(activeEndpointIdRaw() ?: vault.loadMetadata()?.endpointId ?: "unpaired"),
                outbox = outbox,
                commands = gatewayFor(),
                connectionState = { activeTransport().states.value },
                ensureConversationListen = { wp, wi -> activeTransport().ensureConversationListen(wp, wi) },
                log = log,
            )
        }
        return commandCoordinator
    }

    fun draftRepository(): com.zcode.android.domain.repo.DraftRepository = drafts

    /**
     * Phase 9 tool plane (§21–§23 + model switch): diff fetch, attachments, git,
     * files, model options. Lazily built over the hub's transport; the ports in
     * this package are its only consumers (INV-2: features never see this).
     */
    private val phase9Tools = java.util.concurrent.ConcurrentHashMap<AppTransport, com.zcode.android.data.session.Phase9Tools>()

    fun phase9(): com.zcode.android.data.session.Phase9Tools =
        phase9Tools.getOrPut(activeTransport()) { com.zcode.android.data.session.Phase9Tools(activeTransport()) }

    /** Phase 3 read-only viewer plane (usage/automations/plugins/skills/mcp; v4-wire.md §12). */
    private val viewerTools = java.util.concurrent.ConcurrentHashMap<AppTransport, com.zcode.android.data.session.ViewerTools>()

    fun viewerTools(): com.zcode.android.data.session.ViewerTools =
        viewerTools.getOrPut(activeTransport()) { com.zcode.android.data.session.ViewerTools(activeTransport()) }

    /** The connected workspace target for git/files (§7.11); null when disconnected. */
    fun activeWorkspaceTarget(): Pair<String?, String?>? = activeTransport().activeWorkspaceTarget

    /** Approvals-inbox observation source (open interactions across tasks). */
    fun interactionsStore(): com.zcode.android.domain.repo.InteractionRepository = interactions

    // ------------------------------------------------------------------ internals

    private fun connect() {
        if (connectJob?.isActive == true) return
        log.info("hub.connect", "cold-start connect begin (kind=${activeKind()})")
        connectJob = scope.launch {
            if (activeKind() == EndpointKind.DIRECT_SERVER) {
                connectDirect()
                return@launch
            }
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
                val err = (e as? TransportFailure)?.error
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

    /**
     * Direct-endpoint connect (§11.4): descriptor from the registry row + candidates;
     * the token rides the vault capability (§12.4) — the hub never sees it.
     */
    private suspend fun connectDirect() {
        val active = _endpoint.value ?: return
        val tokenSource = vault.loadDirectTokenSource(active.endpointId.raw) ?: run {
            _phase.value = ConnectionPhase.FAILED
            _lastError.value = com.zcode.android.core.transport.ErrorMapping.directTokenRejected(null)
            return
        }
        // The direct transport owns its own tokenProvider (capability custody, §26.3);
        // reconnects re-answer it — a token rotation propagates on the next connect.
        directTransport.rebindTokenProvider { tokenSource() }
        val descriptor = EndpointDescriptor(
            id = active.endpointId.raw,
            kind = EndpointKind.DIRECT_SERVER,
            displayName = active.displayName,
            wsUrl = active.wsUrl,
            candidates = active.candidates,
            desktopAppVersion = active.desktopAppVersion,
        )
        try {
            directTransport.connect(descriptor) { _, _, _ -> "" }
            refreshTasks()
            markHealthy(active.endpointId)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val err = (e as? TransportFailure)?.error
            log.warn(
                "hub.directConnectFailed",
                err?.let { "${it.category} ${it.developerDiagnostic.take(140)}" }
                    ?: "${e.javaClass.simpleName}: ${e.message?.take(120)}",
            )
        }
    }

    private suspend fun markHealthy(endpointId: EndpointId) {
        runCatching {
            endpointRepo.endpoints().firstOrNull { it.endpointId == endpointId }?.let {
                endpointRepo.save(it.copy(lastHealthyAtMs = System.currentTimeMillis()))
            }
            refreshRegistry()
        }
    }

    // ------------------------------------------------------------------ §11.6 switcher

    /**
     * Switch the ACTIVE endpoint (§11.6/U22): closes the current connection, resets
     * the per-endpoint coordinators (§28.5 single-owner state), selects the new
     * endpoint, persists the selection, connects. One connection at a time.
     */
    suspend fun switchTo(endpointId: String): Boolean {
        val target = endpointRepo.endpoints().firstOrNull { it.endpointId.raw == endpointId }
        // Relay ids live in the vault metadata, not always the registry row (the relay
        // row is written lazily at first connect — tolerate that pre-persist gap).
        val isRelayTarget = vault.loadMetadata()?.endpointId == endpointId
        if (target == null && !isRelayTarget) return false
        if (_endpoint.value?.endpointId?.raw == endpointId && started) return true
        _switching.value = true
        try {
            runCatching { activeTransport().resetForEndpointSwitch() }
            resetEndpointScopedState()
            val meta = vault.loadMetadata()
            _endpoint.value = when {
                target != null && target.kind == com.zcode.android.domain.model.EndpointKind.DIRECT_SERVER -> target
                meta != null && meta.endpointId == endpointId -> relayEndpointFromMeta(meta)
                target != null -> target
                else -> return false
            }
            _endpointName.value = _endpoint.value?.displayName
            _workspaces.value = emptyList()
            _lastRefreshAtMs.value = null
            _taskLoadError.value = null
            _lastError.value = null
            _directServerInfo.value = null
            preferences.update { it.copy(selectedEndpointId = endpointId) }
            _phase.value = phaseOf(activeTransport().states.value)
            connect()
            refreshRegistry()
            log.info("hub.switched", "endpoint=${endpointId.take(8)}… kind=${activeKind()}")
            return true
        } finally {
            _switching.value = false
        }
    }

    /**
     * Full-unpair bookkeeping (§12.7): the vault wipe destroyed EVERY credential
     * (relay + all direct tokens share the sealed store) — the active endpoint, the
     * registry view and the persisted selection must go with it, or the nav gate
     * would still see a "paired" surrogate after the wipe.
     */
    fun onUnpaired() {
        scope.launch { runCatching { activeTransport().close("unpair") } }
        resetEndpointScopedState()
        _endpoint.value = null
        _endpoints.value = emptyList()
        _workspaces.value = emptyList()
        _directServerInfo.value = null
        _lastRefreshAtMs.value = null
        _phase.value = ConnectionPhase.UNPAIRED
        scope.launch {
            runCatching { preferences.update { it.copy(selectedEndpointId = null) } }
            runCatching { endpointRepo.deleteAll() }
        }
        log.info("hub.unpaired", "active endpoint + registry + selection cleared")
    }

    /** Per-endpoint coordinator state does NOT survive a switch (§28.5 single-owner). */
    private fun resetEndpointScopedState() {
        coordinator = null
        interactionCoordinator = null
        commandCoordinator = null
        openedSessions.clear()
        managersVersion.value++
        lastReportedPhase = null
        // The catalog is endpoint-scoped (§11.2): a switch/unpair drops it.
        configCollectorJob?.cancel()
        configCollectorJob = null
        _workspaceCatalogs.value = emptyMap()
    }

    /**
     * Phase 2 (§11.2): the slash-command/config catalog for a workspace identity
     * key (null until the topic delivers a frame — an honest empty catalog).
     */
    fun workspaceCatalog(workspaceKey: String): kotlinx.coroutines.flow.Flow<com.zcode.android.core.protocol.v4.WorkspaceConfigState?> =
        _workspaceCatalogs.map { it[workspaceKey] }

    /** One collector per gateway instance; re-armed on endpoint lifecycle. */
    private fun ensureWorkspaceConfigCollector() {
        if (configCollectorJob?.isActive == true) return
        val gateway = runCatching { gatewayFor(activeTransport()) }.getOrNull() ?: return
        configCollectorJob = scope.launch {
            gateway.topicEvents.collect { event ->
                if (event is com.zcode.android.data.session.GatewayTopicEvent.WorkspaceConfig) {
                    val state = event.state
                    if (state == null) {
                        log.warn("hub.cfgFault", "${event.reason?.take(80)} (workspace=${event.workspaceKey.take(24)})")
                    } else {
                        _workspaceCatalogs.value = _workspaceCatalogs.value + (event.workspaceKey to state)
                        log.info("hub.cfgCatalog", "workspace=${event.workspaceKey.take(24)} slash=${state.slashCommands.size}")
                    }
                }
            }
        }
    }

    /** Best-effort workspace-config subscribe (§11.2): failure = no catalog, logged. */
    private fun launchWorkspaceConfigSubscribe(wsPath: String?, workspaceIdentity: String?) {
        scope.launch {
            runCatching {
                gatewayFor(activeTransport()).subscribeWorkspaceConfig(wsPath, workspaceIdentity)
            }.onSuccess { ack ->
                log.info("hub.cfgSubscribe", "workspace=${(workspaceIdentity ?: wsPath)?.take(24)} epoch=${ack.logEpoch.take(12)}")
            }.onFailure { e ->
                log.warn("hub.cfgSubscribe", "refused: ${e.message?.take(80)} (catalog stays absent)")
            }
        }
    }

    /** Validate + add a DIRECT endpoint (§12.2 "write nothing until validation passes"). */
    sealed class AddDirectOutcome {
        data class Added(val endpoint: Endpoint) : AddDirectOutcome()
        data class Rejected(val error: AppError) : AddDirectOutcome()
    }

    suspend fun addDirectEndpoint(
        displayName: String,
        wsUrl: String,
        token: String,
        candidates: List<String> = emptyList(),
    ): AddDirectOutcome {
        // Normalized ws URL + http origin for the probe (phase10-direct.md §2).
        val normalized = wsUrl.trim()
        val origin = com.zcode.android.core.transport.DirectTransport.httpOriginOf(normalized)
        return when (val probe = com.zcode.android.core.transport.DirectServerProbe.probe(origin, token, 10_000)) {
            is com.zcode.android.core.transport.ProbeResult.Ok -> {
                val info = probe.info
                val endpointId = EndpointId(java.util.UUID.randomUUID().toString())
                vault.sealDirectToken(
                    token,
                    com.zcode.android.core.security.vault.DirectEndpointMetadata(
                        endpointId = endpointId.raw,
                        displayName = displayName.ifBlank { info.name ?: info.serverId ?: hostOf(normalized) },
                        wsUrl = normalized,
                        serverId = info.serverId,
                        serverVersion = info.version,
                        addedAtMs = System.currentTimeMillis(),
                    ),
                )
                val endpoint = Endpoint(
                    endpointId = endpointId,
                    kind = com.zcode.android.domain.model.EndpointKind.DIRECT_SERVER,
                    displayName = displayName.ifBlank { info.name ?: info.serverId ?: hostOf(normalized) },
                    wsUrl = normalized,
                    pairedAtMs = System.currentTimeMillis(),
                    desktopAppVersion = info.version,
                    candidates = candidates,
                )
                endpointRepo.save(endpoint)
                refreshRegistry()
                log.info("hub.directAdded", "endpoint=${endpointId.raw.take(8)}… workspaces=${info.workspaces.size}")
                AddDirectOutcome.Added(endpoint)
            }
            is com.zcode.android.core.transport.ProbeResult.TokenRejected ->
                AddDirectOutcome.Rejected(com.zcode.android.core.transport.ErrorMapping.directTokenRejected(401))
            is com.zcode.android.core.transport.ProbeResult.LocalNetworkBlocked ->
                AddDirectOutcome.Rejected(com.zcode.android.core.transport.ErrorMapping.localNetworkBlocked(probe.reason))
            is com.zcode.android.core.transport.ProbeResult.Unreachable ->
                AddDirectOutcome.Rejected(
                    AppError(
                        category = com.zcode.android.core.common.error.ErrorCategory.NETWORK,
                        userMessage = "Server unreachable — check the URL and network",
                        developerDiagnostic = "direct add probe unreachable: ${probe.reason.take(120)}",
                        retryable = AppError.Retryable.USER,
                        recoverable = true,
                        autoRecovery = com.zcode.android.core.common.error.RecoveryAction.NONE,
                    ),
                )
        }
    }

    /** Remove a DIRECT endpoint (token + registry row). The active relay endpoint is not removable here. */
    suspend fun removeDirectEndpoint(endpointId: String) {
        vault.deleteDirectToken(endpointId)
        endpointRepo.delete(EndpointId(endpointId))
        if (_endpoint.value?.endpointId?.raw == endpointId) {
            // Removed the ACTIVE direct endpoint → fall back to the relay pairing.
            resetEndpointScopedState()
            runCatching { directTransport.close("endpoint removed") }
            val meta = vault.loadMetadata()
            _endpoint.value = meta?.let { relayEndpointFromMeta(it) }
            preferences.update { it.copy(selectedEndpointId = null) }
            _phase.value = phaseOf(activeTransport().states.value)
            if (meta != null) connect()
        }
        refreshRegistry()
    }

    private fun hostOf(url: String): String = com.zcode.android.core.transport.DirectTransport.hostOf(url)

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
                // FIELD (2026-10-04, vc33 device): a stale lastError from the OLD
                // connection (e.g. bridge-degraded) kept rendering "Sync problem" in
                // the connection center/diagnostics while the machine was CONNECTED —
                // a fresh connection invalidates the old failure (§14.4.1).
                _lastError.value = null
                // Phase 7 §17.5/§28.6 reconnect pass: SAFE_AGGRESSIVE retransmits +
                // TTL sweep (sendText stays user-gated — INV-4).
                runCatching { commandCoordinatorOrNull()?.onConnected() }
                    .onFailure { log.warn("hub.commandRecovery", "${it.javaClass.simpleName}") }
            }
            else -> Unit
        }
    }

    private fun phaseOf(state: ConnectionStateMachine.ConnectionState): ConnectionPhase = connectionPhaseOf(state)
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
