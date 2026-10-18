package com.zcode.android.data.session

import com.zcode.android.core.common.error.serverSpoke
import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.protocol.v4.CommandAck
import com.zcode.android.core.transport.ConnectionStateMachine
import com.zcode.android.domain.ids.CommandId
import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.domain.outbox.CommandRetryClassifier
import com.zcode.android.domain.outbox.OutboxEntry
import com.zcode.android.domain.outbox.OutboxStateMachine
import com.zcode.android.domain.repo.OutboxRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * Command coordinator — Phase 7.2/7.5 (§17.5 outbox send path, §28.6 idempotency
 * classification, §15.5 "no timeout = assume success", §17.6 offline model).
 *
 * The ONE submission gate for non-interaction commands (`sendText`, `stop`,
 * `cancelBackgroundWork`, queue edits). Interaction answers keep their own
 * fail-closed gate in [InteractionCoordinator] — both mint exactly one commandId
 * through the SAME outbox (INV-4) and never bypass its state machine.
 *
 * §17.6 offline model: with the transport not CONNECTED a submission is BLOCKED
 * ("no silent queuing" — approvals/prompts are never decoupled from their context);
 * the draft stays a draft. §15.5: a send whose outcome is lost goes UNKNOWN — the
 * UI shows a truthful pending state, retry is USER-GATED for sendText (PRE_TRANSMIT_
 * ONLY, §28.6) and automatic only for the verified SAFE_AGGRESSIVE family.
 */
class CommandCoordinator(
    private val endpointId: EndpointId,
    private val outbox: OutboxRepository,
    private val commands: CommandSender,
    private val connectionState: () -> ConnectionStateMachine.ConnectionState,
    /** Conversation-plane listens (createSession MUST register the workspace scope first — round-3 field fidelity). */
    private val ensureConversationListen: (workspacePath: String?, workspaceIdentity: String?) -> Unit = { _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: ZLog = ZLog(),
) {

    sealed class Outcome {
        /** Idempotent ACK accepted/duplicate/noop — authoritative acceptance. */
        data class Accepted(val ack: CommandAck) : Outcome()

        /** §17.6: transport not connected — NOT queued, draft preserved. */
        data object BlockedOffline : Outcome()

        /** §20.4-adjacent CAS guard (queue commands): the caller omitted baseRevision. */
        data object MissingBaseRevision : Outcome()

        /** CAS `stale` (§15-S16): "conversation changed — please try again", draft preserved. */
        data class Stale(val ack: CommandAck) : Outcome()

        /** Server rejected/failed the command (typed ack), or outcome lost. */
        data class Failed(val ack: CommandAck?) : Outcome()

        /** Outcome UNKNOWN (§15.5) — entry kept for user-gated/SAFE_AGGRESSIVE retry. */
        data class UnknownKept(val commandId: CommandId) : Outcome()
    }

    // ---------------------------------------------------------------- pending overlay (§14.2)

    enum class SendState { SENDING, SENT_UNKNOWN, FAILED_RETRYABLE }

    /**
     * Optimistic send overlay — Android-owned UI state (§14.2), in-memory only.
     * Reconciliation: dropped when the authoritative projection shows the userInput
     * row (ViewModel) or on terminal ack; > 60 s pending renders degraded (§14.3).
     */
    data class PendingSend(
        val commandId: CommandId,
        val sessionId: String?,
        val text: String,
        val state: SendState,
        val atMs: Long,
    )

    private val _pendingSends = MutableStateFlow<List<PendingSend>>(emptyList())
    val pendingSends: StateFlow<List<PendingSend>> = _pendingSends.asStateFlow()

    // ---------------------------------------------------------------- sendText (7.2)

    /**
     * Verified `sendText` payload (command.ts): `{text}` required; the optional
     * routing keys (`requestedDelivery`, `heldQueueDisposition`,
     * `expectedHeldQueueItemIds`) ride the inputRouting decision (§9). sendText is
     * NOT in COMMANDS_REQUIRING_BASE_REVISION (verified set) — no CAS guard here.
     */
    /** attachmentRefSchema projection ({ref, fileName, mime, bytes} — strict, §10.2). */
    data class AttachmentRefPayload(val ref: String, val fileName: String, val mime: String, val bytes: Long)

    suspend fun sendPrompt(
        sessionId: String,
        text: String,
        requestedDelivery: String? = null,
        heldQueueDisposition: String? = null,
        expectedHeldQueueItemIds: List<String>? = null,
        /** Phase 9 (§23.2): committed attachment refs — content bytes NEVER ride the envelope. */
        attachments: List<AttachmentRefPayload>? = null,
    ): Outcome {
        if (connectionState() != ConnectionStateMachine.ConnectionState.CONNECTED) {
            log.warn("command.blockedOffline", "type=sendText session=$sessionId")
            return Outcome.BlockedOffline
        }
        if (text.isBlank()) return Outcome.Failed(null)
        val payload = buildJsonObject {
            put("text", text)
            requestedDelivery?.let { put("requestedDelivery", it) }
            heldQueueDisposition?.let { put("heldQueueDisposition", it) }
            expectedHeldQueueItemIds?.let { ids ->
                putJsonArray("expectedHeldQueueItemIds") { ids.forEach { add(JsonPrimitive(it)) } }
            }
            attachments?.takeIf { it.isNotEmpty() }?.let { refs ->
                putJsonArray("attachments") {
                    refs.forEach { a ->
                        add(buildJsonObject {
                            put("ref", a.ref)
                            put("fileName", a.fileName)
                            put("mime", a.mime)
                            put("bytes", a.bytes)
                        })
                    }
                }
            }
        }
        val commandId = accept("sendText", sessionId, payload) ?: return Outcome.BlockedOffline
        overlay(commandId, sessionId, text, SendState.SENDING)
        return transmitPending(commandId, type = "sendText", sessionId = sessionId, payload = payload)
    }

    // ---------------------------------------------------------------- stop / cancel (7.5)

    /** `stop` (§28.6 SAFE_AGGRESSIVE — aggressive retry safe). */
    suspend fun stopGeneration(sessionId: String, expectedForegroundExecutionId: String? = null): Outcome =
        simpleCommand("stop", sessionId, buildJsonObject {
            expectedForegroundExecutionId?.let { put("expectedForegroundExecutionId", it) }
        })

    /** `cancelBackgroundWork {workId}` (§28.6 SAFE_AGGRESSIVE). */
    suspend fun cancelBackgroundWork(sessionId: String, workId: String): Outcome =
        simpleCommand("cancelBackgroundWork", sessionId, buildJsonObject { put("workId", workId) })

    // ---------------------------------------------------------------- slash commands (Phase 2, §11.3)

    /**
     * `/compact` (alias /compress) — `compact {}` is an INPUT-TYPE maintenance
     * command: NOT CAS-guarded (verified set excludes it, command.ts:148-150);
     * queued FIFO when busy/held. Duplicate compaction surfaces as a typed ack
     * (reasonCode compactOperationLock / activeTurn) — the UI toasts, never guesses.
     */
    suspend fun compact(sessionId: String): Outcome =
        simpleCommand("compact", sessionId, buildJsonObject { })

    /**
     * `/goal <objective>` (and `/goal replace <objective>`) — `sendGoalCommand
     * {text, displayText}`; not CAS-guarded. displayText carries the ORIGINAL
     * slash text (the desktop's row-render contract, SessionPane.tsx:2459-2470).
     */
    suspend fun sendGoalCommand(sessionId: String, objective: String, displayText: String): Outcome =
        simpleCommand("sendGoalCommand", sessionId, buildJsonObject {
            put("text", objective)
            put("displayText", displayText)
        })

    /** `/goal resume` — `resumeGoal {}` IS CAS-guarded (verified set §5). */
    suspend fun resumeGoal(sessionId: String, baseRevision: Long?): Outcome =
        casCommand("resumeGoal", sessionId, baseRevision, buildJsonObject { })

    // ---------------------------------------------------------------- createSession (task creation)

    /**
     * Create a task in an existing workspace — the parity feature (§6.1: the web
     * remote creates tasks in existing workspaces). VERIFIED wire shape (desktop
     * adapter, replayable path): `createSession` with sessionId=null,
     * `payload.workspaceId = workspaceIdentity?.trim() || workspacePath` (§7.11),
     * optional `firstInput {text}` — the ack's `result.sessionId` carries the new
     * session. Not CAS-guarded (verified set). The workspace scope listen goes out
     * FIRST (round-3 field fidelity: unscoped conversation-plane calls are silent).
     */
    suspend fun createSession(
        workspacePath: String,
        workspaceIdentity: String?,
        firstText: String,
        provider: String? = null,
        model: String? = null,
    ): Outcome {
        if (connectionState() != ConnectionStateMachine.ConnectionState.CONNECTED) {
            log.warn("command.blockedOffline", "type=createSession")
            return Outcome.BlockedOffline
        }
        if (firstText.isBlank()) return Outcome.Failed(null)
        ensureConversationListen(workspacePath, workspaceIdentity)
        val identityKey = workspaceIdentity?.trim()?.takeIf { it.isNotEmpty() } ?: workspacePath
        val payload = buildJsonObject {
            put("workspaceId", identityKey)
            put("firstInput", buildJsonObject { put("text", firstText) })
            // FIELD (2026-10-12): model picker on task creation — the desktop's
            // createSessionRequestedConfigSchema carries {provider, model} as the
            // "requested override" for the new session (command.ts:30-41).
            if (provider != null || model != null) {
                put("config", buildJsonObject {
                    provider?.let { put("provider", JsonPrimitive(it)) }
                    model?.let { put("model", JsonPrimitive(it)) }
                })
            }
        }
        val commandId = accept("createSession", sessionId = null, payload = payload)
            ?: return Outcome.BlockedOffline
        // The workspace target is MANDATORY on the create wire — there is no session
        // for the transport to resolve it from (the fallback chain would drop it).
        return transmitPending(
            commandId,
            type = "createSession",
            sessionId = null,
            payload = payload,
            workspacePath = workspacePath,
            workspaceIdentity = workspaceIdentity,
        )
    }

    // ---------------------------------------------------------------- queue edits (7.5, CAS)

    /** `sendQueuedNow {queueItemId}` — CAS-guarded (verified set): baseRevision REQUIRED. */
    suspend fun sendQueuedNow(sessionId: String, queueItemId: String, baseRevision: Long?): Outcome =
        casCommand("sendQueuedNow", sessionId, baseRevision, buildJsonObject { put("queueItemId", queueItemId) })

    /** `editQueueItem {queueItemId, newText}` — CAS-guarded (verified set). */
    suspend fun editQueueItem(sessionId: String, queueItemId: String, newText: String, baseRevision: Long?): Outcome =
        casCommand("editQueueItem", sessionId, baseRevision, buildJsonObject {
            put("queueItemId", queueItemId)
            put("newText", newText)
        })

    // ---------------------------------------------------------------- model/mode switch (Phase 9.5)

    /**
     * `switchModelConfig {provider, model, thought}` — CAS-guarded (verified set,
     * §10.3). Echo-reconciled: the authoritative `config` state patch (key-level
     * replacement) is the truth; the ACK only confirms admission (§14.2 "runtime
     * echo wins").
     */
    suspend fun switchModelConfig(
        sessionId: String,
        provider: String,
        model: String,
        thought: String,
        baseRevision: Long?,
    ): Outcome = casCommand("switchModelConfig", sessionId, baseRevision, buildJsonObject {
        put("provider", provider)
        put("model", model)
        put("thought", thought)
    })

    /**
     * `switchCollaborationMode {mode}` — CAS-guarded; the verified switchable set is
     * build|edit|plan|yolo (`auto` is NOT user-switchable, schema comment). The
     * caller validates against [com.zcode.android.domain.model.SessionConfigState.SWITCHABLE_MODES];
     * this gate re-checks fail-closed (an out-of-set mode is never sent).
     */
    suspend fun switchCollaborationMode(sessionId: String, mode: String, baseRevision: Long?): Outcome {
        if (mode !in com.zcode.android.domain.model.SessionConfigState.SWITCHABLE_MODES) {
            return Outcome.Failed(null)
        }
        return casCommand("switchCollaborationMode", sessionId, baseRevision, buildJsonObject {
            put("mode", mode)
        })
    }

    // ---------------------------------------------------------------- retry / recovery

    /** User-gated retry (§17.5 post-contact policy; §28.6) — SAME commandId (INV-4). */
    suspend fun retry(commandId: CommandId): Outcome {
        val entry = outbox.get(commandId) ?: return Outcome.Failed(null)
        val payload = runCatching {
            com.zcode.android.data.session.jsonParser.parseToJsonElement(entry.payloadJson).jsonObject
        }.getOrNull() ?: return Outcome.Failed(null)
        val state = outbox.get(commandId)?.state
        return when (state) {
            OutboxStateMachine.State.UNKNOWN, OutboxStateMachine.State.FAILED_RETRYABLE -> {
                outbox.apply(commandId, OutboxStateMachine.Event.UserGatedRetry, clock())
                if (entry.type == "sendText") overlay(commandId, entry.sessionId, textOf(payload), SendState.SENDING)
                transmitPending(commandId, entry.type, entry.sessionId, payload)
            }
            else -> Outcome.Failed(null) // live/terminal entry — nothing to retry
        }
    }

    /**
     * §28.5/§17.5 reconnect pass: retransmit FAILED_RETRYABLE entries (pre-contact —
     * no bytes ever sent, any class) and the SAFE_AGGRESSIVE family from UNKNOWN;
     * everything else waits for its user. Plus the §17.5 TTL sweep (24 h verified).
     */
    suspend fun onConnected() {
        val live = outbox.observePending(endpointId).first()
        for (entry in live) {
            val canAuto = CommandRetryClassifier.canAutoRetry(entry.type, entry.state)
            if (!canAuto) continue
            val payload = runCatching {
                com.zcode.android.data.session.jsonParser.parseToJsonElement(entry.payloadJson).jsonObject
            }.getOrNull() ?: continue
            // Same machine-legal retransmission transition for both classes (§28.6).
            outbox.apply(entry.commandId, OutboxStateMachine.Event.AutoRetransmit, clock())
            if (entry.type == "sendText") overlay(entry.commandId, entry.sessionId, textOf(payload), SendState.SENDING)
            transmitPending(entry.commandId, entry.type, entry.sessionId, payload)
        }
        val expired = outbox.sweepExpired(endpointId, clock())
        if (live.isNotEmpty() || expired > 0) {
            log.info("command.recoveryPass", "retransmitted=${live.count { CommandRetryClassifier.canAutoRetry(it.type, it.state) }} expired=$expired")
        }
    }

    /** Overlay reconcile: the authoritative row landed — drop the matching pending. */
    fun reconcileAuthoritative(sessionId: String, sentText: String) {
        _pendingSends.value = _pendingSends.value.filterNot {
            it.sessionId == sessionId && it.text == sentText
        }
    }

    fun dropOverlay(commandId: CommandId) {
        _pendingSends.value = _pendingSends.value.filterNot { it.commandId == commandId }
    }

    // ---------------------------------------------------------------- internals

    /** ACCEPT: mint commandId once (INV-4); null = blocked offline (no entry created). */
    private suspend fun accept(type: String, sessionId: String?, payload: JsonObject): CommandId? {
        if (connectionState() != ConnectionStateMachine.ConnectionState.CONNECTED) return null
        return outbox.accept(
            endpointId = endpointId,
            sessionId = sessionId,
            type = type,
            payloadJson = payload.toString(),
            baseRevision = null,
            baseLogEpoch = null,
            issuedAtMs = clock(),
        )
    }

    private suspend fun simpleCommand(type: String, sessionId: String, payload: JsonObject): Outcome {
        if (connectionState() != ConnectionStateMachine.ConnectionState.CONNECTED) return Outcome.BlockedOffline
        val commandId = accept(type, sessionId, payload) ?: return Outcome.BlockedOffline
        return transmitPending(commandId, type, sessionId, payload)
    }

    private suspend fun casCommand(type: String, sessionId: String, baseRevision: Long?, payload: JsonObject): Outcome {
        if (connectionState() != ConnectionStateMachine.ConnectionState.CONNECTED) return Outcome.BlockedOffline
        if (baseRevision == null) return Outcome.MissingBaseRevision
        val commandId = outbox.accept(
            endpointId = endpointId,
            sessionId = sessionId,
            type = type,
            payloadJson = payload.toString(),
            baseRevision = baseRevision,
            baseLogEpoch = null,
            issuedAtMs = clock(),
        )
        return transmitPending(commandId, type, sessionId, payload)
    }

    /**
     * Row-targeting CAS variant (Phase 2, GAP-10): ROW_TARGETING_COMMANDS need
     * BOTH currencies — baseRevision (verified CAS set) AND baseLogEpoch —
     * validated LOCALLY before accept (a missing one is never a lenient send;
     * the envelope builder would reject it later, but the outbox entry must not
     * exist half-currencied).
     */
    private suspend fun rowTargetingCommand(
        type: String,
        sessionId: String,
        baseRevision: Long?,
        baseLogEpoch: String?,
        payload: JsonObject,
    ): Outcome {
        if (connectionState() != ConnectionStateMachine.ConnectionState.CONNECTED) return Outcome.BlockedOffline
        if (baseRevision == null || baseLogEpoch.isNullOrBlank()) return Outcome.MissingBaseRevision
        val commandId = outbox.accept(
            endpointId = endpointId,
            sessionId = sessionId,
            type = type,
            payloadJson = payload.toString(),
            baseRevision = baseRevision,
            baseLogEpoch = baseLogEpoch,
            issuedAtMs = clock(),
        )
        return transmitPending(commandId, type, sessionId, payload)
    }

    /**
     * `editUserQuery {target: {rowId, entityId}, newText}` (Phase 2, GAP-10;
     * docs/protocol/v4-wire.md §11.4): row-targeted AND CAS-guarded — the desktop
     * sends current.revision + current.logEpoch (SessionPane.tsx:3055-3066).
     * workspaceMode stays OMITTED (= the desktop's `preserve` default — branch
     * switch only); attachments omitted = preserve canonical originals. The turn
     * replacement is authoritative churn (row.removed + row.appended in the same
     * epoch, or an epoch move → S9 snapshot) — NO optimistic row edit exists here.
     */
    suspend fun editUserQuery(
        sessionId: String,
        rowId: Long,
        entityId: String,
        newText: String,
        baseRevision: Long?,
        baseLogEpoch: String?,
    ): Outcome {
        if (newText.isBlank()) return Outcome.Failed(null)
        return rowTargetingCommand(
            type = "editUserQuery",
            sessionId = sessionId,
            baseRevision = baseRevision,
            baseLogEpoch = baseLogEpoch,
            payload = buildJsonObject {
                put("target", buildJsonObject {
                    put("rowId", JsonPrimitive(rowId))
                    put("entityId", JsonPrimitive(entityId))
                })
                put("newText", newText)
            },
        )
    }

    // ---------------------------------------------------------------- row actions (Phase 4A: E3.2+I1+N5)

    /** `{target: {rowId, entityId}}` — the shared row-target payload (core.ts conversationRowTargetSchema). */
    private fun rowTarget(rowId: Long, entityId: String): JsonObject = buildJsonObject {
        put("target", buildJsonObject {
            put("rowId", JsonPrimitive(rowId))
            put("entityId", JsonPrimitive(entityId))
        })
    }

    /** `retryTurn {target}` — re-asks the turn (command.ts:161; the desktop's handleRetry). */
    suspend fun retryTurn(sessionId: String, rowId: Long, entityId: String, baseRevision: Long?, baseLogEpoch: String?): Outcome =
        rowTargetingCommand("retryTurn", sessionId, baseRevision, baseLogEpoch, rowTarget(rowId, entityId))

    /**
     * `forkAssistant {target}` — the ack's `result.sessionId` names the CHILD session
     * (commandResultSchema type=forkAssistant); the caller navigates there exactly like
     * the desktop (`onSessionCreated(ack.result.sessionId)`). Read it via [forkedSessionId].
     */
    suspend fun forkAssistant(sessionId: String, rowId: Long, entityId: String, baseRevision: Long?, baseLogEpoch: String?): Outcome =
        rowTargetingCommand("forkAssistant", sessionId, baseRevision, baseLogEpoch, rowTarget(rowId, entityId))

    /** `applyFileRewind {target}` — workspace-only file undo for the turn; chat history is not truncated. */
    suspend fun applyFileRewind(sessionId: String, rowId: Long, entityId: String, baseRevision: Long?, baseLogEpoch: String?): Outcome =
        rowTargetingCommand("applyFileRewind", sessionId, baseRevision, baseLogEpoch, rowTarget(rowId, entityId))

    /**
     * `setAssistantFeedback {target, feedback}` — `feedback: like|dislike|null` (strict
     * enum, NULLABLE): clearing is an EXPLICIT JSON null, an out-of-enum value never goes
     * on the wire (fail closed, INV-14).
     */
    suspend fun setAssistantFeedback(
        sessionId: String,
        rowId: Long,
        entityId: String,
        feedback: String?,
        baseRevision: Long?,
        baseLogEpoch: String?,
    ): Outcome {
        if (feedback != null && feedback !in FEEDBACK_VALUES) return Outcome.Failed(null)
        val payload = JsonObject(
            rowTarget(rowId, entityId) + ("feedback" to (feedback?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)),
        )
        return rowTargetingCommand("setAssistantFeedback", sessionId, baseRevision, baseLogEpoch, payload)
    }

    /** TRANSMIT → ACK / SendLost mapping (§15.5: a lost outcome is UNKNOWN, never success). */
    private suspend fun transmitPending(
        commandId: CommandId,
        type: String,
        sessionId: String?,
        payload: JsonObject,
        workspacePath: String? = null,
        workspaceIdentity: String? = null,
    ): Outcome {
        val state = outbox.get(commandId)?.state
        if (state == OutboxStateMachine.State.ACCEPTED_LOCALLY) {
            outbox.apply(commandId, OutboxStateMachine.Event.Transmit, clock())
        } // retry paths already moved the entry to TRANSMITTED (UserGatedRetry/AutoRetransmit)
        return try {
            val ack = commands.send(
                type = type,
                sessionId = sessionId,
                payload = payload,
                baseRevision = outbox.get(commandId)?.baseRevision,
                baseLogEpoch = outbox.get(commandId)?.baseLogEpoch,
                commandId = commandId.raw,
                workspacePath = workspacePath,
                workspaceIdentity = workspaceIdentity,
            )
            outbox.apply(
                commandId,
                OutboxStateMachine.Event.Ack(ack.status, ack.reasonCode, ack.message, ack.revisionAtDecision),
                clock(),
            )
            when (ack.status) {
                "accepted", "duplicate", "noop" -> {
                    if (type == "sendText") dropOverlay(commandId)
                    Outcome.Accepted(ack)
                }
                "stale" -> {
                    if (type == "sendText") overlay(commandId, sessionId, textOf(payload), SendState.FAILED_RETRYABLE)
                    Outcome.Stale(ack)
                }
                else -> {
                    if (type == "sendText") dropOverlay(commandId)
                    Outcome.Failed(ack)
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // FIELD (2026-10-01, vc22 device): distinguish SERVER-SPOKE rejections
            // (fault.* channel errors — the command REACHED the desktop and was
            // refused: authoritative evidence, outbox FAILED_TERMINAL, honest copy)
            // from genuine transport loss (§15.5 UNKNOWN — retryable). The old
            // catch-all sent clientMismatch into the retry loop forever.
            if (e is com.zcode.android.core.transport.TransportFailure && e.error.serverSpoke) {
                val fault = e.error.developerDiagnostic.take(64)
                runCatching {
                    outbox.apply(
                        commandId,
                        OutboxStateMachine.Event.Ack("rejected", fault, e.error.userMessage),
                        clock(),
                    )
                }
                if (type == "sendText") dropOverlay(commandId)
                log.warn("command.rejected", "type=$type $fault")
                // Authoritative rejection as an ack-shaped outcome (status=rejected):
                // the server SPOKE — never the UNKNOWN/retry class.
                return Outcome.Failed(
                    CommandAck(
                        commandId = commandId.raw,
                        status = "rejected",
                        revisionAtDecision = 0,
                        reasonCode = fault,
                        message = e.error.userMessage,
                        result = null,
                    ),
                )
            }
            // Outcome UNKNOWN (§15.5); sendText stays in the overlay as retryable-unknown.
            runCatching { outbox.apply(commandId, OutboxStateMachine.Event.SendLost, clock()) }
            if (type == "sendText") overlay(commandId, sessionId, textOf(payload), SendState.SENT_UNKNOWN)
            log.warn("command.sendLost", "type=$type ${e.javaClass.simpleName}: ${e.message?.take(80)}")
            Outcome.UnknownKept(commandId)
        }
    }

    private fun overlay(commandId: CommandId, sessionId: String?, text: String, state: SendState) {
        val current = _pendingSends.value.filterNot { it.commandId == commandId }
        _pendingSends.value = current + PendingSend(commandId, sessionId, text, state, clock())
        // INV-5: the overlay is human-rate tiny; a hard bound anyway (§28.7 discipline).
        if (_pendingSends.value.size > 64) {
            _pendingSends.value = _pendingSends.value.takeLast(64)
        }
    }

    private fun textOf(payload: JsonObject): String =
        (payload["text"] as? JsonPrimitive)?.content.orEmpty()

    // ---------------------------------------------------------------- observe (UI)

    fun observePendingSends(): StateFlow<List<PendingSend>> = pendingSends

    companion object {
        /** command.ts setAssistantFeedback: `z.enum(["like","dislike"]).nullable()`. */
        val FEEDBACK_VALUES = setOf("like", "dislike")

        /** The forkAssistant ack's child session (`result.type == "forkAssistant"`); null otherwise. */
        fun forkedSessionId(ack: CommandAck): String? {
            val result = ack.result ?: return null
            if ((result["type"] as? JsonPrimitive)?.content != "forkAssistant") return null
            return (result["sessionId"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        }
    }
}

internal val jsonParser = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
