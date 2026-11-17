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

        /**
         * Pass-35 (Part AL): the production rowsWindowSchema.totalCount is the SESSION's
         * total row count — NOT the window size. The window-shape arm used to serve
         * `rows.size` here, so the phone's Load-earlier strip claimed "showing latest
         * 320 of 320" while rows remained above (the pass-3/4-era "armed 2000 → of 338"
         * observation). MOCK_ROWS_TOTAL sets the armed total; null keeps the legacy
         * window-size value for old fixtures.
         */
        var snapshotTotalCount: Long? = null

        /**
         * Pass-35 fault arm (MOCK_PAGE_FAULT=1): conversationRowsRangeV4 answers an
         * RPC error — the phone's Load-earlier error strip + Retry face becomes
         * drivable (it was never receipted in the pass0 series).
         */
        var pageRangeFault: Boolean = false
        /** Shuffle fragment envelopes inside each logical message (out-of-order storm). */
        var shuffleFragments: Boolean = false
        /** #228 class: inject one physical envelope > 1 MiB (once). */
        var injectOversizedEnvelope: Boolean = false
        /** S11 class: corrupt one fragment's crc32 value (once). */
        var corruptFragmentCrc: Boolean = false
        /** Close the socket after N outbound logical frames (disconnect storm driver). */
        var disconnectAfterFrames: Int? = null
        /**
         * Mock-fidelity debt #12 (pass 14): the frame-CLASS filter — close the socket
         * after N outbound DATA frames (topic/delta pushes), ignoring the poll/RPC
         * traffic that made the unfiltered counter fire mid-handshake (=2) or at
         * ~2.5 min of life (=30, every poll frame counted).
         */
        var disconnectAfterDataFrames: Int? = null
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

        /**
         * p41 (pass-41): when TRUE the relay's bootstrap-response AND
         * workspace-list-response serve a SECOND workspace row (/synthetic/ws-two,
         * "Synthetic Two") — unblocks the Manage sheet's reorder arrows and the
         * chips row's second chip (see [relayWorkspacesView]).
         */
        var relaySecondWorkspace: Boolean = false

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

        /**
         * F-PAIRSTATUS field fidelity (2026-10-26, vc46 S918B on the real relay): the
         * relay answers auth_ack with pair_status "pending" and holds it until the
         * DESKTOP approves — the cold-start reconnect after a fresh pairing observed
         * ~29 s of pair_status_query polling before "matched". When non-null,
         * auth_ack and pair_status_ack carry THIS value; [pairStatusMatchAfterMs]
         * (when >= 0) flips the state to "matched" after that delay, simulating the
         * desktop's approval latency. Defaults keep every historical suite at the
         * instant-"matched" shape.
         */
        @Volatile var initialPairStatus: String? = null
        @Volatile var pairStatusMatchAfterMs: Long = -1L

        /**
         * FIELD (2026-10-26, vc46 S918B, relay-4): the real relay answered an
         * in-flight control request with {"type":"error","code":"DEVICE_OFFLINE"} —
         * when true, the FIRST post-auth control request (bootstrap or bridge-open)
         * receives exactly that error frame instead of its normal answer.
         */
        @Volatile var errorFirstControlRequest: String? = null

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
        /** G30 (vc51 Phase 4B): the read-only bots viewer's scripted inventory. */
        var botsList: JsonObject? = null
        /** Scriptable off-peak tasks (off-peak-task channel list items; null = typed fault). */
        var offPeakTaskList: JsonArray? = null
        /**
         * Mock-fidelity debt #11 (pass 14): arm [MOCK_OFFPEAK_COMPLETE_MS] at the FIRST
         * `off-peak-task.list` (the G63 notifier's baseline seed) instead of relay
         * start — the relay-start anchor fired the flip BEFORE the app's first
         * CONNECTED (+12s default landed 5s early), so the queued→completed
         * transition never materialized across a single process life. Null = the
         * legacy relay-start anchor.
         */
        @Volatile var offPeakCompleteOnFirstListMs: Long? = null
        /** Scriptable plugins list (listPlugins result; null = typed fault). */
        var listPlugins: JsonObject? = null
        /** Scriptable skills reference catalog (getSkillReferenceCatalog result; null = typed fault). */
        var skillReferenceCatalog: JsonObject? = null
        /** Scriptable MCP server statuses (listMcpServerStatuses result; null = typed fault). */
        var mcpServerStatuses: JsonObject? = null
        /** G60 (vc52 Phase 12): the per-task token usage fixture (null = typed fault). */
        var taskTokenUsage: JsonObject? = null
        /** G68: the recorded opt-in self-report frames (test seam). */
        val recordedMobileViewState = ArrayList<JsonObject>()
        val recordedMobileDiagnostics = ArrayList<JsonObject>()

        // ---------------- Phase 4 coding-plan quota plane (GAP-3/4; ADR-020, v4-wire.md §13) ----------------
        /** Scriptable entitlement snapshot (usage-stats getEntitlementSnapshot result; null = typed fault). */
        var usageEntitlement: JsonObject? = null
        /** Scriptable reset status (getCodingPlanResetStatus result; null = typed fault). */
        var codingPlanResetStatus: JsonObject? = null
        /** Scriptable opportunity evaluation (requestCodingPlanResetOpportunity result; null = typed fault). */
        var resetOpportunity: JsonObject? = null
        /** Scriptable reset use (useCodingPlanReset result; null = typed fault). */
        var resetUse: JsonObject? = null
        /** Mock-fidelity debt #10 (pass 14): set when a scripted use succeeds — every
         *  later getCodingPlanResetStatus then serves the CONFIRMED progression (the
         *  consumed opportunity + the usedAt history), the phone's reconciliation
         *  lands COMPLETED, and the success face renders. Null = pre-use status. */
        @Volatile var codingPlanResetUsedAtMs: Long? = null
        /** Which window the scripted use consumed (the desktop's FIVE_HOUR | WEEK pair). */
        @Volatile var codingPlanResetUsedType: String = "FIVE_HOUR"
        /** Mock-fidelity debt #15 (pass 15): markCodingPlanResetHistoryRead CLEARS the
         *  unread cursor (the desktop's server behavior) — every later status read
         *  then serves hasUnreadHistory=false instead of the sticky true. */
        @Volatile var codingPlanResetHistoryRead: Boolean = false
        /** Mock-fidelity debt #16 (pass 15): how far a scripted reset drops the used
         *  window's entitlement meter (usage points; the desktop's server recomputes,
         *  the rig just needs the self-consistent direction). */
        var resetMeterDelta: Int = 10
        /** Pass-15 (debt #10's queue variant): seed ONE pre-consume copy of the
         *  scripted status into the queue at use time, so the FIRST post-use
         *  getCodingPlanResetStatus is not yet confirmed and the phone's
         *  reconciliation retry ladder (useRetryDelaysMs 250/750/1500) fires before
         *  the fallback path confirms. */
        @Volatile var resetQueuePreOnUse: Boolean = false
        /** Pass-29 rig arm (MOCK_USAGE): the G20 coding-plan usage charts
         *  (getCodingPlanUsageSnapshot result; null = typed fault → charts hidden). */
        var codingPlanUsageSnapshot: JsonObject? = null
        /** FIFO of reset statuses served to the phone (the desktop's post-use
         *  reconciliation polls until usedAt appears — a queue scripts the
         *  pre-consume → confirmed progression; falls back to [codingPlanResetStatus]). */
        val resetStatusQueue: ArrayDeque<JsonObject> = ArrayDeque()
        // ---- vc51 Phase-3 rig arms (G9/G13/G15 + RIG-2) ----
        /** G13: scripted sessions-index snapshot (sessions-index.ts shape; null = typed fault). */
        var sessionsIndexSnapshot: JsonObject? = null
        /** G13: a scripted `session.upserted` delta pushed this many ms after the index subscribe. */
        var sessionsIndexUpsertDelayMs: Long? = null
        var sessionsIndexUpsertDelta: JsonObject? = null
        /** G15: scripted backgroundBashOutputV4 results keyed by workId (null = typed unavailable). */
        val backgroundBashOutputs = LinkedHashMap<String, JsonObject>()
        /** G15: scripted backgroundWorks entries merged into the state block. */
        var backgroundWorks: JsonArray? = null
        /** G9: scripted sharedContextImport in the session state block. */
        var sharedContextImport: JsonObject? = null
        /**
         * RIG-2: seeded file-tree entries the workspace-file search filters (see fileTree).
         * Pass-21 (F-21-B arming debt): also builds the DIRECTORY listings the files.list
         * `readdir` reader needs — the raw path-keys-only seed made readdir(root) fault
         * (fault.file.notFound), so the §23.4 Files viewer could only ever show its
         * unavailable degrade on the rig. Each directory (root included) gets one entry
         * per distinct direct child; files keep their (empty) self-listing for search.
         */
        fun seedWorkspaceFiles(paths: List<String>) {
            for (p in paths) fileTree[p] = JsonArray(emptyList())
            // Mock-fidelity debt #2 (pass 16): every seeded path is a FILE in this
            // vocabulary (directories are derived from parents), so each one also gets
            // default readTextFile content — the listing-only seed made every non-README
            // open fault (fault.file.notFound) on the §23.4 Files viewer. Explicit
            // fileText entries (e.g. the pass-21 README fixture) keep precedence.
            for (p in paths) {
                fileText.putIfAbsent(p, seededFileContent(p))
            }
            val dirs = linkedSetOf("")
            for (p in paths) {
                var d = p.substringBeforeLast('/', "")
                while (d.isNotEmpty() && dirs.add(d)) d = d.substringBeforeLast('/', "")
            }
            for (d in dirs) {
                val prefix = if (d.isEmpty()) "" else "$d/"
                val entries = LinkedHashMap<String, JsonObject>()
                for (p in paths) {
                    val rest = p.removePrefix(prefix)
                    if (rest.isEmpty() || !p.startsWith(prefix)) continue
                    val name = rest.substringBefore('/')
                    val isDir = rest.contains('/')
                    val path = if (isDir) prefix + name else p
                    entries.putIfAbsent("$name/$isDir", buildJsonObject {
                        put("name", JsonPrimitive(name))
                        put("path", JsonPrimitive(path))
                        put("type", JsonPrimitive(if (isDir) "directory" else "file"))
                        put("isSymbolicLink", JsonPrimitive(false))
                    })
                }
                fileTree[d] = JsonArray(entries.values.toList())
            }
        }

        /** Deterministic paged-viewer content for a seeded path (debt #2). */
        private fun seededFileContent(path: String): String {
            val name = path.substringAfterLast('/')
            return (1..14).joinToString("\n") { i ->
                if (i == 1) "# $name — rig seed (pass-16 debt-#2 fix)"
                else "line $i of the synthetic content the mock serves for $path"
            } + "\n"
        }

        /**
         * Pass-46: the Phase-5B (A3.12 + A3.13) demo seed extracted from MockRelayMain
         * so the MOCK_TRAJECTORY mode arms mutate ONE source (the arms' self-tests seed
         * the exact shape the relay serves). plansState + modelTrajectory (2 records:
         * a tool_calls finish with a drill-in payload + a RateLimitError) + the
         * sessionMessages search corpus.
         */
        fun seedPhase5bDemo() {
            plansState = JsonObject(mapOf(
                "plans" to JsonArray(listOf(
                    JsonObject(mapOf(
                        "title" to JsonPrimitive("Ship the release"),
                        "steps" to JsonArray(listOf(
                            JsonObject(mapOf("content" to JsonPrimitive("Write the changelog"), "status" to JsonPrimitive("completed"))),
                            JsonObject(mapOf("content" to JsonPrimitive("Bump the version"), "status" to JsonPrimitive("in-progress"))),
                            JsonObject(mapOf("content" to JsonPrimitive("Tag and publish"), "status" to JsonPrimitive("pending"))),
                        )),
                    )),
                )),
            ))
            modelTrajectory = JsonObject(mapOf(
                "taskId" to JsonPrimitive("synth-task-1"), "available" to JsonPrimitive(true), "truncated" to JsonPrimitive(false),
                "sourceFiles" to JsonArray(listOf(JsonPrimitive("model-io-synth-task-1.jsonl"))),
                "records" to JsonArray(listOf(
                    JsonObject(mapOf(
                        "requestId" to JsonPrimitive("req-1"), "attempt" to JsonPrimitive(1),
                        "startedAt" to JsonPrimitive("2026-10-20T10:00:00Z"), "durationMs" to JsonPrimitive(4200),
                        "callSource" to JsonObject(mapOf("kind" to JsonPrimitive("main"))),
                        "model" to JsonObject(mapOf("modelId" to JsonPrimitive("glm-5.3"), "providerId" to JsonPrimitive("zai"))),
                        "response" to JsonObject(mapOf(
                            "finishReason" to JsonPrimitive("tool_calls"), "text" to JsonPrimitive("Checking the workspace state"),
                            // G33 (vc51 Phase 4D): the drill-in payloads (args/result heads)
                            "toolCalls" to JsonArray(listOf(JsonObject(mapOf(
                                "name" to JsonPrimitive("bash"),
                                "args" to JsonPrimitive("{\"command\":\"git status --short\",\"timeout\":30000}"),
                                "result" to JsonPrimitive("M core/session.ts\n?? docs/evidence/notes.md"),
                            )))),
                        )),
                    )),
                    JsonObject(mapOf(
                        "requestId" to JsonPrimitive("req-2"), "attempt" to JsonPrimitive(1),
                        "startedAt" to JsonPrimitive("2026-10-20T10:01:00Z"), "durationMs" to JsonPrimitive(950),
                        "callSource" to JsonObject(mapOf("kind" to JsonPrimitive("main"))),
                        "model" to JsonObject(mapOf("modelId" to JsonPrimitive("glm-5.3"), "providerId" to JsonPrimitive("zai"))),
                        "error" to JsonObject(mapOf("name" to JsonPrimitive("RateLimitError"), "message" to JsonPrimitive("429"))),
                    )),
                )),
            ))
            sessionMessages = JsonArray(listOf(
                JsonObject(mapOf("role" to JsonPrimitive("user"), "content" to JsonPrimitive("Ship the release this week"))),
                JsonObject(mapOf("role" to JsonPrimitive("assistant"), "content" to JsonPrimitive("I'll draft the changelog and bump the version"))),
            ))
        }

        /**
         * Pass-46 rig leg (MOCK_TRAJECTORY=<comma modes>): mutate the Phase-5B
         * trajectory seed —
         *  - `usage`: append a third record carrying `response.usage` token columns
         *    (N-BK-1(b)'s vc52 Phase 12 render — never live-driven);
         *  - `truncated`: flip the truncated badge flag;
         *  - `empty`: records=[] (the honest empty face; sessionMessages stays seeded
         *    so the search corpus still answers);
         *  - `unavailable`: modelTrajectory=null → the typed fault.mock.unscripted
         *    face (the phone renders trajectory_unavailable).
         * Applied in list order; unknown modes are ignored (fail-safe).
         */
        fun applyTrajectoryModes(modes: List<String>) {
            for (mode in modes) {
                when (mode) {
                    "usage" -> {
                        val base = modelTrajectory ?: continue
                        val records = (base["records"] as? JsonArray) ?: continue
                        val usageRecord = JsonObject(mapOf(
                            "requestId" to JsonPrimitive("req-3"), "attempt" to JsonPrimitive(2),
                            "startedAt" to JsonPrimitive("2026-10-20T10:02:00Z"), "durationMs" to JsonPrimitive(12_400),
                            "callSource" to JsonObject(mapOf("kind" to JsonPrimitive("main"))),
                            "model" to JsonObject(mapOf("modelId" to JsonPrimitive("glm-5.3"), "providerId" to JsonPrimitive("zai"))),
                            "response" to JsonObject(mapOf(
                                "finishReason" to JsonPrimitive("stop"), "text" to JsonPrimitive("Release checklist drafted"),
                                "usage" to JsonObject(mapOf(
                                    "inputTokens" to JsonPrimitive(1240), "outputTokens" to JsonPrimitive(356),
                                    "totalTokens" to JsonPrimitive(1596), "cacheReadTokens" to JsonPrimitive(800),
                                    "reasoningTokens" to JsonPrimitive(112),
                                )),
                            )),
                        ))
                        modelTrajectory = JsonObject(base.jsonObject + ("records" to JsonArray(records + usageRecord)))
                    }
                    "truncated" -> {
                        val base = modelTrajectory ?: continue
                        modelTrajectory = JsonObject(base.jsonObject + ("truncated" to JsonPrimitive(true)))
                    }
                    "empty" -> {
                        val base = modelTrajectory ?: continue
                        modelTrajectory = JsonObject(base.jsonObject + ("records" to JsonArray(emptyList())))
                    }
                    "unavailable" -> modelTrajectory = null
                }
            }
        }

        /**
         * Pass-47 rig leg (MOCK_TERMINAL_OUT=<mode>): seed the scripted terminal
         * output queue drained at onDynamicData-listen registration (create's 150 ms
         * flush is the fallback) — the FIRST driveable output face for the
         * never-driven terminal plane (ZERO report hits in 46 passes).
         *  - `plain`: workspace banner + echo/prompt lines (no SGR);
         *  - `ansi`: SGR-rich lines (bold, fg colors) exercising the view's palette +
         *    bold spans (terminalColor/AnnotatedString).
         * Unknown/blank modes fall back to `plain` (fail-safe).
         */
        fun terminalSeedOutput(rawMode: String?) {
            val mode = rawMode?.trim()?.lowercase()?.takeUnless { it.isEmpty() } ?: "plain"
            // Each entry is a STREAM chunk: it must end with \n or the phone's ring
            // line-buffer joins the pushes into one carry line (live-verified pass 47).
            val lines = if (mode == "ansi") {
                listOf(
                    "\u001B[1m\u001B[34mrig-p47@mock\u001B[0m workspace /synthetic/ws-one\n",
                    "\u001B[32m$ echo attached\u001B[0m\n",
                    "docs  src  \u001B[1;34mbuild\u001B[0m  README.md\n",
                    "\u001B[33mrig-p47 ready\u001B[0m\n",
                )
            } else {
                listOf(
                    "rig-p47 workspace /synthetic/ws-one\n",
                    "$ echo attached\n",
                    "attached\n",
                    "rig-p47 ready\n",
                )
            }
            synchronized(terminalOutput) { lines.forEach { terminalOutput.addLast(it) } }
        }

        /** Pass-47 rig leg (MOCK_TERMINAL_CAP_OFF): the hello's `localTerminal` bit —
         *  the CapabilityOff face ("this desktop does not expose a local terminal")
         *  is structurally unreachable while the hello hardcodes true. */
        fun helloLocalTerminal(capOffRaw: String?): Boolean = capOffRaw?.trim() != "1"


        /** Recorded usage-stats promise calls: (method, argKind "arr"|"obj"|"other", unwrapped params).
         *  The GAP-3 wire test pins the §7 ARRAY-wrap convention through this. */
        val usageStatsCalls = java.util.concurrent.CopyOnWriteArrayList<Triple<String, String, JsonObject>>()
        /** Read marker (markCodingPlanResetHistoryRead) arrival count. */
        val resetHistoryReadCount = java.util.concurrent.atomic.AtomicInteger(0)
        /** Channels the mock REFUSES (authorization-refused fallback fixtures, §13.3). */
        var refuseChannels: Set<String> = emptySet()
        /** Reject attachment transactions with the verified oversize/grammar faults. */
        var rejectAttachments: Boolean = false
        /** Pass-30 rig arm (MOCK_ATTACHMENT_FAIL): which attachment-transaction phase
         *  answers a server fault — "begin", "chunk", or "commit". Blank = healthy.
         *  The fault is an RPC ERROR (respondError), so the app's transport throws
         *  TransportFailure → the port's GENERIC Failed path (chip failed with the
         *  payload RETAINED — the Retry face stays live). */
        @Volatile var attachmentFailPhase: String? = null
        /** Pass-30 rig arm (MOCK_ATTACHMENT_CHUNK_DELAY_MS): delay before answering
         *  each attachmentChunkV4 — widens the ↑ NN% uploading-chip window so the
         *  progress render is catchable on the rig. */
        @Volatile var attachmentChunkDelayMs: Long = 0L
        /** Pass-32 rig arm (MOCK_ATTACHMENT_KEEP_AFTER_ABORT): attachmentAbortV4 does
         *  NOT delete the staging entry, so post-abort chunks are accepted and the
         *  transaction completes/commits — the AH.D "server that blindly accepted
         *  post-abort chunks" shape that resurrects the user-removed chip as
         *  ✓ READY (F-P31-A's second variant, previously undrivable). */
        @Volatile var attachmentKeepAfterAbort: Boolean = false
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
        /** I1: the queue's drain state the `setAutoDrain` word flips (echoed in queue patches). */
        @Volatile var autoDrain: Boolean = true
        /** Pass-11 rig leg (MOCK_REFUSE_COMMANDS): command types answered respondError
         * BEFORE any effect/ack — the goal verbs' SendNotice.GOAL_*_REFUSED degrade branches. */
        @Volatile var refuseCommandTypes: Set<String>? = null
        /** Mock-fidelity debt #1 (pass 13, MOCK_UNKNOWN_INTERACTION_IDS): when armed,
         *  resolveInteraction answers the typed unknown-id rejection instead of the old
         *  unconditional accept — the app's answer-with-error face becomes drivable. */
        @Volatile var unknownInteractionIdsArm: Boolean = false
        /** Verified inputRoutingSchema.mode. */
        @Volatile var inputRoutingMode: String = "startNow"
        /** Pass-17 rig arm (debt #18, MOCK_INPUT_ROUTING): when armed, the send/stop
         *  handler flips are SUPPRESSED and the armed mode stays sticky — the phone's
         *  CHOICE held-queue disposition dialog (G50) becomes drivable on the rig
         *  (the mock otherwise only ever serves startNow/enqueue). */
        @Volatile var inputRoutingArmSticky: Boolean = false
        /** Pass-19 rig arm (debt #20, MOCK_QUEUE_GHOST_APPEND_MS): when armed, a
         *  trio-bearing sendText validates expectedHeldQueueItemIds set-equal against
         *  the live queue (the host's applyHeldQueueDisposition) and answers the
         *  desktop-faithful CAS failure ack; arming also schedules the ONE-SHOT
         *  silent ghost append (no patch) that moves the queue under the phone's
         *  state — the race the G50 confirmation guard exists for. */
        @Volatile var heldQueueCasArm: Boolean = false
        @Volatile var heldQueueGhostDelayMs: Long = 1_000
        @Volatile var heldQueueGhostFired: Boolean = false
        /** Pass-20 rig arm (F-P20-A + the stale-face sweep, MOCK_CAS_STALE_TYPES): the
         *  listed command types answer the host's CAS-mismatch shape
         *  {status:"stale", reasonCode:"proto.staleRevision"} — the phone's typed
         *  SESSION_CHANGED stale faces become drivable on the rig. */
        @Volatile var casStaleTypes: Set<String>? = null
        /** Pass-37 rig arm (MOCK_FEEDBACK_FAIL=1): setAssistantFeedback answers the
         *  host's failed ack {status:"failed", reasonCode:"fault.feedback.fail"} — the
         *  phone's keyed FEEDBACK_REFUSED notice ("The desktop refused the feedback —
         *  nothing changed.") becomes drivable. Mutates nothing. */
        @Volatile var feedbackFail: Boolean = false
        /** Pass-39 rig arm (MOCK_ARCHIVED_FAULT=1): listArchivedTasks answers the
         *  typed RPC ERROR fault.archived.rig — the phone's guarded() maps it to null
         *  and the archived viewer renders its UNAVAILABLE face (the never-driven
         *  third face of pass 38's coverage list). Mutates nothing. */
        @Volatile var archivedFault: Boolean = false
        /** Pass-39 rig arm (MOCK_UNREAD_CAS=1): setTaskUnread(unread=false) VALIDATES
         *  the client's expectedUnreadAt against the stored stamp (the desktop's
         *  host-side CAS); a mismatch answers the typed RPC ERROR
         *  fault.unread.stale:expectedUnreadAt and mutates nothing — the phone's
         *  mark-read refusal face ("Couldn't update the task — try again") becomes
         *  drivable. Unarmed, the arg is logged but ignored (pass-38 behavior). */
        @Volatile var unreadCas: Boolean = false
        /** Pass-39 force mode (MOCK_UNREAD_CAS=force): the CAS comparison drifts the
         *  stored stamp (expected+1) so EVERY mark-read mismatches — the deterministic
         *  desktop-diverged-elsewhere simulator for the refusal-face leg (the real
         *  comparison mode is =1). */
        @Volatile var unreadCasForce: Boolean = false
        /** Pass-22 rig arm (Part X leftover, MOCK_SET_THOUGHT_LEVEL_STALE): the effort
         *  switch rides the PROTOCOL method (v4Call setThoughtLevel), whose host layer
         *  CAS-checks via FQ and answers a JSON-RPC ERROR on mismatch — NOT a
         *  {status:"stale"} ack. Armed, the mock answers the host-faithful Dl shape
         *  {message:"Session state revision mismatch", code:-32009,
         *  data:{actualRevision, expectedRevision}} and mutates nothing. */
        @Volatile var setThoughtLevelStale: Boolean = false
        /** Pass-22 rig arm (Part X leftover, MOCK_COMPACT_COMMAND): the host's
         *  command-plane compact handler (vMc) — under enqueue/guide/choice the
         *  compact SELF-QUEUES (a kind:"compact" queue item via steerTurn
         *  delivery:"queue") and answers accepted; a second compact while one is
         *  queued throws compactOperationLock (the dispatcher's typed rejected ack). */
        @Volatile var compactCommandArm: Boolean = false
        /** Pass-23: the passthrough compact's completed:manual marker delay (tests shrink it). */
        @Volatile var compactRunMarkerDelayMs: Long = 900L
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
        /** G71 (vc52 Phase 12): the hooks-monitor + subagent snapshot rows arm. */
        var hooksRowsDemo: Boolean = false

        /**
         * Pass-33 rig (Part AJ): the never-driven row-kind render faces in ONE arm —
         * an openable artifact row (bytes seeded for the download affordance), a
         * quiet-fallback artifact row (unknown extension, ref UNSEEDED so the open
         * rides the download-failure path), an unknown-kind tolerance row (the
         * INV-8 §19.5 face), a non-compact timelineMarker (the generic quiet row),
         * and a STREAMING reasoning row (the auto-expanded face). Default off.
         */
        var rowKindsDemo: Boolean = false

        /**
         * Pass-34 rig (Part AK): the toolCall row's PER-TOOL-KIND render matrix in ONE
         * arm — a CHANGES group (Edit+Write with path inputs + durations → "N files"
         * count), an EXECUTE group (Bash + BashOutput — the taxonomy-hole name so the
         * no-subtitle fallback renders), an ERROR card (error{code,message} +
         * actions.canRetry → the G66 recovery card), two >2k-preview Grep rows (one
         * whose slice is SERVED via [toolCallSlices], one deliberately unscripted so
         * the preview-copy fallback face drives), and the single-card status faces
         * (cancelled / running / pendingApproval / unknown-status). Default off.
         */
        var rowToolsDemo: Boolean = false

        /**
         * Pass-34: the SERVED `getTaskSnapshotToolCallsSlice` bodies — keyed by
         * toolCallId; a requested id ABSENT here answers `fault.mock.unscripted`
         * (the honest unscripted shape the app's lazy fetch must degrade from).
         */
        val toolCallSlices: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()

        /**
         * I2.4 (Phase 6A): stamp the context-compaction lifecycle into the NEXT
         * snapshot — three `timelineMarker` rows (`marker.type == "compact"`):
         * started:auto, completed:auto with token counts, failed:manual — the
         * phone's notice row renders per phase and offers Retry on the failed one.
         */
        var compactionDemo: Boolean = false

        // ---------------- Phase 10 (direct surface, docs/protocol/phase10-direct.md) ----------------
        /** Simulate the 401 token gate on /api/server-info + /ws (auth-class fixtures). */
        var directRejectToken: Boolean = false
        /** Scripted server-info workspaces (default = the single synthetic workspace). */
        var directWorkspaces: List<Pair<String, String>>? = null
        /** Scripted zcode-task.listTasks result rows (ZCodeTaskMeta subset). */
        var directTasks: List<JsonObject> = emptyList()

        // ---------------- Phase 5A (A3.8–A3.10) task management state ----------------
        /** Pinned / unread / archived task ids the lifecycle words mutate (rig round-trips). */
        val pinnedTaskIds: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        val unreadTaskIds: MutableMap<String, Long> = java.util.concurrent.ConcurrentHashMap()
        val archivedTaskIds: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        /** Custom groups `{id, title, color, createdAt, updatedAt}` + membership (groupId → taskIds). */
        val taskGroups: MutableList<JsonObject> = java.util.Collections.synchronizedList(ArrayList())
        val taskGroupMembers: MutableMap<String, MutableList<String>> = java.util.concurrent.ConcurrentHashMap()
        /** Off-peak ids the `pauseTask`/`continueTask` channel words have paused. */
        val offPeakPausedIds: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        /** L6.2: `cancelTask` marks terminal — the next `list` reports `cancelled`. */
        val offPeakCancelledIds: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        /** `resumeTask` calls (taskId) — the "continue a finished task" word. */
        val resumedTaskIds: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())

        // ---------------- vc51 Phase 5 (RIG-3) ----------------
        /**
         * RIG-3 (MOCK_INTERACTION_PUSH_MS): a FRESH approval pushed AFTER the first
         * conversation subscribe — the rig app backgrounds within the window and
         * receives the state patch off-foreground, exercising the FRESH notification
         * path (shade actions included; the A12 re-post path is pinned on the app side).
         */
        @Volatile var interactionPushDelayMs: Long? = null
        @Volatile var interactionPushId: String = "mock-approval-push-1"
        @Volatile var interactionPushTool: String = "Bash"
        /**
         * RIG-P1 (vc52 Phase 14, N-BE-6): the scheduled fresh-push interaction is
         * EXCLUDED from every served snapshot until the scheduled patch has actually
         * fired — `MOCK_INTERACTION_PUSH_MS=0` used to race the patch ahead of the
         * initial snapshot, so the "fresh push" leg silently degraded to
         * "was in the snapshot". After delivery the id serves in snapshots again
         * (a reconnecting client must still see the pending interaction).
         */
        @Volatile var interactionPushDelivered: Boolean = false

        // ---------------- G77 (vc52 Phase 7, RIG-P1): AskUserQuestion auto-resolution ----------------
        /**
         * The `autoResolution` arm served on every pending permission/userInput entry:
         * null = none (pre-RIG-P1 shape) | "countdown" (visibleCountdown, the desktop's
         * 5-minute fuse — the entry is DROPPED once the fuse fires, the auto-continue)
         * | "grace" (hiddenGrace; the phone shows no countdown and no Keep-waiting yet)
         * | "snoozed" (the snoozed state; "Keep waiting" is already done)
         * | "expired" (visibleCountdown with a deadline in the past — the
         * already-expired face: the phone must render non-actionable).
         */
        @Volatile var autoResolutionArm: String? = null
        /** Fuse length in ms — the desktop policy default is 300_000 (5 minutes). */
        @Volatile var autoResolutionMs: Long = 300_000L
        /** The fuse's start (visibleCountdown/hiddenGrace/snoozed all carry startedAt). */
        @Volatile var autoResolutionStartedAtMs: Long = System.currentTimeMillis()
        /** snoozeInteractionAutoResolution arrived — the live arm flips to snoozed. */
        @Volatile var autoResolutionSnoozed: Boolean = false

        // ---------------- G48+G51 (vc52 Phase 11) the enforcing capability gate ----------------
        /** MOCK_ENFORCE_RUN_DELTAS: stream workflowRun deltas ONLY to clients that declared the bit. */
        @Volatile var enforceWorkflowRunDeltas: Boolean = false
        /** G43-P13: a scripted resync fault (e.g. fault.subscription.notOwned) for the ladder's fallback face. */
        @Volatile var resyncIndexFault: String? = null

        // ---------------- Pass-16 stall arm (the P.A2 watch-note: a REAL frames>0 stall) ----------------
        /**
         * MOCK_TURN_STALL: after the first conversation subscribe, emit
         * [turnStallLiveFrames] live row frames, then keep journaling rows WITHOUT
         * emitting — a genuine mid-turn delivery stall (a frames>0 resync backlog)
         * the stream watchdog must resync out of. P.A2's replay was frames=0; this
         * is the shape that answers "does a REAL stall surface anything".
         */
        @Volatile var turnStall: Boolean = false
        /** Live frames emitted before the silent phase begins. */
        @Volatile var turnStallLiveFrames: Int = 2
        /** Delay between live frames AND between silent backlog rows (ms). */
        @Volatile var turnStallEveryMs: Long = 1_500L
        // ---------------- G53 + N-BG-2 (vc52 Phase 11) the git depth + the refusal surface ----------------
        /**
         * G53: the scripted `getBranchComparison` result ({baseRef, headRef,
         * comparisonLabel, changes: [...]}) — null = the verb faults
         * fault.git.notAvailable (the unscripted face the rig used to serve).
         */
        @Volatile var gitBranchComparison: JsonObject? = null
        /** G53 plumbing: the scripted getIgnoredPaths STRING ARRAY (null = the typed fault). */
        @Volatile var gitIgnoredPaths: JsonArray? = null
        /** G53 plumbing: the scripted getWorkspaceRepositoryInfo result (null = the typed fault). */
        @Volatile var gitWorkspaceRepositoryInfo: JsonObject? = null
        /**
         * N-BG-2 (vc52 Phase 11): the deliberate git-refusal arms — a method-name →
         * fault map the mock answers with respondError so the phone's SURFACING is
         * drivable on the rig (the recorded live face: the Generate button did
         * nothing, the Changes/History tabs rendered empty).
         */
        @Volatile var gitRefusals: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()
        /** Scheduled workflowRun.updated/removed push delays (ms) — the rig's scripted stream. */
        @Volatile var runDeltaUpdateMs: Long = 4_000L
        @Volatile var runDeltaRemoveMs: Long = 14_000L
        /**
         * Mock-fidelity debt #13 (pass 14): anchor the delta schedule at the FIRST
         * conversation subscribe instead of relay start — the relay-start anchor raced
         * every reconnect (the deltas fired before the app ever subscribed). False =
         * the legacy relay-start anchor.
         */
        @Volatile var runDeltasAnchorSubscribe: Boolean = false

        // ---------------- Phase 5A (A3.3 + L6.4) workflows ----------------
        /** `listSavedWorkflows` payload (BJ shape); null = unscripted → typed fault (hub hidden). */
        var savedWorkflows: JsonObject? = null
        /** `listSavedWorkflowRuns` items (cz shape) — appended when `startSavedWorkflow` runs. */
        val savedWorkflowRuns: MutableList<JsonObject> = java.util.Collections.synchronizedList(ArrayList())
        /** The session's runs (`conversationWorkflowRunsV4` Br shape) + per-run events (Rl). */
        val workflowRuns: MutableList<JsonObject> = java.util.Collections.synchronizedList(ArrayList())
        val workflowRunEvents: MutableMap<String, MutableList<JsonObject>> = java.util.concurrent.ConcurrentHashMap()
        /** Every `startSavedWorkflow` envelope payload the mock saw (name/scope/args). */
        val startedWorkflows: MutableList<JsonObject> = java.util.Collections.synchronizedList(ArrayList())

        // ---------------- Phase 5B (A3.11) ----------------
        /** `conversationFileRewindPreviewV4` result (nt shape; null = typed fault → copy-only confirm). */
        var rewindPreview: JsonObject? = null
        /** A3.14 rig: a pending AskUserQuestion interaction stamped into snapshots + state. */
        var pendingQuestion: JsonObject? = null
        /** ADR-022 rig: the writes the armed terminal tier sent. */
        val terminalWrites: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())

        // ---------------- ADR-024 (Phase 5D): hooks trust ----------------
        /** Scripted workspaceHookReview payload (the yS shape); null = no review pending. */
        var hookReview: JsonObject? = null
        /** Pass-45 (p45 hooks-second): the SECOND task's review (MOCK_HOOKS_SECOND=1)
         *  — the hooks viewer's per-task group separation (hooks_group_<taskId>) needs
         *  two ledger entries; one seed could only ever render one group. Distinct
         *  reviewItemIds required (hookTrustStates is shared). */
        var hookReviewSecond: JsonObject? = null
        /** Per-item trust states the review commands mutate (rig round-trips). */
        val hookTrustStates: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()
        var hookReviewsResolved: Boolean = false

        // ---------------- Phase 5B (A3.12 + A3.13) ----------------
        /** `conversationPlansV4` result (plans projection; null = typed fault → the panel hides). */
        var plansState: JsonObject? = null
        /** `getModelTrajectory` result (readModelTrajectory shape; null = typed fault). */
        var modelTrajectory: JsonObject? = null
        /** `readSessionMessages` items (role/content) — the search corpus. */
        var sessionMessages: JsonArray? = null

        /**
         * Pass-46 rig leg: the conflict arm's served paths (the `restoreBetweenCheckpoints`
         * non-force answer under `checkpointConflicts`). Default stays the pass-27 single
         * `src/config.ts`; MOCK_CHECKPOINT_CONFLICT_FILES widens it so the phone's
         * `conflicts.take(3)` body slice becomes drivable (>3 files).
         */
        @Volatile var checkpointConflictFiles: List<String> = listOf("src/config.ts")

        // ---------------- Phase 5B (A3.11) git checkpoints ----------------
        val checkpoints: MutableList<JsonObject> = java.util.Collections.synchronizedList(ArrayList())
        /** A3.15: per-task assistant feedback (`setAssistantMessageFeedback` snapshots). */
        val assistantFeedback: MutableMap<String, MutableList<String?>> = java.util.concurrent.ConcurrentHashMap()

        // ---------------- ADR-021 (Phase 5D): git write tier ----------------
        val stagedPaths: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())
        val discardedPaths: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())
        /**
         * p40 (pass-40): the ABSOLUTE fixture paths a successful `commit` consumed.
         * The phone sends workspaceRelativePath keys on stage/unstage/discard but the
         * fixture rows key `path` absolutely, so commit records the row's own `path`
         * and servedGitChanges() skips the consumed rows (the desktop drops committed
         * files from the changes list — the phone's post-commit fetchChanges() must
         * see the shrink, not the same three rows again).
         */
        val committedPaths: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())
        val commits: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())
        var generatedCommitMessage: JsonObject? = null
        var gitPushFails: Boolean = false
        /** A scripted typed issue (nt-style JsonObject with code/message) — non-null = refusals. */
        var gitBranchIssue: JsonObject? = null
        /**
         * RIG-5 (vc51 Phase 5): the branch the last SUCCESSFUL switch/create ack moved
         * to — the served `getRepositorySummary`/`getLocalBranches` merge it so the Git
         * pane's header follows the switch (the recorded ack-without-mutate fidelity
         * class of the Phase-4 exit-gate note).
         */
        @Volatile var currentGitBranch: String? = null
        var gitIdentityMissing: Boolean = false
        var checkpointDiffFiles: List<String> = listOf("src/version.ts", "scratch/probe.txt")
        /** Scripted conflict on a non-forced restore (the force-overwrite branch). */
        var checkpointConflicts: Boolean = false
        /** One-shot direct-socket close after N binary frames (loss class). */
        var directDisconnectAfterFrames: Int? = null
    }

    /**
     * Verified pendingInteraction permission payload (snapshot.ts §9). RIG-P1
     * (vc52 Phase 14): `createdAt` is REQUIRED on the desktop schema — the phone's
     * `?: clock()` fallback re-stamps the age on every reconnect when the wire
     * omits it — so the slot captures its timestamp ONCE and every snapshot/patch
     * serves that same value (stable across app SIGKILL + reconnect).
     */
    data class PendingPermission(
        val interactionId: String,
        val toolName: String = "Bash",
        val summary: String = "Run a synthetic command?",
        val options: List<String> = listOf("allowOnce", "allowAlways", "deny"),
        val createdAtMs: Long = System.currentTimeMillis(),
    )

    /** Verified pendingInteraction userInput payload (snapshot.ts §9). RIG-P1: same stable-createdAt rule. */
    data class PendingUserInput(
        val interactionId: String,
        val prompt: String = "Synthetic question?",
        /** optionId list; empty + freeText=true = free-form ask. */
        val options: List<String> = listOf(),
        val freeText: Boolean = true,
        val createdAtMs: Long = System.currentTimeMillis(),
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
        // A3.8 windowHostController row schema (the app's BootstrapParser reads both
        // on the relay bootstrap): emitted only when non-default so the default row
        // keeps the exact live-corpus key set (INV-8).
        val pinned: Boolean = false,
        val unreadAtMs: Long? = null,
        /**
         * F-ROUND26 (field-fidelity): production task rows carry the §7.11 identity —
         * the app's bridge resolution takes the ROW's workspace as authoritative
         * (vc33 consistent-pair rule) — emitted only when non-null so every scripted
         * row keeps its historical shape.
         */
        val workspaceIdentity: String? = null,
        /**
         * RIG-P3 (vc52 Phase 14, N-BD-5): the bootstrap task entry's optional
         * `provider` (the desktop's per-task model/provider chip) — the phone decodes
         * it since Phase 13 (24df8f25) and renders the badge; emitted only when
         * non-null so the default row keeps the live-corpus key set (INV-8).
         */
        val provider: String? = null,
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
                workspaceIdentity?.let { put("workspaceIdentity", JsonPrimitive(it)) }
                provider?.let { put("provider", JsonPrimitive(it)) }
                if (pinned) put("pinned", JsonPrimitive(true))
                unreadAtMs?.let { put("unreadAt", JsonPrimitive(it)) }
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
            // RIG DEBUG (2026-10-28 fresh-pairing walk): print both proofs — key/nonce inputs only, never a production secret path.
            println("MOCK: auth proof mismatch received=${proof} expected=${expectedProof}")
            sendTo(ws, mapOf("type" to JsonPrimitive("error"), "code" to JsonPrimitive("AUTH_FAILED"), "message" to JsonPrimitive("mock: proof mismatch")))
            scope.launch { events.emit(ServerEvent.AuthRejected("proof_mismatch")) }
            return
        }
        // Single-terminal rule: a second authenticated terminal kicks the first (Q10).
        // RIG-FIDELITY (field rig, 2026-10-27): a PRIOR terminal whose client process
        // died leaves a half-dead socket here — send()/close() on it used to throw
        // BEFORE the new terminal's auth_ack was ever written, and every subsequent
        // connection timed out at auth_ack for 15 s (the "restart the mock after an
        // emulator kill" rig rule). The kick must never break the new terminal's auth.
        val prior = terminalSocket
        if (prior != null && prior !== ws && script.kickFirstTerminalOnSecond) {
            runCatching {
                prior.send("""{"type":"error","code":"KICKED","message":""}""")
                prior.close(1000, "kicked by mock")
                scope.launch { events.emit(ServerEvent.TerminalKicked("second terminal authenticated")) }
            }
        }
        terminalSocket = ws
        conversationScopeRegistered = false // fresh connection: scope must be re-registered
        val initialStatus = script.initialPairStatus
        pairStatus = initialStatus ?: "matched"
        if (initialStatus != null && script.pairStatusMatchAfterMs >= 0) {
            scope.launch {
                kotlinx.coroutines.delay(script.pairStatusMatchAfterMs)
                pairStatus = "matched"
            }
        }
        sendTo(ws, mapOf("type" to JsonPrimitive("auth_ack"), "pair_status" to JsonPrimitive(pairStatus)))
        sendTo(ws, mapOf("type" to JsonPrimitive("pair_status_ack"), "pair_status" to JsonPrimitive(pairStatus)))
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
            "bootstrap-request" -> if (consumeScriptedControlError(ws, payload)) return else sendBootstrapResponse(ws, payload.str("requestId"))
            "workspace-list-request" -> if (consumeScriptedControlError(ws, payload)) return else sendWorkspaceListResponse(ws, payload.str("requestId"))
            "workspace-bridge-open" -> if (consumeScriptedControlError(ws, payload)) return else handleBridgeOpen(ws, payload)
            // G67 (vc52 Phase 11): the reconnect request — the fixture flips the remote-down arm off.
            "workspace-reconnect-request" -> handleWorkspaceReconnectRequest(payload)
            // G68 (vc52 Phase 12): the opt-in self-reporting frames — RECORDED (no
            // response exists; the desktop persists + logs them silently).
            "mobile-view-state-update" -> synchronized(script.recordedMobileViewState) { script.recordedMobileViewState.add(payload) }
            "mobile-diagnostic" -> synchronized(script.recordedMobileDiagnostics) { script.recordedMobileDiagnostics.add(payload) }
            else -> Unit
        }
    }

    /**
     * FIELD (2026-10-26, vc46 S918B, relay-4): when [Script.errorFirstControlRequest]
     * names a code (e.g. "DEVICE_OFFLINE"), the FIRST post-auth control request gets
     * exactly that error frame — with the request's requestId echoed, the shape the
     * real relay answers with — and the script clears itself.
     */
    private fun consumeScriptedControlError(ws: okhttp3.WebSocket, payload: JsonObject): Boolean {
        val code = script.errorFirstControlRequest ?: return false
        script.errorFirstControlRequest = null
        val requestId = payload.str("requestId")
        sendTo(
            ws,
            buildMap {
                put("type", JsonPrimitive("error"))
                put("code", JsonPrimitive(code))
                put("message", JsonPrimitive("mock: scripted $code"))
                requestId?.let { put("requestId", JsonPrimitive(it)) }
            },
        )
        return true
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

    private fun sendLogicalFrame(bytes: ByteArray, isData: Boolean = false) {
        val ws = terminalSocket ?: return
        logicalFramesSent++
        if (mode == Mode.DIRECT) {
            // Direct surface (§11.4): raw binary SocketProtocol frames — no relay
            // envelopes, no fragments, no acks (phase10-direct.md §3).
            if (script.perFrameLatencyMs > 0) Thread.sleep(script.perFrameLatencyMs)
            sendBinaryFrame(ws, bytes)
            if (isData) noteDataFrameSent()?.let { reason -> ws.close(1011, reason) }
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
        // Debt #12 (pass 14): the DATA-frame-class arm — close only after N data
        // pushes; poll/RPC frames never count (the unfiltered counter fired mid-auth
        // at =2 and at ~2.5 min at =30).
        if (isData) noteDataFrameSent()?.let { reason -> ws.close(1011, reason) }
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
    /** G64 (vc52 Phase 11): the scoped CUA permission-observation listen (the 4th notify). */
    @Volatile private var cuaPermissionListenerId: Int? = null
    /** G13: the onDynamicSessionsIndexFrame EventListen (per connection). */
    @Volatile private var sessionsIndexListenerId: Int? = null
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
    /**
     * The scripted workspaceHookReview interaction with the live trust states — the
     * ONE build shared by the projection and every state patch (FIX, 2026-11-02 fix
     * round: resolveInteraction's patch used to emit `pendingInteractions: []`,
     * wiping the scripted hook the moment an approval/question was answered — the
     * app's key-level replacement trusts the list as the whole truth, so the hook
     * card vanished on the rig mid-leg).
     */
    private fun scriptedHookInteraction(): JsonObject? {
        // Pass-15 rig find: a RESPONDED review is resolved — hookReviewsResolved was
        // written by respondWorkspaceHookReview and never read, so every later full
        // snapshot/patch re-asserted the trust card as pending (the app's key-level
        // replacement correctly trusted the wire). The review stays resolved; the
        // per-item trust states remain for the revoke/disable planes.
        if (script.hookReviewsResolved) return null
        return script.hookReview?.let { scriptedHookInteractionFor(it) }
    }

    /** Pass-45 (p45 hooks-second): the SECOND task's review — the hooks viewer's
     *  per-task group separation needs two ledger entries (the viewer is keyed
     *  hooks_group_<taskId>; one seeded review could only ever render one group).
     *  Composes with MOCK_HOOKS (MOCK_HOOKS_SECOND alone seeds only the second).
     *  Shares hookReviewsResolved + hookTrustStates with the first review — the
     *  SECOND seed must use distinct reviewItemIds (h4/h5) or trust flips leak
     *  across the two reviews. */
    private fun scriptedHookInteractionSecond(): JsonObject? {
        if (script.hookReviewsResolved) return null
        return script.hookReviewSecond?.let { scriptedHookInteractionFor(it) }
    }

    private fun scriptedHookInteractionFor(hr: JsonObject): JsonObject? {
        val items = (hr["items"] as? JsonArray)?.map { el ->
            val o = el as? JsonObject ?: return@map el
            val id = (o["reviewItemId"] as? JsonPrimitive)?.content
            val ts = script.hookTrustStates[id]
            if (ts != null) JsonObject(o + ("trustState" to JsonPrimitive(ts))) else o
        }
        // RIG-P1 (vc52 Phase 14): the "N pending · M hooks" header follows the LIVE
        // trust states — the summary block is DERIVED from the merged items on every
        // serve (the static armed payload froze it at the arm-time face, so a live
        // trust flip never moved the header). pendingCount = the items still in
        // pending_trust; hookCount = the item count; eventCount = distinct events.
        val merged = items ?: emptyList()
        val derivedSummary = buildJsonObject {
            put("eventCount", JsonPrimitive(merged.mapNotNull { (it as? JsonObject)?.strOrNull("event") }.distinct().size))
            put("hookCount", JsonPrimitive(merged.size))
            put("pendingCount", JsonPrimitive(merged.count { (it as? JsonObject)?.strOrNull("trustState") == "pending_trust" }))
        }
        return buildJsonObject {
            put("interactionId", hr["interactionId"] ?: JsonPrimitive("hook-rig"))
            put("kind", JsonPrimitive("workspaceHookReview"))
            put("anchorRowId", kotlinx.serialization.json.JsonNull)
            // RIG-P1: createdAt is REQUIRED (desktop schema) — serve the ARMED value,
            // never a per-serve re-stamp (the phone's age badge re-stamped on every
            // reconnect when the value moved).
            put("createdAt", hr["createdAt"] ?: JsonPrimitive(System.currentTimeMillis().toDouble()))
            put("payload", buildJsonObject {
                put("kind", JsonPrimitive("workspaceHookReview"))
                (hr as? JsonObject)?.forEach { (k, v) -> if (k != "items" && k != "summary") put(k, v) }
                put("summary", derivedSummary)
                put("items", JsonArray(merged))
            })
        }
    }

    /**
     * RIG-P1 (vc52 Phase 7, G77): the `autoResolution` payload for the armed shape —
     * the byte-verified desktop discriminatedUnion("state") from the shipped asar
     * (b$ schema): `{state: hiddenGrace|visibleCountdown, startedAt, visibleAt,
     * deadlineAt}` | `{state: snoozed, startedAt, snoozedAt}`. Timestamps are
     * epoch-ms numbers. Returns null when the arm is unset (the pre-RIG-P1 shape).
     */
    private fun autoResolutionForEntry(now: Long): JsonObject? {
        val arm = script.autoResolutionArm ?: return null
        val started = script.autoResolutionStartedAtMs
        val fuseEnd = started + script.autoResolutionMs
        return when (arm) {
            "countdown" ->
                if (script.autoResolutionSnoozed) {
                    buildJsonObject {
                        put("state", JsonPrimitive("snoozed"))
                        put("startedAt", JsonPrimitive(started.toDouble()))
                        put("snoozedAt", JsonPrimitive(now.toDouble()))
                    }
                } else {
                    buildJsonObject {
                        put("state", JsonPrimitive("visibleCountdown"))
                        put("startedAt", JsonPrimitive(started.toDouble()))
                        put("visibleAt", JsonPrimitive(started.toDouble()))
                        put("deadlineAt", JsonPrimitive(fuseEnd.toDouble()))
                    }
                }
            "grace" -> buildJsonObject {
                put("state", JsonPrimitive("hiddenGrace"))
                put("startedAt", JsonPrimitive(started.toDouble()))
                put("visibleAt", JsonPrimitive((now + 30_000L).toDouble()))
                put("deadlineAt", JsonPrimitive(fuseEnd.toDouble()))
            }
            "snoozed" -> buildJsonObject {
                put("state", JsonPrimitive("snoozed"))
                put("startedAt", JsonPrimitive(started.toDouble()))
                put("snoozedAt", JsonPrimitive(now.toDouble()))
            }
            "expired" -> buildJsonObject {
                put("state", JsonPrimitive("visibleCountdown"))
                put("startedAt", JsonPrimitive((now - script.autoResolutionMs).toDouble()))
                put("visibleAt", JsonPrimitive((now - script.autoResolutionMs).toDouble()))
                put("deadlineAt", JsonPrimitive((now - 60_000L).toDouble()))
            }
            else -> null
        }
    }

    /**
     * RIG-P1: the desktop's auto-continue — once the countdown fuse has fired the
     * interaction LEAVES the authoritative list (the phone's disappearance rule
     * resolves it by-elsewhere). Only the live "countdown" arm fires; "expired"
     * serves the already-expired face on purpose (the phone must render it
     * non-actionable, not drop it silently).
     */
    private fun autoResolutionFired(now: Long): Boolean =
        script.autoResolutionArm == "countdown" && !script.autoResolutionSnoozed &&
            now >= script.autoResolutionStartedAtMs + script.autoResolutionMs

    /**
     * The authoritative pending list for the CURRENT script slots — one builder for the
     * snapshot (stateBlock) and every state.updated patch. Key-level replacement means
     * a patch must carry every surviving interaction; a partial list resolves the rest
     * (pass-13 c2bcdc1b for the hook, pass-14 for the sibling permission/question).
     */
    /**
     * The authoritative pending list for the CURRENT script slots — one builder for
     * every state patch (key-level replacement means a patch must carry every
     * surviving interaction; a partial list resolves the rest — pass-13 c2bcdc1b for
     * the hook, pass-14 for the sibling permission/question).
     */
    private fun pendingInteractionList(): JsonArray {
        val now = System.currentTimeMillis()
        return JsonArray(listOfNotNull(
            script.pendingPermission?.let { pp ->
                if (autoResolutionFired(now)) {
                    null // G77: the fuse fired — the desktop auto-continued it out of the list
                } else {
                    buildJsonObject {
                        put("interactionId", JsonPrimitive(pp.interactionId))
                        put("kind", JsonPrimitive("permission"))
                        put("anchorRowId", JsonNull)
                        // RIG-P1: createdAt is REQUIRED — serve the slot's CAPTURED
                        // value (stable across reconnect/SIGKILL), never a re-stamp.
                        put("createdAt", JsonPrimitive(pp.createdAtMs.toDouble()))
                        autoResolutionForEntry(now)?.let { put("autoResolution", it) }
                        put("payload", buildJsonObject {
                            put("kind", JsonPrimitive("permission"))
                            put("toolCallId", JsonPrimitive("tool-approval-${pp.interactionId}"))
                            put("toolName", JsonPrimitive(pp.toolName))
                            put("summary", JsonPrimitive(pp.summary))
                            put("detail", buildJsonObject { put("command", JsonPrimitive("echo synthetic")) })
                            put("options", JsonArray(pp.options.map { id ->
                                buildJsonObject {
                                    put("optionId", JsonPrimitive(id))
                                    // Debt #5 (pass 16): the desktop sends `label: c.name`
                                    // (the human label); the mock echoed the raw optionId
                                    // and the phone renders the label — "allowOnce" was a
                                    // mock-fidelity artifact, not app behavior.
                                    put("label", JsonPrimitive(optionLabel(id)))
                                    put("kind", JsonPrimitive(id))
                                }
                            }))
                        })
                    }
                }
            },
            script.pendingUserInput?.let { pu ->
                if (autoResolutionFired(now)) {
                    null // G77: the fuse fired — auto-continued out of the list
                } else {
                    buildJsonObject {
                        put("interactionId", JsonPrimitive(pu.interactionId))
                        put("kind", JsonPrimitive("userInput"))
                        put("anchorRowId", JsonNull)
                        put("createdAt", JsonPrimitive(pu.createdAtMs.toDouble()))
                        autoResolutionForEntry(now)?.let { put("autoResolution", it) }
                        put("payload", buildJsonObject {
                            put("kind", JsonPrimitive("userInput"))
                            put("prompt", JsonPrimitive(pu.prompt))
                            put("freeText", JsonPrimitive(pu.freeText))
                            // A3.14: the AskUserQuestion multi-question plane (MOCK_QUESTION).
                            script.pendingQuestion?.get("questions")?.let { put("questions", it) }
                            put("options", JsonArray(pu.options.map { id ->
                                buildJsonObject { put("optionId", JsonPrimitive(id)); put("label", JsonPrimitive(optionLabel(id))) }
                            }))
                        })
                    }
                }
            },
            scriptedHookInteraction(),
            scriptedHookInteractionSecond(),
        ))
    }

    /**
     * RIG-P1 (N-BE-6): the SNAPSHOT-scoped pending list — the scheduled fresh-push
     * interaction stays OUT until its own patch delivered it (`MOCK_INTERACTION_PUSH_MS=0`
     * used to race the patch ahead of the initial snapshot, so the "fresh push" leg
     * silently degraded to "was in the snapshot"). The pushed PATCH always carries it
     * ([pushPendingPermission] builds the full list); snapshots may serve the id
     * again once [Script.interactionPushDelivered] flipped.
     */
    /** Debt #5 (pass 16): human labels for the kernel option ids (verbatim fallback otherwise). */
    private fun optionLabel(id: String): String = when (id) {
        "allowOnce" -> "Allow once"
        "allowAlways" -> "Allow always"
        "deny" -> "Deny"
        else -> id
    }

    private fun snapshotPendingInteractions(): JsonArray {        val full = pendingInteractionList()
        if (script.interactionPushDelayMs == null || script.interactionPushDelivered) return full
        return JsonArray(full.filter { el ->
            val o = el as? JsonObject ?: return@filter true
            o.strOrNull("interactionId") != script.interactionPushId
        })
    }

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
            put("autoDrain", JsonPrimitive(script.autoDrain))
        }
        val configBlock = script.sessionConfig
        val pending = snapshotPendingInteractions()
        return buildJsonObject {
            put("revision", JsonPrimitive(stateRevision.get()))
            put("control", control)
            put("inputRouting", routing)
            put("queue", queue)
        // ADR-024 (Phase 5D): the hook review with the mutated trust states — MERGED
        // beside the permission/userInput interactions by pendingInteractionList (U4: the
        // key used to be REPLACED, so MOCK_HOOKS silently dropped MOCK_APPROVAL/MOCK_QUESTION).
        put("pendingInteractions", pending)
            // Phase 9 (§10.3): the session `config` echo when scripted (snapshot AND
            // state.updated patches carry it — the switch commands' reconciliation plane).
            script.sessionConfig?.let { put("config", it) }
            // Phase 2 (§11.1): the usage key when scripted (GAP-1/GAP-2).
            script.usageState?.let { put("usage", it) }
            // Phase 4C (D2): the goal (with its verification timeline) + todo keys.
            script.goalState?.let { put("goal", it) }
            script.planState?.let { put("plan", it) }
            // G15 (vc51): backgroundWorks when scripted (the composer indicator's feed).
            script.backgroundWorks?.let { put("backgroundWorks", it) }
            // G9 (vc51): the sharedContextImport handover state when scripted.
            script.sharedContextImport?.let { put("sharedContextImport", it) }
        }
    }

    /** Server-side state.updated driver (races, effects) — §9 key-level replacement. */
    fun pushStatePatch(patch: JsonObject) {
        // RIG-5: the patch rides the CALLING session's stream (activeSessionId), not the
        // hardcoded workspace session — a resync replay from that session must find it.
        val sessionId = routingSessionId()
        val topic = "conversation/$sessionId"
        // G3 rig fidelity: journal the frame BEFORE emitting — a resumable
        // resyncConversationV4 (base epoch + baseSeq) replays the opLog, and an
        // unjournaled effect patch is lost to exactly that replay (the G3 "state patch
        // appears lost after the resync the model switch triggered").
        val ops = JsonArray(listOf(buildJsonObject {
            put("op", JsonPrimitive("state.updated"))
            put("patch", patch)
        }))
        journalAndEmit(sessionId, ops)
    }

    // ------------------------------------------------------------ G48+G51 (vc52 Phase 11): the enforcing delta gate

    /**
     * G48+G51: the gate-enforcing workflowRun delta stream. When the
     * MOCK_ENFORCE_RUN_DELTAS arm is up, the mock streams a scripted
     * `workflowRun.updated` (run patch + actor/node step graph) and later a
     * `workflowRun.removed` — but ONLY to a client that DECLARED
     * `capabilities.workflowRunDeltas === true` in its clientHello (the shipped
     * desktop's `clientSupportsWorkflowRunDeltas` gate, byte-verified asar
     * `12a2fc93…`). An undeclaring client is counted as suppressed — the
     * production-only behavioral delta the old rig could never catch.
     */
    private fun workflowRunDeltasDeclared(): Boolean =
        lastClientHelloCapabilities?.get("workflowRunDeltas")?.let {
            (it as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
        } == true

    private fun pushWorkflowRunDelta(sessionId: String, op: JsonObject) {
        journalAndEmit(sessionId, JsonArray(listOf(op)))
    }

    /** Scripted `workflowRun.updated` for wf-run-0 (patch + actors + nodes). */
    fun pushWorkflowRunUpdatedForTest() {
        if (!script.enforceWorkflowRunDeltas) return
        if (!workflowRunDeltasDeclared()) {
            workflowRunDeltasSuppressed++
            return
        }
        workflowRunDeltasSent++
        // Rig fidelity (the G48 exit-gate leg's live receipt): the poll plane and the
        // delta plane must AGREE — a re-fetch after the delta serves the SAME truth
        // (the static script list used to clobber the merged status on the phone).
        synchronized(script.workflowRuns) {
            val i = script.workflowRuns.indexOfFirst { it.strOrNull("runId") == "wf-run-0" }
            if (i >= 0) script.workflowRuns[i] = JsonObject(script.workflowRuns[i] + mapOf(
                "status" to JsonPrimitive("completed"),
                "updatedAt" to JsonPrimitive(System.currentTimeMillis()),
            ))
        }
        val op = buildJsonObject {
            put("op", JsonPrimitive("workflowRun.updated"))
            put("runId", JsonPrimitive("wf-run-0"))
            put("revision", JsonPrimitive(1))
            put("run", buildJsonObject {
                put("runId", JsonPrimitive("wf-run-0"))
                put("status", JsonPrimitive("completed"))
                put("usage", buildJsonObject {
                    put("spentTokens", JsonPrimitive(123))
                    put("nodesUsed", JsonPrimitive(2))
                })
                put("lastEventSequence", JsonPrimitive(4))
            })
            put("actors", JsonArray(listOf(buildJsonObject {
                put("siteId", JsonPrimitive("actor-a1"))
                put("ordinal", JsonPrimitive(0))
                put("name", JsonPrimitive("lint"))
                put("status", JsonPrimitive("completed"))
            })))
            put("nodes", JsonArray(listOf(buildJsonObject {
                put("siteId", JsonPrimitive("node-n1"))
                put("ordinal", JsonPrimitive(0))
                put("phase", JsonPrimitive("settled"))
                put("outcome", JsonPrimitive("ok"))
                put("toolCalls", JsonPrimitive(3))
            })))
        }
        pushWorkflowRunDelta(routingSessionId(), op)
    }

    /**
     * G64 (vc52 Phase 11): push the CUA permission observation to the scoped listener
     * (the desktop fires `v4/cua/permission-observation` through
     * onDynamicCuaPermissionObservation). No listener attached → counted dropped.
     */
    fun pushCuaPermissionObservation(payload: JsonObject) {
        val listener = cuaPermissionListenerId ?: return
        val bytes = RpcSerialization.encodeAll(
            listOf(
                RpcValue.Arr(listOf(RpcValue.Int(ChannelWire.ResponseType.EventFire.id), RpcValue.Int(listener))),
                RpcValue.Obj(payload),
            ),
        )
        sendLogicalFrame(bytes, isData = true)
    }

    /** The byte-verified blocked observation (Accessibility denied). */
    fun blockedCuaPayload(sessionId: String, accessibility: String = "denied", screenRecording: String = "granted"): JsonObject =
        buildJsonObject {
            put("schemaVersion", JsonPrimitive(1))
            put("eventId", JsonPrimitive("cua-rig-1"))
            put("eventSeq", JsonPrimitive(1))
            put("occurredAt", JsonPrimitive(System.currentTimeMillis().toDouble()))
            put("sessionId", JsonPrimitive(sessionId))
            put("toolCallId", JsonPrimitive("call-rig-1"))
            put("permissionStatus", buildJsonObject {
                put("schemaVersion", JsonPrimitive(1))
                put("platform", JsonPrimitive("darwin"))
                put("grantOwner", JsonPrimitive("com.zcode.cua-helper"))
                put("accessibility", JsonPrimitive(accessibility))
                put("screenRecording", JsonPrimitive(screenRecording))
            })
        }

    /** Scripted `workflowRun.removed` for wf-run-s1. */
    fun pushWorkflowRunRemovedForTest() {
        if (!script.enforceWorkflowRunDeltas) return
        if (!workflowRunDeltasDeclared()) {
            workflowRunDeltasSuppressed++
            return
        }
        workflowRunDeltasSent++
        val op = buildJsonObject {
            put("op", JsonPrimitive("workflowRun.removed"))
            put("runId", JsonPrimitive("wf-run-s1"))
            put("revision", JsonPrimitive(2))
        }
        pushWorkflowRunDelta(routingSessionId(), op)
    }

    /** Replay-journal bound — the app's own subscriber-buffer order (INV-5, holdBufferMaxOps=500). */
    private val opLogMaxFrames = 512

    /**
     * Journal one delta frame (monotonic seq) for resync replay, then emit it live.
     * G3: every command-effect frame (state patches, row deltas) routes through here
     * so effect state survives a resumable resync, not just full snapshots.
     */
    private fun journalAndEmit(sessionId: String, ops: JsonArray) {
        val from = nextSeq()
        val frame = DeltaFrame(from, seqHeadNow, ops)
        val log = opLog.getOrPut(sessionId) { ArrayDeque() }
        log.addLast(frame)
        while (log.size > opLogMaxFrames) log.removeFirst()
        emitDeltasFrame(sessionId, listOf(frame), DeliveryKind.ONLINE)
    }

    /**
     * Pass-16 stall arm: journal one delta frame for resync replay WITHOUT emitting
     * it live — the app sees the turn go quiet while the server backlog grows, so
     * the stream watchdog's resync (resume) replays frames>0. Mirrors
     * [journalAndEmit] minus the emit.
     */
    private fun journalRowSilent(sessionId: String, row: JsonObject) {
        // Debt #17 (pass 17): the silently journalled row is an APPEND — it joins the
        // authoritative appendedRows so a later resync SNAPSHOT carries it too (the
        // desktop's server would include its own pushed rows; the stall rows used to
        // vanish from the timeline after the watchdog's force-resync).
        synchronized(appendedRows) { appendedRows[row["rowId"].toString()] = row }
        val ops = JsonArray(listOf(buildJsonObject {
            put("op", JsonPrimitive("row.appended"))
            put("row", row)
        }))
        val from = nextSeq()
        val frame = DeltaFrame(from, seqHeadNow, ops)
        val log = opLog.getOrPut(sessionId) { ArrayDeque() }
        log.addLast(frame)
        while (log.size > opLogMaxFrames) log.removeFirst()
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
            sendLogicalFrame(bytes, isData = true)
        }
    }

    /**
     * G13: sessions-index frame push (snapshot or one `session.upserted` delta) —
     * rides the onDynamicSessionsIndexFrame listener with the desktop's frame shape.
     */
    private fun pushSessionsIndexFrame(topic: String, subscriptionId: String, snapshot: JsonObject?, delta: JsonObject?) {
        val listener = sessionsIndexListenerId ?: return
        val frame = buildJsonObject {
            put("topic", JsonPrimitive(topic))
            put("subscriptionId", JsonPrimitive(subscriptionId))
            put("fromSeq", JsonPrimitive(0))
            put("toSeq", JsonPrimitive(1))
            put("sentAt", JsonPrimitive(System.currentTimeMillis().toDouble()))
            put("payload", buildJsonObject {
                if (snapshot != null) {
                    put("kind", JsonPrimitive("snapshot"))
                    put("snapshot", snapshot)
                } else if (delta != null) {
                    put("kind", JsonPrimitive("deltas"))
                    put("deltas", JsonArray(listOf(delta)))
                }
            })
        }
        val wireFrames = TopicWireFrameCodec.encode(
            frame = frame, deliveryKind = DeliveryKind.INITIAL, topic = topic,
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
            sendLogicalFrame(bytes, isData = true)
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
        // G3: the same journal+emit path as state patches — authoritative row effects
        // replay after a resumable resync instead of living only in the snapshot tail.
        val ops = JsonArray(listOf(buildJsonObject {
            put("op", JsonPrimitive(op))
            put("row", row)
        }))
        journalAndEmit(sessionId, ops)
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

    /**
     * Pass-37 rig: every row schemaRow ever generated (snapshot windows, range pages,
     * scripted extras), keyed by rowId. The feedback echo's fallback looks rows up here
     * so an upsert for a snapshot/page row preserves the REAL generated shape (text,
     * kind, createdAt) instead of re-synthesizing a bare "synthetic" row — the phone
     * replaces the whole row on row.upserted, so a synthesized base would visibly
     * re-text a snapshot row. putIfAbsent: the FIRST generation is the one the phone
     * received; later regenerations must not shift the recorded createdAt.
     */
    private val generatedRows = HashMap<String, JsonObject>()

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
            RowKind.TIMELINE_MARKER -> buildJsonObject {
                // I2.4 (Phase 6A): `text` carries "status:origin" (the schemaRow
                // status-carrying convention); the marker payload mirrors the host's
                // projectMarker compact arm {type, origin, status}.
                put("lane", JsonPrimitive("turnTailBoundary"))
                put("marker", buildJsonObject {
                    put("type", JsonPrimitive("compact"))
                    put("origin", JsonPrimitive(text.substringAfter(":", "manual")))
                    put("status", JsonPrimitive(text.substringBefore(":")))
                })
            }
            RowKind.USER_INPUT -> buildJsonObject {
                put("text", JsonPrimitive(text))
                put("origin", JsonPrimitive("typed"))
                put("guided", JsonPrimitive(false))
                put("attachments", JsonArray(emptyList()))
            }
            // G71 (vc52 Phase 12): the per-turn hooks monitor + the subagent row
            // (chunk-RB4EITEQ jk/Dk + U$ schemas; `text` carries "event:displayName"
            // for hook rows and the subagentType for subagent rows — the rich fields
            // ride `extra` from the seeding arm).
            RowKind.HOOK_INVOCATION -> buildJsonObject {
                put("hookInvocationId", JsonPrimitive("hi-$rowId"))
                put("hookEventName", JsonPrimitive(text.substringBefore(":")))
                put("hookCount", JsonPrimitive(1))
                put("state", JsonPrimitive("completed"))
                put("lane", JsonPrimitive("toolAfter"))
                put("executions", JsonArray(emptyList()))
            }
            RowKind.SUBAGENT -> buildJsonObject {
                put("subagentType", JsonPrimitive(text))
                put("status", JsonPrimitive("success"))
                put("summaryText", JsonPrimitive("synthetic subagent"))
            }
            // Pass-33 rig: the artifact row's required decoder fields (all
            // fail-closed `obj.string(...)` in the domain decoder). `text` carries
            // the display name; the open affordance downloads by REF, so the
            // seeding arm puts real bytes under script.fileBytes[ref] (see
            // snapshotRows' rowKindsDemo block) — readFileRange has no default
            // content fallback (unlike readTextFile's seeded-every-path shape).
            RowKind.ARTIFACT -> buildJsonObject {
                put("artifactVersionId", JsonPrimitive("av-$rowId"))
                put("logicalArtifactKey", JsonPrimitive(text))
                put("displayName", JsonPrimitive(text))
                put("artifactType", JsonPrimitive("document"))
                put("mimeType", JsonPrimitive("text/html"))
                put("sizeBytes", JsonPrimitive(script.fileBytes[text]?.size?.toLong() ?: 2048L))
                put("sha256", JsonPrimitive("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"))
                put("ref", JsonPrimitive(text))
            }
        }
        val row = JsonObject(base + kindFields + (extra ?: JsonObject(emptyMap())).toMap())
        generatedRows.putIfAbsent(rowId.toString(), row)
        return row
    }

    /** Pass-37 seam: the recorded generated shape for a rowId (null = never generated). */
    internal fun generatedRowForTest(rowId: Long): JsonObject? = generatedRows[rowId.toString()]

    /** Kinds the mock emits (subset of the verified rows.ts union, all schema-faithful). */
    enum class RowKind(val wire: String) {
        TURN_HEADER("turnHeader"), USER_INPUT("userInput"), ASSISTANT_TEXT("assistantText"),
        REASONING("reasoning"), TOOL_CALL("toolCall"), TIMELINE_MARKER("timelineMarker"),
        HOOK_INVOCATION("hookInvocation"), SUBAGENT("subagent"), ARTIFACT("artifact"),
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
        // pass-11 (F-P11-A's mock half): the app loads the attachment from its CACHED
        // rows BEFORE the conversation subscribe's snapshot (the cold-start read raced
        // snapshotRows' registration — one-shot fault.attachment.unknownRef, and the
        // chip rendered "Loading…" until a recomposition retried). Register at arm
        // time; snapshotRows' put is idempotent.
        val att = MockAttachment(sessionId = "scripted-attachment", fileName = "mock.png", mime = mime, totalBytes = bytes.size.toLong())
        att.data.write(bytes)
        synchronized(committedAttachments) { committedAttachments[ref] = att }
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
            // pass-25 rig arming: stamp canEdit on the LATEST user row so the
            // message-edit sheet (gated on actions.canEdit — ConversationScreen.kt)
            // becomes live-reachable; desktop rows.ts stamps the same flag on
            // editable user messages.
            val lastUser = rows.indexOfLast { it["kind"]?.let { k -> (k as JsonPrimitive).content } == RowKind.USER_INPUT.wire }
            if (lastUser >= 0) rows[lastUser] = JsonObject(rows[lastUser] + ("actions" to ROW_ACTIONS_USER_EDIT))
        }
        if (script.compactionDemo) {
            // I2.4: the lifecycle the desktop sends around an auto compaction —
            // started, then the terminal auto success (with token counts), then a
            // failed manual compaction for the Retry affordance.
            rows.add(schemaRow(sessionId, nextId++, RowKind.TIMELINE_MARKER, "running:auto"))
            rows.add(schemaRow(sessionId, nextId++, RowKind.TIMELINE_MARKER, "completed:auto", extra = buildJsonObject {
                put("marker", buildJsonObject {
                    put("type", JsonPrimitive("compact"))
                    put("origin", JsonPrimitive("auto"))
                    put("status", JsonPrimitive("completed"))
                    put("tokensBefore", JsonPrimitive(180_000))
                    put("tokensAfter", JsonPrimitive(42_000))
                })
            }))
            rows.add(schemaRow(sessionId, nextId++, RowKind.TIMELINE_MARKER, "failed:manual"))
        }
        if (script.hooksRowsDemo) {
            // G71 (vc52 Phase 12): one completed PreToolUse group (completed + blocked +
            // failed executions, blockReason carried) and one running SessionStart group —
            // the phone's monitor renders grouped per-execution rows with states.
            rows.add(schemaRow(sessionId, nextId++, RowKind.HOOK_INVOCATION, "PreToolUse", extra = buildJsonObject {
                put("state", JsonPrimitive("completed"))
                put("durationMs", JsonPrimitive(420))
                put("executions", JsonArray(listOf(
                    hookExecutionRow("hr-1", 0, didExecute = true, state = "completed", outcome = "success", displayName = "Lint gate", sourceKind = "project", durationMs = 210),
                    hookExecutionRow("hr-2", 1, didExecute = true, state = "completed", outcome = "blocked", displayName = "Secrets guard", sourceKind = "user", durationMs = 95, blockReason = "matched deny rule: secrets.*"),
                    hookExecutionRow("hr-3", 2, didExecute = true, state = "failed", outcome = "failed", displayName = "Telemetry", sourceKind = "plugin", pluginName = "acme-tools", durationMs = 115),
                    hookExecutionRow("hr-4", 3, didExecute = false, state = "completed", outcome = "success", displayName = "Skipped watcher", sourceKind = "project", durationMs = null),
                )))
            }))
            rows.add(schemaRow(sessionId, nextId++, RowKind.HOOK_INVOCATION, "SessionStart", extra = buildJsonObject {
                put("state", JsonPrimitive("running"))
                put("executions", JsonArray(listOf(
                    hookExecutionRow("hr-5", 0, didExecute = true, state = "running", outcome = null, displayName = "Warm build cache", sourceKind = "project", durationMs = null),
                )))
            }))
            // N-BK-1(a) feeds from the same rig: a subagent row carrying childSessionId.
            rows.add(schemaRow(sessionId, nextId++, RowKind.SUBAGENT, "explore", extra = buildJsonObject {
                put("parentToolCallId", JsonPrimitive("tool-parent-1"))
                put("status", JsonPrimitive("success"))
                put("summaryText", JsonPrimitive("Explored the auth module"))
                put("childSessionId", JsonPrimitive("sess-child-9"))
                put("backgrounded", JsonPrimitive(true))
                put("workId", JsonPrimitive("work-sub-1"))
            }))
        }
        if (script.rowKindsDemo) {
            // Pass-33 (Part AJ): the never-driven row-kind faces.
            // 1. The OPENABLE artifact row — real bytes under the ref so the preview
            //    card's Open affordance downloads and hands off to the system viewer.
            val html = "<html><body><h1>rig report</h1><p>pass-33 artifact row</p></body></html>".toByteArray(Charsets.UTF_8)
            script.fileBytes["rig-p33-report.html"] = html
            rows.add(schemaRow(sessionId, nextId++, RowKind.ARTIFACT, "rig-p33-report.html"))
            // 2. The QUIET-fallback artifact row — unknown extension; its ref is
            //    deliberately UNSEEDED (download fails → the app's log-only face).
            rows.add(schemaRow(sessionId, nextId++, RowKind.ARTIFACT, "rig-p33-blob.xyz", extra = buildJsonObject {
                put("mimeType", JsonPrimitive("application/octet-stream"))
                put("sizeBytes", JsonPrimitive(2048L))
            }))
            // 2b. The OPENABLE-but-unserved artifact (pass-33 gen2): a CARD renders
            //    (known extension) but readFileRange answers fault.file.notFound —
            //    stage-1 of the open affordance's silent failure (stage-2 = the
            //    downloaded file hits the unconfigured FileProvider root).
            rows.add(schemaRow(sessionId, nextId++, RowKind.ARTIFACT, "rig-p33-missing.html", extra = buildJsonObject {
                put("mimeType", JsonPrimitive("text/html"))
                put("sizeBytes", JsonPrimitive(4096L))
            }))
            // 3. The unknown-kind tolerance row (INV-8 §19.5): NOT in KNOWN_KINDS —
            //    the domain decoder routes it to Unknown and the renderer renders
            //    the "Unsupported entry · <kind>" quiet face. Never a crash.
            rows.add(JsonObject(buildJsonObject {
                put("rowId", JsonPrimitive(nextId++))
                put("turnId", JsonPrimitive("turn-$sessionId-${nextId / 4 + 1}"))
                put("kind", JsonPrimitive("futureWidget"))
                put("entityId", JsonPrimitive("ent-future"))
                put("createdAt", JsonPrimitive(System.currentTimeMillis().toDouble()))
                put("createdAtSeq", JsonPrimitive(nextId))
                put("widgetPayload", buildJsonObject { put("shape", JsonPrimitive("unspecified")) })
            }))
            // 4. A NON-compact timelineMarker — the generic QuietMetaRow(label=type)
            //    fallback (compact is the only marker type with a dedicated face).
            rows.add(schemaRow(sessionId, nextId++, RowKind.TIMELINE_MARKER, "completed:manual", extra = buildJsonObject {
                put("marker", buildJsonObject {
                    put("type", JsonPrimitive("checkpoint"))
                    put("origin", JsonPrimitive("manual"))
                    put("status", JsonPrimitive("completed"))
                })
            }))
            // 5. The STREAMING reasoning face — auto-expanded while the agent thinks
            //    (ReasoningRow's LaunchedEffect(state) branch).
            rows.add(schemaRow(sessionId, nextId++, RowKind.REASONING, "weighing the fixture shapes against the wire contract before wiring the seam", extra = buildJsonObject {
                put("state", JsonPrimitive("streaming"))
            }))
        }
        if (script.rowToolsDemo) {
            // Pass-34 (Part AK): the toolCall row's PER-TOOL-KIND render matrix.
            // Grouping needs ≥2 CONSECUTIVE SETTLED rows of one category; the single
            // faces are interleaved so no accidental group forms. Durations ride
            // startedAt/endedAt (epoch-ms doubles — the decoder's shape).
            val now = System.currentTimeMillis()
            fun rigTool(
                name: String,
                status: String,
                inputText: String,
                inputPath: String? = null,
                durationMs: Long? = null,
                callId: String? = null,
                previewObj: JsonObject? = null,
                errorObj: JsonObject? = null,
                canRetry: Boolean = false,
                approvalId: String? = null,
            ) {
                val extra = buildJsonObject {
                    put("toolName", JsonPrimitive(name))
                    put("inputText", JsonPrimitive(inputText))
                    callId?.let { put("toolCallId", JsonPrimitive(it)) }
                    inputPath?.let { p ->
                        put("input", buildJsonObject {
                            put("path", JsonPrimitive(p))
                            put("file_path", JsonPrimitive(p))
                        })
                    }
                    previewObj?.let { put("outputPreview", it) }
                    errorObj?.let { put("error", it) }
                    if (canRetry) put("actions", buildJsonObject { put("canRetry", JsonPrimitive(true)) })
                    approvalId?.let { put("approvalInteractionId", JsonPrimitive(it)) }
                    durationMs?.let { d ->
                        put("startedAt", JsonPrimitive((now - d).toDouble()))
                        put("endedAt", JsonPrimitive(now.toDouble()))
                    }
                }
                // schemaRow's `text` carries "status[:interactionId]" for tool rows.
                val text = if (approvalId != null) "pendingApproval:$approvalId" else status
                rows.add(schemaRow(sessionId, nextId++, RowKind.TOOL_CALL, text, extra = extra))
            }
            fun bigPreview(seed: String): JsonObject = buildJsonObject {
                put("text", JsonPrimitive(buildString {
                    append("$seed — the retained preview body (the client caps it at 2000 chars and lazy-fetches the slice).\n")
                    repeat(60) { i -> append("line ${i + 1}: the served full-output body must be LONGER than the cap so the button renders.\n") }
                }))
            }
            // CHANGES group (2 settled rows, path inputs, durations → "Changes · 2 files · 4.3s").
            rigTool("Edit", "success", "src/version.ts", inputPath = "src/version.ts", durationMs = 1_900)
            rigTool("Write", "success", "README.md", inputPath = "README.md", durationMs = 2_400)
            // EXECUTE group — BashOutput is the taxonomy hole (unknown name → NO kind
            // subtitle; the substring fallback buckets it EXECUTE so it GROUPS).
            rigTool("Bash", "success", "git status --short", durationMs = 3_100)
            rigTool("BashOutput", "success", "bash_id: shell-7", durationMs = 2_200)
            // The SERVED >2k preview row (the lazy fetch answers a real slice body).
            rigTool("Grep", "success", "TODO", callId = "tool-rig-grepbig", previewObj = bigPreview("grepbig"))
            script.toolCallSlices["tool-rig-grepbig"] = buildString {
                append("SERVED SLICE for tool-rig-grepbig (getTaskSnapshotToolCallsSlice output field — the desktop's C7 shape).\n")
                repeat(120) { i -> append("slice line ${i + 1}: this body is served by the relay and must replace the capped preview in the expanded card.\n") }
            }
            // The ERROR card: typed error + canRetry → the G66 recovery card (Retry
            // rendered; Switch model + Report issue wired app-side).
            rigTool("TaskUpdate", "error", "{\"taskId\":\"synth-task-1\",\"status\":\"in_progress\"}", durationMs = 1_200, errorObj = buildJsonObject {
                put("code", JsonPrimitive("task_write_denied"))
                put("message", JsonPrimitive("the desktop's task store refused the update for the rig probe"))
            }, canRetry = true)
            // The UNSCRIPTED >2k preview row (its slice id is absent → fault → the
            // fallback face renders the retained preview copy as "full output").
            rigTool("Grep", "success", "FIXME", callId = "tool-rig-grepfallback", previewObj = bigPreview("grepfallback"))
            // Single-card status faces (each breaks any accidental group above/below).
            rigTool("Bash", "pendingApproval", "rm -rf build/", approvalId = "inter-rig-tools-1")
            rigTool("WebSearch", "cancelled", "kotlin compose desktop")
            rigTool("Glob", "running", "src/**/*.kt")
            // The UNKNOWN-status face: an empty status string decodes to UNKNOWN
            // ("Unknown" label, the done-rail color).
            rigTool("CronList", "", "list cron")
        }
        return JsonArray(rows)
    }

    /** G71: one `executions[]` entry (chunk-RB4EITEQ `Dk`, .strict()). */
    private fun hookExecutionRow(
        runId: String,
        index: Int,
        didExecute: Boolean,
        state: String,
        outcome: String?,
        displayName: String,
        sourceKind: String,
        durationMs: Long?,
        blockReason: String? = null,
        pluginName: String? = null,
    ): JsonObject = buildJsonObject {
        put("hookRunId", JsonPrimitive(runId))
        put("hookIndex", JsonPrimitive(index))
        put("didExecute", JsonPrimitive(didExecute))
        put("state", JsonPrimitive(state))
        outcome?.let { put("outcome", JsonPrimitive(it)) }
        blockReason?.let { put("blockReason", JsonPrimitive(it)) }
        durationMs?.let { put("durationMs", JsonPrimitive(durationMs)) }
        put("displayName", JsonPrimitive(displayName))
        put("sourceKind", JsonPrimitive(sourceKind))
        pluginName?.let { put("pluginName", JsonPrimitive(it)) }
    }

    /** Phase 4A: `actions:{canFork:true,canRetry:true}` — rows.ts only ever sends TRUE keys. */
    private val ROW_ACTIONS_ASSISTANT: JsonObject = buildJsonObject {
        put("canFork", JsonPrimitive(true))
        put("canRetry", JsonPrimitive(true))
    }

    /** pass-25: `actions:{canEdit:true}` on editable user messages. */
    private val ROW_ACTIONS_USER_EDIT: JsonObject = buildJsonObject {
        put("canEdit", JsonPrimitive(true))
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
                sendLogicalFrame(bytes, isData = true)
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

    /** Pass-33 rig: the snapshot rows a session currently serves (the kind-matrix faces). */
    internal fun snapshotRowsForTest(sessionId: String, count: Int): JsonArray = snapshotRows(sessionId, count)
    internal fun revisionForTest(): Long = stateRevision.get()
    internal fun pendingPermissionForTest(): PendingPermission? = script.pendingPermission
    internal fun pendingUserInputForTest(): PendingUserInput? = script.pendingUserInput
    internal fun pendingListForTest(): JsonArray = pendingInteractionList()
    internal fun armAutoResolutionForTest(arm: String, fuseMs: Long, startedAtMs: Long) {
        script.autoResolutionArm = arm
        script.autoResolutionMs = fuseMs
        script.autoResolutionStartedAtMs = startedAtMs
        script.autoResolutionSnoozed = false
    }
    internal fun autoResolutionSnoozedForTest(): Boolean = script.autoResolutionSnoozed
    // Phase 4A self-test seams: the snapshot row builder + the appended/upserted row ledger.
    /** G68: the recorded opt-in self-report frame counts (test seam; public — cross-module). */
    fun selfReportViewStateCount(): Int = synchronized(script.recordedMobileViewState) { script.recordedMobileViewState.size }
    fun selfReportDiagnosticsCount(): Int = synchronized(script.recordedMobileDiagnostics) { script.recordedMobileDiagnostics.size }

    internal fun snapshotRowsForTestSeam(sessionId: String, count: Int): JsonArray = snapshotRows(sessionId, count)
    internal fun appendedRowForTest(rowId: Long): JsonObject? = synchronized(appendedRows) { appendedRows[rowId.toString()] }
    internal fun appendedRowsForTest(): Map<String, JsonObject> = synchronized(appendedRows) { LinkedHashMap(appendedRows) }
    /** Pass-30 seam: attachment-store size for the fail-arm is-a-shape-not-a-mutation pin. */
    internal fun attachmentStoreForTest(): Map<String, MockAttachment> = synchronized(attachmentStore) { LinkedHashMap(attachmentStore) }

    /**
     * Phase 7 test driver: inject a pending permission interaction (E2E approval
     * flows) — arms the script knob AND pushes the verified state.updated patch so
     * connected clients see it without resubscribing.
     */
    fun pushPendingPermission(interactionId: String, toolName: String) {
        script.pendingPermission = PendingPermission(interactionId, toolName)
        stateRevision.incrementAndGet()
        // F-P4-A (pass-4 rig receipt): the patch must carry the FULL pending list.
        // stateBlock() routes through snapshotPendingInteractions(), which EXCLUDES the
        // push id while interactionPushDelivered is still false (it flips only AFTER
        // this call returns) — so the pushed patch structurally could not carry the
        // interaction it exists to deliver, and delivery degraded to the next snapshot
        // (which landed STALE in the app). Build from the unfiltered list instead.
        val pending = pendingInteractionList()
        lastPushedPendingInteractionsForTest = pending
        val block = stateBlock() ?: return
        pushStatePatch(buildJsonObject {
            put("revision", JsonPrimitive(stateRevision.get()))
            put("pendingInteractions", pending)
        })
    }

    /**
     * RIG-3 (vc51 Phase 5): the once-per-connection guard for the fresh-interaction
     * push — the FIRST conversation subscribe schedules [pushPendingPermission] after
     * [MockZCode.Script.interactionPushDelayMs]; a resubscribe must not re-arm.
     */
    private val interactionPushArmed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val pendingPushScheduled = java.util.concurrent.atomic.AtomicBoolean(false)

    /** The subscribe handler's arming hook (guarded; a no-op when the arm is unset). */
    private fun armInteractionPush() {
        if (script.interactionPushDelayMs == null) return
        if (!interactionPushArmed.compareAndSet(false, true)) return
        val ms = script.interactionPushDelayMs ?: return
        val id = script.interactionPushId
        val tool = script.interactionPushTool
        scope.launch {
            pendingPushScheduled.set(true)
            kotlinx.coroutines.delay(ms)
            pendingPushScheduled.set(false)
            pushPendingPermission(id, tool)
            // RIG-P1 (N-BE-6): delivery done — snapshots may serve the push id again
            // (a reconnecting client must still see the pending interaction).
            script.interactionPushDelivered = true
        }
    }

    internal fun armInteractionPushForTest(delayMs: Long) {
        script.interactionPushDelayMs = delayMs
        script.interactionPushDelivered = false
        interactionPushArmed.set(false)
        armInteractionPush()
    }

    /** The production subscribe-side arming (guard honored; used by the once-per-connection pin). */
    internal fun subscribeArmsInteractionPushForTest() = armInteractionPush()

    internal fun interactionPushArmedForTest(): Boolean = interactionPushArmed.get()

    // ---------------- Mock-fidelity debts #11/#13 (pass 14): the anchored schedules ----------------

    /** Debt #13: once-per-process guard for the subscribe-anchored delta schedule. */
    private val runDeltasArmed = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Debt #13 (pass 14): the subscribe-anchored variant of [armWorkflowRunDeltasForTest]'s
     * schedule — armed from the FIRST conversation subscribe (the gate's
     * workflowRunDeltasDeclared already reads the clientHello, which precedes every
     * subscribe) instead of relay start. A no-op unless the anchor is on.
     */
    internal fun armWorkflowRunDeltasOnSubscribe() {
        if (!script.runDeltasAnchorSubscribe || !script.enforceWorkflowRunDeltas) return
        if (!runDeltasArmed.compareAndSet(false, true)) return
        val updateMs = script.runDeltaUpdateMs
        val removeMs = script.runDeltaRemoveMs
        scope.launch {
            kotlinx.coroutines.delay(updateMs)
            pushWorkflowRunUpdatedForTest()
            kotlinx.coroutines.delay((removeMs - updateMs).coerceAtLeast(500L))
            pushWorkflowRunRemovedForTest()
        }
    }

    /** Debt #13 seam: armed once (the schedule lives in the mock's scope). */
    internal fun runDeltasArmedForTest(): Boolean = runDeltasArmed.get()

    /** Pass-16 stall arm: once-per-process guard for the subscribe-anchored stall schedule. */
    private val turnStallArmed = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Pass-16 stall arm (MOCK_TURN_STALL): armed from the FIRST conversation
     * subscribe (same anchor class as [armWorkflowRunDeltasOnSubscribe]). Emits
     * [Script.turnStallLiveFrames] live row frames, then journals rows silently —
     * the frames>0 backlog a resumable resync replays after the watchdog re-kick.
     */
    internal fun armTurnStallOnSubscribe() {
        if (!script.turnStall) return
        if (!turnStallArmed.compareAndSet(false, true)) return
        val everyMs = script.turnStallEveryMs
        scope.launch {
            repeat(script.turnStallLiveFrames) { i ->
                val liveRow = schemaRow(routingSessionId(), nextRowId(), RowKind.ASSISTANT_TEXT,
                    "stall-leg live frame ${i + 1} — the stream was flowing")
                // Debt #17 (pass 17): the live frames are authoritative rows too — they
                // join appendedRows so a resync snapshot keeps them (same shape as the
                // sendText/interaction handlers' bookkeeping).
                synchronized(appendedRows) { appendedRows[liveRow["rowId"].toString()] = liveRow }
                pushRowDelta(routingSessionId(), "row.appended", liveRow)
                kotlinx.coroutines.delay(everyMs)
            }
            var n = 0
            while (true) {
                n += 1
                journalRowSilent(routingSessionId(),
                    schemaRow(routingSessionId(), nextRowId(), RowKind.ASSISTANT_TEXT,
                        "stall-leg backlog frame $n — journaled, never emitted"))
                kotlinx.coroutines.delay(everyMs)
            }
        }
    }

    /** Pass-16 stall seam: armed once (the silent journal lives in the mock's scope). */
    internal fun turnStallArmedForTest(): Boolean = turnStallArmed.get()

    /** Pass-16 stall seam: journal ONE silent backlog row now (deterministic self-test drive). */
    internal fun journalStallBacklogRowForTest(sessionId: String, text: String): Long {
        val row = schemaRow(sessionId, nextRowId(), RowKind.ASSISTANT_TEXT, text)
        journalRowSilent(sessionId, row)
        return (row["rowId"] as? JsonPrimitive)?.content?.toLongOrNull() ?: -1L
    }

    /** Debt #11: once-per-process guard for the first-list off-peak flip. */
    private val offPeakCompleteScheduled = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Debt #11 (pass 14): arm [Script.offPeakCompleteOnFirstListMs] from the FIRST
     * `off-peak-task.list` — the G63 notifier's baseline seed IS that call, so the
     * flip can never outrun the baseline inside one process life. A no-op unless
     * the anchor is set.
     */
    private fun scheduleOffPeakCompleteOnFirstList() {
        val ms = script.offPeakCompleteOnFirstListMs ?: return
        if (!offPeakCompleteScheduled.compareAndSet(false, true)) return
        scope.launch {
            kotlinx.coroutines.delay(ms)
            completeOffPeakRow(this@MockZCode, "offpeak-rig-1")
        }
    }

    /** Debt #11 seam: armed once (the flip lives in the mock's scope). */
    internal fun offPeakCompleteScheduledForTest(): Boolean = offPeakCompleteScheduled.get()

    /** Off-peak list seam: drive the channel handler directly (the anchored-flip leg). */
    internal fun offPeakTaskPromiseForTest(id: Int, name: String?, arg: RpcValue?) =
        handleOffPeakTaskPromise(id, name, arg)

    // ---------------- Mock-fidelity debt #12 (pass 14): the data-frame-class counter ----------------

    /** DATA frames (topic/delta pushes) sent this connection — the class the #12 arm counts. */
    private val outDataFrames = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * Debt #12 (pass 14): count one sent DATA frame; consume-and-fire the
     * [Script.disconnectAfterDataFrames] arm. Returns the close reason when the
     * limit is reached (null = keep the socket).
     */
    internal fun noteDataFrameSent(): String? {
        val sent = outDataFrames.addAndGet(1)
        val limit = script.disconnectAfterDataFrames
        if (limit != null && sent >= limit) {
            script.disconnectAfterDataFrames = null
            return "mock disconnect storm (data)"
        }
        return null
    }

    /** Debt #12 seam: the running data-frame count. */
    internal fun dataFramesSentForTest(): Long = outDataFrames.get()

    /** RIG-P2 (N-BD-2b) seams — the handler and the self-test share ONE computation. */
    internal fun listTasksServedForTest(): List<JsonObject> =
        script.directTasks.filter { taskIdOf(it) !in script.archivedTaskIds }

    internal fun listArchivedServedForTest(): List<JsonObject> =
        script.directTasks.filter { taskIdOf(it) in script.archivedTaskIds }

    /** RIG-P1 seam: the N-BE-6 delivery flag (snapshots exclude the push id while false). */
    internal fun interactionPushDeliveredForTest(): Boolean = script.interactionPushDelivered

    /** True while a scheduled push is still pending (test seam for the guard's negative arm). */
    internal fun pushRescheduledForTest(): Boolean = pendingPushScheduled.get()

    /** F-P4-A seam: the pendingInteractions array the LAST push patch actually carried. */
    @Volatile internal var lastPushedPendingInteractionsForTest: JsonArray? = null

    /** RIG-5 self-test seam: the zcode-task channel words (archive/resume mutations are state-side). */
    internal fun taskPromiseForTest(id: Int, name: String?, arg: JsonObject?) = handleTaskPromise(id, name, arg)

    /** G42-P23: the management-verb seam — returns the wire error string on refusal. */
    internal fun workflowManagementPromiseForTest(name: String, params: JsonObject?): String? =
        when (name) {
            "deleteSavedWorkflow" -> computeWorkflowDelete(params).second
            "updateSavedWorkflowMeta" -> computeWorkflowUpdateMeta(params).second
            "moveSavedWorkflow" -> computeWorkflowMove(params).second
            else -> "fault.mock.unknownMethod"
        }

    /** RIG-5 self-test seam: the git-channel words (branch acks mutate the served state). */
    internal fun gitPromiseForTest(id: Int, name: String?, arg: JsonObject?) =
        handleGitPromise(id, name, RpcValue.Obj(arg ?: JsonObject(emptyMap())))

    /** p46 self-test seam: the git-checkpoint channel words (the conflict arm's payload). */
    internal fun checkpointPromiseForTest(id: Int, name: String?, arg: JsonObject?) =
        handleGitCheckpointPromise(id, name, arg)

    /** p46: the non-force restore answer under the conflict arm (script.checkpointConflictFiles). */
    private fun checkpointConflictPayload(): JsonObject = buildJsonObject {
        put("success", JsonPrimitive(false))
        put("conflicts", JsonArray(script.checkpointConflictFiles.map { JsonPrimitive(it) }))
    }

    /** p46 self-test seam: the conflict payload the handler serves. */
    internal fun checkpointConflictPayloadForTest(): JsonObject = checkpointConflictPayload()

    /**
     * RIG-5 self-test seam: the summary `getRepositorySummary` serves (scripted summary
     * MERGED with the tracked branch — the same computation the handler performs).
     */
    internal fun repositorySummaryForTest(): JsonObject? = mergedGitSummary()

    /** RIG-5 self-test seam: the head getLocalBranches serves (tracked branch or main). */
    internal fun localBranchesHeadForTest(): String = script.currentGitBranch ?: "main"

    /** p40 self-test seam: the derived changes view getChanges serves (write-tier mutation applied). */
    internal fun servedGitChangesForTest(): JsonArray = servedGitChanges()

    /**
     * p40 (pass-40): the DERIVED changes view — the scripted fixture with the write
     * tier's mutations applied. Until this, stage/unstage/discard acked and mutated
     * script.stagedPaths/discardedPaths while getChanges kept serving the static
     * fixture, so the ack had NO visible face (the recorded ack-without-mutate class,
     * vc51-era). Rules: a discarded/committed path's row drops; a staged path's row
     * serves section "staged"; everything else keeps its fixture section. The phone
     * sends workspaceRelativePath keys ("src/version.ts"), the fixture rows key `path`
     * absolutely ("/synthetic/repo/src/version.ts") — match all three spellings.
     */
    /**
     * p41: resolve a git path spelling (the phone's workspaceRelativePath or the
     * fixture's absolute one) to the fixture row's absolute path — the write tier's
     * staged set may hold either spelling, and the exact-string removes must span both.
     */
    private fun canonicalGitPath(p: String): String {
        val base = script.gitChanges ?: return p
        return base.mapNotNull { it as? JsonObject }
            .firstOrNull { row ->
                val abs = (row["path"] as? JsonPrimitive)?.content
                val rel = (row["workspaceRelativePath"] as? JsonPrimitive)?.content
                abs == p || rel == p || (abs?.endsWith("/$p") == true)
            }?.let { (it["path"] as? JsonPrimitive)?.content } ?: p
    }

    private fun servedGitChanges(): JsonArray {
        val base = script.gitChanges ?: return JsonArray(emptyList())
        val staged = script.stagedPaths.toSet()
        val discarded = script.discardedPaths.toSet()
        val committed = script.committedPaths.toSet()
        fun JsonObject.matches(p: String): Boolean {
            val rel = (this["workspaceRelativePath"] as? JsonPrimitive)?.content
            val abs = (this["path"] as? JsonPrimitive)?.content
            return abs == p || rel == p || (abs?.endsWith("/$p") == true)
        }
        return JsonArray(base.mapNotNull { row ->
            val obj = row as? JsonObject ?: return@mapNotNull row
            val rel = (obj["workspaceRelativePath"] as? JsonPrimitive)?.content
            val abs = (obj["path"] as? JsonPrimitive)?.content ?: return@mapNotNull obj
            if (abs in committed || abs in discarded) return@mapNotNull null
            if (rel != null && (rel in discarded || rel in committed)) return@mapNotNull null
            val stagedHit = staged.any { obj.matches(it) }
            // p41: the section now derives from the WRITE TIER's live set — a row that
            // left stagedPaths serves as its working-tree section (kind added →
            // untracked, else unstaged) instead of its STATIC fixture section, so an
            // unstaged fixture-staged row actually leaves the Staged tab.
            val section = when {
                stagedHit -> "staged"
                (obj["kind"] as? JsonPrimitive)?.content == "added" -> "untracked"
                else -> "unstaged"
            }
            JsonObject(obj + mapOf("section" to JsonPrimitive(section)))
        })
    }

    /** The served summary: the scripted body with the tracked branch merged in (null = unscripted). */
    private fun mergedGitSummary(): JsonObject? {
        val summary = script.gitSummary ?: return null
        val branch = script.currentGitBranch
        if (branch == null || (summary["branchName"] as? JsonPrimitive)?.content == branch) return summary
        return JsonObject(summary + mapOf(
            "branchName" to JsonPrimitive(branch),
            "trackingBranchName" to kotlinx.serialization.json.JsonNull,
            "ahead" to JsonPrimitive(0),
            "behind" to JsonPrimitive(0),
        ))
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
                if (name == "onDynamicSessionsIndexFrame") {
                    sessionsIndexListenerId = id
                }
                if (name == "onDynamicCuaPermissionObservation") {
                    cuaPermissionListenerId = id
                }
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
                            // p47: per-attach seed — MOCK_TERMINAL_OUT enqueues HERE (per
                            // listen, not boot-time) so every attach serves its own lines;
                            // the drain below pushes them to the just-registered listener.
                            System.getenv("MOCK_TERMINAL_OUT")?.let { script.terminalSeedOutput(it) }
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

    /**
     * Pass-21: workspace-relative view of a requested file path — the phone requests
     * ABSOLUTE workspace paths while the seeded trees are workspace-relative. The
     * workspace root itself maps to ""; anything else strips the workspace prefix.
     * Null = the path was absolute but outside this workspace (no relative form).
     */
    private fun fileRelPath(path: String): String? =
        when {
            path == workspace.workspacePath -> ""
            path.startsWith(workspace.workspacePath + "/") -> path.removePrefix(workspace.workspacePath + "/")
            else -> path
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
                // (write/resize ride handleTerminalPromise too — the armed input tier)
                "git" -> handleGitPromise(id, name, arg)
                "git-checkpoint" -> handleGitCheckpointPromise(id, name, argAsObject(arg))
                "file" -> handleFilePromise(id, name, argAsObject(arg))
                "model-selection" -> handleModelSelectionPromise(id, name)
                "zcode-task" -> handleTaskPromise(id, name, argAsObject(arg))
                "off-peak-task" -> handleOffPeakTaskPromise(id, name, arg)
                "feedback" -> handleFeedbackPromise(id, name, argAsObject(arg))
                "usage-stats" -> handleUsageStatsPromise(id, name, arg)
                // Phase 4C (C2): the slash panel's extra sections over their own channels.
                "commands" -> handleCommandsPromise(id, name)
                "subagents" -> handleSubagentsPromise(id, name)
                // G30 (vc51 Phase 4B): the INV-9 seed — the shipped host has no bots list
                // RPC (fs-backed provider runtimes), so the viewer degrades honestly there;
                // the mock serves the scripted inventory for the rig.
                "bots" -> handleBotsPromise(id, name)
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
            "subscribeWorkspaceConfigV4",
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
        synchronized(v4CallLedger) { v4CallLedger.add((name ?: "?") to (argAsObject(arg) ?: JsonObject(emptyMap()))) }
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
                // Pass-22 rig arm (MOCK_SET_THOUGHT_LEVEL_STALE): the host's PROTOCOL
                // layer CAS-checks expectedRevision via FQ and answers a JSON-RPC
                // error (Dl -32009) — never a {status:"stale"} ack — and mutates
                // nothing. The phone's port maps the throw to FAILED, so its
                // enumerated STALE notice branch is structurally unreachable.
                if (script.setThoughtLevelStale) {
                    val params = argAsObject(arg)
                    val expected = (params?.get("expectedRevision") as? JsonPrimitive)?.content
                    println("MOCK: setThoughtLevel forced revision-mismatch (MOCK_SET_THOUGHT_LEVEL_STALE) -> Dl -32009 (expected=$expected)")
                    respond(
                        id, ChannelWire.ResponseType.PromiseError,
                        RpcValue.Obj(setThoughtLevelStaleErrorBody(expected)),
                    )
                    return
                }
                val params = argAsObject(arg)
                val level = params?.strOrNull("thoughtLevel")
                if (level == null) {
                    respondError(id, "fault.mock.unscripted:setThoughtLevel")
                } else {
                    stateRevision.incrementAndGet()
                    script.sessionConfig = thoughtLevelConfig(level)
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
                        put("localTerminal", JsonPrimitive(script.helloLocalTerminal(System.getenv("MOCK_TERMINAL_CAP_OFF"))))
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
                    // G48 (vc52 Phase 11): record the declared capabilities — the
                    // workflowRun delta gate reads this (production:
                    // clientSupportsWorkflowRunDeltas === true only when declared).
                    lastClientHelloCapabilities = hello["capabilities"] as? JsonObject
                    respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Undefined)
                }
            }
            "subscribeWorkspaceConfigV4" -> {
                // F-CHAT-FOCUS4 (field 2026-10-27, vc50): the PRODUCTION adapter's
                // subscribeWorkspaceConfigV4 — params are the workspace target ONLY;
                // the adapter builds the topic (resolveWorkspaceKey: identity ?: path).
                val params = argAsObject(arg) ?: return respondError(id, "fault.subscribe.params")
                if (params.str("workspacePath").isBlank()) return respondError(id, "fault.subscribe.workspaceScope")
                lastSubscribeWorkspacePath = params.str("workspacePath")
                val resolvedKey = params.str("workspaceIdentity") ?: params.str("workspacePath")
                val cfgTopic = "workspace-config/$resolvedKey"
                val cfgSubscriptionId = "sub-mock-cfg-${cfgTopic.hashCode()}"
                respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                    put("ack", buildJsonObject {
                        put("subscriptionId", JsonPrimitive(cfgSubscriptionId))
                        put("mode", JsonPrimitive("snapshot"))
                        put("logEpoch", JsonPrimitive("cfg-epoch-mock-1"))
                    })
                }))
                script.workspaceConfigState?.let { cfg ->
                    pushWorkspaceConfigFrame(cfgTopic, cfgSubscriptionId, cfg)
                }
                return
            }
            "subscribeConversationV4" -> {
                val params = argAsObject(arg) ?: return respondError(id, "fault.subscribe.params")
                // Round-3 field fidelity: production routes by the workspace scope —
                // a blank workspacePath is a client-side routing bug, surface it loudly.
                if (params.str("workspacePath").isBlank()) return respondError(id, "fault.subscribe.workspaceScope")
                lastSubscribeWorkspacePath = params.str("workspacePath")
                // Phase 2 (§11.2): a workspace-config topic subscribe carries an
                // EXPLICIT topic (the desktop adapter's subscribeWorkspaceConfigV4
                // shape) instead of a sessionId. KEPT for wire-shape corpus
                // compatibility; production calls the dedicated method now.
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
                // RIG-5: this conversation owns the command effects from here on (the
                // D4.1 artifact pushed every patch to the hardcoded workspace session).
                activeSessionId = sessionId
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
                // RIG-3: the fresh-interaction push schedules on the FIRST subscribe —
                // the rig app backgrounds within the window and takes the patch off-foreground.
                armInteractionPush()
                // Debt #13 (pass 14): the subscribe-anchored delta schedule (a no-op
                // unless MOCK_RUN_DELTA_ANCHOR=subscribe armed it).
                armWorkflowRunDeltasOnSubscribe()
                // Pass-16: the subscribe-anchored stall schedule (a no-op unless
                // MOCK_TURN_STALL armed it).
                armTurnStallOnSubscribe()
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
                // RIG-5: a resync is the same ownership signal a subscribe is.
                activeSessionId = sessionId
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
                // Pass-35 fault arm: the Load-earlier error strip + Retry face.
                if (script.pageRangeFault) {
                    println("MOCK: page range fault (MOCK_PAGE_FAULT)")
                    return respondError(id, "fault.rowsRange.rig")
                }
                val params = argAsObject(arg) ?: return respondError(id, "fault.rowsRange.params")
                val sessionId = params.str("sessionId")
                val limit = params.strOrNull("limit")?.toIntOrNull() ?: 10
                val beforeRowId = params.strOrNull("beforeRowId")?.toLongOrNull()
                // RIG-P3 (vc52 Phase 10 / F-37-D): the page must be the rows STRICTLY
                // OLDER than beforeRowId, keyed consistently with the snapshot's
                // rowsWindowSchema (row ids rowIdBase..top) — the old handler ignored
                // beforeRowId and served rows 1..limit with hasMore=false, so the
                // phone's Load-earlier leg could never page backwards (every call
                // returned the SAME newest page). hasMore stays true while rows remain
                // above the page (the snapshot's firstRowId bounds the store top).
                // The store's top must match the SNAPSHOT WINDOW's id base — with the
                // RIG-P3 tail-window arm (MOCK_ROWS_TOTAL) the window ids run
                // base..base+count-1 (e.g. 1682..2001), NOT 1..count; capping at
                // count-1 served pages BELOW the window (an unfetchable id gap).
                val storeTop = snapshotRowIdTop(script.snapshotRowCount ?: workspace.rowCount, script.snapshotRowIdBase)
                val page = rowsRangePage(storeTop, beforeRowId, limit)
                val rows = page.map { rid ->
                    schemaRow(
                        sessionId, rid,
                        if (rid % 2L == 0L) RowKind.ASSISTANT_TEXT else RowKind.REASONING,
                        "synthetic row $rid",
                    )
                }
                respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                    put("rows", JsonArray(rows))
                    put("atSeq", JsonPrimitive(maxOf(1L, seqHeads[sessionId] ?: 1L)))
                    put("atRevision", JsonPrimitive(script.currentRevision))
                    put("atLogEpoch", JsonPrimitive(logEpoch))
                    put("hasMore", JsonPrimitive(page.first > MockZCode.ROW_ID_FLOOR))
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
                // pass-25 rig observability: the generic v4call line carries no
                // command type, which left the phone's spontaneous frames
                // (e.g. the unattributed post-send sendConversationCommandV4)
                // unattributable from the log alone.
                println("MOCK: command type=$type")
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
                // Phase 5A (L6.4 + I1): startSavedWorkflow — the typed Bu vocabulary rides reasonCode.
                if (type == "startSavedWorkflow") {
                    val payload = env?.get("payload") as? JsonObject
                    val name = payload?.strOrNull("name")
                    script.startedWorkflows.add(payload ?: JsonObject(emptyMap()))
                    val known = (script.savedWorkflows?.get("workflows") as? JsonArray)
                        ?.any { (it as? JsonObject)?.strOrNull("name") == name } == true
                    val reason = when {
                        name.isNullOrBlank() -> "invalid_name"
                        !known -> "not_found"
                        else -> null
                    }
                    if (reason != null) {
                        respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                            put("commandId", JsonPrimitive(commandId))
                            put("status", JsonPrimitive("rejected"))
                            put("reasonCode", JsonPrimitive(reason))
                            put("revisionAtDecision", JsonPrimitive(stateRevision.get()))
                        }))
                        return
                    }
                    val runId = "wf-run-${script.workflowRuns.size + 1}"
                    val now = System.currentTimeMillis()
                    script.workflowRuns.add(buildJsonObject {
                        put("runId", JsonPrimitive(runId)); put("label", JsonPrimitive(name!!))
                        put("status", JsonPrimitive("running")); put("updatedAt", JsonPrimitive(now)); put("resumable", JsonPrimitive(false))
                    })
                    script.workflowRunEvents[runId] = mutableListOf(
                        buildJsonObject { put("sequence", JsonPrimitive(1)); put("type", JsonPrimitive("run.started")); put("payload", buildJsonObject { put("message", JsonPrimitive("started $name")) }) },
                    )
                    script.savedWorkflowRuns.add(buildJsonObject {
                        put("runId", JsonPrimitive(runId)); put("name", JsonPrimitive(name)); put("status", JsonPrimitive("running"))
                        put("createdAt", JsonPrimitive(now)); put("updatedAt", JsonPrimitive(now)); put("spentTokens", JsonPrimitive(0))
                    })
                    respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                        put("commandId", JsonPrimitive(commandId))
                        put("status", JsonPrimitive("accepted"))
                        put("revisionAtDecision", JsonPrimitive(stateRevision.get()))
                        put("result", buildJsonObject { put("runId", JsonPrimitive(runId)) })
                    }))
                    return
                }
                // ---- G3 (vc51): the run sheet's Resume + amend — REAL run-state mutations
                // so the rig leg proves the round-trip (the sheet's refreshed run reflects
                // the new state via the state patch the revision bump rides). Rejections are
                // typed respondError faults (the desktop's own vocabulary), not acks.
                if (type == "resumeWorkflowRun" || type == "amendWorkflowRunSettings") {
                    val payload = env?.get("payload") as? JsonObject
                    when (val outcome = workflowRunCommandResult(type, payload?.strOrNull("workId"))) {
                        is WorkflowRunCommandOutcome.Fault -> {
                            respondError(id, outcome.code)
                            return
                        }
                        is WorkflowRunCommandOutcome.Accepted -> {
                            respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                                put("commandId", JsonPrimitive(commandId))
                                put("status", JsonPrimitive("accepted"))
                                put("revisionAtDecision", JsonPrimitive(stateRevision.get()))
                                put("result", outcome.result)
                            }))
                            return
                        }
                    }
                }
                // Pass-11 (MOCK_REFUSE_COMMANDS): the named types answer fault.* — the
                // client's server-spoke rejection class, so goal verbs degrade to their
                // SendNotice.GOAL_*_REFUSED branches (Outcome.Failed, outbox terminal).
                refuseReasonFor(type)?.let { reason ->
                    respondError(id, reason)
                    return
                }
                // Mock-fidelity debt #1 (pass 13): the typed unknown-interactionId
                // rejection — an answer naming no pending slot is REJECTED with the
                // desktop's status/reasonCode shape instead of the old unconditional
                // accept (the answer-with-error face was structurally undrivable).
                if (type == "resolveInteraction") {
                    val iid = (env?.get("payload") as? JsonObject)?.strOrNull("interactionId")
                    resolveInteractionRejection(iid)?.let { code ->
                        respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                            put("commandId", JsonPrimitive(commandId))
                            put("status", JsonPrimitive("rejected"))
                            put("reasonCode", JsonPrimitive(code))
                            put("revisionAtDecision", JsonPrimitive(stateRevision.get()))
                        }))
                        return
                    }
                }
                // Pass-22 rig arm (MOCK_COMPACT_COMMAND): the host's command-plane
                // compact handler (vMc) — under enqueue/guide/choice (or an active
                // turn) the compact SELF-QUEUES as a kind:"compact" queue item and
                // answers accepted; a compact while one is already queued answers the
                // typed rejected ack compactOperationLock ("Compact is already running
                // or queued"). The compact command is NOT CAS-guarded (the phone's
                // verified set) — the baseRevision stale arm must never intercept it.
                if (type == "compact" && script.compactCommandArm) {
                    when (val decision = compactCommandDecision()) {
                        is CompactDecision.Rejected -> {
                            println("MOCK: compact REJECTED compactOperationLock (MOCK_COMPACT_COMMAND — already queued)")
                            respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                                put("commandId", JsonPrimitive(commandId))
                                put("status", JsonPrimitive("rejected"))
                                put("reasonCode", JsonPrimitive(decision.reasonCode))
                                put("message", JsonPrimitive(decision.message))
                                put("revisionAtDecision", JsonPrimitive(stateRevision.get()))
                            }))
                            return
                        }
                        is CompactDecision.Queued -> {
                            println("MOCK: compact deferred to queue (MOCK_COMPACT_COMMAND, routing=${decision.routing}) -> ${decision.queueItemId}")
                            respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                                put("commandId", JsonPrimitive(commandId))
                                put("status", JsonPrimitive("accepted"))
                                put("revisionAtDecision", JsonPrimitive(stateRevision.get()))
                            }))
                            return
                        }
                        CompactDecision.Passthrough -> pushCompactRunMarkers()
                    }
                }
                // Pass-19 rig arm (debt #20, MOCK_QUEUE_GHOST_APPEND_MS): the desktop's
                // held-queue CAS — a trio-bearing sendText whose expectedHeldQueueItemIds
                // no longer matches the live queue answers the desktop-faithful failure
                // ack and mutates nothing; arming also schedules the one-shot silent
                // ghost append that moves the queue under the phone's state.
                if (type == "sendText" && script.heldQueueCasArm) {
                    armHeldQueueGhostOnce()
                    heldQueueCasRejection(env)?.let { casAck ->
                        respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(casAck))
                        return
                    }
                }
                // Pass-20 rig arm (F-P20-A): the GOAL half of the same admission — a
                // disposition-less sendGoalCommand under choice answers the host's
                // heldQueueDispositionRequired failure ack and mutates nothing (the
                // phone's goal surfaces carry no trio, so the real desktop refuses
                // every phone goal send while a paused queue + choice state stands).
                if (type == "sendGoalCommand" && script.heldQueueCasArm) {
                    heldQueueGoalAdmissionRejection(env)?.let { goalAck ->
                        respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(goalAck))
                        return
                    }
                }
                // Pass-20 stale arm (MOCK_CAS_STALE_TYPES): the listed types answer the
                // host's revision-mismatch ack — the phone's typed SESSION_CHANGED
                // stale faces become drivable.
                casStaleAck(type, env)?.let { staleAck ->
                    respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(staleAck))
                    return
                }
                // Pass-37 rig arm (MOCK_FEEDBACK_FAIL=1): setAssistantFeedback answers the
                // host's failed ack (the F-P19-A failed-ack shape) — the phone's keyed
                // FEEDBACK_REFUSED notice becomes drivable (zero series receipts before
                // pass 37). Mutates nothing; dispatcher-level like the stale arm, so it
                // outranks any handler effect. Sticky while armed.
                if (type == "setAssistantFeedback") {
                    feedbackFailAck(env?.strOrNull("commandId"))?.let { failAck ->
                        println("MOCK: setAssistantFeedback forced fail (MOCK_FEEDBACK_FAIL)")
                        respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(failAck))
                        return
                    }
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
            // ---- Phase 5A (A3.3): workflow runs — Br/Rl shapes over the mock's run state ----
            "conversationWorkflowRunsV4" -> respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                put("runs", JsonArray(ArrayList(script.workflowRuns)))
            }))
            "conversationWorkflowRunEventsV4" -> {
                val a = argAsObject(arg)
                val runId = a?.strOrNull("runId")
                val after = a?.strOrNull("afterSequence")?.toLongOrNull() ?: 0L
                val limit = a?.strOrNull("limit")?.toIntOrNull() ?: 200
                val all = script.workflowRunEvents[runId] ?: emptyList()
                val page = all.filter { ((it["sequence"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L) > after }
                respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                    put("events", JsonArray(page.take(limit)))
                    put("hasMore", JsonPrimitive(page.size > limit))
                }))
            }
            "conversationWorkflowRunArtifactsV4" -> respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject { put("artifacts", JsonArray(emptyList())) }))
            "conversationWorkflowRunNodeResultV4", "conversationWorkflowRunWorkspaceV4" ->
                respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(JsonObject(emptyMap())))
            "conversationWorkflowRunArtifactDataV4" -> respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                put("items", JsonArray(emptyList())); put("hasMore", JsonPrimitive(false))
            }))
            "conversationWorkflowRunArtifactReadV4" -> respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                put("dataBase64", JsonPrimitive("")); put("mediaType", JsonPrimitive("text/plain")); put("totalBytes", JsonPrimitive(0)); put("nextOffset", kotlinx.serialization.json.JsonNull)
            }))
            // ---- Phase 5B (A3.11): the rewind preview (nt shape: canApply + safe/unsafe/ignored) ----
            "conversationFileRewindPreviewV4" -> scriptableObject(id, script.rewindPreview, "conversationFileRewindPreviewV4")
            // ---- Phase 5B (A3.12): the plans projection ----
            "conversationPlansV4" -> scriptableObject(id, script.plansState, "conversationPlansV4")
            // ---- Phase 5B (A3.13): the trajectory + session reads ----
            "readSessionMessages" -> respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Arr(
                (script.sessionMessages ?: JsonArray(emptyList())).map { RpcValue.Obj(it as JsonObject) },
            ))
            "readSessionEvents" -> respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Arr(emptyList()))
            "readSessionDebug" -> respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(JsonObject(emptyMap())))
            // ---- Phase 5A (L6.4): the saved-workflow hub words (BJ / FJ / JJ shapes) ----
            // ADR-024 (Phase 5D): the Settings-side grant — echoes the digest pair granted.
            "grantWorkspaceHookTrust" -> {
                val params = argAsObject(arg)
                respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                    put("workspacePath", JsonPrimitive(params?.strOrNull("workspacePath") ?: ""))
                    put("bundleDigest", JsonPrimitive(params?.strOrNull("bundleDigest") ?: ""))
                    put("hookDeclarationDigest", JsonPrimitive(params?.strOrNull("hookDeclarationDigest") ?: ""))
                    put("granted", JsonPrimitive(true))
                }))
            }
            "setPluginEnabled" -> {
                val pluginId = argAsObject(arg)?.str("pluginId")
                val enabled = (argAsObject(arg)?.get("enabled") as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
                if (pluginId == null || enabled == null) respondError(id, "fault.mock.unscripted:setPluginEnabled")
                else {
                    // flip the scripted plugin's flag so the next listPlugins is wire-true
                    script.listPlugins?.let { lp ->
                        val arr = lp["plugins"] as? JsonArray
                        if (arr != null) {
                            val rebuilt = arr.map { item ->
                                val o = item as? JsonObject
                                if (o != null && (o["id"] as? JsonPrimitive)?.content == pluginId) {
                                    JsonObject(o + ("enabled" to JsonPrimitive(enabled)))
                                } else item
                            }
                            script.listPlugins = JsonObject(lp + ("plugins" to JsonArray(rebuilt)))
                        }
                    }
                    respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                        put("pluginId", JsonPrimitive(pluginId)); put("enabled", JsonPrimitive(enabled))
                    }))
                }
            }
                            "getTaskTokenUsage" -> scriptableObject(id, script.taskTokenUsage, "getTaskTokenUsage")
                // Pass-34 (Part AK): the C7 lazy tool-output slice — SERVED bodies for
                // seeded ids (the desktop's `{toolCalls:[{toolCallId, output}]}` shape);
                // an unscripted id answers the honest fault so the app's fallback face
                // (the retained preview copy) drives.
                "getTaskSnapshotToolCallsSlice" -> {
                    val params = argAsObject(arg)
                    val wanted = (params?.get("toolCallIds") as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.content }
                        .orEmpty()
                    val served = wanted.firstOrNull { script.toolCallSlices.containsKey(it) }
                    if (served == null) {
                        println("MOCK: tool slice fault for ${wanted.joinToString(",")}")
                        respondError(id, "fault.mock.unscripted:getTaskSnapshotToolCallsSlice")
                    } else {
                        println("MOCK: tool slice serve $served")
                        respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                            put("toolCalls", JsonArray(wanted.map { cid -> buildJsonObject {
                                put("toolCallId", JsonPrimitive(cid))
                                script.toolCallSlices[cid]?.let { put("output", JsonPrimitive(it)) }
                            } }))
                        }))
                    }
                }
                // G42-P23 (vc52 Phase 12): the management verbs — MUTATING fixtures
                // (the scripted list mutates so the hub reflects the change on reload;
                // ok:false fixtures drive the refused faces).
                "deleteSavedWorkflow" -> respondWorkflowManagement(id, computeWorkflowDelete(argAsObject(arg)))
                "updateSavedWorkflowMeta" -> respondWorkflowManagement(id, computeWorkflowUpdateMeta(argAsObject(arg)))
                "moveSavedWorkflow" -> respondWorkflowManagement(id, computeWorkflowMove(argAsObject(arg)))
                "listSavedWorkflows" -> scriptableObject(id, script.savedWorkflows, "listSavedWorkflows")
            "getSavedWorkflow" -> {
                val name = argAsObject(arg)?.strOrNull("name")
                val wf = (script.savedWorkflows?.get("workflows") as? JsonArray)?.firstOrNull { (it as? JsonObject)?.strOrNull("name") == name } as? JsonObject
                respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(
                    if (wf == null) buildJsonObject { put("ok", JsonPrimitive(false)); put("reason", JsonPrimitive("not_found")) }
                    else buildJsonObject {
                        put("ok", JsonPrimitive(true)); put("name", wf["name"]!!); put("path", wf["path"] ?: JsonPrimitive("")); put("scope", wf["scope"] ?: JsonPrimitive("project"))
                        put("meta", buildJsonObject { wf["description"]?.let { put("description", it) }; wf["whenToUse"]?.let { put("whenToUse", it) }; wf["args"]?.let { put("args", it) } })
                        put("script", JsonPrimitive("// synthetic"))
                    },
                ))
            }
            "listSavedWorkflowRuns" -> {
                val name = argAsObject(arg)?.strOrNull("name")
                val runs = script.savedWorkflowRuns.filter { name == null || it.strOrNull("name") == name }
                respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject { put("runs", JsonArray(runs)) }))
            }
            // ---- vc51 Phase-3 rig arms ----
            "subscribeSessionsIndexV4" -> {
                val params = argAsObject(arg) ?: return respondError(id, "fault.subscribe.params")
                if (params.str("workspacePath").isBlank()) return respondError(id, "fault.subscribe.workspaceScope")
                val key = params.strOrNull("workspaceIdentity")
                    ?: params.str("workspacePath")
                val topic = "sessions-index/$key"
                val subId = "sub-mock-idx-${topic.hashCode()}"
                respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                    put("ack", buildJsonObject {
                        put("subscriptionId", JsonPrimitive(subId))
                        put("mode", JsonPrimitive("snapshot"))
                        put("logEpoch", JsonPrimitive("idx-epoch-mock-1"))
                    })
                }))
                val snapshot = script.sessionsIndexSnapshot
                if (snapshot != null) pushSessionsIndexFrame(topic, subId, snapshot = snapshot, delta = null)
                val delay = script.sessionsIndexUpsertDelayMs
                val upsert = script.sessionsIndexUpsertDelta
                if (delay != null && upsert != null) {
                    scope.launch {
                        kotlinx.coroutines.delay(delay)
                        pushSessionsIndexFrame(topic, subId, snapshot = null, delta = upsert)
                    }
                }
            }
            "resyncSessionsIndexV4", "resyncWorkspaceConfigV4" -> {
                // G43-P13 (vc52 Phase 11): the index/config RESYNC verbs —
                // {subscriptionId, base?, forceSnapshot?} → {ack: {subscriptionId, mode, logEpoch}}
                // (byte-verified request schema A7e/ack fl on asar 12a2fc93…). The mock
                // re-serves the CURRENT snapshot frame (the freshness receipt). A scripted
                // fault (script.resyncIndexFault) serves the not-owned face so the ladder's
                // re-subscribe fallback is drivable on the rig.
                val params = argAsObject(arg) ?: return respondError(id, "fault.resync.params")
                script.resyncIndexFault?.let { fault ->
                    return respondError(id, fault)
                }
                val topic = if (name == "resyncSessionsIndexV4") {
                    "sessions-index/${lastSubscribeWorkspacePath ?: workspace.workspacePath}"
                } else {
                    "workspace-config/${lastSubscribeWorkspacePath ?: workspace.workspacePath}"
                }
                val subId = if (topic.startsWith("sessions-index/")) "sub-mock-idx-${topic.hashCode()}" else "sub-mock-cfg-${topic.hashCode()}"
                respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                    put("ack", buildJsonObject {
                        put("subscriptionId", JsonPrimitive(subId))
                        put("mode", JsonPrimitive("snapshot"))
                        put("logEpoch", JsonPrimitive(if (topic.startsWith("sessions-index/")) "idx-epoch-mock-1" else "cfg-epoch-mock-1"))
                    })
                }))
                if (topic.startsWith("sessions-index/")) {
                    script.sessionsIndexSnapshot?.let { pushSessionsIndexFrame(topic, subId, snapshot = it, delta = null) }
                } else {
                    // G43: the config resync re-serves the workspace-config snapshot frame.
                    val cfgTopic = topic
                    pushWorkspaceConfigFrame(cfgTopic, subId, stateBlockForTest() ?: JsonObject(emptyMap()), delta = false)
                }
            }
            "unsubscribeConversationV4", "unsubscribeWorkspaceConfigV4", "unsubscribeSessionsIndexV4" -> {
                // GAP-A3/G14: the phone's teardown words — acknowledged (the desktop
                // releases its owned subscription; the mock records the call).
                respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Undefined)
            }
            "backgroundBashOutputV4" -> {
                // G15: {sessionId, workId} → the scripted snapshot or the typed
                // unavailable union member (background-bash-output.ts).
                val params = argAsObject(arg)
                val workId = params?.strOrNull("workId")
                val scripted = workId?.let { script.backgroundBashOutputs[it] }
                if (scripted != null) {
                    respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(scripted))
                } else {
                    respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                        put("kind", JsonPrimitive("unavailable"))
                        put("workId", JsonPrimitive(workId ?: ""))
                    }))
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
                if (attachmentFailFault() == "fault.attachment.beginFail") {
                    respondError(id, "fault.attachment.beginFail")
                    println("MOCK: attachmentBegin forced fail (MOCK_ATTACHMENT_FAIL=begin)")
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
                if (script.attachmentChunkDelayMs > 0L) {
                    Thread.sleep(script.attachmentChunkDelayMs)
                }
                if (attachmentFailFault() == "fault.attachment.chunkFail") {
                    respondError(id, "fault.attachment.chunkFail")
                    println("MOCK: attachmentChunk forced fail (MOCK_ATTACHMENT_FAIL=chunk)")
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
                if (attachmentFailFault() == "fault.attachment.commitFail") {
                    respondError(id, "fault.attachment.commitFail")
                    println("MOCK: attachmentCommit forced fail (MOCK_ATTACHMENT_FAIL=commit)")
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
                val removed = attachmentAbortMutate(uploadId)
                if (script.attachmentKeepAfterAbort) {
                    println("MOCK: attachmentAbort KEPT (MOCK_ATTACHMENT_KEEP_AFTER_ABORT) upload=$uploadId — post-abort chunks accepted")
                } else if (removed) {
                    println("MOCK: attachmentAbort removed upload=$uploadId")
                }
                respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject { }))
            }
            // G16 (vc51 Phase 4D): the attachment PREVIEW-SOURCE + conversation-scoped
            // read/stat words. The shipped host answers `{kind:"chunked"}` for a
            // non-desktop-continuous client — the phone then pulls the preview bytes
            // over conversationAttachmentRead (its larger preview budget).
            "attachmentPreviewSourceV4" -> {
                respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                    put("kind", JsonPrimitive("chunked"))
                }))
            }
            "conversationAttachmentStatV4" -> {
                val params = argAsObject(arg)
                val ref = params?.str("ref")
                val att = synchronized(committedAttachments) { committedAttachments[ref] }
                if (ref == null || att == null) {
                    respondError(id, "fault.attachment.unknownRef")
                    return
                }
                respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                    put("mediaType", JsonPrimitive(att.mime))
                    put("totalBytes", JsonPrimitive(att.data.toByteArray().size))
                }))
            }
            "conversationAttachmentReadV4" -> {
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
        // p47: the args log line — the terminal channel printed nothing forever
        // (the ae091f81/7feb2695 template: arg-exact receipts for create/write/resize/dispose).
        println("MOCK: terminal ${name ?: "?"} args=${params ?: JsonObject(emptyMap())}")
        when (name) {
            // ADR-022 (Phase 5D): the armed input tier — write echoes the bytes back on
            // onDynamicData (the rig proves the round-trip); resize acks.
            "write" -> {
                val data = params?.str("data") ?: ""
                val terminalId = params?.str("id") ?: ""
                script.terminalWrites.add(data)
                pushTerminalData(terminalId, data)
                respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Undefined)
            }
            "resize" -> respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Undefined)
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
                // p47: MOCK_TERMINAL_EXIT_MS=<ms> schedules the exit event after the
                // attach settles — drives the phone's Exited face (terminal_status_exited).
                System.getenv("MOCK_TERMINAL_EXIT_MS")?.trim()?.toLongOrNull()?.let { ms ->
                    scope.launch {
                        kotlinx.coroutines.delay(ms)
                        pushTerminalExit(terminalId, 0)
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
            sendLogicalFrame(bytes, isData = true)
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
            sendLogicalFrame(bytes, isData = true)
        }
    }

    // Phase 5B (A3.11): the git-checkpoint trio — stateful so the rig proves create →
    // diff → restore (with the conflict branch on a scripted flag).
    private fun handleGitCheckpointPromise(id: Int, name: String?, arg: JsonObject?) {
        // p45: the args log line — the restore/force wire receipt must be ARG-EXACT
        // (the force confirm's pair divergence is only provable from the ids the phone
        // actually sent; respondError-style silence hid the git channel's args forever).
        println("MOCK: git-checkpoint ${name ?: "?"} args=${arg ?: JsonObject(emptyMap())}")
        when (name) {
            "createCheckpoint" -> {
                val now = System.currentTimeMillis()
                val meta = buildJsonObject {
                    put("checkpointId", JsonPrimitive("ckpt-${script.checkpoints.size + 1}"))
                    put("workspacePath", JsonPrimitive(arg?.strOrNull("workspacePath") ?: ""))
                    put("repoRoot", JsonPrimitive("/synthetic/repo")); put("workspaceInRepoPath", JsonPrimitive("."))
                    put("createdAt", JsonPrimitive(now))
                    put("refName", JsonPrimitive("refs/zcode/checkpoints/ckpt")); put("commitOid", JsonPrimitive("deadbeef"))
                    put("scope", JsonPrimitive("workspace"))
                }
                script.checkpoints.add(meta)
                respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(meta))
            }
            "diffCheckpoints" -> respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                put("files", JsonArray(script.checkpointDiffFiles.map { buildJsonObject {
                    put("kind", JsonPrimitive("modified")); put("repoRelativePath", JsonPrimitive(it))
                } }))
            }))
            // vc51 Phase 4A (CKPT-HYDRATE): the list word the phone hydrates its
            // checkpoint card from on entry — the full scripted snapshot history.
            "listCheckpoints" -> respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                put("checkpoints", JsonArray(script.checkpoints.toList()))
            }))
            "restoreBetweenCheckpoints" -> {
                val force = (arg?.get("force") as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
                if (script.checkpointConflicts && !force) {
                    // p46: the conflict paths come from script.checkpointConflictFiles
                    // (MOCK_CHECKPOINT_CONFLICT_FILES widens the default single file so
                    // the phone's conflicts.take(3) body slice is drivable).
                    respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(checkpointConflictPayload()))
                } else {
                    respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                        put("success", JsonPrimitive(true))
                        put("restoredPaths", JsonArray(script.checkpointDiffFiles.map { JsonPrimitive(it) }))
                    }))
                }
            }
            "deleteCheckpoint" -> respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(JsonObject(emptyMap())))
            else -> respondError(id, "fault.mock.unknownMethod:git-checkpoint-${name ?: "?"}")
        }
    }

    private val gitChannelLedger = ArrayList<Pair<String, JsonObject>>()
    fun taskChannelLedgerForTest(channel: String): List<Pair<String, JsonObject>> =
        if (channel == "git") synchronized(gitChannelLedger) { ArrayList(gitChannelLedger) } else emptyList()

    private fun handleGitPromise(id: Int, name: String?, arg: RpcValue?) {
        synchronized(gitChannelLedger) { gitChannelLedger.add((name ?: "?") to (argAsObject(arg) ?: JsonObject(emptyMap()))) }
        when (name) {
            // ADR-021 (Phase 5D): the write tier — stage/unstage/discard mutate the
            // scripted changes; commit/push/branch echo the bundle-extracted shapes
            // (with the scripted typed-issue branch).
            "stagePaths", "unstagePaths" -> {
                val paths = (argAsObject(arg)?.get("paths") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
                if (paths.isEmpty()) respondError(id, "fault.mock.unscripted:$name")
                else {
                    // p41: the REMOVE must span spellings — stagedPaths holds the seeded
                    // ABSOLUTE paths while the phone sends workspaceRelativePath ones,
                    // and removeAll is exact-string (an unseeded relative spelling would
                    // no-op the remove). Adds keep the phone's spelling: servedGitChanges
                    // matches both spellings (abs == p || rel == p || endsWith).
                    if (name == "stagePaths") script.stagedPaths.addAll(paths)
                    else script.stagedPaths.removeAll { sp -> paths.any { p -> sp == p || sp == canonicalGitPath(p) } }
                    respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Undefined)
                }
            }
            "discardPaths" -> {
                val paths = (argAsObject(arg)?.get("paths") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
                if (paths.isEmpty()) respondError(id, "fault.mock.unscripted:discardPaths")
                else {
                    script.discardedPaths.addAll(paths)
                    script.stagedPaths.removeAll(paths.toSet())
                    respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Undefined)
                }
            }
            "commit" -> {
                val message = argAsObject(arg)?.str("message") ?: ""
                if (message.isBlank()) respondError(id, "Commit message cannot be empty")
                else {
                    // p40 (pass-40): a commit CONSUMES the served rows the desktop would
                    // commit — stagedOnly commits the section-"staged" view, includeUnstaged
                    // commits everything still served — so the phone's post-commit
                    // fetchChanges() sees the shrink instead of the same rows again.
                    val stagedOnly = (argAsObject(arg)?.get("stagedOnly") as? JsonPrimitive)?.content == "true"
                    val served = servedGitChanges()
                    val toCommit = served.mapNotNull { row ->
                        val obj = row as? JsonObject ?: return@mapNotNull null
                        val section = (obj["section"] as? JsonPrimitive)?.content
                        if (stagedOnly && section != "staged") return@mapNotNull null
                        (obj["path"] as? JsonPrimitive)?.content
                    }
                    script.committedPaths.addAll(toCommit)
                    // p41: spell-aware consume — the staged set may hold the phone's
                    // relative spelling while toCommit holds the fixture's absolute one.
                    script.stagedPaths.removeAll { sp -> toCommit.any { c -> sp == c || sp == canonicalGitPath(c) || canonicalGitPath(sp) == c } }
                    respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                        put("commitHash", JsonPrimitive("abc123${script.commits.size}"))
                        put("summary", buildJsonObject { put("additions", JsonPrimitive(1)); put("deletions", JsonPrimitive(0)); put("files", JsonPrimitive(toCommit.size)) })
                    }))
                }
            }
            // RIG-P2 (vc52 Phase 14): the N-BG-2 refusal arm answers FIRST for every
            // git read the phone can surface (the deliberate fault beats the fixture —
            // the same rule the G53 depth trio applies), so the known-code face is
            // drivable per method on the rig.
            "generateCommitMessage" -> {
                script.gitRefusals["generateCommitMessage"]?.let { return respondError(id, it) }
                scriptableObject(id, script.generatedCommitMessage, "generateCommitMessage")
            }
            "push" -> {
                if (script.gitPushFails) respondError(id, "Cannot push while HEAD is detached.")
                else respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                    put("branchName", JsonPrimitive("main")); put("trackingBranchName", JsonPrimitive("origin/main"))
                }))
            }
            "switchBranch", "createBranchAndSwitch" -> {
                // str() is NON-nullable (missing key → ""), so the elvis below must use
                // strOrNull — with str(), createBranchAndSwitch's wire arg spelling
                // (`branchName`, the transport's gitWrite) never won the elvis and the
                // branch was silently lost on the wire (the recorded "summary doesn't
                // mutate post-create" class).
                val branch = argAsObject(arg)?.let { it.strOrNull("targetBranchName") ?: it.strOrNull("branchName") } ?: ""
                if (script.gitBranchIssue != null) {
                    respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                        put("action", JsonPrimitive(if (name == "switchBranch") "switch" else "create-and-switch"))
                        put("branchName", JsonPrimitive(branch))
                        put("didChange", JsonPrimitive(false)); put("created", JsonPrimitive(name == "createBranchAndSwitch"))
                        put("issues", JsonArray(listOf(script.gitBranchIssue!!)))
                    }))
                } else {
                    // RIG-5: a successful switch/create MUTATES the served git state — the
                    // summary's branch + the branch list's head follow the ack (the Phase-4
                    // exit-gate's recorded ack-without-mutate fidelity note).
                    if (branch.isNotEmpty()) script.currentGitBranch = branch
                    respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                        put("action", JsonPrimitive(if (name == "switchBranch") "switch" else "create-and-switch"))
                        put("branchName", JsonPrimitive(branch))
                        put("didChange", JsonPrimitive(true)); put("created", JsonPrimitive(name == "createBranchAndSwitch"))
                        put("issues", JsonArray(emptyList()))
                    }))
                }
            }
            "getLocalBranches" -> respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                put("headRefType", JsonPrimitive("branch")); put("currentBranchName", JsonPrimitive(script.currentGitBranch ?: "main"))
                put("branches", JsonArray(listOf("main", "feature/a").map { b ->
                    buildJsonObject { put("name", JsonPrimitive(b)); put("upstream", JsonPrimitive(if (b == "main") "origin/main" else null)) }
                }))
            }))
            "getIdentity" -> respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                if (script.gitIdentityMissing) {
                    put("userName", kotlinx.serialization.json.JsonNull); put("userEmail", kotlinx.serialization.json.JsonNull)
                } else {
                    put("userName", JsonPrimitive("Ernest")); put("userEmail", JsonPrimitive("takis312@hotmail.com"))
                }
            }))
            "getRepositorySummary" -> {
                val merged = mergedGitSummary()
                if (merged == null) respondError(id, "fault.git.notAvailable")
                else respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(merged))
            }
            "getChanges" -> {
                script.gitRefusals["getChanges"]?.let { return respondError(id, it) }
                val changes = script.gitChanges
                if (changes == null) respondError(id, "fault.git.notAvailable")
                else {
                    // N-BK-1 (pass-4 rig receipt): the app requests ONE tab per call
                    // (sourceId = "unstaged" | "staged" — GitPort.kt's verified set) and
                    // decodes each row's `section` to place it; serving every row for
                    // both calls duplicated each change into BOTH tabs. The unstaged tab
                    // renders section ∈ {unstaged, untracked, conflicted} (GitScreen.kt's
                    // section orders), so the "unstaged" request serves all three.
                    // Rows without a `section` keep the legacy serve-everything behavior.
                    // p40: the source is the DERIVED view (write-tier mutation applied —
                    // staged moves, discards, commits), not the static fixture.
                    val requested = argAsObject(arg)?.strOrNull("sourceId")
                    val derived = servedGitChanges()
                    val served = if (requested == null) derived else derived.filter {
                        val section = (it as? JsonObject)?.strOrNull("section") ?: return@filter true
                        if (requested == "unstaged") section in listOf("unstaged", "untracked", "conflicted")
                        else section == requested
                    }
                    respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Arr(served.map { RpcValue.Obj(it) }))
                }
            }
            "getDiff" -> {
                val diff = script.gitDiff
                if (diff == null) respondError(id, "fault.git.notAvailable")
                else respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(diff))
            }
            "getCommitGraph" -> {
                script.gitRefusals["getCommitGraph"]?.let { return respondError(id, it) }
                val commits = script.gitCommits
                if (commits == null) respondError(id, "fault.git.notAvailable")
                else respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                    put("commits", commits)
                    put("hasMore", JsonPrimitive(script.gitHasMore))
                }))
            }
            // G53 (vc52 Phase 11): the depth trio. A N-BG-2 refusal arm answers FIRST
            // (the deliberate fault beats the fixture), then the scripted fixture, then
            // the unscripted typed fault.
            "getBranchComparison", "getIgnoredPaths", "getWorkspaceRepositoryInfo" -> {
                script.gitRefusals[name ?: ""]?.let { return respondError(id, it) }
                when (name) {
                    "getBranchComparison" -> {
                        val comparison = script.gitBranchComparison
                        if (comparison == null) respondError(id, "fault.git.notAvailable")
                        else respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(comparison))
                    }
                    "getIgnoredPaths" -> {
                        val ignored = script.gitIgnoredPaths
                        if (ignored == null) respondError(id, "fault.git.notAvailable")
                        else respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Arr(ignored.map { RpcValue.Str((it as JsonPrimitive).content) }))
                    }
                    else -> {
                        val info = script.gitWorkspaceRepositoryInfo
                        if (info == null) respondError(id, "fault.git.notAvailable")
                        else respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(info))
                    }
                }
            }
            else -> respondError(id, "fault.git.unknownMethod")
        }
    }

    private fun handleFilePromise(id: Int, name: String?, params: JsonObject?) {
        when (name) {
            "readdir" -> {
                val path = params?.str("path").orEmpty()
                // Pass-21: the phone requests ABSOLUTE workspace paths (workspaceRoot =
                // /synthetic/ws-one); the seeded tree is workspace-relative — resolve by
                // stripping the workspace prefix before the key lookup.
                val rel = fileRelPath(path)
                val entries = script.fileTree[path] ?: rel?.takeIf { script.fileTree.containsKey(it) }?.let { script.fileTree[it] }
                if (entries == null) respondError(id, "fault.file.notFound")
                else respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Arr(entries.map { RpcValue.Obj(it) }))
            }
            "stat" -> {
                val path = params?.str("path").orEmpty()
                val rel = fileRelPath(path)
                respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                    put("path", JsonPrimitive(path))
                    put("type", JsonPrimitive(if (script.fileTree.containsKey(path) || rel?.let { script.fileTree.containsKey(it) } == true) "directory" else "file"))
                    put("size", JsonPrimitive(script.fileBytes[path]?.size?.toLong() ?: script.fileText[path]?.toByteArray()?.size?.toLong()
                        ?: rel?.let { script.fileText[it]?.toByteArray()?.size?.toLong() } ?: 0L))
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
                val rel = fileRelPath(path)
                val content = script.fileText[path] ?: rel?.let { script.fileText[it] }
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
                val rel = fileRelPath(path)
                val bytes = script.fileBytes[path] ?: rel?.let { script.fileBytes[it] }
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
    // ---- G42-P23 (vc52 Phase 12): the workflow management verbs ----
    // PURE computes (fixture mutation + the {ok} response or the typed fault) — the
    // dispatch responds, the self-test seam calls them directly.

    /** Delete: removes the row from the scripted fixture. */
    internal fun computeWorkflowDelete(params: JsonObject?): Pair<JsonObject?, String?> {
        val name = params?.str("name")
        val list = script.savedWorkflows
        if (name == null || list == null) return null to "fault.mock.unscripted:deleteSavedWorkflow"
        val workflows = (list["workflows"] as? JsonArray)?.filterIsInstance<JsonObject>() ?: emptyList()
        val remaining = workflows.filter { (it["name"] as? JsonPrimitive)?.content != name }
        script.savedWorkflows = JsonObject(list.toMap() + ("workflows" to JsonArray(remaining)))
        return buildJsonObject { put("ok", JsonPrimitive(true)) } to null
    }

    /** UpdateMeta: rewrites meta.description in the fixture. */
    internal fun computeWorkflowUpdateMeta(params: JsonObject?): Pair<JsonObject?, String?> {
        val name = params?.str("name")
        val meta = params?.get("meta") as? JsonObject
        val list = script.savedWorkflows
        if (name == null || meta == null || list == null) return null to "fault.mock.unscripted:updateSavedWorkflowMeta"
        val workflows = (list["workflows"] as? JsonArray)?.filterIsInstance<JsonObject>()
        val updated = workflows?.map { wf ->
            if ((wf["name"] as? JsonPrimitive)?.content == name) {
                JsonObject(wf.toMap() + ("description" to (meta["description"] ?: JsonPrimitive(""))))
            } else wf
        }
        if (updated != null) {
            script.savedWorkflows = JsonObject(list.toMap() + ("workflows" to JsonArray(updated)))
        }
        return buildJsonObject { put("ok", JsonPrimitive(true)) } to null
    }

    /** Move: acks {ok:true} (the fixture is workspace-agnostic). */
    internal fun computeWorkflowMove(params: JsonObject?): Pair<JsonObject?, String?> {
        if (params?.str("name") == null || script.savedWorkflows == null) return null to "fault.mock.unscripted:moveSavedWorkflow"
        return buildJsonObject { put("ok", JsonPrimitive(true)) } to null
    }

    private fun respondWorkflowManagement(id: Int, result: Pair<JsonObject?, String?>) {
        val (obj, error) = result
        if (error != null) respondError(id, error) else respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(obj!!))
    }

    /**
     * Mock-fidelity debt #1 (pass 13): the typed unknown-interactionId gate for
     * resolveInteraction. Returns the rejection reasonCode when [iid] names NO
     * pending slot, null when the answer may proceed (the pre-pass-13 shape).
     * Known slots: the pending permission and the pending user input — a hook
     * review resolves through respondWorkspaceHookReview, never this verb, so its
     * id stays unknown here on purpose.
     */
    internal fun resolveInteractionRejection(iid: String?): String? {
        if (!script.unknownInteractionIdsArm) return null
        if (iid == null) return "fault.interaction.params"
        val known = script.pendingPermission?.interactionId == iid ||
            script.pendingUserInput?.interactionId == iid
        return if (known) null else "fault.interaction.unknownId"
    }

    /**
     * Pass-11 (MOCK_REFUSE_COMMANDS), seam-extracted pass-44 (p44-mock-refuse-list):
     * the typed fault the named command types must answer BEFORE any effect/ack —
     * null when the type is not refuse-armed and the dispatch proceeds normally.
     */
    internal fun refuseReasonFor(type: String): String? {
        val refuseTypes = script.refuseCommandTypes ?: return null
        return if (type in refuseTypes) "fault.command.rigRefused:$type" else null
    }

    /**
     * Pass-19 rig arm (debt #20, MOCK_QUEUE_GHOST_APPEND_MS): the desktop's held-queue
     * CAS — host applyHeldQueueDisposition (zcode.cjs): when inputRouting.mode=="choice"
     * and the payload carries heldQueueDisposition, expectedHeldQueueItemIds (when
     * non-empty) must be set-equal to the live queue (no duplicates, same length, every
     * id present) or the send fails. Returns the desktop-faithful failure ack —
     * {status:"failed", reasonCode:"guard.heldQueueConfirmationStale", message:"paused
     * queue changed after send confirmation opened"} — when the CAS mismatches, null
     * otherwise (proceed). Mutates nothing on the mismatch path.
     */
    internal fun heldQueueCasRejection(env: JsonObject?): JsonObject? {
        if (!script.heldQueueCasArm) return null
        if (script.inputRoutingMode != "choice") return null
        val payload = env?.get("payload") as? JsonObject
        val disposition = payload?.strOrNull("heldQueueDisposition") ?: return null
        val expected = (payload?.get("expectedHeldQueueItemIds") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
        val live = script.queueItems.orEmpty()
        val casOk = expected.isEmpty() || run {
            val set = expected.toSet()
            set.size == expected.size && live.size == set.size && expected.all { id -> live.any { it.queueItemId == id } }
        }
        if (casOk) {
            println("MOCK: sendText heldQueue CAS ok (disposition=$disposition expected=$expected live=${live.map { it.queueItemId }})")
            return null
        }
        println("MOCK: sendText heldQueue CAS MISMATCH (disposition=$disposition expected=$expected live=${live.map { it.queueItemId }}) -> guard.heldQueueConfirmationStale (no mutation)")
        return buildJsonObject {
            put("commandId", JsonPrimitive(env?.strOrNull("commandId") ?: "unknown"))
            put("status", JsonPrimitive("failed"))
            put("reasonCode", JsonPrimitive("guard.heldQueueConfirmationStale"))
            put("message", JsonPrimitive("paused queue changed after send confirmation opened"))
            put("revisionAtDecision", JsonPrimitive(stateRevision.get()))
        }
    }

    /**
     * Pass-20 (F-P20-A leg): the GOAL half of the host's held-queue admission. The
     * host's sendGoalCommand handler (zcode.cjs SMc -> n9e) passes the payload's
     * heldQueueDisposition/expectedHeldQueueItemIds through the SAME
     * applyHeldQueueDisposition gate as sendText — under inputRouting.mode=="choice"
     * a disposition-less goal send throws V4HeldQueueDispositionRequiredError, which
     * the dispatcher maps to {status:"failed",
     * reasonCode:"heldQueueDispositionRequired", message:"held queue requires
     * heldQueueDisposition (clearQueueAndSend | keepQueueAndSend)"}. The desktop UI
     * pre-gates (its confirmation dialog supplies the trio), but the phone's goal
     * surfaces send no trio — so on the real desktop every phone goal send under a
     * paused queue + choice admission is refused. Returns the desktop-faithful
     * failure ack, null otherwise (proceed). Mutates nothing. Gated on the same
     * heldQueueCasArm envelope as the sendText CAS (old rigs' shapes unchanged).
     */
    internal fun heldQueueGoalAdmissionRejection(env: JsonObject?): JsonObject? {
        if (!script.heldQueueCasArm) return null
        if (script.inputRoutingMode != "choice") return null
        val payload = env?.get("payload") as? JsonObject
        if (payload?.strOrNull("heldQueueDisposition") != null) return null
        println("MOCK: sendGoalCommand heldQueue admission REFUSED (no heldQueueDisposition under choice) -> heldQueueDispositionRequired (no mutation)")
        return buildJsonObject {
            put("commandId", JsonPrimitive(env?.strOrNull("commandId") ?: "unknown"))
            put("status", JsonPrimitive("failed"))
            put("reasonCode", JsonPrimitive("heldQueueDispositionRequired"))
            put("message", JsonPrimitive("held queue requires heldQueueDisposition (clearQueueAndSend | keepQueueAndSend)"))
            put("revisionAtDecision", JsonPrimitive(stateRevision.get()))
        }
    }

    /**
     * Pass-20 stale arm (MOCK_CAS_STALE_TYPES): the listed command types answer the
     * host's revision-mismatch ack — {status:"stale", reasonCode:"proto.staleRevision"}
     * (zcode.cjs dispatcher: a baseRevision-bearing command whose baseRevision misses
     * the live revision). The phone's typed SESSION_CHANGED faces become drivable
     * (the desktop recovers silently via recoverFromStaleAuthority; the phone warns
     * honestly). Mutates nothing; sticky while armed.
     */
    internal fun casStaleAck(type: String, env: JsonObject?): JsonObject? {
        if (type == "compact") return null // Y.B.3: the compact command is NOT CAS-guarded — never interceptable
        val types = script.casStaleTypes ?: return null
        if (type !in types) return null
        println("MOCK: $type forced stale (MOCK_CAS_STALE_TYPES) -> proto.staleRevision")
        return buildJsonObject {
            put("commandId", JsonPrimitive(env?.strOrNull("commandId") ?: "unknown"))
            put("status", JsonPrimitive("stale"))
            put("reasonCode", JsonPrimitive("proto.staleRevision"))
            put("revisionAtDecision", JsonPrimitive(stateRevision.get()))
        }
    }

    /**
     * Pass-37 rig arm (MOCK_FEEDBACK_FAIL=1, Part AN): setAssistantFeedback answers the
     * host's failed ack {status:"failed", reasonCode:"fault.feedback.fail", …} (the
     * F-P19-A failed-ack shape) — the phone's keyed FEEDBACK_REFUSED notice ("The
     * desktop refused the feedback — nothing changed.") becomes drivable. `null` when
     * unarmed; mutates nothing. Only consulted for the feedback command (the caller
     * dispatches by type, mirroring casStaleAck's arm).
     */
    internal fun feedbackFailAck(commandId: String?): JsonObject? {
        if (!script.feedbackFail) return null
        return buildJsonObject {
            put("commandId", JsonPrimitive(commandId ?: "unknown"))
            put("status", JsonPrimitive("failed"))
            put("reasonCode", JsonPrimitive("fault.feedback.fail"))
            put("message", JsonPrimitive("feedback rejected by host policy"))
            put("revisionAtDecision", JsonPrimitive(stateRevision.get()))
        }
    }

    /**
     * Pass-30 rig arm (MOCK_ATTACHMENT_FAIL): the fault the attachment transaction's
     * CURRENT phase should answer — `null` when healthy or the phase isn't one of
     * begin/chunk/commit (unknown values arm nothing, fail-closed per H.A0.2).
     * The handler answers this as an RPC ERROR while CONNECTED, so the app's
     * transport throws TransportFailure → the port's GENERIC Failed path (chip
     * failed, payload RETAINED, Retry live).
     */
    internal fun attachmentFailFault(): String? = when (script.attachmentFailPhase) {
        "begin" -> "fault.attachment.beginFail"
        "chunk" -> "fault.attachment.chunkFail"
        "commit" -> "fault.attachment.commitFail"
        else -> null
    }

    /** Pass-30: the BEGIN guard's fault — the verified rejectAttachments fault
     *  outranks the pass-30 arm (the pass-4 shape stands). */
    internal fun attachmentBeginGuardFault(): String? =
        if (script.rejectAttachments) "fault.attachment.rejected" else null

    /**
     * Pass-32 rig arm (MOCK_ATTACHMENT_KEEP_AFTER_ABORT): the store mutation the
     * abort handler performs — `true` when the staging entry was removed (the
     * server-faithful default), `false` when kept (the arm) or was already absent.
     * A kept entry makes every post-abort chunkV4 find its `att` and commit
     * succeed, which is the F-P31-A READY-resurrection driver.
     */
    internal fun attachmentAbortMutate(uploadId: String?): Boolean = synchronized(attachmentStore) {
        if (script.attachmentKeepAfterAbort) false
        else attachmentStore.remove(uploadId) != null
    }

    /** Test seams for the pass-32 arm: seed/inspect the live staging store. */
    internal fun attachmentSeedForTest(uploadId: String) {
        synchronized(attachmentStore) {
            attachmentStore[uploadId] = MockAttachment(
                sessionId = "sess-p32", fileName = "rig-p32.bin", mime = "application/octet-stream", totalBytes = 1L,
            )
        }
    }


    /**
     * Pass-22 rig arm (MOCK_SET_THOUGHT_LEVEL_STALE): the host's FQ serialization —
     * the protocol method setThoughtLevel answers a JSON-RPC ERROR on a
     * revision mismatch (Dl -32009, "Session state revision mismatch",
     * {actualRevision, expectedRevision}), never a {status:"stale"} ack, and
     * mutates nothing. The phone's ChannelSession.failWith keeps only `message`,
     * so the port collapses the throw to FAILED.
     */
    internal fun setThoughtLevelStaleErrorBody(expectedRevision: String?): JsonObject = buildJsonObject {
        put("message", JsonPrimitive("Session state revision mismatch"))
        put("name", JsonPrimitive("Error"))
        put("code", JsonPrimitive(-32009))
        put("data", buildJsonObject {
            put("actualRevision", JsonPrimitive(stateRevision.get()))
            expectedRevision?.let { put("expectedRevision", JsonPrimitive(it.toLongOrNull() ?: 0L)) }
        })
    }

    /** Pass-22 compact arm decision (host vMc mirror). */
    internal sealed interface CompactDecision {
        /** The typed rejected ack body fields (compactOperationLock — host ID throw). */
        data class Rejected(val reasonCode: String, val message: String) : CompactDecision
        /** The compact self-queued (kind:"compact" item + queue patch + revision bump). */
        data class Queued(val queueItemId: String, val routing: String) : CompactDecision
        /** Not deferred (startNow/null routing) — the mock has no compact turn; fall through. */
        data object Passthrough : CompactDecision
    }

    /**
     * Pass-22 rig arm (MOCK_COMPACT_COMMAND): the host's command-plane compact
     * handler (zcode.cjs vMc) — a compact while one is queued/running answers the
     * typed rejected ack compactOperationLock; under enqueue/guide/choice routing
     * the compact SELF-QUEUES (steerTurn delivery:"queue" ⇒ a kind:"compact" queue
     * item + queue patch + revision bump) and answers accepted with NO rows.
     * The compact command is NOT CAS-guarded — the stale arm must never see it.
     */
    internal fun compactCommandDecision(): CompactDecision {
        if (script.queueItems.orEmpty().any { it.kind == "compact" }) {
            return CompactDecision.Rejected(
                reasonCode = "compactOperationLock",
                message = "Compact is already running or queued",
            )
        }
        val routing = script.inputRoutingMode
        if (routing == "enqueue" || routing == "guide" || routing == "choice") {
            val qid = "q-rig-compact-${queuedSendSeq.incrementAndGet()}"
            stateRevision.incrementAndGet()
            script.queueItems = script.queueItems.orEmpty() +
                QueueItem(qid, "/compact", kind = "compact")
            pushQueuePatch()
            return CompactDecision.Queued(qid, routing)
        }
        return CompactDecision.Passthrough
    }

    /**
     * Pass-23 rig arm (Part-Y leftover, the MOCK_COMPACT_COMMAND passthrough half):
     * the host's vMc under startNow/null routing RUNS the compaction turn — the
     * desktop conversation renders the manual projectMarker pair. The mock scripts
     * the same pair live: `running:manual` immediately, `completed:manual` (token
     * counts) after [Script.compactRunMarkerDelayMs]; both join the authoritative
     * appendedRows tail (the debt-#17 contract) so a resync snapshot keeps them.
     * The generic accepted ack still answers (the caller falls through).
     */
    internal fun pushCompactRunMarkers() {
        val sessionId = routingSessionId()
        val running = schemaRow(sessionId, nextRowId(), RowKind.TIMELINE_MARKER, "running:manual")
        synchronized(appendedRows) { appendedRows[running["rowId"].toString()] = running }
        pushRowAppended(sessionId, running)
        println("MOCK: compact RAN NOW (MOCK_COMPACT_COMMAND passthrough) -> running:manual marker")
        scope.launch {
            kotlinx.coroutines.delay(script.compactRunMarkerDelayMs)
            val completed = schemaRow(sessionId, nextRowId(), RowKind.TIMELINE_MARKER, "completed:manual", extra = buildJsonObject {
                put("marker", buildJsonObject {
                    put("type", JsonPrimitive("compact"))
                    put("origin", JsonPrimitive("manual"))
                    put("status", JsonPrimitive("completed"))
                    put("tokensBefore", JsonPrimitive(164_000))
                    put("tokensAfter", JsonPrimitive(38_500))
                })
            })
            synchronized(appendedRows) { appendedRows[completed["rowId"].toString()] = completed }
            pushRowAppended(sessionId, completed)
            println("MOCK: compact completed:manual marker pushed (MOCK_COMPACT_COMMAND passthrough)")
        }
    }

    /** One-shot: schedule the silent ghost append — the queue moves server-side
     *  WITHOUT a queue patch, so the phone keeps answering with its stale ids. */
    private fun armHeldQueueGhostOnce() {
        if (!script.heldQueueCasArm || script.heldQueueGhostFired) return
        script.heldQueueGhostFired = true
        scope.launch {
            kotlinx.coroutines.delay(script.heldQueueGhostDelayMs)
            appendHeldQueueGhostForTest()
        }
    }

    /** Self-test seam: the ghost append body (silent — no patch, no revision bump). */
    internal fun appendHeldQueueGhostForTest() {
        script.queueItems = script.queueItems.orEmpty() +
            QueueItem("q-rig-ghost-1", "ghost follow-up (silent append)")
        println("MOCK: heldQueue ghost appended SILENTLY (no patch) -> live=${script.queueItems.orEmpty().map { it.queueItemId }}")
    }

    private fun applyCommandEffect(type: String, env: JsonObject?): JsonObject? {
        val payload = env?.get("payload") as? JsonObject
        when (type) {
            "createSession" -> {
                val payload = env?.get("payload") as? JsonObject
                val text = (payload?.get("firstInput") as? JsonObject)?.strOrNull("text") ?: ""
                val newId = "sess-created-${createdCount.incrementAndGet()}"
                val wsPath = lastCommandWorkspacePath ?: workspace.workspacePath
                // Pass-39 (p39-mock-created-label): the created task inherits the source
                // workspace's LABEL from a sibling row — the hard-coded default
                // ("Synthetic Workspace") diverged from /synthetic/ws-one's "Synthetic
                // One" and the phone's home rendered an incoherent section header (the
                // group label from one member, row subtitles from the others).
                val siblingLabel = script.bootstrapTasks.orEmpty()
                    .firstOrNull { it.workspacePath == wsPath && it.workspaceLabel != "Synthetic Workspace" }
                    ?.workspaceLabel
                script.bootstrapTasks = script.bootstrapTasks.orEmpty() + MockZCode.MockTask(
                    taskId = newId,
                    title = text.take(60),
                    displayStatus = "running",
                    workspacePath = wsPath,
                    workspaceLabel = siblingLabel ?: "Synthetic Workspace",
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
                // Pass-17 rig receipt line (G49/G50): the send trio keys the phone may
                // put on the sendText payload — logged verbatim so rig legs can pin
                // the wire shape (the app's own log carries only the method + latency).
                val heldDisposition = payload?.strOrNull("heldQueueDisposition")
                val requestedDelivery = payload?.strOrNull("requestedDelivery")
                val expectedIds = (payload?.get("expectedHeldQueueItemIds") as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
                println("MOCK: sendText trio heldQueueDisposition=$heldDisposition " +
                    "requestedDelivery=$requestedDelivery expectedHeldQueueItemIds=$expectedIds")
                if (heldDisposition == "clearQueueAndSend") {
                    // Desktop-faithful: the CLEAR disposition executes server-side — the
                    // held queue is emptied and the strip reflects it on the next patch.
                    script.queueItems = emptyList()
                }
                if (requestedDelivery == "queue") {
                    // Debt #19 (pass 18): desktop-faithful QUEUED delivery. The desktop
                    // server answers sendText{requestedDelivery:"queue"} with
                    // inputAccepted{delivery:"queue"} and the input HOLDS as a queue
                    // item (the bot path marks the task "waiting" with a Queued
                    // notification); the phone's strip renders it from the queue patch.
                    // The mock used to append user/assistant rows immediately, which
                    // un-queued the send and hid the G49 override's whole point.
                    val qid = "q-rig-${queuedSendSeq.incrementAndGet()}"
                    script.queueItems = script.queueItems.orEmpty() + QueueItem(qid, text)
                    println("MOCK: sendText queued delivery -> $qid appended (heldDisposition=$heldDisposition)")
                    pushQueuePatch()
                    pushStatePatch(buildJsonObject {
                        put("revision", JsonPrimitive(stateRevision.get()))
                        put("inputRouting", buildJsonObject { put("mode", JsonPrimitive(script.inputRoutingMode)) })
                    })
                    applyInputRoutingFlip("enqueue")
                } else {
                    val sessionId = routingSessionId()
                    val userRow = schemaRow(sessionId, nextRowId(), RowKind.USER_INPUT, text)
                    val replyBase = schemaRow(sessionId, nextRowId(), RowKind.ASSISTANT_TEXT, "synthetic reply to: ${text.take(24)}")
                    // Phase 4A: the live reply is the latest assistant row → it carries the flags.
                    val replyRow = if (script.rowActionsDemo) JsonObject(replyBase + ("actions" to ROW_ACTIONS_ASSISTANT)) else replyBase
                    appendedRows[userRow["rowId"].toString()] = userRow
                    appendedRows[replyRow["rowId"].toString()] = replyRow
                    pushRowAppended(sessionId, userRow)
                    pushRowAppended(sessionId, replyRow)
                    if (heldDisposition == "clearQueueAndSend") pushQueuePatch()
                    pushStatePatch(buildJsonObject {
                        put("revision", JsonPrimitive(stateRevision.get()))
                        put("control", buildJsonObject {
                            put("phase", JsonPrimitive("running"))
                            put("sessionEnded", JsonPrimitive(false))
                            put("canStop", JsonPrimitive(true))
                            put("stopState", JsonPrimitive("stoppable"))
                            put("activeWorks", JsonArray(emptyList()))
                        })
                        put("inputRouting", buildJsonObject { put("mode", JsonPrimitive(script.inputRoutingMode)) })
                    })
                    script.controlCanStop = true
                    script.controlStopState = "stoppable"
                    applyInputRoutingFlip("enqueue")
                }
            }
            "respondWorkspaceHookReview" -> {
                stateRevision.incrementAndGet()
                val decision = payload?.get("decision") as? JsonObject
                val ids = (decision?.get("reviewItemIds") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
                ids.forEach { id2 -> script.hookTrustStates[id2] = "trusted_persistent" }
                script.hookReviewsResolved = true
                // pass-25 rig-fidelity fix: the patch must carry the WHOLE pending list
                // (key-level replacement — the resolveInteraction contract). The bare
                // {revision} patch left the hook card pending forever on the rig: the
                // app correctly keeps its last-known state when a patch omits the key.
                pushStatePatch(buildJsonObject {
                    put("revision", JsonPrimitive(stateRevision.get()))
                    put("pendingInteractions", pendingInteractionList())
                })
            }
            "toggleWorkspaceHookReviewItem" -> {
                stateRevision.incrementAndGet()
                val itemId = payload?.str("reviewItemId") ?: return null
                script.hookTrustStates[itemId] = if (payload.str("enabled") == "true") "trusted_persistent" else "revoked"
                pushStatePatch(buildJsonObject { put("revision", JsonPrimitive(stateRevision.get())) })
            }
            "revokeWorkspaceHookTrust" -> {
                stateRevision.incrementAndGet()
                val ids = (payload?.get("reviewItemIds") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
                ids.forEach { id2 -> script.hookTrustStates[id2] = "revoked" }
                pushStatePatch(buildJsonObject { put("revision", JsonPrimitive(stateRevision.get())) })
            }
            "requestWorkspaceHookReview" -> {
                stateRevision.incrementAndGet()
                // G71 (vc52 Phase 12): the desktop RE-ISSUES the review — the resolved
                // flag flips back so scriptedHookInteraction() re-posts the review card,
                // and the patch carries the WHOLE pending list (the resolveInteraction
                // key-level-replacement contract; the bare revision patch left the
                // refresh a silent no-op on the rig).
                script.hookReviewsResolved = false
                pushStatePatch(buildJsonObject {
                    put("revision", JsonPrimitive(stateRevision.get()))
                    put("pendingInteractions", pendingInteractionList())
                })
            }
            "resolveInteraction" -> {
                val iid = payload?.strOrNull("interactionId") ?: return null
                stateRevision.incrementAndGet()
                val hadPermission = script.pendingPermission?.interactionId == iid
                val hadUserInput = script.pendingUserInput?.interactionId == iid
                // Clear ONLY the slot the answered id names (pass-14 rig find): the old
                // unconditional null of BOTH slots silently resolved the sibling
                // interaction beside the answered one (a permission answer killed the
                // AskUserQuestion, and vice versa) with no wire command for it.
                if (hadPermission) script.pendingPermission = null
                if (hadUserInput) script.pendingUserInput = null
                pushStatePatch(buildJsonObject {
                    put("revision", JsonPrimitive(stateRevision.get()))
                    // The patch is the WHOLE pending list (key-level replacement): carry
                    // every SURVIVING slot plus the scripted hook review — an empty list
                    // would resolve them too (the rig FIX documented on
                    // scriptedHookInteraction).
                    put("pendingInteractions", pendingInteractionList())
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
                    val flipped = schemaRow(routingSessionId(), nextRowId(), RowKind.TOOL_CALL, "success")
                    appendedRows[flipped["rowId"].toString()] = flipped
                    pushRowAppended(routingSessionId(), flipped)
                }
                if (hadUserInput) {
                    val answered = schemaRow(routingSessionId(), nextRowId(), RowKind.USER_INPUT, "(answered)")
                    appendedRows[answered["rowId"].toString()] = answered
                    pushRowAppended(routingSessionId(), answered)
                }
            }
            "snoozeInteractionAutoResolution" -> {
                // RIG-P1 (G77): the phone's "Keep waiting" — the desktop accepts the
                // snooze (accepted/duplicate/noop all count as success) and the live
                // fuse flips to the snoozed state; the patch carries the WHOLE list
                // (key-level replacement) so the card re-renders without a countdown.
                stateRevision.incrementAndGet()
                script.autoResolutionSnoozed = true
                pushStatePatch(buildJsonObject {
                    put("revision", JsonPrimitive(stateRevision.get()))
                    put("pendingInteractions", pendingInteractionList())
                })
            }
            "stop" -> {
                stateRevision.incrementAndGet()
                script.controlCanStop = false
                script.controlStopState = "idle"
                applyInputRoutingFlip("startNow")
                pushStatePatch(buildJsonObject {
                    put("revision", JsonPrimitive(stateRevision.get()))
                    put("control", buildJsonObject {
                        put("phase", JsonPrimitive("idle"))
                        put("sessionEnded", JsonPrimitive(true))
                        put("canStop", JsonPrimitive(false))
                        put("stopState", JsonPrimitive("idle"))
                        put("activeWorks", JsonArray(emptyList()))
                    })
                    put("inputRouting", buildJsonObject { put("mode", JsonPrimitive(script.inputRoutingMode)) })
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
            // ---- Phase 5A (I1): the two decoded-unsent queue words + follow-up mode — REAL
            // state so the rig proves the reorder round-trip (the moved row renders in place).
            "reorderQueueItem" -> {
                stateRevision.incrementAndGet()
                val qid = payload?.strOrNull("queueItemId")
                val before = if (payload?.get("beforeQueueItemId") is kotlinx.serialization.json.JsonNull) null else payload?.strOrNull("beforeQueueItemId")
                val items = script.queueItems.orEmpty().toMutableList()
                val moved = items.firstOrNull { it.queueItemId == qid }
                if (moved != null) {
                    items.remove(moved)
                    val index = if (before == null) items.size else items.indexOfFirst { it.queueItemId == before }.coerceAtLeast(0)
                    items.add(index, moved)
                    script.queueItems = items
                }
                pushQueuePatch()
            }
            "setAutoDrain" -> {
                stateRevision.incrementAndGet()
                script.autoDrain = payload?.strOrNull("autoDrain")?.toBooleanStrictOrNull() ?: script.autoDrain
                pushQueuePatch()
            }
            // ---- G4 (vc51): deleteQueueItem — the fifth queue verb's real patch round-trip.
            "deleteQueueItem" -> {
                stateRevision.incrementAndGet()
                val qid = payload?.strOrNull("queueItemId")
                script.queueItems = script.queueItems.orEmpty().filter { it.queueItemId != qid }
                pushQueuePatch()
                null
            }
            "setFollowupMode" -> {
                stateRevision.incrementAndGet()
                val mode = payload?.strOrNull("mode") ?: return null
                val current = script.sessionConfig
                script.sessionConfig = mergeSessionConfig(
                    current,
                    "provider" to JsonPrimitive(current?.strOrNull("provider") ?: "zai"),
                    "model" to JsonPrimitive(current?.strOrNull("model") ?: "glm-5.3"),
                    "thought" to JsonPrimitive(current?.strOrNull("thought") ?: "standard"),
                    "thoughtLevels" to (current?.get("thoughtLevels") ?: JsonArray(listOf(JsonPrimitive("standard")))),
                    "followupMode" to JsonPrimitive(mode),
                    "mode" to JsonPrimitive(current?.strOrNull("mode") ?: "build"),
                )
                pushStatePatch(buildJsonObject {
                    put("revision", JsonPrimitive(stateRevision.get()))
                    put("config", script.sessionConfig!!)
                })
            }
            // ---- Phase 4A (E3.2+I1+N5): the four row commands — effects shaped on
            // the verified ack results (command.ts commandResultSchema) + row deltas.
            "retryTurn" -> {
                stateRevision.incrementAndGet()
                // The desktop re-asks the turn: a fresh assistant row arrives (authoritative).
                val reply = JsonObject(
                    schemaRow(routingSessionId(), nextRowId(), RowKind.ASSISTANT_TEXT, "retried reply") +
                        ("actions" to ROW_ACTIONS_ASSISTANT),
                )
                appendedRows[reply["rowId"].toString()] = reply
                pushRowAppended(routingSessionId(), reply)
                pushRevisionPatch()
            }
            "forkAssistant" -> {
                stateRevision.incrementAndGet()
                val child = "sess-forked-${createdCount.incrementAndGet()}"
                script.bootstrapTasks = script.bootstrapTasks.orEmpty() + MockTask(
                    taskId = child,
                    title = "Fork of ${routingSessionId().take(12)}",
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
            "createSelectionSideSession" -> {
                stateRevision.incrementAndGet()
                val firstText = (payload?.get("firstInput") as? JsonObject)
                    ?.strOrNull("text")
                lastSideSessionFirstText = firstText
                val child = "sess-side-${createdCount.incrementAndGet()}"
                script.bootstrapTasks = script.bootstrapTasks.orEmpty() + MockTask(
                    taskId = child,
                    title = firstText?.take(40) ?: "Side session",
                    displayStatus = "idle",
                    workspacePath = lastCommandWorkspacePath ?: workspace.workspacePath,
                    createdAtMs = System.currentTimeMillis(),
                    updatedAtMs = System.currentTimeMillis(),
                )
                pushWorkspaceListUpdated(script.bootstrapTasks.orEmpty())
                lastSideSessionId = child
                return buildJsonObject {
                    put("type", JsonPrimitive("createSelectionSideSession"))
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
                    val reverted = schemaRow(routingSessionId(), rowId, RowKind.TURN_HEADER, "completedSuccess", extra = rowActionsTurnExtra("reverted"))
                    appendedRows[rowId.toString()] = reverted
                    pushRowUpserted(routingSessionId(), reverted)
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
                    // Pass-37: prefer the row's REAL recorded shape (appendedRows first,
                    // then the generatedRows record) so the echoed upsert preserves the
                    // row the phone already holds; the synthesized base is now the LAST
                    // resort for client-invented ids only.
                    val base = appendedRows[rowId.toString()]
                        ?: generatedRows[rowId.toString()]
                        ?: JsonObject(schemaRow(routingSessionId(), rowId, RowKind.ASSISTANT_TEXT, "synthetic") + ("actions" to ROW_ACTIONS_ASSISTANT))
                    // JsonNull IS a JsonPrimitive — an explicit null clears, a string sets.
                    val set = feedback is JsonPrimitive && feedback !is JsonNull
                    val next = if (set) JsonObject(base + ("feedback" to feedback!!)) else JsonObject(base - "feedback")
                    appendedRows[rowId.toString()] = next
                    lastFeedback = if (set) (feedback as JsonPrimitive).content else null
                    pushRowUpserted(routingSessionId(), next)
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
                script.sessionConfig = mergeSessionConfig(
                    current,
                    "provider" to JsonPrimitive(provider),
                    "model" to JsonPrimitive(model),
                    "thought" to JsonPrimitive(thought),
                    "thoughtLevels" to (current?.get("thoughtLevels") ?: JsonArray(listOf(JsonPrimitive(thought)))),
                    "followupMode" to JsonPrimitive(current?.strOrNull("followupMode") ?: "queue"),
                    "mode" to JsonPrimitive(current?.strOrNull("mode") ?: "build"),
                    // The §10.3 echo carries the CURRENT selection — refresh it to the pick.
                    "modelSelection" to buildJsonObject {
                        put("providerId", JsonPrimitive(provider))
                        put("modelId", JsonPrimitive(model))
                    },
                )
                pushStatePatch(buildJsonObject {
                    put("revision", JsonPrimitive(stateRevision.get()))
                    put("config", script.sessionConfig!!)
                })
            }
            "switchCollaborationMode" -> {
                stateRevision.incrementAndGet()
                val mode = payload?.strOrNull("mode") ?: return null
                val current = script.sessionConfig
                script.sessionConfig = mergeSessionConfig(
                    current,
                    "provider" to JsonPrimitive(current?.strOrNull("provider") ?: "zai"),
                    "model" to JsonPrimitive(current?.strOrNull("model") ?: "glm-5.3"),
                    "thought" to JsonPrimitive(current?.strOrNull("thought") ?: "standard"),
                    "thoughtLevels" to (current?.get("thoughtLevels") ?: JsonArray(listOf(JsonPrimitive("standard")))),
                    "followupMode" to JsonPrimitive(current?.strOrNull("followupMode") ?: "queue"),
                    "mode" to JsonPrimitive(mode),
                )
                pushStatePatch(buildJsonObject {
                    put("revision", JsonPrimitive(stateRevision.get()))
                    put("config", script.sessionConfig!!)
                })
            }
            // RIG-P2 (vc52 Phase 14, N-BD-2a): the Remove chip's word — the desktop
            // flips the import's status to `discarded` (shared-context-import.ts) and
            // patches it; the phone decodes the status and `sendable` goes false, so
            // the chip leaves the composer. Without this effect the mock acked and the
            // chip legitimately persisted until the next served frame (~8 dead wires
            // in the N-BD-2 pass).
            "discardSharedContext" -> {
                stateRevision.incrementAndGet()
                val contextId = payload?.strOrNull("contextId")
                val current = script.sharedContextImport
                if (current != null && (contextId.isNullOrBlank() || current.strOrNull("contextId") == contextId)) {
                    script.sharedContextImport = JsonObject(current + ("status" to JsonPrimitive("discarded")))
                    pushStatePatch(buildJsonObject {
                        put("revision", JsonPrimitive(stateRevision.get()))
                        put("sharedContextImport", script.sharedContextImport!!)
                    })
                }
            }        }
        return null
    }

    /**
     * G3 rig fidelity: a switch rebuilds the config keys it manages but must never
     * DROP the keys it does not — the shipped desktop's mode switch left
     * `modelSelection` (and any other §10.3 keys) intact, and the phone's chips
     * reconcile against the whole echo. Key-preserving merge over the current config.
     */
    private fun mergeSessionConfig(
        current: JsonObject?,
        vararg changes: Pair<String, kotlinx.serialization.json.JsonElement>,
    ): JsonObject = JsonObject((current ?: JsonObject(emptyMap())) + changes.toMap())

    /**
     * The effort switch's config rebuild (`setThoughtLevel` channel word) — the FOURTH
     * member of the fixed-key rebuild class G3 named; same key-preserving merge.
     */
    internal fun thoughtLevelConfig(level: String): JsonObject {
        val current = script.sessionConfig
        return mergeSessionConfig(
            current,
            "provider" to JsonPrimitive(current?.strOrNull("provider") ?: "zai"),
            "model" to JsonPrimitive(current?.strOrNull("model") ?: "glm-5.3"),
            "thought" to JsonPrimitive(level),
            "thoughtLevels" to (current?.get("thoughtLevels") ?: JsonArray(listOf(JsonPrimitive(level)))),
            "followupMode" to JsonPrimitive(current?.strOrNull("followupMode") ?: "queue"),
            "mode" to JsonPrimitive(current?.strOrNull("mode") ?: "build"),
        )
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

    /** G30 (vc51 Phase 4B): the read-only bots viewer's scripted list leg. */
    private fun handleBotsPromise(id: Int, name: String?) {
        when (name) {
            "list" -> {
                val result = script.botsList
                if (result == null) {
                    respondError(id, "fault.mock.unscripted:bots.list")
                } else {
                    respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(result))
                }
            }
            else -> respondError(id, "fault.mock.unknownMethod:bots-${name ?: "?"}")
        }
    }

    /** Phase 4A: a revision-only patch (the CAS currency moved; no other key changed). */
    private fun pushRevisionPatch() {
        pushStatePatch(buildJsonObject { put("revision", JsonPrimitive(stateRevision.get())) })
    }

    /** Phase 4A observability (self-tests + rig): the last fork's child id / last feedback echo. */
    @Volatile var lastForkedSessionId: String? = null
    @Volatile var lastFeedback: String? = null
    /** Pass-29 observability: the last side-session child id + the dispatched selection text. */
    @Volatile var lastSideSessionId: String? = null
    @Volatile var lastSideSessionFirstText: String? = null
    /** Phase 4C seam: the most recent zcode-agent call's params per method (wire-shape pins). */
    private val recentV4Calls = HashMap<String, JsonObject?>()
    /** Phase 4D seam: every `zcode-task` channel call (method, params) in send order. */
    private val taskChannelLedger = ArrayList<Pair<String, JsonObject>>()
    fun taskChannelCallsForTest(): List<Pair<String, JsonObject>> = synchronized(taskChannelLedger) { ArrayList(taskChannelLedger) }

    /** Phase 5: the ORDERED zcode-agent v4call ledger (method, params) — the rule-9 wire proof for every facade read. */
    private val v4CallLedger = ArrayList<Pair<String, JsonObject>>()
    fun v4CallsForTest(): List<Pair<String, JsonObject>> = synchronized(v4CallLedger) { ArrayList(v4CallLedger) }

    /** Self-test/rig seam: clear the ordered v4call ledger (per-leg receipts). */
    fun resetCallLedgerForTest() = synchronized(v4CallLedger) { v4CallLedger.clear() }

    private fun taskIdOf(task: JsonObject): String? = (task["taskId"] as? JsonPrimitive)?.content

    /** A directTasks row decorated with the mutable pinned/unread/archived flags (ZCodeTaskMeta-ish). */
    private fun decorateMeta(task: JsonObject): JsonObject {
        val tid = taskIdOf(task)
        return JsonObject(task +
            ("pinned" to JsonPrimitive(tid in script.pinnedTaskIds)) +
            ("archived" to JsonPrimitive(tid in script.archivedTaskIds)) +
            (script.unreadTaskIds[tid]?.let { "unreadAt" to JsonPrimitive(it) } ?: ("unreadAt" to kotlinx.serialization.json.JsonNull)))
    }

    /** `listGroupedTaskViewStructure` result: group nodes (with member metas) then loose tasks. */
    private fun groupedStructure(): JsonObject {
        val grouped = script.taskGroupMembers.values.flatten().toSet()
        val nodes = ArrayList<JsonObject>()
        synchronized(script.taskGroups) {
            script.taskGroups.forEach { g ->
                val gid = (g["id"] as? JsonPrimitive)?.content
                val members = script.taskGroupMembers[gid] ?: emptyList()
                nodes += buildJsonObject {
                    put("type", JsonPrimitive("group")); put("group", g)
                    put("tasks", JsonArray(script.directTasks.filter { taskIdOf(it) in members }.map(::decorateMeta)))
                }
            }
        }
        script.directTasks.filter { taskIdOf(it) !in grouped && taskIdOf(it) !in script.pinnedTaskIds && taskIdOf(it) !in script.archivedTaskIds }
            .forEach { nodes += buildJsonObject { put("type", JsonPrimitive("task")); put("task", decorateMeta(it)) } }
        return buildJsonObject { put("nodes", JsonArray(nodes)) }
    }
    fun lastV4CallParamsForTest(name: String): JsonObject? = synchronized(recentV4Calls) { recentV4Calls[name] }

    /** Test seam for the command-effect machinery (the socket path calls the same function). */
    internal fun applyCommandEffectForTest(type: String, env: JsonObject?): JsonObject? = applyCommandEffect(type, env)

    /** G3 (vc51): resume/amend verdicts — a typed fault or the accepted result body. */
    internal sealed class WorkflowRunCommandOutcome {
        data class Fault(val code: String) : WorkflowRunCommandOutcome()
        data class Accepted(val result: JsonObject) : WorkflowRunCommandOutcome()
    }

    /**
     * G3 (vc51): the resume/amend state machine over the mock's run store — mutations +
     * the state patch inside, the verdict returned (the WS handler only translates to
     * respondError / the accepted ack). Desktop vocabulary: not_found / not_resumable
     * (command.ts:282's rejection family); amend STOPS the current run (supersededBy) and
     * starts the settings round on a NEW run.
     */
    internal fun workflowRunCommandResult(type: String, workId: String?): WorkflowRunCommandOutcome {
        val run = script.workflowRuns.firstOrNull { it.strOrNull("runId") == workId }
            ?: return WorkflowRunCommandOutcome.Fault("fault.command.workflowRunRejected.not_found")
        val now = System.currentTimeMillis()
        if (type == "resumeWorkflowRun") {
            if (run.strOrNull("resumable") != "true") {
                return WorkflowRunCommandOutcome.Fault("fault.command.workflowRunResumeRejected.not_resumable")
            }
            script.workflowRuns[script.workflowRuns.indexOfFirst { it.strOrNull("runId") == workId }] =
                JsonObject(run + mapOf(
                    "status" to JsonPrimitive("running"),
                    "resumable" to JsonPrimitive(false),
                    "updatedAt" to JsonPrimitive(now),
                ))
            script.workflowRunEvents.getOrPut(workId!!) { mutableListOf() }.add(
                buildJsonObject {
                    put("sequence", JsonPrimitive((script.workflowRunEvents[workId]?.maxOfOrNull { it.strOrNull("sequence")?.toLongOrNull() ?: 0L } ?: 0L) + 1))
                    put("type", JsonPrimitive("run.resumed"))
                    put("payload", buildJsonObject { put("message", JsonPrimitive("resumed from the phone")) })
                },
            )
            stateRevision.incrementAndGet()
            pushStatePatch(buildJsonObject { put("revision", JsonPrimitive(stateRevision.get())) })
            return WorkflowRunCommandOutcome.Accepted(buildJsonObject { put("runId", JsonPrimitive(workId)) })
        }
        // amendWorkflowRunSettings
        val newRunId = "${workId}-amended"
        script.workflowRuns[script.workflowRuns.indexOfFirst { it.strOrNull("runId") == workId }] =
            JsonObject(run + mapOf(
                "status" to JsonPrimitive("stopped"),
                "resumable" to JsonPrimitive(false),
                "supersededBy" to JsonPrimitive(newRunId),
                "updatedAt" to JsonPrimitive(now),
            ))
        script.workflowRuns.add(buildJsonObject {
            put("runId", JsonPrimitive(newRunId)); put("label", JsonPrimitive(run.strOrNull("label") ?: "workflow"))
            put("status", JsonPrimitive("running")); put("updatedAt", JsonPrimitive(now)); put("resumable", JsonPrimitive(false))
        })
        script.workflowRunEvents[newRunId] = mutableListOf(
            buildJsonObject {
                put("sequence", JsonPrimitive(1)); put("type", JsonPrimitive("run.started"))
                put("payload", buildJsonObject { put("message", JsonPrimitive("settings amended; run restarted")) })
            },
        )
        stateRevision.incrementAndGet()
        pushStatePatch(buildJsonObject { put("revision", JsonPrimitive(stateRevision.get())) })
        return WorkflowRunCommandOutcome.Accepted(buildJsonObject { put("runId", JsonPrimitive(newRunId)); put("toolCallId", JsonPrimitive("settings-rig")) })
    }

    /** Test seam for the resync replay journal (G3 pins): the per-session DeltaFrames. */
    internal fun opLogForTest(sessionId: String): List<DeltaFrame> = opLog[sessionId]?.toList() ?: emptyList()

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

    /**
     * Debt #18 (pass 17, MOCK_INPUT_ROUTING): the send/stop handlers' routing flips
     * go through here so the arm's sticky mode wins when armed. Without the arm the
     * behavior is unchanged (enqueue on send, startNow on stop).
     */
    internal fun applyInputRoutingFlip(mode: String) {
        if (!script.inputRoutingArmSticky) script.inputRoutingMode = mode
    }

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
                put("autoDrain", JsonPrimitive(script.autoDrain))
            })
        })
    }

    private val createdCount = java.util.concurrent.atomic.AtomicInteger(0)
    /** Debt #19 (pass 18): ids for queue-delivered sends (offset past the q-rig seeds). */
    private val queuedSendSeq = java.util.concurrent.atomic.AtomicInteger(100)
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
        // Clear ONLY the slot this driver picked (same rule as the resolve command): the
        // old unconditional null of both slots + an EMPTY list silently resolved every
        // sibling interaction beside the raced one (pass-14 rig find, c2bcdc1b's class).
        val permId = script.pendingPermission?.interactionId
        if (permId != null) script.pendingPermission = null
        else script.pendingUserInput?.interactionId?.let { script.pendingUserInput = null }
        stateRevision.incrementAndGet()
        pushStatePatch(buildJsonObject {
            put("revision", JsonPrimitive(stateRevision.get()))
            put("pendingInteractions", pendingInteractionList())
        })
    }

    /** G67 (vc52 Phase 11): the remote-down fixture — the NEXT bridge-open fails with the typed disconnected error. */
    @Volatile var remoteDownArmed: Boolean = false
        private set
    /** G67: the arm flips OFF after the reconnect request (the reconnect FIXED the workspace). */
    fun armRemoteDown() {
        remoteDownArmed = true
        synchronized(script.bridgeErrorReasons) { script.bridgeErrorReasons.clear() }
    }

    /** G67 self-test seam: the reconnect-request ledger (requestId → workspaceKey). */
    val reconnectRequestsLedger = java.util.Collections.synchronizedList(ArrayList<Pair<String, String>>())

    private fun handleWorkspaceReconnectRequest(payload: JsonObject) {
        val requestId = payload.str("requestId")
        val workspaceKey = payload.str("workspaceKey")
        synchronized(reconnectRequestsLedger) { reconnectRequestsLedger.add(requestId to workspaceKey) }
        // The reconnect FIXES the remote workspace (the fixture's honest face).
        remoteDownArmed = false
        sendRaw(
            RelayFrameCodec.encodeEnvelope(
                json.encodeToString(JsonObject.serializer(), buildJsonObject {
                    put("zcode_type", JsonPrimitive("workspace-reconnect-response"))
                    put("requestId", JsonPrimitive(requestId))
                    put("workspaceKey", JsonPrimitive(workspaceKey))
                    put("success", JsonPrimitive(true))
                })
            )
        )
    }

    private fun handleBridgeOpen(ws: okhttp3.WebSocket, payload: JsonObject) {
        val requestId = payload.str("requestId")
        lastBridgeSessionId = payload.str("bridgeSessionId")
        // One-shot error queue (recovery-test driver): pop → verified error shape.
        // G67 (vc52 Phase 11): the remote-down fixture — the arm makes THIS bridge-open
        // fail with the desktop's typed disconnected-remote error (the bridge NEVER
        // succeeds while the arm is up; the reconnect-request flips it off).
        if (remoteDownArmed) {
            lastBridgeSessionId = payload.str("bridgeSessionId")
            lastBridgeWorkspaceKey = payload.str("workspaceKey")
            scope.launch { events.emit(ServerEvent.BridgeError("workspace-closed")) }
            sendRaw(
                RelayFrameCodec.encodeEnvelope(
                    json.encodeToString(JsonObject.serializer(), buildJsonObject {
                        put("zcode_type", JsonPrimitive("workspace-bridge-error"))
                        put("requestId", JsonPrimitive(requestId))
                        put("reason", JsonPrimitive("workspace-closed"))
                        put("error", JsonPrimitive("The remote workspace is not connected. Send /reconnect first, then try again."))
                    })
                )
            )
            return
        }
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
/**
 * H1.6 (Phase 6B): the `feedback` channel — `create` echoes a normalized ticket
 * (the host's cCe face: ticket_id/title/type/status/created_at/updated_at), `list`
 * returns {items, total}. The phone holds no credentials; the HOST is the submitter.
 */
private fun handleFeedbackPromise(id: Int, name: String?, arg: JsonObject?) {
    when (name) {
        "create" -> {
            val payload = arg ?: JsonObject(emptyMap())
            val description = (payload["description"] as? JsonPrimitive)?.content.orEmpty()
            if (description.isBlank()) {
                respondError(id, "fault.mock.unscripted:feedback.create-empty-description")
                return
            }
            val now = System.currentTimeMillis()
            respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                put("ticket_id", JsonPrimitive("mock-ticket-${(description.hashCode().toLong() and 0xFFFF) + 1}"))
                put("title", JsonPrimitive((payload["title"] as? JsonPrimitive)?.content ?: description.lineSequence().firstOrNull().orEmpty().take(80)))
                put("type", JsonPrimitive((payload["type"] as? JsonPrimitive)?.content ?: "bug"))
                put("status", JsonPrimitive("open"))
                put("created_at", JsonPrimitive(now.toString()))
                put("updated_at", JsonPrimitive(now.toString()))
            }))
        }
        "list" -> {
            val now = System.currentTimeMillis()
            respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                put("items", JsonArray(listOf(buildJsonObject {
                    put("ticket_id", JsonPrimitive("mock-ticket-1"))
                    put("title", JsonPrimitive("mock feedback"))
                    put("type", JsonPrimitive("bug"))
                    put("status", JsonPrimitive("open"))
                    put("created_at", JsonPrimitive(now.toString()))
                    put("updated_at", JsonPrimitive(now.toString()))
                })))
                put("total", JsonPrimitive(1))
            }))
        }
        else -> respondError(id, "fault.mock.unknownMethod:feedback-$name")
    }
}

private fun handleOffPeakTaskPromise(id: Int, name: String?, arg: RpcValue?) {
        // Phase 5A (A3.10 erratum): pauseTask/continueTask are OffPeakTaskService words
        // (bare positional id). Paused ids are reflected in the next `list`.
        if (name == "pauseTask" || name == "continueTask") {
            val offPeakId = (arg as? RpcValue.Arr)?.items?.firstOrNull()?.let { (it as? RpcValue.Str)?.value }
            if (offPeakId == null) {
                respondError(id, "fault.mock.unscripted:$name")
                return
            }
            if (name == "pauseTask") script.offPeakPausedIds.add(offPeakId) else script.offPeakPausedIds.remove(offPeakId)
            respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                put("id", JsonPrimitive(offPeakId))
                put("status", JsonPrimitive(if (name == "pauseTask") "paused" else "queued"))
            }))
            return
        }
        if (name == "cancelTask") {
            // L6.2 (Phase 6B): terminal `cancelled`; reflected in the next `list`.
            val offPeakId = (arg as? RpcValue.Arr)?.items?.firstOrNull()?.let { (it as? RpcValue.Str)?.value }
            if (offPeakId == null) {
                respondError(id, "fault.mock.unscripted:cancelTask")
                return
            }
            script.offPeakCancelledIds.add(offPeakId)
            respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                put("id", JsonPrimitive(offPeakId))
                put("status", JsonPrimitive("cancelled"))
            }))
            return
        }
        // G29 (vc51 Phase 4C): the authoring verbs — createTask appends a queued row,
        // updateTask rewrites one, deleteTask/deleteHistory remove — all reflected in
        // the next `list` (the monitor verbs' state contract, extended to authoring).
        if (name == "createTask") {
            val params = argAsObject(arg)
            val title = params?.strOrNull("title")?.takeIf { it.isNotBlank() }
            if (params == null || title == null || params.strOrNull("prompt").isNullOrBlank()) {
                respondError(id, "fault.mock.badRequest:createTask needs title + prompt")
                return
            }
            val row = buildJsonObject {
                val newId = "offpeak-${script.offPeakTaskList?.size?.plus(1) ?: 1}"
                put("id", JsonPrimitive(newId))
                put("offPeakTaskId", JsonPrimitive(newId))
                put("title", JsonPrimitive(title))
                put("status", JsonPrimitive("queued"))
                put("queuePosition", JsonPrimitive((script.offPeakTaskList?.size ?: 0) + 1))
                put("createdAt", JsonPrimitive(System.currentTimeMillis()))
                // §7.11 identity key — the phone's list filter matches on workspaceKey
                put("workspaceKey", JsonPrimitive(params.strOrNull("workspacePath") ?: params.strOrNull("workspaceKey") ?: ""))
            }
            script.offPeakTaskList = JsonArray((script.offPeakTaskList ?: JsonArray(emptyList())) + row)
            respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                put("ok", JsonPrimitive(true)); put("task", row)
            }))
            return
        }
        if (name == "updateTask") {
            val params = argAsObject(arg)
            val target = params?.strOrNull("offPeakTaskId")
            val current = script.offPeakTaskList
            if (params == null || target == null || current == null) {
                respondError(id, "fault.mock.unscripted:updateTask")
                return
            }
            val existing = current.firstOrNull {
                val oid = (it.jsonObject["offPeakTaskId"] as? JsonPrimitive)?.content ?: (it.jsonObject["id"] as? JsonPrimitive)?.content
                oid == target
            } as? JsonObject
            if (existing == null) {
                respondError(id, "fault.mock.unscripted:updateTask:target")
                return
            }
            val updated = JsonObject(existing + params.filterKeys { it != "offPeakTaskId" }.mapValues { it.value as kotlinx.serialization.json.JsonElement })
            script.offPeakTaskList = JsonArray(current.map { el ->
                val oid = (el.jsonObject["offPeakTaskId"] as? JsonPrimitive)?.content ?: (el.jsonObject["id"] as? JsonPrimitive)?.content
                if (oid == target) updated else el
            })
            respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(updated))
            return
        }
        if (name == "deleteTask" || name == "deleteHistory") {
            val target = (arg as? RpcValue.Arr)?.items?.firstOrNull()?.let { (it as? RpcValue.Str)?.value }
            val current = script.offPeakTaskList
            if (target == null || current == null) {
                respondError(id, "fault.mock.unscripted:$name")
                return
            }
            script.offPeakTaskList = JsonArray(current.filter {
                val oid = (it.jsonObject["offPeakTaskId"] as? JsonPrimitive)?.content ?: (it.jsonObject["id"] as? JsonPrimitive)?.content
                oid != target
            })
            respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(JsonObject(emptyMap())))
            return
        }
        if (name != "list") {
            respondError(id, "fault.mock.unknownMethod:off-peak-task-$name")
            return
        }
        // Debt #11 (pass 14): the first-list anchor — the G63 notifier's baseline
        // seed IS this call, so arming here guarantees baseline-before-flip ordering
        // inside one process life (the relay-start anchor fired 5s before CONNECTED).
        scheduleOffPeakCompleteOnFirstList()
        val items = script.offPeakTaskList?.let { arr ->
            JsonArray(arr.map { el ->
                val o = el as? JsonObject ?: return@map el
                val oid = (o["offPeakTaskId"] as? JsonPrimitive)?.content ?: (o["id"] as? JsonPrimitive)?.content
                when {
                    oid != null && oid in script.offPeakCancelledIds -> JsonObject(o + ("status" to JsonPrimitive("cancelled")))
                    oid != null && oid in script.offPeakPausedIds -> JsonObject(o + ("status" to JsonPrimitive("paused")))
                    else -> o
                }
            })
        }
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
                // RIG-P2 (N-BD-2b): archived tasks leave the SERVED main list (they stay
                // reachable through listArchivedTasks) — the ack's served-list mutation.
                RpcValue.Arr(listTasksServedForTest().map { RpcValue.Obj(it) }),
            )
            "listDeletedTaskIds" -> respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Arr(emptyList()))
            "listPinnedTaskIds" -> respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Arr(script.pinnedTaskIds.map { RpcValue.Str(it) }))
            // Phase 5A (A3.8–A3.10): metas filtered by the mutable flags so round-trips are provable.
            "listPinnedTasks" -> respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Arr(
                script.directTasks.filter { taskIdOf(it) in script.pinnedTaskIds }.map { RpcValue.Obj(decorateMeta(it)) },
            ))
            "listArchivedTasks" -> {
                if (script.archivedFault) {
                    println("MOCK: listArchivedTasks forced fault (MOCK_ARCHIVED_FAULT) -> fault.archived.rig")
                    respondError(id, "fault.archived.rig")
                } else respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Arr(
                    listArchivedServedForTest().map { RpcValue.Obj(decorateMeta(it)) },
                ))
            }
            "setTaskPinned" -> {
                val taskId = arg?.str("taskId")
                val pinned = (arg?.get("pinned") as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
                if (taskId == null || pinned == null) respondError(id, "fault.mock.unscripted:setTaskPinned")
                else {
                    if (pinned) script.pinnedTaskIds.add(taskId) else script.pinnedTaskIds.remove(taskId)
                    respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                        put("taskId", JsonPrimitive(taskId)); put("pinned", JsonPrimitive(pinned))
                    }))
                }
            }
            "setTaskUnread" -> {
                val taskId = arg?.str("taskId")
                val unread = (arg?.get("unread") as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
                if (taskId == null || unread == null) respondError(id, "fault.mock.unscripted:setTaskUnread")
                else {
                    // Pass-39: receipt the wire arg (pass 38's coverage list — the CAS arg
                    // rode the wire but nothing observed it).
                    println("MOCK: setTaskUnread taskId=$taskId unread=$unread expectedUnreadAt=${arg["expectedUnreadAt"]}")
                    if (!unread && script.unreadCas) {
                        val expected = (arg["expectedUnreadAt"] as? JsonPrimitive)?.content?.toLongOrNull()
                        val stored = if (script.unreadCasForce) expected?.plus(1) else script.unreadTaskIds[taskId]
                        if (expected == null || expected != stored) {
                            println("MOCK: setTaskUnread CAS mismatch (MOCK_UNREAD_CAS) expected=$expected stored=$stored -> fault.unread.stale")
                            respondError(id, "fault.unread.stale:expectedUnreadAt")
                            return
                        }
                    }
                    val stamp = if (unread) System.currentTimeMillis().also { script.unreadTaskIds[taskId] = it } else { script.unreadTaskIds.remove(taskId); null }
                    respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                        put("taskId", JsonPrimitive(taskId))
                        if (stamp != null) put("unreadAt", JsonPrimitive(stamp)) else put("unreadAt", kotlinx.serialization.json.JsonNull)
                    }))
                }
            }
            // RIG-P2 (vc52 Phase 14, N-BD-2b): the archive verbs MUTATE the served
            // state — the id joins/leaves `archivedTaskIds`, the main `listTasks`
            // stops serving the archived row, and the mutation rides
            // `workspace-list-updated` so the hub's projection follows the ack (the
            // recorded face: create/fork/archive acked ok but the served task list
            // never changed).
            "archiveTask", "unarchiveTask" -> {
                val taskId = arg?.str("taskId")
                if (taskId == null) respondError(id, "fault.mock.unscripted:$name")
                else {
                    if (name == "archiveTask") script.archivedTaskIds.add(taskId) else script.archivedTaskIds.remove(taskId)
                    // §48-U2 path: the desktop pushes the updated task list immediately.
                    script.bootstrapTasks?.let { tasks ->
                        pushWorkspaceListUpdated(tasks.filter { it.taskId !in script.archivedTaskIds })
                    }
                    respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                        put("taskId", JsonPrimitive(taskId))
                        put("archived", JsonPrimitive(name == "archiveTask"))
                    }))
                }
            }
            "resumeTask" -> {
                val taskId = arg?.str("taskId")
                if (taskId == null) respondError(id, "fault.mock.unscripted:resumeTask")
                else {
                    script.resumedTaskIds.add(taskId)
                    // RIG-5: the row's visible status follows the continue verb (the next
                    // listTasks reflects the ack instead of the stale ended face).
                    script.directTasks = script.directTasks.map {
                        if (taskIdOf(it) == taskId) JsonObject(it.jsonObject + ("displayStatus" to JsonPrimitive("idle"))) else it
                    }
                    respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                        put("taskId", JsonPrimitive(taskId)); put("status", JsonPrimitive("idle"))
                    }))
                }
            }
            "deleteArchivedTask" -> {
                val taskId = arg?.str("taskId")
                if (taskId == null || taskId !in script.archivedTaskIds) respondError(id, "fault.mock.unscripted:deleteArchivedTask")
                else {
                    script.archivedTaskIds.remove(taskId)
                    script.directTasks = script.directTasks.filterNot { taskIdOf(it) == taskId }
                    // Pass-38: sync the CONTROL view too (the deleteTask fix's class) — the
                    // unsynced view resurrected the deleted task on the app's next refresh
                    // AND starved its group's member list (groupedStructure reads directTasks).
                    script.bootstrapTasks = script.bootstrapTasks.orEmpty().filterNot { taskIdOf(it.toJson()) == taskId }
                    respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(JsonObject(emptyMap())))
                }
            }
            "createTaskGroup" -> {
                val now = System.currentTimeMillis()
                val group = buildJsonObject {
                    put("id", JsonPrimitive("task-group-${script.taskGroups.size + 1}"))
                    put("title", JsonPrimitive(arg?.strOrNull("title")?.trim()?.ifEmpty { null } ?: "New Group"))
                    put("color", JsonPrimitive(arg?.strOrNull("color") ?: "gray"))
                    put("createdAt", JsonPrimitive(now)); put("updatedAt", JsonPrimitive(now))
                }
                script.taskGroups.add(group)
                respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(group))
            }
            "renameTaskGroup", "updateTaskGroupColor" -> {
                val groupId = arg?.str("groupId")
                val idx = script.taskGroups.indexOfFirst { (it["id"] as? JsonPrimitive)?.content == groupId }
                if (groupId == null || idx < 0) respondError(id, "fault.mock.taskGroupMissing")
                else {
                    val key = if (name == "renameTaskGroup") "title" else "color"
                    val updated = JsonObject(script.taskGroups[idx] + (key to JsonPrimitive(arg.str(key))) + ("updatedAt" to JsonPrimitive(System.currentTimeMillis())))
                    script.taskGroups[idx] = updated
                    respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(updated))
                }
            }
            "deleteTaskGroup" -> {
                val groupId = arg?.str("groupId")
                script.taskGroups.removeAll { (it["id"] as? JsonPrimitive)?.content == groupId }
                if (groupId != null) script.taskGroupMembers.remove(groupId)
                respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Undefined)
            }
            "listGroupedTaskViewStructure" -> respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(groupedStructure()))
            "applyGroupedTaskViewOrder" -> {
                val groups = arg?.get("groups") as? JsonArray
                if (groups == null) respondError(id, "fault.mock.unscripted:applyGroupedTaskViewOrder")
                else {
                    // The host refuses pinned/archived members ("Grouped task order 包含不可见 task").
                    val refs = groups.flatMap { g -> ((g as JsonObject)["taskRefs"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.str("taskId") } ?: emptyList() }
                    if (refs.any { it in script.pinnedTaskIds || it in script.archivedTaskIds }) {
                        respondError(id, "Grouped task order 包含不可见 task")
                    } else {
                        script.taskGroupMembers.clear()
                        groups.forEach { g ->
                            val go = g as JsonObject
                            val gid = go.str("groupId")
                            script.taskGroupMembers[gid] = (go["taskRefs"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.str("taskId") }?.toMutableList() ?: mutableListOf()
                        }
                        respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(groupedStructure()))
                    }
                }
            }
            // Phase 4D (L6.3+J4 / H1.1): the lifecycle + search methods (shipped 3.14.5).
            "deleteTask" -> {
                val taskId = arg?.str("taskId")
                if (taskId == null) respondError(id, "fault.mock.unscripted:deleteTask")
                else {
                    script.directTasks = script.directTasks.filterNot { (it.jsonObject["taskId"] as? JsonPrimitive)?.content == taskId }
                    // Pass-38: keep the CONTROL view's list in sync (same stale-view class).
                    script.bootstrapTasks = script.bootstrapTasks.orEmpty().filterNot { taskIdOf(it.toJson()) == taskId }
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
                    // Pass-38: the CONTROL view reads bootstrapTasks — keep the title in sync.
                    script.bootstrapTasks = script.bootstrapTasks.orEmpty().map {
                        if (taskIdOf(it.toJson()) == taskId) it.copy(title = title) else it
                    }
                    respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(JsonObject(emptyMap())))
                }
            }
            "archiveTask", "unarchiveTask" -> {
                val taskId = arg?.str("taskId")
                if (taskId == null) respondError(id, "fault.mock.unscripted:" + name)
                else {
                    // RIG-5: the ack mutates the set listArchivedTasks filters — the next
                    // refresh reflects the archive (the recorded ack-without-mutate class).
                    if (name == "archiveTask") script.archivedTaskIds.add(taskId) else script.archivedTaskIds.remove(taskId)
                    respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(JsonObject(emptyMap())))
                }
            }
            "getModelTrajectory" -> {
                // p46: the arg log line — the trajectory wire receipt must be ARG-EXACT
                // (the ae091f81 git-checkpoint template; zcode-task channel calls print
                // nothing, so the plane's first drive is otherwise invisible).
                println("MOCK: zcode-task getModelTrajectory args=${arg ?: JsonObject(emptyMap())}")
                scriptableObject(id, script.modelTrajectory, "getModelTrajectory")
            }
            // Phase 5C (A3.15): the feedback SNAPSHOT word — the mock keeps per-task lists.
            "setAssistantMessageFeedback" -> {
                val taskId = arg?.str("taskId")
                if (taskId == null) respondError(id, "fault.mock.unscripted:setAssistantMessageFeedback")
                else {
                    val list = script.assistantFeedback.getOrPut(taskId) { mutableListOf() }
                    val turnIndex = arg.str("turnIndex").toIntOrNull() ?: 0
                    val fb = arg["feedback"]
                    val value = if (fb is kotlinx.serialization.json.JsonNull) null else (fb as? JsonPrimitive)?.content
                    while (list.size <= turnIndex) list.add(null)
                    list[turnIndex] = value
                    respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                        put("taskId", JsonPrimitive(taskId))
                        put("feedbacks", JsonArray(list.map { if (it == null) kotlinx.serialization.json.JsonNull else JsonPrimitive(it) }))
                    }))
                }
            }
            "listTaskList" -> {
                val search = arg?.str("search").orEmpty().lowercase()
                // p42 (debt #27): the desktop's rowToTaskListItem attaches searchSnippets
                // (matched-context strings from searchable_text, searchSnippet = the first)
                // to SEARCH hits — the phone's tasks-search pane renders them under the
                // title. The mock's conversations are generated on demand (no stored
                // searchable_text), so the searchable surface is the task title itself and
                // the snippet is the matched-context window around the hit.
                // p43 (debt #29): the desktop's queryTaskList honors the caller's `limit`
                // (LIMIT ? over the ordered filter) and answers {items: ≤limit, total:
                // COUNT over the filter, hasMore: total > items.length} (host
                // chunk-TCPD6WAE.js). The mock served ALL hits with hasMore:false
                // hardcoded, so the phone's limit:20 search (TaskServiceClient.searchTasks)
                // could never see the pagination face — with MOCK_TASKS_MANY seeded, the
                // desktop-faithful 20-items/hasMore=true answer is drivable.
                val limit = arg?.str("limit")?.toIntOrNull()?.takeIf { it > 0 }
                val hits = script.directTasks
                    .filter { search.isEmpty() || ((it.jsonObject["title"] as? JsonPrimitive)?.content?.lowercase()?.contains(search) == true) }
                    .map { base ->
                        val obj = JsonObject(base.jsonObject)
                        if (search.isEmpty()) {
                            obj
                        } else {
                            val title = (base.jsonObject["title"] as? JsonPrimitive)?.content.orEmpty()
                            val low = title.lowercase()
                            val at = low.indexOf(search)
                            val snippet = if (at >= 0) {
                                val s = maxOf(0, at - 24)
                                val e = minOf(title.length, at + search.length + 24)
                                buildString {
                                    if (s > 0) append("…")
                                    append(title.substring(s, e))
                                    if (e < title.length) append("…")
                                }
                            } else {
                                title
                            }
                            JsonObject(obj + mapOf(
                                "searchSnippet" to JsonPrimitive(snippet),
                                "searchSnippets" to JsonArray(listOf(JsonPrimitive(snippet))),
                            ))
                        }
                    }
                val served = limit?.let { hits.take(it) } ?: hits
                val hasMore = limit != null && hits.size > served.size
                if (search.isNotEmpty()) {
                    println("MOCK: listTaskList search='$search' → ${hits.size} hits (limit=$limit served=${served.size} hasMore=$hasMore)")
                }
                respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                    put("items", JsonArray(served))
                    put("total", JsonPrimitive(hits.size))
                    put("hasMore", JsonPrimitive(hasMore))
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
            "getEntitlementSnapshot" -> scriptableObject(id, script.usageEntitlement?.let { confirmedEntitlement(it) }, "getEntitlementSnapshot")
            "getCodingPlanResetStatus" -> {
                val queued = synchronized(script.resetStatusQueue) { script.resetStatusQueue.removeFirstOrNull() }
                if (queued != null) {
                    // Pass-15 (debt #10's queue variant): queue entries are EXPLICITLY
                    // scripted shapes — serve as-is with only the #15 unread fold, so a
                    // queued pre-consume entry genuinely delays the confirmation (the
                    // phone's reconciliation retry ladder fires) instead of being
                    // confirmed in place by the pass-14 fold.
                    println("MOCK: reset status served from QUEUE")
                    scriptableObject(id, foldUnread(queued), "getCodingPlanResetStatus")
                } else {
                    // Debt #10 (pass 14): after a scripted use, every status read serves
                    // the CONFIRMED progression — the phone's post-use reconciliation
                    // polls until usedAt lands, then breaks COMPLETED.
                    scriptableObject(id, script.codingPlanResetStatus?.let { confirmedResetStatus(it) }, "getCodingPlanResetStatus")
                }
            }
            "requestCodingPlanResetOpportunity" ->
                scriptableObject(id, script.resetOpportunity, "requestCodingPlanResetOpportunity")
            "useCodingPlanReset" -> {
                if (script.resetUse == null) return scriptableObject(id, null, "useCodingPlanReset")
                // Debt #10 (pass 14): the scripted SUCCESS stamps the use (which window,
                // when) so the status reads confirm it — the success half the G90 chain
                // never had (fault.mock.unscripted receipted pass 13).
                val params = argAsObject(arg)
                script.codingPlanResetUsedType = params?.strOrNull("resetType") ?: "FIVE_HOUR"
                script.codingPlanResetUsedAtMs = System.currentTimeMillis()
                // Pass-15 (debt #10's queue variant): arm the ladder leg — one
                // pre-consume entry lands in the queue exactly between the use and
                // the confirmation reads.
                if (script.resetQueuePreOnUse) {
                    script.codingPlanResetStatus?.let {
                        synchronized(script.resetStatusQueue) { script.resetStatusQueue.addLast(JsonObject(it)) }
                    }
                    println("MOCK: reset ladder armed (one pre-consume status queued at use)")
                }
                scriptableObject(id, script.resetUse, "useCodingPlanReset")
            }
            "getCodingPlanUsageSnapshot" ->
                scriptableObject(id, script.codingPlanUsageSnapshot, "getCodingPlanUsageSnapshot")
            "markCodingPlanResetHistoryRead" -> {
                // Debt #15 (pass 15): the mark CLEARS the unread cursor (the desktop's
                // server) — every later status read serves the cleared cursor; the
                // pass-14-declared arrival counter goes live too.
                script.codingPlanResetHistoryRead = true
                script.resetHistoryReadCount.incrementAndGet()
                println("MOCK: reset history read (hasUnreadHistory cleared)")
                respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Undefined)
            }
            else -> respondError(id, "fault.mock.unknownMethod:usage-stats-${name ?: "?"}")
        }
    }

    /**
     * Debt #10 (pass 14): the post-use confirmation fold. After a scripted
     * useCodingPlanReset success, the served status carries the used-type's consumed
     * opportunity (the array emptied — "no NEW opportunity" keeps the completion
     * sticky), the usedAt history, and the shared unread cursor — the exact shape
     * applyResetStatus's completion rule needs (core/ui CodingPlanQuotaResetState).
     * Debt #15 (pass 15): the unread cursor folds against the history-read flag —
     * markCodingPlanResetHistoryRead clears it (the desktop's server), so every
     * later read serves hasUnreadHistory=false instead of the sticky true.
     */
    /** Debt #15 (pass 15): the unread-cursor fold — a history-read mark clears the
     *  cursor on EVERY served shape (queued or fallback); otherwise the entry's own
     *  value stands. */
    private fun foldUnread(base: JsonObject): JsonObject {
        val unread = if (script.codingPlanResetHistoryRead) false
        else (base["hasUnreadHistory"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
        val own = (base["hasUnreadHistory"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
        return if (unread == own) base else JsonObject(base + mapOf("hasUnreadHistory" to JsonPrimitive(unread)))
    }

    private fun confirmedResetStatus(base: JsonObject): JsonObject {
        val usedAt = script.codingPlanResetUsedAtMs ?: return foldUnread(base)
        val week = script.codingPlanResetUsedType == "WEEK"
        val dropKey = if (week) "availableWeekResets" else "availableFiveHourResets"
        val histKey = if (week) "latestWeekResetHistory" else "latestFiveHourResetHistory"
        val unread = !script.codingPlanResetHistoryRead
        return JsonObject(base + mapOf(
            dropKey to JsonArray(emptyList()),
            histKey to buildJsonObject { put("usedAt", JsonPrimitive(usedAt)) },
            "hasUnreadHistory" to JsonPrimitive(unread),
        ))
    }

    /**
     * Debt #16 (pass 15): after a scripted use, later getEntitlementSnapshot reads
     * serve the used window's limit entry mutated by [Script.resetMeterDelta]
     * (usage dropped, remaining/percentage recomputed so the meter stays
     * self-consistent; clamped at 0). The other window's entry is untouched. The
     * base fixture stays pristine — the mutation derives from the per-process use
     * stamp, so every relay generation re-seeds its own baseline.
     */
    private fun confirmedEntitlement(base: JsonObject): JsonObject {
        val usedAt = script.codingPlanResetUsedAtMs ?: return base
        val week = script.codingPlanResetUsedType == "WEEK"
        val wantUnit = if (week) 6 else 5
        val quota = base["quota"] as? JsonObject ?: return base
        val limits = quota["limits"] as? JsonArray ?: return base
        var mutated = false
        val newLimits = JsonArray(limits.map { el ->
            val limit = el as? JsonObject ?: return@map el
            val unit = (limit["unit"] as? JsonPrimitive)?.content?.toIntOrNull()
            if (unit != wantUnit) return@map el
            mutated = true
            val usage = ((limit["usage"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0)
                .coerceAtLeast(script.resetMeterDelta) - script.resetMeterDelta
            JsonObject(limit + mapOf(
                "usage" to JsonPrimitive(usage),
                "remaining" to JsonPrimitive((100 - usage).coerceAtLeast(0)),
                "percentage" to JsonPrimitive(usage),
            ))
        })
        if (!mutated) return base
        println("MOCK: entitlement meters mutated (${if (week) "WEEK/unit-6" else "FIVE_HOUR/unit-5"} after use at $usedAt)")
        return JsonObject(base + mapOf("quota" to JsonObject(quota + mapOf("limits" to newLimits))))
    }

    /** Debt #10 seam: drive the usage-stats handler directly (the reset legs). */
    internal fun usageStatsPromiseForTest(id: Int, name: String?, arg: RpcValue?) =
        handleUsageStatsPromise(id, name, arg)

    /** Self-test observability: every promise reply (id, responseTypeId, payload) in send order. */
    private val promiseResponses = ArrayList<Triple<Int, Int, RpcValue>>()
    internal fun promiseResponsesForTest(): List<Triple<Int, Int, RpcValue>> =
        synchronized(promiseResponses) { ArrayList(promiseResponses) }

    private fun respond(id: Int, type: ChannelWire.ResponseType, data: RpcValue) {
        synchronized(promiseResponses) { promiseResponses.add(Triple(id, type.id, data)) }
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
    /**
     * G48 (vc52 Phase 11): the clientHello's `capabilities` object as the mock last
     * saw it (null = never declared) — the enforcing-mock seam the rig gate reads.
     */
    @Volatile var lastClientHelloCapabilities: JsonObject? = null
        private set

    /** Self-test seam: simulate a clientHello's declared capabilities (returns the set value). */
    internal fun lastClientHelloCapabilitiesForTest(caps: JsonObject?): JsonObject? {
        lastClientHelloCapabilities = caps
        return lastClientHelloCapabilities
    }
    /** G48 gate counters: deltas streamed to declared clients / suppressed at the gate. */
    @Volatile var workflowRunDeltasSent: Long = 0
        private set
    @Volatile var workflowRunDeltasSuppressed: Long = 0
        private set
    @Volatile var lastCommandClientId: String? = null
        private set

    /**
     * RIG-5 (vc51 Phase 5): the session the phone most recently subscribed/resynced —
     * command effects and state patches route THERE (the D4.1 artifact routed everything
     * to the hardcoded workspace session). Null = no conversation subscribed yet → the
     * default workspace session keeps serving (existing suites unchanged).
     */
    @Volatile private var activeSessionId: String? = null
    private fun routingSessionId(): String = activeSessionId ?: workspace.sessionId
    internal fun activeSessionForTest(sessionId: String?) { activeSessionId = sessionId }

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
                            // Pass-35: totalCount is the SESSION total (the armed value),
                            // not the window size — the pass-3/4-era "of 338" artifact.
                            put("rows", buildJsonObject {
                                put("window", rows)
                                put("totalCount", JsonPrimitive(script.snapshotTotalCount ?: rows.size))
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
            sendLogicalFrame(bytes, isData = true)
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

    /**
     * Pass-38 (p38-mock-decorate): the CONTROL view's task rows. The old view served the
     * raw `MockTask.toJson()` list, so a pin/unread mutation (which mutates only the
     * `pinnedTaskIds`/`unreadTaskIds` sets the decorated CHANNEL metas read) was
     * silently reverted on the app's next refresh — the phone re-applied the stale
     * bootstrap view over its own ok'd mutation. The view now flows through
     * [decorateMeta] so both surfaces read the same live sets; rename/delete sync the
     * [MockScript.bootstrapTasks] objects too.
     */
    internal fun bootstrapTasksView(): kotlinx.serialization.json.JsonArray =
        script.bootstrapTasks?.let { override ->
            // Pass-38 (p38-mock-archive-exclude): the served MAIN list excludes archived
            // rows (the listTasks RIG-P2 shape) — the unfiltered control view resurrected
            // the just-archived row on the app's post-action refresh (live receipt: push
            // applied tasks=2 → refresh re-applied tasks=3 within 1 s, row back on home).
            kotlinx.serialization.json.JsonArray(override
                .filterNot { taskIdOf(it.toJson()) in script.archivedTaskIds }
                .map { decorateMeta(it.toJson()) })
        } ?: kotlinx.serialization.json.JsonArray(listOf(
            // PRODUCTION shape (report F5/G1): the legacy {id,title,status} row carries
            // no workspacePath — the app's bootstrap parser keys tasks by
            // workspaceIdentity ?: workspacePath, and a blank key is silently skipped
            // (logged applied, zero rows persisted).
            // F-ROUND26 self-consistency: the row's §7.11 identity FOLLOWS the same
            // active-key override the view state advertises — a served view whose task
            // row disagreed with its own activeWorkspaceKey made the app's vc33
            // task-row-authoritative bridge resolution resolve a workspace the strict
            // mock then rejected as stale (its own view!) — the BridgeError/
            // FreshDiscovery/ScopedChannel strict-bridge suites' red.
            decorateMeta(MockTask(
                taskId = workspace.sessionId,
                title = "Synthetic task",
                displayStatus = "idle",
                workspacePath = workspace.workspacePath,
                workspaceLabel = "Synthetic One",
                workspaceIdentity = script.bootstrapActiveWorkspaceKey ?: workspace.workspaceKey,
            ).toJson()),
        ))

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
                    put("workspaces", relayWorkspacesView())
                    // Pass-38 (p38-mock-decorate): the CONTROL view decorates rows with
                    // the LIVE pin/unread/archived sets — the raw MockTask jsons went
                    // stale the moment setTaskPinned/setTaskUnread mutated only
                    // pinnedTaskIds/unreadTaskIds, so the app's next refresh re-applied
                    // the connect-time flags and visually reverted the mutation
                    // (receipted live: mark-read ok → "Marked read" → dot still bold).
                    put("tasks", bootstrapTasksView())
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
                    put("workspaces", relayWorkspacesView())
                    put("activeWorkspaceKey", JsonPrimitive(workspace.workspaceKey))
                })
            })
        ))
    }

    /**
     * p41 (pass-41): the relay's workspace-list rows, shared by the bootstrap-response
     * and the workspace-list-response. Until this arm BOTH surfaces served exactly ONE
     * row, so the Manage sheet's reorder arrows were structurally disabled (one row →
     * `index > 0` and `index < order.lastIndex` never both hold) and the chips row had
     * no second chip — the pass-40 "did NOT drive" items. MOCK_WS_TWO=1 appends
     * /synthetic/ws-two ("Synthetic Two"); NO task is seeded on it, so the home's
     * task-key sections are untouched (no new F-P38-A ghost) and a chip/row switch to
     * it filters to an honest zero.
     */
    internal fun relayWorkspacesView(): kotlinx.serialization.json.JsonArray {
        val primary = buildJsonObject {
            put("workspacePath", JsonPrimitive(workspace.workspacePath))
            put("label", JsonPrimitive("Synthetic One"))
            put("kind", JsonPrimitive("local"))
            put("workspaceKey", JsonPrimitive(workspace.workspaceKey))
        }
        if (!script.relaySecondWorkspace) return kotlinx.serialization.json.JsonArray(listOf(primary))
        val second = buildJsonObject {
            put("workspacePath", JsonPrimitive("/synthetic/ws-two"))
            put("label", JsonPrimitive("Synthetic Two"))
            put("kind", JsonPrimitive("local"))
            put("workspaceKey", JsonPrimitive("/synthetic/ws-two"))
        }
        return kotlinx.serialization.json.JsonArray(listOf(primary, second))
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

        /**
         * RIG-P3: the snapshot's row-id scheme is rowIdBase..rowIdBase+count-1 (plus
         * appended turn/markdown rows); the conversation STORE's top row id:
         */
        fun snapshotRowIdTop(rowCount: Int, rowIdBase: Long = 1L): Long = rowIdBase + rowCount - 1

        /**
         * RIG-P3 (vc52 Phase 10 / F-37-D): the tail-window PAGE the rowsRange handler
         * serves — the rows STRICTLY OLDER than [beforeRowId] (or the OLDEST page when
         * null), never crossing the store floor (row id 1). hasMore = rows remain above
         * the page. Deterministic + id-keyed, so two paging calls return two DISTINCT
         * older pages.
         */
        fun rowsRangePage(storeTop: Long, beforeRowId: Long?, limit: Int): LongRange {
            val upper = ((beforeRowId ?: (storeTop + 1)) - 1).coerceAtMost(storeTop)
            val lower = (upper - (limit.coerceAtLeast(1) - 1)).coerceAtLeast(ROW_ID_FLOOR)
            if (upper < lower) return LongRange.EMPTY
            return lower..upper
        }

        /** The mock's conversation store is row ids 1..top (snapshotRowIdBase counts the SNAPSHOT window, not the store). */
        const val ROW_ID_FLOOR = 1L
    }
}
