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

    /**
     * Phase 2 (§11.2, GAP-12): the workspace slash-command catalog from the
     * `workspace-config/<workspaceKey>` topic — builtin + custom entries verbatim
     * (`source` null = legacy payload). Empty list = no catalog arrived (the menu
     * renders nothing — the desktop's EMPTY_SLASH_COMMANDS parity, no fallback).
     */
    fun observeSlashCommands(taskId: String): Flow<List<SlashCommandView>>

    data class SlashCommandView(
        val name: String,
        val description: String,
        val inputHint: String?,
        val source: String?,
    )

    // ---------------------------------------------------------------- Phase 4C (C2): the other two slash sections

    data class SlashSkillView(val name: String, val description: String, val scope: String)
    data class SlashAgentView(val name: String, val description: String, val scope: String?)

    /**
     * The desktop slash panel's extra sections (slashCommandPanelSections.tsx): the
     * user's custom + plugin commands (`commands.list`), skills (the reference catalog)
     * and subagents (`subagents.list`). Each leg carries its OWN error so one failing
     * channel never blanks the others (desktop: per-section loading/error states).
     * null lists = not loaded; [loading] = fetch in flight.
     */
    data class SlashExtras(
        val userCommands: List<SlashCommandView>,
        val skills: List<SlashSkillView>?,
        val skillsError: String?,
        val agents: List<SlashAgentView>?,
        val agentsError: String?,
        val loading: Boolean,
    )

    /** Lazy (on first "/"): one fetch per session (desktop useSkills enabled-on-trigger). */
    suspend fun fetchSlashExtras(taskId: String): SlashExtras =
        SlashExtras(emptyList(), null, null, null, null, loading = false)

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

    /** Phase 2 (§11.3): the slash command plane — /compact, /goal send + resume. */
    suspend fun compact(taskId: String): SendOutcome
    suspend fun sendGoal(taskId: String, objective: String, displayText: String): SendOutcome
    suspend fun resumeGoal(taskId: String): SendOutcome

    /**
     * Phase 2 (§11.4, GAP-10): editUserQuery — row-targeted + CAS-guarded (both
     * currencies come from the projection/epoch); the turn replacement arrives as
     * authoritative churn, never an optimistic local edit.
     */
    suspend fun editUserQuery(taskId: String, rowId: Long, entityId: String, newText: String): SendOutcome

    // ---------------------------------------------------------------- Phase 4A row actions (E3.2+I1+N5)

    /**
     * The four row commands the protocol layer modeled with zero senders — all
     * row-targeted + CAS-guarded (both currencies from the projection/epoch, the
     * editUserQuery pattern). Visibility is gated by the ROW'S OWN flags
     * (`actions.canRetry/canFork/canRewindFiles`) — the wire decides, never the phone.
     */
    suspend fun retryTurn(taskId: String, rowId: Long, entityId: String): SendOutcome = SendOutcome.FAILED

    /** forkAssistant: an accepted ack names the CHILD session (the desktop navigates to it). */
    suspend fun forkAssistant(taskId: String, rowId: Long, entityId: String): ForkOutcome = ForkOutcome(SendOutcome.FAILED, null)

    data class ForkOutcome(val outcome: SendOutcome, val childSessionId: String?)

    /** applyFileRewind on a turnHeader target — workspace-only undo of that turn's files. */
    suspend fun applyFileRewind(taskId: String, rowId: Long, entityId: String): SendOutcome = SendOutcome.FAILED

    /** setAssistantFeedback: `like` | `dislike` | null (clear). The row's `feedback` echo renders the result. */
    suspend fun setAssistantFeedback(taskId: String, rowId: Long, entityId: String, feedback: String?): SendOutcome = SendOutcome.FAILED

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
        /** K4: the task's title resolved from the task store by sessionId; null = unresolved. */
        val taskTitle: String? = null,
        /** L3: one bounded, redacted preview line from the retained payload; null = none. */
        val preview: String? = null,
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

    /**
     * Phase 3C (C7): lazy FULL tool output — the desktop's tool-calls slice when the
     * facade has a body; null means "no slice" and the caller renders the row's own
     * retained payload (3.14.5.7961 ships the slice as a `return null` stub).
     */
    suspend fun fetchToolCallOutput(taskId: String, toolCallId: String): String? = null

    /**
     * Phase 3D (A3.1): a bounded preview read of the row's own attachment (thumbnails
     * render in-conversation; the phone sent images it could never see again). Null =
     * refused/unavailable (INV-11: the chip still renders).
     */
    suspend fun fetchAttachmentPreview(taskId: String, ref: String): ByteArray? = null

    data class TurnChangesResult(
        val changes: com.zcode.android.domain.model.TurnFileChanges?,
        val error: String?,
    )

    /** §28.6 CAS-guarded model switch (echo-reconciled via the `config` projection). */
    suspend fun switchModel(taskId: String, provider: String, model: String, thought: String): SendOutcome

    /** §28.6 CAS-guarded collaboration-mode switch (build|edit|plan|yolo only). */
    suspend fun switchMode(taskId: String, mode: String): SendOutcome

    /**
     * Phase 4C (A3.4): the reasoning-effort switch — `setThoughtLevel` on the shipped
     * facade; the offered levels come from the wire's `config.thoughtLevels` and the
     * result reconciles through the §10.3 `config` echo.
     */
    suspend fun setThoughtLevel(taskId: String, thought: String): SendOutcome = SendOutcome.FAILED

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

    /** One-tap retry of a FAILED upload (same uploadId, retained payload). */
    suspend fun retryUpload(taskId: String, uploadId: String): UploadResult

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
        /** FIELD (2026-10-04, vc33): retained ONLY while uploading/failed so a
         *  failed chip can RETRY one-tap; nulled on commit/remove (INV-5 bound:
         *  strip is small-count × ≤20 MiB desktop cap). */
        val payload: ByteArray? = null,
    )
}
