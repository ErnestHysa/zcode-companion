package com.zcode.android.core.transport

import com.zcode.android.core.common.error.AppError
import com.zcode.android.core.common.error.ErrorCategory
import com.zcode.android.core.common.error.RecoveryAction
import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.protocol.channel.ChannelSession
import com.zcode.android.core.protocol.socket.SocketFrame
import com.zcode.android.core.protocol.socket.ChunkStream
import com.zcode.android.core.protocol.v4.ConnectionFlowState
import com.zcode.android.core.protocol.v4.Hello
import com.zcode.android.core.protocol.v4.RowsRangeParams
import com.zcode.android.core.protocol.v4.RowsRangeResult
import com.zcode.android.core.protocol.v4.SubscribeAck
import com.zcode.android.core.protocol.v4.CommandAck
import com.zcode.android.core.protocol.v4.CommandEnvelope
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import com.zcode.android.core.protocol.bytes.RpcSerialization.RpcValue
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random

/**
 * Server-info (§7.10, docs/protocol/phase10-direct.md §1) — tolerant decode
 * (INV-8): unknown fields ignored; `capabilities` flags default false.
 */
data class ServerInfo(
    val serverId: String?,
    val name: String?,
    val version: String?,
    val protocolVersion: Long?,
    val authRequired: Boolean,
    val workspaces: List<ServerWorkspace>,
    val capabilities: ServerCapabilities,
    val raw: JsonObject,
) {
    data class ServerWorkspace(val path: String?, val label: String?)
    data class ServerCapabilities(
        val desktopContinuous: Boolean,
        val websocketRpc: Boolean,
        val processResourceTelemetry: Boolean,
    )

    companion object {
        fun decode(obj: JsonObject): ServerInfo {
            val caps = obj["capabilities"] as? JsonObject
            fun flag(name: String) = (caps?.get(name) as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
            val workspaces = (obj["workspaces"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { el ->
                    val ws = el as? JsonObject ?: return@mapNotNull null
                    val path = (ws["path"] as? JsonPrimitive)?.content
                    // A workspace without a path cannot produce a §7.11 identity key — dropped.
                    if (path == null) return@mapNotNull null
                    ServerWorkspace(path, (ws["label"] as? JsonPrimitive)?.content)
                }
                .orEmpty()
            return ServerInfo(
                serverId = (obj["serverId"] as? JsonPrimitive)?.content,
                name = (obj["name"] as? JsonPrimitive)?.content,
                version = (obj["version"] as? JsonPrimitive)?.content,
                protocolVersion = (obj["protocolVersion"] as? JsonPrimitive)?.content?.toLongOrNull(),
                authRequired = (obj["authRequired"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false,
                workspaces = workspaces,
                capabilities = ServerCapabilities(
                    desktopContinuous = flag("desktopContinuous"),
                    websocketRpc = flag("websocketRpc"),
                    processResourceTelemetry = flag("processResourceTelemetry"),
                ),
                raw = obj,
            )
        }
    }
}

/**
 * TOFU pin policy seam (§11.5, phase10-direct.md §8): the transport records the
 * first-seen TLS leaf fingerprint and fails CLOSED on a later mismatch. The app
 * binds it per endpoint (opt-in); null disables the check entirely.
 */
interface TofuPinPolicy {
    fun pinnedFingerprint(endpointId: String): String?
    fun recordFingerprint(endpointId: String, fingerprint: String)
}

/** SHA-256 over the DER of the presented leaf certificate (standard digest, INV-10 untouched). */
object TlsFingerprint {
    fun of(cert: java.security.cert.Certificate): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString("") { "%02x".format(it) }
}

/** ZCodeTaskMeta subset (docs/protocol/phase10-direct.md §6) for the direct task plane. */
data class DirectTaskMeta(
    val taskId: String,
    val title: String,
    val statusWire: String?,
    val workspacePath: String?,
    val workspaceIdentity: String?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val mode: String?,
    val model: String?,
)

/**
 * DirectTransport — production-grade Option B transport (§11.4, §43.2-10.1):
 * HTTP server-info preflight with the `?token=` → `zcode_lite_token` cookie
 * handshake (docs/protocol/phase10-direct.md §2), BINARY WS frames carrying raw
 * SocketProtocol frames (13-byte header, id/ack constant zero — §3 of the same
 * doc), the shared [ChannelV4Plane] for the v4 plane (byte-identical to relay —
 * §8.3.1), candidate failover in stored order within the SAME trust domain only
 * (§11.3), Local-Network-Protection error classification (§25.3.7) and the TOFU
 * pin option (fail-closed on mismatch).
 *
 * Auth-model separation is NORMATIVE (§11.4): NO relay HMAC, NO relay envelopes,
 * NO relay heartbeat — WS liveness is OkHttp `pingInterval(20s)` (§11.5). The
 * token never appears in logs (INV-3; the preflight URL is logged scheme+host
 * only) and never leaves the [TokenProvider] capability.
 */
class DirectTransport(
    private val tokenProvider: TokenProvider,
    private val wsFactory: WsFactory = OkHttpWsFactory(),
    private val log: ZLog,
    private val clock: () -> Long = System::currentTimeMillis,
    random: Random = Random.Default,
    private val config: Config = Config(),
    private val externalScope: CoroutineScope? = null,
    reconnectPolicyOverride: ReconnectPolicy? = null,
    private val tofuPolicy: TofuPinPolicy? = null,
) : AppTransport {

    data class Config(
        val connectTimeoutMs: Long = 10_000,
        /** §11.5: auth handshake 15 s — the preflight HTTP round trip. */
        val authTimeoutMs: Long = 15_000,
        val channelInitTimeoutMs: Long = 10_000,
        val v4CallTimeoutMs: Long = 10_000,
        val bridgeHandshakeTimeoutMs: Long = 30_000,
        val controlRequestTimeoutMs: Long = 10_000,
        val sendQueueCapacity: Int = 1_024,
        val parseBufferCapacity: Int = 1_024,
        /** Bounded candidate list (§11.3; INV-5). */
        val maxCandidates: Int = 8,
    )

    /**
     * Multi-endpoint seam (§11.6): the ACTIVE direct endpoint's token capability is
     * rebound by the app at connect time (custody stays in the vault — the transport
     * still only ever sees the challenge-answering capability, §26.3).
     */
    @Volatile private var tokenOverride: TokenProvider? = null
    fun rebindTokenProvider(provider: TokenProvider) {
        tokenOverride = provider
    }

    private fun currentToken(): String = (tokenOverride ?: tokenProvider).token()

    private val scope = externalScope ?: CoroutineScope(Dispatchers.IO)
    private val machine = ConnectionStateMachine()
    private val reconnect = reconnectPolicyOverride ?: ReconnectPolicy(clock = clock, random = random)

    private val json = Json { ignoreUnknownKeys = true }

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
    @Volatile private var ws: Ws? = null
    private var plane: ChannelV4Plane = newPlane()
    @Volatile private var closedByUs = false

    private var ioJobs: List<Job> = emptyList()
    private val parseBuffer = Channel<ByteArray>(config.parseBufferCapacity)
    private val sendBuffer = Channel<ByteArray>(config.sendQueueCapacity)

    private val recovering = java.util.concurrent.atomic.AtomicBoolean(false)
    private val connectionCounter = java.util.concurrent.atomic.AtomicLong(0)

    /** Server-info from the winning preflight (§11.6 workspace-list source). */
    @Volatile var serverInfo: ServerInfo? = null
        private set

    /** Cumulative wire counters (diagnostics export, relay-parity fields). */
    private val cFramesSent = java.util.concurrent.atomic.AtomicLong()
    private val cFramesRecv = java.util.concurrent.atomic.AtomicLong()
    private val cV4Calls = java.util.concurrent.atomic.AtomicLong()

    fun statsSnapshot(): Map<String, Long> = sortedMapOf(
        "framesSent" to cFramesSent.get(),
        "framesRecv" to cFramesRecv.get(),
        "v4Calls" to cV4Calls.get(),
    )

    private fun bound() = log.with(
        ZLog.LogContext(endpointId = endpoint?.id, connectionId = "direct-" + connectionCounter.get()),
    )

    private fun newPlane(): ChannelV4Plane = ChannelV4Plane(
        channel = ChannelSession(),
        host = object : ChannelV4Plane.Host {
            override fun endpointId(): String? = endpoint?.id
            override fun workspaceTarget(): Pair<String?, String?>? =
                endpoint?.let { it.workspacePath to it.workspaceIdentity }
            override fun emitInbound(frame: InboundFrame) {
                scope.launch { this@DirectTransport.emitInbound(frame) }
            }
            override fun onWireVersionMismatch(message: String?) {
                scope.launch { _appErrors.emit(ErrorMapping.versionIncompatible(message ?: "wire version")) }
                onConnectionLost("wire version mismatch")
            }
            override fun onCountedFault(reason: String) {
                scope.launch { _appErrors.emit(ErrorMapping.serializationFault(reason)) }
            }
            override fun onSerializationFault(reason: String) {
                scope.launch { _appErrors.emit(ErrorMapping.serializationFault(reason)) }
            }
            override fun onV4Call() {
                cV4Calls.incrementAndGet()
            }
            override fun connectionId(): String? = plane.connectionId
            override fun boundLog(): ZLog.Bound = bound()
        },
        log = log,
        clock = clock,
        timeouts = ChannelV4Plane.Timeouts(
            channelInitTimeoutMs = config.channelInitTimeoutMs,
            v4CallTimeoutMs = config.v4CallTimeoutMs,
            bridgeHandshakeTimeoutMs = config.bridgeHandshakeTimeoutMs,
            controlRequestTimeoutMs = config.controlRequestTimeoutMs,
            authTimeoutMs = config.authTimeoutMs,
        ),
    )

    private suspend fun emitInbound(frame: InboundFrame) {
        if (!_inbound.tryEmit(frame)) {
            _appErrors.emit(ErrorMapping.protocolFault("inbound flow overflow → resync (INV-5)"))
            onConnectionLost("inbound overflow")
        }
    }

    // ------------------------------------------------------------------ connect

    override suspend fun connect(endpoint: EndpointDescriptor, signer: CredentialSigner) {
        val s = machine.state
        check(s != ConnectionStateMachine.ConnectionState.CLOSED) { "endpoint identity destroyed" }
        check(
            s == ConnectionStateMachine.ConnectionState.UNPAIRED ||
                s == ConnectionStateMachine.ConnectionState.PAIRING ||
                s == ConnectionStateMachine.ConnectionState.AUTHENTICATING
        ) { "connect() is the first-connection path; use recover() (state=$s)" }
        // §11.4 auth-model guard: the direct transport consumes a TOKEN, never a
        // relay signer. The interface keeps the signer param (API freeze, §43.2-2.1);
        // the endpoint KIND is the discriminator — a relay descriptor here is a
        // caller bug we refuse loudly instead of silently mis-authenticating.
        check(endpoint.kind == EndpointKind.DIRECT_SERVER) {
            "DirectTransport requires a DIRECT_SERVER endpoint (got ${endpoint.kind})"
        }
        this.endpoint = endpoint
        closedByUs = false
        machine.seed(ConnectionStateMachine.ConnectionState.AUTHENTICATING)
        publishState()
        try {
            establish()
        } catch (e: TransportFailure) {
            if (e.error.category == ErrorCategory.AUTH) {
                safeMachine(ConnectionStateMachine.ConnectionEvent.AUTH_FAILED)
            }
            throw e
        }
    }

    /**
     * One connection attempt: per-candidate preflight+WS+channel+hello. Candidates
     * are tried in stored order (primary first, then `candidates` LAN→tailnet→
     * tunnel — §11.3); ONLY transport/network/LNP failures advance the loop, an
     * AUTH rejection fails immediately (the token is authoritative), and every
     * candidate belongs to THIS endpoint — a cross-trust-domain switch does not
     * exist in this code path (acceptance: "no auto-switch across trust domains").
     */
    private suspend fun establish() {
        val endpoint = endpoint ?: error("connect() before endpoint")
        connectionCounter.incrementAndGet()
        val ctx = bound()
        val token = currentToken()
        val candidates = buildList {
            add(endpoint.wsUrl)
            addAll(endpoint.candidates.take(config.maxCandidates - 1))
        }.filter { it.isNotBlank() }.distinct()

        var lastFailure: TransportFailure? = null
        for (wsUrl in candidates) {
            try {
                establishOn(wsUrl, token, ctx)
                // Health + telemetry bookkeeping for the registry (§11.3).
                ctx.info("direct.connected", "via=${hostOf(wsUrl)} candidatesTried=${candidates.indexOf(wsUrl) + 1}")
                return
            } catch (e: TransportFailure) {
                lastFailure = e
                val advance = when (e.error.category) {
                    ErrorCategory.AUTH -> false // token rejected: no candidate fixes it
                    ErrorCategory.ANDROID -> true // LNP: this network path is blocked, try the next (§25.3)
                    else -> true // transport/network classes advance (§11.3 ordering)
                }
                ctx.warn("direct.candidateFailed", "${e.error.category} ${e.error.developerDiagnostic.take(120)} advance=$advance")
                if (!advance) throw e
            }
        }
        throw lastFailure ?: TransportFailure(transportLoss("all direct candidates failed"))
    }

    private suspend fun establishOn(wsUrl: String, token: String, ctx: ZLog.Bound) {
        val endpoint = endpoint ?: error("no endpoint")
        if (!isTlsUrl(wsUrl)) {
            // §11.4 honest labeling: ws:// is plaintext — surfaced, never silently used
            // as if it were secure. The connection still proceeds (LAN tradeoff is the
            // user's documented choice, docs/runbooks/direct-server.md §4).
            scope.launch { _appErrors.emit(ErrorMapping.insecureDirectTransport(wsUrl)) }
        }
        // ---- preflight: GET /api/server-info?token= (validates the token; captures
        // cookie). BLOCKING OkHttp call — always off the caller's dispatcher (the app
        // hub connects from the MAIN scope; a NetworkOnMainThreadException here killed
        // the first on-device run, fixed 2026-10-03).
        val origin = httpOriginOf(wsUrl)
        val probe = withContext(kotlinx.coroutines.Dispatchers.IO) {
            DirectServerProbe.probe(origin, token, config.authTimeoutMs)
        }
        val info = when (probe) {
            is ProbeResult.Ok -> probe.info
            is ProbeResult.TokenRejected -> throw TransportFailure(ErrorMapping.directTokenRejected(probe.status))
            is ProbeResult.LocalNetworkBlocked -> throw TransportFailure(ErrorMapping.localNetworkBlocked(probe.reason))
            is ProbeResult.Unreachable -> throw TransportFailure(transportLoss("server-info unreachable: ${probe.reason.take(100)}"))
        }
        serverInfo = info
        if (info.protocolVersion != null && info.protocolVersion != 1L) {
            throw TransportFailure(ErrorMapping.versionIncompatible("server protocolVersion=${info.protocolVersion} (supported: 1)"))
        }
        ctx.info("direct.serverInfo", "server=${info.serverId?.take(40)} version=${info.version} workspaces=${info.workspaces.size}")
        // §14.4.1: the token gate IS the direct auth — validated server-info = auth ok
        // (Authenticating → Connecting); hello completes Connecting → Connected below.
        if (machine.state == ConnectionStateMachine.ConnectionState.AUTHENTICATING) {
            machine.on(ConnectionStateMachine.ConnectionEvent.AUTH_MATCHED)
            publishState()
        }

        // ---- workspace pinning (applyDiscoveredWorkspace parity): the first
        // server-info workspace is the routing scope for the scoped listen; the
        // descriptor's own fields (if any) win — per-call targets override anyway.
        if (endpoint.workspacePath.isNullOrBlank() && info.workspaces.isNotEmpty()) {
            val ws0 = info.workspaces.first()
            this.endpoint = endpoint.copy(workspacePath = ws0.path)
        }

        // ---- Channel plane BEFORE the socket opens (race fix 2026-10-03, relay
        // parity): the direct server sends {Initialize} immediately on accept — the
        // plane the reader feeds must already be the one establish() uses.
        // Recover() re-subscribes from the plane's records — carry them across the
        // rotation (same regression class as the relay, S9/S12/S13).
        val freshPlane = newPlane()
        synchronized(plane.subscriptions) { freshPlane.subscriptions.putAll(plane.subscriptions) }
        plane = freshPlane
        plane.channel.onOutbound = { bytes -> enqueueChannelFrame(bytes) }

        // ---- WS connect (binary frames; cookie header — never a logged URL query)
        val socket = wsFactory.create()
        socket.connect(wsUrl, config.connectTimeoutMs, mapOf("Cookie" to cookieHeader(token)))
        ws = socket
        startIoLoops(socket)

        // ---- TLS fingerprint (TOFU option) — captured from the live socket
        maybeVerifyTofu(socket)
        val hello = plane.helloAndInitialize(endpoint.id, endpoint.appVersion)
        _capabilities.value = CapabilitySnapshot(
            connectionId = hello.connectionId,
            serverTimeMs = hello.serverTime,
            host = hello.capabilities,
            desktopAppVersion = info.version ?: endpoint.desktopAppVersion,
            endpointKind = EndpointKind.DIRECT_SERVER,
        )
        if (machine.state == ConnectionStateMachine.ConnectionState.AUTHENTICATING ||
            machine.state == ConnectionStateMachine.ConnectionState.CONNECTING
        ) {
            machine.on(ConnectionStateMachine.ConnectionEvent.HELLO_RECEIVED) // → CONNECTED
        }
        publishState()
        reconnect.reset()
    }

    private suspend fun maybeVerifyTofu(socket: Ws) {
        val policy = tofuPolicy ?: return
        val endpoint = endpoint ?: return
        val fp = withTimeoutOrNull(5_000) { socket.tlsPeerFingerprint.firstOrNull() } ?: return
        val pinned: String? = policy.pinnedFingerprint(endpoint.id)
        if (pinned == null) {
            policy.recordFingerprint(endpoint.id, fp)
            bound().info("direct.tofu.recorded", "leaf=${fp.take(12)}… (first use, §11.5)")
            return
        }
        if (!pinned.equals(fp, ignoreCase = true)) {
            bound().warn("direct.tofu.mismatch", "FAIL CLOSED (§26.2-T11)")
            runCatching { socket.close(1000, "tofu mismatch") }
            throw TransportFailure(ErrorMapping.tofuPinMismatch(pinned, fp))
        }
        bound().info("direct.tofu.verified", "leaf=${fp.take(12)}…")
    }

    // ------------------------------------------------------------------ io loops

    private fun startIoLoops(socket: Ws) {
        val old = ioJobs
        ioJobs = listOf(
            scope.launch { senderLoop(socket) },
            scope.launch {
                try {
                    for (bytes in parseBuffer) {
                        try {
                            handleWireBytes(bytes)
                        } catch (e: CancellationException) {
                            // closing
                        } catch (e: Exception) {
                            // INV-8: one malformed frame never kills the ordered parser.
                            scope.launch { _appErrors.emit(ErrorMapping.serializationFault("wire handler fault: ${e.javaClass.simpleName}")) }
                        }
                    }
                } catch (e: CancellationException) {
                    // closing
                }
            },
            scope.launch {
                try {
                    socket.incomingBinary.collect { bytes ->
                        if (!parseBuffer.trySend(bytes).isSuccess) {
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
            scope.launch {
                val generation = socket
                socket.closed.collect { closed ->
                    if (ws !== generation) return@collect
                    if (closedByUs) return@collect
                    when {
                        !closed.expected -> onConnectionLost("abnormal close ${closed.code}: ${closed.reason.take(60)}")
                        else -> onConnectionLost("server close ${closed.code}")
                    }
                }
            },
        )
        for (j in old) j.cancel()
    }

    /**
     * SocketProtocol frame parser (docs/protocol/phase10-direct.md §3): binary WS
     * messages are 13-byte-header frames; only Regular payloads reach the Channel
     * layer. ChunkStream reassembles defensively although WS preserves boundaries.
     */
    private val wireStream = ChunkStream()

    private fun handleWireBytes(bytes: ByteArray) {
        cFramesRecv.incrementAndGet()
        wireStream.acceptChunk(bytes)
        while (true) {
            val header = wireStream.peek(SocketFrame.HEADER_SIZE) ?: return
            val (type, _, length) = SocketFrame.parseHeader(header)
            if (wireStream.byteLength < SocketFrame.HEADER_SIZE + length) return
            wireStream.skip(SocketFrame.HEADER_SIZE)
            val payload = if (length > 0) wireStream.read(length) ?: continue else ByteArray(0)
            when (SocketFrame.ProtocolMessageType.from(type)) {
                SocketFrame.ProtocolMessageType.Regular -> {
                    if (payload.isNotEmpty()) {
                        try {
                            plane.channel.onFrame(payload)
                        } catch (e: com.zcode.android.core.protocol.bytes.ProtocolException) {
                            // INV-8: geometry sound, payload not a decodable Channel frame —
                            // count + surface, never crash.
                            scope.launch { _appErrors.emit(ErrorMapping.serializationFault("channel payload undecodable (counted): ${e.message?.take(60)}")) }
                        }
                    }
                }
                SocketFrame.ProtocolMessageType.Disconnect -> onConnectionLost("protocol Disconnect(5)")
                else -> Unit // SocketProtocol ignores non-Regular types (protocol.ts:294)
            }
        }
    }

    private fun enqueueChannelFrame(bytes: ByteArray) {
        val framed = SocketFrame.writeMessage(
            SocketFrame.ProtocolMessage(SocketFrame.ProtocolMessageType.Regular, 0, 0, bytes),
        )
        if (!sendBuffer.trySend(framed).isSuccess) {
            scope.launch {
                _appErrors.emit(ErrorMapping.protocolFault("send queue saturated (INV-5 bound ${config.sendQueueCapacity})"))
            }
        }
        cFramesSent.incrementAndGet()
        bound().debug("direct.send", "bytes=${bytes.size}")
    }

    private suspend fun senderLoop(socket: Ws) {
        for (framed in sendBuffer) {
            if (closedByUs) return
            if (!socket.sendBinary(framed)) {
                onConnectionLost("send failed")
                return
            }
        }
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
        while (!closedByUs) {
            if (machine.state == ConnectionStateMachine.ConnectionState.CONNECTED) return
            if (machine.state == ConnectionStateMachine.ConnectionState.DEGRADED ||
                machine.state == ConnectionStateMachine.ConnectionState.FAILED ||
                machine.state == ConnectionStateMachine.ConnectionState.CLOSED
            ) {
                bound().warn("direct.recovery.quiesced", "machine=${machine.state} — user action required")
                return
            }
            val delayMs = reconnect.nextDelayMs()
            if (delayMs == null) {
                safeMachine(ConnectionStateMachine.ConnectionEvent.RECONNECT_BUDGET_EXHAUSTED)
                _appErrors.emit(ErrorMapping.degradedBudget("reconnect budget exhausted"))
                return
            }
            if (delayMs > 0) delay(delayMs)
            try {
                ensureReconnectingState()
                establish()
                // Re-subscribe (§15.1): the plane tracks the subscription set; the acks
                // decide snapshot-replacement vs gap-free exactly like relay.
                var replaced = false
                val subs = synchronized(plane.subscriptions) { plane.subscriptions.toList() }
                for ((topic, rec) in subs) {
                    val session = topic.removePrefix(com.zcode.android.core.protocol.v4.V4Topics.CONVERSATION_PREFIX)
                    val ack = subscribeSession(session, visibility = null)
                    if (ack.mode == "snapshot" || ack.logEpoch != rec.logEpoch) replaced = true
                }
                if (machine.state == ConnectionStateMachine.ConnectionState.RECOVERING) {
                    machine.on(
                        if (replaced) ConnectionStateMachine.ConnectionEvent.RESYNC_SNAPSHOT_REPLACED
                        else ConnectionStateMachine.ConnectionEvent.RESYNC_GAP_FREE
                    )
                    publishState()
                }
                bound().info("direct.recovered", "subscriptions=${subs.size} snapshotReplace=$replaced")
                return
            } catch (e: TransportFailure) {
                if (e.error.category == ErrorCategory.AUTH) {
                    safeMachine(ConnectionStateMachine.ConnectionEvent.AUTH_FAILED)
                    _appErrors.emit(e.error)
                    return // re-entry = user re-enters the token; never loop auth failures (§12.6)
                }
                bound().warn("direct.reconnectFailed", e.error.developerDiagnostic.take(140))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                bound().warn("direct.reconnectFailed", "transport-class: ${e.javaClass.simpleName}")
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

    private fun onConnectionLost(reason: String) {
        if (closedByUs) return
        if (!recovering.compareAndSet(false, true)) {
            bound().warn("direct.lost.suppressed", "recovery already in flight ($reason)")
            return
        }
        bound().warn("direct.lost", reason)
        plane.channel.close(reason)
        while (sendBuffer.tryReceive().isSuccess) { /* drain unsent (higher layers own retries) */ }
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
            else -> Unit
        }
        publishState()
        if (!transitioned) {
            recovering.set(false)
            return
        }
        scope.launch {
            try {
                recoverLocked()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                bound().warn("recover.fault", "${e.javaClass.simpleName}: ${e.message?.take(80)}")
            } finally {
                recovering.set(false)
            }
        }
    }

    override fun reconnectNow() {
        if (machine.state == ConnectionStateMachine.ConnectionState.DEGRADED) {
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

    override fun resetForEndpointSwitch() {
        plane.channel.close("endpoint switch")
        ws?.let { runCatching { it.close(1000, "endpoint switch") } }
        ws = null
        machine.seed(ConnectionStateMachine.ConnectionState.UNPAIRED)
        publishState()
    }

    // ------------------------------------------------------------------ close / send

    override suspend fun send(frame: OutboundFrame) {
        when (frame) {
            is OutboundFrame.Channel -> enqueueChannelFrame(frame.bytes)
            is OutboundFrame.RelayControl -> {
                // The relay control plane (bootstrap/bridge) does NOT exist on the
                // direct surface (docs/protocol/phase10-direct.md §6) — unsupported-
                // feature error, never a silent drop (§33 distinct-categories rule).
                throw TransportFailure(
                    AppError(
                        category = ErrorCategory.UNSUPPORTED_FEATURE,
                        userMessage = "Not available on direct endpoints",
                        developerDiagnostic = "RelayControl frame on DIRECT transport (control plane is relay-only)",
                        retryable = AppError.Retryable.NO,
                        recoverable = false,
                        autoRecovery = RecoveryAction.NONE,
                    )
                )
            }
        }
    }

    override suspend fun close(reason: String) {
        closedByUs = true
        plane.channel.close(reason)
        ws?.let { runCatching { it.close(1000, reason) } }
        ws = null
        bound().info("direct.closed", reason)
    }

    // ------------------------------------------------------------------ V4Plane (shared plane)

    override val commandClientId: String?
        get() = endpoint?.id

    override val activeWorkspaceTarget: Pair<String?, String?>?
        get() = endpoint?.let { it.workspacePath to it.workspaceIdentity }

    override val v4ConnectionId: String?
        get() = plane.connectionId

    override fun ensureConversationListen(workspacePath: String?, workspaceIdentity: String?) =
        plane.ensureConversationListen(workspacePath, workspaceIdentity)

    override suspend fun subscribeSession(
        sessionId: String,
        visibility: String?,
        workspacePath: String?,
        workspaceIdentity: String?,
    ): SubscribeAck = plane.subscribeSession(sessionId, visibility, workspacePath, workspaceIdentity)

    override suspend fun subscribeWorkspaceConfig(
        workspacePath: String?,
        workspaceIdentity: String?,
    ): SubscribeAck = plane.subscribeWorkspaceConfig(workspacePath, workspaceIdentity)

    override suspend fun rowsRange(sessionId: String, beforeRowId: Long?, limit: Int): RowsRangeResult =
        plane.rowsRange(sessionId, beforeRowId, limit)

    override suspend fun rowsRange(params: RowsRangeParams): RowsRangeResult =
        rowsRange(params.sessionId, params.beforeRowId, params.limit)

    override suspend fun resyncSession(
        sessionId: String,
        base: Pair<String, Long>?,
        forceSnapshot: Boolean,
        workspacePath: String?,
        workspaceIdentity: String?,
    ): SubscribeAck = plane.resyncSession(sessionId, base, forceSnapshot, workspacePath, workspaceIdentity)

    override suspend fun sendConversationCommand(
        envelope: CommandEnvelope,
        workspacePath: String?,
        workspaceIdentity: String?,
    ): CommandAck = plane.sendConversationCommand(envelope, workspacePath, workspaceIdentity)

    override fun subscriptionWorkspace(sessionId: String): Pair<String?, String?>? =
        plane.subscriptionWorkspace(sessionId)

    override fun subscriptionEpoch(sessionId: String): String? = plane.subscriptionEpoch(sessionId)

    override suspend fun conversationFileChanges(
        sessionId: String,
        targetRowId: Long,
        targetEntityId: String,
        baseRevision: Long,
        baseLogEpoch: String,
        workspacePath: String?,
        workspaceIdentity: String?,
    ): JsonObject = plane.conversationFileChanges(
        sessionId, targetRowId, targetEntityId, baseRevision, baseLogEpoch, workspacePath, workspaceIdentity,
    )

    override suspend fun attachmentBegin(
        uploadId: String,
        sessionId: String,
        fileName: String,
        mime: String,
        totalBytes: Long,
        totalChunks: Int,
        checksum: String,
        workspacePath: String?,
        workspaceIdentity: String?,
    ): JsonObject = plane.attachmentBegin(
        uploadId, sessionId, fileName, mime, totalBytes, totalChunks, checksum, workspacePath, workspaceIdentity,
    )

    override suspend fun attachmentChunk(
        uploadId: String,
        sessionId: String,
        chunkIndex: Int,
        dataBase64: String,
        workspacePath: String?,
        workspaceIdentity: String?,
    ): JsonObject = plane.attachmentChunk(uploadId, sessionId, chunkIndex, dataBase64, workspacePath, workspaceIdentity)

    override suspend fun attachmentCommit(uploadId: String, sessionId: String): JsonObject =
        plane.attachmentCommit(uploadId, sessionId)

    override suspend fun attachmentAbort(uploadId: String, sessionId: String): JsonObject =
        plane.attachmentAbort(uploadId, sessionId)

    override suspend fun attachmentRead(
        sessionId: String,
        ref: String,
        offset: Long,
        limit: Int,
        targetRowId: Long?,
        targetEntityId: String?,
        attachmentIndex: Int?,
        workspacePath: String?,
        workspaceIdentity: String?,
    ): JsonObject = plane.attachmentRead(
        sessionId, ref, offset, limit, targetRowId, targetEntityId, attachmentIndex, workspacePath, workspaceIdentity,
    )

    override suspend fun terminalCreate(cols: Int, rows: Int, cwd: String?): JsonObject =
        plane.terminalCreate(cols, rows, cwd)

    override suspend fun terminalDispose(id: String) = plane.terminalDispose(id)

    override fun terminalListenData(id: String, handler: (String) -> Unit): Int =
        plane.terminalListenData(id, handler)

    override fun terminalListenExit(id: String, handler: (Int?) -> Unit): Int =
        plane.terminalListenExit(id, handler)

    override fun disposeListener(listenerId: Int) = plane.disposeListener(listenerId)

    override suspend fun gitRepositorySummary(workspacePath: String): JsonObject =
        plane.gitRepositorySummary(workspacePath)

    override suspend fun gitChanges(workspacePath: String, sourceId: String): JsonObject =
        plane.gitChanges(workspacePath, sourceId)

    override suspend fun gitDiff(workspacePath: String, path: String, staged: Boolean?): JsonObject =
        plane.gitDiff(workspacePath, path, staged)

    override suspend fun gitCommitGraph(workspacePath: String, maxCount: Int, skip: Int): JsonObject =
        plane.gitCommitGraph(workspacePath, maxCount, skip)

    override suspend fun fileReaddir(path: String, includeHidden: Boolean): RpcValue =
        plane.fileReaddir(path, includeHidden)

    override suspend fun fileStat(path: String): JsonObject = plane.fileStat(path)

    override suspend fun fileReadText(path: String, offset: Long?, length: Long?): JsonObject =
        plane.fileReadText(path, offset, length)

    override suspend fun fileReadBytes(path: String, offset: Long, length: Long): ByteArray =
        plane.fileReadBytes(path, offset, length)

    override suspend fun fileBinaryPreview(path: String, maxBytes: Long?): JsonObject =
        plane.fileBinaryPreview(path, maxBytes)

    override suspend fun fileSearch(rootPath: String, workspaceIdentity: String?, query: String, limit: Int): RpcValue =
        plane.fileSearch(rootPath, workspaceIdentity, query, limit)

    override suspend fun appUsageStats(range: String, timeZone: String?): JsonObject =
        plane.appUsageStats(range, timeZone)

    override suspend fun listAutomations(workspacePath: String?, workspaceIdentity: String?): JsonObject =
        plane.listAutomations(workspacePath, workspaceIdentity)

    override suspend fun offPeakTaskList(): JsonObject = plane.offPeakTaskList()

    override suspend fun modelSelectionView(): JsonObject = plane.modelSelectionView()

    // ------------------------------------------------------------------ direct surface

    /** The validated server-info (§11.6 workspace list source); null before first connect. */
    suspend fun refreshServerInfo(): ServerInfo? {
        val endpoint = endpoint ?: return null
        val token = runCatching { currentToken() }.getOrNull() ?: return null
        return when (
            val probe = withContext(kotlinx.coroutines.Dispatchers.IO) {
                DirectServerProbe.probe(httpOriginOf(endpoint.wsUrl), token, config.authTimeoutMs)
            }
        ) {
            is ProbeResult.Ok -> probe.info.also { serverInfo = it }
            else -> null
        }
    }

    /**
     * `zcode-task.listTasks` (docs/protocol/phase10-direct.md §6) — the direct
     * task-list plane. Tolerant decode; entries without taskId/title are skipped.
     */
    suspend fun listTasks(workspacePath: String, workspaceIdentity: String? = null): List<DirectTaskMeta> {
        val params = buildJsonObject {
            put("workspacePath", JsonPrimitive(workspacePath))
            workspaceIdentity?.let { put("workspaceIdentity", JsonPrimitive(workspaceIdentity)) }
        }
        val raw = plane.channelCall(
            "zcode-task", "listTasks",
            RpcValue.Arr(listOf(RpcValue.Obj(params))),
            config.v4CallTimeoutMs,
        )
        val arr = (raw as? RpcValue.Arr)?.items
            ?: ((raw as? RpcValue.Obj)?.json as? JsonObject)?.get("tasks") as? kotlinx.serialization.json.JsonArray
            ?: throw TransportFailure(ErrorMapping.protocolFault("listTasks result not an array"))
        return arr.mapNotNull { el ->
            // Wire elements are RpcValues (RpcValue.Obj carries the JsonObject).
            val obj = (el as? RpcValue.Obj)?.json as? JsonObject ?: return@mapNotNull null
            val taskId = (obj["taskId"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val title = (obj["title"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            DirectTaskMeta(
                taskId = taskId,
                title = title,
                statusWire = (obj["status"] as? JsonPrimitive)?.content,
                workspacePath = (obj["workspacePath"] as? JsonPrimitive)?.content,
                workspaceIdentity = (obj["workspaceIdentity"] as? JsonPrimitive)?.content,
                createdAtMs = (obj["createdAt"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong() ?: 0L,
                updatedAtMs = (obj["updatedAt"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong() ?: 0L,
                mode = (obj["mode"] as? JsonPrimitive)?.content,
                model = (obj["model"] as? JsonPrimitive)?.content,
            )
        }
    }

    // ------------------------------------------------------------------ helpers

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

    companion object {
        /** ws(s)://host[:port]/… → http(s)://host[:port] (preflight origin). */
        fun httpOriginOf(wsUrl: String): String {
            val trimmed = wsUrl.substringBefore("?")
            val scheme = when {
                trimmed.startsWith("wss://") -> "https://"
                trimmed.startsWith("ws://") -> "http://"
                trimmed.startsWith("https://") -> "https://"
                else -> "http://"
            }
            val rest = trimmed.removePrefix("wss://").removePrefix("ws://").removePrefix("https://").removePrefix("http://")
            val authority = rest.substringBefore("/")
            return scheme + authority
        }

        fun isTlsUrl(url: String): Boolean = url.startsWith("wss://") || url.startsWith("https://")

        /** ws(s)://host/… → host for logs (never the query — the token never logs, INV-3). */
        fun hostOf(wsUrl: String): String = wsUrl.substringBefore("?")
            .removePrefix("wss://").removePrefix("ws://").removePrefix("https://").removePrefix("http://")
            .substringBefore("/")

        /** The zcode_lite_token cookie header value (phase10-direct.md §2). */
        fun cookieHeader(token: String): String = "zcode_lite_token=${token}"
    }
}

/** Preflight outcomes (classified for §33 mapping at the call site). */
sealed class ProbeResult {
    data class Ok(val info: ServerInfo, val status: Int) : ProbeResult()
    data class TokenRejected(val status: Int) : ProbeResult()
    data class LocalNetworkBlocked(val reason: String) : ProbeResult()
    data class Unreachable(val reason: String) : ProbeResult()
}

/**
 * Direct-server probe — the HTTP half of §11.4. Shared by DirectTransport
 * (connect preflight), the add-endpoint validation flow and candidate health
 * probes. Standard OkHttp client; the token rides the query on this FIRST
 * request (the server answers with the HttpOnly cookie), and is never logged.
 */
object DirectServerProbe {

    /** LNP failure markers (§25.3.7) — practical Android signals on IOExceptions. */
    private val lnpMarkers = listOf("EPERM", "ECONNABORTED", "permission", "not permitted")

    fun probe(origin: String, token: String, timeoutMs: Long): ProbeResult {
        val client = OkHttpClient.Builder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        val url = "${origin.trimEnd('/')}/api/server-info?token=$token"
        return try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                val body = response.body?.string()
                when {
                    response.code == 401 || response.code == 403 -> ProbeResult.TokenRejected(response.code)
                    !response.isSuccessful -> ProbeResult.Unreachable("http ${response.code}")
                    body == null -> ProbeResult.Unreachable("empty body")
                    else -> {
                        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                            ?: return ProbeResult.Unreachable("server-info not JSON")
                        ProbeResult.Ok(ServerInfo.decode(obj), response.code)
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            classifyProbeFailure(e)
        }
    }

    /** Failure classification (§25.3.7 LNP / DNS / other) — extracted for direct testing. */
    internal fun classifyProbeFailure(e: Exception): ProbeResult {
        val message = (e.message ?: "") + (e.cause?.message ?: "")
        val lnp = lnpMarkers.any { message.contains(it, ignoreCase = true) }
        return when {
            lnp -> ProbeResult.LocalNetworkBlocked(message.take(120))
            e is java.net.UnknownHostException || isNameResolution(message) ->
                ProbeResult.Unreachable("DNS: ${message.take(100)}")
            else -> ProbeResult.Unreachable("${e.javaClass.simpleName}: ${message.take(100)}")
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun isNameResolution(message: String): Boolean =
        message.contains("UnknownHost", ignoreCase = true) ||
            message.contains("Unable to resolve", ignoreCase = true) ||
            message.contains("ENOTFOUND", ignoreCase = true) ||
            message.contains("resolve", ignoreCase = true) && message.contains("No address", ignoreCase = true)
}
