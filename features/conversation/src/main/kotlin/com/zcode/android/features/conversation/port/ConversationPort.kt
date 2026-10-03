package com.zcode.android.features.conversation.port

import com.zcode.android.core.common.error.AppError
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.domain.model.StreamingBuffer
import com.zcode.android.domain.model.Task
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.domain.state.TaskStateMachine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Consumer-side port for the conversation surface (INV-2/INV-6: no transport types).
 * Phase 6 was READ/STREAM only; Phase 7 (§43.2) adds the SAFE WRITE PATH: composer
 * drafts, the outbox send path, interaction answers, stop/cancel, queue management.
 * Every command returns a typed outcome — no silent queuing, no guessed success
 * (§15.5/§17.6).
 */
interface ConversationPort {
    /** App-level connection phase (§14.4.1 UI mirror) for the status line. */
    val phase: StateFlow<ConnectionPhase>

    suspend fun task(taskId: String): Task?

    fun observeRows(taskId: String): Flow<List<ConversationRow>>

    fun observeEpoch(taskId: String): Flow<String?>

    /** §14.4.2 Disconnected overlay (labeled staleness). */
    fun observeStale(taskId: String): Flow<Boolean>

    fun observeTaskState(taskId: String): Flow<TaskStateMachine.State>

    /** Reducer-held in-memory streaming buffers; consumers MUST coalesce (§19.2). */
    fun observeStreaming(taskId: String): Flow<List<StreamingBuffer>>

    /** More history above the current tail window (drives "load earlier"). */
    fun observeHasMoreHistory(taskId: String): Flow<Boolean>

    /** Total rows in the session (window metadata; null when unknown). */
    fun observeTotalRows(taskId: String): Flow<Long?>

    /** Phase 7 A-side projection: control/queue/pendingInteractions/inputRouting. */
    fun observeSessionState(taskId: String): Flow<com.zcode.android.domain.model.SessionProjectionState>

    /** Subscribe (Phase 5 coordinator path; journal restore included). */
    suspend fun open(task: Task): Boolean

    /**
     * §19.4 scroll-back: rowsRange → page cache. `epochRejected=true` = §15-S9
     * epoch move — the UI shows the "history changed" notice and re-anchors to tail.
     */
    suspend fun loadOlder(taskId: String, beforeRowId: Long): LoadOlderResult

    data class LoadOlderResult(
        val rowsAdded: Int,
        val hasMore: Boolean,
        val error: AppError?,
        val epochRejected: Boolean,
    )

    // ---------------------------------------------------------------- Phase 7 write path

    /** §17.4 drafts: per (endpoint, workspace, task), Room-backed, debounced by the caller. */
    fun observeDraft(taskId: String): Flow<String?>
    suspend fun saveDraft(taskId: String, text: String)
    suspend fun clearDraft(taskId: String)

    /** §17.5 send path (INV-4 idempotent, §17.6 offline-blocked). */
    suspend fun sendPrompt(taskId: String, text: String, heldQueueDisposition: String? = null): SendOutcome

    enum class SendOutcome { ACCEPTED, BLOCKED_OFFLINE, STALE, FAILED, UNKNOWN_KEPT }

    /** Truthful pending state (§14.2 overlay; in-memory, reconciled by the ViewModel). */
    fun observePendingSends(taskId: String): Flow<List<PendingSendView>>

    data class PendingSendView(val commandId: String, val text: String, val state: String, val atMs: Long)

    /** User-gated retry of an UNKNOWN/failed send — SAME commandId (INV-4). */
    suspend fun retrySend(commandId: String): SendOutcome

    /** §7.5 stop control (SAFE_AGGRESSIVE retry class, §28.6). */
    suspend fun stopGeneration(taskId: String): SendOutcome

    /** §7.5 cancelBackgroundWork (SAFE_AGGRESSIVE). */
    suspend fun cancelBackgroundWork(taskId: String, workId: String): SendOutcome

    /** §7.5 queue edits — CAS-guarded (verified set; baseRevision from the projection). */
    suspend fun sendQueuedNow(taskId: String, queueItemId: String): SendOutcome
    suspend fun editQueueItem(taskId: String, queueItemId: String, newText: String): SendOutcome

    /** §20 interaction answer (permission or user-input) through the ONE fail-closed gate. */
    suspend fun answerInteraction(
        interactionId: String,
        optionId: String,
        freeText: String?,
        userInput: Boolean,
    ): AnswerOutcome

    enum class AnswerOutcome { SUBMITTED, RETRYABLE_KEPT, INERT_OPTION, REFINE_REQUIRES_TEXT, NOT_APPROVABLE, DUPLICATE_NO_OP, UNKNOWN_INTERACTION }

    /** Approvals inbox source (§18.3): open interactions across tasks. */
    fun observeOpenInteractions(): Flow<List<InteractionSummary>>

    data class InteractionSummary(
        val interactionId: String,
        val sessionId: String?,
        val kind: String,
        val lifecycle: String,
        val toolName: String?,
        val createdAtMs: Long,
    )

    /** One-tap approve from the inbox (hydrates the card from the store — §18.3). */
    suspend fun answerFromInbox(interactionId: String, optionId: String, freeText: String?): AnswerOutcome

    /**
     * Phase 7 field: answer a question-type user-input interaction (AskUserQuestion).
     * answers: question text → the selected option values (+ optional custom answer).
     */
    suspend fun answerUserInputQuestions(
        interactionId: String,
        answers: Map<String, List<String>>,
    ): AnswerOutcome

    // ---------------------------------------------------------------- Phase 9 (§43.2-9)

    /**
     * §22 diff source: per-turn pre-parsed hunks (`conversationFileChangesV4`)
     * for the diff viewer. Fails closed as [TurnChangesResult] with a typed error
     * — the viewer renders the failure state (INV-11), never a guessed empty diff.
     */
    suspend fun fetchTurnFileChanges(taskId: String, rowId: Long, entityId: String): TurnChangesResult

    data class TurnChangesResult(
        val changes: com.zcode.android.domain.model.TurnFileChanges?,
        val error: String?,
    )

    /** §28.6 CAS-guarded model switch (echo-reconciled via the `config` projection). */
    suspend fun switchModel(taskId: String, provider: String, model: String, thought: String): SendOutcome

    /** §28.6 CAS-guarded collaboration-mode switch (build|edit|plan|yolo only). */
    suspend fun switchMode(taskId: String, mode: String): SendOutcome

    /**
     * §23.2 composer attachment upload — full transaction (begin→chunks→commit)
     * with per-chunk progress. Cancel = abort (removeUpload). Client-side gates:
     * 20 MiB cap + MIME grammar (fail closed pre-wire).
     */
    suspend fun uploadAttachment(
        taskId: String,
        fileName: String,
        mime: String,
        bytes: ByteArray,
        uploadId: String,
        onProgress: (sent: Int, total: Int) -> Unit,
    ): UploadResult

    sealed class UploadResult {
        /** Committed — [ref] rides the sendText payload's `attachments` array. */
        data class Committed(val ref: String, val bytes: Long) : UploadResult()
        data object TooLarge : UploadResult()
        data object BlockedOffline : UploadResult()
        data class Failed(val message: String?) : UploadResult()
    }

    /** Cancel/remove a pending upload (abort transaction + forget its bytes). */
    suspend fun removeUpload(uploadId: String)

    /**
     * §23.3 chunked attachment read (image/video/pdf) → local cache file.
     * Returns the file + media type; null error = success.
     */
    suspend fun fetchAttachment(
        taskId: String,
        ref: String,
        targetRowId: Long?,
        targetEntityId: String?,
        attachmentIndex: Int?,
    ): AttachmentFetchResult

    data class AttachmentFetchResult(
        val file: java.io.File?,
        val mediaType: String?,
        val totalBytes: Long,
        val error: String?,
    )

    /** Pending composer attachments (per task, in-memory — §14.2 Android-owned). */
    fun observeComposerAttachments(taskId: String): Flow<List<ComposerAttachment>>

    data class ComposerAttachment(
        val uploadId: String,
        val fileName: String,
        val mime: String,
        val bytes: Long,
        val state: String,
        val progress: Float,
        val ref: String?,
    )
}
