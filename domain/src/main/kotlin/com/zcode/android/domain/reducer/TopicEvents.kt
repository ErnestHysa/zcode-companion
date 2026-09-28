package com.zcode.android.domain.reducer

import com.zcode.android.domain.model.ConversationRow
import kotlinx.serialization.json.JsonObject

/**
 * Topic-plane projection events — the DOMAIN vocabulary the Phase 5.1 reducers
 * consume (§19.2 pipeline step "domain events"; architectural.md §15.5, P5–P7).
 *
 * The data-layer gateway maps transport frames onto these (epoch stamped from the
 * active subscription record, §15.1) — `domain` itself never sees protocol types.
 * The op set mirrors the VERIFIED delta vocabulary (docs/protocol/v4-wire.md §3);
 * unknown ops stay typed [ProjectionOp.Unknown] and are COUNTED (INV-8).
 */

/** (logEpoch, seq) watermark — the stale-guard currency (§9.13). */
data class ProjectionWatermark(val logEpoch: String, val seq: Long)

/** Verified delta vocabulary (delta.ts), domain-typed; unknown ops preserved raw. */
sealed class ProjectionOp {
    data class RowAppended(val row: ConversationRow) : ProjectionOp()
    data class RowUpserted(val row: ConversationRow) : ProjectionOp()
    data class RowRemoved(val fromRowId: Long) : ProjectionOp()
    data class RowTextAppended(val rowId: Long, val path: String, val append: String) : ProjectionOp()
    /** Patch vocabulary is NOT ported (no verified fixture) — patch is retained, never merged (§4.1). */
    data class StateUpdated(val patch: JsonObject) : ProjectionOp()
    data class Unknown(val op: String?, val raw: JsonObject) : ProjectionOp()
}

/** Decoded topic events with the subscription-generation epoch stamped by the gateway. */
sealed class TopicEvent {
    abstract val topic: String
    abstract val sessionId: String
    abstract val logEpoch: String
    abstract val fromSeq: Long
    abstract val toSeq: Long

    /** Snapshot frame (fromSeq = 0 verified, docs/protocol/v4-wire.md §3). */
    data class Snapshot(
        override val topic: String,
        override val sessionId: String,
        override val logEpoch: String,
        override val fromSeq: Long,
        override val toSeq: Long,
        val rows: List<ConversationRow>,
    ) : TopicEvent()

    /** Deltas frame — ops are applied contiguously across [fromSeq, toSeq]. */
    data class Deltas(
        override val topic: String,
        override val sessionId: String,
        override val logEpoch: String,
        override val fromSeq: Long,
        override val toSeq: Long,
        val ops: List<ProjectionOp>,
    ) : TopicEvent()
}

/** A rowsRange page result (read-only, stateless, safe to re-send — §7.4.3). */
data class TopicRowsPage(
    val topic: String,
    val sessionId: String,
    val logEpoch: String,
    val rows: List<ConversationRow>,
    val atSeq: Long,
    val hasMore: Boolean,
)
