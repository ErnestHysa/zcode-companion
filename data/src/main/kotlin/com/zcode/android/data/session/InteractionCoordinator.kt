package com.zcode.android.data.session

import com.zcode.android.core.common.error.serverSpoke
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

    /**
     * Card payload record per known interaction — BOTH planes (Phase 7 U9: user-input
     * requests submit through the same gate, so they need sessionId/options here too).
     */
    private class KnownRequest(
        val sessionId: String?,
        val options: List<com.zcode.android.domain.model.PermissionOption>,
        val permission: PermissionRequest? = null,
        val userInput: UserInputRequest? = null,
    )

    private val requests = HashMap<InteractionId, KnownRequest>()

    /** INV-4: the pending outbox entry per interaction — retries REUSE its commandId. */
    private val pendingCommands = HashMap<InteractionId, CommandId>()

    // ---------------------------------------------------------------- events in

    suspend fun onPermissionRequested(request: PermissionRequest) {
        machines[request.interactionId] = PermissionStateMachine(request.interactionId)
        requests[request.interactionId] = KnownRequest(request.sessionId, request.options, permission = request)
        interactions.upsertPermission(request, endpointId, InteractionLifecycle.REQUESTED)
    }

    suspend fun onUserInputRequested(request: UserInputRequest) {
        machines[request.interactionId] = PermissionStateMachine(request.interactionId)
        requests[request.interactionId] = KnownRequest(request.sessionId, request.options, userInput = request)
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

    /** Which answer policy gates the submit (§20.4 permission vs the U9 elicitation plane). */
    enum class AnswerKind { PERMISSION, USER_INPUT }

    suspend fun submit(
        interactionId: InteractionId,
        optionId: String,
        freeText: String? = null,
        kind: AnswerKind = AnswerKind.PERMISSION,
    ): SubmitOutcome {
        val machine = machines[interactionId] ?: return SubmitOutcome.UnknownInteraction
        val request = requests[interactionId] ?: return SubmitOutcome.UnknownInteraction
        // §20.4: classification BEFORE any state change — an unknown option never
        // reaches the machine, the outbox, or the wire.
        val decision = when (kind) {
            AnswerKind.PERMISSION ->
                PermissionStateMachine.AnswerPolicy.decide(request.options, optionId, freeText)
            AnswerKind.USER_INPUT ->
                PermissionStateMachine.AnswerPolicy.decideUserInput(request.options, optionId, freeText)
        }
        val answer = when (decision) {
            is PermissionStateMachine.AnswerPolicy.Decision.Submit -> decision
            is PermissionStateMachine.AnswerPolicy.Decision.InertUnknown -> return SubmitOutcome.InertOption
            is PermissionStateMachine.AnswerPolicy.Decision.RefineRequiresText -> return SubmitOutcome.RefineRequiresText
            is PermissionStateMachine.AnswerPolicy.Decision.NotApprovable -> return SubmitOutcome.NotApprovable
        }

        runCatching { machine.on(PermissionStateMachine.Event.UserChose(optionId, freeText)) }
            .onFailure { return SubmitOutcome.DuplicateNoOp } // includes RESOLVED/STALE → Submitting illegals
        if (machine.state != PermissionStateMachine.State.SUBMITTING) {
            return SubmitOutcome.DuplicateNoOp
        }
        updateLifecycle(interactionId, machine.state)

        // ACCEPT: the outbox mints the commandId exactly once (INV-4).
        // Verified resolveInteraction payload (command.ts): answer {optionId?, freeText?}
        // — optionId is omitted entirely for free-text-only elicitation answers.
        val payload = buildJsonObject {
            put("interactionId", interactionId.raw)
            put("answer", buildJsonObject {
                if (answer.optionId.isNotEmpty()) put("optionId", answer.optionId)
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
            // Transmit is legal only pre-contact (ACCEPTED_LOCALLY). A retry entered
            // through UserGatedRetry has ALREADY moved the entry to TRANSMITTED —
            // re-applying Transmit would throw an illegal transition and eat the
            // retry (Phase 7 finding: the first suite to execute the retry path).
            if (outbox.get(commandId)?.state == OutboxStateMachine.State.ACCEPTED_LOCALLY) {
                outbox.apply(commandId, OutboxStateMachine.Event.Transmit, clock())
            }
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
                // Truthful attribution: a disappearance-reconciled card may already be
                // RESOLVED-by-elsewhere before the late ack returns (§20.3 row 3).
                interactions.markResolved(endpointId, interactionId, clock(), resolvedBySelf = !machine.resolvedByElsewhere)
                pendingCommands.remove(interactionId) // terminal: the entry's work is done
            } else if (machine.state in TERMINAL_MACHINE_STATES) {
                pendingCommands.remove(interactionId)
            }
            updateLifecycle(interactionId, machine.state)
            return SubmitOutcome.Submitted(ack)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // FIELD (2026-10-01, vc22 device): server-spoke faults (fault.* channel
            // errors) are AUTHORITATIVE — ack rejected → machine FAILED (recoverable
            // by resync, §20.3 row 8), never the retry loop.
            if (e is com.zcode.android.core.transport.TransportFailure && e.error.serverSpoke) {
                runCatching {
                    outbox.apply(
                        commandId,
                        OutboxStateMachine.Event.Ack("rejected", e.error.developerDiagnostic.take(64), e.error.userMessage),
                        clock(),
                    )
                }
                runCatching { machine.on(PermissionStateMachine.Event.AckRejected) }
                updateLifecycle(interactionId, machine.state)
                log.warn("interaction.rejected", e.error.developerDiagnostic.take(80))
                return SubmitOutcome.Submitted(
                    com.zcode.android.core.protocol.v4.CommandAck(
                        commandId = commandId.raw,
                        status = "rejected",
                        revisionAtDecision = 0,
                        reasonCode = e.error.developerDiagnostic.take(64),
                        message = e.error.userMessage,
                        result = null,
                    ),
                )
            }
            // Outcome UNKNOWN (never assume success, §15.5); card retryable-DISPLAYED
            // with the SAME commandId (§20.3 row 5; INV-4 preserved).
            runCatching { outbox.apply(commandId, OutboxStateMachine.Event.SendLost, clock()) }
            runCatching { machine.on(PermissionStateMachine.Event.SendFailedPreContact) }
            updateLifecycle(interactionId, machine.state)
            log.warn("interaction.submitRetryable", "${e.javaClass.simpleName}")
            return SubmitOutcome.RetryableKept(commandId)
        }
    }

    /**
     * Approvals-inbox submit (§18.3): answer an interaction whose card machine may
     * not be in memory (its session was never attached in this process). Hydrates
     * the machine + request from the interaction STORE payload, then re-enters the
     * SAME fail-closed gate — no second submission path exists (INV-14).
     */
    suspend fun submitFromStore(
        interactionId: InteractionId,
        optionId: String,
        freeText: String? = null,
    ): SubmitOutcome {
        if (machines[interactionId] != null && requests[interactionId] != null) {
            return submit(interactionId, optionId, freeText, AnswerKind.PERMISSION)
        }
        val stored = interactions.loadStored(endpointId, interactionId)
            ?: return SubmitOutcome.UnknownInteraction
        if (stored.record.resolvedAtMs != null) return SubmitOutcome.DuplicateNoOp
        val payload = stored.payload
        val answerKind = when (stored.record.kind) {
            InteractionRepository.InteractionRecord.Kind.USER_INPUT -> {
                onUserInputRequested(
                    UserInputRequest(
                        interactionId = interactionId,
                        sessionId = stored.record.sessionId?.raw,
                        prompt = payload.strOrNull("prompt"),
                        options = optionIdsOf(payload),
                        createdAtMs = stored.record.createdAtMs,
                        raw = payload,
                    )
                )
                AnswerKind.USER_INPUT
            }
            InteractionRepository.InteractionRecord.Kind.PERMISSION -> {
                onPermissionRequested(
                    PermissionRequest(
                        interactionId = interactionId,
                        sessionId = stored.record.sessionId?.raw,
                        toolName = stored.record.toolName ?: payload.strOrNull("toolName") ?: "",
                        reason = payload.strOrNull("summary"),
                        input = payload["detail"] as? JsonObject,
                        options = optionIdsOf(payload),
                        createdAtMs = stored.record.createdAtMs,
                        raw = payload,
                    )
                )
                AnswerKind.PERMISSION
            }
        }
        // §14.4.3: submit is only legal from DISPLAYED — render the hydrated card first.
        render(interactionId)
        return submit(interactionId, optionId, freeText, answerKind)
    }

    private fun optionIdsOf(payload: JsonObject): List<com.zcode.android.domain.model.PermissionOption> =
        (payload["options"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.mapNotNull { it.strOrNull("optionId") }
            ?.map { com.zcode.android.domain.model.PermissionOption(it) }
            ?: emptyList()

    private fun JsonObject.strOrNull(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content

    // ---------------------------------------------------------------- elicitation answers (AskUserQuestion)

    /**
     * Answer a question-type user-input interaction (AskUserQuestion plane).
     *
     * VERIFIED wire shape (desktop ElicitationDialog.buildElicitationResponseContent):
     * `answer { action: "accept", content }` where content carries
     * `answers: {"<question>": "<values joined ', '>"}`, per-question
     * `answer_<i>: string | string[]`, and the legacy single-question `answer`.
     * Empty answers are OMITTED (the agent continues on best judgment — never
     * fabricate empty preferences). Same outbox INV-4 gate as every other submit.
     */
    suspend fun submitElicitationAnswers(
        interactionId: InteractionId,
        answers: Map<String, List<String>>,
    ): SubmitOutcome {
        val machine = machines[interactionId] ?: return SubmitOutcome.UnknownInteraction
        val request = requests[interactionId] ?: return SubmitOutcome.UnknownInteraction
        if (request.userInput == null) return SubmitOutcome.NotApprovable

        runCatching { machine.on(PermissionStateMachine.Event.UserChose("accept", null)) }
            .onFailure { return SubmitOutcome.DuplicateNoOp }
        if (machine.state != PermissionStateMachine.State.SUBMITTING) {
            return SubmitOutcome.DuplicateNoOp
        }
        updateLifecycle(interactionId, machine.state)

        val nonEmpty = answers.filterValues { values -> values.any { it.isNotBlank() } }
        if (nonEmpty.isEmpty()) {
            // Nothing provided: back the machine off — an empty form was never sent.
            runCatching { machine.on(PermissionStateMachine.Event.SendFailedPreContact) }
            updateLifecycle(interactionId, machine.state)
            return SubmitOutcome.NotApprovable
        }
        val content = buildJsonObject {
            put("answers", buildJsonObject {
                for ((question, values) in nonEmpty) {
                    put(question, values.joinToString(", "))
                }
            })
            nonEmpty.entries.forEachIndexed { index, (_, values) ->
                put(
                    "answer_$index",
                    if (values.size == 1) kotlinx.serialization.json.JsonPrimitive(values.first())
                    else kotlinx.serialization.json.buildJsonArray { values.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } },
                )
            }
            if (nonEmpty.size == 1) {
                val values = nonEmpty.values.first()
                put(
                    "answer",
                    if (values.size == 1) kotlinx.serialization.json.JsonPrimitive(values.first())
                    else kotlinx.serialization.json.buildJsonArray { values.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } },
                )
            }
        }
        val payload = buildJsonObject {
            put("interactionId", interactionId.raw)
            put("answer", buildJsonObject {
                put("action", "accept")
                put("content", content)
            })
        }

        val existing = pendingCommands[interactionId]
        val commandId: CommandId = when {
            existing == null -> {
                val minted = outbox.accept(
                    endpointId = endpointId,
                    sessionId = request.sessionId,
                    type = "resolveInteraction",
                    payloadJson = payload.toString(),
                    baseRevision = null,
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
            else -> return SubmitOutcome.DuplicateNoOp
        }
        try {
            if (outbox.get(commandId)?.state == OutboxStateMachine.State.ACCEPTED_LOCALLY) {
                outbox.apply(commandId, OutboxStateMachine.Event.Transmit, clock())
            }
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
                interactions.markResolved(endpointId, interactionId, clock(), resolvedBySelf = !machine.resolvedByElsewhere)
                pendingCommands.remove(interactionId)
            } else if (machine.state in TERMINAL_MACHINE_STATES) {
                pendingCommands.remove(interactionId)
            }
            updateLifecycle(interactionId, machine.state)
            return SubmitOutcome.Submitted(ack)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (e is com.zcode.android.core.transport.TransportFailure && e.error.serverSpoke) {
                runCatching {
                    outbox.apply(
                        commandId,
                        OutboxStateMachine.Event.Ack("rejected", e.error.developerDiagnostic.take(64), e.error.userMessage),
                        clock(),
                    )
                }
                runCatching { machine.on(PermissionStateMachine.Event.AckRejected) }
                updateLifecycle(interactionId, machine.state)
                log.warn("interaction.rejected", e.error.developerDiagnostic.take(80))
                return SubmitOutcome.Submitted(ack = com.zcode.android.core.protocol.v4.CommandAck(
                    commandId = commandId.raw, status = "rejected", revisionAtDecision = 0,
                    reasonCode = e.error.developerDiagnostic.take(64), message = e.error.userMessage, result = null,
                ))
            }
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

    // ---------------------------------------------------------------- Phase 7: pendingInteractions feed

    /**
     * Authoritative interaction reconcile from the verified `pendingInteractions`
     * state key (docs/protocol/v4-wire.md §9 — Q11 RESOLVED: snapshots DO carry open
     * interactions). Presence creates/refreshes cards (bound to THIS session — the
     * §9.13 mapping finally lands: snapshot-reconciled requests carry sessionId);
     * authoritative DISAPPEARANCE of a known interaction of this session resolves it
     * by-elsewhere (§20.3 row 3 / §14.4.3 "Any" row). Unknown interaction kinds are
     * counted and never rendered actionable (INV-14).
     *
     * A null `pendingInteractions` key means the projection carried no list (legacy
     * shape) — nothing is reconciled from this source; the Phase 5 toolCall-row
     * fallback stays authoritative for such frames.
     */
    suspend fun reconcileFromSessionState(sessionId: String, state: com.zcode.android.domain.model.SessionProjectionState) {
        val pending = state.pendingInteractions ?: return
        var unknownKinds = 0
        val openIds = HashSet<InteractionId>()
        for (pi in pending) {
            val iid = InteractionId(pi.interactionId)
            val permission = pi.permission
            val userInput = pi.userInput
            when {
                pi.kind == com.zcode.android.domain.model.PendingInteraction.Kind.PERMISSION && permission != null -> {
                    openIds.add(iid)
                    val request = PermissionRequest(
                        interactionId = iid,
                        sessionId = sessionId,
                        toolName = permission.toolName,
                        reason = permission.summary,
                        input = permission.detail,
                        options = permission.options,
                        createdAtMs = pi.createdAtMs ?: clock(),
                        raw = pi.raw,
                    )
                    if (machines[iid] == null) {
                        onPermissionRequested(request)
                        render(iid)
                    } else {
                        // Refresh the payload (detail/summary may evolve); lifecycle state unchanged.
                        requests[iid] = KnownRequest(request.sessionId, request.options, permission = request)
                        interactions.upsertPermission(request, endpointId, lifecycleOf(machines[iid]!!.state))
                    }
                }
                pi.kind == com.zcode.android.domain.model.PendingInteraction.Kind.USER_INPUT && userInput != null -> {
                    openIds.add(iid)
                    val request = UserInputRequest(
                        interactionId = iid,
                        sessionId = sessionId,
                        prompt = userInput.prompt,
                        options = userInput.options,
                        createdAtMs = pi.createdAtMs ?: clock(),
                        raw = pi.raw,
                    )
                    if (machines[iid] == null) {
                        onUserInputRequested(request)
                        render(iid)
                    } else {
                        requests[iid] = KnownRequest(request.sessionId, request.options, userInput = request)
                        interactions.upsertUserInput(request, endpointId, lifecycleOf(machines[iid]!!.state))
                    }
                }
                else -> {
                    unknownKinds++
                    log.debug("reconcile.pendingKind", "kind=${pi.kind.wire} (counted, no card — INV-14)")
                }
            }
        }
        // Disappearance = authoritative resolution (the list is the whole truth —
        // key-level replacement, never a delta). Only THIS session's interactions.
        for ((iid, machine) in machines.toList()) {
            if (requests[iid]?.sessionId == sessionId && iid !in openIds) {
                runCatching { machine.on(PermissionStateMachine.Event.ResolvedElsewhere) }
                if (machine.state == PermissionStateMachine.State.RESOLVED) {
                    interactions.markResolved(endpointId, iid, clock(), resolvedBySelf = false)
                    pendingCommands.remove(iid)
                }
                updateLifecycle(iid, machine.state)
            }
        }
        interactions.sweep(endpointId, clock())
    }

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
        val known = requests[interactionId] ?: return
        known.permission?.let { interactions.upsertPermission(it, endpointId, lifecycleOf(state)) }
        known.userInput?.let { interactions.upsertUserInput(it, endpointId, lifecycleOf(state)) }
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
