package com.zcode.android.data.session

import com.zcode.android.core.common.log.ZLog
import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.domain.ids.SessionId
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.domain.model.WorkflowRunDelta
import com.zcode.android.domain.model.WorkflowRunPlane
import com.zcode.android.domain.model.WorkflowRunStep
import com.zcode.android.domain.reducer.ProjectionOp
import com.zcode.android.domain.repo.ConversationRepository
import com.zcode.android.domain.repo.ConversationStoreOp
import com.zcode.android.domain.repo.StoreApplyResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject

/**
 * G51 (vc52 Phase 11) manager fan-out pin: the single-writer manager folds
 * `workflowRun.updated`/`workflowRun.removed` ops into the per-session
 * WorkflowRunPlane (graphs + deltasSeen + count) and fires the delta consumer —
 * the plane the run-sheet step graph and the poll-stop face read. A new-epoch
 * snapshot RESETS the plane (stale graphs from a dead epoch never survive).
 */
class WorkflowRunDeltaFanOutTest {

    private class NoStore : ConversationRepository {
        override fun observeRows(sessionId: SessionId): Flow<List<ConversationRow>> = MutableStateFlow(emptyList())
        override fun observeEpoch(sessionId: SessionId): Flow<String?> = MutableStateFlow(null)
        override suspend fun currentEpoch(sessionId: SessionId): String? = null
        override suspend fun apply(op: ConversationStoreOp): StoreApplyResult = StoreApplyResult.Applied
        override suspend fun cachedRowCount(sessionId: SessionId): Int = 0
        override suspend fun minCachedRowId(sessionId: SessionId): Long? = null
        override suspend fun maxCachedRowId(sessionId: SessionId): Long? = null
    }

    private fun manager(onDelta: suspend (WorkflowRunDelta) -> Unit = {}) = ConversationSessionManager(
        sessionId = SessionId("sess-runplane"),
        endpointId = EndpointId("ep"),
        store = NoStore(),
        journalSink = { _, _, _, _ -> },
        escalate = { _, _, _ -> },
        onWorkflowRunDelta = onDelta,
        log = ZLog(sink = {}),
    )

    private fun step(siteId: String, ordinal: Long) = WorkflowRunStep.decode(
        JsonObject(mapOf(
            "siteId" to kotlinx.serialization.json.JsonPrimitive(siteId),
            "ordinal" to kotlinx.serialization.json.JsonPrimitive(ordinal),
            "phase" to kotlinx.serialization.json.JsonPrimitive("settled"),
        )),
    )!!

    private fun deltas(vararg ops: ProjectionOp, fromSeq: Long = 1, toSeq: Long = 2) = GatewayTopicEvent.Deltas(
        topic = "conversation/sess-runplane",
        sessionId = "sess-runplane",
        logEpoch = "epoch-1",
        fromSeq = fromSeq,
        toSeq = toSeq,
        ops = ops.toList(),
    )

    private fun updatedOp(runId: String, actors: List<WorkflowRunStep> = emptyList(), nodes: List<WorkflowRunStep> = emptyList()) =
        ProjectionOp.WorkflowRunUpdated(
            WorkflowRunDelta.Updated(runId, 1, JsonObject(emptyMap()), emptyList(), emptyList(), emptyList(), actors, nodes, JsonObject(emptyMap())),
        )

    @Test
    fun `updated ops fold into the plane and fire the consumer`() = runTest {
        val seen = ArrayList<WorkflowRunDelta>()
        val m = manager { seen.add(it) }
        m.handle(deltas(updatedOp("r1", nodes = listOf(step("n1", 0)))))
        val plane = m.workflowRunPlane.value
        assertTrue(plane.deltasSeen)
        assertEquals(1L, plane.deltaCount)
        assertEquals(1, plane.graphs["r1"]?.nodes?.size)
        assertEquals(1, seen.size)
        assertEquals("r1", (seen.first() as WorkflowRunDelta.Updated).runId)
    }

    @Test
    fun `removed ops drop the graph and count`() = runTest {
        val m = manager()
        m.handle(deltas(updatedOp("r1")))
        m.handle(deltas(ProjectionOp.WorkflowRunRemoved("r1", 2), fromSeq = 3, toSeq = 4))
        val plane = m.workflowRunPlane.value
        assertEquals(2L, plane.deltaCount)
        assertEquals(null, plane.graphs["r1"])
        assertTrue(plane.deltasSeen)
    }

    @Test
    fun `a new-epoch snapshot resets the plane (stale graphs never survive recovery)`() = runTest {
        val m = manager()
        m.handle(deltas(updatedOp("r1")))
        assertTrue(m.workflowRunPlane.value.deltasSeen)
        m.handle(
            GatewayTopicEvent.Snapshot(
                topic = "conversation/sess-runplane",
                sessionId = "sess-runplane",
                logEpoch = "epoch-2",
                fromSeq = 0,
                toSeq = 5,
                rows = emptyList(),
            ),
        )
        val plane = m.workflowRunPlane.value
        assertEquals(WorkflowRunPlane.EMPTY, plane, "the dead epoch's graphs + deltas-flowing face reset")
    }

    @Test
    fun `the same-epoch snapshot keeps the plane (reconciliation snapshots repeat)`() = runTest {
        val m = manager()
        m.handle(deltas(updatedOp("r1")))
        m.handle(
            GatewayTopicEvent.Snapshot(
                topic = "conversation/sess-runplane",
                sessionId = "sess-runplane",
                logEpoch = "epoch-1",
                fromSeq = 0,
                toSeq = 5,
                rows = emptyList(),
            ),
        )
        assertTrue(m.workflowRunPlane.value.deltasSeen, "same epoch = reconciliation, the plane stays")
    }
}
