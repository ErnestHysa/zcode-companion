package com.zcode.android.features.conversation

import androidx.annotation.VisibleForTesting
import com.zcode.android.core.common.error.AppError
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.domain.model.StreamingBuffer
import com.zcode.android.domain.model.Task
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.domain.state.TaskStateMachine
import com.zcode.android.features.conversation.port.ConversationPort
import com.zcode.android.features.conversation.stream.coalesceForUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Conversation screen state (§18.3 row: loading/streaming/offline/error/empty;
 * §19.2 render pipeline). Phase 7 adds the WRITE surface: composer draft, pending
 * sends (§14.2 overlay, reconciled against authoritative rows), the A-side session
 * state (approval cards / user-input / stop / queue / input routing), and the
 * offline/stale block copy (§17.6) — no silent queuing, no guessed success.
 */
data class ConversationUiState(
    val task: Task? = null,
    val rows: List<ConversationRow> = emptyList(),
    /** rowId → streamed text so far (coalesced ≤ ~30 Hz; §19.2). */
    val streaming: Map<Long, String> = emptyMap(),
    val epoch: String? = null,
    val stale: Boolean = false,
    val taskState: TaskStateMachine.State = TaskStateMachine.State.UNKNOWN,
    val phase: ConnectionPhase = ConnectionPhase.UNPAIRED,
    val loading: Boolean = true,
    val hasMoreHistory: Boolean = false,
    /** Total rows in the session (window metadata; null when unknown) — shown in the load-earlier strip. */
    val totalRows: Long? = null,
    val loadingOlder: Boolean = false,
    val loadOlderError: AppError? = null,
    val historyChanged: Boolean = false,
    // ---- Phase 7 write surface ----
    val draft: String = "",
    /** §9 A-side projection (null = never carried by any frame yet). */
    val sessionState: com.zcode.android.domain.model.SessionProjectionState? = null,
    /** §14.2 pending-send overlay, reconciled against authoritative userInput rows. */
    val pendingSends: List<ConversationPort.PendingSendView> = emptyList(),
    /** One-shot user copy for the last blocked/failed/stale send (§17.6/§33). */
    val sendNotice: SendNotice? = null,
    /** Interaction ids with an in-flight submit (double-tap safety at the UI layer). */
    val busyInteractions: Set<String> = emptySet(),
    // ---- Phase 9 (§43.2-9) ----
    /** §23.2 composer uploads (per task, port-held overlay). */
    val composerAttachments: List<ConversationPort.ComposerAttachment> = emptyList(),
    // ---- Phase 2 (§11.2, GAP-12) ----
    /** The workspace slash-command catalog (workspace-config topic; empty = none). */
    val slashCommands: List<ConversationPort.SlashCommandView> = emptyList(),
) {
    val empty: Boolean get() = !loading && rows.isEmpty() && streaming.isEmpty()

    /** Open permission interactions of the authoritative projection (pinned cards). */
    val pendingPermissions: List<com.zcode.android.domain.model.PendingInteraction>
        get() = sessionState?.pendingInteractions
            ?.filter { it.kind == com.zcode.android.domain.model.PendingInteraction.Kind.PERMISSION && it.permission != null }
            ?: emptyList()

    /** Open user-input questions (§20.1 U9 plane). */
    val pendingUserInputs: List<com.zcode.android.domain.model.PendingInteraction>
        get() = sessionState?.pendingInteractions
            ?.filter { it.kind == com.zcode.android.domain.model.PendingInteraction.Kind.USER_INPUT && it.userInput != null }
            ?: emptyList()

    /** Send enabled: connected (or drafting allowed offline — send blocked at §17.6). */
    val connected: Boolean get() = phase == ConnectionPhase.CONNECTED
}

/** §33-mapped user copy for send-path outcomes (never raw exceptions). */
/**
 * A send-outcome notice. [text] is the English fallback; when [key] is set the UI
 * resolves it per locale (C5-i18n — view models hold no Android resources).
 */
data class SendNotice(
    val text: String,
    val kind: Kind,
    val key: Key? = null,
    val args: List<String> = emptyList(),
) {
    enum class Kind { INFO, WARNING, ERROR }

    enum class Key {
        SWITCH_OFFLINE, SESSION_CHANGED, SWITCH_REFUSED, MODEL_SWITCH_REFUSED,
        ATTACHMENT_TOO_LARGE, ATTACHMENT_OFFLINE, ATTACHMENT_FAILED, ATTACHMENT_RETRY_FAILED,
        COMPACT_REFUSED, GOAL_REFUSED, GOAL_RESUME_REFUSED,
    }
}

class ConversationViewModel(
    private val port: ConversationPort,
    private val scope: CoroutineScope,
    private val taskId: String,
) {

    private val _historyChanged = MutableStateFlow(false)
    private val _hasMore = MutableStateFlow(false)
    private val _totalRows = MutableStateFlow<Long?>(null)

    init {
        // FIELD (2026-09-30, vc11): seed §19.4 pagination from the authoritative window
        // metadata (the desktop truncates the initial snapshot — 60-row tail windows over
        // 60k+-seq sessions). Without this the scroll-up prefetch never armed.
        scope.launch {
            port.observeHasMoreHistory(taskId).collect { _hasMore.value = it }
        }
        scope.launch {
            port.observeTotalRows(taskId).collect { _totalRows.value = it }
        }
    }
    private val _loadingOlder = MutableStateFlow(false)
    private val _loadOlderError = MutableStateFlow<AppError?>(null)
    private val _task = MutableStateFlow<Task?>(null)

    /** Raw streaming snapshots, throttled to the render budget (§19.2). */
    @VisibleForTesting
    val streamingCoalesced: StateFlow<Map<Long, String>> = port.observeStreaming(taskId)
        .coalesceForUi(STREAM_FRAME_MS)
        .mapToTextMap()
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private data class RowsEpoch(val rows: List<ConversationRow>, val epoch: String?)
    private data class StaleState(val stale: Boolean, val taskState: TaskStateMachine.State)
    private data class StreamPhase(val streaming: Map<Long, String>, val task: Task?, val phase: ConnectionPhase)
    private data class Flags(
        val hasMore: Boolean,
        val loadingOlder: Boolean,
        val error: AppError?,
        val historyChanged: Boolean,
    )
    private data class WriteState(
        val sessionState: com.zcode.android.domain.model.SessionProjectionState?,
        val pendingSends: List<ConversationPort.PendingSendView>,
        val busy: Set<String>,
        /** FIELD (2026-10-01, vc21 device): the draft is a COMBINE SOURCE — the
         *  TextField is fully state-driven, so live-conversation recompositions can
         *  never re-render it with a stale value mid-typing. */
        val draft: String,
        val composerAttachments: List<ConversationPort.ComposerAttachment>,
    )

    // ---- Phase 7 write state (declared BEFORE the state combine that reads it) ----
    private val _draft = MutableStateFlow("")
    private val _sendNotice = MutableStateFlow<SendNotice?>(null)
    private val _busyInteractions = MutableStateFlow<Set<String>>(emptySet())
    private val _slashCommands = MutableStateFlow<List<ConversationPort.SlashCommandView>>(emptyList())
    private var draftLoaded = false
    private var draftSaveJob: kotlinx.coroutines.Job? = null

    val state: StateFlow<ConversationUiState> = combine(
        combine(port.observeRows(taskId), port.observeEpoch(taskId)) { rows, epoch -> RowsEpoch(rows, epoch) },
        combine(port.observeStale(taskId), port.observeTaskState(taskId)) { s, t -> StaleState(s, t) },
        combine(streamingCoalesced, _task, port.phase) { streaming, task, phase -> StreamPhase(streaming, task, phase) },
        combine(_hasMore, _loadingOlder, _loadOlderError, _historyChanged) { h, lo, err, hc -> Flags(h, lo, err, hc) },
        combine(
            port.observeSessionState(taskId),
            port.observePendingSends(taskId),
            _busyInteractions,
            _draft,
            port.observeComposerAttachments(taskId),
        ) { s, p, b, d, att -> WriteState(s, p, b, d, att) },
    ) { rowsEpoch, staleState, streamPhase, flags, write ->
        // §14.2 overlay reconciliation: a pending send whose authoritative userInput
        // row has landed is DONE — drop it (max lifetime guard renders it degraded).
        val now = System.currentTimeMillis()
        val authoritativeTexts = rowsEpoch.rows.filterIsInstance<ConversationRow.UserInput>().map { it.text }.toSet()
        val livePending = write.pendingSends.filter { p ->
            p.text !in authoritativeTexts && now - p.atMs < PENDING_MAX_LIFETIME_MS
        }
        ConversationUiState(
            task = streamPhase.task,
            rows = rowsEpoch.rows,
            streaming = streamPhase.streaming,
            epoch = rowsEpoch.epoch,
            stale = staleState.stale,
            taskState = staleState.taskState,
            phase = streamPhase.phase,
            loading = rowsEpoch.rows.isEmpty() && streamPhase.streaming.isEmpty() &&
                staleState.taskState == TaskStateMachine.State.UNKNOWN && rowsEpoch.epoch == null,
            hasMoreHistory = flags.hasMore,
            totalRows = _totalRows.value,
            loadingOlder = flags.loadingOlder,
            loadOlderError = flags.error,
            historyChanged = flags.historyChanged,
            draft = write.draft,
            sessionState = write.sessionState,
            pendingSends = livePending,
            sendNotice = _sendNotice.value,
            busyInteractions = write.busy,
            composerAttachments = write.composerAttachments,
            slashCommands = _slashCommands.value,
        )
    }.stateIn(scope, SharingStarted.Eagerly, ConversationUiState())

    init {
        scope.launch {
            val task = port.task(taskId)
            _task.value = task
            if (task != null) port.open(task)
        }
        // Phase 2 (§11.2): the workspace catalog (totalRows precedent — read at
        // combine time; the catalog is conflated latest-wins, a stale read is
        // corrected by the next frame).
        scope.launch {
            port.observeSlashCommands(taskId).collect { _slashCommands.value = it }
        }
    }

    /**
     * §19.2/§19.4 scroll-back: fired by the screen at the top page edge.
     * Prepending shifts indices, so the screen re-anchors by row key after a
     * successful page (the ViewModel returns rowsAdded for that).
     */
    fun loadOlder(beforeRowId: Long, onResult: (rowsAdded: Int) -> Unit = {}) {
        if (_loadingOlder.value) return
        scope.launch {
            _loadingOlder.value = true
            _loadOlderError.value = null
            try {
                val result = port.loadOlder(taskId, beforeRowId)
                if (result.epochRejected) {
                    // §15-S9: the desktop rewound history while we paged — the page was
                    // rejected with zero mutation; re-anchor to the authoritative tail.
                    _historyChanged.value = true
                    _hasMore.value = false
                    onResult(0) // defined contract: zero rows added, no scroll adjust
                } else {
                    _hasMore.value = result.hasMore
                    _loadOlderError.value = result.error
                    onResult(result.rowsAdded)
                }
            } finally {
                _loadingOlder.value = false
            }
        }
    }

    // ---------------------------------------------------------------- Phase 9 (§43.2-9)

    /** §22 diff source — per-turn pre-parsed hunks for the diff viewer. */
    suspend fun turnFileChanges(rowId: Long, entityId: String): ConversationPort.TurnChangesResult =
        port.fetchTurnFileChanges(taskId, rowId, entityId)

    /** §28.6 echo-reconciled switches (the `config` projection is the truth).
     *  FIELD (2026-10-04, vc33): the outcome was fire-and-forgotten — a rejected/
     *  stale/no-op switch looked exactly like a successful one. Non-acceptance is
     *  now surfaced (§33 copy); acceptance reflects via the §14.2 port overlay. */
    fun switchMode(mode: String) {
        scope.launch {
            when (port.switchMode(taskId, mode)) {
                ConversationPort.SendOutcome.ACCEPTED -> Unit
                ConversationPort.SendOutcome.BLOCKED_OFFLINE ->
                    _sendNotice.value = SendNotice("Offline — the switch was not sent. Nothing changed.", SendNotice.Kind.WARNING, SendNotice.Key.SWITCH_OFFLINE)
                ConversationPort.SendOutcome.STALE ->
                    _sendNotice.value = SendNotice("The session changed on the desktop — try the switch again.", SendNotice.Kind.WARNING, SendNotice.Key.SESSION_CHANGED)
                else ->
                    _sendNotice.value = SendNotice("The desktop refused the switch — nothing changed.", SendNotice.Kind.ERROR, SendNotice.Key.SWITCH_REFUSED)
            }
        }
    }

    fun switchModel(provider: String, model: String, thought: String) {
        scope.launch {
            when (port.switchModel(taskId, provider, model, thought)) {
                ConversationPort.SendOutcome.ACCEPTED -> Unit
                ConversationPort.SendOutcome.BLOCKED_OFFLINE ->
                    _sendNotice.value = SendNotice("Offline — the switch was not sent. Nothing changed.", SendNotice.Kind.WARNING, SendNotice.Key.SWITCH_OFFLINE)
                ConversationPort.SendOutcome.STALE ->
                    _sendNotice.value = SendNotice("The session changed on the desktop — try the switch again.", SendNotice.Kind.WARNING, SendNotice.Key.SESSION_CHANGED)
                else ->
                    _sendNotice.value = SendNotice("The desktop refused the model switch — nothing changed.", SendNotice.Kind.ERROR, SendNotice.Key.MODEL_SWITCH_REFUSED)
            }
        }
    }

    /** §23.2 upload (full transaction; progress surfaces via the port overlay).
     *  FIELD (2026-10-04, vc33): failures were returned but never SHOWN — an upload
     *  into a reconnect window "didn't go through at all". Honest notice now. */
    suspend fun uploadAttachment(fileName: String, mime: String, bytes: ByteArray): ConversationPort.UploadResult {
        val uploadId = "up-" + java.util.UUID.randomUUID().toString()
        val result = port.uploadAttachment(taskId, fileName, mime, bytes, uploadId) { _, _ -> }
        when (result) {
            is ConversationPort.UploadResult.Committed -> Unit
            is ConversationPort.UploadResult.TooLarge ->
                _sendNotice.value = SendNotice("Attachment is over the 20 MB desktop limit — not sent.", SendNotice.Kind.WARNING, SendNotice.Key.ATTACHMENT_TOO_LARGE)
            is ConversationPort.UploadResult.BlockedOffline ->
                _sendNotice.value = SendNotice("Offline — the attachment was not sent. Retry when connected.", SendNotice.Kind.WARNING, SendNotice.Key.ATTACHMENT_OFFLINE)
            is ConversationPort.UploadResult.Failed ->
                _sendNotice.value = SendNotice("Attachment failed to upload (${result.message?.take(60) ?: "connection lost"}) — not sent.", SendNotice.Kind.ERROR, SendNotice.Key.ATTACHMENT_FAILED, listOf(result.message?.take(60) ?: ""))
        }
        return result
    }

    fun removeUpload(uploadId: String) {
        scope.launch { port.removeUpload(uploadId) }
    }

    /** One-tap retry of a failed attachment chip (payload retained port-side). */
    fun retryUpload(uploadId: String) {
        scope.launch {
            val result = port.retryUpload(taskId, uploadId)
            if (result !is ConversationPort.UploadResult.Committed) {
                _sendNotice.value = SendNotice("Attachment retry failed — not sent.", SendNotice.Kind.ERROR, SendNotice.Key.ATTACHMENT_RETRY_FAILED)
            }
        }
    }

    fun dismissHistoryNotice() {
        _historyChanged.value = false
    }

    fun retryOlder(beforeRowId: Long) = loadOlder(beforeRowId)

    // ---------------------------------------------------------------- Phase 7 write path

    init {
        // §17.4: the persisted draft restores once (process death included). E8 field
        // finding: the store emission can race the user's FIRST keystrokes — an
        // unconditional restore WIPED the just-typed draft (the composer was blank at
        // send: send() read an empty draft and silently no-op'd). The restore applies
        // only when the user has not typed; user content ALWAYS wins.
        scope.launch {
            port.observeDraft(taskId).collect { stored ->
                if (!draftLoaded) {
                    draftLoaded = true
                    if (!stored.isNullOrEmpty() && _draft.value.isEmpty()) {
                        _draft.value = stored
                    }
                }
            }
        }
    }

    /** Composer change: in-memory immediately, Room at the §17.4 300 ms debounce. */
    fun onDraftChange(text: String) {
        _draft.value = text
        draftSaveJob?.cancel()
        draftSaveJob = scope.launch {
            kotlinx.coroutines.delay(DRAFT_DEBOUNCE_MS)
            port.saveDraft(taskId, text)
        }
    }

    fun dismissSendNotice() {
        _sendNotice.value = null
    }

    /**
     * Send (§17.5/§17.6): the coordinator blocks offline (the draft is preserved —
     * E8); ACCEPTED clears the draft (the message is now the authoritative plane's
     * concern); STALE/FAILED/UNKNOWN surface truthful copy (§33) with the draft kept.
     */
    fun send(heldQueueDisposition: String? = null) {
        val text = _draft.value
        if (text.isBlank()) return
        scope.launch {
            // Phase 2 (§11.3, GAP-12): the desktop's slash grammar gates the send —
            // ready composer attachments block the whole plane (desktop parity).
            val readyAttachments = state.value.composerAttachments.count { it.state == "ready" && it.ref != null }
            when (val dispatch = parseVisibleSlashCommand(text, readyAttachments)) {
                null -> routeSendPrompt(text, heldQueueDisposition)
                is SlashDispatch.Compact -> sendSlash(
                    { port.compact(taskId) },
                    text,
                    failureNotice = "Compact was refused by the desktop" +
                        " (it may already be running or an active turn blocks it).",
                    failureKey = SendNotice.Key.COMPACT_REFUSED,
                    acceptedClearsDraft = true,
                )
                is SlashDispatch.SendGoal -> sendSlash(
                    { port.sendGoal(taskId, dispatch.objective, dispatch.displayText) },
                    text,
                    failureNotice = "The goal update was refused by the desktop.",
                    failureKey = SendNotice.Key.GOAL_REFUSED,
                )
                is SlashDispatch.ResumeGoal -> sendSlash(
                    { port.resumeGoal(taskId) },
                    text,
                    failureNotice = "Goal resume was refused (the conversation may have moved — try again).",
                    failureKey = SendNotice.Key.GOAL_RESUME_REFUSED,
                )
                is SlashDispatch.EmptyGoal -> refuseSlash("/goal needs a goal text — e.g. \"/goal finish the report\".")
                is SlashDispatch.UnsupportedGoal -> refuseSlash(
                    "The desktop does not support \"/goal ${dispatch.action}\" here — manage it on the desktop.",
                )
                is SlashDispatch.UnsupportedPlanShortcut -> refuseSlash(
                    "Plan mode cannot start with attachments attached — remove them and retry.",
                )
                is SlashDispatch.PlanShortcut -> {
                    // Desktop: the command only CONFIGURES the shortcut (mode + this
                    // submission's planEnabled) — the task text then rides plain sendText.
                    when (port.switchMode(taskId, "plan")) {
                        ConversationPort.SendOutcome.ACCEPTED -> routeSendPrompt(
                            dispatch.task.ifBlank { "" },
                            heldQueueDisposition,
                            skipEmpty = true,
                        )
                        else -> _sendNotice.value = SendNotice(
                            "Could not switch to plan mode — the plan shortcut was not sent.",
                            SendNotice.Kind.WARNING,
                        )
                    }
                }
            }
        }
    }

    /** Plain sendText (null dispatch = ordinary prompt; custom slash text rides raw). */
    private suspend fun routeSendPrompt(text: String, heldQueueDisposition: String?, skipEmpty: Boolean = false) {
        if (skipEmpty && text.isBlank()) {
            // Empty /plan only edits the mode (the desktop returns "sent" without a send).
            _sendNotice.value = null
            draftSaveJob?.cancel()
            _draft.value = ""
            port.clearDraft(taskId)
            return
        }
        when (port.sendPrompt(taskId, text, heldQueueDisposition)) {
            ConversationPort.SendOutcome.ACCEPTED -> {
                _sendNotice.value = null
                draftSaveJob?.cancel()
                _draft.value = ""
                port.clearDraft(taskId)
            }
            ConversationPort.SendOutcome.BLOCKED_OFFLINE ->
                _sendNotice.value = SendNotice(
                    "You're offline — the draft is saved on this phone. Reconnect to send.",
                    SendNotice.Kind.WARNING,
                )
            ConversationPort.SendOutcome.STALE ->
                _sendNotice.value = SendNotice(
                    "Conversation changed on the desktop — please try again.",
                    SendNotice.Kind.WARNING,
                )
            ConversationPort.SendOutcome.FAILED ->
                // FIELD (vc22): a FAILED outcome is now an authoritative DESKTOP
                // rejection (the command reached it) — never "connection lost".
                _sendNotice.value = SendNotice(
                    "The desktop refused the send — see diagnostics (fault reason recorded).",
                    SendNotice.Kind.ERROR,
                )
            ConversationPort.SendOutcome.UNKNOWN_KEPT ->
                _sendNotice.value = SendNotice(
                    "Send outcome unknown (connection lost). Retry from the pending list — it will not be duplicated.",
                    SendNotice.Kind.WARNING,
                )
        }
    }

    /** Fire a slash command; an accepted dispatch clears the draft, failures preserve it. */
    private suspend fun sendSlash(
        fire: suspend () -> ConversationPort.SendOutcome,
        originalText: String,
        failureNotice: String,
        failureKey: SendNotice.Key? = null,
        acceptedClearsDraft: Boolean = true,
    ) {
        when (fire()) {
            ConversationPort.SendOutcome.ACCEPTED -> {
                _sendNotice.value = null
                if (acceptedClearsDraft) {
                    draftSaveJob?.cancel()
                    _draft.value = ""
                    port.clearDraft(taskId)
                }
            }
            ConversationPort.SendOutcome.BLOCKED_OFFLINE ->
                _sendNotice.value = SendNotice(
                    "You're offline — the command was not sent and the draft is preserved.",
                    SendNotice.Kind.WARNING,
                )
            else -> _sendNotice.value = SendNotice(failureNotice, SendNotice.Kind.WARNING, failureKey)
        }
    }

    /** Refusal path: NOTHING goes on the wire, the draft is preserved (desktop warn parity). */
    private fun refuseSlash(notice: String) {
        _sendNotice.value = SendNotice(notice, SendNotice.Kind.WARNING)
    }

    /**
     * GAP-10 (§11.4): the confirmed edit — the desktop re-asks the turn; the
     * replacement arrives as authoritative churn (row.removed + row.appended or
     * an epoch move). Blank replacements are refused client-side (desktop gate).
     */
    fun editUserQuery(rowId: Long, entityId: String, newText: String) {
        if (newText.isBlank()) {
            _sendNotice.value = SendNotice(
                "An edit needs new text — nothing was sent.",
                SendNotice.Kind.WARNING,
            )
            return
        }
        scope.launch {
            when (port.editUserQuery(taskId, rowId, entityId, newText)) {
                ConversationPort.SendOutcome.ACCEPTED -> {
                    _sendNotice.value = SendNotice(
                        "Edit sent — the turn is being re-asked.",
                        SendNotice.Kind.INFO,
                    )
                }
                ConversationPort.SendOutcome.BLOCKED_OFFLINE ->
                    _sendNotice.value = SendNotice(
                        "You're offline — the edit was not sent.",
                        SendNotice.Kind.WARNING,
                    )
                ConversationPort.SendOutcome.STALE ->
                    _sendNotice.value = SendNotice(
                        "The conversation moved on the desktop — the edit was not applied. Try again.",
                        SendNotice.Kind.WARNING,
                    )
                else ->
                    _sendNotice.value = SendNotice(
                        "The edit was refused by the desktop (a conflicting change may exist — check on the desktop).",
                        SendNotice.Kind.ERROR,
                    )
            }
        }
    }

    /** User-gated retry of an UNKNOWN send — SAME commandId (INV-4, no duplicate). */
    fun retryPendingSend(commandId: String) {
        scope.launch {
            when (port.retrySend(commandId)) {
                ConversationPort.SendOutcome.ACCEPTED -> _sendNotice.value = null
                ConversationPort.SendOutcome.BLOCKED_OFFLINE ->
                    _sendNotice.value = SendNotice("Still offline — nothing was duplicated.", SendNotice.Kind.WARNING)
                else -> _sendNotice.value = SendNotice("Retry failed — the message is kept.", SendNotice.Kind.ERROR)
            }
        }
    }

    fun stopGeneration() {
        scope.launch {
            val outcome = port.stopGeneration(taskId)
            if (outcome == ConversationPort.SendOutcome.BLOCKED_OFFLINE) {
                _sendNotice.value = SendNotice("Offline — reconnect to stop the agent.", SendNotice.Kind.WARNING)
            }
        }
    }

    fun cancelBackgroundWork(workId: String) {
        scope.launch { port.cancelBackgroundWork(taskId, workId) }
    }

    fun sendQueuedNow(queueItemId: String) {
        scope.launch {
            if (port.sendQueuedNow(taskId, queueItemId) == ConversationPort.SendOutcome.STALE) {
                _sendNotice.value = SendNotice("Queue changed on the desktop — please try again.", SendNotice.Kind.WARNING)
            }
        }
    }

    fun editQueueItem(queueItemId: String, newText: String) {
        scope.launch {
            if (port.editQueueItem(taskId, queueItemId, newText) == ConversationPort.SendOutcome.STALE) {
                _sendNotice.value = SendNotice("Queue changed on the desktop — please try again.", SendNotice.Kind.WARNING)
            }
        }
    }

    /** §20 answer through the ONE fail-closed gate (double-tap-safe via busy set). */
    fun answerInteraction(interactionId: String, optionId: String, freeText: String?, userInput: Boolean) {
        if (interactionId in _busyInteractions.value) return
        _busyInteractions.value = _busyInteractions.value + interactionId
        scope.launch {
            try {
                when (port.answerInteraction(interactionId, optionId, freeText, userInput)) {
                    ConversationPort.AnswerOutcome.REFINE_REQUIRES_TEXT ->
                        _sendNotice.value = SendNotice("Add the refinement text first.", SendNotice.Kind.WARNING)
                    ConversationPort.AnswerOutcome.NOT_APPROVABLE ->
                        _sendNotice.value = SendNotice("This request can't be answered here — check the desktop.", SendNotice.Kind.ERROR)
                    ConversationPort.AnswerOutcome.RETRYABLE_KEPT ->
                        _sendNotice.value = SendNotice("Answer not confirmed — retry when connected.", SendNotice.Kind.WARNING)
                    else -> Unit // SUBMITTED / INERT (button disabled) / DUPLICATE (no-op) are silent
                }
            } finally {
                _busyInteractions.value = _busyInteractions.value - interactionId
            }
        }
    }

    /** Phase 7 field: AskUserQuestion answers through the ONE fail-closed gate. */
    fun answerUserInputQuestions(interactionId: String, answers: Map<String, List<String>>) {
        if (interactionId in _busyInteractions.value) return
        _busyInteractions.value = _busyInteractions.value + interactionId
        scope.launch {
            try {
                when (port.answerUserInputQuestions(interactionId, answers)) {
                    ConversationPort.AnswerOutcome.RETRYABLE_KEPT ->
                        _sendNotice.value = SendNotice("Answers not confirmed — retry when connected.", SendNotice.Kind.WARNING)
                    ConversationPort.AnswerOutcome.NOT_APPROVABLE ->
                        _sendNotice.value = SendNotice("This question can't be answered here — check the desktop.", SendNotice.Kind.ERROR)
                    else -> Unit
                }
            } finally {
                _busyInteractions.value = _busyInteractions.value - interactionId
            }
        }
    }

    private fun kotlinx.coroutines.flow.Flow<List<StreamingBuffer>>.mapToTextMap(): kotlinx.coroutines.flow.Flow<Map<Long, String>> =
        this.map { buffers -> buffers.associate { it.rowId to it.text } }

    companion object {
        /** ≈30 Hz coalescing budget (§19.2 frame-budget aligned). */
        const val STREAM_FRAME_MS = 33L

        /** §17.4 keystroke debounce. */
        const val DRAFT_DEBOUNCE_MS = 300L

        /** §14.3: a pending send older than this renders as degraded, never as progress. */
        const val PENDING_MAX_LIFETIME_MS = 60_000L
    }
}

/** Compact label for the app-bar trailing status ("Connected · Running"). */
fun taskStateLabel(state: TaskStateMachine.State): String? = when (state) {
    TaskStateMachine.State.IDLE -> "Idle"
    TaskStateMachine.State.RUNNING, TaskStateMachine.State.LOADING -> "Running"
    TaskStateMachine.State.WAITING_FOR_PERMISSION, TaskStateMachine.State.WAITING_FOR_USER -> "Waiting"
    TaskStateMachine.State.COMPLETED -> "Completed"
    TaskStateMachine.State.FAILED -> "Failed"
    TaskStateMachine.State.CANCELLED -> "Stopped"
    TaskStateMachine.State.UNKNOWN -> null
}
