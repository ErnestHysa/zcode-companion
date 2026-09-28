package com.zcode.android.data.session

import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.protocol.v4.CommandAck
import com.zcode.android.domain.ids.CommandId
import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.domain.ids.InteractionId
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.domain.model.InteractionLifecycle
import com.zcode.android.domain.model.PermissionRequest
import com.zcode.android.domain.model.UserInputRequest
import com.zcode.android.domain.outbox.OutboxStateMachine
import com.zcode.android.domain.repo.InteractionRepository
import com.zcode.android.domain.repo.OutboxRepository
import com.zcode.android.domain.state.PermissionStateMachine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Interaction coordinator — Phase 5.4 (§14.4.3 + §20 wiring): interaction card states
 * wired to events and to the v4 `resolveInteraction` command path, with the INV-14
 * fail-closed answer policy enforced at the ONLY submission gate ([submit]).
 *
 * INV-4: the commandId is minted exactly once per accepted submit (the outbox owns
 * minting) and is stable across every user-gated retry; a send whose outcome is
 * unknown leaves the outbox entry UNKNOWN (never assumed success, §15.5) and the
 * card retryable-DISPLAYED (§20.3 row 5) — reconciliation on reconnect reclassifies.
 *
 * Event-source note (honest scope): the REAL `permission.requested` event shape on
 * the phone path is captured at Phase 7 (approval E2E, Q11/Q7 territory); this
 * coordinator consumes typed [PermissionRequest]/[UserInputRequest] models — the
 * verified §20.1 payload shapes — from its callers, and reconciles cards from
 * authoritative toolCall rows (S13) via the VERIFIED pendingApproval +
 * approvalInteractionId fields (rows.ts) and the VERIFIED synthesized option
 * vocabulary (§7.4.4).
 */
class InteractionCoordinator(
    private val endpointId: EndpointId,
    private val interactions: InteractionRepository,
    private val outbox: OutboxRepository,
    private val commands: CommandSender,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: ZLog = ZLog(),
) {

    private val machines = HashMap<InteractionId, PermissionStateMachine>()
    private val requests = HashMap<InteractionId, PermissionRequest>()

    /** INV-4: the pending outbox entry per interaction — retries REUSE its commandId. */
    private val pendingCommands = HashMap<InteractionId, CommandId>()

    // ---------------------------------------------------------------- events in

    suspend fun onPermissionRequested(request: PermissionRequest) {
        machines[request.interactionId] = PermissionStateMachine(request.interactionId)
        requests[request.interactionId] = request
        interactions.upsertPermission(request, endpointId, InteractionLifecycle.REQUESTED)
    }

    suspend fun onUserInputRequested(request: UserInputRequest) {
        machines[request.interactionId] = PermissionStateMachine(request.interactionId)
        interactions.upsertUserInput(request, endpointId, InteractionLifecycle.REQUESTED)
    }

    /** Card rendered (§14.4.3 row 1). */
    suspend fun render(interactionId: InteractionId) {
        val machine = machineFor(interactionId) ?: return
        runCatching { machine.on(PermissionStateMachine.Event.CardRendered) }
        updateLifecycle(interactionId, machine.state)
    }

    /** `permission.resolved` arrived (possibly before our own ACK — resolved-elsewhere). */
    suspend fun onResolvedElsewhere(interactionId: InteractionId) {
        val machine = machineFor(interactionId) ?: return
        runCatching { machine.on(PermissionStateMachine.Event.ResolvedElsewhere) }
        if (machine.state == PermissionStateMachine.State.RESOLVED) {
            interactions.markResolved(endpointId, interactionId, clock(), resolvedBySelf = false)
            updateLifecycle(interactionId, machine.state)
        }
    }

    /** Desktop session closed / task deleted (§20.3 row 7). */
    suspend fun onSessionClosed(interactionId: InteractionId) {
        machineFor(interactionId)?.let {
            runCatching { it.on(PermissionStateMachine.Event.SessionClosed) }
            updateLifecycle(interactionId, it.state)
        }
    }

    // ---------------------------------------------------------------- submit (the ONE gate)

    sealed class SubmitOutcome {
        data class Submitted(val ack: CommandAck) : SubmitOutcome()
        /** Send outcome unknown / transport failure — card retryable, SAME commandId (INV-4). */
        data class RetryableKept(val commandId: CommandId) : SubmitOutcome()
        /** Unknown/inert option — never sent (§20.4 row 2). */
        data object InertOption : SubmitOutcome()
        /** workflowRefine requires free text. */
        data object RefineRequiresText : SubmitOutcome()
        /** No option set / malformed request — fail closed (§20.4 row 6). */
        data object NotApprovable : SubmitOutcome()
        /** Double-tap while submitting — no-op (§14.4.3). */
        data object DuplicateNoOp : SubmitOutcome()
        data object UnknownInteraction : SubmitOutcome()
    }

    suspend fun submit(interactionId: InteractionId, optionId: String, freeText: String? = null): SubmitOutcome {
        val machine = machines[interactionId] ?: return SubmitOutcome.UnknownInteraction
        val request = requests[interactionId] ?: return SubmitOutcome.UnknownInteraction
        // §20.4: classification BEFORE any state change — an unknown option never
        // reaches the machine, the outbox, or the wire.
        val decision = PermissionStateMachine.AnswerPolicy.decide(request.options, optionId, freeText)
        val answer = when (decision) {
            is PermissionStateMachine.AnswerPolicy.Decision.Submit -> decision
            is PermissionStateMachine.AnswerPolicy.Decision.InertUnknown -> return SubmitOutcome.InertOption
            is PermissionStateMachine.AnswerPolicy.Decision.RefineRequiresText -> return SubmitOutcome.RefineRequiresText
            is PermissionStateMachine.AnswerPolicy.Decision.NotApprovable -> return SubmitOutcome.NotApprovable
        }

        val before = machine.state
        runCatching { machine.on(PermissionStateMachine.Event.UserChose(optionId, freeText)) }
            .onFailure { return SubmitOutcome.DuplicateNoOp } // includes RESOLVED/STALE → Submitting illegals
        if (machine.state != PermissionStateMachine.State.SUBMITTING) {
            return SubmitOutcome.DuplicateNoOp
        }
        updateLifecycle(interactionId, machine.state)

        // ACCEPT: the outbox mints the commandId exactly once (INV-4).
        val payload = buildJsonObject {
            put("interactionId", interactionId.raw)
            put("answer", buildJsonObject {
                put("optionId", answer.optionId)
                answer.freeText?.let { put("freeText", it) }
            })
        }
        // INV-4 minting rule: a retry of a server-contacted command retransmits the
        // SAME outbox entry (user-gated); only a fresh accept mints a new commandId;
        // a LIVE pending entry is never duplicated.
        val existing = pendingCommands[interactionId]
        val commandId: CommandId = when {
            existing == null -> {
                val minted = outbox.accept(
                    endpointId = endpointId,
                    sessionId = request.sessionId,
                    type = "resolveInteraction",
                    payloadJson = payload.toString(),
                    baseRevision = null, // resolveInteraction NOT in COMMANDS_REQUIRING_BASE_REVISION (verified set)
                    baseLogEpoch = null,
                    issuedAtMs = clock(),
                )
                pendingCommands[interactionId] = minted
                minted
            }
            outbox.get(existing)?.state == OutboxStateMachine.State.UNKNOWN ||
                outbox.get(existing)?.state == OutboxStateMachine.State.FAILED_RETRYABLE -> {
                outbox.apply(existing, OutboxStateMachine.Event.UserGatedRetry, clock())
                existing
            }
            outbox.get(existing)?.state == OutboxStateMachine.State.ACCEPTED_LOCALLY -> existing
            else -> return SubmitOutcome.DuplicateNoOp // live entry (TRANSMITTED/…) — never duplicate
        }
        try {
            outbox.apply(commandId, OutboxStateMachine.Event.Transmit, clock())
            val ack = commands.send(
                type = "resolveInteraction",
                sessionId = request.sessionId,
                payload = payload,
                baseRevision = null,
                baseLogEpoch = null,
                commandId = commandId.raw,
            )
            outbox.apply(commandId, OutboxStateMachine.Event.Ack(ack.status), clock())
            val machineEvent = when (ack.status) {
                "accepted", "duplicate", "noop" -> PermissionStateMachine.Event.AckConfirmed
                "stale" -> PermissionStateMachine.Event.AckStale
                else -> PermissionStateMachine.Event.AckRejected
            }
            runCatching { machine.on(machineEvent) }
            if (machine.state == PermissionStateMachine.State.RESOLVED) {
                interactions.markResolved(endpointId, interactionId, clock(), resolvedBySelf = true)
                pendingCommands.remove(interactionId) // terminal: the entry's work is done
            } else if (machine.state in TERMINAL_MACHINE_STATES) {
                pendingCommands.remove(interactionId)
            }
            updateLifecycle(interactionId, machine.state)
            return SubmitOutcome.Submitted(ack)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // Outcome UNKNOWN (never assume success, §15.5); card retryable-DISPLAYED
            // with the SAME commandId (§20.3 row 5; INV-4 preserved).
            runCatching { outbox.apply(commandId, OutboxStateMachine.Event.SendLost, clock()) }
            runCatching { machine.on(PermissionStateMachine.Event.SendFailedPreContact) }
            updateLifecycle(interactionId, machine.state)
            log.warn("interaction.submitRetryable", "${e.javaClass.simpleName}")
            return SubmitOutcome.RetryableKept(commandId)
        }
    }

    // ---------------------------------------------------------------- recovery reconcile (S13)

    /**
     * §15-S13: after recovery, reconcile cards against the authoritative snapshot.
     * Rows carrying the VERIFIED pendingApproval + approvalInteractionId fields create
     * open cards (with the verified synthesized option vocabulary, §7.4.4); known
     * interactions ABSENT from the snapshot flip to STALE (§14.4.3 — submit disabled
     * until authoritative state confirms).
     */
    suspend fun reconcileFromSnapshot(rows: List<ConversationRow>) {
        val snapshotInteractionIds = HashSet<InteractionId>()
        for (row in rows) {
            if (row !is ConversationRow.ToolCall) continue
            log.debug("reconcile.toolCall", "tool=${row.toolName} status=${row.status.wire} approval=${row.approvalInteractionId}")
            val iid = row.approvalInteractionId ?: continue
            if (row.status != ConversationRow.ToolCallStatus.PENDING_APPROVAL) continue
            val interactionId = InteractionId(iid)
            snapshotInteractionIds.add(interactionId)
            if (interactionId in requests) continue
            // Recovered open interaction — card + record now (S13 "create card + notification now").
            val request = PermissionRequest(
                interactionId = interactionId,
                sessionId = null, // the row plane carries no session id; binding arrives with the Phase 7 event plane
                toolName = row.toolName,
                reason = null,
                input = row.input,
                // Verified synthesized option vocabulary (§7.4.4) — the v4 plane's own
                // allow/deny ids; conservative subset (no context-dependent ids invented).
                options = listOf(
                    com.zcode.android.domain.model.PermissionOption("allowOnce"),
                    com.zcode.android.domain.model.PermissionOption("allowAlways"),
                    com.zcode.android.domain.model.PermissionOption("deny"),
                ),
                createdAtMs = clock(),
                raw = row.raw,
            )
            onPermissionRequested(request)
            render(interactionId)
        }
        // Known interactions missing from the authoritative snapshot → STALE.
        for ((iid, machine) in machines.toList()) {
            if (iid !in snapshotInteractionIds) {
                runCatching { machine.on(PermissionStateMachine.Event.ResnapshotLacks) }
                updateLifecycle(iid, machine.state)
            }
        }
        interactions.sweep(endpointId, clock())
    }

    /** Machines for open cards (recovery reload path — store is authoritative for lifecycle). */
    fun knownInteractionIds(): Set<InteractionId> = machines.keys.toSet()

    fun machineStateForTest(interactionId: InteractionId): PermissionStateMachine.State? = machines[interactionId]?.state

    companion object {
        private val TERMINAL_MACHINE_STATES = setOf(
            PermissionStateMachine.State.RESOLVED, PermissionStateMachine.State.STALE,
            PermissionStateMachine.State.FAILED, PermissionStateMachine.State.CANCELLED,
            PermissionStateMachine.State.EXPIRED,
        )
    }

    private fun machineFor(interactionId: InteractionId): PermissionStateMachine? = machines[interactionId]

    private suspend fun updateLifecycle(interactionId: InteractionId, state: PermissionStateMachine.State) {
        val request = requests[interactionId] ?: return
        interactions.upsertPermission(request, endpointId, lifecycleOf(state))
    }

    private fun lifecycleOf(state: PermissionStateMachine.State): InteractionLifecycle = when (state) {
        PermissionStateMachine.State.REQUESTED -> InteractionLifecycle.REQUESTED
        PermissionStateMachine.State.DISPLAYED -> InteractionLifecycle.DISPLAYED
        PermissionStateMachine.State.SUBMITTING -> InteractionLifecycle.SUBMITTING
        PermissionStateMachine.State.RESOLVED -> InteractionLifecycle.RESOLVED
        PermissionStateMachine.State.EXPIRED -> InteractionLifecycle.EXPIRED
        PermissionStateMachine.State.CANCELLED -> InteractionLifecycle.CANCELLED
        PermissionStateMachine.State.STALE -> InteractionLifecycle.STALE
        PermissionStateMachine.State.FAILED -> InteractionLifecycle.FAILED
    }
}
