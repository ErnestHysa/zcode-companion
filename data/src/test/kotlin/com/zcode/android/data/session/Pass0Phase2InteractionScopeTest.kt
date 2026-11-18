package com.zcode.android.data.session

import com.zcode.android.core.protocol.v4.CommandAck
import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.domain.ids.InteractionId
import com.zcode.android.domain.model.SessionProjectionState
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
 * F-P7-A (pass-0 Phase 2; the pass-7 Approvals-screen receipt): the inbox answer
 * path was interaction-scoped in NAME only — the submit's send carried NO
 * workspace (the gateway fell back to the ACTIVE screen's target) and never
 * established the conversation scope for the interaction's own workspace, so on
 * DIRECT a foreign-workspace approval's resolve never reached the wire
 * (RETRYABLE_KEPT with zero v4call; the relay path worked only because the relay
 * routes by session id).
 *
 * The fix: the coordinator resolves the session's OWN workspace target (the
 * recovery map via [workspaceForSession]), establishes the conversation scope
 * for it BEFORE the send ([ensureScope] — the CommandCoordinator's identical
 * seam), and carries the workspace on the wire call.
 */
class Pass0Phase2InteractionScopeTest {

    /** Records the wire target the answer actually carried. */
    private class RecordingSender : CommandSender {
        val sent = mutableListOf<Triple<String, String?, String?>>() // type to (workspacePath, workspaceIdentity)

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
            sent.add(Triple(type, workspacePath, workspaceIdentity))
            return CommandAck(commandId, "accepted", 1, null, null, null)
        }
    }

    private class RecordingOutbox : OutboxRepository {
        private val states = HashMap<String, com.zcode.android.domain.outbox.OutboxStateMachine.State>()
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
        ): com.zcode.android.domain.ids.CommandId {
            minted++
            val id = com.zcode.android.domain.ids.CommandId("cmd-$minted")
            states[id.raw] = com.zcode.android.domain.outbox.OutboxStateMachine.State.ACCEPTED_LOCALLY
            return id
        }

        override suspend fun apply(
            commandId: com.zcode.android.domain.ids.CommandId,
            event: com.zcode.android.domain.outbox.OutboxStateMachine.Event,
            atMs: Long,
        ): com.zcode.android.domain.outbox.OutboxEntry? {
            states[commandId.raw]?.let {
                states[commandId.raw] = com.zcode.android.domain.outbox.OutboxStateMachine.restore(it).on(event)
            }
            return null
        }

        override suspend fun get(commandId: com.zcode.android.domain.ids.CommandId): com.zcode.android.domain.outbox.OutboxEntry? =
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
        override suspend fun countByState(endpointId: EndpointId, state: com.zcode.android.domain.outbox.OutboxStateMachine.State): Int = 0
    }

    private class RecordingInteractions : InteractionRepository {
        val payloads = HashMap<String, JsonObject>()
        override fun observeOpen(endpointId: EndpointId) =
            kotlinx.coroutines.flow.flowOf(emptyList<InteractionRepository.InteractionRecord>())
        override suspend fun upsertPermission(request: com.zcode.android.domain.model.PermissionRequest, endpointId: EndpointId, lifecycle: com.zcode.android.domain.model.InteractionLifecycle) {
            payloads[request.interactionId.raw] = request.raw ?: buildJsonObject { }
        }
        override suspend fun upsertUserInput(request: com.zcode.android.domain.model.UserInputRequest, endpointId: EndpointId, lifecycle: com.zcode.android.domain.model.InteractionLifecycle) {
            payloads[request.interactionId.raw] = request.raw ?: buildJsonObject { }
        }
        override suspend fun markResolved(endpointId: EndpointId, interactionId: InteractionId, atMs: Long, resolvedBySelf: Boolean) = Unit
        override suspend fun listOpen(endpointId: EndpointId): List<InteractionRepository.InteractionRecord> = emptyList()
        override suspend fun sweep(endpointId: EndpointId, nowMs: Long): Int = 0
        override suspend fun count(endpointId: EndpointId): Int = payloads.size
        override suspend fun loadStored(endpointId: EndpointId, interactionId: InteractionId): InteractionRepository.StoredInteraction? = null
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

    @Test
    fun `F-P7-A - the inbox answer rides the interaction's OWN workspace with the scope established`() = runTest {
        val sender = RecordingSender()
        val scopedFor = mutableListOf<Pair<String?, String?>>()
        val coordinator = InteractionCoordinator(
            endpointId = EndpointId("ep"),
            interactions = RecordingInteractions(),
            outbox = RecordingOutbox(),
            commands = sender,
            workspaceForSession = { sid -> if (sid == "synth-task-1") ("/repo-one" to "ws-one") else null },
            ensureScope = { wp, wi -> scopedFor.add(wp to wi) },
        )
        coordinator.reconcileFromSessionState("synth-task-1", permissionState("i-ws-1"))

        val outcome = coordinator.submit(InteractionId("i-ws-1"), "allowOnce")
        assertTrue(outcome is InteractionCoordinator.SubmitOutcome.Submitted, "got $outcome")
        // THE BUG (pre-fix): the send carried null/null — the gateway fell back to the
        // ACTIVE screen's workspace target and the scoped listen was never established.
        assertEquals(
            listOf("/repo-one" to "ws-one"),
            sender.sent.map { it.second to it.third },
            "the answer must carry the interaction's OWN workspace",
        )
        assertEquals(
            listOf<Pair<String?, String?>?>("/repo-one" to "ws-one"),
            scopedFor,
            "the conversation scope must be established for the interaction's workspace BEFORE the send",
        )
    }

    @Test
    fun `F-P7-A - a session with no workspace target answers unrouted (the fallback stays)`() = runTest {
        val sender = RecordingSender()
        val scopedFor = mutableListOf<Pair<String?, String?>>()
        val coordinator = InteractionCoordinator(
            endpointId = EndpointId("ep"),
            interactions = RecordingInteractions(),
            outbox = RecordingOutbox(),
            commands = sender,
            workspaceForSession = { null },
            ensureScope = { wp, wi -> scopedFor.add(wp to wi) },
        )
        coordinator.reconcileFromSessionState("synth-task-1", permissionState("i-ws-2"))
        val outcome = coordinator.submit(InteractionId("i-ws-2"), "allowOnce")
        assertTrue(outcome is InteractionCoordinator.SubmitOutcome.Submitted)
        // No known target: nothing to establish, the gateway's own fallback applies.
        assertEquals(listOf<Triple<String, String?, String?>>(Triple("resolveInteraction", null, null)), sender.sent)
        assertTrue(scopedFor.isEmpty())
    }
}
