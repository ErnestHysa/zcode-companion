package com.zcode.android.core.transport

import com.zcode.android.core.common.error.AppError
import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.protocol.bytes.ProtocolException
import com.zcode.android.core.protocol.bytes.RpcSerialization.RpcValue
import com.zcode.android.core.protocol.channel.ChannelSession
import com.zcode.android.core.protocol.v4.ClientHello
import com.zcode.android.core.protocol.v4.CommandAck
import com.zcode.android.core.protocol.v4.CommandEnvelope
import com.zcode.android.core.protocol.v4.Hello
import com.zcode.android.core.protocol.v4.LogicalFrameAssembler
import com.zcode.android.core.protocol.v4.RowsRangeParams
import com.zcode.android.core.protocol.v4.RowsRangeResult
import com.zcode.android.core.protocol.v4.SubscribeAck
import com.zcode.android.core.protocol.v4.TopicFrame
import com.zcode.android.core.protocol.v4.TopicWireFrame
import com.zcode.android.core.protocol.v4.V4Topics
import com.zcode.android.core.protocol.v4.V4WireVersionException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Shared failure surface (§33): every transport throws [TransportFailure] carrying
 * the already-classified [AppError]. Transports may subclass it; higher layers
 * extract `.error` from the BASE type so both transports surface identically.
 */
open class TransportFailure(val error: AppError) : Exception(error.developerDiagnostic)

/**
 * The v4 conversation + service-channel surface (docs/protocol/v4-wire.md §4–§10,
 * docs/protocol/phase9-channels.md) — byte-identical on BOTH transports (§8.3.1:
 * "both share ~90% of the stack"). Implemented by [RelayTransport] (over relay
 * rpc-frame envelopes) and [DirectTransport] (over raw binary SocketProtocol
 * frames) through the shared [ChannelV4Plane].
 *
 * Auth models stay OUT of this surface by construction (§11.4): nothing here
 * knows how the connection authenticated.
 */
interface V4Plane {
    /** The clientId the v4 handshake bound for THIS connection (fault.command.clientMismatch guard). */
    val commandClientId: String?

    /** The connected endpoint's discovered workspace target (path, identity) — Phase 9 tool-plane routing. */
    val activeWorkspaceTarget: Pair<String?, String?>?

    /** The v4 hello's connectionId — attachment begin/chunk/commit/abort require it. */
    val v4ConnectionId: String?

    suspend fun subscribeSession(
        sessionId: String,
        visibility: String? = "foreground",
        workspacePath: String? = null,
        workspaceIdentity: String? = null,
    ): SubscribeAck

    /**
     * Phase 2 (§11.2, GAP-12): subscribe the workspace-level `workspace-config/
     * <workspaceKey>` catalog topic on the SAME `subscribeConversationV4` RPC with
     * an EXPLICIT topic (the desktop adapter's own subscribeWorkspaceConfigV4 shape,
     * zcodeAgentService.ts:5562-5584). workspaceKey = the §7.11 identity key
     * (workspaceIdentity ?: workspacePath — the desktop's resolveWorkspaceKey).
     * The initial frame rides the same post-response notification; the scoped
     * conversation listen already routes it (dispatch is by topic prefix).
     */
    suspend fun subscribeWorkspaceConfig(
        workspacePath: String?,
        workspaceIdentity: String?,
    ): SubscribeAck

    suspend fun rowsRange(sessionId: String, beforeRowId: Long?, limit: Int): RowsRangeResult
    suspend fun rowsRange(params: RowsRangeParams): RowsRangeResult

    suspend fun resyncSession(
        sessionId: String,
        base: Pair<String, Long>?,
        forceSnapshot: Boolean = false,
        workspacePath: String? = null,
        workspaceIdentity: String? = null,
    ): SubscribeAck

    suspend fun sendConversationCommand(
        envelope: CommandEnvelope,
        workspacePath: String? = null,
        workspaceIdentity: String? = null,
    ): CommandAck

    /** Scoped onDynamicConversationFrame listen (FIELD-KILLER #5) — idempotent per path. */
    fun ensureConversationListen(workspacePath: String?, workspaceIdentity: String?)

    /** Workspace targets seen at subscribe time (session → path/identity), recovery + command routing. */
    fun subscriptionWorkspace(sessionId: String): Pair<String?, String?>?

    /** Server-acked logEpoch for a subscribed session (§15-S9 fresh after every subscribe). */
    fun subscriptionEpoch(sessionId: String): String?

    // ---- Phase 9 read surfaces (INV-9: no write methods exist — typed-surface enforcement)

    suspend fun conversationFileChanges(
        sessionId: String,
        targetRowId: Long,
        targetEntityId: String,
        baseRevision: Long,
        baseLogEpoch: String,
        workspacePath: String? = null,
        workspaceIdentity: String? = null,
    ): JsonObject

    suspend fun attachmentBegin(
        uploadId: String,
        sessionId: String,
        fileName: String,
        mime: String,
        totalBytes: Long,
        totalChunks: Int,
        checksum: String,
        workspacePath: String? = null,
        workspaceIdentity: String? = null,
    ): JsonObject

    suspend fun attachmentChunk(
        uploadId: String,
        sessionId: String,
        chunkIndex: Int,
        dataBase64: String,
        workspacePath: String? = null,
        workspaceIdentity: String? = null,
    ): JsonObject

    suspend fun attachmentCommit(uploadId: String, sessionId: String): JsonObject
    suspend fun attachmentAbort(uploadId: String, sessionId: String): JsonObject

    suspend fun attachmentRead(
        sessionId: String,
        ref: String,
        offset: Long,
        limit: Int,
        targetRowId: Long? = null,
        targetEntityId: String? = null,
        attachmentIndex: Int? = null,
        workspacePath: String? = null,
        workspaceIdentity: String? = null,
    ): JsonObject

    // ---- Phase 3 read-only viewer surfaces (INV-9: read methods only — v4-wire.md §12)

    /**
     * GAP-5: `getAppUsageStats` (zcodeAgent.ts:592, scope passthrough) → the CLI
     * `v4/usage/stats` — read-only, stateless, safe to re-send (transport.ts:363-366).
     * Params {range: "all"|"7d"|"30d", timeZone?} (strict); result = AppUsageSnapshot.
     */
    suspend fun appUsageStats(range: String, timeZone: String?): JsonObject

    /**
     * GAP-6: `listAutomations` (zcodeAgent.ts:677, workspace-target form) → the
     * automation list. Result = the desktop ZCodeAutomation[] channel type —
     * a top-level ARRAY (ProxyChannel convention) normalized to {automations:[…]}
     * for the shared decoder (the git.getChanges precedent).
     */
    suspend fun listAutomations(workspacePath: String?, workspaceIdentity: String?): JsonObject

    suspend fun createAutomation(workspacePath: String?, workspaceIdentity: String?, request: JsonObject): JsonObject
    suspend fun updateAutomation(workspacePath: String?, workspaceIdentity: String?, automationId: String, request: JsonObject): JsonObject
    suspend fun deleteAutomation(workspacePath: String?, workspaceIdentity: String?, automationId: String)
    suspend fun setAutomationEnabled(workspacePath: String?, workspaceIdentity: String?, automationId: String, enabled: Boolean)
    suspend fun runAutomationNow(workspacePath: String?, workspaceIdentity: String?, automationId: String): JsonObject

    /**
     * Phase 4B (A3.2): the run-history trio on the shipped 3.14.5 facade (bundle offset
     * ~348362: `listAutomationRuns(m){return o.listRuns(m.automationId,{workspacePath,
     * workspaceIdentity})}` / `deleteAutomationRun(m){o.deleteRun(m.runId,…)}` /
     * `restartAutomation(m){o.restart(m.automationId,…)}`). listAutomationRuns returns a
     * top-level ARRAY normalized to {runs:[…]} (the listAutomations precedent).
     */
    suspend fun listAutomationRuns(workspacePath: String?, workspaceIdentity: String?, automationId: String): JsonObject
    suspend fun deleteAutomationRun(workspacePath: String?, workspaceIdentity: String?, runId: String)
    suspend fun restartAutomation(workspacePath: String?, workspaceIdentity: String?, automationId: String)

    /**
     * Phase 4C (C2): the slash panel's extra sections over their own service channels
     * (C1's unused-channel rows): `commands.list` → CommandsListResult and
     * `subagents.list` → AgentsListResult. Both take the workspace-target form.
     */
    suspend fun commandsList(workspacePath: String?, workspaceIdentity: String?): JsonObject
    suspend fun subagentsList(workspacePath: String?, workspaceIdentity: String?, provider: String?): JsonObject
    /**
     * Phase 4C (A3.4): the reasoning-effort facade call (zcodeAgent.ts:705) —
     * `{sessionId, thoughtLevel, expectedRevision, persistAsWorkspaceLastUsed}`.
     * Returns the session snapshot (tolerant: the §10.3 `config` echo is the truth).
     */
    suspend fun setThoughtLevel(workspacePath: String?, workspaceIdentity: String?, sessionId: String, thoughtLevel: String, expectedRevision: Long?): JsonObject
    /**
     * Phase 4C (C7): the scratch-workspace facade call (shipped 3.14.5: params
     * `{name}` → `{path}` — desktop-local; NO workspace target keys, the strict
     * create-params schema would refuse them).
     */
    suspend fun createScratchWorkspace(name: String): JsonObject
    /**
     * Generic service-channel call (terminal/git/file/model-selection — phase9-channels.md).
     * Same error mapping as v4Call; a channel REFUSAL is a server-spoke rejection the
     * feature layers translate into their §13.3 disabled state.
     */
    suspend fun channelCall(channelName: String, method: String, arg: RpcValue, timeoutMs: Long): RpcValue

    /**
     * GAP-6: the `off-peak-task` channel `list` read (offPeakTask.ts:41 — NO args,
     * Arr([]) like model-selection.getView). Result normalized to {tasks:[…]}.
     */
    suspend fun offPeakTaskList(): JsonObject

    /**
     * GAP-7: `listPlugins` (zcodeAgent.ts:608) → the CLI `plugins/list` word
     * (including the host's own one-retry-on-timeout recovery, which lives
     * host-side). Result = zcodePluginsListResultSchema (STRICT).
     */
    suspend fun listPlugins(workspacePath: String?, workspaceIdentity: String?): JsonObject

    /**
     * GAP-8: `getSkillReferenceCatalog` (zcodeAgent.ts:617) → the CLI
     * `skills/referenceCatalog` word. Params {workspacePath, workspaceIdentity?,
     * sessionId?}; result = zcodeSkillsReferenceCatalogResultSchema (STRICT).
     */
    suspend fun skillReferenceCatalog(workspacePath: String?, workspaceIdentity: String?, sessionId: String?): JsonObject

    /**
     * GAP-9: `listMcpServerStatuses` (zcodeAgent.ts:607) → the CLI `mcp/list`
     * word. The phone pins `mode: "status"` — the passive last-known read;
     * `mode: "connect"` triggers REAL server connects (a viewer must not
     * side-effect the desktop's MCP runtime on browse).
     */
    suspend fun mcpServerStatuses(workspacePath: String?, workspaceIdentity: String?): JsonObject

    // ---- Phase 4 coding-plan quota plane (ADR-020; v4-wire.md §13)

    /**
     * GAP-3: `getEntitlementSnapshot` (usageStats.ts:29) on the `usage-stats`
     * channel — the desktop UI's own quota-panel data source (POLLED; there is
     * no quota push channel anywhere in the desktop, ADR-020). Args
     * `Arr([{}])` — every UsageEntitlementRequest field is optional and the
     * host resolves the current account/provider. Read-only.
     */
    suspend fun usageEntitlementSnapshot(request: JsonObject = JsonObject(emptyMap())): JsonObject

    /**
     * GAP-4 reset words (ADR-020 — the roadmap's second ruling is recorded in
     * the ADR and the GAP-4 commit). Params are the coding-plan-reset.ts
     * request interfaces: scope = {preferredProviderId, accountAccess} where
     * accountAccess is the phone-DERIVED credential-free plan descriptor
     * (domain accountAccessFromProviderId). `codingPlanResetUse` is the
     * quota plane's ONLY destructive-adjacent write word — it consumes a
     * server-granted reset opportunity and is reachable ONLY through the
     * typed-confirm dialog (T19; pinned by CodingPlanQuotaResetUiTest).
     */
    suspend fun codingPlanResetStatus(preferredProviderId: String, accountAccess: JsonObject): JsonObject
    suspend fun codingPlanResetOpportunity(
        preferredProviderId: String,
        accountAccess: JsonObject,
        idempotencyKey: String,
    ): JsonObject
    suspend fun codingPlanResetUse(
        preferredProviderId: String,
        accountAccess: JsonObject,
        idempotencyKey: String,
        resetType: String,
    ): JsonObject
    suspend fun codingPlanResetHistoryRead(preferredProviderId: String, accountAccess: JsonObject)

    // terminal (view-only — INV-9 EXCEPT the ADR-022 armed write tier)
    suspend fun terminalCreate(cols: Int, rows: Int, cwd: String? = null): JsonObject
    suspend fun terminalDispose(id: String)

    /**
     * ADR-022 (Phase 5D, A3.18): the armed write tier — `write {id, data}` /
     * `resize {id, cols, rows}` (host/index.js ≈250695 createTerminalService). The typed
     * gate is the CONTROLLER's arm (check{} above): the plane methods are reachable only
     * through the armed composer (pinned: zero calls while disarmed).
     */
    suspend fun terminalWrite(id: String, data: String)
    suspend fun terminalResize(id: String, cols: Int, rows: Int)
    fun terminalListenData(id: String, handler: (String) -> Unit): Int
    fun terminalListenExit(id: String, handler: (Int?) -> Unit): Int
    fun disposeListener(listenerId: Int)

    // git (read-only subset + the ADR-021 write tier — the ONLY sanctioned git mutations)
    suspend fun gitRepositorySummary(workspacePath: String): JsonObject
    suspend fun gitChanges(workspacePath: String, sourceId: String): JsonObject
    suspend fun gitDiff(workspacePath: String, path: String, staged: Boolean?): JsonObject
    suspend fun gitCommitGraph(workspacePath: String, maxCount: Int, skip: Int): JsonObject

    /**
     * ADR-021 (gaps-and-polish Phase 5D, A3.17+J1.3): the git write tier — stage/unstage
     * (reversible, one tap), commit/push/switch/createBranchAndSwitch (dialog-gated),
     * discardPaths (Tier C: the DESTRUCTIVE worktree write, typed-confirm DISCARD).
     * Shapes bundle-extracted (host/index.js ≈200265–202500); the branch result carries
     * the typed issue map (`issues:[{code: kebab-case, message, detail?, paths?}]`).
     * NEVER ported: pull/fetch/force-push/rebase/merge/reset/stash/identity-mutation.
     */
    suspend fun gitStagePaths(workspacePath: String, paths: List<String>)
    suspend fun gitUnstagePaths(workspacePath: String, paths: List<String>)
    suspend fun gitDiscardPaths(workspacePath: String, paths: List<String>, staged: Boolean)
    suspend fun gitCommit(workspacePath: String, message: String, paths: List<String>?, stagedOnly: Boolean): JsonObject
    suspend fun gitGenerateCommitMessage(workspacePath: String, includeUnstaged: Boolean): JsonObject
    suspend fun gitPush(workspacePath: String): JsonObject
    suspend fun gitSwitchBranch(workspacePath: String, targetBranchName: String): JsonObject
    suspend fun gitCreateBranchAndSwitch(workspacePath: String, branchName: String, startPoint: String?): JsonObject
    suspend fun gitLocalBranches(workspacePath: String): JsonObject
    suspend fun gitIdentity(workspacePath: String): JsonObject

    // file (read-only subset)
    suspend fun fileReaddir(path: String, includeHidden: Boolean = false): RpcValue
    suspend fun fileStat(path: String): JsonObject
    suspend fun fileReadText(path: String, offset: Long?, length: Long?): JsonObject
    suspend fun fileReadBytes(path: String, offset: Long, length: Long): ByteArray
    suspend fun fileBinaryPreview(path: String, maxBytes: Long?): JsonObject
    suspend fun fileSearch(rootPath: String, workspaceIdentity: String?, query: String, limit: Int): RpcValue

    // model-selection (read-only view)
    suspend fun modelSelectionView(): JsonObject

    /**
     * ADR-024 (gaps-and-polish Phase 5D, A3.20): the hooks-trust grant (host/index.js
     * ≈340201 → `workspaceHookTrustGrant`). Reachable ONLY from the review card's
     * confirmed path (the ADR's §26 T25 gates); never a free-form digest entry UI.
     */
    suspend fun grantWorkspaceHookTrust(workspacePath: String?, workspaceIdentity: String?, bundleDigest: String, hookDeclarationDigest: String): JsonObject

    /**
     * ADR-023 (gaps-and-polish Phase 5D, A3.19): the ONE sanctioned plugin write —
     * `setPluginEnabled {workspacePath, workspaceIdentity, pluginId, enabled, operationId?,
     * scope?}` (host/index.js ≈346624 → the CLI `plugins/setEnabled` word).
     * install/uninstall/update/configure stay desktop-only per the ADR (T24).
     */
    suspend fun setPluginEnabled(workspacePath: String?, workspaceIdentity: String?, pluginId: String, enabled: Boolean): JsonObject

    // C7 (Phase 3C): task-plane tool-output slice. The 3.14.5.7961 facade body is a
    // `return null` stub — a null result is DISTINCT here (the caller falls back to the
    // row's retained payload), so this returns JsonObject?, never throws on null.
    suspend fun taskSnapshotToolCallsSlice(taskId: String, toolCallIds: List<String>): JsonObject?
}

/** Transport + v4 plane — what the data/app layers consume (INV-2/INV-6). */
interface AppTransport : Transport, V4Plane

/**
 * Shared Channel/v4 engine. Owns the [ChannelSession], the subscription records,
 * the scoped-listen registry, topic-frame decode/assembly and every v4 +
 * phase-9 call shape. Both transports feed it Channel frames and delegate the
 * [V4Plane] methods to it. Transport-specific behavior enters ONLY through
 * [Host] (inbound emission, loss signalling, workspace target, counters).
 *
 * FIELD hardening history is preserved verbatim from RelayTransport (the live-
 * proven shapes and the FIELD-KILLER notes moved with the code, unchanged).
 */
internal class ChannelV4Plane(
    val channel: ChannelSession,
    private val host: Host,
    private val log: ZLog,
    private val clock: () -> Long,
    private val timeouts: Timeouts,
    private val clientKind: String = EndpointDescriptor.CLIENT_KIND_DEFAULT,
) {
    interface Host {
        fun endpointId(): String?
        fun workspaceTarget(): Pair<String?, String?>?
        /** Async inbound emission (transport owns the bounded flow + overflow policy). */
        fun emitInbound(frame: InboundFrame)
        /** Wire-version mismatch — transport kills the connection (§39.3 hard gate). */
        fun onWireVersionMismatch(message: String?)
        /** Counted data fault: serialization-fault emit + INV-8 counter. */
        fun onCountedFault(reason: String)
        /** Serialization-fault emit only (wire sound, payload unknown — INV-8 degrade). */
        fun onSerializationFault(reason: String)
        fun onV4Call()
        fun connectionId(): String?
        fun boundLog(): ZLog.Bound
    }

    data class Timeouts(
        val channelInitTimeoutMs: Long,
        val v4CallTimeoutMs: Long,
        val bridgeHandshakeTimeoutMs: Long,
        val controlRequestTimeoutMs: Long,
        val authTimeoutMs: Long,
    )

    fun bound(): ZLog.Bound = host.boundLog()

    // ---------------------------------------------------------------- topic decode

    private val v4Assembler = LogicalFrameAssembler(clock = clock)

    /** Active v4 subscriptions for recover(): topic → record (lastSeq = -1 → no watermark). */
    data class SubscriptionRecord(val subscriptionId: String, val logEpoch: String, val lastSeq: Long)
    val subscriptions = LinkedHashMap<String, SubscriptionRecord>()

    /** Conversation-topic listen scopes already registered on the CURRENT channel. */
    private val listenedScopes = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /** Workspace targets seen at subscribe time (session → §7.11 identity key / path). */
    private val subscriptionWorkspaces = HashMap<String, Pair<String?, String?>>()

    fun resetForNewConnection() {
        synchronized(subscriptions) { subscriptions.clear() }
        listenedScopes.clear()
        synchronized(subscriptionWorkspaces) { subscriptionWorkspaces.clear() }
    }

    fun handleTopicWireFrame(obj: JsonObject) {
        // Shape disambiguation (FIELD-KILLER #3): the arg is the TopicWireFrame when it
        // carries wireVersion; the wrapped {frame: topicFrame} shape is unwrapped;
        // anything else decodes as-is (mock/legacy direct shape).
        val wireObj: JsonObject = when {
            obj["wireVersion"] != null -> obj
            (obj["frame"] as? JsonObject)?.get("wireVersion") != null -> obj["frame"] as JsonObject
            else -> obj
        }
        val wire = try {
            TopicWireFrame.decode(wireObj)
        } catch (e: V4WireVersionException) {
            host.onWireVersionMismatch(e.message)
            return
        } catch (e: ProtocolException) {
            // FIELD POLICY (round 12): a malformed TOPIC frame is a data fault, not a
            // transport fault — surface + drop it (INV-8). Tearing the connection down
            // here turned a single bad snapshot into an endless reconnect loop.
            host.onCountedFault("topic frame fault (counted): ${e.message?.take(80)}")
            return
        }
        val assembled = try {
            v4Assembler.accept(wire)
        } catch (e: ProtocolException) {
            host.onCountedFault("assembly fault (counted): ${e.message?.take(80)}")
            return
        } ?: return
        val frame = try {
            TopicFrame.decode(assembled.frame)
        } catch (e: ProtocolException) {
            // Wire sound, payload shape unknown → degrade (INV-8), never merge.
            host.onSerializationFault(e.message ?: "topic frame decode")
            return
        }
        synchronized(subscriptions) {
            subscriptions[frame.topic]?.let { rec ->
                subscriptions[frame.topic] = rec.copy(lastSeq = frame.toSeq)
            }
        }
        host.emitInbound(
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

    // ---------------------------------------------------------------- scoped listen

    /**
     * FIELD FIX (round 3) + FIELD-KILLER #5 (2026-09-30, vc10): the EventListen ARG is
     * the WORKSPACE SCOPE that routes the zcode-agent channel on production; the scope
     * routes TOPIC FRAMES TOO — a scoped listener is attached PER WORKSPACE before
     * subscribing into it, or initial snapshots route to no listener.
     */
    fun ensureConversationListen(workspacePath: String?, workspaceIdentity: String?) {
        // A BLANK scope attaches the channel to no workspace — the desktop then routes
        // nothing to it AND (mock-observed) it deregisters the prior scoped listen.
        if (workspacePath.isNullOrBlank()) return
        // Path-only key: the desktop routes by workspacePath; registering the SAME path
        // with a different workspaceIdentity created TWO listeners → the snapshot fired
        // to both (field: gateway.frame ×2-×5).
        val key = workspacePath
        if (!listenedScopes.add(key)) return
        val scopeJson = buildJsonObject {
            put("workspacePath", JsonPrimitive(workspacePath))
            workspaceIdentity?.let { put("workspaceIdentity", JsonPrimitive(it)) }
        }
        channel.listen("zcode-agent", "onDynamicConversationFrame", RpcValue.Obj(scopeJson)) { args ->
            val obj = (args as? RpcValue.Obj)?.json as? JsonObject ?: return@listen
            handleTopicWireFrame(obj)
        }
    }

    // ---------------------------------------------------------------- calls

    suspend fun channelReady(timeoutMs: Long) {
        try {
            channel.awaitInitialize(timeoutMs)
        } catch (e: ChannelSession.Failure.TimedOut) {
            throw TransportFailure(ErrorMapping.timeout("channel initialize", timeoutMs))
        } catch (e: ChannelSession.Failure) {
            throw TransportFailure(ErrorMapping.protocolFault(e.message ?: "channel failure"))
        }
    }

    /**
     * Channel call with the LIVE-PROVEN ProxyChannel convention (Phase 1 finding 5):
     * Promise-call args are wrapped in an ARRAY; a bare object arrives as undefined.
     */
    suspend fun v4Call(name: String, arg: RpcValue): RpcValue {
        val started = clock()
        host.onV4Call()
        bound().debug("v4.call", "$name begin")
        return try {
            val raw = channel.call("zcode-agent", name, RpcValue.Arr(listOf(arg)), timeouts.v4CallTimeoutMs)
            bound().debug("v4.call", "$name ok in ${clock() - started}ms")
            raw
        } catch (e: ChannelSession.Failure.TimedOut) {
            bound().warn("v4.call", "$name TIMEOUT after ${clock() - started}ms")
            throw TransportFailure(ErrorMapping.timeout(name, timeouts.v4CallTimeoutMs))
        } catch (e: ChannelSession.Failure.ChannelError) {
            // Server-side fault (fault.* namespace) — task-class rejection, user-retry.
            bound().warn("v4.call", "$name channel error: ${e.detail.take(100)}")
            throw TransportFailure(ErrorMapping.taskRejected(e.detail))
        } catch (e: ChannelSession.Failure) {
            bound().warn("v4.call", "$name failed: ${e.message?.take(100)}")
            throw TransportFailure(ErrorMapping.protocolFault(e.message ?: "channel call failed"))
        }
    }

    suspend fun helloV4(): Hello {
        channelReady(timeouts.channelInitTimeoutMs)
        bound().debug("v4.call", "helloConversationV4 begin")
        val raw = try {
            channel.call("zcode-agent", "helloConversationV4", RpcValue.Arr(emptyList()), timeouts.bridgeHandshakeTimeoutMs)
        } catch (e: ChannelSession.Failure.TimedOut) {
            throw TransportFailure(ErrorMapping.timeout("helloConversationV4", timeouts.bridgeHandshakeTimeoutMs))
        } catch (e: ChannelSession.Failure) {
            throw TransportFailure(ErrorMapping.protocolFault(e.message ?: "hello failed"))
        }
        val obj = (raw as? RpcValue.Obj)?.json as? JsonObject
            ?: throw TransportFailure(ErrorMapping.protocolFault("hello response not an object"))
        return try {
            Hello.decode(obj)
        } catch (e: V4WireVersionException) {
            throw TransportFailure(ErrorMapping.versionIncompatible(e.message ?: "wire version"))
        } catch (e: ProtocolException) {
            throw TransportFailure(ErrorMapping.protocolFault(e.message ?: "hello decode"))
        }
    }

    suspend fun initializeV4(endpointId: String, appVersion: String) {
        val clientHello = ClientHello(
            clientId = endpointId,
            clientKind = clientKind,
            appVersion = appVersion,
        )
        v4Call("initializeConversationV4", RpcValue.Obj(clientHello.encode()))
    }

    /**
     * The connect-time handshake: scoped listen FIRST (the scope gates the whole
     * conversation plane on production — calls before it are silenced), then
     * channelReady → hello → clientHello initialize. Returns the decoded hello.
     */
    suspend fun helloAndInitialize(endpointId: String, appVersion: String): Hello {
        val target = host.workspaceTarget()
        ensureConversationListen(target?.first, target?.second)
        val hello = helloV4()
        connectionId = hello.connectionId
        initializeV4(endpointId, appVersion)
        return hello
    }

    suspend fun subscribeSession(
        sessionId: String,
        visibility: String?,
        workspacePath: String?,
        workspaceIdentity: String?,
    ): SubscribeAck {
        val target = host.workspaceTarget()
        val params = buildJsonObject {
            put("workspacePath", JsonPrimitive(workspacePath ?: target?.first ?: ""))
            (workspaceIdentity ?: target?.second)?.let { put("workspaceIdentity", JsonPrimitive(it)) }
            put("sessionId", JsonPrimitive(sessionId))
            visibility?.let { put("visibility", JsonPrimitive(it)) }
        }
        val raw = v4Call("subscribeConversationV4", RpcValue.Obj(params))
        val obj = (raw as? RpcValue.Obj)?.json as? JsonObject
            ?: throw TransportFailure(ErrorMapping.protocolFault("subscribe result not an object"))
        val ackObj = obj["ack"] as? JsonObject
            ?: throw TransportFailure(ErrorMapping.protocolFault("subscribe ack missing"))
        val ack = SubscribeAck.decode(ackObj)
        synchronized(subscriptions) {
            subscriptions[V4Topics.conversation(sessionId)] = SubscriptionRecord(ack.subscriptionId, ack.logEpoch, -1)
        }
        synchronized(subscriptionWorkspaces) {
            subscriptionWorkspaces[sessionId] = workspacePath to (workspaceIdentity ?: target?.second)
        }
        return ack
    }

    suspend fun rowsRange(sessionId: String, beforeRowId: Long?, limit: Int): RowsRangeResult {
        val target = host.workspaceTarget()
        bound().debug("v4.call", "rowsRange session=$sessionId before=$beforeRowId limit=$limit")
        val params = buildJsonObject {
            put("workspacePath", JsonPrimitive(target?.first ?: ""))
            target?.second?.let { put("workspaceIdentity", JsonPrimitive(it)) }
            put("sessionId", JsonPrimitive(sessionId))
            beforeRowId?.let { put("beforeRowId", JsonPrimitive(it)) }
            put("limit", JsonPrimitive(limit))
        }
        val raw = v4Call("conversationRowsRangeV4", RpcValue.Obj(params))
        val obj = (raw as? RpcValue.Obj)?.json as? JsonObject
            ?: throw TransportFailure(ErrorMapping.protocolFault("rowsRange result not an object"))
        return RowsRangeResult.decode(obj)
    }

    suspend fun subscribeWorkspaceConfig(
        workspacePath: String?,
        workspaceIdentity: String?,
    ): SubscribeAck {
        val target = host.workspaceTarget()
        val path = workspacePath ?: target?.first
        val identity = workspaceIdentity ?: target?.second
        // §7.11 identity key — the desktop's resolveWorkspaceKey (identity ?: path).
        val workspaceKey = identity?.trim()?.takeIf { it.isNotEmpty() } ?: path
            ?: throw TransportFailure(ErrorMapping.protocolFault("workspace-config subscribe without a workspace target"))
        val topic = V4Topics.workspaceConfig(workspaceKey)
        bound().debug("v4.call", "subscribeWorkspaceConfig topic=$topic")
        val params = buildJsonObject {
            put("workspacePath", JsonPrimitive(path ?: ""))
            identity?.let { put("workspaceIdentity", JsonPrimitive(identity)) }
            put("topic", JsonPrimitive(topic))
        }
        val raw = v4Call("subscribeConversationV4", RpcValue.Obj(params))
        val obj = (raw as? RpcValue.Obj)?.json as? JsonObject
            ?: throw TransportFailure(ErrorMapping.protocolFault("subscribe result not an object"))
        val ackObj = obj["ack"] as? JsonObject
            ?: throw TransportFailure(ErrorMapping.protocolFault("subscribe ack missing"))
        val ack = SubscribeAck.decode(ackObj)
        // Record like a session subscription so recover() knows to re-subscribe the
        // topic after a reconnect (the subscription lives in the CLI process memory).
        synchronized(subscriptions) {
            subscriptions[topic] = SubscriptionRecord(ack.subscriptionId, ack.logEpoch, -1)
        }
        return ack
    }

    suspend fun resyncSession(
        sessionId: String,
        base: Pair<String, Long>?,
        forceSnapshot: Boolean,
        workspacePath: String?,
        workspaceIdentity: String?,
    ): SubscribeAck {
        val target = host.workspaceTarget()
        val params = buildJsonObject {
            put("workspacePath", JsonPrimitive(workspacePath ?: target?.first ?: ""))
            (workspaceIdentity ?: target?.second)?.let { put("workspaceIdentity", JsonPrimitive(it)) }
            put("sessionId", JsonPrimitive(sessionId))
            if (base == null) put("base", kotlinx.serialization.json.JsonNull) else put("base", buildJsonObject {
                put("logEpoch", JsonPrimitive(base.first))
                put("seq", JsonPrimitive(base.second))
            })
            if (forceSnapshot) put("forceSnapshot", JsonPrimitive(true))
        }
        val raw = v4Call("resyncConversationV4", RpcValue.Obj(params))
        val obj = (raw as? RpcValue.Obj)?.json as? JsonObject
            ?: throw TransportFailure(ErrorMapping.protocolFault("resync result not an object"))
        val ackObj = obj["ack"] as? JsonObject
            ?: throw TransportFailure(ErrorMapping.protocolFault("resync ack missing"))
        val ack = SubscribeAck.decode(ackObj)
        // FIELD (2026-10-04, vc32): the resync ack REPLACES the session's subscription
        // record exactly like a subscribe ack — the old code left the stale
        // subscriptionId/epoch in place, so post-resync frame routing and escalation
        // base comparisons ran against a generation the server had already retired.
        synchronized(subscriptions) {
            subscriptions[V4Topics.conversation(sessionId)] = SubscriptionRecord(ack.subscriptionId, ack.logEpoch, -1)
        }
        synchronized(subscriptionWorkspaces) {
            subscriptionWorkspaces[sessionId] = workspacePath to (workspaceIdentity ?: target?.second)
        }
        return ack
    }

    suspend fun sendConversationCommand(
        envelope: CommandEnvelope,
        workspacePath: String?,
        workspaceIdentity: String?,
    ): CommandAck {
        val fromSubscription = envelope.sessionId?.let { synchronized(subscriptionWorkspaces) { subscriptionWorkspaces[it] } }
        val target = host.workspaceTarget()
        val wp = workspacePath ?: fromSubscription?.first ?: target?.first
        val wi = workspaceIdentity ?: fromSubscription?.second ?: target?.second
        val arg = buildJsonObject {
            wp?.let { put("workspacePath", JsonPrimitive(it)) }
            wi?.let { put("workspaceIdentity", JsonPrimitive(it)) }
            put("envelope", envelope.encode())
        }
        val raw = v4Call("sendConversationCommandV4", RpcValue.Obj(arg))
        val obj = (raw as? RpcValue.Obj)?.json as? JsonObject
            ?: throw TransportFailure(ErrorMapping.protocolFault("command ack not an object"))
        return CommandAck.decode(obj)
    }

    fun subscriptionWorkspace(sessionId: String): Pair<String?, String?>? =
        synchronized(subscriptionWorkspaces) { subscriptionWorkspaces[sessionId] }

    fun subscriptionEpoch(sessionId: String): String? =
        synchronized(subscriptions) { subscriptions[V4Topics.conversation(sessionId)]?.logEpoch }

    // ---------------------------------------------------------------- Phase 9 read surfaces
    // docs/protocol/v4-wire.md §10 + docs/protocol/phase9-channels.md. READ-ONLY: the
    // terminal surface deliberately exposes NO write/resize and the git/file surfaces
    // expose no mutation methods (INV-9 — the typed surface is the enforcement).

    suspend fun conversationFileChanges(
        sessionId: String,
        targetRowId: Long,
        targetEntityId: String,
        baseRevision: Long,
        baseLogEpoch: String,
        workspacePath: String?,
        workspaceIdentity: String?,
    ): JsonObject {
        val target = host.workspaceTarget()
        val params = buildJsonObject {
            put("workspacePath", JsonPrimitive(workspacePath ?: target?.first ?: ""))
            (workspaceIdentity ?: target?.second)?.let { put("workspaceIdentity", JsonPrimitive(it)) }
            put("sessionId", JsonPrimitive(sessionId))
            put("target", buildJsonObject {
                put("rowId", JsonPrimitive(targetRowId))
                put("entityId", JsonPrimitive(targetEntityId))
            })
            put("baseRevision", JsonPrimitive(baseRevision))
            put("baseLogEpoch", JsonPrimitive(baseLogEpoch))
        }
        return v4CallObject("conversationFileChangesV4", params)
    }

    private fun v4ConnectionIdOrThrow(): String =
        host.connectionId() ?: throw TransportFailure(ErrorMapping.internal("no v4 connectionId (not connected)"))

    suspend fun attachmentBegin(
        uploadId: String,
        sessionId: String,
        fileName: String,
        mime: String,
        totalBytes: Long,
        totalChunks: Int,
        checksum: String,
        workspacePath: String?,
        workspaceIdentity: String?,
    ): JsonObject {
        val target = host.workspaceTarget()
        val params = buildJsonObject {
            put("workspacePath", JsonPrimitive(workspacePath ?: target?.first ?: ""))
            (workspaceIdentity ?: target?.second)?.let { put("workspaceIdentity", JsonPrimitive(it)) }
            put("connectionId", JsonPrimitive(v4ConnectionIdOrThrow()))
            put("uploadId", JsonPrimitive(uploadId))
            put("sessionId", JsonPrimitive(sessionId))
            put("fileName", JsonPrimitive(fileName))
            put("mime", JsonPrimitive(mime))
            put("totalBytes", JsonPrimitive(totalBytes))
            put("totalChunks", JsonPrimitive(totalChunks))
            put("checksum", JsonPrimitive(checksum))
        }
        return v4CallObject("attachmentBeginV4", params)
    }

    suspend fun attachmentChunk(
        uploadId: String,
        sessionId: String,
        chunkIndex: Int,
        dataBase64: String,
        workspacePath: String?,
        workspaceIdentity: String?,
    ): JsonObject {
        val target = host.workspaceTarget()
        val params = buildJsonObject {
            put("workspacePath", JsonPrimitive(workspacePath ?: target?.first ?: ""))
            (workspaceIdentity ?: target?.second)?.let { put("workspaceIdentity", JsonPrimitive(it)) }
            put("connectionId", JsonPrimitive(v4ConnectionIdOrThrow()))
            put("uploadId", JsonPrimitive(uploadId))
            put("sessionId", JsonPrimitive(sessionId))
            put("chunkIndex", JsonPrimitive(chunkIndex))
            put("dataBase64", JsonPrimitive(dataBase64))
        }
        return v4CallObject("attachmentChunkV4", params)
    }

    private suspend fun attachmentTerminal(method: String, uploadId: String, sessionId: String): JsonObject {
        val target = host.workspaceTarget()
        val params = buildJsonObject {
            put("workspacePath", JsonPrimitive(target?.first ?: ""))
            target?.second?.let { put("workspaceIdentity", JsonPrimitive(it)) }
            put("connectionId", JsonPrimitive(v4ConnectionIdOrThrow()))
            put("uploadId", JsonPrimitive(uploadId))
            put("sessionId", JsonPrimitive(sessionId))
        }
        return v4CallObject(method, params)
    }

    suspend fun attachmentCommit(uploadId: String, sessionId: String): JsonObject =
        attachmentTerminal("attachmentCommitV4", uploadId, sessionId)

    suspend fun attachmentAbort(uploadId: String, sessionId: String): JsonObject =
        attachmentTerminal("attachmentAbortV4", uploadId, sessionId)

    suspend fun attachmentRead(
        sessionId: String,
        ref: String,
        offset: Long,
        limit: Int,
        targetRowId: Long?,
        targetEntityId: String?,
        attachmentIndex: Int?,
        workspacePath: String?,
        workspaceIdentity: String?,
    ): JsonObject {
        val target = host.workspaceTarget()
        val params = buildJsonObject {
            put("workspacePath", JsonPrimitive(workspacePath ?: target?.first ?: ""))
            (workspaceIdentity ?: target?.second)?.let { put("workspaceIdentity", JsonPrimitive(it)) }
            put("sessionId", JsonPrimitive(sessionId))
            put("ref", JsonPrimitive(ref))
            if (targetRowId != null && targetEntityId != null) {
                put("target", buildJsonObject {
                    put("rowId", JsonPrimitive(targetRowId))
                    put("entityId", JsonPrimitive(targetEntityId))
                })
            }
            attachmentIndex?.let { put("attachmentIndex", JsonPrimitive(it)) }
            put("offset", JsonPrimitive(offset))
            put("limit", JsonPrimitive(limit))
        }
        return v4CallObject("attachmentReadV4", params)
    }

    /** v4Call variant returning the decoded JsonObject (the Phase 9 calls all return objects). */
    private suspend fun v4CallObject(name: String, params: JsonObject): JsonObject {
        val raw = v4Call(name, RpcValue.Obj(params))
        return (raw as? RpcValue.Obj)?.json as? JsonObject
            ?: throw TransportFailure(ErrorMapping.protocolFault("$name result not an object"))
    }

    /**
     * Generic service-channel call (terminal/git/file/model-selection — phase9-channels.md).
     * Same error mapping as v4Call; a channel REFUSAL (unknown channel / role gate) is a
     * server-spoke rejection the feature layers translate into their §13.3 disabled state.
     */
    suspend fun channelCall(
        channelName: String,
        method: String,
        arg: RpcValue,
        timeoutMs: Long,
    ): RpcValue {
        host.onV4Call()
        bound().debug("channel.call", "$channelName.$method begin")
        return try {
            val raw = channel.call(channelName, method, arg, timeoutMs)
            bound().debug("channel.call", "$channelName.$method ok")
            raw
        } catch (e: ChannelSession.Failure.TimedOut) {
            bound().warn("channel.call", "$channelName.$method TIMEOUT")
            throw TransportFailure(ErrorMapping.timeout("$channelName.$method", timeoutMs))
        } catch (e: ChannelSession.Failure.ChannelError) {
            bound().warn("channel.call", "$channelName.$method refused: ${e.detail.take(100)}")
            throw TransportFailure(ErrorMapping.taskRejected(e.detail))
        } catch (e: ChannelSession.Failure) {
            bound().warn("channel.call", "$channelName.$method failed: ${e.message?.take(100)}")
            throw TransportFailure(ErrorMapping.protocolFault(e.message ?: "channel call failed"))
        }
    }

    // ---------------------------------------------------------------- Phase 3 read-only viewer surfaces
    // v4-wire.md §12. Read-only R-class reads on §13.3-sanctioned channels — the
    // typed surface exposes NO write method (INV-9): no automation create/update/
    // delete/enable, no plugin install/uninstall/enable, no skills setEnabled, no
    // mcp mutations (§13.3 fallbacks apply via TransportFailure at the port layer).

    suspend fun appUsageStats(range: String, timeZone: String?): JsonObject {
        val params = buildJsonObject {
            put("range", JsonPrimitive(range))
            timeZone?.let { put("timeZone", JsonPrimitive(it)) }
        }
        return v4CallObject("getAppUsageStats", params)
    }

    /** Top-level ARRAY results normalize to {key:[…]} for the shared decoders. */
    private suspend fun v4CallArrayNormalized(name: String, params: JsonObject, key: String): JsonObject =
        when (val raw = v4Call(name, RpcValue.Obj(params))) {
            is RpcValue.Arr -> buildJsonObject {
                put(key, JsonArray(raw.items.map { el ->
                    (el as? RpcValue.Obj)?.json as? JsonObject ?: JsonObject(emptyMap())
                }))
            }
            is RpcValue.Obj -> (raw.json as? JsonObject)?.takeIf { it[key] is JsonArray }
                ?: throw TransportFailure(ErrorMapping.protocolFault("$name result missing $key"))
            else -> throw TransportFailure(ErrorMapping.protocolFault("$name result not a list"))
        }

    suspend fun listAutomations(workspacePath: String?, workspaceIdentity: String?): JsonObject {
        val target = host.workspaceTarget()
        val params = buildJsonObject {
            put("workspacePath", JsonPrimitive(workspacePath ?: target?.first ?: ""))
            (workspaceIdentity ?: target?.second)?.let { put("workspaceIdentity", JsonPrimitive(it)) }
        }
        return v4CallArrayNormalized("listAutomations", params, "automations")
    }

    // ---- FIELD (2026-10-12): automation CRUD — sibling v4 methods to
    // listAutomations (the phone-reachable zcodeAgent surface). Params mirror
    // ZCodeAgentCreateAutomationParams (automation-types.ts:149-171).

    suspend fun createAutomation(
        workspacePath: String?,
        workspaceIdentity: String?,
        request: JsonObject,
    ): JsonObject {
        val target = host.workspaceTarget()
        val params = buildJsonObject {
            put("workspacePath", JsonPrimitive(workspacePath ?: target?.first ?: ""))
            (workspaceIdentity ?: target?.second)?.let { put("workspaceIdentity", JsonPrimitive(it)) }
            request.forEach { (k, v) -> put(k, v) }
        }
        val raw = v4Call("createAutomation", RpcValue.Obj(params))
        return ((raw as? RpcValue.Obj)?.json as? kotlinx.serialization.json.JsonObject)
            ?: throw TransportFailure(ErrorMapping.protocolFault("createAutomation result not an object"))
    }

    suspend fun updateAutomation(
        workspacePath: String?,
        workspaceIdentity: String?,
        automationId: String,
        request: JsonObject,
    ): JsonObject {
        val target = host.workspaceTarget()
        val params = buildJsonObject {
            put("workspacePath", JsonPrimitive(workspacePath ?: target?.first ?: ""))
            (workspaceIdentity ?: target?.second)?.let { put("workspaceIdentity", JsonPrimitive(it)) }
            put("automationId", JsonPrimitive(automationId))
            request.forEach { (k, v) -> put(k, v) }
        }
        val raw = v4Call("updateAutomation", RpcValue.Obj(params))
        return ((raw as? RpcValue.Obj)?.json as? kotlinx.serialization.json.JsonObject)
            ?: throw TransportFailure(ErrorMapping.protocolFault("updateAutomation result not an object"))
    }

    suspend fun deleteAutomation(workspacePath: String?, workspaceIdentity: String?, automationId: String) {
        val target = host.workspaceTarget()
        v4Call(
            "deleteAutomation",
            RpcValue.Obj(buildJsonObject {
                put("workspacePath", JsonPrimitive(workspacePath ?: target?.first ?: ""))
                (workspaceIdentity ?: target?.second)?.let { put("workspaceIdentity", JsonPrimitive(it)) }
                put("automationId", JsonPrimitive(automationId))
            }),
        )
    }

    suspend fun setAutomationEnabled(
        workspacePath: String?,
        workspaceIdentity: String?,
        automationId: String,
        enabled: Boolean,
    ) {
        val target = host.workspaceTarget()
        v4Call(
            "setAutomationEnabled",
            RpcValue.Obj(buildJsonObject {
                put("workspacePath", JsonPrimitive(workspacePath ?: target?.first ?: ""))
                (workspaceIdentity ?: target?.second)?.let { put("workspaceIdentity", JsonPrimitive(it)) }
                put("automationId", JsonPrimitive(automationId))
                put("enabled", JsonPrimitive(enabled))
            }),
        )
    }

    suspend fun runAutomationNow(
        workspacePath: String?,
        workspaceIdentity: String?,
        automationId: String,
    ): JsonObject {
        val target = host.workspaceTarget()
        val raw = v4Call(
            "runAutomationNow",
            RpcValue.Obj(buildJsonObject {
                put("workspacePath", JsonPrimitive(workspacePath ?: target?.first ?: ""))
                (workspaceIdentity ?: target?.second)?.let { put("workspaceIdentity", JsonPrimitive(it)) }
                put("automationId", JsonPrimitive(automationId))
            }),
        )
        return ((raw as? RpcValue.Obj)?.json as? kotlinx.serialization.json.JsonObject)
            ?: throw TransportFailure(ErrorMapping.protocolFault("runAutomationNow result not an object"))
    }

    // ---- Phase 4B (A3.2): run history — ZCodeAgentAutomationIdParams / DeleteAutomationRunParams.

    suspend fun listAutomationRuns(workspacePath: String?, workspaceIdentity: String?, automationId: String): JsonObject {
        val target = host.workspaceTarget()
        val params = buildJsonObject {
            put("workspacePath", JsonPrimitive(workspacePath ?: target?.first ?: ""))
            (workspaceIdentity ?: target?.second)?.let { put("workspaceIdentity", JsonPrimitive(it)) }
            put("automationId", JsonPrimitive(automationId))
        }
        return v4CallArrayNormalized("listAutomationRuns", params, "runs")
    }

    suspend fun deleteAutomationRun(workspacePath: String?, workspaceIdentity: String?, runId: String) {
        val target = host.workspaceTarget()
        v4Call(
            "deleteAutomationRun",
            RpcValue.Obj(buildJsonObject {
                put("workspacePath", JsonPrimitive(workspacePath ?: target?.first ?: ""))
                (workspaceIdentity ?: target?.second)?.let { put("workspaceIdentity", JsonPrimitive(it)) }
                put("runId", JsonPrimitive(runId))
            }),
        )
    }

    suspend fun restartAutomation(workspacePath: String?, workspaceIdentity: String?, automationId: String) {
        val target = host.workspaceTarget()
        v4Call(
            "restartAutomation",
            RpcValue.Obj(buildJsonObject {
                put("workspacePath", JsonPrimitive(workspacePath ?: target?.first ?: ""))
                (workspaceIdentity ?: target?.second)?.let { put("workspaceIdentity", JsonPrimitive(it)) }
                put("automationId", JsonPrimitive(automationId))
            }),
        )
    }

    suspend fun commandsList(workspacePath: String?, workspaceIdentity: String?): JsonObject {
        val target = host.workspaceTarget()
        return when (val raw = channelCall("commands", "list", RpcValue.Arr(listOf(RpcValue.Obj(buildJsonObject {
            put("workspacePath", JsonPrimitive(workspacePath ?: target?.first ?: ""))
            (workspaceIdentity ?: target?.second)?.let { put("workspaceIdentity", JsonPrimitive(it)) }
        }))), timeouts.v4CallTimeoutMs)) {
            is RpcValue.Obj -> raw.json as JsonObject
            is RpcValue.Arr -> buildJsonObject { put("commands", JsonArray(raw.items.map { (it as? RpcValue.Obj)?.json as? JsonObject ?: JsonObject(emptyMap()) })) }
            else -> throw TransportFailure(ErrorMapping.protocolFault("commands.list result not an object"))
        }
    }

    suspend fun subagentsList(workspacePath: String?, workspaceIdentity: String?, provider: String?): JsonObject {
        val target = host.workspaceTarget()
        return when (val raw = channelCall("subagents", "list", RpcValue.Arr(listOf(RpcValue.Obj(buildJsonObject {
            put("workspacePath", JsonPrimitive(workspacePath ?: target?.first ?: ""))
            (workspaceIdentity ?: target?.second)?.let { put("workspaceIdentity", JsonPrimitive(it)) }
            provider?.let { put("provider", JsonPrimitive(it)) }
        }))), timeouts.v4CallTimeoutMs)) {
            is RpcValue.Obj -> raw.json as JsonObject
            is RpcValue.Arr -> buildJsonObject { put("agents", JsonArray(raw.items.map { (it as? RpcValue.Obj)?.json as? JsonObject ?: JsonObject(emptyMap()) })) }
            else -> throw TransportFailure(ErrorMapping.protocolFault("subagents.list result not an object"))
        }
    }

    suspend fun setThoughtLevel(workspacePath: String?, workspaceIdentity: String?, sessionId: String, thoughtLevel: String, expectedRevision: Long?): JsonObject {
        val target = host.workspaceTarget()
        val raw = v4Call("setThoughtLevel", RpcValue.Obj(buildJsonObject {
            put("workspacePath", JsonPrimitive(workspacePath ?: target?.first ?: ""))
            (workspaceIdentity ?: target?.second)?.let { put("workspaceIdentity", JsonPrimitive(it)) }
            put("sessionId", JsonPrimitive(sessionId))
            put("thoughtLevel", JsonPrimitive(thoughtLevel))
            expectedRevision?.let { put("expectedRevision", JsonPrimitive(it)) }
            put("persistAsWorkspaceLastUsed", JsonPrimitive(true))
        }))
        return ((raw as? RpcValue.Obj)?.json as? kotlinx.serialization.json.JsonObject)
            ?: throw TransportFailure(ErrorMapping.protocolFault("setThoughtLevel result not an object"))
    }

    suspend fun createScratchWorkspace(name: String): JsonObject {
        val raw = v4Call("createScratchWorkspace", RpcValue.Obj(buildJsonObject {
            put("name", JsonPrimitive(name))
        }))
        return ((raw as? RpcValue.Obj)?.json as? kotlinx.serialization.json.JsonObject)
            ?: throw TransportFailure(ErrorMapping.protocolFault("createScratchWorkspace result not an object"))
    }

    suspend fun offPeakTaskList(): JsonObject =
        when (val raw = channelCall("off-peak-task", "list", RpcValue.Arr(emptyList()), timeouts.controlRequestTimeoutMs)) {
            is RpcValue.Arr -> buildJsonObject {
                put("tasks", JsonArray(raw.items.map { el ->
                    (el as? RpcValue.Obj)?.json as? JsonObject ?: JsonObject(emptyMap())
                }))
            }
            is RpcValue.Obj -> (raw.json as? JsonObject)?.takeIf { it["tasks"] is JsonArray }
                ?: throw TransportFailure(ErrorMapping.protocolFault("off-peak list result missing tasks"))
            else -> throw TransportFailure(ErrorMapping.protocolFault("off-peak list result not a list"))
        }

    suspend fun listPlugins(workspacePath: String?, workspaceIdentity: String?): JsonObject {
        val target = host.workspaceTarget()
        val params = buildJsonObject {
            put("workspacePath", JsonPrimitive(workspacePath ?: target?.first ?: ""))
            (workspaceIdentity ?: target?.second)?.let { put("workspaceIdentity", JsonPrimitive(it)) }
        }
        return v4CallObject("listPlugins", params)
    }

    suspend fun grantWorkspaceHookTrust(workspacePath: String?, workspaceIdentity: String?, bundleDigest: String, hookDeclarationDigest: String): JsonObject {
        val target = host.workspaceTarget()
        val params = buildJsonObject {
            put("workspacePath", JsonPrimitive(workspacePath ?: target?.first ?: ""))
            (workspaceIdentity ?: target?.second)?.let { put("workspaceIdentity", JsonPrimitive(it)) }
            put("bundleDigest", JsonPrimitive(bundleDigest))
            put("hookDeclarationDigest", JsonPrimitive(hookDeclarationDigest))
        }
        return v4CallObject("grantWorkspaceHookTrust", params)
    }

    suspend fun setPluginEnabled(workspacePath: String?, workspaceIdentity: String?, pluginId: String, enabled: Boolean): JsonObject {
        val target = host.workspaceTarget()
        val params = buildJsonObject {
            put("workspacePath", JsonPrimitive(workspacePath ?: target?.first ?: ""))
            (workspaceIdentity ?: target?.second)?.let { put("workspaceIdentity", JsonPrimitive(it)) }
            put("pluginId", JsonPrimitive(pluginId))
            put("enabled", JsonPrimitive(enabled))
        }
        return v4CallObject("setPluginEnabled", params)
    }

    suspend fun skillReferenceCatalog(
        workspacePath: String?,
        workspaceIdentity: String?,
        sessionId: String?,
    ): JsonObject {
        val target = host.workspaceTarget()
        val params = buildJsonObject {
            put("workspacePath", JsonPrimitive(workspacePath ?: target?.first ?: ""))
            (workspaceIdentity ?: target?.second)?.let { put("workspaceIdentity", JsonPrimitive(it)) }
            sessionId?.let { put("sessionId", JsonPrimitive(it)) }
        }
        return v4CallObject("getSkillReferenceCatalog", params)
    }

    suspend fun mcpServerStatuses(workspacePath: String?, workspaceIdentity: String?): JsonObject {
        val target = host.workspaceTarget()
        val params = buildJsonObject {
            put("workspacePath", JsonPrimitive(workspacePath ?: target?.first ?: ""))
            (workspaceIdentity ?: target?.second)?.let { put("workspaceIdentity", JsonPrimitive(it)) }
            put("mode", JsonPrimitive("status"))
        }
        return v4CallObject("listMcpServerStatuses", params)
    }

    // ---- Phase 4 coding-plan quota plane (ADR-020; v4-wire.md §13)
    // The `usage-stats` ProxyChannel (promise args ARRAY-wrapped, §7). The
    // entitlement read is the GAP-3 data source; the reset words land with
    // GAP-4 behind the typed-confirm gate (ADR-020: useCodingPlanReset is the
    // plane's ONE destructive-adjacent word).

    suspend fun usageEntitlementSnapshot(request: JsonObject = JsonObject(emptyMap())): JsonObject =
        when (
            val raw = channelCall(
                "usage-stats", "getEntitlementSnapshot",
                RpcValue.Arr(listOf(RpcValue.Obj(request))),
                timeouts.controlRequestTimeoutMs,
            )
        ) {
            is RpcValue.Obj -> (raw.json as? JsonObject)
                ?: throw TransportFailure(ErrorMapping.protocolFault("getEntitlementSnapshot result not an object"))
            else -> throw TransportFailure(ErrorMapping.protocolFault("getEntitlementSnapshot result not an object"))
        }

    /** Shared scope-request shape for the reset words (coding-plan-reset.ts). */
    private fun resetScopeParams(preferredProviderId: String, accountAccess: JsonObject): JsonObject =
        buildJsonObject {
            put("preferredProviderId", JsonPrimitive(preferredProviderId))
            put("accountAccess", accountAccess)
        }

    private suspend fun usageStatsObject(method: String, request: JsonObject): JsonObject =
        when (
            val raw = channelCall(
                "usage-stats", method, RpcValue.Arr(listOf(RpcValue.Obj(request))),
                timeouts.controlRequestTimeoutMs,
            )
        ) {
            is RpcValue.Obj -> (raw.json as? JsonObject)
                ?: throw TransportFailure(ErrorMapping.protocolFault("$method result not an object"))
            else -> throw TransportFailure(ErrorMapping.protocolFault("$method result not an object"))
        }

    suspend fun codingPlanResetStatus(preferredProviderId: String, accountAccess: JsonObject): JsonObject =
        usageStatsObject("getCodingPlanResetStatus", resetScopeParams(preferredProviderId, accountAccess))

    suspend fun codingPlanResetOpportunity(
        preferredProviderId: String,
        accountAccess: JsonObject,
        idempotencyKey: String,
    ): JsonObject = usageStatsObject(
        "requestCodingPlanResetOpportunity",
        buildJsonObject {
            resetScopeParams(preferredProviderId, accountAccess).forEach { (k, v) -> put(k, v) }
            put("idempotencyKey", JsonPrimitive(idempotencyKey))
        },
    )

    suspend fun codingPlanResetUse(
        preferredProviderId: String,
        accountAccess: JsonObject,
        idempotencyKey: String,
        resetType: String,
    ): JsonObject = usageStatsObject(
        "useCodingPlanReset",
        buildJsonObject {
            resetScopeParams(preferredProviderId, accountAccess).forEach { (k, v) -> put(k, v) }
            put("idempotencyKey", JsonPrimitive(idempotencyKey))
            put("resetType", JsonPrimitive(resetType))
        },
    )

    suspend fun codingPlanResetHistoryRead(preferredProviderId: String, accountAccess: JsonObject) {
        // Promise<void> on the desktop — the reply is deliberately unexamined.
        channelCall(
            "usage-stats", "markCodingPlanResetHistoryRead",
            RpcValue.Arr(listOf(RpcValue.Obj(resetScopeParams(preferredProviderId, accountAccess)))),
            timeouts.controlRequestTimeoutMs,
        )
    }

    // ---- ADR-021 git write tier (channel `git`; the workspace target is the resolved one)
    private suspend fun gitWrite(method: String, params: JsonObject): JsonObject =
        when (val raw = channelCall("git", method, RpcValue.Arr(listOf(RpcValue.Obj(params))), 60_000)) {
            is RpcValue.Obj -> raw.json as? JsonObject ?: JsonObject(emptyMap())
            is RpcValue.Undefined, null -> JsonObject(emptyMap())
            else -> throw TransportFailure(ErrorMapping.protocolFault("git.$method result not an object"))
        }

    suspend fun gitStagePaths(workspacePath: String, paths: List<String>) {
        gitWrite("stagePaths", buildJsonObject {
            put("workspacePath", JsonPrimitive(workspacePath)); put("paths", JsonArray(paths.map { JsonPrimitive(it) }))
        })
    }

    suspend fun gitUnstagePaths(workspacePath: String, paths: List<String>) {
        gitWrite("unstagePaths", buildJsonObject {
            put("workspacePath", JsonPrimitive(workspacePath)); put("paths", JsonArray(paths.map { JsonPrimitive(it) }))
        })
    }

    suspend fun gitDiscardPaths(workspacePath: String, paths: List<String>, staged: Boolean) {
        gitWrite("discardPaths", buildJsonObject {
            put("workspacePath", JsonPrimitive(workspacePath)); put("paths", JsonArray(paths.map { JsonPrimitive(it) }))
            put("staged", JsonPrimitive(staged))
        })
    }

    suspend fun gitCommit(workspacePath: String, message: String, paths: List<String>?, stagedOnly: Boolean): JsonObject =
        gitWrite("commit", buildJsonObject {
            put("workspacePath", JsonPrimitive(workspacePath)); put("message", JsonPrimitive(message))
            paths?.let { put("paths", JsonArray(it.map { p -> JsonPrimitive(p) })) }
            put("stagedOnly", JsonPrimitive(stagedOnly))
        })

    suspend fun gitGenerateCommitMessage(workspacePath: String, includeUnstaged: Boolean): JsonObject =
        gitWrite("generateCommitMessage", buildJsonObject {
            put("workspacePath", JsonPrimitive(workspacePath)); put("includeUnstaged", JsonPrimitive(includeUnstaged))
        })

    suspend fun gitPush(workspacePath: String): JsonObject =
        gitWrite("push", buildJsonObject { put("workspacePath", JsonPrimitive(workspacePath)) })

    suspend fun gitSwitchBranch(workspacePath: String, targetBranchName: String): JsonObject =
        gitWrite("switchBranch", buildJsonObject {
            put("workspacePath", JsonPrimitive(workspacePath)); put("targetBranchName", JsonPrimitive(targetBranchName))
        })

    suspend fun gitCreateBranchAndSwitch(workspacePath: String, branchName: String, startPoint: String?): JsonObject =
        gitWrite("createBranchAndSwitch", buildJsonObject {
            put("workspacePath", JsonPrimitive(workspacePath)); put("branchName", JsonPrimitive(branchName))
            startPoint?.let { put("startPoint", JsonPrimitive(it)) }
        })

    suspend fun gitLocalBranches(workspacePath: String): JsonObject =
        when (val raw = channelCall("git", "getLocalBranches", RpcValue.Arr(listOf(RpcValue.Obj(buildJsonObject { put("workspacePath", JsonPrimitive(workspacePath)) }))), 30_000)) {
            is RpcValue.Obj -> raw.json as? JsonObject ?: JsonObject(emptyMap())
            else -> throw TransportFailure(ErrorMapping.protocolFault("git.getLocalBranches result not an object"))
        }

    suspend fun gitIdentity(workspacePath: String): JsonObject =
        when (val raw = channelCall("git", "getIdentity", RpcValue.Arr(listOf(RpcValue.Obj(buildJsonObject { put("workspacePath", JsonPrimitive(workspacePath)) }))), 30_000)) {
            is RpcValue.Obj -> raw.json as? JsonObject ?: JsonObject(emptyMap())
            else -> throw TransportFailure(ErrorMapping.protocolFault("git.getIdentity result not an object"))
        }

    // ---- terminal (view-only: create/dispose/data/exit ONLY — INV-9, phase9-channels.md §1)

    suspend fun terminalCreate(cols: Int, rows: Int, cwd: String?): JsonObject {
        val params = buildJsonObject {
            put("cols", JsonPrimitive(cols))
            put("rows", JsonPrimitive(rows))
            cwd?.let { put("cwd", JsonPrimitive(it)) }
        }
        val raw = channelCall("terminal", "create", RpcValue.Arr(listOf(RpcValue.Obj(params))), timeouts.authTimeoutMs)
        return (raw as? RpcValue.Obj)?.json as? JsonObject
            ?: throw TransportFailure(ErrorMapping.protocolFault("terminal.create result not an object"))
    }

    suspend fun terminalWrite(id: String, data: String) {
        channelCall("terminal", "write", RpcValue.Arr(listOf(RpcValue.Obj(buildJsonObject {
            put("id", JsonPrimitive(id)); put("data", JsonPrimitive(data))
        }))), 30_000)
    }

    suspend fun terminalResize(id: String, cols: Int, rows: Int) {
        channelCall("terminal", "resize", RpcValue.Arr(listOf(RpcValue.Obj(buildJsonObject {
            put("id", JsonPrimitive(id)); put("cols", JsonPrimitive(cols)); put("rows", JsonPrimitive(rows))
        }))), 30_000)
    }

    suspend fun terminalDispose(id: String) {
        channelCall("terminal", "dispose", RpcValue.Arr(listOf(RpcValue.Obj(buildJsonObject {
            put("id", JsonPrimitive(id))
        }))), timeouts.controlRequestTimeoutMs)
    }

    /** Dynamic event listen (`onDynamicData(id)`) — arg passed RAW (proxy-channel.ts:127). */
    fun terminalListenData(id: String, handler: (String) -> Unit): Int =
        channel.listen("terminal", "onDynamicData", RpcValue.Str(id)) { value ->
            val text = (value as? RpcValue.Str)?.value
            if (text != null) handler(text)
        }

    fun terminalListenExit(id: String, handler: (Int?) -> Unit): Int =
        channel.listen("terminal", "onDynamicExit", RpcValue.Str(id)) { value ->
            handler((value as? RpcValue.Int)?.value?.toInt())
        }

    fun disposeListener(listenerId: Int) = channel.disposeListener(listenerId)

    // ---- git (read-only subset — phase9-channels.md §2)

    private suspend fun gitCall(method: String, params: JsonObject): JsonObject {
        val raw = channelCall("git", method, RpcValue.Arr(listOf(RpcValue.Obj(params))), timeouts.controlRequestTimeoutMs)
        return (raw as? RpcValue.Obj)?.json as? JsonObject
            ?: throw TransportFailure(ErrorMapping.protocolFault("git.$method result not an object"))
    }

    suspend fun gitRepositorySummary(workspacePath: String): JsonObject =
        gitCall("getRepositorySummary", buildJsonObject { put("workspacePath", JsonPrimitive(workspacePath)) })

    suspend fun gitChanges(workspacePath: String, sourceId: String): JsonObject {
        val raw = channelCall("git", "getChanges", RpcValue.Arr(listOf(RpcValue.Obj(buildJsonObject {
            put("workspacePath", JsonPrimitive(workspacePath))
            put("sourceId", JsonPrimitive(sourceId))
        }))), timeouts.controlRequestTimeoutMs)
        return when (raw) {
            is RpcValue.Obj -> raw.json as? JsonObject
                ?: throw TransportFailure(ErrorMapping.protocolFault("git.getChanges result not an object"))
            is RpcValue.Arr -> buildJsonObject {
                // getChanges returns a top-level ARRAY (GitFileChange[]) — normalize to
                // {items:[…]} for the shared decoder.
                put("items", JsonArray(raw.items.map { el ->
                    (el as? RpcValue.Obj)?.json as? JsonObject ?: JsonObject(emptyMap())
                }))
            }
            else -> throw TransportFailure(ErrorMapping.protocolFault("git.getChanges result not an object"))
        }
    }

    suspend fun gitDiff(workspacePath: String, path: String, staged: Boolean?): JsonObject =
        gitCall("getDiff", buildJsonObject {
            put("workspacePath", JsonPrimitive(workspacePath))
            put("path", JsonPrimitive(path))
            staged?.let { put("staged", JsonPrimitive(staged)) }
        })

    suspend fun gitCommitGraph(workspacePath: String, maxCount: Int, skip: Int): JsonObject =
        gitCall("getCommitGraph", buildJsonObject {
            put("workspacePath", JsonPrimitive(workspacePath))
            put("maxCount", JsonPrimitive(maxCount))
            put("skip", JsonPrimitive(skip))
        })

    // ---- file (read-only subset — phase9-channels.md §3)

    private suspend fun fileCall(method: String, params: JsonObject, timeoutMs: Long): RpcValue =
        channelCall("file", method, RpcValue.Arr(listOf(RpcValue.Obj(params))), timeoutMs)

    suspend fun fileReaddir(path: String, includeHidden: Boolean): RpcValue =
        fileCall("readdir", buildJsonObject {
            put("path", JsonPrimitive(path))
            put("includeHidden", JsonPrimitive(includeHidden))
        }, timeouts.controlRequestTimeoutMs)

    suspend fun fileStat(path: String): JsonObject {
        val raw = fileCall("stat", buildJsonObject { put("path", JsonPrimitive(path)) }, timeouts.controlRequestTimeoutMs)
        return (raw as? RpcValue.Obj)?.json as? JsonObject
            ?: throw TransportFailure(ErrorMapping.protocolFault("file.stat result not an object"))
    }

    suspend fun fileReadText(path: String, offset: Long?, length: Long?): JsonObject {
        val raw = fileCall("readTextFile", buildJsonObject {
            put("path", JsonPrimitive(path))
            offset?.let { put("offset", JsonPrimitive(it)) }
            length?.let { put("length", JsonPrimitive(it)) }
        }, timeouts.controlRequestTimeoutMs)
        return (raw as? RpcValue.Obj)?.json as? JsonObject
            ?: throw TransportFailure(ErrorMapping.protocolFault("file.readTextFile result not an object"))
    }

    /** Raw byte range (top-level binary fast path — protocol.ts readFileRange comment). */
    suspend fun fileReadBytes(path: String, offset: Long, length: Long): ByteArray {
        val raw = fileCall("readFileRange", buildJsonObject {
            put("path", JsonPrimitive(path)); put("offset", JsonPrimitive(offset)); put("length", JsonPrimitive(length))
        }, timeouts.controlRequestTimeoutMs)
        return when (raw) {
            is RpcValue.Buf -> raw.bytes
            is RpcValue.Str -> raw.value.toByteArray(Charsets.ISO_8859_1)
            else -> throw TransportFailure(ErrorMapping.protocolFault("file.readFileRange result not binary"))
        }
    }

    suspend fun fileBinaryPreview(path: String, maxBytes: Long?): JsonObject {
        val raw = fileCall("readBinaryPreview", buildJsonObject {
            put("path", JsonPrimitive(path))
            maxBytes?.let { put("maxBytes", JsonPrimitive(maxBytes)) }
        }, timeouts.controlRequestTimeoutMs)
        return (raw as? RpcValue.Obj)?.json as? JsonObject
            ?: throw TransportFailure(ErrorMapping.protocolFault("file.readBinaryPreview result not an object"))
    }

    suspend fun fileSearch(rootPath: String, workspaceIdentity: String?, query: String, limit: Int): RpcValue =
        fileCall("searchWorkspaceFiles", buildJsonObject {
            put("rootPath", JsonPrimitive(rootPath))
            workspaceIdentity?.let { put("workspaceIdentity", JsonPrimitive(it)) }
            put("query", JsonPrimitive(query))
            put("limit", JsonPrimitive(limit))
        }, timeouts.controlRequestTimeoutMs)

    // ---- model-selection (read-only view for the §19.5 picker — phase9-channels.md §4)

    suspend fun modelSelectionView(): JsonObject {
        val raw = channelCall("model-selection", "getView", RpcValue.Arr(emptyList()), timeouts.controlRequestTimeoutMs)
        return (raw as? RpcValue.Obj)?.json as? JsonObject
            ?: throw TransportFailure(ErrorMapping.protocolFault("model-selection.getView result not an object"))
    }

    // ---- C7 (Phase 3C): task-plane tool-output slice — phase3c notes; the shipped
    // facade answers null (stub), which maps to a null result (fallback path).

    suspend fun taskSnapshotToolCallsSlice(taskId: String, toolCallIds: List<String>): JsonObject? {
        val target = host.workspaceTarget()
        val params = buildJsonObject {
            put("taskId", JsonPrimitive(taskId))
            put("workspacePath", JsonPrimitive(target?.first ?: ""))
            target?.second?.let { put("workspaceIdentity", JsonPrimitive(it)) }
            put("toolCallIds", JsonArray(toolCallIds.map { JsonPrimitive(it) }))
        }
        return when (val raw = v4Call("getTaskSnapshotToolCallsSlice", RpcValue.Obj(params))) {
            is RpcValue.Obj -> raw.json as? JsonObject
            else -> null
        }
    }

    /** The hello's connectionId, stamped by [helloAndInitialize]. */
    @Volatile var connectionId: String? = null
        private set
}
