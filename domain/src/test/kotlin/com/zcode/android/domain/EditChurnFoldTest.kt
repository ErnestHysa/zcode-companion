package com.zcode.android.domain

import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.domain.reducer.ConversationTopicReducer
import com.zcode.android.domain.reducer.ProjectionOp
import com.zcode.android.domain.repo.ConversationStoreOp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * GAP-10 (android-native-gaps.md; docs/protocol/v4-wire.md §11.4) — the
 * §20.3-style reconciliation for the editUserQuery turn replacement: the desktop
 * REWRITES the turn, and the wire consequence is row churn the client must FOLD,
 * never append. Golden ops from the GENERATED fixture (rule 5):
 * `row.removed {fromRowId}` (delta.ts:99-100 "删除该行及之后所有") + the
 * replacement turn's rows. The removed rows' streaming buffers are discarded
 * with them; the replacement rows append normally; the cursor advances; no
 * resync escalates (same-epoch churn).
 */
class EditChurnFoldTest {

    private fun churnOps(): List<ProjectionOp> {
        val file = sequenceOf(File("."), File(".."), File("../.."))
            .map { it.resolve("fixtures/protocol/phase2-wire-fixtures.json") }
            .firstOrNull { it.isFile }
            ?: error("phase2-wire-fixtures.json not found")
        val cases = Json.parseToJsonElement(file.readText()).jsonObject["cases"]!!
            as kotlinx.serialization.json.JsonArray
        val data = cases.map { it.jsonObject }
            .first { it["name"]!!.toString().trim('"') == "edit-churn-ops" }["data"]!!
            as kotlinx.serialization.json.JsonArray
        return data.map { el ->
            val obj = el.jsonObject
            when (obj["op"]!!.toString().trim('"')) {
                "row.removed" -> ProjectionOp.RowRemoved(obj["fromRowId"]!!.toString().trim('"').toLong())
                "row.appended" -> ProjectionOp.RowAppended(ConversationRow.Decoder.decode(obj["row"]!!.jsonObject))
                else -> error("unexpected op")
            }
        }
    }

    @Test
    fun `edit churn folds - truncation plus replacement rows, same epoch, no escalation`() = runTest {
        val reducer = ConversationTopicReducer(com.zcode.android.domain.ids.SessionId("s1"))
        // Baseline: rows 40..45 in epoch e1 through seq 799.
        reducer.onSnapshot(
            TopicEvent.Snapshot(
                topic = "conversation/s1", sessionId = "s1", logEpoch = "e1",
                fromSeq = 0, toSeq = 799,
                rows = (40L..45L).map { id ->
                    ConversationRow.Unknown(
                        rowId = id, turnId = "t-$id", unknownKind = "gen",
                        raw = kotlinx.serialization.json.JsonObject(
                            mapOf("rowId" to kotlinx.serialization.json.JsonPrimitive(id)),
                        ),
                    )
                },
            ),
        )
        // The churn: remove from rowId 41, then the replacement turn arrives.
        val ops = churnOps()
        val effects = reducer.onDeltas(
            TopicEvent.Deltas(
                topic = "conversation/s1", sessionId = "s1", logEpoch = "e1",
                fromSeq = 799, toSeq = 802, ops = ops,
            ),
        )
        val persists = effects.filterIsInstance<ConversationTopicReducer.Effect.Persist>().single()
        val ops0 = persists.ops
        assertTrue(ops0[0] is ConversationStoreOp.RowRemoved, "first op folds the truncation")
        assertEquals(41L, (ops0[0] as ConversationStoreOp.RowRemoved).fromRowId)
        assertTrue(ops0[1] is ConversationStoreOp.RowUpserted, "replacement turnHeader lands")
        assertTrue(ops0[2] is ConversationStoreOp.RowUpserted, "replacement userInput lands")
        assertEquals(50L, (ops0[1] as ConversationStoreOp.RowUpserted).row.rowId)
        assertTrue(
            effects.any { it is ConversationTopicReducer.Effect.StreamingTrimmedFrom && it.fromRowId == 41L },
            "streaming buffers of removed rows are discarded (§15.5c)",
        )
        assertTrue(
            effects.none { it is ConversationTopicReducer.Effect.EscalateResync },
            "same-epoch churn FOLDS — never a resync, never an append past the cut",
        )
        assertEquals(802L, reducer.watermark?.seq, "cursor advances through the churn")
    }
}
