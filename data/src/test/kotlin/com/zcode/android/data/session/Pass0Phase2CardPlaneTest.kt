package com.zcode.android.data.session

import com.zcode.android.core.protocol.v4.CommandAck
import com.zcode.android.domain.ids.CommandId
import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.domain.ids.InteractionId
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.domain.model.InteractionLifecycle
import com.zcode.android.domain.model.PermissionRequest
import com.zcode.android.domain.model.SessionProjectionState
import com.zcode.android.domain.model.UserInputRequest
import com.zcode.android.domain.outbox.OutboxEntry
import com.zcode.android.domain.outbox.OutboxStateMachine
import com.zcode.android.domain.repo.InteractionRepository
import com.zcode.android.domain.repo.OutboxRepository
import com.zcode.android.domain.state.PermissionStateMachine
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * pass-0 Phase 2 pins at the interaction-coordinator plane.
 *
 * F-P0-B (the most re-manifested row in the ledger — 8 stamps, passes 3–9): the
 * ROWS reconcile sweep staled STATE-PLANE-delivered permission machines. The two
 * reconcilers fought: the rows tail (a bounded toolCall window that never carries
 * state-plane ids) dropped the id → the sweep fired ResnapshotLacks → the machine
 * went STALE while the authoritative pendingInteractions list still carried it →
 * the card rendered terminal-but-actionable (the answer died a silent
 * AlreadyResolved / DuplicateNoOp). The U9 exemption (user-input) and the T2
 * exemption (row-anchored) protected two of the three machine origins — the
 * state-plane PERMISSION origin was the third.
 */
class Pass0Phase2CardPlaneTest {

    // ------------------------------------------------------------------ fakes

    private class RecordingInteractions : InteractionRepository {
        val upserts = mutableListOf<Pair<InteractionId, InteractionLifecycle>>()
        val resolved = mutableListOf<InteractionId>()

        override fun observeOpen(endpointId: EndpointId) =
            kotlinx.coroutines.flow.flowOf(emptyList<InteractionRepository.InteractionRecord>())

        override suspend fun upsertPermission(request: PermissionRequest, endpointId: EndpointId, lifecycle: InteractionLifecycle) {
            upserts.add(request.interactionId to lifecycle)
        }

        override suspend fun upsertUserInput(request: UserInputRequest, endpointId: EndpointId, lifecycle: InteractionLifecycle) {
            upserts.add(request.interactionId to lifecycle)
        }

        override suspend fun markResolved(endpointId: EndpointId, interactionId: InteractionId, atMs: Long, resolvedBySelf: Boolean) {
            resolved.add(interactionId)
        }

        override suspend fun listOpen(endpointId: EndpointId): List<InteractionRepository.InteractionRecord> = emptyList()

        override suspend fun sweep(endpointId: EndpointId, nowMs: Long): Int = 0
        override suspend fun count(endpointId: EndpointId): Int = upserts.size

        override suspend fun loadStored(endpointId: EndpointId, interactionId: InteractionId): InteractionRepository.StoredInteraction? = null
    }

    private class ScriptedSender : CommandSender {
        val sent = mutableListOf<String>()

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
            sent.add(type)
            return CommandAck(commandId, "accepted", 1, null, null, null)
        }
    }

    /** Tiny outbox fake (the InteractionCoordinatorPhase7Test InMemoryTestOutbox shape). */
    private class InMemoryOutbox : OutboxRepository {
        private class Row(val type: String, var state: OutboxStateMachine.State, val baseRevision: Long?)

        private val rows = LinkedHashMap<String, Row>()
        var minted = 0
            private set

        override suspend fun accept(
            endpointId: EndpointId,
            sessionId: String?,
            type: String,
            payloadJson: String,
            baseRevision: Long?,
            baseLogEpoch: String?,
            issuedAtMs: Long,
        ): CommandId {
            minted++
            val id = CommandId("cmd-$minted")
            rows[id.raw] = Row(type, OutboxStateMachine.State.ACCEPTED_LOCALLY, baseRevision)
            return id
        }

        override suspend fun apply(commandId: CommandId, event: OutboxStateMachine.Event, atMs: Long): OutboxEntry? {
            val row = rows[commandId.raw] ?: return null
            row.state = OutboxStateMachine.restore(row.state).on(event)
            return null
        }

        override suspend fun get(commandId: CommandId): OutboxEntry? =
            rows[commandId.raw]?.let {
                OutboxEntry(
                    commandId = commandId, endpointId = "ep", sessionId = "s", type = it.type,
                    payloadJson = "{}", baseRevision = it.baseRevision, baseLogEpoch = null,
                    issuedAtMs = 0, state = it.state, attempts = 0, lastTransitionAtMs = 0,
                )
            }

        override fun observePending(endpointId: EndpointId) = kotlinx.coroutines.flow.flowOf(emptyList<OutboxEntry>())
        override suspend fun sweepExpired(endpointId: EndpointId, nowMs: Long, ttlMs: Long): Int = 0
        override suspend fun sweepTerminal(endpointId: EndpointId, nowMs: Long, ttlMs: Long): Int = 0
        override suspend fun pendingRefs(endpointId: EndpointId) = emptyList<com.zcode.android.domain.journal.RecoveryJournal.PendingCommandRef>()
        override suspend fun countByState(endpointId: EndpointId, state: OutboxStateMachine.State): Int =
            rows.values.count { it.state == state }
    }

    // ------------------------------------------------------------------ fixtures

    private fun permissionState(interactionId: String, tool: String = "Bash"): SessionProjectionState =
        SessionProjectionState.fromSnapshot(
            buildJsonObject {
                putJsonArray("pendingInteractions") {
                    add(
                        buildJsonObject {
                            put("interactionId", interactionId)
                            put("kind", "permission")
                            put("createdAt", 1700000000000.0)
                            putJsonObject("payload") {
                                put("kind", "permission")
                                put("toolName", tool)
                                put("summary", "Run the build?")
                                putJsonArray("options") {
                                    add(buildJsonObject { put("optionId", "allowOnce"); put("label", "Allow once"); put("kind", "allowOnce") })
                                    add(buildJsonObject { put("optionId", "deny"); put("label", "No"); put("kind", "deny") })
                                }
                            }
                        },
                    )
                }
            },
        )

    private fun userInputState(interactionId: String): SessionProjectionState =
        SessionProjectionState.fromSnapshot(
            buildJsonObject {
                putJsonArray("pendingInteractions") {
                    add(
                        buildJsonObject {
                            put("interactionId", interactionId)
                            put("kind", "userInput")
                            put("createdAt", 1700000000000.0)
                            putJsonObject("payload") {
                                put("kind", "userInput")
                                put("prompt", "Pick one")
                                put("freeText", false)
                                putJsonArray("options") {
                                    add(buildJsonObject { put("optionId", "email"); put("label", "Email") })
                                }
                            }
                        },
                    )
                }
            },
        )

    private fun pendingApprovalRow(interactionId: String, status: ConversationRow.ToolCallStatus): ConversationRow.ToolCall =
        ConversationRow.ToolCall(
            rowId = 1,
            turnId = "t1",
            base = ConversationRow.Base(createdAt = 1700000000000.0, createdAtSeq = 1),
            assistantResponseId = null,
            toolCallId = "tc-1",
            toolName = "Bash",
            status = status,
            inputText = "",
            input = null,
            outputPreview = null,
            error = null,
            approvalInteractionId = interactionId,
            startedAt = null,
            endedAt = null,
            raw = buildJsonObject { put("kind", "toolCall") },
        )

    private fun coordinator(
        interactions: RecordingInteractions = RecordingInteractions(),
        sender: ScriptedSender = ScriptedSender(),
    ): Triple<InteractionCoordinator, RecordingInteractions, ScriptedSender> {
        val c = InteractionCoordinator(
            endpointId = EndpointId("ep"),
            interactions = interactions,
            outbox = InMemoryOutbox(),
            commands = sender,
        )
        return Triple(c, interactions, sender)
    }

    // ------------------------------------------------------------------ F-P0-B

    @Test
    fun `F-P0-B - a state-plane permission survives the rows sweep and stays answerable`() = runTest {
        val (coordinator, interactions, sender) = coordinator()
        // 1. The state plane delivers the permission (the dual-plane delivery).
        coordinator.reconcileFromSessionState("sess-1", permissionState("i-sp-1"))
        assertEquals(
            PermissionStateMachine.State.DISPLAYED,
            coordinator.machineStateForTest(InteractionId("i-sp-1")),
        )
        // 2. A ROWS snapshot arrives whose tail carries NO interaction ids (the rows
        //    plane only ever anchors its own row-schema ids — a state-plane id is
        //    never in the tail). Pre-fix this staled the machine here.
        coordinator.reconcileFromSnapshot(rows = emptyList(), sessionId = "sess-1")
        assertEquals(
            coordinator.machineStateForTest(InteractionId("i-sp-1")),
            PermissionStateMachine.State.DISPLAYED,
            "the rows sweep must never stale a state-plane-delivered permission (the two reconcilers fight)",
        )
        assertTrue(interactions.resolved.isEmpty(), "no store resolution may ride the rows sweep for a state-plane card")
        // 3. The dual-plane answer leg — the card must still be actionable.
        val outcome = coordinator.submit(InteractionId("i-sp-1"), "allowOnce")
        assertTrue(outcome is InteractionCoordinator.SubmitOutcome.Submitted, "the state-plane card must answer after the rows sweep; got $outcome")
        assertEquals(listOf("resolveInteraction"), sender.sent)
    }

    @Test
    fun `F-P0-B - the U9 user-input exemption survives the same sweep (control)`() = runTest {
        val (coordinator, _, sender) = coordinator()
        coordinator.reconcileFromSessionState("sess-1", userInputState("i-ui-1"))
        coordinator.reconcileFromSnapshot(rows = emptyList(), sessionId = "sess-1")
        assertEquals(
            PermissionStateMachine.State.DISPLAYED,
            coordinator.machineStateForTest(InteractionId("i-ui-1")),
        )
        val outcome = coordinator.submit(InteractionId("i-ui-1"), "email", kind = InteractionCoordinator.AnswerKind.USER_INPUT)
        assertTrue(outcome is InteractionCoordinator.SubmitOutcome.Submitted)
    }

    @Test
    fun `F-P0-B - a row-anchored machine keeps the T2 positive leg (control)`() = runTest {
        val (coordinator, _, _) = coordinator()
        coordinator.reconcileFromSnapshot(listOf(pendingApprovalRow("i-row-1", ConversationRow.ToolCallStatus.PENDING_APPROVAL)), sessionId = "sess-1")
        assertEquals(
            PermissionStateMachine.State.DISPLAYED,
            coordinator.machineStateForTest(InteractionId("i-row-1")),
        )
        // The positive leg (the row leaves pending-approval) still resolves by-elsewhere.
        coordinator.reconcileFromSnapshot(listOf(pendingApprovalRow("i-row-1", ConversationRow.ToolCallStatus.RUNNING)), sessionId = "sess-1")
        assertEquals(
            coordinator.machineStateForTest(InteractionId("i-row-1")),
            PermissionStateMachine.State.RESOLVED,
            "the T2 positive leg must keep resolving row-anchored machines",
        )
    }
}
