package com.zcode.android.app.connect

import com.zcode.android.app.pairing.wsUrlForOrigin
import com.zcode.android.core.common.error.AppError
import com.zcode.android.core.security.vault.CredentialVault
import com.zcode.android.data.session.RecoveryCoordinator
import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.domain.model.Endpoint
import com.zcode.android.domain.model.StreamingBuffer
import com.zcode.android.domain.model.Task
import com.zcode.android.domain.repo.ConversationCacheBounds
import com.zcode.android.domain.repo.ConversationRepository
import com.zcode.android.domain.repo.ConversationStoreOp
import com.zcode.android.domain.repo.EndpointRepository
import com.zcode.android.domain.repo.TaskRepository
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.domain.state.TaskStateMachine
import com.zcode.android.features.conversation.port.ConversationPort
import com.zcode.android.features.tasks.port.TasksPort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first

/**
 * Composition-root implementations of the feature ports (the ONLY meeting point of
 * features and transport-owned machinery, mirroring DefaultPairingPort — §40.2,
 * INV-2/INV-6).
 */
class DefaultTasksPort(
    private val hub: ConnectionHub,
    private val vault: CredentialVault,
    private val taskRepo: TaskRepository,
    /** Round-4: re-pair acceptance wipes the stale pairing (wired to PairingPort.unpair). */
    private val rePair: () -> Unit = {},
) : TasksPort {

    override val suggestRepair: StateFlow<Boolean> get() = hub.suggestRepair

    override fun repairPairing() = rePair()

    /** Round-7 diagnostics: cached task count (suspend — call from the export path). */
    suspend fun cachedTaskCountForDiagnostics(): Int {
        val endpointId = vault.loadMetadata()?.endpointId ?: return -1
        return taskRepo.observeAllTasks(EndpointId(endpointId)).first().size
    }

    // FIELD FIX (2026-09-30, blank task list on first pair): the old cold `flow { }`
    // emitted emptyList and COMPLETED when the vault had no metadata yet — the exact
    // state during a fresh pairing. stateIn(Eagerly) collected the completed flow and
    // the list never updated again, even after bootstrap applied 493 tasks (diagnostics
    // 2026-09-30: hub.bootstrapApplied tasks=493, UI blank). Key on the hub's endpoint
    // StateFlow instead: unpaired → empty, pairing completes → Room observer takes
    // over; the flow never completes.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override val tasks: Flow<List<Task>> =
        hub.endpoint.flatMapLatest { endpoint ->
            if (endpoint == null) flowOf(emptyList())
            else taskRepo.observeAllTasks(endpoint.endpointId)
        }

    override val phase: StateFlow<ConnectionPhase> get() = hub.phase
    override val taskLoadError: StateFlow<AppError?> get() = hub.taskLoadError
    override val lastRefreshAtMs: StateFlow<Long?> get() = hub.lastRefreshAtMs
    override val endpointName: StateFlow<String?> get() = hub.endpointName

    override suspend fun refresh(): Boolean = hub.refreshTasks()

    override fun observeWorkspaces(): Flow<List<com.zcode.android.domain.model.WorkspaceOption>> =
        hub.workspaces

    override suspend fun createTask(workspaceKey: String, firstText: String): Boolean =
        hub.createTask(workspaceKey, firstText)

    override fun retryConnection() = hub.retry()
}

class DefaultConversationPort(
    private val hub: ConnectionHub,
    private val conversationRepo: ConversationRepository,
    private val vault: CredentialVault,
) : ConversationPort {

    override val phase: StateFlow<ConnectionPhase> get() = hub.phase

    override suspend fun task(taskId: String): Task? =
        hub.currentTasks().firstOrNull { it.taskId == taskId }

    override fun observeRows(taskId: String): Flow<List<ConversationRow>> =
        conversationRepo.observeRows(com.zcode.android.domain.ids.SessionId(taskId))

    override fun observeEpoch(taskId: String): Flow<String?> =
        conversationRepo.observeEpoch(com.zcode.android.domain.ids.SessionId(taskId))

    override fun observeStale(taskId: String): Flow<Boolean> =
        hub.sessionStale(taskId)

    override fun observeTaskState(taskId: String): Flow<TaskStateMachine.State> =
        hub.sessionTaskState(taskId)

    override fun observeStreaming(taskId: String): Flow<List<StreamingBuffer>> =
        hub.sessionStreaming(taskId)

    /** §19.4 backward pagination: more history above the current tail window (vc11). */
    override fun observeHasMoreHistory(taskId: String): Flow<Boolean> =
        hub.sessionHasMoreHistory(taskId)

    override fun observeTotalRows(taskId: String): Flow<Long?> =
        hub.sessionTotalRows(taskId)

    override fun observeSessionState(taskId: String): Flow<com.zcode.android.domain.model.SessionProjectionState> =
        hub.sessionProjectionState(taskId)

    override suspend fun open(task: Task): Boolean {
        val coordinator = hub.openConversation(task) ?: return false
        // Wait for the first authoritative anchor so first paint is never an empty
        // lie (§19.4 tail-first: the snapshot apply is the paint source).
        return true
    }

    // ---------------------------------------------------------------- Phase 7 write path

    /** §17.4 draft scope — (endpoint, workspace identity, task); endpoint from the vault. */
    private suspend fun draftScope(taskId: String): com.zcode.android.domain.repo.DraftRepository.DraftScope? {
        val endpointId = vault.loadMetadata()?.endpointId ?: return null
        val task = task(taskId) ?: return null
        return com.zcode.android.domain.repo.DraftRepository.DraftScope(
            endpointId = EndpointId(endpointId),
            workspace = task.workspace,
            taskId = taskId,
        )
    }

    override fun observeDraft(taskId: String): Flow<String?> =
        kotlinx.coroutines.flow.channelFlow {
            val scope = draftScope(taskId)
            if (scope == null) {
                kotlinx.coroutines.flow.flowOf<String?>(null).collect { send(it) }
            } else {
                hub.draftRepository().observe(scope).collect { send(it) }
            }
        }

    override suspend fun saveDraft(taskId: String, text: String) {
        draftScope(taskId)?.let { hub.draftRepository().save(it, text, System.currentTimeMillis()) }
    }

    override suspend fun clearDraft(taskId: String) {
        draftScope(taskId)?.let { hub.draftRepository().clear(it) }
    }

    override suspend fun sendPrompt(taskId: String, text: String, heldQueueDisposition: String?): ConversationPort.SendOutcome {
        val coordinator = hub.commandCoordinatorOrNull() ?: return ConversationPort.SendOutcome.FAILED
        return when (val outcome = coordinator.sendPrompt(taskId, text, heldQueueDisposition = heldQueueDisposition)) {
            is com.zcode.android.data.session.CommandCoordinator.Outcome.Accepted -> ConversationPort.SendOutcome.ACCEPTED
            is com.zcode.android.data.session.CommandCoordinator.Outcome.BlockedOffline -> ConversationPort.SendOutcome.BLOCKED_OFFLINE
            is com.zcode.android.data.session.CommandCoordinator.Outcome.Stale -> ConversationPort.SendOutcome.STALE
            is com.zcode.android.data.session.CommandCoordinator.Outcome.Failed -> ConversationPort.SendOutcome.FAILED
            is com.zcode.android.data.session.CommandCoordinator.Outcome.UnknownKept -> ConversationPort.SendOutcome.UNKNOWN_KEPT
            is com.zcode.android.data.session.CommandCoordinator.Outcome.MissingBaseRevision -> ConversationPort.SendOutcome.FAILED
        }
    }

    override fun observePendingSends(taskId: String): Flow<List<ConversationPort.PendingSendView>> =
        kotlinx.coroutines.flow.channelFlow {
            val coordinator = hub.commandCoordinatorOrNull()
            if (coordinator == null) {
                kotlinx.coroutines.flow.flowOf(emptyList<com.zcode.android.data.session.CommandCoordinator.PendingSend>()).collect { list ->
                    send(list.map { ConversationPort.PendingSendView(it.commandId.raw, it.text, it.state.name, it.atMs) })
                }
            } else {
                coordinator.pendingSends.collect { list ->
                    send(
                        list.filter { it.sessionId == taskId }
                            .map { ConversationPort.PendingSendView(it.commandId.raw, it.text, it.state.name, it.atMs) },
                    )
                }
            }
        }

    override suspend fun retrySend(commandId: String): ConversationPort.SendOutcome {
        val coordinator = hub.commandCoordinatorOrNull() ?: return ConversationPort.SendOutcome.FAILED
        return mapOutcome(coordinator.retry(com.zcode.android.domain.ids.CommandId(commandId)))
    }

    override suspend fun stopGeneration(taskId: String): ConversationPort.SendOutcome {
        val coordinator = hub.commandCoordinatorOrNull() ?: return ConversationPort.SendOutcome.FAILED
        return mapOutcome(coordinator.stopGeneration(taskId))
    }

    override suspend fun cancelBackgroundWork(taskId: String, workId: String): ConversationPort.SendOutcome {
        val coordinator = hub.commandCoordinatorOrNull() ?: return ConversationPort.SendOutcome.FAILED
        return mapOutcome(coordinator.cancelBackgroundWork(taskId, workId))
    }

    override suspend fun sendQueuedNow(taskId: String, queueItemId: String): ConversationPort.SendOutcome =
        queueOp(taskId) { revision, coordinator ->
            coordinator.sendQueuedNow(taskId, queueItemId, baseRevision = revision)
        }

    override suspend fun editQueueItem(taskId: String, queueItemId: String, newText: String): ConversationPort.SendOutcome =
        queueOp(taskId) { revision, coordinator ->
            coordinator.editQueueItem(taskId, queueItemId, newText, baseRevision = revision)
        }

    /** CAS currency (baseRevision) comes from the session projection's latest revision. */
    private suspend fun queueOp(
        taskId: String,
        op: suspend (Long?, com.zcode.android.data.session.CommandCoordinator) -> com.zcode.android.data.session.CommandCoordinator.Outcome,
    ): ConversationPort.SendOutcome {
        val coordinator = hub.commandCoordinatorOrNull() ?: return ConversationPort.SendOutcome.FAILED
        val revision = hub.sessionProjectionState(taskId).first().revision
        return mapOutcome(op(revision, coordinator))
    }

    private fun mapOutcome(outcome: com.zcode.android.data.session.CommandCoordinator.Outcome): ConversationPort.SendOutcome =
        when (outcome) {
            is com.zcode.android.data.session.CommandCoordinator.Outcome.Accepted -> ConversationPort.SendOutcome.ACCEPTED
            is com.zcode.android.data.session.CommandCoordinator.Outcome.BlockedOffline -> ConversationPort.SendOutcome.BLOCKED_OFFLINE
            is com.zcode.android.data.session.CommandCoordinator.Outcome.Stale -> ConversationPort.SendOutcome.STALE
            is com.zcode.android.data.session.CommandCoordinator.Outcome.Failed -> ConversationPort.SendOutcome.FAILED
            is com.zcode.android.data.session.CommandCoordinator.Outcome.UnknownKept -> ConversationPort.SendOutcome.UNKNOWN_KEPT
            is com.zcode.android.data.session.CommandCoordinator.Outcome.MissingBaseRevision -> ConversationPort.SendOutcome.FAILED
        }

    override suspend fun answerInteraction(
        interactionId: String,
        optionId: String,
        freeText: String?,
        userInput: Boolean,
    ): ConversationPort.AnswerOutcome {
        val coordinator = hub.interactionCoordinatorOrNull() ?: return ConversationPort.AnswerOutcome.UNKNOWN_INTERACTION
        return mapAnswer(
            coordinator.submit(
                com.zcode.android.domain.ids.InteractionId(interactionId),
                optionId,
                freeText,
                if (userInput) com.zcode.android.data.session.InteractionCoordinator.AnswerKind.USER_INPUT
                else com.zcode.android.data.session.InteractionCoordinator.AnswerKind.PERMISSION,
            ),
        )
    }

    override fun observeOpenInteractions(): Flow<List<ConversationPort.InteractionSummary>> =
        kotlinx.coroutines.flow.channelFlow {
            val endpointId = vault.loadMetadata()?.endpointId
            if (endpointId == null) {
                kotlinx.coroutines.flow.flowOf(emptyList<com.zcode.android.domain.repo.InteractionRepository.InteractionRecord>()).collect {
                    send(emptyList())
                }
            } else {
                hub.interactionsStore().observeOpen(EndpointId(endpointId)).collect { list ->
                    send(
                        list.map {
                            ConversationPort.InteractionSummary(
                                interactionId = it.interactionId.raw,
                                sessionId = it.sessionId?.raw,
                                kind = it.kind.name,
                                lifecycle = it.lifecycle.name,
                                toolName = it.toolName,
                                createdAtMs = it.createdAtMs,
                            )
                        },
                    )
                }
            }
        }

    override suspend fun answerFromInbox(interactionId: String, optionId: String, freeText: String?): ConversationPort.AnswerOutcome {
        val coordinator = hub.interactionCoordinatorOrNull() ?: return ConversationPort.AnswerOutcome.UNKNOWN_INTERACTION
        return mapAnswer(
            coordinator.submitFromStore(
                com.zcode.android.domain.ids.InteractionId(interactionId),
                optionId,
                freeText,
            ),
        )
    }

    private fun mapAnswer(outcome: com.zcode.android.data.session.InteractionCoordinator.SubmitOutcome): ConversationPort.AnswerOutcome =
        when (outcome) {
            is com.zcode.android.data.session.InteractionCoordinator.SubmitOutcome.Submitted -> ConversationPort.AnswerOutcome.SUBMITTED
            is com.zcode.android.data.session.InteractionCoordinator.SubmitOutcome.RetryableKept -> ConversationPort.AnswerOutcome.RETRYABLE_KEPT
            is com.zcode.android.data.session.InteractionCoordinator.SubmitOutcome.InertOption -> ConversationPort.AnswerOutcome.INERT_OPTION
            is com.zcode.android.data.session.InteractionCoordinator.SubmitOutcome.RefineRequiresText -> ConversationPort.AnswerOutcome.REFINE_REQUIRES_TEXT
            is com.zcode.android.data.session.InteractionCoordinator.SubmitOutcome.NotApprovable -> ConversationPort.AnswerOutcome.NOT_APPROVABLE
            is com.zcode.android.data.session.InteractionCoordinator.SubmitOutcome.DuplicateNoOp -> ConversationPort.AnswerOutcome.DUPLICATE_NO_OP
            is com.zcode.android.data.session.InteractionCoordinator.SubmitOutcome.UnknownInteraction -> ConversationPort.AnswerOutcome.UNKNOWN_INTERACTION
        }

    /**
     * §19.4 scroll-back: `rowsRange` (stateless, re-send-safe, ≤ 200 rows verified)
     * → page cache through the SAME epoch-guarded store apply path as live frames
     * (§15-S9 interplay: an epoch move rejects the page with zero mutation).
     */
    override suspend fun loadOlder(
        taskId: String,
        beforeRowId: Long,
    ): ConversationPort.LoadOlderResult {
        return try {
            if (hub.coordinatorOrNull() == null) {
                return ConversationPort.LoadOlderResult(0, hasMore = false, error = null, epochRejected = false)
            }
            val page = hub.gateway.rowsRange(
                sessionId = taskId,
                beforeRowId = beforeRowId,
                limit = ConversationCacheBounds.PAGE_DEFAULT_ROWS,
            )
            val result = conversationRepo.apply(
                ConversationStoreOp.PageFetched(
                    sessionId = com.zcode.android.domain.ids.SessionId(taskId),
                    logEpoch = page.atLogEpoch,
                    rows = page.rows,
                ),
            )
            when (result) {
                is com.zcode.android.domain.repo.StoreApplyResult.EpochRejected -> {
                    // FIELD (2026-09-30, vc16): epoch rejections were SILENT — the export
                    // could never distinguish "rejected" from "never fetched". Surface
                    // both epochs into the ring.
                    val storeEpoch = conversationRepo.currentEpoch(com.zcode.android.domain.ids.SessionId(taskId))
                    hub.log.warn(
                        "port.pageEpochRejected",
                        "session=$taskId storeEpoch=$storeEpoch pageEpoch=${page.atLogEpoch} rows=${page.rows.size}",
                    )
                    ConversationPort.LoadOlderResult(0, page.hasMore, error = null, epochRejected = true)
                }
                else -> ConversationPort.LoadOlderResult(page.rows.size, page.hasMore, error = null, epochRejected = false)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val error = (e as? com.zcode.android.core.transport.RelayTransport.Failure)?.error
                ?: com.zcode.android.core.transport.ErrorMapping.timeout("rowsRange", 10_000)
            hub.log.warn("port.pageFault", "session=$taskId ${e.javaClass.simpleName}: ${e.message?.take(120)}")
            ConversationPort.LoadOlderResult(0, hasMore = true, error = error, epochRejected = false)
        }
    }
}
