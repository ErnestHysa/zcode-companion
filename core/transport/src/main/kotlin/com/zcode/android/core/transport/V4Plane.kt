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

    /**
     * GAP-6: the `off-peak-task` channel `list` read (offPeakTask.ts:41 — NO args,
     * Arr([]) like model-selection.getView). Result normalized to {tasks:[…]}.
     */
    suspend fun offPeakTaskList(): JsonObject

    // terminal (view-only — INV-9)
    suspend fun terminalCreate(cols: Int, rows: Int, cwd: String? = null): JsonObject
    suspend fun terminalDispose(id: String)
    fun terminalListenData(id: String, handler: (String) -> Unit): Int
    fun terminalListenExit(id: String, handler: (Int?) -> Unit): Int
    fun disposeListener(listenerId: Int)

    // git (read-only subset)
    suspend fun gitRepositorySummary(workspacePath: String): JsonObject
    suspend fun gitChanges(workspacePath: String, sourceId: String): JsonObject
    suspend fun gitDiff(workspacePath: String, path: String, staged: Boolean?): JsonObject
    suspend fun gitCommitGraph(workspacePath: String, maxCount: Int, skip: Int): JsonObject

    // file (read-only subset)
    suspend fun fileReaddir(path: String, includeHidden: Boolean = false): RpcValue
    suspend fun fileStat(path: String): JsonObject
    suspend fun fileReadText(path: String, offset: Long?, length: Long?): JsonObject
    suspend fun fileReadBytes(path: String, offset: Long, length: Long): ByteArray
    suspend fun fileBinaryPreview(path: String, maxBytes: Long?): JsonObject
    suspend fun fileSearch(rootPath: String, workspaceIdentity: String?, query: String, limit: Int): RpcValue

    // model-selection (read-only view)
    suspend fun modelSelectionView(): JsonObject
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

    /** The hello's connectionId, stamped by [helloAndInitialize]. */
    @Volatile var connectionId: String? = null
        private set
}
