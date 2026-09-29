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

/** Command submission port (bound to the transport's v4 command plane). */
interface CommandSender {
    suspend fun send(
        type: String,
        sessionId: String?,
        payload: JsonObject,
        baseRevision: Long?,
        baseLogEpoch: String?,
        commandId: String,
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
}

/**
 * Relay binding — wraps [com.zcode.android.core.transport.RelayTransport] (the only
 * production transport; DirectTransport arrives Phase 10 behind this same interface).
 */
class RelayConversationGateway(
    private val transport: com.zcode.android.core.transport.RelayTransport,
    private val clientId: String,
    private val traceLog: com.zcode.android.core.common.log.ZLog? = null,
) : ConversationGateway {

    private val _topicEvents = MutableSharedFlow<GatewayTopicEvent>(extraBufferCapacity = 256)
    override val topicEvents: SharedFlow<GatewayTopicEvent> = _topicEvents.asSharedFlow()

    /** Subscription records: session → active epoch (ack-stamped; the frame carries none). */
    private val epochs = HashMap<String, String>()

    /** Forward transport frames into domain events; call once after connect (single collector). */
    override fun startForwarding(scope: CoroutineScope) {
        scope.launch {
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
            ?: return // unsubscribed: ignore
        traceLog?.debug("gateway.frame", "session=$sessionId kind=${arrived.deliveryKind} seq=${arrived.fromSeq}..${arrived.toSeq} epoch=${epoch.take(24)}")
        try {
            when (val payload = arrived.frame.payload) {
                is TopicFrame.TopicPayload.Snapshot -> {
                    val rows = decodeRows(payload.snapshot, sessionId)
                    _topicEvents.emit(
                        GatewayTopicEvent.Snapshot(arrived.topic, sessionId, epoch, arrived.fromSeq, arrived.toSeq, rows)
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
        val arr = snapshot["rows"] as? kotlinx.serialization.json.JsonArray
            ?: throw RowDecodeException("snapshot.rows missing (session $sessionId)")
        return arr.map { decodeRow(it as? JsonObject ?: throw RowDecodeException("snapshot row not an object"), sessionId) }
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
        return GatewayPage(
            rows = r.rows.map { ConversationRow.Decoder.decode(rawOf(it)) },
            atSeq = r.atSeq,
            atLogEpoch = r.atLogEpoch,
            hasMore = r.hasMore,
        )
    }

    private fun rawOf(row: com.zcode.android.core.protocol.v4.ConversationRow): JsonObject = row.raw

    override suspend fun send(
        type: String,
        sessionId: String?,
        payload: JsonObject,
        baseRevision: Long?,
        baseLogEpoch: String?,
        commandId: String,
    ): CommandAck {
        val envelope = com.zcode.android.core.protocol.v4.CommandEnvelope.build(
            clientId = clientId,
            sessionId = sessionId,
            type = type,
            payload = payload,
            issuedAt = System.currentTimeMillis(),
            baseRevision = baseRevision,
            baseLogEpoch = baseLogEpoch,
            commandId = commandId, // minted once by the outbox — INV-4
        )
        return transport.sendConversationCommand(envelope)
    }

    /** Test seam: forget the active subscription records (process-death simulation). */
    fun resetSubscriptionsForTest() {
        synchronized(epochs) { epochs.clear() }
    }
}
