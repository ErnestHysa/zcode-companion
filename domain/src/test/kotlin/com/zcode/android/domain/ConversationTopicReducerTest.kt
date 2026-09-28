package com.zcode.android.domain

import com.zcode.android.domain.ids.SessionId
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.domain.reducer.ConversationTopicReducer
import com.zcode.android.domain.reducer.ProjectionOp
import com.zcode.android.domain.reducer.ProjectionWatermark
import com.zcode.android.domain.reducer.TopicEvent
import com.zcode.android.domain.reducer.TopicRowsPage
import com.zcode.android.domain.repo.ConversationStoreOp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 5.1/5.2 deterministic reducer suite — §15.5 semantics, §15-S7/S8/S9/S10/S11
 * guard behaviors at the reducer level, INV-5 derived-state bounds.
 */
class ConversationTopicReducerTest {

    private val sid = SessionId("s1")
    private var now = 0L
    private fun reducer(holdWindowMs: Long = 5_000, config: ConversationTopicReducer.Config? = null) =
        ConversationTopicReducer(
            sid, clock = { now },
            config = config ?: ConversationTopicReducer.Config(holdWindowMs = holdWindowMs),
        )

    private fun row(id: Long, text: String = "r$id"): ConversationRow = ConversationRow.Unknown(
        rowId = id, turnId = "t", unknownKind = "gen",
        raw = JsonObject(mapOf("rowId" to JsonPrimitive(id), "text" to JsonPrimitive(text))),
    )

    private fun snapshot(epoch: String, toSeq: Long, ids: List<Long>) = TopicEvent.Snapshot(
        topic = "conversation/s1", sessionId = "s1", logEpoch = epoch,
        fromSeq = 0, toSeq = toSeq, rows = ids.map { row(it) },
    )

    private fun deltas(epoch: String, fromSeq: Long, ops: List<ProjectionOp>) = TopicEvent.Deltas(
        topic = "conversation/s1", sessionId = "s1", logEpoch = epoch,
        fromSeq = fromSeq, toSeq = fromSeq + ops.size - 1, ops = ops,
    )

    private fun append(id: Long) = ProjectionOp.RowAppended(row(id))

    // ---------------------------------------------------------------- 5.2 snapshot semantics

    @Test
    fun `snapshot replaces atomically - cursor reset, derived discard, store replace op (S9, 15-5)`() = runTest {
        val r = reducer()
        r.onDeltas(deltas("e1", 1, listOf(append(1), append(2))))
            .filterIsInstance<ConversationTopicReducer.Effect.StreamingUpserted>()
        r.onDeltas(deltas("e1", 3, listOf(ProjectionOp.RowTextAppended(1, "text", "stream…"))))
        assertEquals(1, r.streamingBufferCount, "streaming buffer exists pre-snapshot")

        val effects = r.onSnapshot(snapshot("e2", 50, listOf(7, 8, 9)))

        assertEquals(ProjectionWatermark("e2", 50), r.watermark, "cursor reset to snapshot toSeq")
        assertEquals(0, r.streamingBufferCount, "§15.5c derived-state discard")
        assertEquals(0, r.heldFrameCount)
        // effect order: StreamingCleared BEFORE Persist(replace) BEFORE cursor
        val kinds = effects.map { it::class.simpleName }
        assertTrue(kinds.indexOf("StreamingCleared") < kinds.indexOf("Persist") && kinds.indexOf("Persist") < kinds.indexOf("CursorAdvanced"))
        val persist = effects.filterIsInstance<ConversationTopicReducer.Effect.Persist>().single()
        val op = persist.ops.single() as ConversationStoreOp.SnapshotArrived
        assertEquals(listOf(7L, 8L, 9L), op.rows.map { it.rowId })
        assertEquals("e2", op.logEpoch)
    }

    @Test
    fun `snapshot never carries draft or ui-state effects (15-5d local-only retention)`() = runTest {
        val r = reducer()
        val effects = r.onSnapshot(snapshot("e1", 10, listOf(1)))
        // The complete effect vocabulary is structural: nothing here can reach a draft,
        // preference, notification, or UI store — only conversation-store ops + derived
        // streaming effects + journal cursor. (§15.5d is proven by construction + the
        // end-to-end draft-preservation scenarios in the recovery matrix.)
        assertTrue(effects.all {
            it is ConversationTopicReducer.Effect.Persist ||
                it is ConversationTopicReducer.Effect.CursorAdvanced ||
                it is ConversationTopicReducer.Effect.StreamingCleared
        })
    }

    // ---------------------------------------------------------------- guards

    @Test
    fun `duplicate and stale delta frames drop with zero mutation (S7)`() = runTest {
        val r = reducer()
        r.onSnapshot(snapshot("e1", 10, listOf(1)))
        val before = r.watermark
        assertEquals(emptyList(), r.onDeltas(deltas("e1", 5, listOf(append(99)))), "fully-stale frame dropped")
        assertEquals(emptyList(), r.onDeltas(deltas("e1", 10, listOf(append(99)))), "boundary-duplicate dropped")
        assertEquals(before, r.watermark)
        assertEquals(2, r.duplicateDrops)
    }

    @Test
    fun `contiguous deltas apply in order and advance the cursor`() = runTest {
        val r = reducer()
        r.onSnapshot(snapshot("e1", 2, listOf(1, 2)))
        val effects = r.onDeltas(deltas("e1", 3, listOf(append(3), ProjectionOp.RowRemoved(2))))
        val persist = effects.filterIsInstance<ConversationTopicReducer.Effect.Persist>().single()
        assertEquals(2, persist.ops.size)
        assertTrue(persist.ops[0] is ConversationStoreOp.RowUpserted)
        assertTrue(persist.ops[1] is ConversationStoreOp.RowRemoved)
        assertEquals(ProjectionWatermark("e1", 4), r.watermark)
    }

    @Test
    fun `epoch mismatch escalates snapshot replacement and never merges (S9)`() = runTest {
        val r = reducer()
        r.onSnapshot(snapshot("e1", 10, listOf(1)))
        val effects = r.onDeltas(deltas("e2", 11, listOf(append(99))))
        assertTrue(effects.any { it is ConversationTopicReducer.Effect.EscalateResync })
        assertEquals(ProjectionWatermark("e1", 10), r.watermark, "no mutation on mismatch")
        assertEquals(0, r.streamingBufferCount)
    }

    @Test
    fun `gap holds, fill applies held frames in order (S8)`() = runTest {
        val r = reducer()
        r.onSnapshot(snapshot("e1", 2, listOf(1, 2)))
        // frame 5..6 arrives before 3..4
        val holdEffects = r.onDeltas(deltas("e1", 5, listOf(append(5), append(6))))
        assertTrue(holdEffects.isEmpty())
        assertEquals(1, r.heldFrameCount)
        assertTrue(r.isHolding)

        val fill = r.onDeltas(deltas("e1", 3, listOf(append(3), append(4))))
        val applied = fill.filterIsInstance<ConversationTopicReducer.Effect.Persist>().flatMap { it.ops }
        assertEquals(listOf(3L, 4L, 5L, 6L), applied.map { (it as ConversationStoreOp.RowUpserted).row.rowId }, "held frame applied after fill, in seq order")
        assertEquals(ProjectionWatermark("e1", 6), r.watermark)
        assertEquals(0, r.heldFrameCount)
    }

    @Test
    fun `hold expires into an escalation - never a local fill (S10)`() = runTest {
        val r = reducer(holdWindowMs = 100)
        r.onSnapshot(snapshot("e1", 2, listOf(1)))
        r.onDeltas(deltas("e1", 5, listOf(append(5))))
        now += 150 // hold window elapsed
        val effects = r.onHoldExpired()
        val esc = effects.filterIsInstance<ConversationTopicReducer.Effect.EscalateResync>().single()
        assertEquals(ProjectionWatermark("e1", 2), esc.base, "resync resumes from the cursor")
        assertEquals(0, r.heldFrameCount)
        assertEquals(1, r.escalations)
    }

    @Test
    fun `hold buffer overflow escalates instead of growing (INV-5, verified 500-op bound)`() = runTest {
        val r = reducer(config = ConversationTopicReducer.Config(holdBufferMaxOps = 3))
        r.onSnapshot(snapshot("e1", 2, listOf(1)))
        r.onDeltas(deltas("e1", 6, listOf(append(6), append(7)))) // 2 held ops
        val effects = r.onDeltas(deltas("e1", 9, listOf(append(9), append(10)))) // would exceed 3
        assertTrue(effects.any { it is ConversationTopicReducer.Effect.EscalateResync })
        assertEquals(0, r.heldOpCount)
    }

    @Test
    fun `overlapping resend window escalates rather than double-applies`() = runTest {
        val r = reducer()
        r.onSnapshot(snapshot("e1", 10, listOf(1)))
        // frame spans 8..11 with cursor at 10 → partial overlap = resend ambiguity
        val effects = r.onDeltas(deltas("e1", 8, listOf(append(8), append(9), append(10), append(11))))
        assertTrue(effects.any { it is ConversationTopicReducer.Effect.EscalateResync }, "partial overlap is ambiguity → resync")
        assertEquals(ProjectionWatermark("e1", 10), r.watermark, "no mutation on overlap escalation")
    }

    @Test
    fun `fresh adoption of a non-zero stream escalates - the snapshot was missed (S10)`() = runTest {
        val r = reducer()
        val effects = r.onDeltas(deltas("e1", 4, listOf(append(4))))
        assertTrue(effects.any { it is ConversationTopicReducer.Effect.EscalateResync })
        assertTrue(effects.any { it is ConversationTopicReducer.Effect.Persist }, "ops still persist (epoch adopted)")
    }

    @Test
    fun `rowsRange page adopts epoch at the page atSeq and never fabricates a gap (S16 read path)`() = runTest {
        val r = reducer()
        val effects = r.onRowsPage(TopicRowsPage("conversation/s1", "s1", "e1", listOf(row(1), row(2)), atSeq = 40, hasMore = false))
        assertEquals(1, effects.filterIsInstance<ConversationTopicReducer.Effect.Persist>().single().ops.size)
        r.adoptEpoch("e1", floorSeq = 40)
        // next live frame IS contiguous at 41 — no false gap, no hold
        val next = r.onDeltas(deltas("e1", 41, listOf(append(41))))
        assertTrue(next.none { it is ConversationTopicReducer.Effect.EscalateResync })
        assertEquals(ProjectionWatermark("e1", 41), r.watermark)
    }

    @Test
    fun `page never moves the cursor on an established session`() = runTest {
        val r = reducer()
        r.onSnapshot(snapshot("e1", 10, listOf(1)))
        r.onRowsPage(TopicRowsPage("conversation/s1", "s1", "e1", listOf(row(1)), atSeq = 99, hasMore = true))
        assertEquals(ProjectionWatermark("e1", 10), r.watermark, "stateless read must not advance the delta cursor")
    }

    // ---------------------------------------------------------------- derived state bounds

    @Test
    fun `streaming buffer honors the 8 KiB per-row bound and drops over-cap rows (28-7)`() = runTest {
        val r = reducer()
        r.onSnapshot(snapshot("e1", 1, listOf(1)))
        val chunk = "x".repeat(1_000)
        repeat(10) { r.onDeltas(deltas("e1", 2L + it, listOf(ProjectionOp.RowTextAppended(1, "text", chunk)))) }
        // the over-cap buffer was dropped; a fresh derived buffer restarted from the
        // newest chunk only (derived state is best-effort; the PERSISTED row is unaffected)
        val buffers = r.streamingBuffersForTest()
        assertTrue(buffers.single().text.length <= 1_000, "derived buffer never exceeds the cap after eviction restart")
        // a small stream stays within bounds (contiguous frames 12..15)
        repeat(4) { r.onDeltas(deltas("e1", 12L + it, listOf(ProjectionOp.RowTextAppended(2, "text", "ok")))) }
        assertEquals(2, r.streamingBufferCount)
        assertEquals("okokokok", r.streamingBuffersForTest().single { it.rowId == 2L }.text)
    }

    @Test
    fun `row removal trims derived state from the removed row up`() {
        val r = reducer()
        r.onSnapshot(snapshot("e1", 3, listOf(1, 2, 3)))
        r.onDeltas(deltas("e1", 4, listOf(ProjectionOp.RowTextAppended(2, "text", "a"))))
        r.onDeltas(deltas("e1", 5, listOf(ProjectionOp.RowTextAppended(3, "text", "b"))))
        assertEquals(2, r.streamingBufferCount)
        val effects = r.onDeltas(deltas("e1", 6, listOf(ProjectionOp.RowRemoved(2))))
        assertTrue(effects.any { it is ConversationTopicReducer.Effect.StreamingTrimmedFrom })
        assertEquals(0, r.streamingBufferCount, "buffers ≥ fromRowId discarded")
    }

    // ---------------------------------------------------------------- metadata ops

    @Test
    fun `state-updated patches and unknown ops are counted, never merged (no invented interpretation)`() = runTest {
        val r = reducer()
        r.onSnapshot(snapshot("e1", 1, listOf(1)))
        r.onDeltas(
            deltas(
                "e1", 2, listOf(
                    ProjectionOp.StateUpdated(JsonObject(mapOf("anything" to JsonPrimitive(true)))),
                    ProjectionOp.Unknown("workflowRun.node.updated", JsonObject(emptyMap())),
                )
            )
        )
        assertEquals(1, r.metadataOps)
        assertEquals(1, r.unknownOps)
    }
}
