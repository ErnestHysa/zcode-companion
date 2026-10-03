package com.zcode.android.app.connect

import com.zcode.android.app.pairing.wsUrlForOrigin
import com.zcode.android.core.common.error.AppError
import com.zcode.android.core.security.vault.CredentialVault
import com.zcode.android.domain.ids.WorkspaceIdentityKey
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
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
    /** Attachment payload cache (§23.3: app cache, LRU-managed by the caller). */
    private val attachmentCacheDir: java.io.File,
) : ConversationPort {

    override val phase: StateFlow<ConnectionPhase> get() = hub.phase

    /**
     * FIELD (2026-10-02, vc25): the workspace of every opened task, remembered at
     * open() — draft scope resolution MUST NOT depend on the live task projection.
     * The old path re-resolved the Task on every save/observe and silently no-oped
     * whenever the projection was momentarily unresolvable (E8: a draft saved into
     * the void). The workspace is known at open time and cannot change for a task
     * (§7.11 identity dedupe).
     */
    private val workspaceByTask = java.util.concurrent.ConcurrentHashMap<String, WorkspaceIdentityKey>()

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
        workspaceByTask[task.taskId] = task.workspace
        val coordinator = hub.openConversation(task) ?: return false
        // Wait for the first authoritative anchor so first paint is never an empty
        // lie (§19.4 tail-first: the snapshot apply is the paint source).
        return true
    }

    // ---------------------------------------------------------------- Phase 7 write path

    /** §17.4 draft scope — (endpoint, workspace identity, task); endpoint from the vault. */
    private suspend fun draftScope(taskId: String): com.zcode.android.domain.repo.DraftRepository.DraftScope? {
        val endpointId = vault.loadMetadata()?.endpointId ?: return null
        // Workspace from the open()-time record FIRST — the live projection lookup is
        // only a fallback (and can transiently fail under load: E8 silent no-op).
        val workspace = workspaceByTask[taskId]
            ?: task(taskId)?.workspace
            ?: return null
        return com.zcode.android.domain.repo.DraftRepository.DraftScope(
            endpointId = EndpointId(endpointId),
            workspace = workspace,
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
        val scope = draftScope(taskId)
        scope?.let { hub.draftRepository().save(it, text, System.currentTimeMillis()) }
    }

    override suspend fun clearDraft(taskId: String) {
        val scope = draftScope(taskId)
        scope?.let { hub.draftRepository().clear(it) }
    }

    override suspend fun sendPrompt(taskId: String, text: String, heldQueueDisposition: String?): ConversationPort.SendOutcome {
        val coordinator = hub.commandCoordinatorOrNull() ?: return ConversationPort.SendOutcome.FAILED
        // §23.2: ready (committed) composer uploads ride the sendText payload as
        // attachment refs; they are consumed on ACCEPTED (§14.2 — the overlay drops).
        val ready = composerAttachments.value[taskId].orEmpty().filter { it.state == "ready" && it.ref != null }
        val outcome = coordinator.sendPrompt(
            taskId,
            text,
            heldQueueDisposition = heldQueueDisposition,
            attachments = ready.map {
                com.zcode.android.data.session.CommandCoordinator.AttachmentRefPayload(
                    ref = it.ref!!, fileName = it.fileName, mime = it.mime, bytes = it.bytes,
                )
            },
        )
        return when (outcome) {
            is com.zcode.android.data.session.CommandCoordinator.Outcome.Accepted -> {
                if (ready.isNotEmpty()) {
                    composerAttachments.value = composerAttachments.value +
                        (taskId to composerAttachments.value[taskId].orEmpty().filterNot { a -> ready.any { r -> r.uploadId == a.uploadId } })
                }
                ConversationPort.SendOutcome.ACCEPTED
            }
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

    override suspend fun answerUserInputQuestions(
        interactionId: String,
        answers: Map<String, List<String>>,
    ): ConversationPort.AnswerOutcome {
        val coordinator = hub.interactionCoordinatorOrNull() ?: return ConversationPort.AnswerOutcome.UNKNOWN_INTERACTION
        return mapAnswer(
            coordinator.submitElicitationAnswers(
                com.zcode.android.domain.ids.InteractionId(interactionId),
                answers,
            ),
        )
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
            val page = hub.gateway().rowsRange(
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
            val error = (e as? com.zcode.android.core.transport.TransportFailure)?.error
                ?: com.zcode.android.core.transport.ErrorMapping.timeout("rowsRange", 10_000)
            hub.log.warn("port.pageFault", "session=$taskId ${e.javaClass.simpleName}: ${e.message?.take(120)}")
            ConversationPort.LoadOlderResult(0, hasMore = true, error = error, epochRejected = false)
        }
    }

    // ---------------------------------------------------------------- Phase 9 (§43.2-9)

    /**
     * CAS currency snapshot for a session: (revision, logEpoch) — fileChanges and
     * the switch commands carry both (§10.1/§10.3). Null revision = projection not
     * ready → caller surfaces a typed not-ready failure (never a guessed base).
     */
    private suspend fun casCurrency(taskId: String): Pair<Long, String>? {
        val state = hub.sessionProjectionState(taskId).firstOrNull()
        val revision = state?.revision ?: return null
        val epoch = conversationRepo.currentEpoch(com.zcode.android.domain.ids.SessionId(taskId)) ?: return null
        return revision to epoch
    }

    override suspend fun fetchTurnFileChanges(
        taskId: String,
        rowId: Long,
        entityId: String,
    ): ConversationPort.TurnChangesResult {
        val currency = casCurrency(taskId)
            ?: return ConversationPort.TurnChangesResult(null, "conversation state not ready — open the task and retry")
        val (workspacePath, workspaceIdentity) = hub.activeWorkspaceTarget() ?: (null to null)
        return try {
            val changes = hub.phase9().turnFileChanges(
                sessionId = taskId,
                targetRowId = rowId,
                targetEntityId = entityId,
                baseRevision = currency.first,
                baseLogEpoch = currency.second,
                workspacePath = workspacePath,
                workspaceIdentity = workspaceIdentity,
            )
            ConversationPort.TurnChangesResult(changes, null)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            hub.log.warn("port.fileChanges", "session=$taskId ${e.javaClass.simpleName}: ${e.message?.take(120)}")
            ConversationPort.TurnChangesResult(null, e.message ?: "file changes unavailable")
        }
    }

    override suspend fun switchModel(
        taskId: String,
        provider: String,
        model: String,
        thought: String,
    ): ConversationPort.SendOutcome {
        val coordinator = hub.commandCoordinatorOrNull() ?: return ConversationPort.SendOutcome.FAILED
        val currency = casCurrency(taskId)
        val outcome = coordinator.switchModelConfig(taskId, provider, model, thought, currency?.first)
        return mapOutcome(outcome).also { r ->
            if (r == ConversationPort.SendOutcome.ACCEPTED) hub.log.info("port.switchModel", "session=$taskId model=$model")
        }
    }

    override suspend fun switchMode(taskId: String, mode: String): ConversationPort.SendOutcome {
        val coordinator = hub.commandCoordinatorOrNull() ?: return ConversationPort.SendOutcome.FAILED
        val currency = casCurrency(taskId)
        return mapOutcome(coordinator.switchCollaborationMode(taskId, mode, currency?.first))
    }

    // ---- composer attachments (§23.2) — in-memory per-task overlay (Android-owned, §14.2)

    private val composerAttachments = MutableStateFlow<Map<String, List<ConversationPort.ComposerAttachment>>>(emptyMap())

    override fun observeComposerAttachments(taskId: String): Flow<List<ConversationPort.ComposerAttachment>> =
        composerAttachments.map { it[taskId] ?: emptyList() }

    private fun upsertAttachment(taskId: String, att: ConversationPort.ComposerAttachment) {
        val current = composerAttachments.value[taskId] ?: emptyList()
        composerAttachments.value = composerAttachments.value +
            (taskId to (current.filterNot { it.uploadId == att.uploadId } + att))
    }

    override suspend fun uploadAttachment(
        taskId: String,
        fileName: String,
        mime: String,
        bytes: ByteArray,
        uploadId: String,
        onProgress: (sent: Int, total: Int) -> Unit,
    ): ConversationPort.UploadResult {
        if (bytes.size > com.zcode.android.core.protocol.v4.AttachmentLimits.MAX_BYTES) {
            return ConversationPort.UploadResult.TooLarge
        }
        val tools = hub.phase9()
        val (workspacePath, workspaceIdentity) = hub.activeWorkspaceTarget() ?: (null to null)
        upsertAttachment(
            taskId,
            ConversationPort.ComposerAttachment(uploadId, fileName, mime, bytes.size.toLong(), "uploading", 0f, null),
        )
        // §23.2: checksum over the WHOLE payload (wire grammar sha256:<64 hex>).
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        val checksum = "sha256:" + digest.joinToString("") { "%02x".format(it) }
        val totalChunks = if (bytes.isEmpty()) 0 else (bytes.size + com.zcode.android.core.protocol.v4.AttachmentLimits.CHUNK_MAX_BYTES - 1) /
            com.zcode.android.core.protocol.v4.AttachmentLimits.CHUNK_MAX_BYTES
        return try {
            when (val begin = tools.attachmentBegin(uploadId, taskId, fileName, mime, bytes.size.toLong(), checksum, workspacePath, workspaceIdentity)) {
                is com.zcode.android.core.protocol.v4.V4Attachment.BeginResult.Committed -> {
                    upsertAttachment(taskId, ConversationPort.ComposerAttachment(uploadId, fileName, mime, bytes.size.toLong(), "ready", 1f, begin.ref))
                    ConversationPort.UploadResult.Committed(begin.ref, bytes.size.toLong())
                }
                is com.zcode.android.core.protocol.v4.V4Attachment.BeginResult.Staging -> {
                    var index = begin.nextChunkIndex
                    var offset: Long = index * com.zcode.android.core.protocol.v4.AttachmentLimits.CHUNK_MAX_BYTES.toLong()
                    while (offset < bytes.size) {
                        val end = minOf(offset + com.zcode.android.core.protocol.v4.AttachmentLimits.CHUNK_MAX_BYTES, bytes.size.toLong()).toInt()
                        tools.attachmentChunk(uploadId, taskId, index, bytes.copyOfRange(offset.toInt(), end), workspacePath, workspaceIdentity)
                        index += 1
                        offset = index * com.zcode.android.core.protocol.v4.AttachmentLimits.CHUNK_MAX_BYTES.toLong()
                        onProgress(index, totalChunks)
                        upsertAttachment(
                            taskId,
                            ConversationPort.ComposerAttachment(uploadId, fileName, mime, bytes.size.toLong(), "uploading", index.toFloat() / totalChunks, null),
                        )
                    }
                    val ref = tools.attachmentCommit(uploadId, taskId)
                    upsertAttachment(taskId, ConversationPort.ComposerAttachment(uploadId, fileName, mime, bytes.size.toLong(), "ready", 1f, ref))
                    ConversationPort.UploadResult.Committed(ref, bytes.size.toLong())
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            runCatching { tools.attachmentAbort(uploadId, taskId) }
            throw e
        } catch (e: com.zcode.android.data.session.Phase9Tools.AttachmentRejectedException) {
            upsertAttachment(taskId, ConversationPort.ComposerAttachment(uploadId, fileName, mime, bytes.size.toLong(), "failed", 0f, null))
            ConversationPort.UploadResult.Failed(e.message)
        } catch (e: Exception) {
            if (hub.phase.value != ConnectionPhase.CONNECTED) {
                upsertAttachment(taskId, ConversationPort.ComposerAttachment(uploadId, fileName, mime, bytes.size.toLong(), "failed", 0f, null))
                return ConversationPort.UploadResult.BlockedOffline
            }
            hub.log.warn("port.upload", "task=$taskId ${e.javaClass.simpleName}: ${e.message?.take(120)}")
            runCatching { tools.attachmentAbort(uploadId, taskId) }
            upsertAttachment(taskId, ConversationPort.ComposerAttachment(uploadId, fileName, mime, bytes.size.toLong(), "failed", 0f, null))
            ConversationPort.UploadResult.Failed(e.message)
        }
    }

    override suspend fun removeUpload(uploadId: String) {
        // Find the owning task + abort the transaction if it was live.
        val entry = composerAttachments.value.entries.firstOrNull { e -> e.value.any { it.uploadId == uploadId } }
        val att = entry?.value?.firstOrNull { it.uploadId == uploadId }
        if (entry != null && att != null && att.state == "uploading") {
            runCatching { hub.phase9().attachmentAbort(uploadId, entry.key) }
        }
        if (entry != null) {
            composerAttachments.value = composerAttachments.value +
                (entry.key to entry.value.filterNot { it.uploadId == uploadId })
        }
    }

    /** Clear a task's ready attachments after a successful send (consumed refs). */
    fun clearComposerAttachments(taskId: String) {
        composerAttachments.value = composerAttachments.value - taskId
    }

    override suspend fun fetchAttachment(
        taskId: String,
        ref: String,
        targetRowId: Long?,
        targetEntityId: String?,
        attachmentIndex: Int?,
    ): ConversationPort.AttachmentFetchResult {
        val (workspacePath, workspaceIdentity) = hub.activeWorkspaceTarget() ?: (null to null)
        val safeRef = ref.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val out = java.io.File(attachmentCacheDir, "$taskId-$safeRef.bin")
        return try {
            val (mediaType, totalBytes) = hub.phase9().attachmentReadAll(
                sessionId = taskId,
                ref = ref,
                targetRowId = targetRowId,
                targetEntityId = targetEntityId,
                attachmentIndex = attachmentIndex,
                sink = { chunk ->
                    if (!out.exists()) out.writeBytes(chunk) else out.appendBytes(chunk)
                },
                workspacePath = workspacePath,
                workspaceIdentity = workspaceIdentity,
            )
            ConversationPort.AttachmentFetchResult(out, mediaType, totalBytes, null)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            hub.log.warn("port.attachmentFetch", "task=$taskId ${e.javaClass.simpleName}: ${e.message?.take(120)}")
            out.delete()
            ConversationPort.AttachmentFetchResult(null, null, 0, e.message ?: "attachment unavailable")
        }
    }
}
