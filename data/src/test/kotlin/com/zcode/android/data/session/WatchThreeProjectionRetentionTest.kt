package com.zcode.android.data.session

import com.zcode.android.core.common.log.ZLog
import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.domain.ids.SessionId
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.domain.model.SessionProjectionState
import com.zcode.android.domain.model.WorkspaceHookReview
import com.zcode.android.domain.reducer.ProjectionOp
import com.zcode.android.domain.repo.ConversationRepository
import com.zcode.android.domain.repo.ConversationStoreOp
import com.zcode.android.domain.repo.StoreApplyResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * WATCH-3 (vc52 Phase 14) pin — "the subscribe-ack path's projection rebuild
 * transiently drops the pinned plane": after a trust patch → app resync →
 * `gateway.replay frames=0`, the hook card vanished for ONE screen generation
 * while the reconcile still logged the interaction present; it self-healed on the
 * next state delivery. The candidate mechanism was the subscribe/resync-ack path
 * rebuilding the projection WITHOUT state keys.
 *
 * Pinned at the manager level (the projection's single writer):
 *  1. a snapshot WITH state keys carrying the hook interaction lands it;
 *  2. a later snapshot WITHOUT state keys (the legacy shape a resync can re-serve)
 *     RETAINS the interaction — the projection is never rebuilt-empty;
 *  3. a deltas frame carrying only row ops (a resync replay with no state ops —
 *     the `frames` the replay CAN deliver) RETAINS it;
 *  4. a snapshot WITH state keys that no longer lists the interaction replaces the
 *     plane atomically (the §15.5a contract — the one legitimate clear path).
 *
 * Wire-shape fixtures throughout (the §proto/enum blind-spot rule).
 */
class WatchThreeProjectionRetentionTest {

    private class NoStore : ConversationRepository {
        override fun observeRows(sessionId: SessionId): Flow<List<ConversationRow>> = MutableStateFlow(emptyList())
        override fun observeEpoch(sessionId: SessionId): Flow<String?> = MutableStateFlow(null)
        override suspend fun currentEpoch(sessionId: SessionId): String? = null
        override suspend fun apply(op: ConversationStoreOp): StoreApplyResult = StoreApplyResult.Applied
        override suspend fun cachedRowCount(sessionId: SessionId): Int = 0
        override suspend fun minCachedRowId(sessionId: SessionId): Long? = null
        override suspend fun maxCachedRowId(sessionId: SessionId): Long? = null
    }

    private fun manager() = ConversationSessionManager(
        sessionId = SessionId("sess-watch3"),
        endpointId = EndpointId("ep-watch3"),
        store = NoStore(),
        journalSink = { _, _, _, _ -> },
        escalate = { _, _, _ -> },
        onHookReviewCaptured = {},
        log = ZLog(sink = {}),
    )

    /** The hook interaction in its exact wire shape (the payload.kind discriminator). */
    private fun hookInteractionJson() = buildJsonObject {
        put("interactionId", "hook-watch3")
        put("kind", "workspaceHookReview")
        put("anchorRowId", kotlinx.serialization.json.JsonNull)
        put("createdAt", JsonPrimitive(1_700_000_000_000.0))
        putJsonObject("payload") {
            put("kind", "workspaceHookReview")
            put("reviewFlowId", "flow-watch3")
            put("generation", JsonPrimitive(1))
            put("interactionId", "hook-watch3")
            put("sessionId", "sess-watch3")
            put("taskId", "sess-watch3")
            put("runId", "run-1")
            put("workspaceIdentity", "synthetic-ws")
            put("workspaceLabel", "Synthetic Workspace")
            put("bundleDigest", "a".repeat(64))
            put("createdAt", JsonPrimitive(1_700_000_000_000.0))
            put("deadlineAt", JsonPrimitive(1_700_000_300_000.0))
            putJsonObject("summary") {
                put("eventCount", JsonPrimitive(2))
                put("hookCount", JsonPrimitive(1))
                put("pendingCount", JsonPrimitive(1))
            }
            put("items", buildJsonArray {})
        }
    }

    private fun snapshotState(vararg interactions: JsonObject): SessionProjectionState =
        SessionProjectionState.fromSnapshot(buildJsonObject {
            put("revision", JsonPrimitive(101))
            put("pendingInteractions", buildJsonArray { interactions.forEach { add(it) } })
        })

    private fun snapshotEvent(
        state: SessionProjectionState?,
        toSeq: Long = 3,
        epoch: String = "epoch-1",
    ) = GatewayTopicEvent.Snapshot(
        topic = "conversation/sess-watch3",
        sessionId = "sess-watch3",
        logEpoch = epoch,
        fromSeq = 0,
        toSeq = toSeq,
        rows = listOf(bodyRow(1)),
        totalCount = 1,
        firstRowId = 1,
        sessionState = state,
    )

    private fun rowOpsEvent(toSeq: Long) = GatewayTopicEvent.Deltas(
        topic = "conversation/sess-watch3",
        sessionId = "sess-watch3",
        logEpoch = "epoch-1",
        fromSeq = 3,
        toSeq = toSeq,
        ops = listOf(ProjectionOp.RowAppended(bodyRow(toSeq))),
    )

    private fun bodyRow(rowId: Long): ConversationRow =
        ConversationRow.Decoder.decode(
            Json.parseToJsonElement(
                """{"rowId":$rowId,"turnId":"turn-1","kind":"assistantText","text":"synthetic body $rowId",
                   "state":"complete","createdAt":1.0,"createdAtSeq":$rowId}""",
            ) as JsonObject,
        )!!

    private fun pendingIds(m: ConversationSessionManager): List<String> =
        m.sessionState.value.pendingInteractions.orEmpty().map { it.interactionId }

    @Test
    fun `snapshot WITH state keys lands the hook interaction`() = runTest {
        val m = manager()
        m.handle(snapshotEvent(snapshotState(hookInteractionJson())))
        assertEquals(listOf("hook-watch3"), pendingIds(m))
    }

    @Test
    fun `snapshot WITHOUT state keys RETAINS the projection (never rebuilt-empty)`() = runTest {
        val m = manager()
        m.handle(snapshotEvent(snapshotState(hookInteractionJson())))
        // The resync's re-served legacy-shape snapshot: NO state block (sessionState=null).
        m.handle(snapshotEvent(state = null, toSeq = 5))
        assertEquals(
            listOf("hook-watch3"), pendingIds(m),
            "the subscribe/resync-ack path must not rebuild the projection without state keys (the WATCH-3 candidate mechanism)",
        )
    }

    @Test
    fun `a row-op replay (resync deltas, no state ops) RETAINS the projection`() = runTest {
        val m = manager()
        m.handle(snapshotEvent(snapshotState(hookInteractionJson())))
        m.handle(rowOpsEvent(toSeq = 6))
        assertEquals(
            listOf("hook-watch3"), pendingIds(m),
            "a deltas frame without a state.updated op never touches the projection",
        )
    }

    @Test
    fun `the retained interaction still feeds the hook-review ledger`() = runTest {
        val m = manager()
        m.handle(snapshotEvent(snapshotState(hookInteractionJson())))
        m.handle(snapshotEvent(state = null, toSeq = 5))
        assertNotNull(
            m.lastHookReview.value,
            "the trust card's data survives the stateless frame (the one-generation vanish is refuted at the writer)",
        )
    }

    @Test
    fun `a snapshot WITH state keys that drops the interaction replaces the plane atomically`() = runTest {
        val m = manager()
        m.handle(snapshotEvent(snapshotState(hookInteractionJson())))
        // The one legitimate clear: a fresh state block that lists NOTHING.
        m.handle(snapshotEvent(snapshotState(), toSeq = 7))
        assertEquals(
            emptyList(), m.sessionState.value.pendingInteractions.orEmpty(),
            "the §15.5a atomic replacement still applies when the frame CARRIES state keys (an empty list IS the truth)",
        )
    }
}
