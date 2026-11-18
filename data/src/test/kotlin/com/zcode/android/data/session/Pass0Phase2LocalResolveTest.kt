package com.zcode.android.data.session

import com.zcode.android.core.protocol.v4.CommandAck
import com.zcode.android.domain.ids.CommandId
import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.domain.ids.InteractionId
import com.zcode.android.domain.model.SessionProjectionState
import com.zcode.android.domain.outbox.OutboxStateMachine
import com.zcode.android.domain.repo.InteractionRepository
import com.zcode.android.domain.repo.OutboxRepository
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
 * F-P3-C (pass-0 Phase 2; the pass-3 receipt): the inbox ACK's resolution echo
 * is DROPPED when the answered conversation is UNSUBSCRIBED —
 * `gateway.drop :: session=synth-task-3 kind=ONLINE (no subscription record)`.
 * The accepted ack's LOCAL resolve must be complete without the echo: nothing
 * on the phone keeps waiting for the dropped delta.
 *
 * The coordinator fires [InteractionCoordinator's onResolvedLocally] the moment
 * the record resolves on an ACCEPTED ack (never on a rejection); the hub's
 * wiring dismisses the interaction's notification records (the §24.3
 * reconciler). The conversation row itself arrives at the next attach snapshot
 * — the drop's durable harm was the un-dismissed notification plane.
 */
class Pass0Phase2LocalResolveTest {

    private class RecordingInteractions : InteractionRepository {
        val resolved = mutableListOf<InteractionId>()
        override fun observeOpen(endpointId: EndpointId) =
            kotlinx.coroutines.flow.flowOf(emptyList<InteractionRepository.InteractionRecord>())
        override suspend fun upsertPermission(request: com.zcode.android.domain.model.PermissionRequest, endpointId: EndpointId, lifecycle: com.zcode.android.domain.model.InteractionLifecycle) = Unit
        override suspend fun upsertUserInput(request: com.zcode.android.domain.model.UserInputRequest, endpointId: EndpointId, lifecycle: com.zcode.android.domain.model.InteractionLifecycle) = Unit
        override suspend fun markResolved(endpointId: EndpointId, interactionId: InteractionId, atMs: Long, resolvedBySelf: Boolean) {
            resolved.add(interactionId)
        }
        override suspend fun listOpen(endpointId: EndpointId): List<InteractionRepository.InteractionRecord> = emptyList()
        override suspend fun sweep(endpointId: EndpointId, nowMs: Long): Int = 0
        override suspend fun count(endpointId: EndpointId): Int = 0
        override suspend fun loadStored(endpointId: EndpointId, interactionId: InteractionId): InteractionRepository.StoredInteraction? = null
    }

    private class RecordingOutbox : OutboxRepository {
        private val states = HashMap<String, OutboxStateMachine.State>()
        override suspend fun accept(
            endpointId: EndpointId,
            sessionId: String?,
            type: String,
            payloadJson: String,
            baseRevision: Long?,
            baseLogEpoch: String?,
            issuedAtMs: Long,
        ): CommandId {
            val id = CommandId("cmd-${states.size + 1}")
            states[id.raw] = OutboxStateMachine.State.ACCEPTED_LOCALLY
            return id
        }
        override suspend fun apply(commandId: CommandId, event: OutboxStateMachine.Event, atMs: Long): com.zcode.android.domain.outbox.OutboxEntry? {
            states[commandId.raw]?.let { states[commandId.raw] = OutboxStateMachine.restore(it).on(event) }
            return null
        }
        override suspend fun get(commandId: CommandId): com.zcode.android.domain.outbox.OutboxEntry? =
            states[commandId.raw]?.let {
                com.zcode.android.domain.outbox.OutboxEntry(
                    commandId = commandId, endpointId = "ep", sessionId = "s", type = "resolveInteraction",
                    payloadJson = "{}", baseRevision = null, baseLogEpoch = null,
                    issuedAtMs = 0, state = it, attempts = 0, lastTransitionAtMs = 0,
                )
            }
        override fun observePending(endpointId: EndpointId) = kotlinx.coroutines.flow.flowOf(emptyList<com.zcode.android.domain.outbox.OutboxEntry>())
        override suspend fun sweepExpired(endpointId: EndpointId, nowMs: Long, ttlMs: Long): Int = 0
        override suspend fun sweepTerminal(endpointId: EndpointId, nowMs: Long, ttlMs: Long): Int = 0
        override suspend fun pendingRefs(endpointId: EndpointId) = emptyList<com.zcode.android.domain.journal.RecoveryJournal.PendingCommandRef>()
        override suspend fun countByState(endpointId: EndpointId, state: OutboxStateMachine.State): Int = 0
    }

    private class ScriptedSender(private val status: String) : CommandSender {
        override suspend fun send(
            type: String,
            sessionId: String?,
            payload: JsonObject,
            baseRevision: Long?,
            baseLogEpoch: String?,
            commandId: String,
            workspacePath: String?,
            workspaceIdentity: String?,
        ): CommandAck = CommandAck(commandId, status, 1, null, null, null)
    }

    private fun permissionState(interactionId: String): SessionProjectionState = SessionProjectionState.fromSnapshot(
        buildJsonObject {
            putJsonArray("pendingInteractions") {
                add(
                    buildJsonObject {
                        put("interactionId", interactionId)
                        put("kind", "permission")
                        put("createdAt", 1700000000000.0)
                        putJsonObject("payload") {
                            put("kind", "permission")
                            put("toolName", "Bash")
                            putJsonArray("options") {
                                add(buildJsonObject { put("optionId", "allowOnce"); put("label", "Allow once"); put("kind", "allowOnce") })
                            }
                        }
                    },
                )
            }
        },
    )

    @Test
    fun `F-P3-C - an accepted inbox ack fires the local-resolve seam (the echo is not awaited)`() = runTest {
        val fired = mutableListOf<Triple<String?, InteractionId, String>>()
        val coordinator = InteractionCoordinator(
            endpointId = EndpointId("ep"),
            interactions = RecordingInteractions(),
            outbox = RecordingOutbox(),
            commands = ScriptedSender("accepted"),
            onResolvedLocally = { sid, iid, status -> fired.add(Triple(sid, iid, status)) },
        )
        coordinator.reconcileFromSessionState("synth-task-3", permissionState("i-echo-1"))

        val outcome = coordinator.submit(InteractionId("i-echo-1"), "allowOnce")
        assertTrue(outcome is InteractionCoordinator.SubmitOutcome.Submitted)
        assertEquals(
            listOf(Triple<String?, InteractionId, String>("synth-task-3", InteractionId("i-echo-1"), "accepted")),
            fired,
            "the accepted ack must fire the local resolve with the session + interaction + status",
        )
    }

    @Test
    fun `F-P3-C - a rejected ack never fires the local resolve`() = runTest {
        val fired = mutableListOf<Triple<String?, InteractionId, String>>()
        val coordinator = InteractionCoordinator(
            endpointId = EndpointId("ep"),
            interactions = RecordingInteractions(),
            outbox = RecordingOutbox(),
            commands = ScriptedSender("rejected"),
            onResolvedLocally = { sid, iid, status -> fired.add(Triple(sid, iid, status)) },
        )
        coordinator.reconcileFromSessionState("synth-task-3", permissionState("i-echo-2"))

        coordinator.submit(InteractionId("i-echo-2"), "allowOnce")
        assertTrue(fired.isEmpty(), "a rejected ack is not a local resolve")
    }
}
