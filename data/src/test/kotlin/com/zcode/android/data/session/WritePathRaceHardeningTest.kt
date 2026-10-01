package com.zcode.android.data.session

import com.zcode.android.core.protocol.v4.CommandAck
import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.domain.ids.InteractionId
import com.zcode.android.domain.model.SessionProjectionState
import com.zcode.android.domain.outbox.OutboxStateMachine
import com.zcode.android.domain.repo.InteractionRepository
import com.zcode.android.domain.repo.OutboxRepository
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 7.6 race hardening (§20.3 + §43.2-7.6): RANDOMIZED adversarial schedules of
 * user taps × authoritative resolution (before/after taps) × transport death, over
 * the REAL InteractionCoordinator + the domain outbox machine. Invariants asserted
 * per schedule (zero unsafe outcomes):
 *   R1 — at most ONE commandId is EVER minted per interaction (INV-4);
 *   R2 — every transmission used ONE commandId (retries reuse the entry);
 *   R3 — after authoritative resolution a further tap NEVER transmits.
 */
class WritePathRaceHardeningTest {

    private class RaceInteractions : InteractionRepository {
        val payloads = HashMap<String, JsonObject>()
        override fun observeOpen(endpointId: EndpointId) =
            kotlinx.coroutines.flow.flowOf(emptyList<InteractionRepository.InteractionRecord>())
        override suspend fun upsertPermission(request: com.zcode.android.domain.model.PermissionRequest, endpointId: EndpointId, lifecycle: com.zcode.android.domain.model.InteractionLifecycle) {
            payloads[request.interactionId.raw] = request.raw ?: JsonObject(emptyMap())
        }
        override suspend fun upsertUserInput(request: com.zcode.android.domain.model.UserInputRequest, endpointId: EndpointId, lifecycle: com.zcode.android.domain.model.InteractionLifecycle) = Unit
        override suspend fun markResolved(endpointId: EndpointId, interactionId: InteractionId, atMs: Long, resolvedBySelf: Boolean) = Unit
        override suspend fun sweep(endpointId: EndpointId, nowMs: Long): Int = 0
        override suspend fun count(endpointId: EndpointId): Int = payloads.size
        override suspend fun loadStored(endpointId: EndpointId, interactionId: InteractionId): InteractionRepository.StoredInteraction? =
            payloads[interactionId.raw]?.let {
                InteractionRepository.StoredInteraction(
                    record = InteractionRepository.InteractionRecord(
                        interactionId = interactionId, sessionId = null,
                        kind = InteractionRepository.InteractionRecord.Kind.PERMISSION,
                        lifecycle = com.zcode.android.domain.model.InteractionLifecycle.DISPLAYED,
                        toolName = "Bash", createdAtMs = 1, resolvedAtMs = null,
                    ),
                    payload = it,
                )
            }
    }

    private class FlakySender : CommandSender {
        var down = false
        val transmitted = mutableListOf<String>()
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
            transmitted.add(commandId)
            if (down) throw IllegalStateException("down")
            return CommandAck(commandId, "accepted", 1, null, null, null)
        }
    }

    private class MachineOutbox : OutboxRepository {
        private class Row(var state: OutboxStateMachine.State)
        private val rows = LinkedHashMap<String, Row>()

        fun rowCount() = rows.size

        override fun observePending(endpointId: EndpointId) = kotlinx.coroutines.flow.flowOf(
            entries(endpointId),
        )

        private fun entries(endpointId: EndpointId) = rows.map { (id, r) ->
            com.zcode.android.domain.outbox.OutboxEntry(
                commandId = com.zcode.android.domain.ids.CommandId(id), endpointId = endpointId.raw,
                sessionId = "s", type = "resolveInteraction", payloadJson = "{}",
                baseRevision = null, baseLogEpoch = null, issuedAtMs = 0,
                state = r.state, attempts = 0, lastTransitionAtMs = 0,
            )
        }

        override suspend fun accept(
            endpointId: EndpointId,
            sessionId: String?,
            type: String,
            payloadJson: String,
            baseRevision: Long?,
            baseLogEpoch: String?,
            issuedAtMs: Long,
        ): com.zcode.android.domain.ids.CommandId {
            val id = com.zcode.android.domain.ids.CommandId("cmd-${rows.size + 1}")
            rows[id.raw] = Row(OutboxStateMachine.State.ACCEPTED_LOCALLY)
            return id
        }

        override suspend fun apply(
            commandId: com.zcode.android.domain.ids.CommandId,
            event: OutboxStateMachine.Event,
            atMs: Long,
        ): com.zcode.android.domain.outbox.OutboxEntry? {
            val row = rows.getValue(commandId.raw)
            val machine = OutboxStateMachine.restore(row.state)
            machine.on(event)
            row.state = machine.currentState
            return null
        }

        override suspend fun get(commandId: com.zcode.android.domain.ids.CommandId) =
            rows[commandId.raw]?.let {
                com.zcode.android.domain.outbox.OutboxEntry(
                    commandId = commandId, endpointId = "ep", sessionId = "s", type = "resolveInteraction",
                    payloadJson = "{}", baseRevision = null, baseLogEpoch = null,
                    issuedAtMs = 0, state = it.state, attempts = 0, lastTransitionAtMs = 0,
                )
            }

        override suspend fun sweepExpired(endpointId: EndpointId, nowMs: Long, ttlMs: Long): Int = 0
        override suspend fun pendingRefs(endpointId: EndpointId) = emptyList<com.zcode.android.domain.journal.RecoveryJournal.PendingCommandRef>()
        override suspend fun countByState(endpointId: EndpointId, state: OutboxStateMachine.State): Int =
            rows.values.count { it.state == state }
    }

    private fun permissionState(interactionId: String): SessionProjectionState {
        val json = Json.parseToJsonElement(
            """{"pendingInteractions":[{"interactionId":"$interactionId","kind":"permission","anchorRowId":null,
               "createdAt":1700000000000.0,"payload":{"kind":"permission","toolCallId":"tc","toolName":"Bash",
               "summary":"s","options":[{"optionId":"allowOnce","label":"a","kind":"allowOnce"},
               {"optionId":"deny","label":"d","kind":"deny"}]}}]}""",
        ) as JsonObject
        return SessionProjectionState.fromSnapshot(json)
    }

    @Test
    fun `1000 randomized tap-and-resolve schedules - zero unsafe outcomes`() = runTest {
        val rng = Random(20260930)
        var lossPathsExercised = 0
        repeat(1_000) { schedule ->
            val interactions = RaceInteractions()
            val sender = FlakySender()
            val outbox = MachineOutbox()
            val coordinator = InteractionCoordinator(
                endpointId = EndpointId("ep"),
                interactions = interactions,
                outbox = outbox,
                commands = sender,
            )
            val iid = "i-$schedule"
            coordinator.reconcileFromSessionState("s", permissionState(iid))
            sender.down = rng.nextBoolean() // transport may die at any point
            val taps = rng.nextInt(1, 4)
            val resolveFirst = rng.nextBoolean() // desktop may resolve before/after our taps
            var sentAnything = false
            repeat(taps) { tap ->
                if (resolveFirst && tap == 0) {
                    coordinator.reconcileFromSessionState("s", SessionProjectionState(pendingInteractions = emptyList()))
                }
                val outcome = coordinator.submit(InteractionId(iid), "allowOnce")
                if (outcome is InteractionCoordinator.SubmitOutcome.Submitted ||
                    outcome is InteractionCoordinator.SubmitOutcome.RetryableKept
                ) {
                    sentAnything = true
                }
            }
            if (!resolveFirst) {
                coordinator.reconcileFromSessionState("s", SessionProjectionState(pendingInteractions = emptyList()))
            }
            // R3: after authoritative resolution, a further tap NEVER transmits.
            val before = sender.transmitted.size
            coordinator.submit(InteractionId(iid), "allowOnce")
            assertEquals(before, sender.transmitted.size, "schedule $schedule: post-resolution send")
            // R1: at most one commandId minted per interaction across the schedule.
            assertTrue(outbox.rowCount() <= 1, "schedule $schedule: minted ${outbox.rowCount()}")
            // R2: every transmission used ONE commandId (retries reuse the entry).
            assertTrue(sender.transmitted.toSet().size <= 1, "schedule $schedule: multiple commandIds")
            if (sender.down && sentAnything) lossPathsExercised++
        }
        assertTrue(lossPathsExercised > 0, "fuzz never exercised the transport-loss path")
    }
}
