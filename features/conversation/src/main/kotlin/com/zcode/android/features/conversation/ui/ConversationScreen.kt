package com.zcode.android.features.conversation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.zcode.android.core.ui.components.rememberReducedMotion
import com.zcode.android.features.conversation.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zcode.android.core.ui.components.AttentionStrip
import com.zcode.android.core.ui.components.ConnectionStatusLine
import com.zcode.android.core.ui.components.SkeletonBar
import com.zcode.android.core.ui.components.StatusTone
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.domain.model.GoalState
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.domain.state.TaskStateMachine
import com.zcode.android.features.conversation.ConversationUiState
import com.zcode.android.features.conversation.port.ConversationPort
import com.zcode.android.features.conversation.taskStateLabel
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import com.zcode.android.core.ui.components.ZCodeMotion
import com.zcode.android.core.ui.components.rememberArrivalTracker
import com.zcode.android.core.ui.components.rememberMotionSpec
import com.zcode.android.core.ui.components.streamIn
import com.zcode.android.core.ui.components.ZCodeTopBar
import androidx.compose.material3.Scaffold
import com.zcode.android.core.ui.components.errorUserMessage
import com.zcode.android.core.ui.components.readerWidth

/**
 * Conversation surface (§43.2-6.3/6.4 + Phase 7 write path; ui-ux 06/07/09/10/12).
 * Tail-first render, `rowsRange` scroll-back with page-edge prefetch, stream-follow
 * anchoring with jump-to-latest + unread count, mandatory state strips (INV-11),
 * and the Phase 7 write surface: pinned approval/user-input cards (G2a "approval
 * first"), queue strip, and the §17.4/§17.6 composer.
 */
@Composable
fun ConversationScreen(
    state: ConversationUiState,
    phase: ConnectionPhase,
    onBack: () -> Unit,
    onLoadOlder: (Long, (Int) -> Unit) -> Unit,
    onDismissHistoryNotice: () -> Unit,
    onRetryOlder: (Long) -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: (heldQueueDisposition: String?) -> Unit,
    onStop: () -> Unit,
    onRetrySend: (commandId: String) -> Unit,
    onDismissSendNotice: () -> Unit,
    onAnswer: (String, String, String?, Boolean) -> Unit,
    onSendQueuedNow: (queueItemId: String) -> Unit,
    onEditQueueItem: (queueItemId: String, newText: String) -> Unit,
    modifier: Modifier = Modifier,
    onAnswerQuestions: (String, Map<String, List<String>>) -> Unit = { _, _ -> },
    /** A3.14 (Phase 5B): decline the elicitation (resolveInteraction action=decline). */
    onDeclineQuestions: (String) -> Unit = {},
    // ---- Phase 9 (§43.2-9): advanced read-only surfaces + model switch ----
    onOpenFiles: () -> Unit = {},
    onOpenGit: () -> Unit = {},
    onOpenTerminal: () -> Unit = {},
    onSwitchMode: (String) -> Unit = {},
    onSwitchModel: (provider: String, model: String, thought: String) -> Unit = { _, _, _ -> },
    modelOptionsProvider: suspend () -> List<com.zcode.android.domain.model.ModelProviderOption> = { emptyList() },
    turnChangesProvider: suspend (rowId: Long, entityId: String) -> ConversationPort.TurnChangesResult =
        { _, _ -> ConversationPort.TurnChangesResult(null, "unavailable") },
    onUploadAttachment: suspend (fileName: String, mime: String, bytes: ByteArray) -> ConversationPort.UploadResult =
        { _, _, _ -> ConversationPort.UploadResult.Failed("attachments unavailable") },
    onRemoveAttachment: (uploadId: String) -> Unit = {},
    onRetryAttachment: (uploadId: String) -> Unit = {},
    // ---- Phase 2 (§11.4, GAP-10): message edit ----
    onEditUserQuery: (rowId: Long, entityId: String, newText: String) -> Unit = { _, _, _ -> },
    // ---- Phase 3C/3D: tool-output slice, session strip, attachment previews ----
    toolOutputProvider: (suspend (ConversationRow.ToolCall) -> String?)? = null,
    sessionStatusProvider: suspend () -> com.zcode.android.domain.model.GitSummary? = { null },
    loadAttachment: (suspend (ConversationRow.UserInput.AttachmentMeta) -> ByteArray?)? = null,
    // ---- Phase 4A (E3.2+I1+N5): flag-gated row actions over the proven wire ----
    onRetryTurn: (rowId: Long, entityId: String) -> Unit = { _, _ -> },
    onForkAssistant: (rowId: Long, entityId: String) -> Unit = { _, _ -> },
    onApplyFileRewind: (rowId: Long, entityId: String) -> Unit = { _, _ -> },
    onAssistantFeedback: (rowId: Long, entityId: String, feedback: String?) -> Unit = { _, _, _ -> },
    /** A3.11 (Phase 5B): the rewind preview loader (null = the copy-only confirm). */
    rewindPreviewProvider: (suspend (rowId: Long, entityId: String) -> com.zcode.android.domain.model.RewindPreview?)? = null,
    /** Phase 4C (C2): the "/" menu opened — fetch the skills/agents/custom-command extras. */
    onSlashMenuOpen: () -> Unit = {},
    /** Phase 4C (A3.4): the effort switch (setThoughtLevel). */
    onSetThoughtLevel: (String) -> Unit = {},
    /** Phase 4C (C7): the @-mention workspace file search. */
    fileSearchProvider: suspend (String) -> List<String> = { emptyList() },
    // ---- Phase 5A (A3.3): workflow runs panel + run sheet ----
    workflowRuns: List<com.zcode.android.domain.model.WorkflowRunSummary>? = null,
    openWorkflowRun: com.zcode.android.features.conversation.ConversationViewModel.OpenWorkflowRun? = null,
    onOpenWorkflowRun: (com.zcode.android.domain.model.WorkflowRunSummary) -> Unit = {},
    onCloseWorkflowRun: () -> Unit = {},
    onLoadMoreRunEvents: () -> Unit = {},
    // ---- I1 queue (Phase 5A): reorder · auto-drain · follow-up mode ----
    onMoveQueueItem: ((queueItemId: String, delta: Int) -> Unit)? = null,
    onSetAutoDrain: ((Boolean) -> Unit)? = null,
    onSetFollowupMode: ((String) -> Unit)? = null,
    // ---- Phase 5B (A3.12 + A3.13): plans viewer + trajectory/search ----
    plans: List<com.zcode.android.domain.model.ConversationPlan>? = null,
    showTrajectory: Boolean = false,
    trajectory: com.zcode.android.domain.model.TrajectoryResult? = null,
    trajectoryLoading: Boolean = false,
    trajectorySearchResults: List<com.zcode.android.features.conversation.ConversationViewModel.SessionSearchResult>? = null,
    trajectoryQuery: String = "",
    onTrajectoryQueryChange: (String) -> Unit = {},
    onOpenTrajectory: () -> Unit = {},
    onCloseTrajectory: () -> Unit = {},
) {
    val rowActions = remember(onRetryTurn, onForkAssistant, onApplyFileRewind, onAssistantFeedback) {
        RowActionCallbacks(onRetryTurn, onForkAssistant, onApplyFileRewind, onAssistantFeedback)
    }
    // C6-BackHandler: back with an unsent draft raises a confirm (the promised
    // Phase-7 draft guard). The draft is Room-backed — the guard is the CONFIRMATION
    // UX only; "Leave" just proceeds. Empty draft passes straight through.
    var confirmLeave by remember { mutableStateOf(false) }
    val attemptBack = {
        if (state.draft.isNotBlank()) confirmLeave = true else onBack()
        Unit
    }
    androidx.activity.compose.BackHandler(enabled = state.draft.isNotBlank()) { confirmLeave = true }
    if (confirmLeave) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmLeave = false },
            modifier = Modifier.testTag("conversation_leave_confirm"),
            title = { Text(stringResource(R.string.conv_leave_title)) },
            text = { Text(stringResource(R.string.conv_leave_body)) },
            confirmButton = {
                TextButton(
                    onClick = { confirmLeave = false; onBack() },
                    modifier = Modifier.testTag("conversation_leave_discard"),
                ) { Text(stringResource(R.string.conv_leave_discard)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmLeave = false },
                    modifier = Modifier.testTag("conversation_leave_keep"),
                ) { Text(stringResource(R.string.conv_leave_keep)) }
            },
        )
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {},
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                // FIELD (2026-10-01, vc21 device): edge-to-edge draws the IME as an
                // overlay — without imePadding the keyboard covered the pinned
                // composer entirely ("typing but nothing shows up": the field was
                // UNDER the keyboard).
                .imePadding(),
        ) {
            Header(
                state = state,
                phase = phase,
                onBack = attemptBack,
                onOpenFiles = onOpenFiles,
                onOpenGit = onOpenGit,
                onOpenTerminal = onOpenTerminal,
                onSwitchMode = onSwitchMode,
                onSwitchModel = onSwitchModel,
                modelOptionsProvider = modelOptionsProvider,
                sessionStatusProvider = sessionStatusProvider,
                onSetThoughtLevel = onSetThoughtLevel,
                workflowRuns = workflowRuns,
                plans = plans,
                onOpenTrajectory = onOpenTrajectory,
                onOpenWorkflowRun = onOpenWorkflowRun,
            )
            // A3.3: the run detail sheet (an overlay — position in the tree is immaterial).
            openWorkflowRun?.let { open ->
                WorkflowRunSheet(open, onDismiss = onCloseWorkflowRun, onLoadMore = onLoadMoreRunEvents)
            }
            // A3.13: the trajectory sheet (search field + the model-call timeline).
            if (showTrajectory) {
                TrajectorySheet(
                    trajectory = trajectory,
                    loading = trajectoryLoading,
                    searchResults = trajectorySearchResults,
                    query = trajectoryQuery,
                    onQueryChange = onTrajectoryQueryChange,
                    onDismiss = onCloseTrajectory,
                )
            }
            // Phase 9.1: the per-turn diff viewer (opened from a turnHeader's summary).
            var diffTurn by remember { mutableStateOf<ConversationRow.TurnHeader?>(null) }
            // Phase 2 (§11.4, GAP-10): the message-edit sheet (long-press → Edit).
            var editingRow by remember { mutableStateOf<ConversationRow.UserInput?>(null) }
            editingRow?.let { row ->
                EditUserQueryDialog(
                    initialText = row.text,
                    onConfirm = { newText ->
                        onEditUserQuery(row.rowId, row.base.entityId ?: row.turnId, newText)
                        editingRow = null
                    },
                    onDismiss = { editingRow = null },
                )
            }
            diffTurn?.let { turn ->
                TurnDiffDialog(
                    turnLabel = stringResource(R.string.conv_turn_label, turn.rowId),
                    rowId = turn.rowId,
                    entityId = turn.base.entityId ?: turn.turnId,
                    provider = turnChangesProvider,
                    onDismiss = { diffTurn = null },
                )
            }
            // §20.2 "approval first": the open interaction cards are PINNED above the
            // timeline (the agent is blocked until answered — G2a ≤ 3 interactions).
            // B2.12: they are NEVER inside the strip budget.
            for (interaction in state.pendingPermissions) {
                ApprovalCard(
                    interaction = interaction,
                    busy = interaction.interactionId in state.busyInteractions,
                    onAnswer = { optionId, freeText ->
                        onAnswer(interaction.interactionId, optionId, freeText, false)
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            for (interaction in state.pendingUserInputs) {
                UserInputCard(
                    interaction = interaction,
                    busy = interaction.interactionId in state.busyInteractions,
                    onAnswer = { optionId, freeText ->
                        onAnswer(interaction.interactionId, optionId, freeText, true)
                    },
                    onAnswerQuestions = { answers ->
                        onAnswerQuestions(interaction.interactionId, answers)
                    },
                    onDecline = { onDeclineQuestions(interaction.interactionId) },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            // B2.12: the status-strip stack is BUDGETED (§15-S1/S9 mockups) — an
            // unbounded stack in this non-scrollable column squeezed the timeline.
            StripBudget(
                strips = buildList {
                    if (state.stale) {
                        add {
                            AttentionStrip(
                                title = stringResource(R.string.conv_stale_title),
                                tone = StatusTone.WAITING,
                                modifier = Modifier.testTag("conversation_stale_strip"),
                            )
                        }
                    }
                    if (state.taskState == TaskStateMachine.State.WAITING_FOR_PERMISSION ||
                        state.taskState == TaskStateMachine.State.WAITING_FOR_USER
                    ) {
                        add {
                            AttentionStrip(
                                title = stringResource(R.string.conv_attention_title),
                                message = stringResource(R.string.conv_attention_body),
                                tone = StatusTone.WAITING,
                                modifier = Modifier.testTag("conversation_attention_strip"),
                            )
                        }
                    }
                    // §7.5 queue display + edit (sendQueuedNow / editQueueItem, CAS upstream).
                    state.sessionState?.queue?.let { queue ->
                        add {
                            QueueStrip(
                                queue = queue,
                                busy = false,
                                onSendNow = onSendQueuedNow,
                                onEdit = onEditQueueItem,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                onMove = onMoveQueueItem,
                                onSetAutoDrain = onSetAutoDrain,
                                followupMode = state.sessionState?.config?.followupMode,
                                onSetFollowupMode = onSetFollowupMode,
                            )
                        }
                    }
                    // §19.4 FIELD (2026-09-30, vc11): the desktop truncates the initial
                    // snapshot to a tail window — make the remaining history DISCOVERABLE
                    // and tappable (pages of PAGE_MAX_ROWS; no hardcoded total).
                    if (state.hasMoreHistory && !state.loadingOlder && state.rows.isNotEmpty()) {
                        add {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("conversation_load_older_strip")
                                    .clickable { onLoadOlder(state.rows.first().rowId) { /* page fetched; list updates via the store */ } },
                            ) {
                                Text(
                                    text = stringResource(R.string.conv_load_earlier) +
                                        (state.totalRows?.let { total -> stringResource(R.string.conv_showing_latest_of, state.rows.size, total) } ?: ""),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                    if (state.historyChanged) {
                        add {
                            AttentionStrip(
                                title = stringResource(R.string.conv_history_changed),
                                tone = StatusTone.INFO,
                                actionLabel = stringResource(R.string.action_dismiss),
                                onAction = onDismissHistoryNotice,
                                modifier = Modifier.testTag("conversation_history_strip"),
                            )
                        }
                    }
                },
                modifier = Modifier.padding(horizontal = 0.dp),
            )
            when {
                state.loading && state.rows.isEmpty() -> LoadingBody()
                state.empty -> EmptyBody()
                else -> TimelineBody(
                    state = state,
                    onLoadOlder = onLoadOlder,
                    onRetryOlder = onRetryOlder,
                    onOpenTurnChanges = { diffTurn = it },
                    onEditRow = { row -> editingRow = row },
                    toolOutputProvider = toolOutputProvider,
                    rowActions = rowActions,
                        rewindPreviewProvider = rewindPreviewProvider,
                    // F3-LOAD root cause (Phase 4A discovery): the loader was accepted by the
                    // screen but never reached the timeline — previews could never load.
                    loadAttachment = loadAttachment,
                )
            }
            // §17.4/§17.6 composer (pinned bottom; drafting allowed offline, send
            // blocked with explicit copy — never a silent queue).
            ComposerBar(
                state = state,
                onDraftChange = onDraftChange,
                onSend = onSend,
                onStop = onStop,
                onRetrySend = onRetrySend,
                onDismissNotice = onDismissSendNotice,
                onUploadAttachment = onUploadAttachment,
                onRemoveAttachment = onRemoveAttachment,
                onRetryAttachment = onRetryAttachment,
                onSlashMenuOpen = onSlashMenuOpen,
                fileSearchProvider = fileSearchProvider,
            )
        }
    }
}

@Composable
private fun Header(
    state: ConversationUiState,
    phase: ConnectionPhase,
    onBack: () -> Unit,
    onOpenFiles: () -> Unit = {},
    onOpenGit: () -> Unit = {},
    onOpenTerminal: () -> Unit = {},
    onSwitchMode: (String) -> Unit = {},
    onSwitchModel: (provider: String, model: String, thought: String) -> Unit = { _, _, _ -> },
    modelOptionsProvider: suspend () -> List<com.zcode.android.domain.model.ModelProviderOption> = { emptyList() },
    sessionStatusProvider: suspend () -> com.zcode.android.domain.model.GitSummary? = { null },
    /** Phase 4C (A3.4): the effort switch (setThoughtLevel). */
    onSetThoughtLevel: (String) -> Unit = {},
    /** Phase 5A (A3.3): the session's workflow runs (null/empty → no panel). */
    workflowRuns: List<com.zcode.android.domain.model.WorkflowRunSummary>? = null,
    onOpenWorkflowRun: (com.zcode.android.domain.model.WorkflowRunSummary) -> Unit = {},
    /** Phase 5B (A3.12): the plans projection (null/empty → no panel). */
    plans: List<com.zcode.android.domain.model.ConversationPlan>? = null,
    /** Phase 5B (A3.13): open the trajectory sheet. */
    onOpenTrajectory: () -> Unit = {},
) {
    Column {
        ZCodeTopBar(
            title = state.task?.title ?: stringResource(R.string.conv_default_title),
            onBack = onBack,
            backTestTag = "conversation_back",
        )
        ConnectionStatusLine(
            phase = phase,
            // D2: the progress label is TURN-KIND-AWARE — control.activeWorks[].kind
            // (compacting / verifying goal / running subagents…) refines "Running".
            trailing = listOfNotNull(taskStateLabel(state.taskState), GoalState.turnKindLabel(state.sessionState?.control?.activeWorks?.firstOrNull()?.kind))
                .joinToString(" · ").ifEmpty { null },
            modifier = Modifier.padding(horizontal = 16.dp).testTag("conversation_status_line"),
        )
        // GAP-1/GAP-2: the context-window meter + per-source breakdown (§11.1).
        // Renders nothing until a renderable `usage` key arrives (INV-8 absence).
        ContextUsagePanel(
            usage = state.sessionState?.usage,
            modifier = Modifier.padding(top = 2.dp),
        )
        // Phase 9: model/mode chips (§10.3 echo) + Files/Git/Terminal entry points.
        // FIELD (2026-10-04, vc32): FIVE chips cannot share one row on narrow screens —
        // the weighted session chips starved (zero-width model chip on a 320dp layout;
        // on the device the crushed chip exploded vertically and blocked the chat).
        // Two compact rows: the echo chips own the full width; the read-only surface
        // entries right-align under them.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SessionModeChips(
                config = state.sessionState?.config,
                onSwitchMode = onSwitchMode,
                onSwitchModel = onSwitchModel,
                modelOptionsProvider = modelOptionsProvider,
                onSetThoughtLevel = onSetThoughtLevel,
                modifier = Modifier.weight(1f, fill = true),
            )
        }
        // I2.2: branch · clean/changes · agents — from the git summary channel + the
        // timeline's running subagents; refused/not-a-repository → honest absence.
        val sessionStatus by androidx.compose.runtime.produceState<com.zcode.android.domain.model.GitSummary?>(
            initialValue = null,
        ) {
            value = runCatching { sessionStatusProvider() }.getOrNull()
        }
        SessionStatusStrip(
            summary = sessionStatus,
            runningAgents = countRunningAgents(state.rows),
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        // D2: the goal + todo surfaces — keys the wire always carried; absent → nothing.
        state.sessionState?.goal?.let { goal ->
            GoalPanel(goal, modifier = Modifier.padding(horizontal = 8.dp))
        }
        state.sessionState?.plan?.let { plan ->
            PlanPanel(plan, modifier = Modifier.padding(horizontal = 8.dp))
        }
        // A3.3: the session's workflow runs (conversationWorkflowRunsV4); null/empty → nothing.
        workflowRuns?.takeIf { it.isNotEmpty() }?.let { runs ->
            WorkflowRunsPanel(runs, onOpen = onOpenWorkflowRun, modifier = Modifier.padding(horizontal = 8.dp))
        }
        // A3.12: the plans viewer folds under the todo panel (one panel surface, two sections).
        plans?.takeIf { it.isNotEmpty() }?.let { list ->
            PlansViewer(list, modifier = Modifier.padding(horizontal = 8.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.weight(1f))
            AssistChip(onClick = onOpenFiles, label = { Text(stringResource(R.string.files_entry)) }, modifier = Modifier.testTag("conv_open_files"))
            AssistChip(onClick = onOpenGit, label = { Text(stringResource(R.string.git_entry)) }, modifier = Modifier.testTag("conv_open_git"))
            AssistChip(onClick = onOpenTerminal, label = { Text(stringResource(R.string.terminal_entry)) }, modifier = Modifier.testTag("conv_open_terminal"))
            AssistChip(onClick = onOpenTrajectory, label = { Text(stringResource(R.string.conv_trajectory_entry)) }, modifier = Modifier.testTag("conv_open_trajectory"))
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.LoadingBody() {
    // weight(1f), not fillMaxSize — the composer (Phase 7) stays pinned below.
    Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp, vertical = 16.dp)) {
        repeat(4) {
            SkeletonBar(Modifier.fillMaxWidth(if (it % 2 == 0) 0.85f else 0.6f).testTag("conversation_skeleton"))
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.EmptyBody() {
    Column(
        // weight(1f), not fillMaxSize — the composer (Phase 7) stays pinned below.
        Modifier.fillMaxWidth().weight(1f).padding(horizontal = 32.dp).testTag("conversation_empty"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.conv_empty_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.conv_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** §19.2: render window + anchoring + pagination trigger + unread FAB. */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.TimelineBody(
    state: ConversationUiState,
    onLoadOlder: (Long, (Int) -> Unit) -> Unit,
    onRetryOlder: (Long) -> Unit,
    onOpenTurnChanges: (ConversationRow.TurnHeader) -> Unit = {},
    onEditRow: ((ConversationRow.UserInput) -> Unit)? = null,
    toolOutputProvider: (suspend (ConversationRow.ToolCall) -> String?)? = null,
    loadAttachment: (suspend (ConversationRow.UserInput.AttachmentMeta) -> ByteArray?)? = null,
    rowActions: RowActionCallbacks = RowActionCallbacks(),
    /** A3.11 (Phase 5B): the rewind preview loader threaded to the turn card. */
    rewindPreviewProvider: (suspend (rowId: Long, entityId: String) -> com.zcode.android.domain.model.RewindPreview?)? = null,
) {
    val listState = rememberLazyListState()
    // B3-motion + K2: rows that have already arrived (scroll-back never re-animates).
    val arrivedRows = rememberArrivalTracker()
    val motion = rememberMotionSpec()
    val scope = rememberCoroutineScope()
    var unread by remember { mutableIntStateOf(0) }
    var lastKnownCount by remember { mutableIntStateOf(0) }

    // "At bottom" = the END of the LAST item is inside the viewport. A growing
    // assistant message is TALLER than the viewport, so its top-anchored position
    // never satisfies a naive check — the END is what "caught up" means (§19.2).
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            last.index >= info.totalItemsCount - 1 &&
                (last.offset + last.size) <= info.viewportEndOffset + 80 &&
                last.size <= info.viewportEndOffset // a viewport-taller item is never fully visible
        }
    }
    var followBottom by remember { mutableStateOf(true) }
    // §30.5: the system "remove animations" switch picks instant snap-scrolls.
    val reducedMotion = rememberReducedMotion()

    // FIELD (vc24 device): the follow flag is driven by USER GESTURE DIRECTION, not
    // by the derived atBottom (which flips during programmatic scrolls and content
    // growth). Scroll up → release; scroll back down to the tail → re-follow.
    var lastFirstVisible by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        snapshotFlow {
            Triple(listState.isScrollInProgress, listState.firstVisibleItemIndex, atBottom)
        }.collect { (scrolling, firstVisible, bottom) ->
            if (scrolling) {
                if (firstVisible < lastFirstVisible) followBottom = false // user dragged up
                else if (bottom) followBottom = true // user dragged down to the tail
            }
            lastFirstVisible = firstVisible
        }
    }

    // FIELD-KILLER #10 (2026-09-30, vc14 field + repro'd on the emulator): the FIRST
    // non-empty layout must anchor to the tail UNCONDITIONALLY. The old
    // `followBottom && atBottom` guard skipped the scroll on RE-ENTRY with cached rows:
    // the list lays out at index 0 first, `atBottom` reads false, and the conversation
    // opened at the TOP of the window — firing the prefetch spuriously ("loading
    // earlier messages" flash) and showing "jump to newest". Cold opens worked (rows
    // arrived while the list was empty), which is why it was intermittent.
    var anchoredToTail by remember { mutableStateOf(false) }
    // I2.7: one LazyColumn item per timeline ENTRY — settled tool runs collapse into
    // groups (pure map; the scroll index space below is the entry space).
    val entries = remember(state.rows) { groupTimeline(state.rows) }

    // Stick-to-bottom (§19.2, macOS parity): while following, ANY content growth —
    // new rows OR streaming text into the last row — clamps the viewport to the true
    // bottom (huge scrollOffset clamps to max scroll, which shows the tail of a
    // viewport-taller growing message; plain scrollToItem(index) shows its top and
    // was why the view kept falling behind during long generations).
    val followKey = (state.rows.size to state.rows.lastOrNull()?.rowId) to
        (state.streaming[state.rows.lastOrNull()?.rowId]?.length ?: 0)
    LaunchedEffect(followKey) {
        if (!anchoredToTail && state.rows.isNotEmpty()) {
            // Header items ("loading earlier…"/error strips) occupy index 0+.
            val headerItems =
                (if (state.loadingOlder) 1 else 0) + (if (state.loadOlderError != null) 1 else 0)
            listState.scrollToItem(headerItems + entries.size - 1)
            anchoredToTail = true
            lastKnownCount = state.rows.size
            return@LaunchedEffect
        }
        if (followBottom && state.rows.isNotEmpty()) {
            listState.scrollToItem(entries.size - 1, Int.MAX_VALUE / 2)
        }
        val grew = state.rows.size > lastKnownCount
        if (grew && !followBottom && lastKnownCount > 0) {
            unread += state.rows.size - lastKnownCount
        }
        lastKnownCount = state.rows.size
    }
    // Streaming growth also counts when released from the tail.
    LaunchedEffect(state.streaming.values.sumOf { it.length }) {
        if (!followBottom) {
            // coarse: any stream delta while scrolled up marks unread
            unread += 1
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { atBottom }.collect { at ->
            if (at) {
                unread = 0
            }
        }
    }

    // §19.2 prefetch: near the top edge with more history → fetch the previous page.
    LaunchedEffect(state.hasMoreHistory) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { index ->
            if (anchoredToTail && state.hasMoreHistory && !state.loadingOlder && index <= PAGE_EDGE && state.rows.isNotEmpty()) {
                onLoadOlder(state.rows.first().rowId) { rowsAdded ->
                    if (rowsAdded > 0) {
                        // Preserve the reader's position across the prepend (§19.4).
                        scope.launch { listState.scrollToItem(index + rowsAdded) }
                    }
                }
            }
        }
    }

    // weight(1f), NOT fillMaxSize — the pinned composer (§17.4) lives BELOW this
    // box in the Column; fillMaxSize consumed the entire column and pushed the
    // composer off-screen the moment rows loaded (field: 2026-10-01, vc20).
    Box(Modifier.fillMaxWidth().weight(1f)) {
        // M2: reader width discipline — prose caps at the reading ceiling and centers.
        val reader = Modifier.readerWidth()
        LazyColumn(
            state = listState,
            modifier = reader
                .fillMaxSize()
                .align(Alignment.TopCenter)
                .testTag("conversation_list"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (state.loadingOlder) {
                item(key = "older-loading") {
                    Row(Modifier.fillMaxWidth().testTag("conversation_older_loading"), horizontalArrangement = Arrangement.Center) {
                        Text(stringResource(R.string.conv_loading_older), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (state.loadOlderError != null) {
                item(key = "older-error") {
                    AttentionStrip(
                        title = stringResource(R.string.conv_load_older_error),
                        message = errorUserMessage(state.loadOlderError),
                        tone = StatusTone.ERROR,
                        actionLabel = stringResource(com.zcode.android.core.ui.R.string.action_retry),
                        onAction = { state.rows.firstOrNull()?.let { onRetryOlder(it.rowId) } },
                        modifier = Modifier.testTag("conversation_older_error"),
                    )
                }
            }
            items(
                count = entries.size,
                key = { entries[it].key },
                contentType = {
                    when (val e = entries[it]) {
                        is TimelineEntry.Single -> e.row.kind
                        is TimelineEntry.ToolGroup -> "toolGroup"
                    }
                },
            ) { index ->
                // B3-motion + K2: rows ARRIVE (900ms stream-in, staggered from the
                // viewport top) and reflow with animateItem; each row arrives once.
                when (val entry = entries[index]) {
                    is TimelineEntry.Single -> TimelineRow(
                        row = entry.row,
                        state = state,
                        modifier = Modifier
                            .animateItem()
                            .streamIn(
                                key = entry.row.rowId,
                                index = (index - listState.firstVisibleItemIndex).coerceIn(0, 10),
                                tracker = arrivedRows,
                            )
                            .testTag("conv-row-${entry.row.rowId}"),
                        onOpenTurnChanges = onOpenTurnChanges,
                        onEditRow = onEditRow,
                        toolOutputProvider = toolOutputProvider,
                        loadAttachment = loadAttachment,
                        rowActions = rowActions,
                    )
                    is TimelineEntry.ToolGroup -> ToolGroupBlock(
                        group = entry,
                        modifier = Modifier
                            .animateItem()
                            .streamIn(
                                key = entry.rows.first().rowId,
                                index = (index - listState.firstVisibleItemIndex).coerceIn(0, 10),
                                tracker = arrivedRows,
                            )
                            .testTag("conv-row-${entry.rows.first().rowId}"),
                        fullOutputProvider = toolOutputProvider,
                    )
                }
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = !followBottom || !atBottom,
            modifier = Modifier.align(Alignment.BottomEnd),
            enter = fadeIn(tween(motion.ms(ZCodeMotion.WaterfallMs))),
            exit = fadeOut(tween(motion.ms(ZCodeMotion.WaterfallMs))),
        ) {
            FloatingActionButton(
                onClick = {
                    followBottom = true
                    unread = 0
                    scope.launch {
                        if (state.rows.isNotEmpty()) {
                            // §30.5: honor the system reduced-motion switch.
                            if (reducedMotion) listState.scrollToItem(entries.size - 1)
                            else listState.animateScrollToItem(entries.size - 1)
                        }
                    }
                },
                modifier = Modifier
                    .padding(16.dp)
                    .testTag("conversation_jump_latest"),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Box {
                    // B2.9: a NUMBERED badge (the bare 16dp dot had no count/semantics).
                    androidx.compose.foundation.layout.Box(Modifier) {
                        Icon(imageVector = Icons.Filled.ArrowDownward, contentDescription = stringResource(R.string.conv_jump_latest))
                        UnreadBadge(unread = unread, modifier = Modifier.align(Alignment.TopEnd))
                    }
                }
            }
        }
    }
}

/** Phase 4A (E3.2+I1+N5): the four flag-gated row actions, bundled for the timeline pass-through. */
data class RowActionCallbacks(
    val onRetryTurn: (rowId: Long, entityId: String) -> Unit = { _, _ -> },
    val onForkAssistant: (rowId: Long, entityId: String) -> Unit = { _, _ -> },
    val onApplyFileRewind: (rowId: Long, entityId: String) -> Unit = { _, _ -> },
    val onAssistantFeedback: (rowId: Long, entityId: String, feedback: String?) -> Unit = { _, _, _ -> },
)

@Composable
private fun TimelineRow(
    row: ConversationRow,
    state: ConversationUiState,
    modifier: Modifier = Modifier,
    onOpenTurnChanges: (ConversationRow.TurnHeader) -> Unit = {},
    onEditRow: ((ConversationRow.UserInput) -> Unit)? = null,
    toolOutputProvider: (suspend (ConversationRow.ToolCall) -> String?)? = null,
    loadAttachment: (suspend (ConversationRow.UserInput.AttachmentMeta) -> ByteArray?)? = null,
    rowActions: RowActionCallbacks = RowActionCallbacks(),
    /** A3.11 (Phase 5B): the rewind preview loader threaded to the turn card. */
    rewindPreviewProvider: (suspend (rowId: Long, entityId: String) -> com.zcode.android.domain.model.RewindPreview?)? = null,
) {
    Box(modifier = modifier) {
    when (row) {
        is ConversationRow.UserInput -> YouBlock(
            text = row.text,
            attachments = row.attachments,
            loadAttachment = loadAttachment,
            createdAtMs = row.base.createdAt,
            // E3.1: the sheet gates Edit on the WIRE'S OWN capability flag
            // (actions.canEdit — ConversationRowView.tsx:1295 class), not on
            // entityId presence: rows the desktop forbids lose Edit, allowed rows
            // without an entityId keep it.
            editable = row.base.actions?.canEdit == true && onEditRow != null,
            onEdit = onEditRow?.let { cb -> { cb(row) } },
        )
        is ConversationRow.AssistantText -> {
            // §19.2 merge: the store row accumulates appends IMMEDIATELY (authoritative),
            // while the coalesced streaming buffer may LEAD it between delta and flush.
            // If the persisted text already ends with the buffered suffix, the store is
            // flushed — display it; otherwise append the suffix to the persisted base.
            // FIELD (vc23): the doubled-tail bug was fixed at the SOURCE — an
            // authoritative row upsert now DISCARDS the row's streaming buffer in the
            // reducer (§15.5c), so a surviving buffer always means the row has not
            // been authoritatively replaced and the merge is correct.
            val streamed = state.streaming[row.rowId]
            val text = when {
                streamed == null -> row.text
                row.text.endsWith(streamed) -> row.text
                else -> row.text + streamed
            }
            // Phase 4A: the row target the commands need — entityId is the wire's own
            // (conversationRowTargetSchema requires a non-empty entityId; a row without
            // one cannot be targeted, so its actions stay withheld).
            val entityId = row.base.entityId
            AgentProse(
                text = text,
                streaming = row.state == ConversationRow.TextState.STREAMING || streamed != null,
                createdAtMs = row.base.createdAt,
                canFork = row.base.actions?.canFork == true && entityId != null,
                canRetry = row.base.actions?.canRetry == true && entityId != null,
                feedback = row.feedback,
                onFork = entityId?.let { e -> { rowActions.onForkAssistant(row.rowId, e) } },
                onRetry = entityId?.let { e -> { rowActions.onRetryTurn(row.rowId, e) } },
                onFeedback = entityId?.let { e -> { f: String? -> rowActions.onAssistantFeedback(row.rowId, e, f) } },
            )
        }
        is ConversationRow.ToolCall -> ToolCard(row, fullOutputProvider = toolOutputProvider)
        is ConversationRow.Reasoning -> ReasoningRow(row)
        is ConversationRow.TurnHeader -> TurnSeparator(
            row,
            onOpenChanges = { onOpenTurnChanges(row) },
            onRewindFiles = row.base.entityId?.let { e -> { rowActions.onApplyFileRewind(row.rowId, e) } },
            rewindPreviewProvider = rewindPreviewProvider,
        )
        is ConversationRow.Artifact -> QuietMetaRow(
            label = row.displayName.ifBlank { "Artifact" },
            detail = formatBytes(row.sizeBytes),
        )
        is ConversationRow.Subagent -> QuietMetaRow(label = stringResource(R.string.row_subagent, row.subagentType), detail = row.summaryText.take(80))
        is ConversationRow.HookInvocation -> QuietMetaRow(label = stringResource(R.string.row_hook, row.hookEventName), detail = null)
        is ConversationRow.TimelineMarker -> QuietMetaRow(label = row.markerType, detail = null)
        is ConversationRow.Unknown -> UnknownKindRow(row)
    }
    }

}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024 -> "%.1f KB".format(bytes / 1_024.0)
    bytes > 0 -> "$bytes B"
    else -> ""
}

private const val PAGE_EDGE = 12
