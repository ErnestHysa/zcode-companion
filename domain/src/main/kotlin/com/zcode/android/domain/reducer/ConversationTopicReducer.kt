package com.zcode.android.domain.reducer

import com.zcode.android.domain.ids.SessionId
import com.zcode.android.domain.model.StreamingBuffer
import com.zcode.android.domain.repo.ConversationStoreOp

/**
 * Conversation projection reducer — Phase 5.1 (§15.5, P3/P5/P6, §28.4/§28.7).
 *
 * ONE instance per topic; NOT thread-safe by design: it is confined to a single
 * writer coroutine (the session manager's collector — §28.5 "single-writer per
 * topic, message-passing, no locks on shared collections").
 *
 * Guard semantics (normative, §15.5 + §15-S7/S8/S9/S10):
 *  - a SNAPSHOT is always accepted: atomic replacement — cursor resets to the
 *    snapshot's toSeq, ALL derived state (streaming buffers) for the topic is
 *    discarded, and Android-owned state (drafts/UI/notifications — separate stores,
 *    never touched by these effects) is retained (§15.5a–d);
 *  - DELTAS apply only when contiguous (fromSeq == lastSeq + 1) within the same
 *    logEpoch; a smaller toSeq is a duplicate/stale drop (counted, no mutation);
 *    a gap HOLDS in a bounded buffer (verified subscriber bounds) for at most
 *    [Config.holdWindowMs], then escalates a resync (never a local fill, S10);
 *  - an epoch mismatch escalates snapshot replacement (S9) — never a merge;
 *  - a rowsRange page is a stateless read: it never moves the delta cursor (its
 *    ops ride the page itself).
 *
 * INV-5: every buffer here is bounded BEFORE allocation and checked on entry.
 */
class ConversationTopicReducer(
    val sessionId: SessionId,
    private val clock: () -> Long = System::currentTimeMillis,
    private val config: Config = Config(),
) {

    data class Config(
        /** §15-S8: hold ≤ 5 s then gap → snapshot replace. */
        val holdWindowMs: Long = 5_000,
        /** Verified subscriberBufferMaxOps (§7.4.5) — held-frame op budget. */
        val holdBufferMaxOps: Int = 500,
        /** §28.7 streaming buffer bound per row. */
        val streamingRowCapBytes: Int = 8 * 1024,
        /** Streaming buffers retained per topic (render-window class bound, §19.2). */
        val streamingBufferMaxRows: Int = 64,
    )

    /** What the manager must do after this reducer transition (ordered). */
    sealed class Effect {
        /** Persist these store ops (batch, in order). */
        data class Persist(val ops: List<ConversationStoreOp>) : Effect()

        /** The (logEpoch, seq) cursor advanced — journal record follows durability. */
        data class CursorAdvanced(val epoch: String, val seq: Long) : Effect()

        /** §15.5c: derived streaming state discarded for the whole topic. */
        data class StreamingCleared(val reason: String) : Effect()

        /** row.removed truncation reaches derived state too. */
        data class StreamingTrimmedFrom(val fromRowId: Long) : Effect()

        /** Coalescer-visible streaming upsert (in-memory only, never persisted — §14.2). */
        data class StreamingUpserted(val buffer: StreamingBuffer) : Effect()

        /** §15-S9/S10 escalation: resync required; base = cursor to resume from (null = snapshot). */
        data class EscalateResync(val reason: String, val base: ProjectionWatermark?) : Effect()

        /** Page-first read on a fresh session: adopt the epoch WITHOUT moving the delta cursor. */
        data class EpochAdopted(val epoch: String) : Effect()
    }

    // ------------------------------------------------------------ single-writer state

    var watermark: ProjectionWatermark? = null
        private set

    /** Derived streaming buffers (§16 StreamingBuffer) — in-memory only, NEVER persisted. */
    private val streaming = LinkedHashMap<Long, StreamingBuffer>()

    /** Held out-of-order delta frames (S8), ordered by fromSeq. */
    private val hold = ArrayDeque<TopicEvent.Deltas>()
    private var holdFirstSeenMs: Long? = null

    // diagnostics counters (S7/S8/S10 observability, §34 metrics class)
    var duplicateDrops: Long = 0
        private set
    var staleDrops: Long = 0
        private set
    var escalations: Long = 0
        private set
    var unknownOps: Long = 0
        private set
    var metadataOps: Long = 0
        private set

    val heldFrameCount: Int get() = hold.size
    val heldOpCount: Int get() = hold.sumOf { it.ops.size }
    val streamingBufferCount: Int get() = streaming.size

    // ------------------------------------------------------------ transitions

    fun onSnapshot(e: TopicEvent.Snapshot): List<Effect> {
        // §15.5: snapshots REPLACE — always legal, from any prior state/epoch.
        val from = watermark
        streaming.clear()
        hold.clear()
        holdFirstSeenMs = null
        watermark = ProjectionWatermark(e.logEpoch, e.toSeq)
        return listOf(
            Effect.StreamingCleared("snapshot replace${from?.let { " (epoch ${it.logEpoch}→${e.logEpoch})" } ?: ""}"),
            Effect.Persist(listOf(ConversationStoreOp.SnapshotArrived(sessionId, e.logEpoch, e.rows))),
            Effect.CursorAdvanced(e.logEpoch, e.toSeq),
        )
    }

    fun onDeltas(e: TopicEvent.Deltas): List<Effect> {
        val current = watermark
        // Epoch mismatch (subscription generation moved, S9): escalate, never merge.
        if (current != null && current.logEpoch != e.logEpoch) {
            escalations++
            streaming.clear()
            hold.clear()
            holdFirstSeenMs = null
            return listOf(
                Effect.StreamingCleared("epoch mismatch ${current.logEpoch} → ${e.logEpoch}"),
                Effect.EscalateResync("epoch mismatch", base = null),
            )
        }
        // Duplicate or stale tail (S7): drop + count, zero mutation.
        if (current != null && e.toSeq <= current.seq) {
            duplicateDrops++
            return emptyList()
        }
        // Contiguous → apply in order.
        if (current == null || e.fromSeq == current.seq + 1) {
            return applyContiguous(e, adoptEpoch = current == null)
        }
        if (e.fromSeq > current.seq + 1) return holdFrame(e)
        // fromSeq <= lastSeq < toSeq — partial overlap (resend window): the already-
        // applied prefix would double-apply; treat as resend damage → resync (§15.5
        // "any ambiguity escalates to snapshot replacement").
        escalations++
        return listOf(Effect.EscalateResync("overlap resend (fromSeq=${e.fromSeq} ≤ cursor ${current.seq})", base = current))
    }

    fun onRowsPage(page: TopicRowsPage): List<Effect> {
        // Stateless read: adopt the epoch on a fresh session (page-first read path,
        // Phase 6 tail-first render), NEVER move the delta cursor (§7.4.3 resend-safe).
        val current = watermark
        if (current != null && current.logEpoch != page.logEpoch) {
            // Stale-epoch page: the store rejects mechanically; surface the rejection.
            return listOf(Effect.Persist(listOf(ConversationStoreOp.PageFetched(sessionId, page.logEpoch, page.rows))))
        }
        if (current == null) {
            return listOf(
                Effect.Persist(listOf(ConversationStoreOp.PageFetched(sessionId, page.logEpoch, page.rows))),
                Effect.EpochAdopted(page.logEpoch),
            )
        }
        return listOf(Effect.Persist(listOf(ConversationStoreOp.PageFetched(sessionId, page.logEpoch, page.rows))))
    }

    /** Hold-window expiry tick (S8: "hold ≤ 5 s then gap → snapshot replace"). */
    fun onHoldExpired(): List<Effect> {
        val first = holdFirstSeenMs ?: return emptyList()
        if (clock() - first < config.holdWindowMs) return emptyList()
        return escalateGap("hold window expired (${config.holdWindowMs} ms)")
    }

    /** True while a gap is being held (manager schedules [onHoldExpired] ticks). */
    val isHolding: Boolean get() = hold.isNotEmpty()

    // ------------------------------------------------------------ internals

    private fun applyContiguous(e: TopicEvent.Deltas, adoptEpoch: Boolean): List<Effect> {
        val epoch = e.logEpoch
        val ops = ArrayList<ConversationStoreOp>(e.ops.size)
        val derived = ArrayList<Effect>()
        for (op in e.ops) {
            when (op) {
                is ProjectionOp.RowAppended -> ops.add(ConversationStoreOp.RowUpserted(sessionId, epoch, op.row))
                is ProjectionOp.RowUpserted -> ops.add(ConversationStoreOp.RowUpserted(sessionId, epoch, op.row))
                is ProjectionOp.RowRemoved -> {
                    ops.add(ConversationStoreOp.RowRemoved(sessionId, epoch, op.fromRowId))
                    streaming.keys.removeAll { it >= op.fromRowId }
                    derived.add(Effect.StreamingTrimmedFrom(op.fromRowId))
                }
                is ProjectionOp.RowTextAppended -> {
                    ops.add(ConversationStoreOp.RowTextAppended(sessionId, epoch, op.rowId, op.append))
                    recordStreamingAppend(op)
                        ?.let { derived.add(Effect.StreamingUpserted(it)) }
                }
                is ProjectionOp.StateUpdated -> {
                    // Patch vocabulary is not ported (no verified fixture, §4.1): counted,
                    // retained nowhere, NEVER merged into rows (no invented interpretation).
                    metadataOps++
                }
                is ProjectionOp.Unknown -> unknownOps++
            }
        }
        watermark = ProjectionWatermark(epoch, e.toSeq)
        val out = ArrayList<Effect>(derived.size + 3)
        out.addAll(derived)
        if (ops.isNotEmpty()) out.add(Effect.Persist(ops))
        out.add(Effect.CursorAdvanced(epoch, e.toSeq))
        // Cursor advanced: stale resends held in the buffer are now duplicates (S7) —
        // prune them; a chained fill may release further held frames in order (S8).
        out.addAll(pruneAndDrainHeld())
        if (adoptEpoch && e.fromSeq > 0) {
            // A fresh session adopting a NON-zero seq delta stream means the snapshot
            // was missed — the stream's own prefix is unknown. Escalate (S10), do not
            // silently anchor a fabricated base.
            escalations++
            out.add(Effect.EscalateResync("fresh adoption at seq=${e.fromSeq} > 0 (snapshot missed)", base = null))
        }
        return out
    }

    /** After a cursor advance: drop held duplicates, drain chained-contiguous frames. */
    private fun pruneAndDrainHeld(): List<Effect> {
        if (hold.isEmpty()) return emptyList()
        val cursor = watermark ?: return emptyList()
        val effects = ArrayList<Effect>()
        // prune duplicates first (toSeq ≤ cursor → the frame's ops are already applied)
        hold.removeAll { it.toSeq <= cursor.seq }
        // drain chained-contiguous frames (each apply prunes again)
        var progressed = true
        while (progressed) {
            progressed = false
            val it = hold.iterator()
            while (it.hasNext()) {
                val h = it.next()
                if (h.fromSeq == (watermark?.seq ?: return effects) + 1) {
                    it.remove()
                    effects.addAll(applyContiguous(h, adoptEpoch = false))
                    progressed = true
                    break
                }
            }
        }
        if (hold.isEmpty()) holdFirstSeenMs = null
        return effects
    }

    private fun holdFrame(e: TopicEvent.Deltas): List<Effect> {
        if (holdFirstSeenMs == null) holdFirstSeenMs = clock()
        val incoming = e.ops.size
        if (heldOpCount + incoming > config.holdBufferMaxOps) {
            // Verified subscriber buffer bound breached → escalate rather than grow (INV-5).
            return escalateGap("hold buffer overflow (${heldOpCount}+$incoming > ${config.holdBufferMaxOps})")
        }
        hold.add(e)
        // The fill may complete a chain (or the frame may already be stale after a
        // concurrent advance): one unified prune + drain pass decides.
        return pruneAndDrainHeld()
    }

    private fun escalateGap(reason: String): List<Effect> {
        escalations++
        val base = watermark
        hold.clear()
        holdFirstSeenMs = null
        return listOf(Effect.EscalateResync(reason, base = base))
    }

    private fun recordStreamingAppend(op: ProjectionOp.RowTextAppended): StreamingBuffer? {
        if (op.path != "text") return null
        val existing = streaming[op.rowId]
        val mergedText = (existing?.text ?: "") + op.append
        // §28.7 bound checked BEFORE allocation: over-cap rows drop out of the derived
        // buffer (the PERSISTED row text is unaffected — the store keeps appending).
        if (mergedText.encodeToByteArray().size > config.streamingRowCapBytes) {
            streaming.remove(op.rowId)
            return null
        }
        val buffer = StreamingBuffer(op.rowId, mergedText, lastSeq = watermark?.seq ?: 0)
        if (existing == null) {
            if (streaming.size >= config.streamingBufferMaxRows) streaming.remove(streaming.keys.first())
        }
        streaming[op.rowId] = buffer
        return buffer
    }

    /** Test/diagnostics: current derived buffers (never persisted). */
    fun streamingBuffersForTest(): List<StreamingBuffer> = streaming.values.toList()

    /**
     * Manager hook for [Effect.EpochAdopted]: page-first read on a fresh session.
     * The floor is the PAGE's atSeq — the page carried those ops' row effects, so
     * adopting a lower floor would fabricate a gap at the next live frame.
     */
    fun adoptEpoch(epoch: String, floorSeq: Long) {
        if (watermark == null) watermark = ProjectionWatermark(epoch, seq = floorSeq)
    }
}
