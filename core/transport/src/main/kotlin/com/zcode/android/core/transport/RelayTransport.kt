package com.zcode.android.core.transport

import com.zcode.android.core.common.error.AppError
import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.protocol.bytes.ProtocolException
import com.zcode.android.core.protocol.bytes.RpcSerialization.RpcValue
import com.zcode.android.core.protocol.channel.ChannelSession
import com.zcode.android.core.protocol.relay.RelayFrameCodec
import com.zcode.android.core.protocol.relay.RelayLimits
import com.zcode.android.core.protocol.v4.ClientHello
import com.zcode.android.core.protocol.v4.CommandAck
import com.zcode.android.core.protocol.v4.CommandEnvelope
import com.zcode.android.core.protocol.v4.ConnectionFlowState
import com.zcode.android.core.protocol.v4.Hello
import com.zcode.android.core.protocol.v4.LogicalFrameAssembler
import com.zcode.android.core.protocol.v4.RowsRangeParams
import com.zcode.android.core.protocol.v4.RowsRangeResult
import com.zcode.android.core.protocol.v4.SubscribeAck
import com.zcode.android.core.protocol.v4.TopicFrame
import com.zcode.android.core.protocol.v4.TopicWireFrame
import com.zcode.android.core.protocol.v4.V4Methods
import com.zcode.android.core.protocol.v4.V4Topics
import com.zcode.android.core.protocol.v4.V4WireVersionException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random

/**
 * RelayTransport — production-grade Option A transport (§11.4): JSON-text WS to the
 * relay, terminal-role HMAC auth via the injected signer capability (never secret
 * custody — INV-3/INV-10), data-envelope dispatch, rpc-frame fragmentation with the
 * mandatory ack schedule (Q1, live-verified 2026-09-28), 10 s heartbeat + 30 s
 * watchdog (desktop cadence), bounded queues (INV-5), bridge/Channel/v4 plane with the
 * LIVE-PROVEN call shapes (Phase 1 spike: Promise args wrapped in arrays, workspace-
 * target subscribe), reconnect with full-jitter backoff (§11.5).
 *
 * Recovery (§11.2 recover): re-auth → bridge re-open with the SAME recoveryId (Phase 1
 * live-proven replay path) → re-subscribe; snapshot replacement (§15.5) happens only
 * when the server acks mode=snapshot or the epoch moved.
 */
class RelayTransport(
    private val wsFactory: WsFactory = OkHttpWsFactory(),
    private val log: ZLog,
    private val clock: () -> Long = System::currentTimeMillis,
    random: Random = Random.Default,
    private val config: Config = Config(),
    private val externalScope: CoroutineScope? = null,
    reconnectPolicyOverride: ReconnectPolicy? = null,
) : Transport {

    data class Config(
        val connectTimeoutMs: Long = 10_000,
        val authTimeoutMs: Long = 15_000,
        val bridgeTimeoutMs: Long = 10_000,
        val channelInitTimeoutMs: Long = 10_000,
        val controlRequestTimeoutMs: Long = 10_000,
        val heartbeatIntervalMs: Long = RelayLimits.HEARTBEAT_INTERVAL_MS,
        val heartbeatWatchdogMs: Long = RelayLimits.HEARTBEAT_ACK_WATCHDOG_MS,
        /** Client-side resend policy for unacked rpc-frames (desktop grace observed 45 s). */
        val ackResendMs: Long = 5_000,
        val maxResendsPerMessage: Int = 3,
        val sendQueueCapacity: Int = 1_024,
        val parseBufferCapacity: Int = 1_024,
        val v4CallTimeoutMs: Long = RelayLimits.CONVERSATION_QUERY_TIMEOUT_MS.toLong(),
    )

    private val scope = externalScope ?: CoroutineScope(Dispatchers.IO)
    private val machine = ConnectionStateMachine()
    private val reconnect = reconnectPolicyOverride ?: ReconnectPolicy(clock = clock, random = random)

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val encodeJson = Json { encodeDefaults = true; explicitNulls = false }

    private val _states = MutableStateFlow(machine.state)
    override val states: StateFlow<ConnectionStateMachine.ConnectionState> = _states.asStateFlow()

    private val _capabilities = MutableStateFlow<CapabilitySnapshot?>(null)
    override val capabilities: StateFlow<CapabilitySnapshot?> = _capabilities.asStateFlow()

    private val _flowPressure = MutableStateFlow(ConnectionFlowState.DRAINED)
    override val flowPressure: StateFlow<ConnectionFlowState> = _flowPressure.asStateFlow()

    private val _inbound = MutableSharedFlow<InboundFrame>(extraBufferCapacity = 256)
    override val inbound: SharedFlow<InboundFrame> = _inbound.asSharedFlow()

    private val _appErrors = MutableSharedFlow<AppError>(extraBufferCapacity = 64)
    override val appErrors: SharedFlow<AppError> = _appErrors.asSharedFlow()

    // ------------------------------------------------------------- connection state

    private var endpoint: EndpointDescriptor? = null
    private var signer: CredentialSigner? = null
    private var ws: Ws? = null
    private var channel = ChannelSession()
    private val assembler = RelayFrameCodec.Assembler()
    private val v4Assembler = LogicalFrameAssembler(clock = clock)

    /** Per-direction counters start at EXACTLY 1 (live-verified: gap codes otherwise). */
    private val outSeq = AtomicLong(0)
    private val outMessageSeq = AtomicLong(0)
    @Volatile private var lastDeliveredMessageSeq: Long = 0

    /**
     * Bridge identity. A `var` (Phase 6 field fix): after a bridge-ERROR failure the
     * desktop-side bridge for this id is dead/wedged — re-opening the SAME id gets
     * `desktop-disconnected` forever. Rotated on bridge-error failures; CLEAN socket
     * loss keeps the id (the live-proven recovery replay path, Phase 1 finding).
     */
    private var bridgeSessionId: String = RelayFrameCodec.newBridgeSessionId()
    @Volatile private var bridgeGeneration: Int? = null
    @Volatile private var recoveryId: String? = null

    private var heartbeatJob: Job? = null
    private var ackWatcherJob: Job? = null

    /** Per-connection I/O loops (sender, parser, reader) — cancelled on replacement. */
    @Volatile private var ioJobs: List<Job> = emptyList()
    @Volatile private var kicked: Boolean = false
    @Volatile private var closedByUs: Boolean = false
    @Volatile private var lastHeartbeatAckAt: Long = 0
    @Volatile private var lastPairStatus: String? = null

    private val parseBuffer = Channel<String>(config.parseBufferCapacity)
    private val sendBuffer = Channel<String>(config.sendQueueCapacity)

    /** Unacked outbound logical messages: messageSeq → record (bounded by human rate; INV-5). */
    private val unacked = LinkedHashMap<Long, Unacked>()

    private class Unacked(val fragments: List<String>, var sentAtMs: Long, var resends: Int = 0)

    /** Control-plane request matching: requestId → deferred. */
    private val pendingControl = HashMap<String, CompletableDeferred<JsonObject>>()

    /** Active v4 subscriptions for recover(): topic → record (lastSeq = -1 → no watermark). */
    data class SubscriptionRecord(val subscriptionId: String, val logEpoch: String, val lastSeq: Long)
    private val subscriptions = LinkedHashMap<String, SubscriptionRecord>()

    private val controlRequestCounter = AtomicLong(0)
    /**
     * §28.5 "reconnect/recovery takes a per-endpoint mutex; no double resync" —
     * SINGLE-FLIGHT guard for [recover]. Two loss signals can legitimately race
     * (watchdog + a late close frame from the dead generation); without this, two
     * recover loops race establish()/re-subscribe and corrupt the recovery.
     */
    private val recovering = java.util.concurrent.atomic.AtomicBoolean(false)
    /**
     * Consecutive `desktop-disconnected` bridge rejections (round-4 field finding).
     * Unlike transient session errors, the desktop does NOT come back on its own
     * inside the retry budget — the pairing points at a desktop session the relay
     * no longer holds (stale QR/link, or the desktop's relay connection died).
     * After [DESKTOP_DISCONNECTED_DEGRADED_THRESHOLD] consecutive rejections the
     * transport goes Degraded IMMEDIATELY with actionable copy instead of grinding
     * "Reconnecting…" for the whole 2-minute budget.
     */
    @Volatile private var consecutiveDesktopDisconnected = 0
    private val connectionCounter = AtomicLong(0)
    private val malformedChannelFrames = AtomicLong(0)

    /** INV-8 observability for tests/diagnostics. */
    fun malformedChannelFrameCount(): Long = malformedChannelFrames.get()

    /**
     * TEST-ONLY seam: abrupt socket death (EOF/RST class) without a graceful close.
     * Never call from production paths — it bypasses the send queue and the close
     * handshake by design (that is exactly the failure class it simulates).
     */
    fun abortConnectionForTest() {
        ws?.cancel()
    }

    /**
     * Test/diagnostics seam: feed one raw wire text through the EXACT inbound pipeline
     * (handleMessage → envelope dispatch → rpc-frame decode → channel/topic handlers).
     * Corpus replay (§43.2-2.2) and future diagnostic replay use it; never on the
     * production path.
     */
    internal fun ingestForTest(text: String) {
        try {
            handleMessage(text)
        } catch (e: Exception) {
            scope.launch { _appErrors.emit(ErrorMapping.serializationFault("ingest fault: ${e.javaClass.simpleName}")) }
        }
    }

    /** Test seam: park the machine in CONNECTED for replay/ingest scenarios. */
    internal fun seedForTest() {
        machine.seed(ConnectionStateMachine.ConnectionState.CONNECTED)
        publishState()
    }

    /** INV-5 observability: outbound logical messages awaiting their mandatory ack. */
    fun pendingAckCountForTest(): Int = synchronized(unacked) { unacked.size }
    @Volatile private var authSignal: CompletableDeferred<JsonObject>? = null

    private fun bound() = log.with(
        ZLog.LogContext(endpointId = endpoint?.id, connectionId = "relay-" + connectionCounter.get()),
    )

    // ------------------------------------------------------------------ connect

    override suspend fun connect(endpoint: EndpointDescriptor, signer: CredentialSigner) {
        val s = machine.state
        check(s != ConnectionStateMachine.ConnectionState.CLOSED) { "endpoint identity destroyed" }
        check(
            s == ConnectionStateMachine.ConnectionState.UNPAIRED ||
                s == ConnectionStateMachine.ConnectionState.PAIRING ||
                s == ConnectionStateMachine.ConnectionState.AUTHENTICATING
        ) { "connect() is the first-connection path; use recover() (state=$s)" }
        this.endpoint = endpoint
        this.signer = signer
        closedByUs = false
        kicked = false
        if (s == ConnectionStateMachine.ConnectionState.UNPAIRED ||
            s == ConnectionStateMachine.ConnectionState.PAIRING
        ) {
            // Pairing events (QR validated / credential sealed) are Phase 3 flows; the
            // transport requires a sealed credential and enters the machine at Auth.
            machine.seed(ConnectionStateMachine.ConnectionState.AUTHENTICATING)
        }
        publishState()
        try {
            establish()
        } catch (e: Failure) {
            // §14.4.1: auth-class failures land the machine in FAILED (re-scan path).
            if (e.error.category == com.zcode.android.core.common.error.ErrorCategory.AUTH) {
                safeMachine(ConnectionStateMachine.ConnectionEvent.AUTH_FAILED)
            }
            throw e
        }
    }

    private suspend fun establish() {
        val endpoint = endpoint ?: error("connect() before endpoint")
        val signer = signer ?: error("connect() before signer")
        connectionCounter.incrementAndGet()
        val ctx = bound()
        try {
            // ---- WS connect (§11.5: 10 s connect timeout)
            val wsUrl = buildWsUrl(endpoint)
            ctx.info("relay.connect")
            val socket = wsFactory.create()
            socket.connect(wsUrl, config.connectTimeoutMs)
            ws = socket

            // reader + parser + sender for THIS socket
            startIoLoops(socket)

            // ---- terminal-role auth (§6.2; §11.5 auth timeout 15 s). Signals are
            // registered BEFORE the triggering send so early frames cannot race us.
            val deviceSid = endpoint.deviceSid ?: throw Failure(ErrorMapping.internal("relay endpoint needs deviceSid"))
            val challengeSignal = CompletableDeferred<JsonObject>()
            authSignal = challengeSignal
            sendNow(
                encodeJson.encodeToString(
                    com.zcode.android.core.protocol.relay.AuthInit(
                        role = "terminal",
                        deviceSid = deviceSid,
                        meta = com.zcode.android.core.protocol.relay.AuthMeta(
                            platform = "android",
                            version = endpoint.appVersion,
                            name = endpoint.displayName,
                        ),
                        clientTs = clock(),
                    )
                )
            )
            val challenge = withTimeoutOrNull(config.authTimeoutMs) { challengeSignal.await() }
                ?: throw Failure(ErrorMapping.timeout("auth_challenge", config.authTimeoutMs))
            if (challenge["type"]?.toString()?.contains("error") == true) throw authErrorFrom(challenge)
            val nonce = (challenge["nonce"] as? JsonPrimitive)?.content
                ?: throw Failure(ErrorMapping.protocolFault("auth_challenge without nonce"))
            ctx.info("auth.challenge", "nonce len=${nonce.length}") // INV-3: length only
            val proof = signer.proof(nonce, "terminal", deviceSid)
            val ackSignal = CompletableDeferred<JsonObject>()
            authSignal = ackSignal
            sendNow(
                encodeJson.encodeToString(
                    com.zcode.android.core.protocol.relay.AuthResponse(deviceSid = deviceSid, proof = proof, clientTs = clock())
                )
            )
            val ack = withTimeoutOrNull(config.authTimeoutMs) { ackSignal.await() }
                ?: throw Failure(ErrorMapping.timeout("auth_ack", config.authTimeoutMs))
            if ((ack["type"] as? JsonPrimitive)?.content == "error") throw authErrorFrom(ack)
            val pairStatus = (ack["pair_status"] as? JsonPrimitive)?.content
            if (pairStatus != null && pairStatus != "matched") {
                val deadline = clock() + config.authTimeoutMs
                var matched = false
                while (clock() < deadline) {
                    if (lastPairStatus == "matched") { matched = true; break }
                    delay(50)
                }
                if (!matched) throw Failure(ErrorMapping.relayError("AUTH_FAILED", "pair status stayed $pairStatus"))
            }
            lastHeartbeatAckAt = clock()
            ctx.info("auth.matched")

            when (machine.state) {
                ConnectionStateMachine.ConnectionState.RECONNECTING ->
                    machine.on(ConnectionStateMachine.ConnectionEvent.WS_AUTH_OK) // → RECOVERING
                ConnectionStateMachine.ConnectionState.AUTHENTICATING ->
                    machine.on(ConnectionStateMachine.ConnectionEvent.AUTH_MATCHED) // → CONNECTING
                ConnectionStateMachine.ConnectionState.RECOVERING -> Unit // stays; resync completes it
                else -> throw Failure(ErrorMapping.internal("machine in ${machine.state} at auth ok"))
            }
            publishState()

            // ---- bridge + Channel + v4 hello (first connect: → CONNECTED here; recovery
            // path completes via RESYNC_* in recover() — RECOVERING must not exit via HELLO).
            val bridgeInfo = openBridgeInternal()
            // Field fix (production routing): the discovered workspace is the ROUTING
            // SCOPE for every later v4 call (subscribe/rowsRange/resync/command send
            // {workspacePath, workspaceIdentity?} — live-proven param shape). The
            // endpoint descriptor arrives WITHOUT workspace fields on first pair;
            // without this write-back every subsequent call sends an empty workspace.
            applyDiscoveredWorkspace(bridgeInfo)
            channel = ChannelSession()
            channel.onOutbound = { bytes -> enqueueChannelFrame(bytes) }
            attachChannelEventListeners(bridgeInfo)
            val hello = helloV4()
            initializeV4(endpoint)
            _capabilities.value = CapabilitySnapshot(
                connectionId = hello.connectionId,
                serverTimeMs = hello.serverTime,
                host = hello.capabilities,
                desktopAppVersion = endpoint.desktopAppVersion,
                endpointKind = EndpointKind.RELAY_ZAI,
            )
            if (machine.state == ConnectionStateMachine.ConnectionState.CONNECTING) {
                machine.on(ConnectionStateMachine.ConnectionEvent.HELLO_RECEIVED) // → CONNECTED
            }
            publishState()
            reconnect.reset()
            consecutiveDesktopDisconnected = 0
            lastDeliveredMessageSeq = 0
            outSeq.set(0)
            outMessageSeq.set(0)
            synchronized(unacked) { unacked.clear() }

            heartbeatJob?.cancel()
            heartbeatJob = scope.launch { heartbeatLoop() }
            ackWatcherJob?.cancel()
            ackWatcherJob = scope.launch { ackWatchLoop() }
            ctx.info("relay.connected", "connectionId=${hello.connectionId.take(8)}…")
        } catch (e: Failure) {
            // §14.4.1 honest terminal state on a failed connect — NEVER rest in
            // CONNECTING: auth-class → FAILED (re-scan); retryable classes →
            // RECONNECTING (the recovery loop may retry; budget → Degraded).
            bound().warn("relay.connectFailed", "${e.error.category} ${e.error.developerDiagnostic.take(140)}")
            if (e.error.developerDiagnostic.contains("workspace-bridge-error")) {
                // The desktop refused THIS bridge (dead/wedged) — rotate the identity
                // so the retry opens a FRESH bridge instead of replaying into the
                // corpse forever (field: desktop-disconnected loop).
                bridgeSessionId = RelayFrameCodec.newBridgeSessionId()
                bridgeGeneration = null
                recoveryId = null
                bound().warn("bridge.rotated", "fresh bridgeSessionId for the next attempt")
            }
            if (e.error.category == com.zcode.android.core.common.error.ErrorCategory.AUTH) {
                safeMachine(ConnectionStateMachine.ConnectionEvent.AUTH_FAILED)
            } else if (machine.state in setOf(
                    ConnectionStateMachine.ConnectionState.CONNECTING,
                    ConnectionStateMachine.ConnectionState.AUTHENTICATING,
                    ConnectionStateMachine.ConnectionState.RECOVERING,
                )
            ) {
                safeMachine(ConnectionStateMachine.ConnectionEvent.SOCKET_LOST)
                // "Reconnecting" must mean a loop IS running (§14.4.1): self-heal here
                // so EVERY caller (hub cold start, pairing retry) gets bounded retry →
                // budget → Degraded instead of a stranded mid-state.
                scope.launch { runCatching { recover() } }
            }
            publishState()
            throw e
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw Failure(ErrorMapping.timeout("connect", config.connectTimeoutMs))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw Failure(transportLoss("connect failed: ${e.javaClass.simpleName}"))
        }
    }

    private fun authErrorFrom(frame: JsonObject): Failure {
        val code = (frame["code"] as? JsonPrimitive)?.content ?: "AUTH_FAILED"
        return Failure(ErrorMapping.relayError(code, null))
    }

    // ------------------------------------------------------------------ recovery

    override suspend fun recover() {
        if (closedByUs) return
        if (!recovering.compareAndSet(false, true)) return // single-flight (§28.5)
        try {
            recoverLocked()
        } finally {
            recovering.set(false)
        }
    }

    private suspend fun recoverLocked() {
        ws?.let { runCatching { it.close(1000, "reconnect") } }
        while (!closedByUs && !kicked) {
            if (machine.state == ConnectionStateMachine.ConnectionState.CONNECTED) return
            // Round-6 field fix: Degraded/FAILED/CLOSED mean USER ACTION REQUIRED —
            // the loop must go QUIET. Every extra attempt is a full relay handshake
            // that re-poisons the desktop's relay session (#328 class); the field
            // showed this storm freezing the macOS app until it was restarted.
            if (machine.state == ConnectionStateMachine.ConnectionState.DEGRADED ||
                machine.state == ConnectionStateMachine.ConnectionState.FAILED ||
                machine.state == ConnectionStateMachine.ConnectionState.CLOSED
            ) {
                bound().warn("relay.recovery.quiesced", "machine=${machine.state} — user action required")
                return
            }
            val delayMs = reconnect.nextDelayMs()
            if (delayMs == null) {
                // Attempt budget exhausted → Degraded (§14.4.1 table-verbatim path).
                safeMachine(ConnectionStateMachine.ConnectionEvent.RECONNECT_BUDGET_EXHAUSTED)
                _appErrors.emit(ErrorMapping.degradedBudget("reconnect budget exhausted"))
                return
            }
            if (delayMs > 0) delay(delayMs)
            try {
                ensureReconnectingState()
                establish()
                // Re-subscribe (the Phase-1 live-proven recovery path: bridge re-open with
                // the same recoveryId replays missed frames; subscribe re-anchors topics).
                var replaced = false
                for ((topic, rec) in subscriptions.toList()) {
                    val session = topic.removePrefix(V4Topics.CONVERSATION_PREFIX)
                    val ack = subscribeSession(session, visibility = null)
                    // mode=snapshot (or a different epoch) means the projection must be
                    // atomically replaced (§15.5) — flagged for the recovery outcome.
                    if (ack.mode == "snapshot" || ack.logEpoch != rec.logEpoch) replaced = true
                }
                if (machine.state == ConnectionStateMachine.ConnectionState.RECOVERING) {
                    machine.on(
                        if (replaced) ConnectionStateMachine.ConnectionEvent.RESYNC_SNAPSHOT_REPLACED
                        else ConnectionStateMachine.ConnectionEvent.RESYNC_GAP_FREE
                    )
                    publishState()
                }
                bound().info("relay.recovered", "subscriptions=${subscriptions.size} snapshotReplace=$replaced")
                return
            } catch (e: Failure) {
                // Round-4 field finding: repeated desktop-disconnected (a TRANSPORT-
                // class bridge rejection — NOT the SESSION branch below) is not
                // transient: the relay holds no live desktop for this pairing and it
                // does not return inside the budget. Fast-degrade with copy instead
                // of grinding "Reconnecting…" for the full 2 minutes.
                if (e.error.developerDiagnostic.contains("desktop-disconnected")) {
                    consecutiveDesktopDisconnected++
                    if (consecutiveDesktopDisconnected >= DESKTOP_DISCONNECTED_DEGRADED_THRESHOLD) {
                        safeMachine(ConnectionStateMachine.ConnectionEvent.RECONNECT_BUDGET_EXHAUSTED)
                        _appErrors.emit(ErrorMapping.bridgeError("desktop-disconnected"))
                        bound().warn(
                            "relay.desktopGone",
                            "consecutive=$consecutiveDesktopDisconnected → Degraded (stale pairing / desktop off)",
                        )
                        return
                    }
                    // Round-6 pacing (feedback #328 class): a bridge rejection leaves
                    // the desktop's relay session poisoned — retrying it at the
                    // normal 1 s backoff cadence re-wedges the desktop before it can
                    // recover. Floor every post-rejection retry at a calm interval.
                    delay(BRIDGE_REJECT_FLOOR_MS)
                }
                if (e.error.category == com.zcode.android.core.common.error.ErrorCategory.AUTH) {
                    safeMachine(ConnectionStateMachine.ConnectionEvent.AUTH_FAILED)
                    _appErrors.emit(e.error)
                    return // re-pair required; never loop auth failures (§12.6)
                }
                if (e.error.category == com.zcode.android.core.common.error.ErrorCategory.SESSION) {
                    safeMachine(ConnectionStateMachine.ConnectionEvent.SOCKET_LOST)
                    _appErrors.emit(e.error)
                    // §15.3: ONLY the takeover class (session-conflict / KICKED — the
                    // autoRecovery=RECONNECT marker) ends the loop for a user decision.
                    // TRANSIENT session errors (session-not-found while the desktop
                    // re-hydrates its workspace, workspace-closed during a restart)
                    // must KEEP RETRYING within the budget — returning here stranded
                    // the machine in RECONNECTING forever (Phase 6 field finding).
                    if (e.error.autoRecovery == com.zcode.android.core.common.error.RecoveryAction.RECONNECT) {
                        return
                    }
                    bound().warn("relay.reconnectFailed", e.error.developerDiagnostic)
                    continue
                }
                bound().warn("relay.reconnectFailed", e.error.developerDiagnostic)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                bound().warn("relay.reconnectFailed", "transport-class: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun ensureReconnectingState() {
        when (machine.state) {
            ConnectionStateMachine.ConnectionState.CONNECTED,
            ConnectionStateMachine.ConnectionState.CONNECTING,
            ConnectionStateMachine.ConnectionState.AUTHENTICATING,
            ConnectionStateMachine.ConnectionState.RECOVERING,
            -> safeMachine(ConnectionStateMachine.ConnectionEvent.SOCKET_LOST)
            else -> Unit
        }
    }

    /**
     * Socket-loss entry point (reader/watchdog/protocol/close-frame faults).
     *
     * SINGLE-FLIGHT (§28.5): multiple loss signals can race for one dead generation
     * (watchdog + late close frame, ack-grace + reader error). The FIRST one wins
     * and owns the whole recovery cycle; later signals for the same generation are
     * suppressed — their teardown (channel close, send-queue drain) would otherwise
     * sabotage the in-flight recovery.
     */
    private fun onConnectionLost(reason: String) {
        if (closedByUs) return
        if (!recovering.compareAndSet(false, true)) {
            bound().warn("relay.lost.suppressed", "recovery already in flight ($reason)")
            return
        }
        bound().warn("relay.lost", reason)
        channel.close(reason)
        heartbeatJob?.cancel()
        ackWatcherJob?.cancel()
        // Drain unsent frames — they were never transmitted; higher layers own retries.
        while (sendBuffer.tryReceive().isSuccess) { /* drain */ }
        var transitioned = false
        when (machine.state) {
            ConnectionStateMachine.ConnectionState.CONNECTED,
            ConnectionStateMachine.ConnectionState.CONNECTING,
            ConnectionStateMachine.ConnectionState.AUTHENTICATING,
            ConnectionStateMachine.ConnectionState.RECOVERING,
            -> {
                runCatching { machine.on(ConnectionStateMachine.ConnectionEvent.SOCKET_LOST) }
                    .onFailure { bound().warn("state.anomaly", it.message?.take(80)) }
                transitioned = true
            }
            else -> Unit // RECONNECTING/DEGRADED/FAILED: already owned by the retry machinery
        }
        publishState()
        // Round-6 field fix: relaunch the recovery loop ONLY when this loss actually
        // moved the machine to RECONNECTING. A loss while DEGRADED (e.g. the relay's
        // DEVICE_OFFLINE push after a poisoned bridge) used to relaunch the loop,
        // which then ran FULL auth+bootstrap+bridge handshakes in Degraded forever —
        // a ~1/s connect storm that wedges the desktop's own relay session (feedback
        // #328 class; observed freezing the macOS app on every phone retry).
        if (!transitioned) {
            recovering.set(false)
            return
        }
        scope.launch {
            try {
                recoverLocked()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                bound().warn("recover.fault", "${e.javaClass.simpleName}: ${e.message?.take(80)}")
            } finally {
                recovering.set(false)
            }
        }
    }

    private fun onKicked() {
        kicked = true
        bound().warn("relay.kicked", "another terminal took over (§15.3)")
        ws?.let { runCatching { it.close(1000, "kicked") } }
        heartbeatJob?.cancel()
        ackWatcherJob?.cancel()
        // Table-verbatim: live states → Reconnecting, then budget-exhausted → Degraded;
        // reconnectNow() is the user's "take over back" action.
        safeMachine(ConnectionStateMachine.ConnectionEvent.SOCKET_LOST)
        safeMachine(ConnectionStateMachine.ConnectionEvent.RECONNECT_BUDGET_EXHAUSTED)
        scope.launch { _appErrors.emit(ErrorMapping.relayError("KICKED", null)) }
    }

    override fun reconnectNow() {
        kicked = false
        if (machine.state == ConnectionStateMachine.ConnectionState.DEGRADED) {
            // §14.4.1 Degraded → (manual retry / network restored) → Reconnecting: the
            // attempt budget bounds AUTOMATIC retrying; an explicit user action opens a
            // fresh budget (otherwise the retry could never leave Degraded — §11.5
            // "attempt budget then degraded state (user action available)").
            reconnect.reset()
            runCatching { machine.on(ConnectionStateMachine.ConnectionEvent.RETRY_REQUESTED) }
            publishState()
        }
        reconnect.requestImmediate()
        if (machine.state == ConnectionStateMachine.ConnectionState.RECONNECTING) {
            scope.launch { runCatching { recover() } }
        }
    }

    override fun notifyNetworkAvailable() {
        reconnect.requestImmediate()
        if (machine.state == ConnectionStateMachine.ConnectionState.DEGRADED) reconnectNow()
    }

    // ------------------------------------------------------------------ heartbeat

    private suspend fun heartbeatLoop() {
        val endpoint = endpoint ?: return
        val deviceSid = endpoint.deviceSid ?: return
        while (currentCoroutineContext().isActive && !closedByUs && !kicked) {
            delay(config.heartbeatIntervalMs)
            if (closedByUs || kicked) return
            if (clock() - lastHeartbeatAckAt > config.heartbeatWatchdogMs) {
                onConnectionLost("heartbeat watchdog (no ack for ${config.heartbeatWatchdogMs}ms)")
                return
            }
            bound().debug("heartbeat.send", "pair_status_query")
            sendNow(
                encodeJson.encodeToString(
                    com.zcode.android.core.protocol.relay.PairStatusQuery(deviceSid = deviceSid, clientTs = clock())
                )
            )
        }
    }

    // ------------------------------------------------------------------ sending

    override suspend fun send(frame: OutboundFrame) {
        when (frame) {
            is OutboundFrame.Channel -> enqueueChannelFrame(frame.bytes)
            is OutboundFrame.RelayControl -> queueText(frame.json)
        }
    }

    /**
     * Channel-frame enqueue — NON-suspending (ChannelSession invokes onOutbound
     * synchronously inside caller coroutines): fragment, register for acks, trySend in
     * order. Overflow surfaces an error (INV-5) instead of blocking the caller.
     */
    private fun enqueueChannelFrame(bytes: ByteArray) {
        val seq = outSeq.addAndGet(1)
        val messageSeq = outMessageSeq.addAndGet(1)
        val fragments = RelayFrameCodec.fragment(
            bridgeSessionId, bridgeGeneration, recoveryId, bytes, seq, messageSeq,
        )
        val texts = fragments.map { it.envelopeJson }
        synchronized(unacked) { unacked[messageSeq] = Unacked(texts, clock()) }
        var saturated = false
        for (t in texts) if (!sendBuffer.trySend(t).isSuccess) saturated = true
        if (saturated) scope.launch {
            _appErrors.emit(ErrorMapping.protocolFault("send queue saturated (INV-5 bound ${config.sendQueueCapacity})"))
        }
        bound().debug("rpc.send", "messageSeq=$messageSeq fragments=${texts.size} bytes=${bytes.size}")
    }

    private suspend fun queueText(text: String) {
        // Single ordered path into the socket (§10.2). Bounded: overflow means a wedged
        // socket — surface an error rather than grow (INV-5).
        val sent = withTimeoutOrNull(5_000) { sendBuffer.send(text) }
        if (sent == null) {
            _appErrors.emit(ErrorMapping.protocolFault("send queue saturated (INV-5 bound ${config.sendQueueCapacity})"))
        }
    }

    private fun sendNow(text: String) {
        ws?.sendText(text)
    }

    private suspend fun senderLoop(socket: Ws) {
        for (text in sendBuffer) {
            if (closedByUs) return
            if (!socket.sendText(text)) {
                onConnectionLost("send failed")
                return
            }
        }
    }

    private suspend fun ackWatchLoop() {
        while (currentCoroutineContext().isActive && !closedByUs && !kicked) {
            delay(config.ackResendMs)
            val now = clock()
            val resends: List<String> = synchronized(unacked) {
                val out = ArrayList<String>()
                val it = unacked.entries.iterator()
                while (it.hasNext()) {
                    val (messageSeq, un) = it.next()
                    if (now - un.sentAtMs >= config.ackResendMs) {
                        if (un.resends >= config.maxResendsPerMessage) {
                            it.remove()
                            scope.launch {
                                _appErrors.emit(
                                    ErrorMapping.protocolFault(
                                        "rpc messageSeq=$messageSeq unacked after ${un.resends} resends (replayGraceExceeded class)"
                                    )
                                )
                            }
                            onConnectionLost("ack grace exceeded")
                            return
                        }
                        un.resends += 1
                        un.sentAtMs = now
                        out.addAll(un.fragments)
                    }
                }
                out
            }
            for (text in resends) sendNow(text)
        }
    }

    // ------------------------------------------------------------------ reading

    private fun startIoLoops(socket: Ws) {
        // Cancel the PREVIOUS connection's loops: late frames from a dead generation
        // (e.g. a KICKED delivered on the half-closed old socket) must never drive the
        // transport state of the new generation.
        val old = ioJobs
        ioJobs = listOf(
            scope.launch { senderLoop(socket) },
            scope.launch {
                try {
                    for (text in parseBuffer) {
                        try {
                            handleMessage(text)
                        } catch (e: CancellationException) {
                            // closing
                        } catch (e: Exception) {
                            // INV-8: a fault in one message never kills the ordered parser
                            scope.launch { _appErrors.emit(ErrorMapping.serializationFault("handler fault: ${e.javaClass.simpleName}")) }
                        }
                    }
                } catch (e: CancellationException) {
                    // closing
                }
            },
            scope.launch {
                try {
                    socket.incoming.collect { text ->
                        // INV-5: bounded parse channel — on overflow drop + count + resync
                        // (correctness over completeness, §28.4).
                        if (!parseBuffer.trySend(text).isSuccess) {
                            _appErrors.emit(ErrorMapping.protocolFault("parse buffer overflow → resync (INV-5)"))
                            onConnectionLost("parse buffer overflow")
                        }
                    }
                } catch (e: CancellationException) {
                    // closing
                } catch (e: Exception) {
                    onConnectionLost("reader error: ${e.javaClass.simpleName}")
                }
            },
            // Server-initiated CLOSE detection (Phase 6 regression fix): a clean close
            // frame completes `incoming` silently, so without this collector the ONLY
            // loss signal was the 30 s heartbeat watchdog — relay close codes (4009
            // takeover, 4010 desktop-gone, 4011/4013 auth-expired) would surface a
            // full watchdog period late. §15.3 + §33 require immediate classification.
            scope.launch {
                val generation = socket
                socket.closed.collect { closed ->
                    // Late close events from a SUPERSEDED socket must never drive the
                    // transport state of the current generation (§ startIoLoops note).
                    if (ws !== generation) return@collect
                    if (closedByUs) return@collect
                    when {
                        !closed.expected -> onConnectionLost("abnormal close ${closed.code}: ${closed.reason.take(60)}")
                        closed.code == 4009 -> onKicked()
                        closed.code == 4011 || closed.code == 4013 -> {
                            scope.launch { _appErrors.emit(ErrorMapping.closeCode(closed.code)) }
                            onConnectionLost("auth-class close ${closed.code} (§12.6 re-scan)")
                        }
                        else -> onConnectionLost("server close ${closed.code}")
                    }
                }
            },
        )
        for (j in old) j.cancel()
    }

    private fun handleMessage(text: String) {
        val obj = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            scope.launch { _appErrors.emit(ErrorMapping.serializationFault("non-JSON relay message dropped")) }
            return
        }
        when ((obj["type"] as? JsonPrimitive)?.content) {
            "auth_challenge" -> authSignal?.complete(obj)
            "auth_ack" -> {
                lastHeartbeatAckAt = clock()
                (obj["pair_status"] as? JsonPrimitive)?.content?.let { lastPairStatus = it }
                authSignal?.complete(obj)
            }
            "pair_status_ack" -> {
                lastHeartbeatAckAt = clock()
                (obj["pair_status"] as? JsonPrimitive)?.content?.let { lastPairStatus = it }
            }
            "error" -> {
                val code = (obj["code"] as? JsonPrimitive)?.content ?: "UNKNOWN"
                bound().warn("relay.error", "code=$code")
                when (code) {
                    "KICKED" -> onKicked()
                    "AUTH_FAILED" -> {
                        val signal = authSignal
                        if (signal != null) {
                            signal.complete(obj) // handshake surfaces AUTH
                        } else {
                            // No handshake pending (replay/diagnostics context): surface it.
                            scope.launch { _appErrors.emit(ErrorMapping.relayError(code, null)) }
                        }
                    }
                    else -> scope.launch { _appErrors.emit(ErrorMapping.relayError(code, null)) }
                }
            }
            "data" -> handleDataEnvelope(obj)
            else -> {
                // INV-8: unknown top-level types counted (via diagnostics), never crash.
                scope.launch { _appErrors.emit(ErrorMapping.serializationFault("unknown relay frame type (counted)")) }
            }
        }
    }

    private fun handleDataEnvelope(envelope: JsonObject) {
        val results = try {
            assembler.handleEnvelope(envelope.toString())
        } catch (e: ProtocolException) {
            scope.launch { _appErrors.emit(ErrorMapping.protocolFault(e.message ?: "rpc-frame fault")) }
            onConnectionLost("protocol fault: ${e.message?.take(80)}")
            return
        }
        for (r in results) {
            when (r) {
                is RelayFrameCodec.Reassembled.Message -> {
                    if (r.messageSeq <= lastDeliveredMessageSeq) continue // peer resend: idempotent drop
                    lastDeliveredMessageSeq = r.messageSeq
                    sendNow(RelayFrameCodec.encodeAck(bridgeSessionId, bridgeGeneration, recoveryId, r.messageSeq))
                    try {
                        channel.onFrame(r.payload)
                    } catch (e: ProtocolException) {
                        // INV-8: geometry sound, payload not a decodable Channel frame —
                        // count + surface, never crash (corpus-replay path hits this by design).
                        malformedChannelFrames.incrementAndGet()
                        scope.launch { _appErrors.emit(ErrorMapping.serializationFault("channel payload undecodable (counted): ${e.message?.take(60)}")) }
                    }
                }
                is RelayFrameCodec.Reassembled.Ack -> {
                    synchronized(unacked) { unacked.remove(r.ackMessageSeq) }
                }
                is RelayFrameCodec.Reassembled.Control -> handleControlPayload(r.zcodeType, r.payload)
            }
        }
    }

    private fun handleControlPayload(zcodeType: String?, payload: JsonObject) {
        val type = zcodeType ?: return
        when (type) {
            "bootstrap-response", "workspace-list-response", "workspace-bridge-ready",
            "workspace-bridge-error", "workspace-reconnect-response",
            -> {
                val requestId = (payload["requestId"] as? JsonPrimitive)?.content
                if (requestId != null) {
                    val deferred = synchronized(pendingControl) { pendingControl.remove(requestId) }
                    deferred?.complete(payload)
                }
                scope.launch { emitInbound(InboundFrame.ControlPayload(type, payload)) }
            }
            "bridge-degraded" -> {
                bound().warn("bridge.degraded", (payload["reason"] as? JsonPrimitive)?.content ?: "")
                scope.launch { _appErrors.emit(ErrorMapping.protocolFault("bridge-degraded")) }
                onConnectionLost("bridge-degraded")
            }
            else -> scope.launch { emitInbound(InboundFrame.ControlPayload(type, payload)) }
        }
    }

    private suspend fun emitInbound(frame: InboundFrame) {
        if (!_inbound.tryEmit(frame)) {
            _appErrors.emit(ErrorMapping.protocolFault("inbound flow overflow → resync (INV-5)"))
            onConnectionLost("inbound overflow")
        }
    }

    // ------------------------------------------------------------------ channel / v4

    private fun attachChannelEventListeners(bridge: BridgeInfo) {
        // FIELD FIX (round 3): the EventListen ARG is the WORKSPACE SCOPE that routes
        // the zcode-agent channel on production ({workspacePath, workspaceIdentity?} —
        // the spike-proven shape). Without it the desktop host attaches the channel
        // to no workspace and every conversation-plane call (helloConversationV4 …)
        // goes UNANSWERED — the real-phone 10 s timeout. The lenient mock ignored
        // the arg, which is why every suite passed while production hung.
        val scopeJson = buildJsonObject {
            put("workspacePath", JsonPrimitive(bridge.workspacePath))
            bridge.workspaceIdentity?.let { put("workspaceIdentity", JsonPrimitive(it)) }
        }
        val scopeArg = RpcValue.Obj(scopeJson)
        // Downstream topic frames (workspace-level, live-proven SPA shape).
        channel.listen("zcode-agent", "onDynamicConversationFrame", scopeArg) { args ->
            val obj = (args as? RpcValue.Obj)?.json as? JsonObject ?: return@listen
            // SPA live shape: the event payload wraps the routed wire frame in `frame`
            // (Phase 1 spike decoded data["frame"]) — tolerate both wrapped and raw.
            val wireObj = (obj["frame"] as? JsonObject) ?: obj
            handleTopicWireFrame(wireObj)
        }
        // v4/connection/flow (saturated/drained/closed, §28.4). REQUIRES VALIDATION on
        // the production relay — MockZCode emits it; absent signal is a safe no-op.
        channel.listen("zcode-agent", V4Methods.CONNECTION_FLOW, scopeArg) { args ->
            val obj = (args as? RpcValue.Obj)?.json as? JsonObject ?: return@listen
            val connectionId = (obj["connectionId"] as? JsonPrimitive)?.content ?: ""
            _flowPressure.value = when ((obj["state"] as? JsonPrimitive)?.content) {
                "saturated" -> ConnectionFlowState.SATURATED
                "closed" -> ConnectionFlowState.CLOSED
                else -> ConnectionFlowState.DRAINED
            }
            scope.launch { emitInbound(InboundFrame.FlowControl(_flowPressure.value, connectionId)) }
        }
    }

    private fun handleTopicWireFrame(obj: JsonObject) {
        val wire = try {
            TopicWireFrame.decode(obj)
        } catch (e: V4WireVersionException) {
            scope.launch { _appErrors.emit(ErrorMapping.versionIncompatible(e.message ?: "wire version")) }
            onConnectionLost("wire version mismatch")
            return
        } catch (e: ProtocolException) {
            scope.launch { _appErrors.emit(ErrorMapping.protocolFault(e.message ?: "topic frame fault")) }
            onConnectionLost("topic frame fault: ${e.message?.take(80)}")
            return
        }
        val assembled = try {
            v4Assembler.accept(wire)
        } catch (e: ProtocolException) {
            scope.launch { _appErrors.emit(ErrorMapping.protocolFault(e.message ?: "assembly fault")) }
            onConnectionLost("assembly fault: ${e.message?.take(80)}")
            return
        } ?: return
        val frame = try {
            TopicFrame.decode(assembled.frame)
        } catch (e: ProtocolException) {
            // Wire sound, payload shape unknown → degrade (INV-8), never merge.
            scope.launch { _appErrors.emit(ErrorMapping.serializationFault(e.message ?: "topic frame decode")) }
            return
        }
        subscriptions[frame.topic]?.let { rec ->
            subscriptions[frame.topic] = rec.copy(lastSeq = frame.toSeq)
        }
        scope.launch {
            emitInbound(
                InboundFrame.TopicFrameArrived(
                    topic = frame.topic,
                    subscriptionId = frame.subscriptionId,
                    deliveryKind = assembled.deliveryKind,
                    fromSeq = frame.fromSeq,
                    toSeq = frame.toSeq,
                    frame = frame,
                )
            )
        }
    }

    private suspend fun channelReady() {
        try {
            channel.awaitInitialize(config.channelInitTimeoutMs)
        } catch (e: ChannelSession.Failure.TimedOut) {
            throw Failure(ErrorMapping.timeout("channel initialize", config.channelInitTimeoutMs))
        } catch (e: ChannelSession.Failure) {
            throw Failure(ErrorMapping.protocolFault(e.message ?: "channel failure"))
        }
    }

    /**
     * Channel call with the LIVE-PROVEN ProxyChannel convention (Phase 1 finding 5):
     * Promise-call args are wrapped in an ARRAY; a bare object arrives as undefined.
     */
    private suspend fun v4Call(name: String, arg: RpcValue): RpcValue {
        val started = clock()
        bound().debug("v4.call", "$name begin")
        return try {
            val raw = channel.call("zcode-agent", name, RpcValue.Arr(listOf(arg)), config.v4CallTimeoutMs)
            bound().debug("v4.call", "$name ok in ${clock() - started}ms")
            raw
        } catch (e: ChannelSession.Failure.TimedOut) {
            bound().warn("v4.call", "$name TIMEOUT after ${clock() - started}ms")
            throw Failure(ErrorMapping.timeout(name, config.v4CallTimeoutMs))
        } catch (e: ChannelSession.Failure.ChannelError) {
            // Server-side fault (fault.* namespace) — task-class rejection, user-retry.
            bound().warn("v4.call", "$name channel error: ${e.detail.take(100)}")
            throw Failure(ErrorMapping.taskRejected(e.detail))
        } catch (e: ChannelSession.Failure) {
            bound().warn("v4.call", "$name failed: ${e.message?.take(100)}")
            throw Failure(ErrorMapping.protocolFault(e.message ?: "channel call failed"))
        }
    }

    private suspend fun helloV4(): Hello {
        channelReady()
        bound().debug("v4.call", "helloConversationV4 begin")
        val raw = try {
            channel.call("zcode-agent", "helloConversationV4", RpcValue.Arr(emptyList()), config.v4CallTimeoutMs)
        } catch (e: ChannelSession.Failure.TimedOut) {
            throw Failure(ErrorMapping.timeout("helloConversationV4", config.v4CallTimeoutMs))
        } catch (e: ChannelSession.Failure) {
            throw Failure(ErrorMapping.protocolFault(e.message ?: "hello failed"))
        }
        val obj = (raw as? RpcValue.Obj)?.json as? JsonObject
            ?: throw Failure(ErrorMapping.protocolFault("hello response not an object"))
        return try {
            Hello.decode(obj)
        } catch (e: V4WireVersionException) {
            throw Failure(ErrorMapping.versionIncompatible(e.message ?: "wire version"))
        } catch (e: ProtocolException) {
            throw Failure(ErrorMapping.protocolFault(e.message ?: "hello decode"))
        }
    }

    private suspend fun initializeV4(endpoint: EndpointDescriptor) {
        val clientHello = ClientHello(
            clientId = endpoint.id,
            clientKind = endpoint.clientKind,
            appVersion = endpoint.appVersion,
        )
        v4Call("initializeConversationV4", RpcValue.Obj(clientHello.encode()))
    }

    // ------------------------------------------------------------------ v4 surface (live-proven shapes)

    /**
     * Subscribe to `conversation/<sessionId>`. Phone-facing shape (Phase 1 live-proven):
     * {workspacePath, workspaceIdentity?, sessionId, visibility}.
     *
     * Phase 6: an opened task may live outside the endpoint-pinned (active) workspace,
     * so the workspace target can be supplied per call — the verified wire shape
     * carries the workspace on EVERY v4 call (docs/protocol/v4-wire.md §4). Null
     * falls back to the descriptor-pinned workspace exactly as before (the recovery
     * coordinator's re-anchor path is unchanged).
     */
    suspend fun subscribeSession(
        sessionId: String,
        visibility: String? = "foreground",
        workspacePath: String? = null,
        workspaceIdentity: String? = null,
    ): SubscribeAck {
        val endpoint = endpoint ?: throw Failure(ErrorMapping.internal("no endpoint"))
        val params = buildJsonObject {
            put("workspacePath", JsonPrimitive(workspacePath ?: endpoint.workspacePath ?: ""))
            (workspaceIdentity ?: endpoint.workspaceIdentity)?.let { put("workspaceIdentity", JsonPrimitive(it)) }
            put("sessionId", JsonPrimitive(sessionId))
            visibility?.let { put("visibility", JsonPrimitive(it)) }
        }
        val raw = v4Call("subscribeConversationV4", RpcValue.Obj(params))
        val obj = (raw as? RpcValue.Obj)?.json as? JsonObject
            ?: throw Failure(ErrorMapping.protocolFault("subscribe result not an object"))
        val ackObj = obj["ack"] as? JsonObject
            ?: throw Failure(ErrorMapping.protocolFault("subscribe ack missing"))
        val ack = SubscribeAck.decode(ackObj)
        subscriptions[V4Topics.conversation(sessionId)] = SubscriptionRecord(ack.subscriptionId, ack.logEpoch, -1)
        return ack
    }

    /** rowsRange (live-proven shape); limit hard-bounded ≤ 200 (INV-13). */
    suspend fun rowsRange(sessionId: String, beforeRowId: Long?, limit: Int): RowsRangeResult {
        val endpoint = endpoint ?: throw Failure(ErrorMapping.internal("no endpoint"))
        bound().debug("v4.call", "rowsRange session=$sessionId before=$beforeRowId limit=$limit")
        val params = buildJsonObject {
            put("workspacePath", JsonPrimitive(endpoint.workspacePath ?: ""))
            endpoint.workspaceIdentity?.let { put("workspaceIdentity", JsonPrimitive(it)) }
            put("sessionId", JsonPrimitive(sessionId))
            beforeRowId?.let { put("beforeRowId", JsonPrimitive(it)) }
            put("limit", JsonPrimitive(limit))
        }
        val raw = v4Call("conversationRowsRangeV4", RpcValue.Obj(params))
        val obj = (raw as? RpcValue.Obj)?.json as? JsonObject
            ?: throw Failure(ErrorMapping.protocolFault("rowsRange result not an object"))
        return RowsRangeResult.decode(obj)
    }

    suspend fun rowsRange(params: RowsRangeParams): RowsRangeResult =
        rowsRange(params.sessionId, params.beforeRowId, params.limit)

    /**
     * v4/conversation/resync — the Phase 5.3 recovery escalation (§15.1/S8/S10):
     * `base` resumes from a watermark, `base=null`/`forceSnapshot=true` forces a
     * snapshot. Workspace-target param form, mirroring the live-proven subscribe
     * shape (the trusted host injects topic/connectionId — docs/protocol/v4-wire.md
     * §4). The exact phone-facing resync shape on the PRODUCTION relay is
     * `[REQUIRES VALIDATION]` (verified schema shape carried; MockZCode implements
     * both forms) — live validation rides the Phase-12 manual canary.
     */
    suspend fun resyncSession(
        sessionId: String,
        base: Pair<String, Long>?,
        forceSnapshot: Boolean = false,
        workspacePath: String? = null,
        workspaceIdentity: String? = null,
    ): SubscribeAck {
        val endpoint = endpoint ?: throw Failure(ErrorMapping.internal("no endpoint"))
        val params = buildJsonObject {
            put("workspacePath", JsonPrimitive(workspacePath ?: endpoint.workspacePath ?: ""))
            (workspaceIdentity ?: endpoint.workspaceIdentity)?.let { put("workspaceIdentity", JsonPrimitive(it)) }
            put("sessionId", JsonPrimitive(sessionId))
            if (base == null) put("base", JsonNull) else put("base", buildJsonObject {
                put("logEpoch", JsonPrimitive(base.first))
                put("seq", JsonPrimitive(base.second))
            })
            if (forceSnapshot) put("forceSnapshot", JsonPrimitive(true))
        }
        val raw = v4Call("resyncConversationV4", RpcValue.Obj(params))
        val obj = (raw as? RpcValue.Obj)?.json as? JsonObject
            ?: throw Failure(ErrorMapping.protocolFault("resync result not an object"))
        val ackObj = obj["ack"] as? JsonObject
            ?: throw Failure(ErrorMapping.protocolFault("resync ack missing"))
        return SubscribeAck.decode(ackObj)
    }

    /** Send a v4 command envelope (CAS-validated at build time, INV-4 idempotent). */
    suspend fun sendConversationCommand(envelope: CommandEnvelope): CommandAck {
        val raw = v4Call("sendConversationCommandV4", RpcValue.Obj(envelope.encode()))
        val obj = (raw as? RpcValue.Obj)?.json as? JsonObject
            ?: throw Failure(ErrorMapping.protocolFault("command ack not an object"))
        return CommandAck.decode(obj)
    }

    // ------------------------------------------------------------------ control plane

    private suspend fun controlRequest(requestId: String, payloadJson: String, timeoutMs: Long): JsonObject {
        val deferred = CompletableDeferred<JsonObject>()
        synchronized(pendingControl) { pendingControl[requestId] = deferred }
        try {
            sendNow(RelayFrameCodec.encodeEnvelope(payloadJson))
            return withTimeout(timeoutMs) { deferred.await() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw Failure(ErrorMapping.timeout("control request", timeoutMs))
        } finally {
            synchronized(pendingControl) { pendingControl.remove(requestId) }
        }
    }

    private fun nextRequestId(): String = "req-${controlRequestCounter.incrementAndGet()}"

    suspend fun bootstrap(): JsonObject {
        val requestId = nextRequestId()
        return controlRequest(
            requestId,
            encodeJson.encodeToString(
                com.zcode.android.core.protocol.relay.BootstrapRequest(requestId = requestId)
            ),
            config.controlRequestTimeoutMs,
        )
    }

    suspend fun workspaceList(): JsonObject {
        val requestId = nextRequestId()
        return controlRequest(
            requestId,
            encodeJson.encodeToString(
                com.zcode.android.core.protocol.relay.WorkspaceListRequest(requestId = requestId)
            ),
            config.controlRequestTimeoutMs,
        )
    }

    companion object {
        /** Consecutive desktop-disconnected rejections before fast Degraded (round 4). */
        const val DESKTOP_DISCONNECTED_DEGRADED_THRESHOLD = 3

        /** Round-6: minimum wait after a bridge rejection before the next attempt (#328). */
        const val BRIDGE_REJECT_FLOOR_MS = 5_000L
    }

    data class BridgeInfo(
        val workspaceKey: String,
        val workspacePath: String,
        val workspaceIdentity: String?,
        val initialTaskId: String?,
    )

    private suspend fun openBridgeInternal(): BridgeInfo {
        val endpoint = endpoint ?: error("no endpoint")
        // §9.5 pairing completion + Phase 6 field fix: when no workspace is pinned on
        // the endpoint (the standard first-pair path), run the BOOTSTRAP discovery
        // FIRST — the verified production ordering (live corpus: bootstrap-request →
        // bootstrap-response → workspace-bridge-open WITH a resolved taskId). The
        // bootstrap-response carries workspaces[] AND initialViewState.activeTaskId;
        // opening the bridge WITHOUT a resolvable task was rejected by production
        // (workspace-bridge-error session-not-found) while the lenient mock passed.
        val cachedKey = endpoint.workspaceKey
        var workspacePath = endpoint.workspacePath
        val cachedTask = endpoint.taskId
        var workspaceKey: String
        var taskId: String?
        run {
            // Round-5 field fix: bootstrap on EVERY connection — the ACTIVE workspace/
            // task is connection-scoped desktop state (SPA behavior: bootstrap → bridge
            // per connect). Round 3's endpoint write-back accidentally made every
            // RE-connect skip discovery and bridge with the PREVIOUS connection's
            // cached (workspaceKey, taskId) — observed on a real phone as control
            // plane answering (482 tasks) while every bridge-open was rejected
            // desktop-disconnected. Cached/descriptor values remain FALLBACKS only.
            val response = bootstrap()
            val result = response["result"] as? JsonObject
                ?: throw Failure(ErrorMapping.protocolFault("bootstrap-response without result"))
            val workspaces = result["workspaces"] as? JsonArray ?: JsonArray(emptyList())
            val initialView = result["initialViewState"] as? JsonObject
            val activeKey = (initialView?.get("activeWorkspaceKey") as? JsonPrimitive)?.content
                ?: (result["activeWorkspaceKey"] as? JsonPrimitive)?.content // workspace-list shape fallback
            val activeTask = (initialView?.get("activeTaskId") as? JsonPrimitive)?.content
                ?: (result["activeTaskId"] as? JsonPrimitive)?.content
            workspaceKey = activeKey
                ?: ((workspaces.firstOrNull() as? JsonObject)?.get("workspaceKey") as? JsonPrimitive)?.content
                ?: ((workspaces.firstOrNull() as? JsonObject)?.get("workspacePath") as? JsonPrimitive)?.content
                ?: cachedKey
                ?: throw Failure(ErrorMapping.protocolFault("bootstrap has no workspaces"))
            if (workspacePath == null) {
                workspacePath = ((workspaces.firstOrNull() as? JsonObject)?.get("workspacePath") as? JsonPrimitive)?.content
                    ?: endpoint.workspacePath
            }
            taskId = activeTask
                ?: (result["tasks"] as? JsonArray)?.asSequence()
                    ?.mapNotNull { (it as? JsonObject)?.get("taskId") as? JsonPrimitive }
                    ?.firstOrNull()?.content
                ?: cachedTask
        }
        val requestId = nextRequestId()
        val open = com.zcode.android.core.protocol.relay.WorkspaceBridgeOpen(
            requestId = requestId,
            bridgeSessionId = bridgeSessionId,
            bridgeGeneration = bridgeGeneration,
            recoveryId = recoveryId,
            workspaceKey = workspaceKey,
            taskId = taskId,
        )
        val response = controlRequest(requestId, encodeJson.encodeToString(open), config.bridgeTimeoutMs)
        val zt = (response["zcode_type"] as? JsonPrimitive)?.content
        if (zt == "workspace-bridge-error") {
            // The reason field is the ONLY diagnostic production gives — preserve it
            // verbatim in the error (and the log); never discard it again.
            val reason = (response["reason"] as? JsonPrimitive)?.content ?: "unspecified"
            bound().warn("bridge.error", "reason=$reason")
            throw Failure(ErrorMapping.bridgeError(reason))
        }
        val bridge = (response["bridge"] as? JsonObject)
            ?: throw Failure(ErrorMapping.protocolFault("bridge-ready without bridge object"))
        bound().info("bridge.open", "ws=$workspaceKey task=$taskId bridge=${(bridge["bridgeSessionId"] as? JsonPrimitive)?.content?.take(16)}")
        (response["bridgeGeneration"] as? JsonPrimitive)?.content?.toIntOrNull()?.let { bridgeGeneration = it }
        return BridgeInfo(
            workspaceKey = workspaceKey,
            workspacePath = (bridge["workspacePath"] as? JsonPrimitive)?.content ?: workspacePath ?: "",
            workspaceIdentity = (bridge["workspaceIdentity"] as? JsonPrimitive)?.content,
            initialTaskId = (bridge["initialTaskId"] as? JsonPrimitive)?.content ?: taskId,
        )
    }

    // ------------------------------------------------------------------ close

    override suspend fun close(reason: String) {
        closedByUs = true
        heartbeatJob?.cancel()
        ackWatcherJob?.cancel()
        channel.close(reason)
        ws?.let { runCatching { it.close(1000, reason) } }
        ws = null
        bound().info("relay.closed", reason)
    }

    /** Unpair/wipe: destroys the endpoint identity (§14.4.1 Closed — absorbing). */
    suspend fun wipe() {
        close("unpair")
        runCatching { machine.on(ConnectionStateMachine.ConnectionEvent.UNPAIR_OR_WIPE) }
        publishState()
    }

    /** Recovery-id control for tests and Phase-3+ flows (bridge replay identity). */
    fun setRecoveryIdForRecoveryDrill(id: String) {
        recoveryId = id
    }

    /**
     * Server-acked logEpoch for a subscribed session — updated by EVERY subscribe
     * (including the internal recovery re-subscribe, whose ack carries a possibly
     * moved epoch, §15-S9). Read-only view for the projection gateway's frame stamping.
     */
    fun subscriptionEpoch(sessionId: String): String? =
        subscriptions[V4Topics.conversation(sessionId)]?.logEpoch

    private fun publishState() {
        _states.value = machine.state
    }

    private fun safeMachine(event: ConnectionStateMachine.ConnectionEvent) {
        try {
            machine.on(event)
        } catch (e: ConnectionStateMachine.IllegalTransitionException) {
            bound().warn("state.anomaly", e.message?.take(100))
        }
        publishState()
    }

    /** Field fix: write the bridge-discovered workspace back onto the endpoint. */
    private fun applyDiscoveredWorkspace(bridge: BridgeInfo) {
        val current = endpoint ?: return
        endpoint = current.copy(
            workspaceKey = bridge.workspaceKey,
            workspacePath = bridge.workspacePath,
            workspaceIdentity = bridge.workspaceIdentity ?: current.workspaceIdentity,
            taskId = bridge.initialTaskId ?: current.taskId,
        )
    }

    private fun buildWsUrl(endpoint: EndpointDescriptor): String {
        // Q4 live finding: the relay requires ?mid=<desktop deviceMid> for terminal
        // classification — appended exactly like the SPA does.
        val mid = endpoint.deviceMid ?: return endpoint.wsUrl
        return if (endpoint.wsUrl.contains("?")) "${endpoint.wsUrl}&mid=$mid" else "${endpoint.wsUrl}?mid=$mid"
    }

    /** AppError wrapper used inside the transport; surfaces at connect()/v4 boundaries. */
    class Failure(val error: AppError) : Exception(error.developerDiagnostic)
}

/** transport-class loss (§33 transport category). */
fun transportLoss(reason: String): AppError = AppError(
    category = com.zcode.android.core.common.error.ErrorCategory.TRANSPORT,
    userMessage = "Connection lost — reconnecting…",
    developerDiagnostic = reason,
    retryable = AppError.Retryable.AUTO,
    recoverable = true,
    autoRecovery = com.zcode.android.core.common.error.RecoveryAction.RECONNECT,
)

