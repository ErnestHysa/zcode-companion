package com.zcode.android.data.session

import com.zcode.android.core.protocol.v4.CommandAck
import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.domain.ids.InteractionId
import com.zcode.android.domain.model.InteractionLifecycle
import com.zcode.android.domain.model.PermissionOption
import com.zcode.android.domain.model.PermissionRequest
import com.zcode.android.domain.model.SessionProjectionState
import com.zcode.android.domain.model.UserInputRequest
import com.zcode.android.domain.repo.InteractionRepository
import com.zcode.android.domain.state.PermissionStateMachine
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 7 interaction plane: the authoritative `pendingInteractions` feed (Q11 —
 * snapshots DO carry open interactions), the U9 user-input answer policy, the
 * approvals-inbox store hydration, and the §20.3 race cells at coordinator level.
 */
class InteractionCoordinatorPhase7Test {

    private class RecordingInteractions : InteractionRepository {
        val upserts = mutableListOf<Pair<InteractionId, InteractionLifecycle>>()
        val resolved = mutableListOf<Pair<InteractionId, Boolean>>() // id to resolvedBySelf
        val payloads = HashMap<String, JsonObject>()

        override fun observeOpen(endpointId: EndpointId) =
            kotlinx.coroutines.flow.flowOf(emptyList<InteractionRepository.InteractionRecord>())

        override suspend fun upsertPermission(request: PermissionRequest, endpointId: EndpointId, lifecycle: InteractionLifecycle) {
            upserts.add(request.interactionId to lifecycle)
            payloads[request.interactionId.raw] = request.raw ?: buildJsonObject { }
        }

        override suspend fun upsertUserInput(request: UserInputRequest, endpointId: EndpointId, lifecycle: InteractionLifecycle) {
            upserts.add(request.interactionId to lifecycle)
            payloads[request.interactionId.raw] = request.raw ?: buildJsonObject { }
        }

        override suspend fun markResolved(endpointId: EndpointId, interactionId: InteractionId, atMs: Long, resolvedBySelf: Boolean) {
            resolved.add(interactionId to resolvedBySelf)
        }

        override suspend fun sweep(endpointId: EndpointId, nowMs: Long): Int = 0
        override suspend fun count(endpointId: EndpointId): Int = payloads.size

        override suspend fun loadStored(endpointId: EndpointId, interactionId: InteractionId): InteractionRepository.StoredInteraction? {
            val payload = payloads[interactionId.raw] ?: return null
            return InteractionRepository.StoredInteraction(
                record = InteractionRepository.InteractionRecord(
                    interactionId = interactionId,
                    sessionId = null,
                    kind = InteractionRepository.InteractionRecord.Kind.PERMISSION,
                    lifecycle = InteractionLifecycle.DISPLAYED,
                    toolName = "Bash",
                    createdAtMs = 1,
                    resolvedAtMs = null,
                ),
                payload = payload,
            )
        }
    }

    private class ScriptedSender : CommandSender {
        val acks = ArrayDeque<CommandAck>()
        val sent = mutableListOf<Pair<String, JsonObject>>()
        var throwOnSend = false
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
            if (throwOnSend) throw IllegalStateException("down")
            return acks.removeFirstOrNull() ?: CommandAck(commandId, "accepted", 1, null, null, null)
        }
    }

    private fun coordinator(
        interactions: RecordingInteractions = RecordingInteractions(),
        sender: ScriptedSender = ScriptedSender(),
    ): Quad {
        val outbox = InMemoryTestOutbox()
        val c = InteractionCoordinator(
            endpointId = EndpointId("ep"),
            interactions = interactions,
            outbox = outbox,
            commands = sender,
        )
        return Quad(c, interactions, sender, outbox)
    }

    private data class Quad(
        val coordinator: InteractionCoordinator,
        val interactions: RecordingInteractions,
        val sender: ScriptedSender,
        val outbox: InMemoryTestOutbox,
    )

    private fun permissionState(interactionId: String, tool: String = "Bash"): SessionProjectionState =
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
                                put("toolName", tool)
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

    private fun userInputState(interactionId: String, options: List<String>, freeText: Boolean): SessionProjectionState =
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
                                put("freeText", freeText)
                                putJsonArray("options") {
                                    options.forEach { id ->
                                        add(buildJsonObject { put("optionId", id); put("label", id) })
                                    }
                                }
                            }
                        },
                    )
                }
            },
        )

    @Test
    fun `pendingInteractions create DISPLAYED cards bound to their session (Q11)`() = runTest {
        val (coordinator, interactions, _, _) = coordinator()
        coordinator.reconcileFromSessionState("sess-1", permissionState("i-1"))
        assertEquals(1, coordinator.knownInteractionIds().size)
        // REQUESTED at creation + DISPLAYED at render — the last persisted lifecycle wins.
        assertEquals(InteractionLifecycle.DISPLAYED, interactions.upserts.last().second)
        assertEquals(PermissionStateMachine.State.DISPLAYED, coordinator.machineStateForTest(InteractionId("i-1")))
    }

    @Test
    fun `authoritative DISAPPEARANCE resolves by-elsewhere (§20-3 row 3)`() = runTest {
        val (coordinator, interactions, _, _) = coordinator()
        coordinator.reconcileFromSessionState("sess-1", permissionState("i-1"))
        // The interaction is gone from the authoritative projection:
        coordinator.reconcileFromSessionState("sess-1", SessionProjectionState(pendingInteractions = emptyList()))
        assertEquals(1, interactions.resolved.size)
        assertEquals(false, interactions.resolved.single().second)
        assertEquals(PermissionStateMachine.State.RESOLVED, coordinator.machineStateForTest(InteractionId("i-1")))
    }

    @Test
    fun `disappearance of ANOTHER session's interaction is ignored (session scoping)`() = runTest {
        val (coordinator, interactions, _, _) = coordinator()
        coordinator.reconcileFromSessionState("sess-1", permissionState("i-1"))
        coordinator.reconcileFromSessionState("sess-2", SessionProjectionState(pendingInteractions = emptyList()))
        assertEquals(0, interactions.resolved.size)
    }

    @Test
    fun `null pendingInteractions key reconciles NOTHING (legacy snapshots keep the row fallback)`() = runTest {
        val (coordinator, interactions, _, _) = coordinator()
        coordinator.reconcileFromSessionState("sess-1", SessionProjectionState())
        assertEquals(0, interactions.upserts.size)
    }

    @Test
    fun `submit rides resolveInteraction with the chosen answer (allowOnce)`() = runTest {
        val (coordinator, _, sender, _) = coordinator()
        coordinator.reconcileFromSessionState("sess-1", permissionState("i-1"))
        val outcome = coordinator.submit(InteractionId("i-1"), "allowOnce")
        assertTrue(outcome is InteractionCoordinator.SubmitOutcome.Submitted)
        val (type, payload) = sender.sent.single()
        assertEquals("resolveInteraction", type)
        assertEquals("i-1", (payload["interactionId"] as JsonPrimitive).content)
        val answer = payload["answer"] as JsonObject
        assertEquals("allowOnce", (answer["optionId"] as JsonPrimitive).content)
    }

    @Test
    fun `unknown option is INERT — never sent (INV-14 at the gate)`() = runTest {
        val (coordinator, _, sender, _) = coordinator()
        coordinator.reconcileFromSessionState("sess-1", permissionState("i-1"))
        val outcome = coordinator.submit(InteractionId("i-1"), "fullAccess")
        assertEquals(InteractionCoordinator.SubmitOutcome.InertOption, outcome)
        assertEquals(0, sender.sent.size)
    }

    @Test
    fun `resolved-elsewhere DURING our submit — the late ack never double-resolves (race)`() = runTest {
        val (coordinator, interactions, _, _) = coordinator()
        coordinator.reconcileFromSessionState("sess-1", permissionState("i-1"))
        // Desktop resolves first (the state patch raced ahead of our ack):
        coordinator.reconcileFromSessionState("sess-1", SessionProjectionState(pendingInteractions = emptyList()))
        // Our tap arrives late — the machine refuses (RESOLVED → SUBMITTING is illegal):
        val outcome = coordinator.submit(InteractionId("i-1"), "allowOnce")
        assertEquals(InteractionCoordinator.SubmitOutcome.DuplicateNoOp, outcome)
        // Exactly ONE resolution, attributed to elsewhere.
        assertEquals(1, interactions.resolved.size)
        assertEquals(false, interactions.resolved.single().second)
    }

    // ---------------------------------------------------------------- U9 user-input plane

    @Test
    fun `user-input option answers ride resolveInteraction verbatim (elicitation, no allow semantics)`() = runTest {
        val (coordinator, _, sender, _) = coordinator()
        coordinator.reconcileFromSessionState("sess-1", userInputState("i-u", listOf("postgres", "sqlite"), freeText = false))
        val outcome = coordinator.submit(InteractionId("i-u"), "postgres", kind = InteractionCoordinator.AnswerKind.USER_INPUT)
        assertTrue(outcome is InteractionCoordinator.SubmitOutcome.Submitted)
        val answer = sender.sent.single().second["answer"] as JsonObject
        assertEquals("postgres", (answer["optionId"] as JsonPrimitive).content)
    }

    @Test
    fun `user-input with NO options answers via freeText (verified compat path)`() = runTest {
        val (coordinator, _, sender, _) = coordinator()
        coordinator.reconcileFromSessionState("sess-1", userInputState("i-f", emptyList(), freeText = true))
        val outcome = coordinator.submit(InteractionId("i-f"), "", "my answer", kind = InteractionCoordinator.AnswerKind.USER_INPUT)
        assertTrue(outcome is InteractionCoordinator.SubmitOutcome.Submitted)
        val answer = sender.sent.single().second["answer"] as JsonObject
        assertEquals("my answer", (answer["freeText"] as JsonPrimitive).content)
        assertTrue("optionId" !in answer) // omitted entirely — verified schema optionality
    }

    @Test
    fun `user-input freeText REQUIRED when no options (blank never sent)`() = runTest {
        val (coordinator, _, sender, _) = coordinator()
        coordinator.reconcileFromSessionState("sess-1", userInputState("i-f", emptyList(), freeText = true))
        val outcome = coordinator.submit(InteractionId("i-f"), "", "  ", kind = InteractionCoordinator.AnswerKind.USER_INPUT)
        assertEquals(InteractionCoordinator.SubmitOutcome.NotApprovable, outcome)
        assertEquals(0, sender.sent.size)
    }

    @Test
    fun `user-input optionId OUTSIDE the request set is inert (fail-closed U9)`() = runTest {
        val (coordinator, _, sender, _) = coordinator()
        coordinator.reconcileFromSessionState("sess-1", userInputState("i-u", listOf("a", "b"), freeText = false))
        val outcome = coordinator.submit(InteractionId("i-u"), "injected", kind = InteractionCoordinator.AnswerKind.USER_INPUT)
        assertEquals(InteractionCoordinator.SubmitOutcome.InertOption, outcome)
        assertEquals(0, sender.sent.size)
    }

    // ---------------------------------------------------------------- approvals-inbox hydration

    @Test
    fun `submitFromStore hydrates the card from the stored payload and submits (same gate)`() = runTest {
        val (coordinator, interactions, sender, _) = coordinator()
        val payload = Json.parseToJsonElement(
            """
            {"kind":"permission","toolName":"Bash","summary":"Run the build?",
             "options":[{"optionId":"allowOnce"},{"optionId":"deny"}]}
            """.trimIndent(),
        ).let { it as JsonObject }
        interactions.payloads["i-store"] = payload
        val outcome = coordinator.submitFromStore(InteractionId("i-store"), "allowOnce")
        assertTrue(outcome is InteractionCoordinator.SubmitOutcome.Submitted)
        assertEquals(1, sender.sent.size)
    }

    @Test
    fun `submitFromStore on an unknown interaction is UnknownInteraction`() = runTest {
        val (coordinator, _, _, _) = coordinator()
        assertEquals(
            InteractionCoordinator.SubmitOutcome.UnknownInteraction,
            coordinator.submitFromStore(InteractionId("ghost"), "allowOnce"),
        )
    }

    // ---------------------------------------------------------------- disconnect mid-submit (§20-3 row 5)

    @Test
    fun `transport death mid-submit keeps the card retryable with the SAME commandId`() = runTest {
        val (coordinator, interactions, sender, outbox) = coordinator()
        sender.throwOnSend = true
        coordinator.reconcileFromSessionState("sess-1", permissionState("i-1"))
        val outcome = coordinator.submit(InteractionId("i-1"), "deny")
        val kept = assertTrue(outcome is InteractionCoordinator.SubmitOutcome.RetryableKept)
        // Card back to DISPLAYED (retryable), lifecycle persisted.
        assertEquals(PermissionStateMachine.State.DISPLAYED, coordinator.machineStateForTest(InteractionId("i-1")))
        sender.throwOnSend = false
        val retry = coordinator.submit(InteractionId("i-1"), "deny")
        assertTrue(retry is InteractionCoordinator.SubmitOutcome.Submitted)
        // EXACTLY ONE commandId was ever minted (INV-4) — the retry reused the entry.
        assertEquals(1, outbox.minted)
        assertTrue(interactions.resolved.single().second) // resolved by SELF on the retry
    }
}

private val JsonNull = kotlinx.serialization.json.JsonNull

/** Tiny outbox fake that counts mints (INV-4 observability in tests). */
private class InMemoryTestOutbox : com.zcode.android.domain.repo.OutboxRepository {
    private class Row(val type: String, var state: com.zcode.android.domain.outbox.OutboxStateMachine.State, val baseRevision: Long?)

    private val rows = LinkedHashMap<String, Row>()
    var minted = 0
        private set

    override fun observePending(endpointId: EndpointId) = kotlinx.coroutines.flow.flowOf(
        rows.map { (id, r) ->
            com.zcode.android.domain.outbox.OutboxEntry(
                commandId = com.zcode.android.domain.ids.CommandId(id), endpointId = endpointId.raw,
                sessionId = "s", type = r.type, payloadJson = "{}", baseRevision = r.baseRevision,
                baseLogEpoch = null, issuedAtMs = 0, state = r.state, attempts = 0, lastTransitionAtMs = 0,
            )
        },
    )

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
        rows[id.raw] = Row(type, com.zcode.android.domain.outbox.OutboxStateMachine.State.ACCEPTED_LOCALLY, baseRevision)
        return id
    }

    override suspend fun apply(
        commandId: com.zcode.android.domain.ids.CommandId,
        event: com.zcode.android.domain.outbox.OutboxStateMachine.Event,
        atMs: Long,
    ): com.zcode.android.domain.outbox.OutboxEntry? {
        val row = rows[commandId.raw] ?: return null
        row.state = com.zcode.android.domain.outbox.OutboxStateMachine.restore(row.state).on(event)
        return null
    }

    override suspend fun get(commandId: com.zcode.android.domain.ids.CommandId): com.zcode.android.domain.outbox.OutboxEntry? =
        rows[commandId.raw]?.let {
            com.zcode.android.domain.outbox.OutboxEntry(
                commandId = commandId, endpointId = "ep", sessionId = "s", type = it.type,
                payloadJson = "{}", baseRevision = it.baseRevision, baseLogEpoch = null,
                issuedAtMs = 0, state = it.state, attempts = 0, lastTransitionAtMs = 0,
            )
        }

    override suspend fun sweepExpired(endpointId: EndpointId, nowMs: Long, ttlMs: Long): Int = 0
    override suspend fun pendingRefs(endpointId: EndpointId) = emptyList<com.zcode.android.domain.journal.RecoveryJournal.PendingCommandRef>()
    override suspend fun countByState(endpointId: EndpointId, state: com.zcode.android.domain.outbox.OutboxStateMachine.State): Int =
        rows.values.count { it.state == state }
}
