package com.zcode.android.data.session

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.zcode.android.data.db.ZcodeDatabase
import com.zcode.android.data.repo.InteractionRepositoryImpl
import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.domain.ids.InteractionId
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.domain.model.SessionProjectionState
import com.zcode.android.domain.repo.InteractionRepository
import com.zcode.android.domain.repo.OutboxRepository
import com.zcode.android.domain.ids.CommandId
import com.zcode.android.domain.outbox.OutboxStateMachine
import com.zcode.android.domain.outbox.OutboxEntry
import com.zcode.android.core.protocol.v4.CommandAck
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * GAP-A2 (vc51 roadmap, sub-phase 1A; 11 live receipts): the Approvals inbox never
 * clears answered interactions. Every terminal path calls markResolved (which stamps
 * resolvedAtMs) and THEN updateLifecycle, whose full-row upsert re-writes resolvedAtMs
 * from the request object (null) — so the record reopens instantly and `observeOpen`
 * (`resolvedAtMs IS NULL`, the inbox/badge oracle) keeps serving the card forever.
 *
 * The pins use the REAL Room-backed repository — the clobber lives in the upsert
 * semantics a recording fake cannot observe — with the inbox oracle being exactly
 * what ApprovalsScreen reads: observeOpen(endpointId).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ApprovalsInboxResolutionTest {

    private lateinit var db: ZcodeDatabase
    private lateinit var interactions: InteractionRepositoryImpl
    private val ep = EndpointId("ep")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            ZcodeDatabase::class.java,
        ).allowMainThreadQueries().build()
        interactions = InteractionRepositoryImpl(db)
    }

    @After
    fun tearDown() = db.close()

    private class Sender : CommandSender {
        var status: String = "accepted"
        val sent = mutableListOf<Pair<String, JsonObject>>()
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
            sent.add(type to payload)
            return CommandAck(commandId, status, 1, null, null, null)
        }
    }

    private class Outbox : OutboxRepository {
        private class Row(var state: OutboxStateMachine.State)
        private val rows = LinkedHashMap<String, Row>()
        private var minted = 0
        override fun observePending(endpointId: EndpointId) = kotlinx.coroutines.flow.flowOf(emptyList<OutboxEntry>())
        override suspend fun accept(
            endpointId: EndpointId, sessionId: String?, type: String, payloadJson: String,
            baseRevision: Long?, baseLogEpoch: String?, issuedAtMs: Long,
        ): CommandId {
            minted++
            val id = CommandId("cmd-$minted")
            rows[id.raw] = Row(OutboxStateMachine.State.ACCEPTED_LOCALLY)
            return id
        }

        override suspend fun apply(commandId: CommandId, event: OutboxStateMachine.Event, atMs: Long): OutboxEntry? {
            rows[commandId.raw]?.let { it.state = OutboxStateMachine.restore(it.state).on(event) }
            return null
        }

        override suspend fun get(commandId: CommandId): OutboxEntry? = rows[commandId.raw]?.let {
            OutboxEntry(
                commandId = commandId, endpointId = "ep", sessionId = "s", type = "resolveInteraction",
                payloadJson = "{}", baseRevision = null, baseLogEpoch = null, issuedAtMs = 0,
                state = it.state, attempts = 0, lastTransitionAtMs = 0,
            )
        }

        override suspend fun sweepExpired(endpointId: EndpointId, nowMs: Long, ttlMs: Long): Int = 0
        override suspend fun pendingRefs(endpointId: EndpointId): List<com.zcode.android.domain.journal.RecoveryJournal.PendingCommandRef> = emptyList()
        override suspend fun countByState(endpointId: EndpointId, state: OutboxStateMachine.State): Int = 0
    }

    private fun coordinator(sender: Sender = Sender()): InteractionCoordinator =
        InteractionCoordinator(endpointId = ep, interactions = interactions, outbox = Outbox(), commands = sender)

    private fun permissionState(interactionId: String): SessionProjectionState =
        SessionProjectionState.fromSnapshot(
            buildJsonObject {
                putJsonArray("pendingInteractions") {
                    add(
                        buildJsonObject {
                            put("interactionId", interactionId)
                            put("kind", "permission")
                            put("anchorRowId", JsonNull)
                            put("createdAt", 1700000000000.0)
                            putJsonObject("payload") {
                                put("kind", "permission")
                                put("toolCallId", "tc-1")
                                put("toolName", "Bash")
                                put("summary", "Run the build?")
                                putJsonArray("options") {
                                    add(buildJsonObject { put("optionId", "allowOnce"); put("label", "Allow once"); put("kind", "allowOnce") })
                                    add(buildJsonObject { put("optionId", "allowAlways"); put("label", "Always"); put("kind", "allowAlways") })
                                    add(buildJsonObject { put("optionId", "deny"); put("label", "No"); put("kind", "deny") })
                                }
                            }
                        },
                    )
                }
            },
        )

    private fun pendingApprovalRow(interactionId: String): ConversationRow.ToolCall =
        ConversationRow.ToolCall(
            rowId = 1,
            turnId = "turn-1",
            base = ConversationRow.Base(createdAt = 1700000000000.0, createdAtSeq = 1),
            assistantResponseId = null,
            toolCallId = "tc-1",
            toolName = "Bash",
            status = ConversationRow.ToolCallStatus.PENDING_APPROVAL,
            inputText = "echo synthetic",
            input = buildJsonObject { put("command", "echo synthetic") },
            outputPreview = null,
            error = null,
            approvalInteractionId = interactionId,
            startedAt = null,
            endedAt = null,
            raw = buildJsonObject { },
        )

    private suspend fun openIds(): List<String> =
        interactions.observeOpen(ep).first().map { it.interactionId.raw }

    @Test
    fun `A2 - a state-plane permission answered from the card clears the inbox record`() = runTest {
        val c = coordinator()
        c.reconcileFromSessionState("sess-1", permissionState("mock-approval-a2"))
        c.render(InteractionId("mock-approval-a2"))
        assertTrue("precondition: the record is open before the answer", "mock-approval-a2" in openIds())
        val outcome = c.submit(InteractionId("mock-approval-a2"), "allowOnce")
        assertTrue("the answer must dispatch", outcome is InteractionCoordinator.SubmitOutcome.Submitted)
        assertTrue(
            "the inbox record must clear after an accepted answer (GAP-A2)",
            "mock-approval-a2" !in openIds(),
        )
    }

    @Test
    fun `A2 - state-plane disappearance resolves the record without reopening it`() = runTest {
        val c = coordinator()
        c.reconcileFromSessionState("sess-1", permissionState("mock-approval-d"))
        assertTrue("precondition: open before disappearance", "mock-approval-d" in openIds())
        // The desktop resolved it elsewhere: the pendingInteractions list drops it.
        val resolved = SessionProjectionState.fromSnapshot(
            buildJsonObject { putJsonArray("pendingInteractions") { } },
        )
        c.reconcileFromSessionState("sess-1", resolved)
        assertTrue(
            "a state-plane-resolved record must stay closed (GAP-A2)",
            "mock-approval-d" !in openIds(),
        )
    }

    @Test
    fun `A2 - a row-anchored approval answered from the inbox clears the inbox record`() = runTest {
        val c = coordinator()
        c.reconcileFromSnapshot(listOf(pendingApprovalRow("mock-approva-row")), sessionId = "sess-1")
        assertTrue("precondition: open before the answer", "mock-approva-row" in openIds())
        val outcome = c.submitFromStore(InteractionId("mock-approva-row"), "allowOnce")
        assertTrue("the inbox answer must dispatch", outcome is InteractionCoordinator.SubmitOutcome.Submitted)
        assertTrue(
            "the row-anchored inbox record must clear after an accepted answer (GAP-A2)",
            "mock-approva-row" !in openIds(),
        )
    }
}
