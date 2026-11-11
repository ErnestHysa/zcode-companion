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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * G71 (vc52 Phase 12) pin: the manager RETAINS the last workspace hook review —
 * a snapshot/patch carrying a `workspaceHookReview` interaction updates
 * [ConversationSessionManager.lastHookReview]; later merges WITHOUT the interaction
 * keep the retained review (the trust card resolves, the Settings viewer still
 * shows what the workspace runs); a fresh review (new generation) replaces it.
 * Fixtures ride the WIRE shape (payload.kind discriminator) — never the in-memory
 * model alone (the §proto/enum blind-spot rule).
 */
class ManagerHookReviewLedgerTest {

    private class NoStore : ConversationRepository {
        override fun observeRows(sessionId: SessionId): Flow<List<ConversationRow>> = MutableStateFlow(emptyList())
        override fun observeEpoch(sessionId: SessionId): Flow<String?> = MutableStateFlow(null)
        override suspend fun currentEpoch(sessionId: SessionId): String? = null
        override suspend fun apply(op: ConversationStoreOp): StoreApplyResult = StoreApplyResult.Applied
        override suspend fun cachedRowCount(sessionId: SessionId): Int = 0
    }

    private fun manager(
        onCaptured: (WorkspaceHookReview) -> Unit = {},
    ) = ConversationSessionManager(
        sessionId = SessionId("sess-hooks"),
        endpointId = EndpointId("ep"),
        store = NoStore(),
        journalSink = { _, _, _, _ -> },
        escalate = { _, _, _ -> },
        onHookReviewCaptured = onCaptured,
        log = ZLog(sink = {}),
    )

    /** The wire shape of one workspaceHookReview pending interaction. */
    private fun reviewInteractionJson(generation: Long, pending: Int) = buildJsonObject {
        put("interactionId", "hook-$generation")
        put("kind", "workspaceHookReview")
        put("anchorRowId", JsonNull)
        put("createdAt", JsonPrimitive(1700000000000.0))
        putJsonObject("payload") {
            put("kind", "workspaceHookReview")
            put("reviewFlowId", "flow-$generation")
            put("generation", JsonPrimitive(generation))
            put("interactionId", "hook-$generation")
            put("sessionId", "sess-hooks")
            put("taskId", "sess-hooks")
            put("runId", "run")
            put("workspaceIdentity", "synthetic-ws")
            put("workspaceLabel", "Synthetic Workspace")
            put("bundleDigest", "a".repeat(64))
            put("createdAt", JsonPrimitive(1700000000000.0))
            put("deadlineAt", JsonPrimitive(1700000300000.0))
            putJsonObject("summary") {
                put("eventCount", JsonPrimitive(2))
                put("hookCount", JsonPrimitive(3))
                put("pendingCount", JsonPrimitive(pending))
            }
            put("items", buildJsonArray {})
        }
    }

    private fun snapshotWith(review: kotlinx.serialization.json.JsonObject?, toSeq: Long = 3) = GatewayTopicEvent.Snapshot(
        topic = "conversation/sess-hooks",
        sessionId = "sess-hooks",
        logEpoch = "epoch-1",
        fromSeq = 0,
        toSeq = toSeq,
        rows = emptyList(),
        sessionState = SessionProjectionState.fromSnapshot(
            buildJsonObject {
                if (review != null) {
                    put("pendingInteractions", buildJsonArray { add(review) })
                } else {
                    put("pendingInteractions", buildJsonArray {})
                }
            },
        ),
    )

    private fun patchWith(review: kotlinx.serialization.json.JsonObject?) = GatewayTopicEvent.Deltas(
        topic = "conversation/sess-hooks",
        sessionId = "sess-hooks",
        logEpoch = "epoch-1",
        fromSeq = 4,
        toSeq = 5,
        ops = listOf(
            ProjectionOp.StateUpdated(
                buildJsonObject {
                    put("revision", JsonPrimitive(2))
                    put("pendingInteractions", buildJsonArray { if (review != null) add(review) })
                },
            ),
        ),
    )

    @Test
    fun `a snapshot with a hook review populates the ledger`() = runTest {
        val m = manager()
        assertNull(m.lastHookReview.value)
        m.handle(snapshotWith(reviewInteractionJson(1, pending = 2)))
        assertEquals("flow-1", m.lastHookReview.value?.reviewFlowId)
        assertEquals(2, m.lastHookReview.value?.summary?.pendingCount)
    }

    @Test
    fun `later merges without the interaction keep the retained review`() = runTest {
        val m = manager()
        m.handle(snapshotWith(reviewInteractionJson(1, pending = 2)))
        // an EMPTY pending list = the resolved face (the trust card cleared)
        m.handle(snapshotWith(null))
        assertEquals("flow-1", m.lastHookReview.value?.reviewFlowId, "resolved review stays visible to the viewer")
    }

    @Test
    fun `a patch-carried review updates the ledger too`() = runTest {
        val m = manager()
        m.handle(patchWith(reviewInteractionJson(2, pending = 1)))
        assertEquals("flow-2", m.lastHookReview.value?.reviewFlowId)
    }

    @Test
    fun `the capture ALSO fires the process-ledger callback (the exit-gate fix — survives detach)`() = runTest {
        val ledger = ArrayList<WorkspaceHookReview>()
        val m = manager(onCaptured = { ledger.add(it) })
        m.handle(snapshotWith(reviewInteractionJson(1, pending = 2), toSeq = 3))
        assertEquals(1, ledger.size, "the callback fired with the review")
        assertEquals("flow-1", ledger.single().reviewFlowId)
        // A fresh review updates the ledger too (deduped by equality — no refires).
        m.handle(snapshotWith(reviewInteractionJson(1, pending = 2), toSeq = 4))
        assertEquals(1, ledger.size, "the identical review does not refire")
    }

    @Test
    fun `a fresh review replaces the retained one`() = runTest {
        val m = manager()
        m.handle(snapshotWith(reviewInteractionJson(1, pending = 2), toSeq = 3))
        // Distinct toSeq: the manager's snapshot dedupe key (epoch/toSeq/rows) must not
        // swallow the follow-up state (a fresh review IS a different snapshot).
        m.handle(snapshotWith(reviewInteractionJson(3, pending = 0), toSeq = 9))
        assertEquals("flow-3", m.lastHookReview.value?.reviewFlowId)
        assertEquals(0, m.lastHookReview.value?.summary?.pendingCount)
    }
}

private val JsonNull = kotlinx.serialization.json.JsonNull
