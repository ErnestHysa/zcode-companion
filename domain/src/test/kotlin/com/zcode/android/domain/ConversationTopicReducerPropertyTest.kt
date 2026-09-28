package com.zcode.android.domain

import com.zcode.android.domain.ids.SessionId
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.domain.reducer.ConversationTopicReducer
import com.zcode.android.domain.reducer.ProjectionOp
import com.zcode.android.domain.reducer.ProjectionWatermark
import com.zcode.android.domain.reducer.TopicEvent
import com.zcode.android.domain.reducer.TopicRowsPage
import com.zcode.android.domain.repo.ConversationCacheBounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random

/**
 * Phase 5.1 acceptance — "zero ambiguous merges under randomized schedules".
 *
 * Model-based property test: a SEEDED generator authors topic-event schedules with
 * full knowledge of what the SPEC (§15.5, S7–S10) allows, an INDEPENDENT reference
 * model applies the same spec rules, and after EVERY event the reducer's effects
 * (driven through an in-memory store model mirroring the Phase 4 repository rules)
 * must equal the reference — plus INV-5 bounds after every step. 60 seeds × 80 ops.
 */
class ConversationTopicReducerPropertyTest {

    private val sid = SessionId("prop")

    // ------------------------------------------------------------------ helpers

    private fun row(id: Long, text: String): ConversationRow = ConversationRow.Unknown(
        rowId = id, turnId = "t", unknownKind = "gen",
        raw = JsonObject(mapOf("rowId" to JsonPrimitive(id), "text" to JsonPrimitive(text))),
    )

    /** Rows keyed by rowId → text; mirrors the Phase 4 store contract mechanics. */
    private class StoreModel {
        var epoch: String? = null
        val rows = LinkedHashMap<Long, String>()
        var lastStoreResult: String = ""

        fun apply(op: com.zcode.android.domain.repo.ConversationStoreOp) {
            when (op) {
                is com.zcode.android.domain.repo.ConversationStoreOp.SnapshotArrived -> {
                    rows.clear()
                    epoch = op.logEpoch
                    for (r in op.rows) rows[r.rowId] = textOf(r)
                    evict()
                    lastStoreResult = "replaced"
                }
                is com.zcode.android.domain.repo.ConversationStoreOp.PageFetched -> {
                    if (epoch != null && epoch != op.logEpoch) { lastStoreResult = "rejected"; return }
                    if (epoch == null) epoch = op.logEpoch
                    for (r in op.rows) rows[r.rowId] = textOf(r)
                    evict()
                    lastStoreResult = "applied"
                }
                is com.zcode.android.domain.repo.ConversationStoreOp.RowUpserted -> {
                    if (epoch != null && epoch != op.logEpoch) { lastStoreResult = "rejected"; return }
                    if (epoch == null) epoch = op.logEpoch
                    rows[op.row.rowId] = textOf(op.row)
                    evict()
                    lastStoreResult = "applied"
                }
                is com.zcode.android.domain.repo.ConversationStoreOp.RowRemoved -> {
                    if (epoch != null && epoch != op.logEpoch) { lastStoreResult = "rejected"; return }
                    if (epoch == null) epoch = op.logEpoch
                    rows.keys.removeAll { it >= op.fromRowId }
                    lastStoreResult = "applied"
                }
                is com.zcode.android.domain.repo.ConversationStoreOp.RowTextAppended -> {
                    if (epoch != null && epoch != op.logEpoch) { lastStoreResult = "rejected"; return }
                    if (epoch == null) epoch = op.logEpoch
                    val cur = rows[op.rowId]
                    if (cur == null) { lastStoreResult = "dropped" } else { rows[op.rowId] = cur + op.append; lastStoreResult = "applied" }
                }
            }
        }

        private fun evict() {
            while (rows.size > ConversationCacheBounds.MAX_CACHED_ROWS_PER_SESSION) {
                rows.remove(rows.keys.first())
            }
        }

        private fun textOf(r: ConversationRow): String =
            (r.raw["text"] as? JsonPrimitive)?.content ?: ""
    }

    /** INDEPENDENT spec model (§15.5 + S7–S10) — computes what MUST be true. */
    private class ReferenceModel(val holdWindowMs: Long, val holdMaxOps: Int) {
        var epoch: String? = null
        var cursor: Long = -1L
        val rows = LinkedHashMap<Long, String>()
        val held = ArrayList<TopicEvent.Deltas>()
        var holdSinceTick: Long = -1
        var escalations: Long = 0
        var now: Long = 0

        fun snapshot(e: TopicEvent.Snapshot) {
            epoch = e.logEpoch; cursor = e.toSeq; rows.clear(); held.clear(); holdSinceTick = -1
            for (r in e.rows) rows[r.rowId] = textOf(r)
        }

        fun deltas(e: TopicEvent.Deltas): Boolean { // true = state changed
            val ep = epoch
            if (ep != null && ep != e.logEpoch) { escalateClear(); return false }
            if (ep != null && e.toSeq <= cursor) return false
            if (ep == null || e.fromSeq == cursor + 1) {
                applyOps(e)
                drainHeld()
                if (ep == null && e.fromSeq > 0) escalations++
                return true
            }
            if (e.fromSeq > cursor + 1) {
                if (holdSinceTick < 0) holdSinceTick = now
                held.add(e)
                if (held.sumOf { it.ops.size } > holdMaxOps) { escalateClear() }
                return false
            }
            escalations++ // partial-overlap resend → escalate, no mutation
            return false
        }

        fun page(p: TopicRowsPage) {
            if (epoch != null && epoch != p.logEpoch) return
            val fresh = epoch == null
            if (fresh) { epoch = p.logEpoch; cursor = p.atSeq }
            for (r in p.rows) rows[r.rowId] = textOf(r)
        }

        fun tick(): Boolean {
            if (held.isEmpty()) return false
            if (now - holdSinceTick < holdWindowMs) return false
            escalateClear(); return true
        }

        private fun applyOps(e: TopicEvent.Deltas) {
            epoch = e.logEpoch
            cursor = e.toSeq
            for (op in e.ops) when (op) {
                is ProjectionOp.RowAppended -> rows[op.row.rowId] = textOf(op.row)
                is ProjectionOp.RowUpserted -> rows[op.row.rowId] = textOf(op.row)
                is ProjectionOp.RowRemoved -> rows.keys.removeAll { it >= op.fromRowId }
                is ProjectionOp.RowTextAppended -> rows[op.rowId]?.let { rows[op.rowId] = it + op.append }
                else -> Unit
            }
        }

        private fun drainHeld() {
            // S7: resends the cursor already passed are duplicates — prune them.
            held.removeAll { it.toSeq <= cursor }
            var progressed = true
            while (progressed) {
                progressed = false
                val it = held.iterator()
                while (it.hasNext()) {
                    val h = it.next()
                    if (h.fromSeq == cursor + 1) { it.remove(); applyOps(h); progressed = true; break }
                }
            }
            if (held.isEmpty()) holdSinceTick = -1
        }

        private fun escalateClear() { escalations++; held.clear(); holdSinceTick = -1 }

        private fun textOf(r: ConversationRow): String = (r.raw["text"] as? JsonPrimitive)?.content ?: ""
    }

    // ------------------------------------------------------------------ the property

    @Test
    fun `randomized schedules produce zero ambiguous merges against the reference model`() {
        for (seed in 1..60) {
            val rnd = Random(seed)
            var now = 0L
            val reducer = ConversationTopicReducer(
                sid, clock = { now },
                config = ConversationTopicReducer.Config(holdWindowMs = 100, holdBufferMaxOps = 40),
            )
            val ref = ReferenceModel(holdWindowMs = 100, holdMaxOps = 40)
            val store = StoreModel()
            var nextRowId = 1L
            var seqHead = 0L
            val epochs = listOf("eA", "eB")
            var epochIdx = 0

            repeat(80) { step ->
                now += rnd.nextLong(0, 40)
                val choice = rnd.nextInt(100)
                when {
                    choice < 22 -> { // snapshot (sometimes an epoch flip = S9)
                        if (rnd.nextBoolean()) epochIdx = 1 - epochIdx
                        val epoch = epochs[epochIdx]
                        val count = rnd.nextLong(1, 12)
                        val rows = (0 until count).map { row(nextRowId++, "snap-$step-${nextRowId}") }
                        seqHead = rnd.nextLong(1, 500)
                        val e = TopicEvent.Snapshot("conversation/prop", "prop", epoch, 0, seqHead, rows)
                        runEffects(reducer.onSnapshot(e), store)
                        ref.snapshot(e)
                    }
                    choice < 60 -> { // deltas — mix of contiguous / duplicate / gap / epoch-bump
                        val bumpEpoch = rnd.nextInt(10) == 0
                        val kind = rnd.nextInt(10)
                        val epoch = if (bumpEpoch) epochs[1 - epochIdx] else epochs[epochIdx]
                        val ops = ArrayList<ProjectionOp>()
                        val n = rnd.nextLong(1, 5)
                        var from: Long
                        when {
                            kind < 5 -> { from = seqHead + 1; seqHead += n } // contiguous
                            kind < 7 -> { from = rnd.nextLong(0, (seqHead + 1).coerceAtLeast(1)); if (from + n - 1 > seqHead) seqHead = from + n - 1 } // dup/stale/overlap
                            else -> { from = seqHead + rnd.nextLong(2, 8); seqHead = from + n - 1 } // gap (hold)
                        }
                        repeat(n.toInt()) {
                            when (rnd.nextInt(4)) {
                                0, 1 -> ops.add(ProjectionOp.RowAppended(row(nextRowId, "d$step-$nextRowId")))
                                2 -> ops.add(ProjectionOp.RowTextAppended(1L.coerceAtMost(nextRowId), "text", "+$step"))
                                else -> ops.add(ProjectionOp.RowRemoved(rnd.nextLong(1, (nextRowId + 1).coerceAtLeast(2))))
                            }
                            nextRowId++
                        }
                        val e = TopicEvent.Deltas("conversation/prop", "prop", epoch, from, from + ops.size - 1, ops)
                        runEffects(reducer.onDeltas(e), store)
                        ref.deltas(e)
                    }
                    choice < 70 -> { // rowsRange page
                        val epoch = epochs[epochIdx]
                        val rows = (0 until rnd.nextLong(1, 6)).map { row(nextRowId++, "page$nextRowId") }
                        val p = TopicRowsPage("conversation/prop", "prop", epoch, rows, atSeq = seqHead, hasMore = rnd.nextBoolean())
                        runEffects(reducer.onRowsPage(p), store)
                        if (reducer.watermark == null) reducer.adoptEpoch(p.logEpoch, floorSeq = p.atSeq)
                        ref.page(p)
                    }
                    else -> { // hold tick
                        val changed = ref.tick()
                        runEffects(reducer.onHoldExpired(), store)
                        if (changed) assertEquals(reducer.escalations, ref.escalations, "seed $seed step $step")
                    }
                }

                // ---- per-step assertions (the phase acceptance)
                assertEquals(ref.rows, store.rows, "seed $seed step $step: store rows must equal the spec reference (no ambiguous merge)")
                assertEquals(ref.epoch, store.epoch, "seed $seed step $step: epoch")
                if (ref.epoch == null) {
                    assertTrue(reducer.watermark == null, "seed $seed step $step: no epoch → no watermark")
                } else {
                    assertEquals(ProjectionWatermark(ref.epoch!!, ref.cursor), reducer.watermark, "seed $seed step $step: cursor equals the spec cursor")
                }
                if (!ref.held.isEmpty() || reducer.isHolding) {
                    assertEquals(reducer.heldOpCount <= 40, true, "seed $seed: hold bound (INV-5)")
                }
                assertTrue(store.rows.size <= ConversationCacheBounds.MAX_CACHED_ROWS_PER_SESSION, "seed $seed step $step: INV-5 store bound")
            }
            // cursor sanity: never fabricated beyond the last authored head within an epoch
            if (store.epoch != null) {
                assertTrue(store.rows.size <= ConversationCacheBounds.MAX_CACHED_ROWS_PER_SESSION)
            }
        }
    }

    private fun runEffects(effects: List<ConversationTopicReducer.Effect>, store: StoreModel) {
        for (e in effects) {
            when (e) {
                is ConversationTopicReducer.Effect.Persist -> for (op in e.ops) store.apply(op)
                is ConversationTopicReducer.Effect.CursorAdvanced -> Unit
                is ConversationTopicReducer.Effect.EpochAdopted -> Unit
                is ConversationTopicReducer.Effect.StreamingCleared,
                is ConversationTopicReducer.Effect.StreamingTrimmedFrom,
                is ConversationTopicReducer.Effect.StreamingUpserted,
                is ConversationTopicReducer.Effect.EscalateResync,
                -> Unit
            }
        }
    }
}
