package com.zcode.android.data.session

import com.zcode.android.core.protocol.v4.CommandAck
import com.zcode.android.core.protocol.v4.TopicFrame
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.domain.reducer.ProjectionOp
import com.zcode.android.domain.model.RowDecodeException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Conversation-plane gateway — the ONE place protocol types are mapped onto the
 * domain projection vocabulary (§40.2: data → protocol; domain stays pure).
 *
 * Mapping rules (INV-8/INV-14, exact):
 *  - rows decode TYPED (domain ConversationRow.Decoder); a KNOWN-kind schema violation
 *    is a [GatewayTopicEvent.DecodeFaulted] — the caller escalates a resync and never
 *    merges a partially-decoded frame (§39.3 fail-closed);
 *  - unknown delta ops stay [ProjectionOp.Unknown] (counted by the reducer);
 *  - every event is stamped with the subscription-record logEpoch of its session
 *    (the topic frame does not carry the epoch — the ack does; §15.1 generation guard).
 */
sealed class GatewayTopicEvent {
    abstract val topic: String
    abstract val sessionId: String

    data class Snapshot(
        override val topic: String,
        override val sessionId: String,
        val logEpoch: String,
        val fromSeq: Long,
        val toSeq: Long,
        val rows: List<ConversationRow>,
        /**
         * FIELD (2026-09-30, vc11): production snapshots carry a TAIL WINDOW
         * (rowsWindowSchema: {window, totalCount, firstRowId}) — the desktop truncates
         * the initial snapshot (field: 62 989-seq session delivered 60 rows). These
         * drive the §19.4 backward pagination ("load earlier"): firstRowId != null &&
         * window's first row != firstRowId ⇔ more history above. Null for legacy
         * bare-array snapshots (mock) — unknown, treated as no-more.
         */
        val totalCount: Long? = null,
        val firstRowId: Long? = null,
        /**
         * Phase 7 A-side state block (docs/protocol/v4-wire.md §9): control/queue/
         * pendingInteractions/inputRouting/revision, decoded with per-key INV-8
         * tolerance. Null = the snapshot carried no state keys (legacy shape).
         */
        val sessionState: com.zcode.android.domain.model.SessionProjectionState? = null,
    ) : GatewayTopicEvent()

    data class Deltas(
        override val topic: String,
        override val sessionId: String,
        val logEpoch: String,
        val fromSeq: Long,
        val toSeq: Long,
        val ops: List<ProjectionOp>,
    ) : GatewayTopicEvent()

    /** Known-kind schema violation (INV-14): escalate resync, never merge. */
    data class DecodeFaulted(
        override val topic: String,
        override val sessionId: String,
        val reason: String,
    ) : GatewayTopicEvent()
}

data class GatewayAck(val subscriptionId: String, val mode: String, val logEpoch: String)

data class GatewayPage(
    val rows: List<ConversationRow>,
    val atSeq: Long,
    val atLogEpoch: String,
    val hasMore: Boolean,
)

/**
 * Command submission port (bound to the transport's v4 command plane). The
 * workspace target rides the CALL (the verified desktop adapter spreads
 * `{workspacePath, workspaceIdentity, envelope}` on every command) — pass it for
 * session-less commands (createSession has no session to route by); null lets the
 * transport resolve from the session's subscribe-time record.
 */
interface CommandSender {
    suspend fun send(
        type: String,
        sessionId: String?,
        payload: JsonObject,
        baseRevision: Long?,
        baseLogEpoch: String?,
        commandId: String,
        workspacePath: String? = null,
        workspaceIdentity: String? = null,
    ): CommandAck
}

interface ConversationGateway : CommandSender {
    val topicEvents: SharedFlow<GatewayTopicEvent>

    /** Wire the transport's inbound flow into [topicEvents]; call once after connect. */
    fun startForwarding(scope: CoroutineScope)

    /**
     * Subscribe a conversation topic. Phase 6: the workspace target may be supplied
     * per call (the verified v4 shape carries the workspace on every call); null
     * falls back to the endpoint-pinned workspace (the Phase 5 recovery path).
     */
    suspend fun subscribe(
        sessionId: String,
        visibility: String? = "foreground",
        workspacePath: String? = null,
        workspaceIdentity: String? = null,
    ): GatewayAck

    /** §15.1: base resumes from a watermark; null base / forceSnapshot escalates a snapshot. */
    suspend fun resync(
        sessionId: String,
        baseEpoch: String?,
        baseSeq: Long?,
        forceSnapshot: Boolean,
        workspacePath: String? = null,
        workspaceIdentity: String? = null,
    ): GatewayAck

    suspend fun rowsRange(sessionId: String, beforeRowId: Long?, limit: Int): GatewayPage

    /**
     * FIELD (2026-10-04, vc32 device log): the CURRENT connection's ack-stamped
     * subscription epoch for [sessionId] (null when never subscribed on this
     * connection). The recovery escalation compares its resync base against this —
     * a base from a PREVIOUS connection's subscription is rejected by the server
     * with fault.subscription.notOwned; such escalations must force a snapshot
     * instead (the fresh subscribe is the only base the server honors).
     */
    fun currentSubscriptionEpoch(sessionId: String): String?
}

/**
 * Relay binding — wraps [com.zcode.android.core.transport.AppTransport] (the only
 * production transport; DirectTransport arrives Phase 10 behind this same interface).
 */
class RelayConversationGateway(
    private val transport: com.zcode.android.core.transport.AppTransport,
    private val clientId: String,
    private val traceLog: com.zcode.android.core.common.log.ZLog? = null,
) : ConversationGateway {

    private val _topicEvents = MutableSharedFlow<GatewayTopicEvent>(extraBufferCapacity = 256)
    override val topicEvents: SharedFlow<GatewayTopicEvent> = _topicEvents.asSharedFlow()

    /** Subscription records: session → active epoch (ack-stamped; the frame carries none). */
    private val epochs = HashMap<String, String>()

    /** Forward transport frames into domain events; call once after connect (single collector). */
    private var forwardingJob: kotlinx.coroutines.Job? = null

    /** Test/diagnostics: how many inbound collectors were actually created (must be ≤ 1). */
    fun forwardingCollectorCount(): Int = forwardingCollectors.get()
    private val forwardingCollectors = java.util.concurrent.atomic.AtomicInteger(0)

    override fun startForwarding(scope: CoroutineScope) {
        // FIELD-KILLER #7 (2026-09-30, vc12): startTargets runs per task attach — without
        // this guard EVERY task open added ANOTHER transport.inbound collector, so each
        // snapshot (300–780 KB real size) was decoded and applied N× (field: task #5 saw
        // ×5, manager.snapshot ×51, 60-row re-applies every ~130 ms for minutes) — the
        // CPU/memory churn behind "everything becomes so so slow" and the crashes.
        if (forwardingJob?.isActive == true) return
        forwardingCollectors.incrementAndGet()
        forwardingJob = scope.launch {
            transport.inbound.collect { frame ->
                if (frame is com.zcode.android.core.transport.InboundFrame.TopicFrameArrived) {
                    mapTopicFrame(frame)
                }
            }
        }
    }

    private suspend fun mapTopicFrame(arrived: com.zcode.android.core.transport.InboundFrame.TopicFrameArrived) {
        val sessionId = arrived.topic.removePrefix("conversation/")
        if (sessionId == arrived.topic) return // not a conversation topic
        // Epoch precedence: the transport's subscription record (updated by EVERY
        // subscribe, including recovery re-subscribes whose ack may carry a moved
        // epoch, §15-S9) over the gateway's own ack-stamped map.
        val epoch = transport.subscriptionEpoch(sessionId)
            ?: synchronized(epochs) { epochs[sessionId] }
            ?: run {
                // FIELD (2026-10-04, vc32 forensics): silent drops here made a stalled
                // snapshot delivery UNDIAGNOSABLE (frames recv'd at the wire, nothing
                // decoded downstream). Count + log at DEBUG — never silent (§34).
                traceLog?.debug("gateway.drop", "session=$sessionId kind=${arrived.deliveryKind} seq=${arrived.fromSeq}..${arrived.toSeq} (no subscription record)")
                return
            }
        traceLog?.debug("gateway.frame", "session=$sessionId kind=${arrived.deliveryKind} seq=${arrived.fromSeq}..${arrived.toSeq} epoch=${epoch.take(24)}")
        try {
            when (val payload = arrived.frame.payload) {
                is TopicFrame.TopicPayload.Snapshot -> {
                    val rows = decodeRows(payload.snapshot, sessionId)
                    val window = payload.snapshot["rows"] as? JsonObject
                    // Phase 7 A-side state block (§9): only present when the snapshot
                    // carries any of the verified state keys (legacy bare-array shapes
                    // decode to an all-null block → emitted as null to keep "no state
                    // keys" distinguishable from "state keys all absent").
                    val state = com.zcode.android.domain.model.SessionProjectionState.fromSnapshot(payload.snapshot)
                    _topicEvents.emit(
                        GatewayTopicEvent.Snapshot(
                            arrived.topic, sessionId, epoch, arrived.fromSeq, arrived.toSeq, rows,
                            totalCount = (window?.get("totalCount") as? JsonPrimitive)?.content?.toLongOrNull(),
                            firstRowId = (window?.get("firstRowId") as? JsonPrimitive)?.content?.toLongOrNull(),
                            sessionState = state.takeIf {
                                it.control != null || it.inputRouting != null || it.queue != null ||
                                    it.pendingInteractions != null || it.backgroundWorks != null ||
                                    it.config != null || it.revision != null
                            },
                        )
                    )
                }
                is TopicFrame.TopicPayload.Deltas -> {
                    val ops = ArrayList<ProjectionOp>(payload.deltas.size)
                    for (d in payload.deltas) {
                        when (d) {
                            is com.zcode.android.core.protocol.v4.ConversationDelta.RowAppended ->
                                ops.add(ProjectionOp.RowAppended(decodeRow(d.row.raw, sessionId)))
                            is com.zcode.android.core.protocol.v4.ConversationDelta.RowUpserted ->
                                ops.add(ProjectionOp.RowUpserted(decodeRow(d.row.raw, sessionId)))
                            is com.zcode.android.core.protocol.v4.ConversationDelta.RowRemoved ->
                                ops.add(ProjectionOp.RowRemoved(d.fromRowId))
                            is com.zcode.android.core.protocol.v4.ConversationDelta.RowDelta ->
                                ops.add(ProjectionOp.RowTextAppended(d.rowId, d.path, d.append))
                            is com.zcode.android.core.protocol.v4.ConversationDelta.StateUpdated ->
                                ops.add(ProjectionOp.StateUpdated(d.patch))
                            is com.zcode.android.core.protocol.v4.ConversationDelta.Unknown ->
                                ops.add(ProjectionOp.Unknown(d.op, d.raw))
                        }
                    }
                    _topicEvents.emit(
                        GatewayTopicEvent.Deltas(arrived.topic, sessionId, epoch, arrived.fromSeq, arrived.toSeq, ops)
                    )
                }
            }
        } catch (e: RowDecodeException) {
            // Known-kind schema violation: fail closed → the manager escalates a resync
            // (§39.3). The frame is dropped WHOLE — no partial merge (INV-14).
            _topicEvents.emit(GatewayTopicEvent.DecodeFaulted(arrived.topic, sessionId, e.message ?: "decode fault"))
        }
    }

    private fun decodeRows(snapshot: JsonObject, sessionId: String): List<ConversationRow> {
        // FIELD-KILLER #4 (2026-09-30, vc9 real device — the LAST blocker): production
        // `rows` is a TAIL-WINDOW OBJECT, not an array — rowsWindowSchema =
        // { window: [row…], totalCount, firstRowId } (open repo
        // packages/shared/src/zcode-protocol-v4/snapshot.ts @ v3.14.3 29628c9; the
        // 212 KB real snapshot carries seq 0..40326 rows in `rows.window`). The mock's
        // bare-array shape is still accepted.
        val rowsEl = snapshot["rows"]
        val arr = when {
            rowsEl is kotlinx.serialization.json.JsonArray -> rowsEl
            rowsEl is JsonObject -> rowsEl["window"] as? kotlinx.serialization.json.JsonArray
            else -> null
        } ?: throw RowDecodeException(
            "snapshot.rows missing (session $sessionId) — expected rows.window object or rows array",
        )
        // Snapshot resilience (§19.4 tail-first): a snapshot can carry tens of thousands
        // of rows — one row violating the ported schema must not blank the whole task.
        // A row that fails KNOWN-kind required-field validation is demoted to a retained
        // raw placeholder (the same tolerance Unknown kinds get, INV-8); deltas stay
        // fail-closed (INV-14 — single ops, whole-frame drop remains correct there).
        return arr.map { el ->
            val obj = el as? JsonObject
                ?: throw RowDecodeException("snapshot row not an object")
            try {
                decodeRow(obj, sessionId)
            } catch (e: RowDecodeException) {
                ConversationRow.Unknown(
                    rowId = (obj["rowId"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: -1L,
                    turnId = (obj["turnId"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "",
                    unknownKind = "decode-fault:" + (obj["kind"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty(),
                    raw = obj,
                )
            }
        }
    }

    private fun decodeRow(obj: JsonObject, sessionId: String): ConversationRow =
        try {
            ConversationRow.Decoder.decode(obj)
        } catch (e: RowDecodeException) {
            throw RowDecodeException("session $sessionId: ${e.message}")
        }

    override suspend fun subscribe(
        sessionId: String,
        visibility: String?,
        workspacePath: String?,
        workspaceIdentity: String?,
    ): GatewayAck {
        // FIELD-KILLER #5 (2026-09-30, vc10): the desktop routes topic frames by the
        // LISTEN's workspace scope — a session outside the bridge-pinned workspace
        // subscribed fine but its snapshot was routed to nobody. Attach the scoped
        // listener for THIS workspace before the subscribe so the INITIAL snapshot lands.
        transport.ensureConversationListen(workspacePath, workspaceIdentity)
        val ack = transport.subscribeSession(sessionId, visibility, workspacePath, workspaceIdentity)
        synchronized(epochs) { epochs[sessionId] = ack.logEpoch }
        return GatewayAck(ack.subscriptionId, ack.mode, ack.logEpoch)
    }

    override suspend fun resync(
        sessionId: String,
        baseEpoch: String?,
        baseSeq: Long?,
        forceSnapshot: Boolean,
        workspacePath: String?,
        workspaceIdentity: String?,
    ): GatewayAck {
        transport.ensureConversationListen(workspacePath, workspaceIdentity)
        val ack = transport.resyncSession(
            sessionId,
            base = if (baseEpoch == null || baseSeq == null) null else baseEpoch to baseSeq,
            forceSnapshot = forceSnapshot,
            workspacePath = workspacePath,
            workspaceIdentity = workspaceIdentity,
        )
        synchronized(epochs) { epochs[sessionId] = ack.logEpoch }
        return GatewayAck(ack.subscriptionId, ack.mode, ack.logEpoch)
    }

    override suspend fun rowsRange(sessionId: String, beforeRowId: Long?, limit: Int): GatewayPage {
        val r = transport.rowsRange(sessionId, beforeRowId, limit)
        val rows = decodePageRows(r.rows.map { rawOf(it) }, sessionId)
        traceLog?.info(
            "gateway.rowsRange",
            "session=$sessionId rows=${rows.size} atSeq=${r.atSeq} atLogEpoch=${r.atLogEpoch.take(16)} hasMore=${r.hasMore}",
        )
        return GatewayPage(
            rows = rows,
            atSeq = r.atSeq,
            atLogEpoch = r.atLogEpoch,
            hasMore = r.hasMore,
        )
    }

    override fun currentSubscriptionEpoch(sessionId: String): String? = transport.subscriptionEpoch(sessionId)

    private fun rawOf(row: com.zcode.android.core.protocol.v4.ConversationRow): JsonObject = row.raw

    internal companion object {
        /**
         * FIELD (2026-09-30, vc16): production pages contain row kinds our ported
         * decoder may not model — ONE strict failure must not fail the WHOLE page (the
         * user-visible result was "load earlier does nothing"). Same tolerance as the
         * snapshot path: failed rows become retained-raw placeholders (INV-8).
         */
        internal fun decodePageRows(
            raws: List<JsonObject>,
            sessionId: String,
        ): List<com.zcode.android.domain.model.ConversationRow> {
            val rows = ArrayList<com.zcode.android.domain.model.ConversationRow>(raws.size)
            var demoted = 0
            for (raw in raws) {
                try {
                    rows.add(com.zcode.android.domain.model.ConversationRow.Decoder.decode(raw))
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    demoted++
                    rows.add(
                        com.zcode.android.domain.model.ConversationRow.Unknown(
                            rowId = (raw["rowId"] as? JsonPrimitive)?.content?.toLongOrNull() ?: -1L,
                            turnId = (raw["turnId"] as? JsonPrimitive)?.content ?: "",
                            unknownKind = "decode-fault:" + (raw["kind"] as? JsonPrimitive)?.content.orEmpty(),
                            raw = raw,
                        ),
                    )
                }
            }
            return rows
        }
    }

    override suspend fun send(
        type: String,
        sessionId: String?,
        payload: JsonObject,
        baseRevision: Long?,
        baseLogEpoch: String?,
        commandId: String,
        workspacePath: String?,
        workspaceIdentity: String?,
    ): CommandAck {
        val envelope = com.zcode.android.core.protocol.v4.CommandEnvelope.build(
            // FIELD (2026-10-01, vc22 device): the desktop binds boundClientId from OUR
            // clientHello and rejects commands whose envelope.clientId differs
            // (fault.command.clientMismatch — every send/capprove/create failed). The
            // identity is the transport's endpoint id, taken LIVE per call.
            clientId = transport.commandClientId ?: clientId,
            sessionId = sessionId,
            type = type,
            payload = payload,
            issuedAt = System.currentTimeMillis(),
            baseRevision = baseRevision,
            baseLogEpoch = baseLogEpoch,
            commandId = commandId, // minted once by the outbox — INV-4
        )
        return transport.sendConversationCommand(envelope, workspacePath, workspaceIdentity)
    }

    /** Test seam: forget the active subscription records (process-death simulation). */
    fun resetSubscriptionsForTest() {
        synchronized(epochs) { epochs.clear() }
    }
}
