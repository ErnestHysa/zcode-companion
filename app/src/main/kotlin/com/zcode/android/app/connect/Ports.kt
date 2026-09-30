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

    /** §19.4 backward pagination: more history above the current tail window (vc11). */
    override fun observeHasMoreHistory(taskId: String): Flow<Boolean> =
        hub.sessionHasMoreHistory(taskId)

    override fun observeTotalRows(taskId: String): Flow<Long?> =
        hub.sessionTotalRows(taskId)

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
