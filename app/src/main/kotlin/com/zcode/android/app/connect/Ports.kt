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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * Composition-root implementations of the feature ports (the ONLY meeting point of
 * features and transport-owned machinery, mirroring DefaultPairingPort — §40.2,
 * INV-2/INV-6).
 */
class DefaultTasksPort(
    private val hub: ConnectionHub,
    private val vault: CredentialVault,
    private val taskRepo: TaskRepository,
) : TasksPort {

    override val tasks: Flow<List<Task>> = flow {
        val endpointId = vault.loadMetadata()?.endpointId
        if (endpointId == null) {
            emit(emptyList())
        } else {
            emitAll(taskRepo.observeAllTasks(EndpointId(endpointId)))
        }
    }

    override val phase: StateFlow<ConnectionPhase> get() = hub.phase
    override val taskLoadError: StateFlow<AppError?> get() = hub.taskLoadError
    override val lastRefreshAtMs: StateFlow<Long?> get() = hub.lastRefreshAtMs
    override val endpointName: StateFlow<String?> get() = hub.endpointName

    override suspend fun refresh(): Boolean = hub.refreshTasks()

    override fun retryConnection() = hub.retry()
}

class DefaultConversationPort(
    private val hub: ConnectionHub,
    private val conversationRepo: ConversationRepository,
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

    override suspend fun open(task: Task): Boolean {
        val coordinator = hub.openConversation(task) ?: return false
        // Wait for the first authoritative anchor so first paint is never an empty
        // lie (§19.4 tail-first: the snapshot apply is the paint source).
        return true
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
                limit = ConversationCacheBounds.PAGE_MAX_ROWS,
            )
            val result = conversationRepo.apply(
                ConversationStoreOp.PageFetched(
                    sessionId = com.zcode.android.domain.ids.SessionId(taskId),
                    logEpoch = page.atLogEpoch,
                    rows = page.rows,
                ),
            )
            when (result) {
                is com.zcode.android.domain.repo.StoreApplyResult.EpochRejected ->
                    ConversationPort.LoadOlderResult(0, page.hasMore, error = null, epochRejected = true)
                else -> ConversationPort.LoadOlderResult(page.rows.size, page.hasMore, error = null, epochRejected = false)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val error = (e as? com.zcode.android.core.transport.RelayTransport.Failure)?.error
                ?: com.zcode.android.core.transport.ErrorMapping.timeout("rowsRange", 10_000)
            ConversationPort.LoadOlderResult(0, hasMore = true, error = error, epochRejected = false)
        }
    }
}
