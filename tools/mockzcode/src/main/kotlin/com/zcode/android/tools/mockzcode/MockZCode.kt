package com.zcode.android.tools.mockzcode

import com.zcode.android.core.protocol.bytes.RpcSerialization
import com.zcode.android.core.protocol.bytes.RpcSerialization.RpcValue
import com.zcode.android.core.protocol.channel.ChannelWire
import com.zcode.android.core.protocol.relay.RelayFrameCodec
import com.zcode.android.core.protocol.relay.RelayLimits
import com.zcode.android.core.protocol.v4.DeliveryKind
import com.zcode.android.core.protocol.v4.TopicWireFrame
import com.zcode.android.core.protocol.v4.TopicWireFrameCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * MockZCode core (§35.4) — a JVM protocol-faithful mock of the relay + desktop side,
 * built ON the shipped core:protocol codecs (inbound frames are decoded by the real
 * [RelayFrameCodec]; outbound topic frames are encoded by the real
 * [TopicWireFrameCodec]) so the self-test proves round-trips against the shipped wire
 * layer. Test tooling only: NEVER shipped, NEVER pointed at production systems (INV-15).
 *
 * Grounding — nothing invented: handshake/envelope/ack semantics from
 * docs/protocol/relay-control-plane.md (Phase 1 bundle extraction + live captures);
 * v4 shapes from packages/shared/src/zcode-protocol-v4 @ 29628c9; fault classes from
 * Phase-1-observed desktop reason codes (KICKED / AUTH_FAILED / replayGraceExceeded
 * class) and the §15 scenario matrix.
 */
class MockZCode(
    val passHash: String,
    val expectedSid: String,
    val desktopMid: String = "mock-desktop-mid",
    val workspace: MockWorkspace = MockWorkspace(),
    /** Phase 10: relay-envelope mode (default) vs direct /ws + server-info mode (§11.4). */
    val mode: Mode = Mode.RELAY,
    /** The zcode_lite_token secret the direct surface requires (phase10-direct.md §2). */
    val serverToken: String = "mock-direct-token",
) {
    enum class Mode { RELAY, DIRECT }
    /** Scriptable behavior per test. */
    class Script {
        var authScenario: AuthScenario = AuthScenario.HAPPY
        var kickFirstTerminalOnSecond: Boolean = true
        var sendInitializeAfterBridge: Boolean = true
        /** Ack every delivered logical message twice (duplicate-ack tolerance). */
        var duplicateInboundAcks: Boolean = false
        /** Deliver every Nth outbound logical frame twice (duplicate storm). */
        var duplicateEvery: Int = 0

        /**
         * FIELD (2026-09-30, vc8): production delivers onDynamicConversationFrame events
         * with the arg = the TopicWireFrame ITSELF ({wireVersion, kind, …, frame: payload}
         * — desktop schema chunk-RH3H53JA); the Phase-1 spike shape wrapped it in
         * {frame: topicFrame}. true = production shape (regression coverage), false = spike shape.
         */
        var topicEventDirect: Boolean = false

        /**
         * FIELD (2026-09-30, vc9): production snapshot `rows` is a TAIL-WINDOW OBJECT
         * {window: [row…], totalCount, firstRowId} (rowsWindowSchema, snapshot.ts);
         * the default bare-array shape is the Phase-1/4 mock shape.
         */
        var snapshotRowsWindowShape: Boolean = false

        /**
         * FIELD-KILLER #5 (2026-09-30, vc10): production routes topic frames by the
         * LISTEN's workspace scope. When true, the initial snapshot for a subscribe is
         * delivered ONLY if a scoped listener for that subscribe's workspacePath was
         * registered first — the per-workspace listener contract.
         */
        var enforceScopeRouting: Boolean = false

        /** Row-id base for snapshot rows (tail-window simulation: base > firstRowId ⇒ truncated). */
        var snapshotRowIdBase: Long = 1L

        /**
         * When set, the snapshot appends ONE assistantText row carrying this markdown
         * payload (nested list / table / fenced code) — exercises the phone's bounded
         * parser live instead of inferring rendering from MarkdownParser.kt alone.
         */
        var demoAssistantMarkdown: String? = null

        /**
         * Bulk-leg override (MOCK_ROWS): replaces the workspace's default snapshot row
         * count so the phone has a long conversation to scroll (gfxinfo jank leg).
         */
        var snapshotRowCount: Int? = null

        /**
         * Bulk-leg row realism (MOCK_ROWS): ASSISTANT_TEXT snapshot rows carry this
         * multi-line payload instead of the thin "synthetic N" placeholder, so scroll
         * jank is measured against paragraph + fence rows, not one-liners.
         */
        var bulkAssistantText: String? = null

        /** When true (with snapshotRowsWindowShape), the window reports firstRowId=1 ⇒ more history above. */
        var snapshotWindowTruncated: Boolean = false
        /** Shuffle fragment envelopes inside each logical message (out-of-order storm). */
        var shuffleFragments: Boolean = false
        /** #228 class: inject one physical envelope > 1 MiB (once). */
        var injectOversizedEnvelope: Boolean = false
        /** S11 class: corrupt one fragment's crc32 value (once). */
        var corruptFragmentCrc: Boolean = false
        /** Close the socket after N outbound logical frames (disconnect storm driver). */
        var disconnectAfterFrames: Int? = null
        /** Server-side delay before each outbound frame (latency storm). */
        var perFrameLatencyMs: Long = 0
        /** Emit v4/connection/flow with this state right after subscribe. */
        var flowStateAfterSubscribe: String? = null
        /** Corpus playback: raw recv-direction messages replayed verbatim after auth. */
        var playbackMessages: List<JsonObject> = emptyList()
        /** When false the mock never acks pair_status_query (watchdog fault class). */
        var ackHeartbeats: Boolean = true
        /** S5/S6 class: refuse WS upgrades (desktop offline / relay unavailable). */
        var rejectConnections: Boolean = false
        /** §7.4.4 CAS enforcement on the command plane (Phase 5 S16). */
        var enforceCas: Boolean = true
        /** The mock's current revision for CAS decisions + acks. */
        var currentRevision: Long = 7

        // ---------------------------------------------------------------- Phase 6
        /** Task-list bootstrap override (null = the single synthetic default task). */
        var bootstrapTasks: List<MockTask>? = null

        // ---------------------------------------------------------------- Phase 6 field-fix
        /**
         * Production-faithful bridge strictness (default FALSE so the historical
         * suites keep their lenient mock): when TRUE, a `workspace-bridge-open`
         * WITHOUT a resolvable `taskId` is rejected with the verified production
         * error shape (workspace-bridge-error session-not-found) — the observed
         * production behavior that broke real-phone pairing (Phase 6 field finding).
         */
        var bridgeRequireTaskId: Boolean = false
        /**
         * Production-faithful CHANNEL routing (round-3 field finding, default TRUE):
         * conversation-plane calls (helloConversationV4 …) are answered ONLY after an
         * `onDynamicConversationFrame` EventListen carrying a WORKSPACE-SCOPE arg
         * ({workspacePath, workspaceIdentity?}). Unscoped calls are met with SILENCE
         * — exactly the production behavior that surfaced as the real-phone
         * `helloConversationV4 timed out after 10000ms` while the lenient mock
         * answered everything.
         */
        var requireScopedListen: Boolean = true
        /** One-shot bridge-error queue: each open pops the next reason → error response. */
        val bridgeErrorReasons: ArrayDeque<String> = ArrayDeque()
        /**
         * Round-5 field fidelity: bootstrap-view OVERRIDES (the ACTIVE workspace/task
         * is connection-scoped desktop state — tests flip these between connections
         * to prove the client re-discovers instead of bridging with stale values).
         */
        @Volatile var bootstrapActiveWorkspaceKey: String? = null
        @Volatile var bootstrapActiveTaskId: String? = null
        /**
         * Round-6 field fidelity: serve an EMPTY bootstrap view (tasks=0,
         * workspaces=0) — what a RESTARTING desktop answers between its relay
         * reconnect and its workspace reload (observed live).
         */
        @Volatile var bootstrapEmptyView: Boolean = false

        // ---------------------------------------------------------------- Phase 7
        /**
         * Emit the verified A-side state keys in snapshots (docs/protocol/v4-wire.md
         * §9): control / queue / pendingInteractions / inputRouting / revision.
         * Default FALSE keeps the historical suites' bare snapshots unchanged.
         */
        var emitStateKeys: Boolean = false
        // ---------------- Phase 2 (§11.1) ----------------
        /** Scriptable `usage` state key (sessionUsageStateSchema shape) riding the
         *  snapshot state block when [emitStateKeys] is on (GAP-1/GAP-2). */
        var usageState: JsonObject? = null
        // ---------------- Phase 2 (§11.2) ----------------
        /** Scriptable workspace-config catalog (workspaceConfigStateSchema shape):
         *  pushed as the initial frame when a workspace-config topic is
         *  subscribed; null = no frame (honest empty catalog). */
        var workspaceConfigState: JsonObject? = null
        // ---------------- Phase 9 (§43.2-9) ----------------
        /** Scriptable per-turn fileChanges results (one-shot FIFO; §10.1). */
        val conversationFileChanges: ArrayDeque<JsonObject> = ArrayDeque()

        // ---------------- Phase 3 viewers (GAP-5..9; v4-wire.md §12) ----------------
        /** Scriptable app usage snapshot (getAppUsageStats result; null = typed fault). */
        var appUsageStats: JsonObject? = null
        /** Scriptable automations (listAutomations result items; null = typed fault). */
        var listAutomations: JsonArray? = null
        /** Phase 4B (A3.2): scripted run history; null = the run-history surface is unscripted (typed fault). */
        var automationRuns: JsonArray? = null
        /** Phase 4C (D2): scripted goal (with verifications) + todo keys (null = absent). */
        var goalState: JsonObject? = null
        var planState: JsonObject? = null
        /** Phase 4C (C2): slash-panel extras — commands.list result (null = typed fault). */
        var commandsList: JsonObject? = null
        /** subagents.list result (null = typed fault). */
        var subagentsList: JsonObject? = null
        /** Scriptable off-peak tasks (off-peak-task channel list items; null = typed fault). */
        var offPeakTaskList: JsonArray? = null
        /** Scriptable plugins list (listPlugins result; null = typed fault). */
        var listPlugins: JsonObject? = null
        /** Scriptable skills reference catalog (getSkillReferenceCatalog result; null = typed fault). */
        var skillReferenceCatalog: JsonObject? = null
        /** Scriptable MCP server statuses (listMcpServerStatuses result; null = typed fault). */
        var mcpServerStatuses: JsonObject? = null

        // ---------------- Phase 4 coding-plan quota plane (GAP-3/4; ADR-020, v4-wire.md §13) ----------------
        /** Scriptable entitlement snapshot (usage-stats getEntitlementSnapshot result; null = typed fault). */
        var usageEntitlement: JsonObject? = null
        /** Scriptable reset status (getCodingPlanResetStatus result; null = typed fault). */
        var codingPlanResetStatus: JsonObject? = null
        /** Scriptable opportunity evaluation (requestCodingPlanResetOpportunity result; null = typed fault). */
        var resetOpportunity: JsonObject? = null
        /** Scriptable reset use (useCodingPlanReset result; null = typed fault). */
        var resetUse: JsonObject? = null
        /** FIFO of reset statuses served to the phone (the desktop's post-use
         *  reconciliation polls until usedAt appears — a queue scripts the
         *  pre-consume → confirmed progression; falls back to [codingPlanResetStatus]). */
        val resetStatusQueue: ArrayDeque<JsonObject> = ArrayDeque()
        /** Recorded usage-stats promise calls: (method, argKind "arr"|"obj"|"other", unwrapped params).
         *  The GAP-3 wire test pins the §7 ARRAY-wrap convention through this. */
        val usageStatsCalls = java.util.concurrent.CopyOnWriteArrayList<Triple<String, String, JsonObject>>()
        /** Read marker (markCodingPlanResetHistoryRead) arrival count. */
        val resetHistoryReadCount = java.util.concurrent.atomic.AtomicInteger(0)
        /** Channels the mock REFUSES (authorization-refused fallback fixtures, §13.3). */
        var refuseChannels: Set<String> = emptySet()
        /** Reject attachment transactions with the verified oversize/grammar faults. */
        var rejectAttachments: Boolean = false
        /** Scriptable git channel fixtures (shared/src/git.ts shapes). */
        var gitSummary: JsonObject? = null
        var gitChanges: JsonArray? = null
        var gitDiff: JsonObject? = null
        var gitCommits: JsonArray? = null
        var gitHasMore: Boolean = false
        /** Scriptable file channel fixtures (protocol.ts shapes). */
        val fileTree: HashMap<String, JsonArray> = HashMap()
        val fileText: HashMap<String, String> = HashMap()
        val fileBytes: HashMap<String, ByteArray> = HashMap()
        /** Scriptable model-selection view (phase9-channels.md §4). */
        var modelSelection: JsonObject? = null
        /** Initial session `config` echo (§10.3); command effects update + patch it. */
        var sessionConfig: JsonObject? = null
        /** Scripted terminal output (pushed on onDynamicData listen). */
        val terminalOutput: ArrayDeque<String> = ArrayDeque()
        /** Verified conversationSnapshotSchema.control (subset the client consumes). */
        @Volatile var controlCanStop: Boolean = false
        @Volatile var controlStopState: String = "idle"
        /** Verified queueStateSchema (display + edit sources). */
        @Volatile var queueItems: List<QueueItem>? = null
        /** Verified inputRoutingSchema.mode. */
        @Volatile var inputRoutingMode: String = "startNow"
        /** Pending open interaction stamped into snapshots + state (Q11 shape). */
        @Volatile var pendingPermission: PendingPermission? = null
        @Volatile var pendingUserInput: PendingUserInput? = null
        /**
         * Command side effects (Phase 7 E2E): accepted commands mutate the session —
         * sendText appends authoritative rows, resolveInteraction clears the pending
         * interaction + flips its toolCall row, stop flips control, queue ops patch
         * the queue (each with a revision bump). Default FALSE (Phase 2 suites keep
         * their plain-ack contract).
         */
        var commandEffects: Boolean = false
        /** One-shot ack overrides (stale/rejected/failed) — popped in send order. */
        val commandAckOverrides: ArrayDeque<String> = ArrayDeque()
        /**
         * Phase 4A rig leg (E3.2+I1+N5, MOCK_ROW_ACTIONS=1): the LAST assistant row of
         * every snapshot carries `actions:{canFork,canRetry}` (the product projection
         * sets them on the latest retryable row) and a completed turnHeader carries
         * `fileChanges` + `actions.canRewindFiles` — so the phone's flag-gated row
         * actions render and their commands reach [applyCommandEffect].
         */
        var rowActionsDemo: Boolean = false

        // ---------------- Phase 10 (direct surface, docs/protocol/phase10-direct.md) ----------------
        /** Simulate the 401 token gate on /api/server-info + /ws (auth-class fixtures). */
        var directRejectToken: Boolean = false
        /** Scripted server-info workspaces (default = the single synthetic workspace). */
        var directWorkspaces: List<Pair<String, String>>? = null
        /** Scripted zcode-task.listTasks result rows (ZCodeTaskMeta subset). */
        var directTasks: List<JsonObject> = emptyList()
        /** One-shot direct-socket close after N binary frames (loss class). */
        var directDisconnectAfterFrames: Int? = null
    }

    /** Verified pendingInteraction permission payload (snapshot.ts §9). */
    data class PendingPermission(
        val interactionId: String,
        val toolName: String = "Bash",
        val summary: String = "Run a synthetic command?",
        val options: List<String> = listOf("allowOnce", "allowAlways", "deny"),
    )

    /** Verified pendingInteraction userInput payload (snapshot.ts §9). */
    data class PendingUserInput(
        val interactionId: String,
        val prompt: String = "Synthetic question?",
        /** optionId list; empty + freeText=true = free-form ask. */
        val options: List<String> = listOf(),
        val freeText: Boolean = true,
    )

    /** Verified queueItemSchema display subset (queueItemId/text/kind/dispatch.state). */
    data class QueueItem(val queueItemId: String, val text: String, val kind: String = "sendText", val dispatchState: String = "queued")

    /**
     * Phase 6: a task row in bootstrap/workspace-list payloads. Production shape
     * (taskId/displayStatus/timestamps — live corpus `live-subscribe-16`) vs the
     * simplified legacy shape ({id,title,status}) that earlier fixtures used; the
     * client parser must tolerate BOTH (INV-8).
     */
    data class MockTask(
        val taskId: String,
        val title: String,
        val displayStatus: String = "running",
        val workspacePath: String = "/synthetic/ws",
        val workspaceLabel: String = "Synthetic Workspace",
        // Recency-relative (report F5): the store's stale sweep deletes rows older
        // than 7 days inside replaceAll — hardcoded 2023 timestamps made every
        // scripted task vanish from Room the moment it was written.
        val createdAtMs: Long = System.currentTimeMillis() - 5 * 60 * 1000,
        val updatedAtMs: Long = System.currentTimeMillis() - 60 * 1000,
        val productionShape: Boolean = true,
    ) {
        internal fun toJson(): JsonObject = if (productionShape) {
            buildJsonObject {
                put("taskId", JsonPrimitive(taskId))
                put("title", JsonPrimitive(title))
                put("displayStatus", JsonPrimitive(displayStatus))
                put("workspacePath", JsonPrimitive(workspacePath))
                put("workspaceLabel", JsonPrimitive(workspaceLabel))
                put("workspaceKind", JsonPrimitive("local"))
                put("createdAt", JsonPrimitive(createdAtMs))
                put("updatedAt", JsonPrimitive(updatedAtMs))
            }
        } else {
            buildJsonObject {
                put("id", JsonPrimitive(taskId))
                put("title", JsonPrimitive(title))
                put("status", JsonPrimitive(displayStatus))
            }
        }
    }

    enum class AuthScenario { HAPPY, WRONG_PROOF, UNKNOWN_SID }

    data class MockWorkspace(
        val workspaceKey: String = "synthetic-ws-one",
        val workspacePath: String = "/synthetic/ws-one",
        val workspaceIdentity: String? = null,
        val sessionId: String = "synth-task-1",
        val desktopAppVersion: String = "3.14.3",
        val rowCount: Int = 8,
    )

    sealed class ServerEvent {
        data class AuthCompleted(val role: String) : ServerEvent()
        data class AuthRejected(val reason: String) : ServerEvent()
        data class TerminalKicked(val reason: String) : ServerEvent()
        data class BridgeOpened(val workspaceKey: String, val recoveryId: String?) : ServerEvent()
        data class BridgeError(val reason: String) : ServerEvent()
        data class LogicalFrameFromClient(val messageSeq: Long, val bytes: Int) : ServerEvent()
        data class CommandReceived(val type: String, val commandId: String) : ServerEvent()
    }

    val script = Script()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 256)
    val eventsFlow: SharedFlow<ServerEvent> = events

    private val server = mockwebserver3.MockWebServer()

    /** The socket that currently owns the terminal slot (server-initiated sends). */
    @Volatile private var terminalSocket: okhttp3.WebSocket? = null

    private val outSeq = AtomicLong(0)
    private val outMessageSeq = AtomicLong(0)
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    /** Recent outbound logical envelopes — replayed when a bridge re-opens with recoveryId. */
    private val recentOutbound = ArrayDeque<String>()

    private val dispatcher = object : mockwebserver3.Dispatcher() {
        override fun dispatch(request: mockwebserver3.RecordedRequest): mockwebserver3.MockResponse {
            if (script.rejectConnections) {
                // S5/S6 fault class: connect succeeds at TCP but the upgrade is refused —
                // the unreachable-endpoint shape.
                return mockwebserver3.MockResponse.Builder().code(503).build()
            }
            if (mode == Mode.DIRECT) return dispatchDirect(request)
            return mockwebserver3.MockResponse.Builder().webSocketUpgrade(object : okhttp3.WebSocketListener() {
                override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                    val obj = try {
                        json.parseToJsonElement(text).jsonObject
                    } catch (e: Exception) {
                        return
                    }
                    route(webSocket, obj)
                }
            }).build()
        }
    }

    // -------------------------------------------------------------- direct surface
    // docs/protocol/phase10-direct.md §1–§3 (verified shapes, packages/server http.ts).
    // The wire: binary WS messages = 13-byte SocketProtocol frames (id/ack zero),
    // payload = one serialized Channel frame; server sends {Initialize} on open.

    private fun requestToken(request: mockwebserver3.RecordedRequest): String? {
        request.url.queryParameter("token")?.let { return it }
        val cookie = request.headers["Cookie"] ?: return null
        for (part in cookie.split(";")) {
            val kv = part.trim()
            if (kv.startsWith("zcode_lite_token=")) return kv.removePrefix("zcode_lite_token=")
        }
        return null
    }

    private fun tokenValid(request: mockwebserver3.RecordedRequest): Boolean {
        if (script.directRejectToken) return false
        return requestToken(request) == serverToken
    }

    private fun dispatchDirect(request: mockwebserver3.RecordedRequest): mockwebserver3.MockResponse {
        val path = request.url.encodedPath
        return when {
            path == "/api/server-info" -> {
                if (!tokenValid(request)) {
                    mockwebserver3.MockResponse.Builder()
                        .code(401)
                        .addHeader("Content-Type", "application/json")
                        .body("""{"error":"Unauthorized"}""")
                        .build()
                } else {
                    mockwebserver3.MockResponse.Builder()
                        .code(200)
                        .addHeader("Content-Type", "application/json")
                        .addHeader(
                            "Set-Cookie",
                            "zcode_lite_token=$serverToken; Path=/; HttpOnly; SameSite=Lax",
                        )
                        .body(serverInfoJson().toString())
                        .build()
                }
            }
            path == "/ws" -> {
                if (!tokenValid(request)) {
                    mockwebserver3.MockResponse.Builder()
                        .code(401)
                        .body("""{"error":"Unauthorized"}""")
                        .build()
                } else {
                    mockwebserver3.MockResponse.Builder().webSocketUpgrade(object : okhttp3.WebSocketListener() {
                        override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                            terminalSocket = webSocket
                            conversationScopeRegistered = false // scope gates the conversation plane
                            scope.launch { events.emit(ServerEvent.AuthCompleted("direct-token")) }
                            sendBinaryFrame(webSocket, initializeBytes())
                        }

                        override fun onMessage(webSocket: okhttp3.WebSocket, bytes: okio.ByteString) {
                            handleDirectWire(webSocket, bytes.toByteArray())
                        }
                    }).build()
                }
            }
            else -> mockwebserver3.MockResponse.Builder().code(404).build()
        }
    }

    private fun serverInfoJson(): JsonObject {
        val workspaces = script.directWorkspaces ?: listOf(workspace.workspacePath to (workspace.workspaceKey))
        return buildJsonObject {
            put("serverId", JsonPrimitive("mock-direct-server"))
            put("version", JsonPrimitive(workspace.desktopAppVersion))
            put("protocolVersion", JsonPrimitive(1))
            put("authRequired", JsonPrimitive(true))
            put("workspaces", JsonArray(workspaces.map { (p, l) ->
                buildJsonObject { put("path", JsonPrimitive(p)); put("label", JsonPrimitive(l)) }
            }))
            put("capabilities", buildJsonObject {
                put("desktopContinuous", JsonPrimitive(true))
                put("websocketRpc", JsonPrimitive(true))
                put("processResourceTelemetry", JsonPrimitive(true))
            })
        }
    }

    /** Server→client direction: one logical Channel frame per SocketProtocol frame. */
    private fun sendBinaryFrame(webSocket: okhttp3.WebSocket, channelFrame: ByteArray) {
        val framed = com.zcode.android.core.protocol.socket.SocketFrame.writeMessage(
            com.zcode.android.core.protocol.socket.SocketFrame.ProtocolMessage(
                com.zcode.android.core.protocol.socket.SocketFrame.ProtocolMessageType.Regular, 0, 0, channelFrame,
            ),
        )
        try {
            webSocket.send(okio.ByteString.of(*framed))
        } catch (e: Exception) {
            // closed socket — mock-side noise
        }
        val limit = script.directDisconnectAfterFrames
        if (limit != null && directFramesSent + 1 >= limit) {
            script.directDisconnectAfterFrames = null
            webSocket.close(1011, "mock direct disconnect storm")
        }
        directFramesSent++
    }

    @Volatile private var directFramesSent: Int = 0

    private fun handleDirectWire(webSocket: okhttp3.WebSocket, bytes: ByteArray) {
        val frame = try {
            parseSocketFrame(bytes)
        } catch (e: Exception) {
            return
        } ?: return
        if (frame.second.isNotEmpty()) {
            dispatchChannelFrame(frame.second)
        }
    }

    /** Tolerant SocketProtocol parse (single-frame messages; stream reassembly not needed on WS). */
    private fun parseSocketFrame(bytes: ByteArray): Pair<Int, ByteArray>? {
        val sf = com.zcode.android.core.protocol.socket.SocketFrame
        if (bytes.size < sf.HEADER_SIZE) return null
        val (type, _, length) = sf.parseHeader(bytes)
        if (bytes.size < sf.HEADER_SIZE + length) return null
        return type to bytes.copyOfRange(sf.HEADER_SIZE, sf.HEADER_SIZE + length)
    }

    fun start(): String {
        server.dispatcher = dispatcher
        server.start()
        return server.url("/ws").toString()
    }

    // ------------------------------------------------------- raw WS server (report E9)
    // mockwebserver3's webSocketUpgrade MASKS server frames (RFC 6455 §5.1 violation,
    // probe-confirmed) — OkHttp clients correctly refuse with close 1006, so live
    // device E2E can never complete a channel call through MockWebServer. startRaw
    // serves the same routes through RawWsServer, which writes unmasked frames.

    @Volatile private var rawServer: RawWsServer? = null

    fun startRaw(port: Int): String {
        val raw = RawWsServer(port)
        raw.start(::rawRoute)
        rawServer = raw
        return "ws://127.0.0.1:$port/ws"
    }

    internal fun rawRoute(path: String, query: Map<String, String>, headers: Map<String, String>): RawWsServer.Route {
        if (script.rejectConnections) return RawWsServer.Route.Http(503)
        if (mode == Mode.DIRECT) return rawRouteDirect(path, query, headers)
        // RELAY: every request upgrades (same semantics as the MockWebServer dispatcher).
        return RawWsServer.Route.Upgrade(
            onOpen = { },
            onText = { ws, text ->
                runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()?.let { route(ws, it) }
            },
        )
    }

    private fun rawRouteDirect(path: String, query: Map<String, String>, headers: Map<String, String>): RawWsServer.Route {
        val token = query["token"] ?: headers["cookie"]?.split(";")?.map { it.trim() }
            ?.firstOrNull { it.startsWith("zcode_lite_token=") }?.removePrefix("zcode_lite_token=")
        val valid = !script.directRejectToken && token == serverToken
        return when (path) {
            "/api/server-info" -> if (!valid) {
                RawWsServer.Route.Http(401, contentType = "application/json", body = """{"error":"Unauthorized"}""")
            } else {
                RawWsServer.Route.Http(
                    200,
                    contentType = "application/json",
                    body = serverInfoJson().toString(),
                    setCookie = "zcode_lite_token=$serverToken; Path=/; HttpOnly; SameSite=Lax",
                )
            }
            "/ws" -> if (!valid) {
                RawWsServer.Route.Http(401, body = """{"error":"Unauthorized"}""")
            } else {
                RawWsServer.Route.Upgrade(
                    onOpen = { ws ->
                        terminalSocket = ws
                        conversationScopeRegistered = false
                        scope.launch { events.emit(ServerEvent.AuthCompleted("direct-token")) }
                        sendBinaryFrame(ws, initializeBytes())
                    },
                    onBytes = { ws, bytes -> handleDirectWire(ws, bytes) },
                )
            }
            else -> RawWsServer.Route.Http(404)
        }
    }

    fun stop() {
        rawServer?.stop()
        terminalSocket?.let { runCatching { it.close(1000, "mock stop") } }
        try {
            server.close()
        } catch (e: AssertionError) {
            // Test-tooling tolerance: MockWebServer gives up draining lingering sockets
            // after the client already closed its side; no assertion rides on this.
        }
        terminalSocket = null
    }

    val url: String get() = server.url("/ws").toString()

    // ------------------------------------------------------------------ connection

    @Volatile private var pairStatus: String = "matched"
    @Volatile private var nonceIssued: String? = null
    @Volatile private var expectedProof: String? = null

    private fun route(ws: okhttp3.WebSocket, obj: JsonObject) {
        when ((obj["type"] as? JsonPrimitive)?.content) {
            "auth_init" -> handleAuthInit(ws, obj)
            "auth_response" -> handleAuthResponse(ws, obj)
            "pair_status_query" -> if (script.ackHeartbeats) sendTo(
                ws, mapOf("type" to JsonPrimitive("pair_status_ack"), "pair_status" to JsonPrimitive(pairStatus))
            ) else Unit
            "device_register_init" -> sendTo(
                ws, mapOf("type" to JsonPrimitive("device_register_ack"), "device_sid" to JsonPrimitive(expectedSid))
            )
            "data" -> if (ws === terminalSocket) handleData(ws, obj) else Unit
            else -> Unit // desktop dispatch parity: unknown top-level types ignored
        }
    }

    private fun handleAuthInit(ws: okhttp3.WebSocket, obj: JsonObject) {
        val role = obj.str("role")
        val sid = obj.str("device_sid")
        if (script.authScenario == AuthScenario.UNKNOWN_SID || sid != expectedSid) {
            sendTo(ws, mapOf("type" to JsonPrimitive("error"), "code" to JsonPrimitive("AUTH_FAILED"), "message" to JsonPrimitive("mock: unknown device_sid")))
            scope.launch { events.emit(ServerEvent.AuthRejected("unknown_sid")) }
            return
        }
        nonceIssued = UUID.randomUUID().toString().replace("-", "").take(24)
        expectedProof = hmacProof(passHash, nonceIssued!!, role, sid)
        sendTo(ws, mapOf("type" to JsonPrimitive("auth_challenge"), "nonce" to JsonPrimitive(nonceIssued)))
    }

    private fun handleAuthResponse(ws: okhttp3.WebSocket, obj: JsonObject) {
        val proof = obj.str("proof")
        if (script.authScenario == AuthScenario.WRONG_PROOF || proof != expectedProof) {
            sendTo(ws, mapOf("type" to JsonPrimitive("error"), "code" to JsonPrimitive("AUTH_FAILED"), "message" to JsonPrimitive("mock: proof mismatch")))
            scope.launch { events.emit(ServerEvent.AuthRejected("proof_mismatch")) }
            return
        }
        // Single-terminal rule: a second authenticated terminal kicks the first (Q10).
        val prior = terminalSocket
        if (prior != null && prior !== ws && script.kickFirstTerminalOnSecond) {
            prior.send("""{"type":"error","code":"KICKED","message":""}""")
            prior.close(1000, "kicked by mock")
            scope.launch { events.emit(ServerEvent.TerminalKicked("second terminal authenticated")) }
        }
        terminalSocket = ws
        conversationScopeRegistered = false // fresh connection: scope must be re-registered
        pairStatus = "matched"
        sendTo(ws, mapOf("type" to JsonPrimitive("auth_ack"), "pair_status" to JsonPrimitive("matched")))
        sendTo(ws, mapOf("type" to JsonPrimitive("pair_status_ack"), "pair_status" to JsonPrimitive("matched")))
        scope.launch { events.emit(ServerEvent.AuthCompleted("terminal")) }
    }

    /** Queue recv-direction messages (corpus replay) — delivered by [startPlayback]. */
    fun queuePlayback(messages: List<JsonObject>) {
        playbackQueue.clear()
        playbackQueue.addAll(messages)
    }

    private val playbackQueue = ArrayDeque<JsonObject>()

    /** Deterministic corpus playback trigger (tests drive it after connect completes). */
    fun startPlayback() {
        val ws = terminalSocket ?: return
        val messages = playbackQueue.toList()
        playbackQueue.clear()
        for (m in messages) sendTo(ws, raw = m.toString())
    }

    private fun handleData(ws: okhttp3.WebSocket, obj: JsonObject) {
        val payload = obj["payload"] as? JsonObject ?: return
        when ((payload["zcode_type"] as? JsonPrimitive)?.content) {
            "rpc-frame" -> handleRpcFrame(payload)
            "rpc-frame-ack" -> Unit // client acking our logical frames
            "bootstrap-request" -> sendBootstrapResponse(ws, payload.str("requestId"))
            "workspace-list-request" -> sendWorkspaceListResponse(ws, payload.str("requestId"))
            "workspace-bridge-open" -> handleBridgeOpen(ws, payload)
            else -> Unit
        }
    }

    // ------------------------------------------------------------------ rpc-frame plane

    /** Inbound client frames are decoded WITH THE REAL CODEC (self-test grounding). */
    private val inboundAssembler = RelayFrameCodec.Assembler()

    private fun handleRpcFrame(payload: JsonObject) {
        val envelopeJson = RelayFrameCodec.encodeEnvelope(payload.toString())
        val results = try {
            inboundAssembler.handleEnvelope(envelopeJson)
        } catch (e: Exception) {
            return
        }
        for (r in results) {
            when (r) {
                is RelayFrameCodec.Reassembled.Message -> {
                    scope.launch { events.emit(ServerEvent.LogicalFrameFromClient(r.messageSeq, r.payload.size)) }
                    sendAck(payload.str("bridgeSessionId"), r.messageSeq)
                    if (script.duplicateInboundAcks) sendAck(payload.str("bridgeSessionId"), r.messageSeq)
                    dispatchChannelFrame(r.payload)
                }
                else -> Unit
            }
        }
    }

    private fun sendAck(bridgeSessionId: String, ackMessageSeq: Long) {
        val ackJson = json.encodeToString(
            com.zcode.android.core.protocol.relay.RpcFrameAckOut.serializer(),
            com.zcode.android.core.protocol.relay.RpcFrameAckOut(
                bridgeSessionId = bridgeSessionId,
                ackMessageSeq = ackMessageSeq,
            ),
        )
        sendRaw(RelayFrameCodec.encodeEnvelope(ackJson))
    }

    /** Server→client: fragment one logical message and send (per-direction counters from 1). */
    /** Test/diagnostic: logical frames handed to the wire (server→client). */
    @Volatile var logicalFramesSent: Int = 0
        private set

    private fun sendLogicalFrame(bytes: ByteArray) {
        val ws = terminalSocket ?: return
        logicalFramesSent++
        if (mode == Mode.DIRECT) {
            // Direct surface (§11.4): raw binary SocketProtocol frames — no relay
            // envelopes, no fragments, no acks (phase10-direct.md §3).
            if (script.perFrameLatencyMs > 0) Thread.sleep(script.perFrameLatencyMs)
            sendBinaryFrame(ws, bytes)
            return
        }
        val seq = outSeq.addAndGet(1)
        val messageSeq = outMessageSeq.addAndGet(1)
        if (script.perFrameLatencyMs > 0) Thread.sleep(script.perFrameLatencyMs)
        var envelopes = RelayFrameCodec.fragment(currentBridge(), 0, null, bytes, seq, messageSeq)
            .map { it.envelopeJson }
        if (script.shuffleFragments && envelopes.size > 1) envelopes = envelopes.shuffled()
        if (script.corruptFragmentCrc && envelopes.isNotEmpty()) {
            script.corruptFragmentCrc = false
            envelopes = envelopes.toMutableList().also {
                it[0] = it[0].replace(Regex("\"value\":\"[0-9a-f]{8}\""), "\"value\":\"00000000\"")
            }
        }
        if (script.injectOversizedEnvelope) {
            script.injectOversizedEnvelope = false
            sendRaw(oversizedEnvelope(seq + 1, messageSeq + 1))
            outSeq.addAndGet(1)
            outMessageSeq.addAndGet(1)
        }
        var sends = 0
        for (e in envelopes) {
            sendRaw(e)
            recentOutbound.addLast(e)
            if (recentOutbound.size > 32) recentOutbound.removeFirst()
            sends++
            if (script.duplicateEvery > 0 && sends % script.duplicateEvery == 0) sendRaw(e)
        }
        val limit = script.disconnectAfterFrames
        if (limit != null && messageSeq >= limit) {
            script.disconnectAfterFrames = null
            ws.close(1011, "mock disconnect storm")
        }
    }

    /** #228-class fixture: a physical envelope exceeding the 1 MiB budget. */
    private fun oversizedEnvelope(seq: Long, messageSeq: Long): String {
        val big = "A".repeat(RelayLimits.RELAY_MAX_PHYSICAL_FRAME_BYTES + 4096)
        return """{"type":"data","payload":{"zcode_type":"rpc-frame","bridgeSessionId":"${currentBridge()}","seq":$seq,""" +
            """"messageSeq":$messageSeq,"fragmentIndex":0,"fragmentCount":1,"messageBytes":${big.length},""" +
            """"checksum":{"algorithm":"crc32","value":"00000000"},"dataBase64":"$big"},"client_ts":0}"""
    }

    // ------------------------------------------------------------------ channel (v4)

    @Volatile private var conversationListenerId: Int? = null
    @Volatile private var conversationScopeRegistered: Boolean = false
    /** Bridge identity of the most recent workspace-bridge-open (rotation tests). */
    @Volatile var lastBridgeSessionId: String? = null
        private set
    /** Round-5: what the most recent bridge-open ASKED FOR (fresh-discovery tests). */
    @Volatile var lastBridgeWorkspaceKey: String? = null
        private set
    @Volatile var lastBridgeTaskId: String? = null
        private set
    @Volatile private var flowListenerId: Int? = null

    /** True once a client registers a v4/connection/flow EventListen (must NEVER happen — crashes the production host). */
    @Volatile var sawConnectionFlowListen: Boolean = false
        private set

    /** Workspace paths seen in onDynamicConversationFrame EventListen scopes (order preserved). */
    val listenScopesSeen = java.util.concurrent.ConcurrentLinkedQueue<String>()
    @Volatile private var currentBridgeId: String? = null
    private var subscriptionId = "sub-mock-1"
    private var logEpoch = "epoch-mock-1"

    /** Phase 5: per-session delta-frame log for resync replay + the seq head. */
    data class DeltaFrame(val fromSeq: Long, val toSeq: Long, val ops: JsonArray)
    private val opLog = LinkedHashMap<String, ArrayDeque<DeltaFrame>>()
    private val seqHeads = LinkedHashMap<String, Long>()

    private fun currentBridge(): String = currentBridgeId ?: "bridge-unopened"

    // ------------------------------------------------------------ Phase 7 state block

    /** The CAS revision currency (§9): bumped on every command effect. */
    private val stateRevision = AtomicLong(100)

    /** Verified snapshot A-side state object (§9) per the script knobs. */
    private fun stateBlock(): JsonObject? {
        if (!script.emitStateKeys) return null
        val control = buildJsonObject {
            put("phase", JsonPrimitive(if (script.controlCanStop) "running" else "idle"))
            put("sessionEnded", JsonPrimitive(false))
            put("canStop", JsonPrimitive(script.controlCanStop))
            put("stopState", JsonPrimitive(script.controlStopState))
            put("activeWorks", JsonArray(emptyList()))
        }
        val routing = buildJsonObject {
            put("mode", JsonPrimitive(script.inputRoutingMode))
        }
        val queue = buildJsonObject {
            put("items", JsonArray(script.queueItems.orEmpty().map { qi ->
                buildJsonObject {
                    put("queueItemId", JsonPrimitive(qi.queueItemId))
                    put("text", JsonPrimitive(qi.text))
                    put("kind", JsonPrimitive(qi.kind))
                    put("dispatch", buildJsonObject { put("state", JsonPrimitive(qi.dispatchState)) })
                }
            }))
            put("autoDrain", JsonPrimitive(true))
        }
        val configBlock = script.sessionConfig
        val pending = JsonArray(listOfNotNull(
            script.pendingPermission?.let { pp ->
                buildJsonObject {
                    put("interactionId", JsonPrimitive(pp.interactionId))
                    put("kind", JsonPrimitive("permission"))
                    put("anchorRowId", JsonNull)
                    put("createdAt", JsonPrimitive(System.currentTimeMillis().toDouble()))
                    put("payload", buildJsonObject {
                        put("kind", JsonPrimitive("permission"))
                        put("toolCallId", JsonPrimitive("tool-approval-${pp.interactionId}"))
                        put("toolName", JsonPrimitive(pp.toolName))
                        put("summary", JsonPrimitive(pp.summary))
                        put("detail", buildJsonObject { put("command", JsonPrimitive("echo synthetic")) })
                        put("options", JsonArray(pp.options.map { id ->
                            buildJsonObject {
                                put("optionId", JsonPrimitive(id))
                                put("label", JsonPrimitive(id))
                                put("kind", JsonPrimitive(id))
                            }
                        }))
                    })
                }
            },
            script.pendingUserInput?.let { pu ->
                buildJsonObject {
                    put("interactionId", JsonPrimitive(pu.interactionId))
                    put("kind", JsonPrimitive("userInput"))
                    put("anchorRowId", JsonNull)
                    put("createdAt", JsonPrimitive(System.currentTimeMillis().toDouble()))
                    put("payload", buildJsonObject {
                        put("kind", JsonPrimitive("userInput"))
                        put("prompt", JsonPrimitive(pu.prompt))
                        put("freeText", JsonPrimitive(pu.freeText))
                        put("options", JsonArray(pu.options.map { id ->
                            buildJsonObject { put("optionId", JsonPrimitive(id)); put("label", JsonPrimitive(id)) }
                        }))
                    })
                }
            },
        ))
        return buildJsonObject {
            put("revision", JsonPrimitive(stateRevision.get()))
            put("control", control)
            put("inputRouting", routing)
            put("queue", queue)
            put("pendingInteractions", pending)
            // Phase 9 (§10.3): the session `config` echo when scripted (snapshot AND
            // state.updated patches carry it — the switch commands' reconciliation plane).
            script.sessionConfig?.let { put("config", it) }
            // Phase 2 (§11.1): the usage key when scripted (GAP-1/GAP-2).
            script.usageState?.let { put("usage", it) }
            // Phase 4C (D2): the goal (with its verification timeline) + todo keys.
            script.goalState?.let { put("goal", it) }
            script.planState?.let { put("plan", it) }
        }
    }

    /** Server-side state.updated driver (races, effects) — §9 key-level replacement. */
    fun pushStatePatch(patch: JsonObject) {        val listener = conversationListenerId ?: return
        val topic = "conversation/${workspace.sessionId}"
        val frame = buildJsonObject {
            put("topic", JsonPrimitive(topic))
            put("subscriptionId", JsonPrimitive(subscriptionId))
            put("fromSeq", JsonPrimitive(nextSeq()))
            put("toSeq", JsonPrimitive(seqHeadNow))
            put("sentAt", JsonPrimitive(System.currentTimeMillis().toDouble()))
            put("payload", buildJsonObject {
                put("kind", JsonPrimitive("deltas"))
                put("deltas", JsonArray(listOf(buildJsonObject {
                    put("op", JsonPrimitive("state.updated"))
                    put("patch", patch)
                })))
            })
        }
        val wireFrames = TopicWireFrameCodec.encode(
            frame = frame, deliveryKind = DeliveryKind.ONLINE, topic = topic,
            subscriptionId = subscriptionId, logicalFrameId = "lf-${System.nanoTime()}",
            logicalFrameOrdinal = 1,
        )
        for (wire in wireFrames) {
            val bytes = RpcSerialization.encodeAll(
                listOf(
                    RpcValue.Arr(listOf(RpcValue.Int(ChannelWire.ResponseType.EventFire.id), RpcValue.Int(listener))),
                    RpcValue.Obj(buildJsonObject {
                        put("topic", JsonPrimitive(topic))
                        put("frame", wireJson(wire))
                    }),
                )
            )
            sendLogicalFrame(bytes)
        }
    }

    /**
     * Phase 9 driver: seed an authoritative row that RIDES THE INITIAL SNAPSHOT
     * (appendedRows is the snapshot tail) — used by suites needing turnHeader
     * fileChanges summaries or pre-subscribe rows. Returns the rowId.
     */
    fun appendRow(kind: RowKind, text: String, extra: JsonObject? = null): Long {
        val row = schemaRow(workspace.sessionId, nextRowId(), kind, text, extra)
        appendedRows[row["rowId"].toString()] = row
        return (row["rowId"] as? JsonPrimitive)?.content?.toLongOrNull() ?: -1L
    }

    /**
     * Phase 2 driver: push a workspace-config frame (snapshot on subscribe;
     * `config.updated` delta via [pushConfigUpdated]). Rides the SAME scoped
     * conversation listener — the shared v4/conversation/frame notification
     * carries all topics (dispatch by topic prefix).
     */
    private fun pushWorkspaceConfigFrame(topic: String, cfgSubscriptionId: String, config: JsonObject, delta: Boolean = false) {
        val listener = conversationListenerId ?: return
        val frame = buildJsonObject {
            put("topic", JsonPrimitive(topic))
            put("subscriptionId", JsonPrimitive(cfgSubscriptionId))
            put("fromSeq", JsonPrimitive(0))
            put("toSeq", JsonPrimitive(1))
            put("sentAt", JsonPrimitive(System.currentTimeMillis().toDouble()))
            put("payload", buildJsonObject {
                if (!delta) {
                    put("kind", JsonPrimitive("snapshot"))
                    put("snapshot", buildJsonObject {
                        put("protocolVersion", JsonPrimitive(1))
                        put("workspaceId", JsonPrimitive(topic.removePrefix("workspace-config/")))
                        put("logEpoch", JsonPrimitive("cfg-epoch-mock-1"))
                        put("config", config)
                    })
                } else {
                    put("kind", JsonPrimitive("deltas"))
                    put("deltas", JsonArray(listOf(buildJsonObject {
                        put("op", JsonPrimitive("config.updated"))
                        put("config", config)
                    })))
                }
            })
        }
        val wireFrames = TopicWireFrameCodec.encode(
            frame = frame, deliveryKind = DeliveryKind.INITIAL, topic = topic,
            subscriptionId = cfgSubscriptionId, logicalFrameId = "lf-${System.nanoTime()}",
            logicalFrameOrdinal = 1,
        )
        for (wire in wireFrames) {
            val bytes = RpcSerialization.encodeAll(
                listOf(
                    RpcValue.Arr(listOf(RpcValue.Int(ChannelWire.ResponseType.EventFire.id), RpcValue.Int(listener))),
                    RpcValue.Obj(buildJsonObject {
                        put("topic", JsonPrimitive(topic))
                        put("frame", wireJson(wire))
                    }),
                )
            )
            sendLogicalFrame(bytes)
        }
    }

    /** Server-side `config.updated` driver (catalog refresh test path). */
    fun pushConfigUpdated(topic: String, config: JsonObject) {
        pushWorkspaceConfigFrame(topic, "sub-mock-cfg-${topic.hashCode()}", config, delta = true)
    }

    /** Row-appended delta driver (command effects; authoritative rows). */
    private fun pushRowAppended(sessionId: String, row: JsonObject) = pushRowDelta(sessionId, "row.appended", row)

    /** Phase 4A: `row.upserted` for in-place authoritative edits (feedback echo, reverted turn). */
    private fun pushRowUpserted(sessionId: String, row: JsonObject) = pushRowDelta(sessionId, "row.upserted", row)

    private fun pushRowDelta(sessionId: String, op: String, row: JsonObject) {
        val listener = conversationListenerId ?: return
        val topic = "conversation/$sessionId"
        val from = nextSeq()
        val frame = buildJsonObject {
            put("topic", JsonPrimitive(topic))
            put("subscriptionId", JsonPrimitive(subscriptionId))
            put("fromSeq", JsonPrimitive(from))
            put("toSeq", JsonPrimitive(seqHeadNow))
            put("sentAt", JsonPrimitive(System.currentTimeMillis().toDouble()))
            put("payload", buildJsonObject {
                put("kind", JsonPrimitive("deltas"))
                put("deltas", JsonArray(listOf(buildJsonObject {
                    put("op", JsonPrimitive(op))
                    put("row", row)
                })))
            })
        }
        val wireFrames = TopicWireFrameCodec.encode(
            frame = frame, deliveryKind = DeliveryKind.ONLINE, topic = topic,
            subscriptionId = subscriptionId, logicalFrameId = "lf-${System.nanoTime()}",
            logicalFrameOrdinal = 1,
        )
        for (wire in wireFrames) {
            val bytes = RpcSerialization.encodeAll(
                listOf(
                    RpcValue.Arr(listOf(RpcValue.Int(ChannelWire.ResponseType.EventFire.id), RpcValue.Int(listener))),
                    RpcValue.Obj(buildJsonObject {
                        put("topic", JsonPrimitive(topic))
                        put("frame", wireJson(wire))
                    }),
                )
            )
            sendLogicalFrame(bytes)
        }
    }

    /**
     * Monotonic per-session seq allocation (deltas frames are half-open (from,to]).
     * The INITIAL SNAPSHOT anchors the head (protocol: snapshots carry the alignment
     * watermark; live deltas continue contiguously from it) — effect pushes continue
     * from there, never fabricating a gap (§15-S10 would hold/escalate on one).
     */
    private var seqHeadNow: Long = 0
    private fun nextSeq(): Long {
        seqHeadNow += 1
        seqHeads[workspace.sessionId] = seqHeadNow
        return seqHeadNow
    }
    /** Effect rows survive into later snapshots + resync replays (authoritative). */
    private val appendedRows = LinkedHashMap<String, JsonObject>()

    // ------------------------------------------------------------ schema-faithful rows

    /**
     * rows.ts-faithful synthetic row (required base fields + kind fields present so the
     * TYPED domain decoder accepts it — Phase 5 projection path decodes strictly and a
     * known-kind violation would fail closed). Synthetic content only (INV-15/16).
     */
    fun schemaRow(sessionId: String, rowId: Long, kind: RowKind, text: String, extra: JsonObject? = null): JsonObject {
        val base = buildJsonObject {
            put("rowId", JsonPrimitive(rowId))
            put("turnId", JsonPrimitive("turn-$sessionId-${(rowId - 1) / 4 + 1}"))
            put("kind", JsonPrimitive(kind.wire))
            put("entityId", JsonPrimitive("ent-$rowId"))
            put("createdAt", JsonPrimitive(System.currentTimeMillis().toDouble() - 300_000 + rowId))
            put("createdAtSeq", JsonPrimitive(rowId))
        }
        val kindFields = when (kind) {
            RowKind.ASSISTANT_TEXT -> buildJsonObject {
                put("assistantResponseId", JsonPrimitive("ar-$sessionId"))
                put("text", JsonPrimitive(text))
                put("state", JsonPrimitive("complete"))
            }
            RowKind.REASONING -> buildJsonObject {
                put("assistantResponseId", JsonPrimitive("ar-$sessionId"))
                put("text", JsonPrimitive(text))
                put("state", JsonPrimitive("complete"))
                put("durationMs", JsonPrimitive(120))
            }
            RowKind.TOOL_CALL -> buildJsonObject {
                put("assistantResponseId", JsonPrimitive("ar-$sessionId"))
                put("toolCallId", JsonPrimitive("tool-$rowId"))
                put("toolName", JsonPrimitive("Bash"))
                // `text` carries "status[:interactionId]" for scripted tool rows
                put("status", JsonPrimitive(text.substringBefore(":")))
                put("inputText", JsonPrimitive("echo synthetic"))
                text.takeIf { it.startsWith("pendingApproval:") }?.let {
                    put("approvalInteractionId", JsonPrimitive(it.substringAfter(":")))
                }
            }
            RowKind.TURN_HEADER -> buildJsonObject {
                put("origin", JsonPrimitive("user"))
                put("state", JsonPrimitive(text)) // terminal state rides `text` for scripted headers
                put("startedAt", JsonPrimitive(System.currentTimeMillis().toDouble() - 300_000 + rowId))
            }
            RowKind.USER_INPUT -> buildJsonObject {
                put("text", JsonPrimitive(text))
                put("origin", JsonPrimitive("typed"))
                put("guided", JsonPrimitive(false))
                put("attachments", JsonArray(emptyList()))
            }
        }
        return JsonObject(base + kindFields + (extra ?: JsonObject(emptyMap())).toMap())
    }

    /** Kinds the mock emits (subset of the verified rows.ts union, all schema-faithful). */
    enum class RowKind(val wire: String) {
        TURN_HEADER("turnHeader"), USER_INPUT("userInput"), ASSISTANT_TEXT("assistantText"),
        REASONING("reasoning"), TOOL_CALL("toolCall"),
    }

    /** S12 driver: authoritative terminal turn state stamped into the NEXT snapshot. */
    @Volatile private var sessionTurnOutcome: String? = null
    fun setSessionTurnOutcome(turnState: String) { sessionTurnOutcome = turnState }

    /** S13 driver: a pending-approval tool call stamped into the NEXT snapshot. */
    @Volatile private var pendingApproval: Pair<String, String>? = null
    fun setPendingApproval(toolName: String, interactionId: String) { pendingApproval = toolName to interactionId }

    /**
     * A3.1 (gaps-and-polish Phase 3D rig leg): one pre-committed image attachment,
     * stamped into the NEXT snapshot as a user-row attachment — the phone can render
     * an image it sent earlier (the read channel answers from [committedAttachments]).
     */
    @Volatile private var scriptedAttachment: Triple<String, String, ByteArray>? = null
    fun setScriptedAttachment(ref: String, mime: String, bytes: ByteArray) {
        scriptedAttachment = Triple(ref, mime, bytes)
    }

    private fun snapshotRows(sessionId: String, count: Int): JsonArray =
        snapshotRows(sessionId, count, script.snapshotRowIdBase)

    private fun snapshotRows(sessionId: String, count: Int, rowIdBase: Long): JsonArray {
        val rows = ArrayList<JsonObject>(count + 2)
        var nextId = rowIdBase
        for (i in 1..count) {
            val kind = if (i % 2 == 0) RowKind.ASSISTANT_TEXT else RowKind.REASONING
            val text = if (kind == RowKind.ASSISTANT_TEXT) {
                script.bulkAssistantText?.let { "$it (row $i)" } ?: "synthetic $i"
            } else {
                "synthetic $i"
            }
            rows.add(schemaRow(sessionId, nextId++, kind, text))
        }
        sessionTurnOutcome?.let { outcome ->
            rows.add(schemaRow(sessionId, nextId++, RowKind.TURN_HEADER, outcome))
        }
        script.demoAssistantMarkdown?.let { md ->
            rows.add(schemaRow(sessionId, nextId++, RowKind.ASSISTANT_TEXT, md))
        }
        pendingApproval?.let { (tool, iid) ->
            rows.add(schemaRow(sessionId, nextId++, RowKind.TOOL_CALL, "pendingApproval:$iid", extra = buildJsonObject {
                put("toolName", JsonPrimitive(tool))
            }))
        }
        scriptedAttachment?.let { (ref, mime, bytes) ->
            val att = MockAttachment(sessionId = sessionId, fileName = "mock.png", mime = mime, totalBytes = bytes.size.toLong())
            att.data.write(bytes)
            synchronized(committedAttachments) { committedAttachments[ref] = att }
            rows.add(schemaRow(sessionId, nextId++, RowKind.USER_INPUT, "here is the screenshot", extra = buildJsonObject {
                put("attachments", JsonArray(listOf(buildJsonObject {
                    put("ref", JsonPrimitive(ref))
                    put("fileName", JsonPrimitive("mock.png"))
                    put("mime", JsonPrimitive(mime))
                    put("bytes", JsonPrimitive(bytes.size))
                    put("previewRef", JsonNull)
                })))
            }))
        }
        if (script.rowActionsDemo) {
            // A completed turn that changed files: the Undo-files gate needs files>0,
            // state != reverted, a terminal turn state AND actions.canRewindFiles.
            rows.add(schemaRow(sessionId, nextId++, RowKind.TURN_HEADER, "completedSuccess", extra = rowActionsTurnExtra("active")))
            // The latest assistant row of the snapshot is the retryable/forkable one.
            val lastAssistant = rows.indexOfLast { it["kind"]?.let { k -> (k as JsonPrimitive).content } == RowKind.ASSISTANT_TEXT.wire }
            if (lastAssistant >= 0) rows[lastAssistant] = JsonObject(rows[lastAssistant] + ("actions" to ROW_ACTIONS_ASSISTANT))
        }
        return JsonArray(rows)
    }

    /** Phase 4A: `actions:{canFork:true,canRetry:true}` — rows.ts only ever sends TRUE keys. */
    private val ROW_ACTIONS_ASSISTANT: JsonObject = buildJsonObject {
        put("canFork", JsonPrimitive(true))
        put("canRetry", JsonPrimitive(true))
    }

    private fun rowActionsTurnExtra(fileState: String): JsonObject = buildJsonObject {
        put("endedAt", JsonPrimitive(System.currentTimeMillis().toDouble()))
        put("activeMs", JsonPrimitive(4200))
        put("fileChanges", buildJsonObject {
            put("additions", JsonPrimitive(5))
            put("deletions", JsonPrimitive(1))
            put("files", JsonPrimitive(2))
            put("state", JsonPrimitive(fileState))
        })
        if (fileState != "reverted") {
            put("actions", buildJsonObject { put("canRewindFiles", JsonPrimitive(true)) })
        }
    }

    // ------------------------------------------------------------ scripted deltas / resync

    /**
     * Push scripted delta frames for a session (S8 out-of-order / S10 gap drivers):
     * frames are logged for resync replay and delivered immediately as deltas topic
     * frames with [deliveryKind].
     */
    fun pushDeltas(sessionId: String, frames: List<DeltaFrame>, deliveryKind: DeliveryKind = DeliveryKind.ONLINE) {
        val log = opLog.getOrPut(sessionId) { ArrayDeque() }
        for (f in frames) {
            log.addLast(f)
            seqHeads[sessionId] = maxOf(seqHeads[sessionId] ?: 0L, f.toSeq)
        }
        emitDeltasFrame(sessionId, frames, deliveryKind)
    }

    private fun emitDeltasFrame(sessionId: String, frames: List<DeltaFrame>, deliveryKind: DeliveryKind) {
        val listener = conversationListenerId ?: return
        for (f in frames) {
            val topic = "conversation/$sessionId"
            val frame = buildJsonObject {
                put("topic", JsonPrimitive(topic))
                put("subscriptionId", JsonPrimitive(subscriptionId))
                put("fromSeq", JsonPrimitive(f.fromSeq))
                put("toSeq", JsonPrimitive(f.toSeq))
                put("sentAt", JsonPrimitive(System.currentTimeMillis().toDouble()))
                put("payload", buildJsonObject {
                    put("kind", JsonPrimitive("deltas"))
                    put("deltas", f.ops)
                })
            }
            val wireFrames = TopicWireFrameCodec.encode(
                frame = frame, deliveryKind = deliveryKind, topic = topic,
                subscriptionId = subscriptionId, logicalFrameId = "lf-${System.nanoTime()}",
                logicalFrameOrdinal = 1,
            )
            for (wire in wireFrames) {
                val bytes = RpcSerialization.encodeAll(
                    listOf(
                        RpcValue.Arr(listOf(RpcValue.Int(ChannelWire.ResponseType.EventFire.id), RpcValue.Int(listener))),
                        RpcValue.Obj(buildJsonObject {
                            put("topic", JsonPrimitive(topic))
                            put("frame", wireJson(wire))
                        }),
                    )
                )
                sendLogicalFrame(bytes)
            }
        }
    }

    fun currentSeq(sessionId: String): Long = seqHeads[sessionId] ?: 0L

    /** Phase 5 S9 driver: move the epoch (subsequent acks/snapshots carry it). */
    fun bumpEpoch(newEpoch: String) {
        logEpoch = newEpoch
    }

    /**
     * Phase 6 §48-U2 driver: the verified `workspace-list-updated {result}` push
     * (relay-control-plane.md §2). The client refreshes its task projection from
     * this within ≤ 2 s of the event.
     */
    // ------------------------------------------------------------ Phase 7 test hooks

    internal fun stateBlockForTest(): JsonObject? = stateBlock()
    internal fun revisionForTest(): Long = stateRevision.get()
    internal fun pendingPermissionForTest(): PendingPermission? = script.pendingPermission
    internal fun pendingUserInputForTest(): PendingUserInput? = script.pendingUserInput
    // Phase 4A self-test seams: the snapshot row builder + the appended/upserted row ledger.
    internal fun snapshotRowsForTestSeam(sessionId: String, count: Int): JsonArray = snapshotRows(sessionId, count)
    internal fun appendedRowForTest(rowId: Long): JsonObject? = synchronized(appendedRows) { appendedRows[rowId.toString()] }
    internal fun appendedRowsForTest(): Map<String, JsonObject> = synchronized(appendedRows) { LinkedHashMap(appendedRows) }

    /**
     * Phase 7 test driver: inject a pending permission interaction (E2E approval
     * flows) — arms the script knob AND pushes the verified state.updated patch so
     * connected clients see it without resubscribing.
     */
    fun pushPendingPermission(interactionId: String, toolName: String) {
        script.pendingPermission = PendingPermission(interactionId, toolName)
        stateRevision.incrementAndGet()
        val block = stateBlock() ?: return
        pushStatePatch(buildJsonObject {
            put("revision", JsonPrimitive(stateRevision.get()))
            put("pendingInteractions", block["pendingInteractions"] ?: JsonArray(emptyList()))
        })
    }

    fun pushWorkspaceListUpdated(tasks: List<MockTask>) {
        val socket = terminalSocket ?: return
        sendTo(socket, raw = RelayFrameCodec.encodeEnvelope(
            json.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("zcode_type", JsonPrimitive("workspace-list-updated"))
                put("result", buildJsonObject {
                    put("tasks", JsonArray(tasks.map { it.toJson() }))
                })
            })
        ))
    }

    private fun dispatchChannelFrame(bytes: ByteArray) {
        val values = try {
            RpcSerialization.decodeAll(bytes)
        } catch (e: Exception) {
            return // e.g. sanitized corpus synthetic bytes — geometry only
        }
        val header = values.getOrNull(0) as? RpcValue.Arr ?: return
        val typeV = header.items.getOrNull(0) as? RpcValue.Int ?: return
        val id = (header.items.getOrNull(1) as? RpcValue.Int)?.value ?: return
        val channelName = (header.items.getOrNull(2) as? RpcValue.Str)?.value
        val name = (header.items.getOrNull(3) as? RpcValue.Str)?.value
        val arg = values.getOrNull(1)
        when (typeV.value) {
            ChannelWire.RequestType.EventListen.id -> {
                if (name == "onDynamicConversationFrame") {
                    conversationListenerId = id
                    // Scope registration (round-3 field fidelity): the listen arg must
                    // carry the workspace routing scope, spike/SPA-proven shape.
                    val listenArg = when (val a = arg) {
                        is RpcValue.Arr -> (a.items.firstOrNull() as? RpcValue.Obj)?.json as? JsonObject
                        is RpcValue.Obj -> a.json as? JsonObject
                        else -> null
                    }
                    val path = (listenArg?.get("workspacePath") as? JsonPrimitive)?.content
                    conversationScopeRegistered = !path.isNullOrBlank()
                }
                if (name == "v4/connection/flow") {
                    flowListenerId = id
                    sawConnectionFlowListen = true
                }
                // Phase 9: terminal dynamic events — listen arg is the terminal id (RAW).
                if (channelName == "terminal" && (name == "onDynamicData" || name == "onDynamicExit")) {
                    val terminalId = when (val a = arg) {
                        is RpcValue.Str -> a.value
                        is RpcValue.Arr -> (a.items.firstOrNull() as? RpcValue.Str)?.value
                        else -> null
                    }
                    if (terminalId != null) {
                        if (name == "onDynamicData") {
                            synchronized(terminalListeners) { terminalListeners.getOrPut(terminalId) { mutableListOf() }.add(id) }
                            // Emit any scripted output queued before the listen attached.
                            scope.launch {
                                kotlinx.coroutines.delay(50)
                                while (true) {
                                    val text = synchronized(script.terminalOutput) { script.terminalOutput.removeFirstOrNull() } ?: break
                                    pushTerminalData(terminalId, text)
                                }
                            }
                        } else {
                            synchronized(terminalExitListeners) { terminalExitListeners.getOrPut(terminalId) { mutableListOf() }.add(id) }
                        }
                    }
                }
                if (name == "onDynamicConversationFrame") {
                    val listenArg = when (val a = arg) {
                        is RpcValue.Arr -> (a.items.firstOrNull() as? RpcValue.Obj)?.json as? JsonObject
                        is RpcValue.Obj -> a.json as? JsonObject
                        else -> null
                    }
                    (listenArg?.get("workspacePath") as? JsonPrimitive)?.content?.let {
                        synchronized(listenScopesSeen) { listenScopesSeen.add(it) }
                    }
                }
            }
            ChannelWire.RequestType.Promise.id -> handlePromise(id, channelName, name, arg)
            else -> Unit
        }
    }

    /** Live-proven convention: Promise-call args arrive wrapped in an ARRAY. */
private fun scriptableObject(id: Int, scripted: JsonObject?, label: String) {
        if (scripted == null) {
            respondError(id, "fault.mock.unscripted:$label")
        } else {
            respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(scripted))
        }
    }

        private fun argAsObject(arg: RpcValue?): JsonObject? = when (arg) {
        is RpcValue.Arr -> (arg.items.firstOrNull() as? RpcValue.Obj)?.json as? JsonObject
        is RpcValue.Obj -> arg.json as? JsonObject
        else -> null
    }

    private fun handlePromise(id: Int, channelName: String?, name: String?, arg: RpcValue?) {
        // Phase 9: the service channels ride the SAME Channel protocol (§7 convention:
        // promise args array-wrapped). Refusal scenario = §13.3 fallback fixtures.
        if (channelName != null && channelName != "zcode-agent") {
            if (channelName in script.refuseChannels) {
                respondError(id, "fault.channel.refused:$channelName")
                return
            }
            when (channelName) {
                "terminal" -> handleTerminalPromise(id, name, argAsObject(arg))
                "git" -> handleGitPromise(id, name)
                "file" -> handleFilePromise(id, name, argAsObject(arg))
                "model-selection" -> handleModelSelectionPromise(id, name)
                "zcode-task" -> handleTaskPromise(id, name, argAsObject(arg))
                "off-peak-task" -> handleOffPeakTaskPromise(id, name)
                "usage-stats" -> handleUsageStatsPromise(id, name, arg)
                // Phase 4C (C2): the slash panel's extra sections over their own channels.
                "commands" -> handleCommandsPromise(id, name)
                "subagents" -> handleSubagentsPromise(id, name)
                else -> respondError(id, "fault.mock.unknownChannel:$channelName")
            }
            return
        }
        if (channelName != "zcode-agent") return
        // Round-3 field fidelity: the conversation plane is workspace-scoped on
        // production — calls before a scoped listen are SILENT (observed as the
        // real-phone helloConversationV4 10 s timeout).
        val conversationPlane = name in setOf(
            "helloConversationV4", "initializeConversationV4", "subscribeConversationV4",
            "resyncConversationV4", "conversationRowsRangeV4", "sendConversationCommandV4",
        )
        if (script.requireScopedListen && conversationPlane && !conversationScopeRegistered) {
            println("MOCK: silenced unscoped conversation call $name (id=$id) — production behavior")
            return
        }
        // UI-only action probe (I1 class): every zcodeAgent method the phone sends is
        // logged, so "the Retry/Fork/Rewind/Like sheet is UI-only" is provable by the
        // mock stdout between tap and screenshot.
        println("MOCK: v4call $name (id=$id)")
        synchronized(recentV4Calls) { recentV4Calls[name ?: "?"] = argAsObject(arg) }
        when (name) {
            // Phase 4C (C7): the scratch-workspace facade call — {name} → {path}.
            "createScratchWorkspace" -> {
                val name = (argAsObject(arg)?.get("name") as? JsonPrimitive)?.content
                respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                    put("path", JsonPrimitive("/synthetic/scratch-${name?.takeIf { it.isNotBlank() } ?: createdCount.incrementAndGet()}"))
                }))
            }
            // Phase 4C (A3.4): the effort-level facade call (zcodeAgent.ts:705) — the
            // echo plane's `thought` updates and a state patch carries the config.
            "setThoughtLevel" -> {
                val params = argAsObject(arg)
                val level = params?.strOrNull("thoughtLevel")
                if (level == null) {
                    respondError(id, "fault.mock.unscripted:setThoughtLevel")
                } else {
                    stateRevision.incrementAndGet()
                    val current = script.sessionConfig
                    script.sessionConfig = buildJsonObject {
                        put("provider", JsonPrimitive(current?.strOrNull("provider") ?: "zai"))
                        put("model", JsonPrimitive(current?.strOrNull("model") ?: "glm-5.3"))
                        put("thought", JsonPrimitive(level))
                        put("thoughtLevels", current?.get("thoughtLevels") ?: JsonArray(listOf(JsonPrimitive(level))))
                        put("followupMode", JsonPrimitive(current?.strOrNull("followupMode") ?: "queue"))
                        put("mode", JsonPrimitive(current?.strOrNull("mode") ?: "build"))
                    }
                    pushStatePatch(buildJsonObject {
                        put("revision", JsonPrimitive(stateRevision.get()))
                        put("config", script.sessionConfig!!)
                    })
                    respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(script.sessionConfig!!))
                }
            }
            "helloConversationV4" -> {
                val hello = buildJsonObject {
                    put("kind", JsonPrimitive("hello"))
                    put("protocolVersion", JsonPrimitive(3))
                    put("connectionId", JsonPrimitive("mock-conn-1"))
                    put("clientMode", JsonPrimitive("web-remote-replayable"))
                    put("deliveryProfile", JsonPrimitive("replayable"))
                    put("serverTime", JsonPrimitive(System.currentTimeMillis().toDouble()))
                    put("capabilities", buildJsonObject {
                        put("nativeDialogs", JsonPrimitive(true))
                        put("localTerminal", JsonPrimitive(true))
                        put("binaryFrames", JsonPrimitive(false))
                        put("compression", JsonPrimitive("none"))
                    })
                    put("auth", buildJsonObject { })
                }
                respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(hello))
            }
            "initializeConversationV4" -> {
                val hello = argAsObject(arg)
                if (hello == null || hello.strOrNull("kind") != "clientHello") {
                    respondError(id, "fault.connection.handshakeRequired")
                } else {
                    // Production fidelity (zcodeAgentConnectionScope): bind the
                    // clientHello's clientId; commands with a different clientId are
                    // rejected fault.command.clientMismatch (vc22 field bug).
                    boundClientId = hello.strOrNull("clientId")
                    respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Undefined)
                }
            }
            "subscribeConversationV4" -> {
                val params = argAsObject(arg) ?: return respondError(id, "fault.subscribe.params")
                // Round-3 field fidelity: production routes by the workspace scope —
                // a blank workspacePath is a client-side routing bug, surface it loudly.
                if (params.str("workspacePath").isBlank()) return respondError(id, "fault.subscribe.workspaceScope")
                lastSubscribeWorkspacePath = params.str("workspacePath")
                // Phase 2 (§11.2): a workspace-config topic subscribe carries an
                // EXPLICIT topic (the desktop adapter's subscribeWorkspaceConfigV4
                // shape) instead of a sessionId.
                val explicitTopic = params.str("topic")
                if (explicitTopic.startsWith("workspace-config/")) {
                    val cfgSubscriptionId = "sub-mock-cfg-${explicitTopic.hashCode()}"
                    respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                        put("ack", buildJsonObject {
                            put("subscriptionId", JsonPrimitive(cfgSubscriptionId))
                            put("mode", JsonPrimitive("snapshot"))
                            put("logEpoch", JsonPrimitive("cfg-epoch-mock-1"))
                        })
                    }))
                    script.workspaceConfigState?.let { cfg ->
                        pushWorkspaceConfigFrame(explicitTopic, cfgSubscriptionId, cfg)
                    }
                    return
                }
                val sessionId = params.str("sessionId")
                subscriptionId = "sub-mock-$sessionId"
                respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                    put("ack", buildJsonObject {
                        put("subscriptionId", JsonPrimitive(subscriptionId))
                        put("mode", JsonPrimitive("snapshot"))
                        put("logEpoch", JsonPrimitive(logEpoch))
                    })
                }))
                // Initial snapshot rides the post-response notification (schema-true timing)
                pushTopicFrame(sessionId, DeliveryKind.INITIAL, snapshot = true, fromSeq = 0, toSeq = 1)
                script.flowStateAfterSubscribe?.let { st ->
                    script.flowStateAfterSubscribe = null
                    // Field-fidelity (2026-09-29): flow frames go ONLY to a registered
                    // v4/connection/flow listener. No fallback to the conversation
                    // listener — production desktops have no such event at all, and a
                    // misrouted flow frame would hit the conversation decode path.
                    flowListenerId?.let { lit ->
                        val bytes = RpcSerialization.encodeAll(
                            listOf(
                                RpcValue.Arr(listOf(RpcValue.Int(ChannelWire.ResponseType.EventFire.id), RpcValue.Int(lit))),
                                RpcValue.Obj(buildJsonObject {
                                    put("connectionId", JsonPrimitive("mock-conn-1"))
                                    put("state", JsonPrimitive(st))
                                }),
                            )
                        )
                        sendLogicalFrame(bytes)
                    }
                }
            }
            "resyncConversationV4" -> {
                // Phase 5 recovery semantics (§15.1): base matching the live epoch and
                // no forceSnapshot → RESUME (replay logged delta frames after base.seq);
                // anything else → SNAPSHOT (ack + fresh snapshot frame).
                val params = argAsObject(arg) ?: return respondError(id, "fault.resync.params")
                val sessionId = params.str("sessionId")
                val base = params["base"] as? JsonObject
                val baseEpoch = (base?.get("logEpoch") as? JsonPrimitive)?.content
                val baseSeq = (base?.get("seq") as? JsonPrimitive)?.content?.toLongOrNull()
                val force = (params["forceSnapshot"] as? JsonPrimitive)?.content == "true"
                val resumable = !force && baseEpoch == logEpoch && baseSeq != null
                respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                    put("ack", buildJsonObject {
                        put("subscriptionId", JsonPrimitive(subscriptionId))
                        put("mode", JsonPrimitive(if (resumable) "resume" else "snapshot"))
                        put("logEpoch", JsonPrimitive(logEpoch))
                    })
                }))
                if (resumable) {
                    val replay = opLog[sessionId].orEmpty().filter { it.fromSeq > baseSeq }
                    if (replay.isNotEmpty()) emitDeltasFrame(sessionId, replay, DeliveryKind.RECOVERY)
                } else {
                    pushTopicFrame(sessionId, DeliveryKind.RECOVERY, snapshot = true, fromSeq = 0, toSeq = 1)
                }
            }
            "conversationRowsRangeV4" -> {
                val params = argAsObject(arg) ?: return respondError(id, "fault.rowsRange.params")
                val sessionId = params.str("sessionId")
                val limit = params.strOrNull("limit")?.toIntOrNull() ?: 10
                val rows = (0 until limit.coerceAtMost(workspace.rowCount)).map { i ->
                    schemaRow(
                        sessionId, i.toLong() + 1,
                        if (i % 2 == 0) RowKind.ASSISTANT_TEXT else RowKind.REASONING,
                        "synthetic row ${i + 1}",
                    )
                }
                respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                    put("rows", JsonArray(rows))
                    put("atSeq", JsonPrimitive(maxOf(1L, seqHeads[sessionId] ?: 1L)))
                    put("atRevision", JsonPrimitive(script.currentRevision))
                    put("atLogEpoch", JsonPrimitive(logEpoch))
                    put("hasMore", JsonPrimitive(false))
                }))
            }
            "sendConversationCommandV4" -> {
                // Production shape (zcodeAgent.ts): the arg is
                // {workspacePath?, workspaceIdentity?, envelope} — the desktop's
                // adapter spreads the workspace target beside a NESTED envelope.
                // The bare-envelope shape is the Phase-7-dev mock shape; tolerated
                // for legacy fixtures, but the wrapper is what suites must assert.
                val rawArg = argAsObject(arg)
                val wrapped = rawArg?.get("envelope") as? JsonObject
                val env = wrapped ?: rawArg
                (rawArg?.get("workspacePath") as? JsonPrimitive)?.content?.let {
                    lastCommandWorkspacePath = it
                }
                (rawArg?.get("workspaceIdentity") as? JsonPrimitive)?.content?.let {
                    lastCommandWorkspaceIdentity = it
                }
                val type = env?.strOrNull("type") ?: "unknown"
                val commandId = env?.strOrNull("commandId") ?: "unknown"
                lastCommandClientId = env?.strOrNull("clientId")
                scope.launch { events.emit(ServerEvent.CommandReceived(type, commandId)) }
                // Production fidelity: envelope.clientId MUST match the hello-bound id.
                if (boundClientId != null && env?.strOrNull("clientId") != boundClientId) {
                    respond(id, ChannelWire.ResponseType.PromiseError, RpcValue.Obj(buildJsonObject {
                        put("message", JsonPrimitive("fault.command.clientMismatch"))
                        put("name", JsonPrimitive("Error"))
                    }))
                    return
                }
                // §7.4.4 CAS semantics (verified COMMANDS_REQUIRING_BASE_REVISION):
                // missing base → rejected (parse would fail); stale base → stale.
                // Phase 7: the CAS currency is the SAME stateRevision the state block
                // publishes (one desktop, one revision); legacy suites keep their
                // script.currentRevision knob when commandEffects is off.
                if (script.enforceCas && type in com.zcode.android.core.protocol.v4.COMMANDS_REQUIRING_BASE_REVISION) {
                    val baseRev = env?.strOrNull("baseRevision")?.toLongOrNull()
                    val casCurrent = if (script.commandEffects) stateRevision.get() else script.currentRevision
                    val casStatus = when {
                        baseRev == null -> "rejected"
                        baseRev < casCurrent -> "stale"
                        else -> null
                    }
                    if (casStatus != null) {
                        respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                            put("commandId", JsonPrimitive(commandId))
                            put("status", JsonPrimitive(casStatus))
                            put("reasonCode", JsonPrimitive("fault.command.baseRevision"))
                            put("revisionAtDecision", JsonPrimitive(casCurrent))
                        }))
                        return
                    }
                }
                // Phase 7: one-shot scripted ack overrides (failure-path fixtures).
                val override = synchronized(script.commandAckOverrides) { script.commandAckOverrides.removeFirstOrNull() }
                if (override != null) {
                    respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                        put("commandId", JsonPrimitive(commandId))
                        put("status", JsonPrimitive(override))
                        put("reasonCode", JsonPrimitive("mock.scripted"))
                        put("revisionAtDecision", JsonPrimitive(stateRevision.get()))
                    }))
                    return
                }
                var createResult: JsonObject? = null
                if (script.commandEffects) {
                    createResult = applyCommandEffect(type, env)
                }
                val ackObj = buildJsonObject {
                    put("commandId", JsonPrimitive(commandId))
                    put("status", JsonPrimitive("accepted"))
                    put("revisionAtDecision", JsonPrimitive(stateRevision.get()))
                    createResult?.let { put("result", it) }
                }
                respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(ackObj))
            }
            "conversationFileChangesV4" -> {
                val result = synchronized(script.conversationFileChanges) { script.conversationFileChanges.removeFirstOrNull() }
                if (result == null) {
                    respondError(id, "fault.fileChanges.none")
                } else {
                    respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(result))
                }
            }
            // ---- Phase 3 viewer methods (v4-wire.md §12) ----
            "getAppUsageStats" -> {
                val result = script.appUsageStats
                if (result == null) {
                    respondError(id, "fault.mock.unscripted:getAppUsageStats")
                } else {
                    respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(result))
                }
            }
            "listAutomations" -> {
                val items = script.listAutomations
                if (items == null) {
                    respondError(id, "fault.mock.unscripted:listAutomations")
                } else {
                    // The desktop method returns the array directly (ProxyChannel convention).
                    respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Arr(items.map { RpcValue.Obj(it) }))
                }
            }
            // ---- Phase 4B (J2/A3.2/B2.11): the automation CRUD + run-history surface.
            // Shapes mirror ZCodeAutomationCreateParams/UpdateParams and ZCodeAgentRunAutomationNowResult.
            "createAutomation" -> {
                val params = argAsObject(arg)
                if (params == null || script.listAutomations == null) {
                    respondError(id, "fault.mock.unscripted:createAutomation")
                } else if ((params.strOrNull("title") ?: "").contains("LIMIT") || automationCreateLimitArmed) {
                    automationCreateLimitArmed = false
                    respondError(id, "AUTOMATION_CREATE_LIMIT_REACHED: automation limit reached (20)")
                } else {
                    val row = schemaAutomationRow(params, workspace.workspacePath)
                    script.listAutomations = JsonArray((script.listAutomations ?: JsonArray(emptyList())) + row)
                    respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(row))
                }
            }
            "updateAutomation" -> {
                val params = argAsObject(arg)
                val target = params?.strOrNull("automationId")
                val current = script.listAutomations
                if (params == null || target == null || current == null) {
                    respondError(id, "fault.mock.unscripted:updateAutomation")
                } else {
                    val updated = schemaAutomationRow(params, workspace.workspacePath, automationId = target)
                    script.listAutomations = JsonArray(current.map { el ->
                        if ((el.jsonObject["automationId"] as? JsonPrimitive)?.content == target) updated else el
                    })
                    respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(updated))
                }
            }
            "deleteAutomation" -> {
                val params = argAsObject(arg)
                val target = params?.strOrNull("automationId")
                if (target == null || script.listAutomations == null) {
                    respondError(id, "fault.mock.unscripted:deleteAutomation")
                } else {
                    script.listAutomations = JsonArray(script.listAutomations!!.filter {
                        (it.jsonObject["automationId"] as? JsonPrimitive)?.content != target
                    })
                    respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(JsonObject(emptyMap())))
                }
            }
            "setAutomationEnabled" -> {
                val params = argAsObject(arg)
                val target = params?.strOrNull("automationId")
                val enabled = params?.get("enabled") as? JsonPrimitive
                if (target == null || enabled == null || script.listAutomations == null) {
                    respondError(id, "fault.mock.unscripted:setAutomationEnabled")
                } else {
                    script.listAutomations = JsonArray(script.listAutomations!!.map { el ->
                        if ((el.jsonObject["automationId"] as? JsonPrimitive)?.content == target) {
                            JsonObject(el.jsonObject + ("enabled" to enabled))
                        } else el
                    })
                    respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(JsonObject(emptyMap())))
                }
            }
            "runAutomationNow" -> {
                val params = argAsObject(arg)
                val target = params?.strOrNull("automationId")
                if (target == null || script.automationRuns == null) {
                    respondError(id, "fault.mock.unscripted:runAutomationNow")
                } else {
                    // ZCodeAgentRunAutomationNowResult {status: queued|duplicate} — the
                    // second Run-Now for an automation whose last run is still `running`
                    // lands `duplicate` (desktop: an in-flight run already exists).
                    val running = synchronized(automationRunLedger) {
                        automationRunLedger.any { it.jsonObject["automationId"]?.let { a -> (a as JsonPrimitive).content } == target &&
                            it.jsonObject["outcome"] == null }
                    }
                    if (running) {
                        respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                            put("status", JsonPrimitive("duplicate"))
                        }))
                    } else {
                        recordAutomationRun(target, trigger = "manual")
                        respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                            put("status", JsonPrimitive("queued"))
                        }))
                    }
                }
            }
            "listAutomationRuns" -> {
                val params = argAsObject(arg)
                val target = params?.strOrNull("automationId")
                val ledger = script.automationRuns
                if (target == null || ledger == null) {
                    respondError(id, "fault.mock.unscripted:listAutomationRuns")
                } else {
                    val filtered = synchronized(automationRunLedger) {
                        automationRunLedger.filter { (it.jsonObject["automationId"] as? JsonPrimitive)?.content == target }
                    } + ledger.filter { (it.jsonObject["automationId"] as? JsonPrimitive)?.content == target }
                    // Desktop method returns the array directly (ProxyChannel convention).
                    respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Arr(filtered.map { RpcValue.Obj(it) }))
                }
            }
            "deleteAutomationRun" -> {
                val params = argAsObject(arg)
                val runId = params?.strOrNull("runId")
                if (runId == null) {
                    respondError(id, "fault.mock.unscripted:deleteAutomationRun")
                } else {
                    synchronized(automationRunLedger) {
                        automationRunLedger.removeAll { (it.jsonObject["runId"] as? JsonPrimitive)?.content == runId }
                    }
                    script.automationRuns = script.automationRuns?.filter {
                        (it.jsonObject["runId"] as? JsonPrimitive)?.content != runId
                    }?.let { JsonArray(it) }
                    respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(JsonObject(emptyMap())))
                }
            }
            "restartAutomation" -> {
                val params = argAsObject(arg)
                val target = params?.strOrNull("automationId")
                if (target == null || script.listAutomations == null) {
                    respondError(id, "fault.mock.unscripted:restartAutomation")
                } else {
                    script.listAutomations = JsonArray(script.listAutomations!!.map { el ->
                        if ((el.jsonObject["automationId"] as? JsonPrimitive)?.content == target) {
                            JsonObject(el.jsonObject + ("lifecycleStatus" to JsonPrimitive("active")))
                        } else el
                    })
                    respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(JsonObject(emptyMap())))
                }
            }
            "listPlugins" -> scriptableObject(id, script.listPlugins, "listPlugins")
            "getSkillReferenceCatalog" -> scriptableObject(id, script.skillReferenceCatalog, "getSkillReferenceCatalog")
            "listMcpServerStatuses" -> scriptableObject(id, script.mcpServerStatuses, "listMcpServerStatuses")
            "attachmentBeginV4" -> {
                val params = argAsObject(arg)
                if (params == null || script.rejectAttachments) {
                    respondError(id, "fault.attachment.rejected")
                    return
                }
                val uploadId = params.str("uploadId")
                synchronized(attachmentStore) {
                    val existing = attachmentStore[uploadId]
                    if (existing?.committedRef != null) {
                        // Idempotent re-begin of a finished transaction (verified shape).
                        respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                            put("uploadId", JsonPrimitive(uploadId))
                            put("state", JsonPrimitive("committed"))
                            put("nextChunkIndex", JsonPrimitive(existing.chunks))
                            put("ref", JsonPrimitive(existing.committedRef))
                        }))
                        return
                    }
                    attachmentStore[uploadId] = MockAttachment(
                        sessionId = params.str("sessionId"),
                        fileName = params.str("fileName"),
                        mime = params.str("mime"),
                        totalBytes = params.str("totalBytes")?.toLongOrNull() ?: 0L,
                    )
                }
                respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                    put("uploadId", JsonPrimitive(uploadId))
                    put("state", JsonPrimitive("staging"))
                    put("nextChunkIndex", JsonPrimitive(0))
                }))
            }
            "attachmentChunkV4" -> {
                val params = argAsObject(arg)
                val uploadId = params?.str("uploadId")
                val att = synchronized(attachmentStore) { attachmentStore[uploadId] }
                if (params == null || att == null) {
                    respondError(id, "fault.attachment.unknownUpload")
                    return
                }
                val data = params.str("dataBase64")
                val decoded = java.util.Base64.getDecoder().decode(data)
                if (decoded.size > 512 * 1024) {
                    respondError(id, "fault.attachment.chunkTooLarge")
                    return
                }
                val index = params.str("chunkIndex")?.toIntOrNull() ?: -1
                synchronized(attachmentStore) {
                    val expected = att.chunks
                    if (index != expected) {
                        respondError(id, "fault.attachment.chunkIndexMismatch")
                        return
                    }
                    att.data.write(decoded)
                    att.chunks += 1
                }
                respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                    put("uploadId", JsonPrimitive(uploadId))
                    put("nextChunkIndex", JsonPrimitive(att.chunks))
                }))
            }
            "attachmentCommitV4" -> {
                val uploadId = argAsObject(arg)?.str("uploadId")
                val att = synchronized(attachmentStore) { attachmentStore[uploadId] }
                if (uploadId == null || att == null) {
                    respondError(id, "fault.attachment.unknownUpload")
                    return
                }
                val ref = "att-${uploadId}"
                synchronized(attachmentStore) {
                    att.committedRef = ref
                    committedAttachments[ref] = att
                }
                respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                    put("ref", JsonPrimitive(ref))
                }))
            }
            "attachmentAbortV4" -> {
                val uploadId = argAsObject(arg)?.str("uploadId")
                synchronized(attachmentStore) { attachmentStore.remove(uploadId) }
                respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject { }))
            }
            "attachmentReadV4" -> {
                val params = argAsObject(arg)
                val ref = params?.str("ref")
                val att = synchronized(committedAttachments) { committedAttachments[ref] }
                if (ref == null || att == null) {
                    respondError(id, "fault.attachment.unknownRef")
                    return
                }
                val offset = params.str("offset")?.toLongOrNull() ?: 0L
                val limit = params.str("limit")?.toIntOrNull() ?: 512 * 1024
                val bytes = att.data.toByteArray()
                if (offset >= bytes.size) {
                    respondError(id, "fault.attachment.offsetEof")
                    return
                }
                val end = minOf(offset + limit, bytes.size.toLong()).toInt()
                val slice = bytes.copyOfRange(offset.toInt(), end)
                val next = if (end < bytes.size) end.toLong() else null
                respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                    put("dataBase64", JsonPrimitive(java.util.Base64.getEncoder().encodeToString(slice)))
                    put("mediaType", JsonPrimitive(att.mime))
                    put("totalBytes", JsonPrimitive(bytes.size))
                    put("nextOffset", if (next == null) JsonNull else JsonPrimitive(next))
                }))
            }
            else -> respondError(id, "fault.mock.unknownMethod")
        }
    }

    // ------------------------------------------------------------ Phase 9 service channels

    class MockAttachment(
        val sessionId: String,
        val fileName: String,
        val mime: String,
        val totalBytes: Long,
    ) {
        val data = java.io.ByteArrayOutputStream()
        var chunks: Int = 0
        var committedRef: String? = null
    }
    private val attachmentStore = HashMap<String, MockAttachment>()
    private val committedAttachments = HashMap<String, MockAttachment>()
    private val terminalListeners = HashMap<String, MutableList<Int>>()
    private val terminalExitListeners = HashMap<String, MutableList<Int>>()
    private val terminalCounter = AtomicLong(0)

    private fun handleTerminalPromise(id: Int, name: String?, params: JsonObject?) {
        when (name) {
            "create" -> {
                val terminalId = "term-${terminalCounter.incrementAndGet()}"
                val shell = "/bin/zsh"
                respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                    put("id", JsonPrimitive(terminalId))
                    put("shell", JsonPrimitive(shell))
                    put("fontFamily", JsonPrimitive("monospace"))
                    put("fontFamilySource", JsonPrimitive("system"))
                }))
                // Scripted output rides the data listeners as they attach.
                scope.launch {
                    kotlinx.coroutines.delay(150)
                    while (true) {
                        val text = synchronized(script.terminalOutput) { script.terminalOutput.removeFirstOrNull() } ?: break
                        pushTerminalData(terminalId, text)
                    }
                }
            }
            "dispose" -> respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Undefined)
            else -> respondError(id, "fault.terminal.unknownMethod")
        }
    }

    /** Driver: emit `onDynamicData(id)` (RAW id arg — proxy-channel.ts:127 convention). */
    fun pushTerminalData(terminalId: String, text: String) {
        val listeners = synchronized(terminalListeners) { terminalListeners[terminalId]?.toList() } ?: return
        for (listener in listeners) {
            val bytes = RpcSerialization.encodeAll(
                listOf(
                    RpcValue.Arr(listOf(RpcValue.Int(ChannelWire.ResponseType.EventFire.id), RpcValue.Int(listener))),
                    RpcValue.Str(text),
                ),
            )
            sendLogicalFrame(bytes)
        }
    }

    fun pushTerminalExit(terminalId: String, code: Int) {
        val listeners = synchronized(terminalExitListeners) { terminalExitListeners[terminalId]?.toList() } ?: return
        for (listener in listeners) {
            val bytes = RpcSerialization.encodeAll(
                listOf(
                    RpcValue.Arr(listOf(RpcValue.Int(ChannelWire.ResponseType.EventFire.id), RpcValue.Int(listener))),
                    RpcValue.Int(code),
                ),
            )
            sendLogicalFrame(bytes)
        }
    }

    private fun handleGitPromise(id: Int, name: String?) {
        when (name) {
            "getRepositorySummary" -> {
                val summary = script.gitSummary
                if (summary == null) respondError(id, "fault.git.notAvailable")
                else respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(summary))
            }
            "getChanges" -> {
                val changes = script.gitChanges
                if (changes == null) respondError(id, "fault.git.notAvailable")
                else respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Arr(changes.map { RpcValue.Obj(it) }))
            }
            "getDiff" -> {
                val diff = script.gitDiff
                if (diff == null) respondError(id, "fault.git.notAvailable")
                else respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(diff))
            }
            "getCommitGraph" -> {
                val commits = script.gitCommits
                if (commits == null) respondError(id, "fault.git.notAvailable")
                else respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                    put("commits", commits)
                    put("hasMore", JsonPrimitive(script.gitHasMore))
                }))
            }
            else -> respondError(id, "fault.git.unknownMethod")
        }
    }

    private fun handleFilePromise(id: Int, name: String?, params: JsonObject?) {
        when (name) {
            "readdir" -> {
                val path = params?.str("path").orEmpty()
                val entries = script.fileTree[path]
                if (entries == null) respondError(id, "fault.file.notFound")
                else respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Arr(entries.map { RpcValue.Obj(it) }))
            }
            "stat" -> {
                val path = params?.str("path").orEmpty()
                respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                    put("path", JsonPrimitive(path))
                    put("type", JsonPrimitive(if (script.fileTree.containsKey(path)) "directory" else "file"))
                    put("size", JsonPrimitive(script.fileBytes[path]?.size?.toLong() ?: script.fileText[path]?.toByteArray()?.size?.toLong() ?: 0L))
                }))
            }
            // Phase 4C (C7): workspace file search for the composer @-mentions.
            "searchWorkspaceFiles" -> {
                val query = (params?.get("query") as? JsonPrimitive)?.content.orEmpty().lowercase()
                val limit = (params?.get("limit") as? JsonPrimitive)?.content?.toIntOrNull() ?: 20
                val hits = script.fileTree.keys.filter { it.lowercase().contains(query) && query.isNotEmpty() }.take(limit)
                respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Arr(hits.map { path ->
                    RpcValue.Obj(buildJsonObject {
                        put("path", JsonPrimitive(path))
                        put("type", JsonPrimitive(if (script.fileTree.containsKey(path)) "directory" else "file"))
                    })
                }))
            }
            "readTextFile" -> {
                val path = params?.str("path").orEmpty()
                val content = script.fileText[path]
                if (content == null) respondError(id, "fault.file.notFound")
                else {
                    val offset = params?.str("offset")?.toLongOrNull() ?: 0L
                    val length = params?.str("length")?.toLongOrNull()
                    val text = content.toByteArray(Charsets.UTF_8)
                    val from = offset.toInt().coerceIn(0, text.size)
                    val to = (length?.let { from + it.toInt() } ?: text.size).coerceAtMost(text.size)
                    val slice = String(text, from, to - from, Charsets.UTF_8)
                    respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                        put("path", JsonPrimitive(path))
                        put("content", JsonPrimitive(slice))
                        put("offset", JsonPrimitive(from.toLong()))
                        put("bytesRead", JsonPrimitive((to - from).toLong()))
                        put("totalBytes", JsonPrimitive(text.size.toLong()))
                        put("truncated", JsonPrimitive(script.fileText["truncated:$path"] == "1"))
                        put("isBinary", JsonPrimitive(false))
                    }))
                }
            }
            "readFileRange" -> {
                val path = params?.str("path").orEmpty()
                val bytes = script.fileBytes[path]
                if (bytes == null) respondError(id, "fault.file.notFound")
                else {
                    val offset = params?.str("offset")?.toLongOrNull() ?: 0L
                    val length = params?.str("length")?.toLongOrNull() ?: bytes.size.toLong()
                    val from = offset.toInt().coerceIn(0, bytes.size)
                    val to = (offset.toInt() + length.toInt()).coerceAtMost(bytes.size)
                    respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Buf(bytes.copyOfRange(from, to)))
                }
            }
            "readBinaryPreview" -> {
                val path = params?.str("path").orEmpty()
                val bytes = script.fileBytes[path]
                if (bytes == null) respondError(id, "fault.file.notFound")
                else respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                    put("path", JsonPrimitive(path))
                    put("dataBase64", JsonPrimitive(java.util.Base64.getEncoder().encodeToString(bytes)))
                    put("totalBytes", JsonPrimitive(bytes.size))
                }))
            }
            else -> respondError(id, "fault.file.unknownMethod")
        }
    }

    private fun handleModelSelectionPromise(id: Int, name: String?) {
        when (name) {
            "getView" -> {
                val view = script.modelSelection
                if (view == null) respondError(id, "fault.modelSelection.refused")
                else respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(view))
            }
            else -> respondError(id, "fault.modelSelection.unknownMethod")
        }
    }

    /**
     * Phase 7 command side effects (script.commandEffects): accepted commands mutate
     * the mock session exactly like the §9/§7.4.4 contracts imply — sendText appends
     * authoritative rows (the phone's own message arrives via the topic stream),
     * resolveInteraction clears the pending interaction + flips its toolCall row,
     * stop flips the control block, queue ops patch the queue. Every effect bumps
     * the CAS revision.
     */
    private fun applyCommandEffect(type: String, env: JsonObject?): JsonObject? {
        val payload = env?.get("payload") as? JsonObject
        when (type) {
            "createSession" -> {
                val payload = env?.get("payload") as? JsonObject
                val text = (payload?.get("firstInput") as? JsonObject)?.strOrNull("text") ?: ""
                val newId = "sess-created-${createdCount.incrementAndGet()}"
                val wsPath = lastCommandWorkspacePath ?: workspace.workspacePath
                script.bootstrapTasks = script.bootstrapTasks.orEmpty() + MockZCode.MockTask(
                    taskId = newId,
                    title = text.take(60),
                    displayStatus = "running",
                    workspacePath = wsPath,
                    createdAtMs = System.currentTimeMillis(),
                    updatedAtMs = System.currentTimeMillis(),
                )
                // §48-U2 path: the desktop pushes the updated task list immediately.
                pushWorkspaceListUpdated(script.bootstrapTasks.orEmpty())
                JsonObject(emptyMap()) // ack result body; sessionId rides below
            }
            "sendText" -> {
                val text = payload?.strOrNull("text") ?: return null
                stateRevision.incrementAndGet()
                val userRow = schemaRow(workspace.sessionId, nextRowId(), RowKind.USER_INPUT, text)
                val replyBase = schemaRow(workspace.sessionId, nextRowId(), RowKind.ASSISTANT_TEXT, "synthetic reply to: ${text.take(24)}")
                // Phase 4A: the live reply is the latest assistant row → it carries the flags.
                val replyRow = if (script.rowActionsDemo) JsonObject(replyBase + ("actions" to ROW_ACTIONS_ASSISTANT)) else replyBase
                appendedRows[userRow["rowId"].toString()] = userRow
                appendedRows[replyRow["rowId"].toString()] = replyRow
                pushRowAppended(workspace.sessionId, userRow)
                pushRowAppended(workspace.sessionId, replyRow)
                pushStatePatch(buildJsonObject {
                    put("revision", JsonPrimitive(stateRevision.get()))
                    put("control", buildJsonObject {
                        put("phase", JsonPrimitive("running"))
                        put("sessionEnded", JsonPrimitive(false))
                        put("canStop", JsonPrimitive(true))
                        put("stopState", JsonPrimitive("stoppable"))
                        put("activeWorks", JsonArray(emptyList()))
                    })
                    put("inputRouting", buildJsonObject { put("mode", JsonPrimitive("enqueue")) })
                })
                script.controlCanStop = true
                script.controlStopState = "stoppable"
                script.inputRoutingMode = "enqueue"
            }
            "resolveInteraction" -> {
                val iid = payload?.strOrNull("interactionId") ?: return null
                stateRevision.incrementAndGet()
                val hadPermission = script.pendingPermission?.interactionId == iid
                val hadUserInput = script.pendingUserInput?.interactionId == iid
                script.pendingPermission = null
                script.pendingUserInput = null
                pushStatePatch(buildJsonObject {
                    put("revision", JsonPrimitive(stateRevision.get()))
                    put("pendingInteractions", JsonArray(emptyList()))
                    put("control", buildJsonObject {
                        put("phase", JsonPrimitive("running"))
                        put("sessionEnded", JsonPrimitive(false))
                        put("canStop", JsonPrimitive(script.controlCanStop))
                        put("stopState", JsonPrimitive(script.controlStopState))
                        put("activeWorks", JsonArray(emptyList()))
                    })
                })
                if (hadPermission) {
                    // The approval-gated toolCall row flips to success (authoritative).
                    val flipped = schemaRow(workspace.sessionId, nextRowId(), RowKind.TOOL_CALL, "success")
                    appendedRows[flipped["rowId"].toString()] = flipped
                    pushRowAppended(workspace.sessionId, flipped)
                }
                if (hadUserInput) {
                    val answered = schemaRow(workspace.sessionId, nextRowId(), RowKind.USER_INPUT, "(answered)")
                    appendedRows[answered["rowId"].toString()] = answered
                    pushRowAppended(workspace.sessionId, answered)
                }
            }
            "stop" -> {
                stateRevision.incrementAndGet()
                script.controlCanStop = false
                script.controlStopState = "idle"
                script.inputRoutingMode = "startNow"
                pushStatePatch(buildJsonObject {
                    put("revision", JsonPrimitive(stateRevision.get()))
                    put("control", buildJsonObject {
                        put("phase", JsonPrimitive("idle"))
                        put("sessionEnded", JsonPrimitive(true))
                        put("canStop", JsonPrimitive(false))
                        put("stopState", JsonPrimitive("idle"))
                        put("activeWorks", JsonArray(emptyList()))
                    })
                    put("inputRouting", buildJsonObject { put("mode", JsonPrimitive("startNow")) })
                })
            }
            "cancelBackgroundWork" -> {
                stateRevision.incrementAndGet()
                pushStatePatch(buildJsonObject {
                    put("revision", JsonPrimitive(stateRevision.get()))
                    put("backgroundWorks", JsonArray(emptyList()))
                })
            }
            "sendQueuedNow" -> {
                stateRevision.incrementAndGet()
                val qid = payload?.strOrNull("queueItemId")
                script.queueItems = script.queueItems.orEmpty().filter { it.queueItemId != qid }
                pushQueuePatch()
            }
            "editQueueItem" -> {
                stateRevision.incrementAndGet()
                val qid = payload?.strOrNull("queueItemId")
                val newText = payload?.strOrNull("newText")
                script.queueItems = script.queueItems.orEmpty().map {
                    if (it.queueItemId == qid) it.copy(text = newText ?: it.text) else it
                }
                pushQueuePatch()
            }
            // ---- Phase 4A (E3.2+I1+N5): the four row commands — effects shaped on
            // the verified ack results (command.ts commandResultSchema) + row deltas.
            "retryTurn" -> {
                stateRevision.incrementAndGet()
                // The desktop re-asks the turn: a fresh assistant row arrives (authoritative).
                val reply = JsonObject(
                    schemaRow(workspace.sessionId, nextRowId(), RowKind.ASSISTANT_TEXT, "retried reply") +
                        ("actions" to ROW_ACTIONS_ASSISTANT),
                )
                appendedRows[reply["rowId"].toString()] = reply
                pushRowAppended(workspace.sessionId, reply)
                pushRevisionPatch()
            }
            "forkAssistant" -> {
                stateRevision.incrementAndGet()
                val child = "sess-forked-${createdCount.incrementAndGet()}"
                script.bootstrapTasks = script.bootstrapTasks.orEmpty() + MockTask(
                    taskId = child,
                    title = "Fork of ${workspace.sessionId.take(12)}",
                    displayStatus = "idle",
                    workspacePath = lastCommandWorkspacePath ?: workspace.workspacePath,
                    createdAtMs = System.currentTimeMillis(),
                    updatedAtMs = System.currentTimeMillis(),
                )
                pushWorkspaceListUpdated(script.bootstrapTasks.orEmpty())
                lastForkedSessionId = child
                return buildJsonObject {
                    put("type", JsonPrimitive("forkAssistant"))
                    put("sessionId", JsonPrimitive(child))
                }
            }
            "applyFileRewind" -> {
                stateRevision.incrementAndGet()
                val target = payload?.get("target") as? JsonObject
                val rowId = target?.strOrNull("rowId")?.toLongOrNull()
                // The turn's fileChanges flip to `reverted` (the desktop's rewindDialog outcome) —
                // an in-place upsert so the Undo-files gate closes authoritatively.
                if (rowId != null) {
                    val reverted = schemaRow(workspace.sessionId, rowId, RowKind.TURN_HEADER, "completedSuccess", extra = rowActionsTurnExtra("reverted"))
                    appendedRows[rowId.toString()] = reverted
                    pushRowUpserted(workspace.sessionId, reverted)
                }
                pushRevisionPatch()
                return buildJsonObject {
                    put("type", JsonPrimitive("applyFileRewind"))
                    put("applied", JsonPrimitive(true))
                    put("response", JsonPrimitive("Reverted 2 files"))
                    put("preview", buildJsonObject { put("files", JsonArray(emptyList())) })
                }
            }
            "setAssistantFeedback" -> {
                stateRevision.incrementAndGet()
                val target = payload?.get("target") as? JsonObject
                val rowId = target?.strOrNull("rowId")?.toLongOrNull()
                val feedback = payload?.get("feedback")
                if (rowId != null) {
                    // Echo the feedback onto the row (rows.ts `feedback`), null clears it.
                    val base = appendedRows[rowId.toString()]
                        ?: JsonObject(schemaRow(workspace.sessionId, rowId, RowKind.ASSISTANT_TEXT, "synthetic") + ("actions" to ROW_ACTIONS_ASSISTANT))
                    // JsonNull IS a JsonPrimitive — an explicit null clears, a string sets.
                    val set = feedback is JsonPrimitive && feedback !is JsonNull
                    val next = if (set) JsonObject(base + ("feedback" to feedback!!)) else JsonObject(base - "feedback")
                    appendedRows[rowId.toString()] = next
                    lastFeedback = if (set) (feedback as JsonPrimitive).content else null
                    pushRowUpserted(workspace.sessionId, next)
                }
                pushRevisionPatch()
            }
            // Phase 9 (§10.3): switch commands update the `config` echo + patch it —
            // the echo-reconciliation plane the phone's pickers reconcile against.
            "switchModelConfig" -> {
                stateRevision.incrementAndGet()
                val provider = payload?.strOrNull("provider") ?: return null
                val model = payload.strOrNull("model") ?: return null
                val thought = payload.strOrNull("thought") ?: return null
                val current = script.sessionConfig
                script.sessionConfig = buildJsonObject {
                    put("provider", JsonPrimitive(provider))
                    put("model", JsonPrimitive(model))
                    put("thought", JsonPrimitive(thought))
                    put("thoughtLevels", current?.get("thoughtLevels") ?: JsonArray(listOf(JsonPrimitive(thought))))
                    put("followupMode", JsonPrimitive(current?.strOrNull("followupMode") ?: "queue"))
                    put("mode", JsonPrimitive(current?.strOrNull("mode") ?: "build"))
                }
                pushStatePatch(buildJsonObject {
                    put("revision", JsonPrimitive(stateRevision.get()))
                    put("config", script.sessionConfig!!)
                })
            }
            "switchCollaborationMode" -> {
                stateRevision.incrementAndGet()
                val mode = payload?.strOrNull("mode") ?: return null
                val current = script.sessionConfig
                script.sessionConfig = buildJsonObject {
                    put("provider", JsonPrimitive(current?.strOrNull("provider") ?: "zai"))
                    put("model", JsonPrimitive(current?.strOrNull("model") ?: "glm-5.3"))
                    put("thought", JsonPrimitive(current?.strOrNull("thought") ?: "standard"))
                    put("thoughtLevels", current?.get("thoughtLevels") ?: JsonArray(listOf(JsonPrimitive("standard"))))
                    put("followupMode", JsonPrimitive(current?.strOrNull("followupMode") ?: "queue"))
                    put("mode", JsonPrimitive(mode))
                }
                pushStatePatch(buildJsonObject {
                    put("revision", JsonPrimitive(stateRevision.get()))
                    put("config", script.sessionConfig!!)
                })
            }        }
        return null
    }

    private fun handleCommandsPromise(id: Int, name: String?) {
        val result = script.commandsList
        if (result == null) {
            respondError(id, "fault.mock.unscripted:commands.list")
        } else {
            respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(result))
        }
    }

    private fun handleSubagentsPromise(id: Int, name: String?) {
        val result = script.subagentsList
        if (result == null) {
            respondError(id, "fault.mock.unscripted:subagents.list")
        } else {
            respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(result))
        }
    }

    /** Phase 4A: a revision-only patch (the CAS currency moved; no other key changed). */
    private fun pushRevisionPatch() {
        pushStatePatch(buildJsonObject { put("revision", JsonPrimitive(stateRevision.get())) })
    }

    /** Phase 4A observability (self-tests + rig): the last fork's child id / last feedback echo. */
    @Volatile var lastForkedSessionId: String? = null
    @Volatile var lastFeedback: String? = null
    /** Phase 4C seam: the most recent zcode-agent call's params per method (wire-shape pins). */
    private val recentV4Calls = HashMap<String, JsonObject?>()
    /** Phase 4D seam: every `zcode-task` channel call (method, params) in send order. */
    private val taskChannelLedger = ArrayList<Pair<String, JsonObject>>()
    fun taskChannelCallsForTest(): List<Pair<String, JsonObject>> = synchronized(taskChannelLedger) { ArrayList(taskChannelLedger) }
    fun lastV4CallParamsForTest(name: String): JsonObject? = synchronized(recentV4Calls) { recentV4Calls[name] }

    /** Test seam for the command-effect machinery (the socket path calls the same function). */
    internal fun applyCommandEffectForTest(type: String, env: JsonObject?): JsonObject? = applyCommandEffect(type, env)

    // ---- Phase 4B (J2/A3.2/B2.11): automation CRUD + run ledger (synthetic, INV-15/16).

    /** Runs recorded by runAutomationNow (newest first when listed after the scripted ones). */
    private val automationRunLedger = ArrayList<JsonObject>()

    /** One-shot: the next createAutomation answers the desktop's create-limit error. */
    @Volatile var automationCreateLimitArmed: Boolean = false

    /** Rows the mock's CRUD produces — the ZCodeAutomation channel shape the phone decodes. */
    private fun schemaAutomationRow(params: JsonObject, wsPath: String, automationId: String? = null): JsonObject {
        val id = automationId ?: "auto-created-${createdCount.incrementAndGet()}"
        val existing = script.listAutomations?.firstOrNull { (it.jsonObject["automationId"] as? JsonPrimitive)?.content == id }?.jsonObject
        val now = System.currentTimeMillis()
        val m = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        existing?.forEach { (k, v) -> m[k] = v }
        m["automationId"] = JsonPrimitive(id)
        m["title"] = params["title"] ?: existing?.get("title") ?: JsonPrimitive("Untitled")
        m["cronExpr"] = params["cronExpr"] ?: existing?.get("cronExpr") ?: JsonPrimitive("0 9 * * *")
        m["prompt"] = params["prompt"] ?: existing?.get("prompt") ?: JsonPrimitive("")
        params["mode"]?.let { m["mode"] = it }
        m["enabled"] = existing?.get("enabled") ?: JsonPrimitive(true)
        m["lifecycleStatus"] = existing?.get("lifecycleStatus") ?: JsonPrimitive("active")
        m["runCount"] = existing?.get("runCount") ?: JsonPrimitive(0)
        m["recurring"] = params["recurring"] ?: existing?.get("recurring") ?: JsonPrimitive(true)
        params["maxRuns"]?.let { m["maxRuns"] = it }
        params["endAt"]?.let { m["endAt"] = it }
        // scheduleRule: a present object sets it, an explicit null clears it, absence keeps it.
        when (val r = params["scheduleRule"]) {
            is JsonObject -> m["scheduleRule"] = r
            is JsonNull -> m.remove("scheduleRule")
            else -> Unit
        }
        params["modelSelection"]?.let { m["modelSelection"] = it }
        m["workspaceKey"] = JsonPrimitive(wsPath)
        m["workspacePath"] = JsonPrimitive(wsPath)
        m["locationKind"] = JsonPrimitive("local")
        m["dispatchStatus"] = JsonPrimitive("idle")
        m["dispatchAttempts"] = JsonPrimitive(0)
        m["createdAt"] = existing?.get("createdAt") ?: JsonPrimitive(now)
        m["updatedAt"] = JsonPrimitive(now)
        m["nextRunAt"] = JsonPrimitive(now + 3_600_000)
        return JsonObject(m)
    }

    /** A manual run lands as an in-flight ledger row (`automationId:manual:<uuid>`, no outcome yet). */
    private fun recordAutomationRun(automationId: String, trigger: String) {
        val now = System.currentTimeMillis()
        val run = buildJsonObject {
            put("runId", JsonPrimitive("$automationId:manual:${java.util.UUID.randomUUID()}"))
            put("automationId", JsonPrimitive(automationId))
            put("workspaceKey", JsonPrimitive(workspace.workspacePath))
            put("trigger", JsonPrimitive(trigger))
            put("dispatchStatus", JsonPrimitive("dispatched"))
            put("sessionId", JsonPrimitive(workspace.sessionId))
            put("attempts", JsonPrimitive(1))
            put("createdAt", JsonPrimitive(now))
            put("updatedAt", JsonPrimitive(now))
        }
        synchronized(automationRunLedger) { automationRunLedger.add(0, run) }
    }

    /** Self-test seam: finish the latest in-flight ledger run with an outcome. */
    internal fun completeLatestRunForTest(outcome: String) {
        synchronized(automationRunLedger) {
            val i = automationRunLedger.indexOfFirst { it["outcome"] == null }
            if (i >= 0) automationRunLedger[i] = JsonObject(automationRunLedger[i] + ("outcome" to JsonPrimitive(outcome)) + ("updatedAt" to JsonPrimitive(System.currentTimeMillis())))
        }
    }

    internal fun automationRunLedgerForTest(): List<JsonObject> = synchronized(automationRunLedger) { ArrayList(automationRunLedger) }

    private fun pushQueuePatch() {
        pushStatePatch(buildJsonObject {
            put("revision", JsonPrimitive(stateRevision.get()))
            put("queue", buildJsonObject {
                put("items", JsonArray(script.queueItems.orEmpty().map { qi ->
                    buildJsonObject {
                        put("queueItemId", JsonPrimitive(qi.queueItemId))
                        put("text", JsonPrimitive(qi.text))
                        put("kind", JsonPrimitive(qi.kind))
                        put("dispatch", buildJsonObject { put("state", JsonPrimitive(qi.dispatchState)) })
                    }
                }))
                put("autoDrain", JsonPrimitive(true))
            })
        })
    }

    private val createdCount = java.util.concurrent.atomic.AtomicInteger(0)
    private var lastRowId = 0L
    private fun nextRowId(): Long {
        lastRowId += 1
        return lastRowId + 1000 // past the scripted snapshot base
    }

    /**
     * Resolved-elsewhere race driver (§20.3 row 3): the mock resolves the pending
     * interaction WITHOUT any client command — the phone must flip its card to
     * "Resolved on desktop" and never submit.
     */
    fun resolveInteractionElsewhere() {
        val iid = script.pendingPermission?.interactionId ?: script.pendingUserInput?.interactionId ?: return
        script.pendingPermission = null
        script.pendingUserInput = null
        stateRevision.incrementAndGet()
        pushStatePatch(buildJsonObject {
            put("revision", JsonPrimitive(stateRevision.get()))
            put("pendingInteractions", JsonArray(emptyList()))
        })
    }

    private fun handleBridgeOpen(ws: okhttp3.WebSocket, payload: JsonObject) {
        val requestId = payload.str("requestId")
        lastBridgeSessionId = payload.str("bridgeSessionId")
        // One-shot error queue (recovery-test driver): pop → verified error shape.
        val forcedReason = synchronized(script.bridgeErrorReasons) { script.bridgeErrorReasons.removeFirstOrNull() }
        lastBridgeSessionId = payload.str("bridgeSessionId")
        lastBridgeWorkspaceKey = payload.str("workspaceKey")
        lastBridgeTaskId = payload.strOrNull("taskId")
        val missingTask = script.bridgeRequireTaskId && payload.strOrNull("taskId") == null
        // Round-5 field fidelity: a bridge carrying STALE discovery (the previous
        // connection's active workspace/task) is rejected — production behavior
        // observed as desktop-disconnected while the control plane kept answering.
        val expectedKey = script.bootstrapActiveWorkspaceKey ?: workspace.workspaceKey
        val expectedTask = script.bootstrapActiveTaskId ?: workspace.sessionId
        // FIELD (2026-10-04, vc33 — pair-consistency rule): a bridge whose (workspace,
        // task) pair is CONSISTENT WITH THE BOOTSTRAP TASK LIST is valid even when the
        // workspace differs from activeWorkspaceKey — the client resolves the bridged
        // workspace FROM the task (§7.11; the desktop's two view-state fields are
        // independent and may disagree). Stale detection now rejects only pairs that
        // match NEITHER the view state NOR the bootstrap task list.
        val bootstrapConsistent = script.bootstrapTasks.orEmpty().any { t ->
            t.taskId == lastBridgeTaskId && (lastBridgeWorkspaceKey == t.workspacePath)
        }
        val staleDiscovery = script.bridgeRequireTaskId && !missingTask && !bootstrapConsistent &&
            (lastBridgeWorkspaceKey != expectedKey || lastBridgeTaskId != expectedTask)
        if (forcedReason != null || missingTask || staleDiscovery) {
            val reason = forcedReason ?: if (missingTask) "session-not-found" else "desktop-disconnected"
            scope.launch { events.emit(ServerEvent.BridgeError(reason)) }
            sendRaw(
                RelayFrameCodec.encodeEnvelope(
                    json.encodeToString(JsonObject.serializer(), buildJsonObject {
                        put("zcode_type", JsonPrimitive("workspace-bridge-error"))
                        put("requestId", JsonPrimitive(requestId))
                        put("reason", JsonPrimitive(reason))
                        put("error", JsonPrimitive("synthetic: $reason"))
                    })
                )
            )
            return
        }
        val recovery = payload.strOrNull("recoveryId")
        currentBridgeId = payload.str("bridgeSessionId")
        scope.launch { events.emit(ServerEvent.BridgeOpened(payload.str("workspaceKey"), recovery)) }
        if (recovery != null) {
            // Bridge replay semantics (Phase 1 live-proven): re-opening with the same
            // recoveryId replays the bridge's recent logical frames.
            val replay = recentOutbound.toList()
            recentOutbound.clear()
            for (e in replay) sendRaw(e)
        }
        sendRaw(
            RelayFrameCodec.encodeEnvelope(
                json.encodeToString(JsonObject.serializer(), buildJsonObject {
                    put("zcode_type", JsonPrimitive("workspace-bridge-ready"))
                    put("requestId", JsonPrimitive(requestId))
                    put("bridgeSessionId", JsonPrimitive(currentBridgeId))
                    put("bridgeGeneration", JsonPrimitive(0))
                    put("bridge", buildJsonObject {
                        put("kind", JsonPrimitive("local"))
                        put("workspaceKey", JsonPrimitive(workspace.workspaceKey))
                        put("workspacePath", JsonPrimitive(workspace.workspacePath))
                        workspace.workspaceIdentity?.let { put("workspaceIdentity", JsonPrimitive(it)) }
                        put("initialTaskId", JsonPrimitive(workspace.sessionId))
                    })
                })
            )
        )
        if (script.sendInitializeAfterBridge) {
            sendLogicalFrame(initializeBytes())
        }
    }

    private fun initializeBytes(): ByteArray =
        RpcSerialization.encodeAll(
            listOf(RpcValue.Arr(listOf(RpcValue.Int(ChannelWire.ResponseType.Initialize.id))), RpcValue.Undefined)
        )

    /**
     * `zcode-task.listTasks({workspacePath, workspaceIdentity?}) → ZCodeTaskMeta[]`
     * (packages/services/src/session/zcodeTaskService.ts:379, docs/protocol/
     * phase10-direct.md §6) — the direct task-list plane fixture.
     */
private fun handleOffPeakTaskPromise(id: Int, name: String?) {
        if (name != "list") {
            respondError(id, "fault.mock.unknownMethod:off-peak-task-$name")
            return
        }
        val items = script.offPeakTaskList
        if (items == null) {
            respondError(id, "fault.mock.unscripted:offPeakTaskList")
        } else {
            respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Arr(items.map { RpcValue.Obj(it) }))
        }
    }

        private fun handleTaskPromise(id: Int, name: String?, arg: JsonObject?) {
        synchronized(taskChannelLedger) { taskChannelLedger.add((name ?: "?") to (arg ?: JsonObject(emptyMap()))) }
        when (name) {
            "listTasks" -> respond(
                id, ChannelWire.ResponseType.PromiseSuccess,
                RpcValue.Arr(script.directTasks.map { RpcValue.Obj(it) }),
            )
            "listPinnedTaskIds", "listArchivedTasks", "listPinnedTasks", "listDeletedTaskIds" ->
                respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Arr(emptyList()))
            // Phase 4D (L6.3+J4 / H1.1): the lifecycle + search methods (shipped 3.14.5).
            "deleteTask" -> {
                val taskId = arg?.str("taskId")
                if (taskId == null) respondError(id, "fault.mock.unscripted:deleteTask")
                else {
                    script.directTasks = script.directTasks.filterNot { (it.jsonObject["taskId"] as? JsonPrimitive)?.content == taskId }
                    respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(JsonObject(emptyMap())))
                }
            }
            "renameTask" -> {
                val taskId = arg?.str("taskId")
                val title = arg?.str("title")
                if (taskId == null || title == null) respondError(id, "fault.mock.unscripted:renameTask")
                else {
                    script.directTasks = script.directTasks.map {
                        if ((it.jsonObject["taskId"] as? JsonPrimitive)?.content == taskId) {
                            JsonObject(it.jsonObject + ("title" to JsonPrimitive(title)))
                        } else it
                    }
                    respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(JsonObject(emptyMap())))
                }
            }
            "archiveTask", "unarchiveTask" -> {
                val taskId = arg?.str("taskId")
                if (taskId == null) respondError(id, "fault.mock.unscripted:" + name)
                else respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(JsonObject(emptyMap())))
            }
            "listTaskList" -> {
                val search = arg?.str("search").orEmpty().lowercase()
                val hits = script.directTasks
                    .filter { search.isEmpty() || ((it.jsonObject["title"] as? JsonPrimitive)?.content?.lowercase()?.contains(search) == true) }
                    .map { JsonObject(it.jsonObject) }
                respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                    put("items", JsonArray(hits))
                    put("total", JsonPrimitive(hits.size))
                    put("hasMore", JsonPrimitive(false))
                }))
            }
            else -> respondError(id, "fault.mock.unknownTaskMethod:${name ?: "?"}")
        }
    }

    /**
     * Phase 4 coding-plan quota plane (ADR-020; v4-wire.md §13) — the
     * `usage-stats` ProxyChannel words. Every call is recorded as
     * (method, argKind, params) so the phone's §7 ARRAY-wrap convention is
     * pin-able; markCodingPlanResetHistoryRead returns Promise<void> (Undefined).
     */
    private fun handleUsageStatsPromise(id: Int, name: String?, arg: RpcValue?) {
        val argKind = when (arg) {
            is RpcValue.Arr -> "arr"
            is RpcValue.Obj -> "obj"
            else -> "other"
        }
        val params = argAsObject(arg) ?: JsonObject(emptyMap())
        script.usageStatsCalls.add(Triple(name ?: "?", argKind, params))
        when (name) {
            "getEntitlementSnapshot" -> scriptableObject(id, script.usageEntitlement, "getEntitlementSnapshot")
            "getCodingPlanResetStatus" -> {
                val queued = synchronized(script.resetStatusQueue) { script.resetStatusQueue.removeFirstOrNull() }
                scriptableObject(id, queued ?: script.codingPlanResetStatus, "getCodingPlanResetStatus")
            }
            "requestCodingPlanResetOpportunity" ->
                scriptableObject(id, script.resetOpportunity, "requestCodingPlanResetOpportunity")
            "useCodingPlanReset" -> scriptableObject(id, script.resetUse, "useCodingPlanReset")
            "markCodingPlanResetHistoryRead" ->
                respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Undefined)
            else -> respondError(id, "fault.mock.unknownMethod:usage-stats-${name ?: "?"}")
        }
    }

    private fun respond(id: Int, type: ChannelWire.ResponseType, data: RpcValue) {
        sendLogicalFrame(
            RpcSerialization.encodeAll(
                listOf(RpcValue.Arr(listOf(RpcValue.Int(type.id), RpcValue.Int(id))), data)
            )
        )
    }

    private fun respondError(id: Int, fault: String) {
        respond(
            id, ChannelWire.ResponseType.PromiseError,
            RpcValue.Obj(buildJsonObject { put("message", JsonPrimitive(fault)); put("name", JsonPrimitive("Error")) })
        )
    }

    /** Build + send a v4 topic frame through the REAL TopicWireFrameCodec (schema-true). */
    @Volatile private var lastSubscribeWorkspacePath: String? = null
    @Volatile var lastCommandWorkspacePath: String? = null
        private set
    @Volatile var lastCommandWorkspaceIdentity: String? = null
        private set
    @Volatile var boundClientId: String? = null
        private set
    @Volatile var lastCommandClientId: String? = null
        private set

    fun pushTopicFrame(sessionId: String, deliveryKind: DeliveryKind, snapshot: Boolean, fromSeq: Long, toSeq: Long) {
        if (script.enforceScopeRouting) {
            // The snapshot rides the MOST RECENT subscribe's workspace (single-session
            // mock): deliver only if a scoped listener for that workspace exists — the
            // per-workspace listener contract from FIELD-KILLER #5.
            val required = lastSubscribeWorkspacePath
            val routed = required != null && synchronized(listenScopesSeen) { listenScopesSeen.contains(required) }
            if (!routed) return // routed to nobody — exactly what production does
        }
        val listener = conversationListenerId ?: return
        val topic = "conversation/$sessionId"
        val frame = buildJsonObject {
            put("topic", JsonPrimitive(topic))
            put("subscriptionId", JsonPrimitive(subscriptionId))
            put("fromSeq", JsonPrimitive(fromSeq))
            put("toSeq", JsonPrimitive(toSeq))
            put("sentAt", JsonPrimitive(System.currentTimeMillis().toDouble()))
            put("payload", buildJsonObject {
                if (snapshot) {
                    put("kind", JsonPrimitive("snapshot"))
                    put("snapshot", buildJsonObject {
                        put("sessionId", JsonPrimitive(sessionId))
        val rows = JsonArray(
            snapshotRows(sessionId, script.snapshotRowCount ?: workspace.rowCount) + appendedRows.values.toList(),
        )
                        if (script.snapshotRowsWindowShape) {
                            // Production rowsWindowSchema: {window, totalCount, firstRowId}.
                            put("rows", buildJsonObject {
                                put("window", rows)
                                put("totalCount", JsonPrimitive(rows.size))
                                put("firstRowId", JsonPrimitive(if (script.snapshotWindowTruncated) 1L else script.snapshotRowIdBase))
                            })
                        } else {
                            put("rows", rows)
                        }
                        // Phase 7: verified A-side state keys (§9) when scripted.
                        stateBlock()?.let { st ->
                            for ((k, v) in st) put(k, v)
                        }
                    })
                    // §9: the snapshot IS the seq anchor — live deltas continue from it.
                    seqHeadNow = maxOf(seqHeadNow, toSeq)
                } else {
                    put("kind", JsonPrimitive("deltas"))
                    put("deltas", JsonArray(listOf(buildJsonObject {
                        put("op", JsonPrimitive("row.delta"))
                        put("rowId", JsonPrimitive(1))
                        put("path", JsonPrimitive("text"))
                        put("append", JsonPrimitive("…"))
                    })))
                }
            })
        }
        val wireFrames = TopicWireFrameCodec.encode(
            frame = frame,
            deliveryKind = deliveryKind,
            topic = topic,
            subscriptionId = subscriptionId,
            logicalFrameId = "lf-${System.nanoTime()}",
            logicalFrameOrdinal = 1,
        )
        for (wire in wireFrames) {
            val arg: RpcValue = if (script.topicEventDirect) {
                // Production shape: the event arg IS the TopicWireFrame (payload rides its own `frame` field).
                RpcValue.Obj(wireJson(wire))
            } else {
                // Phase-1 spike shape: {frame: topicFrame}.
                RpcValue.Obj(buildJsonObject {
                    put("topic", JsonPrimitive(topic))
                    put("frame", wireJson(wire))
                })
            }
            val bytes = RpcSerialization.encodeAll(
                listOf(
                    RpcValue.Arr(listOf(RpcValue.Int(ChannelWire.ResponseType.EventFire.id), RpcValue.Int(listener))),
                    arg,
                )
            )
            sendLogicalFrame(bytes)
        }
    }

    private fun wireJson(wire: TopicWireFrame): JsonObject = buildJsonObject {
        put("wireVersion", JsonPrimitive(wire.wireVersion))
        put("kind", JsonPrimitive(if (wire is TopicWireFrame.Complete) "complete" else "fragment"))
        put("deliveryKind", JsonPrimitive(wire.deliveryKind.name.lowercase()))
        put("logicalFrameId", JsonPrimitive(wire.logicalFrameId))
        put("logicalFrameOrdinal", JsonPrimitive(wire.logicalFrameOrdinal))
        put("topic", JsonPrimitive(wire.topic))
        put("subscriptionId", JsonPrimitive(wire.subscriptionId))
        when (wire) {
            is TopicWireFrame.Complete -> put("frame", wire.frame)
            is TopicWireFrame.Fragment -> {
                put("fragmentIndex", JsonPrimitive(wire.fragmentIndex))
                put("fragmentCount", JsonPrimitive(wire.fragmentCount))
                put("logicalBytes", JsonPrimitive(wire.logicalBytes))
                put("checksum", buildJsonObject {
                    put("algorithm", JsonPrimitive("crc32"))
                    put("value", JsonPrimitive(wire.checksum))
                })
                put("dataBase64", JsonPrimitive(Base64.getEncoder().encodeToString(wire.data)))
            }
        }
    }

    // ------------------------------------------------------------------ control plane

    private fun sendBootstrapResponse(ws: okhttp3.WebSocket, requestId: String) {
        if (script.bootstrapEmptyView) {
            sendTo(ws, raw = RelayFrameCodec.encodeEnvelope(
                json.encodeToString(JsonObject.serializer(), buildJsonObject {
                    put("zcode_type", JsonPrimitive("bootstrap-response"))
                    put("requestId", JsonPrimitive(requestId))
                    put("success", JsonPrimitive(true))
                    put("result", buildJsonObject {
                        put("windowControlSessionId", JsonPrimitive("synth-window-1"))
                        put("desktopAppVersion", JsonPrimitive(workspace.desktopAppVersion))
                        put("workspaces", JsonArray(emptyList()))
                        put("tasks", JsonArray(emptyList()))
                    })
                })
            ))
            return
        }
        sendTo(ws, raw = RelayFrameCodec.encodeEnvelope(
            json.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("zcode_type", JsonPrimitive("bootstrap-response"))
                put("requestId", JsonPrimitive(requestId))
                put("success", JsonPrimitive(true))
                put("result", buildJsonObject {
                    put("windowControlSessionId", JsonPrimitive("synth-window-1"))
                    put("desktopAppVersion", JsonPrimitive(workspace.desktopAppVersion))
                    put("initialViewState", buildJsonObject {
                        put("activeWorkspaceKey", JsonPrimitive(script.bootstrapActiveWorkspaceKey ?: workspace.workspaceKey))
                        put("activeTaskId", JsonPrimitive(script.bootstrapActiveTaskId ?: workspace.sessionId))
                        put("updatedAt", JsonPrimitive(System.currentTimeMillis()))
                    })
                    put("workspaces", JsonArray(listOf(buildJsonObject {
                        put("workspacePath", JsonPrimitive(workspace.workspacePath))
                        put("label", JsonPrimitive("Synthetic One"))
                        put("kind", JsonPrimitive("local"))
                        put("workspaceKey", JsonPrimitive(workspace.workspaceKey))
                    })))
                    put("tasks", script.bootstrapTasks?.let { override ->
                        JsonArray(override.map { it.toJson() })
                    } ?: JsonArray(listOf(
                        // PRODUCTION shape (report F5/G1): the legacy {id,title,status}
                        // row carries no workspacePath — the app's bootstrap parser keys
                        // tasks by workspaceIdentity ?: workspacePath, and a blank key is
                        // silently skipped (logged applied, zero rows persisted).
                        MockTask(
                            taskId = workspace.sessionId,
                            title = "Synthetic task",
                            displayStatus = "idle",
                            workspacePath = workspace.workspacePath,
                            workspaceLabel = "Synthetic One",
                        ).toJson(),
                    )))
                })
            })
        ))
    }

    private fun sendWorkspaceListResponse(ws: okhttp3.WebSocket, requestId: String) {
        sendTo(ws, raw = RelayFrameCodec.encodeEnvelope(
            json.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("zcode_type", JsonPrimitive("workspace-list-response"))
                put("requestId", JsonPrimitive(requestId))
                put("success", JsonPrimitive(true))
                put("result", buildJsonObject {
                    put("workspaces", JsonArray(listOf(buildJsonObject {
                        put("workspacePath", JsonPrimitive(workspace.workspacePath))
                        put("label", JsonPrimitive("Synthetic One"))
                        put("kind", JsonPrimitive("local"))
                        put("workspaceKey", JsonPrimitive(workspace.workspaceKey))
                    })))
                    put("activeWorkspaceKey", JsonPrimitive(workspace.workspaceKey))
                })
            })
        ))
    }

    // ------------------------------------------------------------------ plumbing

    private fun sendTo(ws: okhttp3.WebSocket, fields: Map<String, JsonPrimitive>? = null, raw: String? = null) {
        val text = raw ?: json.encodeToString(JsonObject.serializer(), JsonObject(fields ?: emptyMap()))
        try {
            ws.send(text)
        } catch (e: Exception) {
            // closed socket — mock-side noise
        }
    }

    private fun sendRaw(text: String) {
        terminalSocket?.let { sendTo(it, raw = text) }
    }

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.content ?: ""
    private fun JsonObject.strOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.content

    companion object {
        /** Server-side proof verification — EXACT desktop construction (INV-10 parity). */
        fun hmacProof(passHash: String, nonce: String, role: String, sid: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(passHash.encodeToByteArray(), "HmacSHA256"))
            return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal("${nonce}|${role}|${sid}".encodeToByteArray()))
        }
    }
}
