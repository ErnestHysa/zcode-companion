package com.zcode.android.features.conversation.ui

import androidx.compose.foundation.clickable
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zcode.android.core.ui.components.ZCodeChip
import com.zcode.android.core.ui.components.ZCodeModalBottomSheet
import com.zcode.android.domain.model.ConversationPlan
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.domain.model.SessionProjectionState
import com.zcode.android.domain.model.WorkflowRunSummary

/**
 * F-CHAT-FOCUS v2 (field 2026-10-27, Ernest on vc48): the conversation header was a
 * STACK of six one-liner cards (status · config · summary · goal · todos · plans) plus
 * a five-chip tool rail — even fully collapsed it stood between the user and the chat.
 * The Jobs-grade answer: NONE of it lives above the timeline permanently. ONE tappable
 * [ConversationScreen] bar affordance opens THIS sheet — every context surface
 * (context meter · session summary · goal · todos · workflow runs · plans) expanded and
 * freely collapsible, with the Files/Git/Terminal/Model calls/Share entries as a chip
 * row at the bottom. One tap to reach anything, zero permanent clutter.
 */
@Composable
fun SessionContextSheet(
    sessionState: SessionProjectionState?,
    rows: List<ConversationRow>,
    workflowRuns: List<WorkflowRunSummary>?,
    plans: List<ConversationPlan>?,
    sessionStatusProvider: suspend () -> com.zcode.android.domain.model.GitSummary?,
    onDismiss: () -> Unit,
    onOpenWorkflowRun: (WorkflowRunSummary) -> Unit = {},
    onOpenFiles: () -> Unit = {},
    onOpenGit: () -> Unit = {},
    onOpenTerminal: () -> Unit = {},
    onOpenTrajectory: () -> Unit = {},
    onShareTranscript: () -> Unit = {},
    modifier: Modifier = Modifier,
    // ---- G26 (vc51): the goal affordances (the desktop's add-goal + pause/resume) ----
    /**
     * F-P20-A (pass-0 Phase 6): the sheet's goal send carries the answered
     * held-queue trio — [onSetGoal] fires only AFTER the choice gate below (nulls
     * = no held queue stood; the legacy trio-less shape).
     */
    onSetGoal: (objective: String, heldQueueDisposition: String?, expectedHeldQueueItemIds: List<String>?) -> Unit = { _, _, _ -> },
    onPauseGoal: () -> Unit = {},
    onResumeGoal: () -> Unit = {},
    /**
     * F-27-C/D5: the sheet's scroll state, HOISTED — the host keys it to the sheet's
     * visibility so every open starts at the top (the recorded reopen-below-the-chip-row
     * face is structurally impossible).
     */
    scrollState: androidx.compose.foundation.ScrollState = rememberScrollState(),
    /**
     * G60 (vc52 Phase 12): the per-task token usage read (getTaskTokenUsage) —
     * fetched when the sheet opens; null provider or null result renders NOTHING
     * (the honest absent face; never a silent 0).
     */
    taskUsageProvider: (suspend () -> com.zcode.android.domain.model.TaskTokenUsage?)? = null,
    /**
     * G92 (pass-0 Phase 10): the workspace config's configOptions (select/boolean
     * + current values) — decoded in V4WorkspaceConfig since Phase 2 with zero
     * consumers; this section is the ONE demoed adapter. Empty list renders
     * NOTHING (never invented options).
     */
    configOptions: List<com.zcode.android.features.conversation.port.ConversationPort.ConfigOptionView> = emptyList(),
) {
    // F-P20-A: the sheet's choice gate — the objective typed in the Set-goal dialog,
    // held while the disposition dialog asks (a dismissal drops it).
    var pendingGoalObjective by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var goalDispositionDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    ZCodeModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier.testTag("session_context_sheet")) {
        // F-27-C (pass 31/33, the bottom-edge clip family): the sheet is edge-to-edge —
        // without a nav-bar inset the footer chip row painted UNDER the 3-button bar
        // (a tap on Share hit system Back) and was cut by the screen edge under gesture
        // nav; the wrapped chips reported zero/11px bounds. The inset is applied on the
        // OUTER column so the sheet's measured height INCLUDES it (skipPartiallyExpanded:
        // height = content height) — the chips clear the bar WITHOUT scrolling, under
        // both nav modes.
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .testTag("session_context_inset"),
        ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = SheetMaxHeight)
                .verticalScroll(scrollState)
                .testTag("session_context_content"),
        ) {
            // §11.1: the context-window meter rides first — it is context about context.
            ContextUsagePanel(
                usage = sessionState?.usage,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
            // G60: the per-task TOKEN usage (the desktop tokenDebug plane's totals —
            // input/output/reasoning/cache + model request/error counts).
            TaskTokenUsageSection(taskUsageProvider)
            // G92 (pass-0 Phase 10): the workspace CONFIG OPTIONS section — the
            // desktop's select/boolean option surface with its current values.
            if (configOptions.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .testTag("config_options_section"),
                ) {
                    Text(
                        stringResource(com.zcode.android.features.conversation.R.string.config_options_title),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    for (option in configOptions) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .testTag("config_option_${option.id}"),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    option.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    option.type,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            option.currentValue?.let { value ->
                                Text(
                                    stringResource(com.zcode.android.features.conversation.R.string.config_option_current, value),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            option.description?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            // I2.3: the session summary capsule (duration · goal iteration · background
            // tasks · subagents) — nothing renders for an empty session.
            SessionSummaryPanel(
                sessionState = sessionState,
                rows = rows,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
            // G52 (vc52 Phase 11): the workspace hook-trust admission plane — hooks are
            // pending trust for this workspace; the count renders ONLY when pending > 0
            // (never a silently-dropped state key).
            sessionState?.workspaceHookAdmission?.takeIf { it.pendingCount > 0 }?.let { admission ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .testTag("hooks_admission_row"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(com.zcode.android.features.conversation.R.string.hooks_admission_pending, admission.pendingCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            // D2: the goal + todo surfaces — EXPANDED inside the sheet (the user opened
            // it to read context); both headers remain true toggles.
            sessionState?.goal?.let { goal ->
                GoalPanel(
                    goal,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 3.dp),
                    onPauseGoal = onPauseGoal,
                    onResumeGoal = onResumeGoal,
                )
            }
            // G26: the desktop offers add-goal on the composer menu for goal-less
            // sessions (ChatPromptActionMenu.tsx:30) — the sheet's Set-goal row.
            if (sessionState?.goal == null) {
                // F-P20-A (pass-0 Phase 6): the sheet's goal send rides the SAME
                // sendGoalCommand admission gate as the composer — under a CHOICE
                // routing (the held-queue choice admission) the disposition dialog
                // fires BEFORE any wire and its answer rides the goal. The pass-20
                // receipt: the ungated sheet goal was structurally refused
                // (heldQueueDispositionRequired) with only the generic ERROR face.
                val heldQueue = sessionState?.inputRouting?.mode ==
                    com.zcode.android.domain.model.InputRouting.Mode.CHOICE
                fun heldQueueIds(): List<String>? =
                    sessionState?.queue?.items?.map { it.queueItemId }?.takeIf { it.isNotEmpty() }
                SetGoalRow(
                    onSet = { objective ->
                        if (heldQueue) {
                            pendingGoalObjective = objective
                            goalDispositionDialog = true
                        } else {
                            onSetGoal(objective, null, null)
                        }
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 3.dp),
                )
                if (goalDispositionDialog) {
                    HeldQueueDispositionDialog(
                        onDismiss = {
                            goalDispositionDialog = false
                            pendingGoalObjective = null // a dismissed answer never sends
                        },
                        onClear = {
                            goalDispositionDialog = false
                            onSetGoal(pendingGoalObjective.orEmpty(), "clearQueueAndSend", heldQueueIds())
                            pendingGoalObjective = null
                        },
                        onKeep = {
                            goalDispositionDialog = false
                            onSetGoal(pendingGoalObjective.orEmpty(), "keepQueueAndSend", heldQueueIds())
                            pendingGoalObjective = null
                        },
                    )
                }
            }
            sessionState?.plan?.let { plan ->
                PlanPanel(plan, modifier = Modifier.padding(horizontal = 16.dp, vertical = 3.dp))
            }
            // A3.3: the session's workflow runs; null/empty → nothing.
            workflowRuns?.takeIf { it.isNotEmpty() }?.let { runs ->
                WorkflowRunsPanel(runs, onOpen = onOpenWorkflowRun, modifier = Modifier.padding(horizontal = 16.dp, vertical = 3.dp))
            }
            // A3.12: the plans viewer (expanded by default inside the sheet).
            plans?.takeIf { it.isNotEmpty() }?.let { list ->
                PlansViewer(list, modifier = Modifier.padding(horizontal = 16.dp, vertical = 3.dp))
            }
            // I2.2: branch · clean/changes · agents (git summary channel + timeline).
            val sessionStatus by androidx.compose.runtime.produceState<com.zcode.android.domain.model.GitSummary?>(
                initialValue = null,
            ) {
                value = runCatching { sessionStatusProvider() }.getOrNull()
            }
            SessionStatusStrip(
                summary = sessionStatus,
                runningAgents = countRunningAgents(rows),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(6.dp))
            Divider()
            // The read-only surface entries — the old five-chip rail, re-homed.
            Text(
                stringResource(com.zcode.android.features.conversation.R.string.context_sheet_tools),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZCodeChip(onClick = onOpenFiles, label = stringResource(com.zcode.android.features.conversation.R.string.files_entry), testTag = "conv_open_files")
                ZCodeChip(onClick = onOpenGit, label = stringResource(com.zcode.android.features.conversation.R.string.git_entry), testTag = "conv_open_git")
                ZCodeChip(onClick = onOpenTerminal, label = stringResource(com.zcode.android.features.conversation.R.string.terminal_entry), testTag = "conv_open_terminal")
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZCodeChip(onClick = onOpenTrajectory, label = stringResource(com.zcode.android.features.conversation.R.string.conv_trajectory_entry), testTag = "conv_open_trajectory")
                ZCodeChip(onClick = onShareTranscript, label = stringResource(com.zcode.android.features.conversation.R.string.conv_share_entry), testTag = "conv_share")
            }
            Spacer(Modifier.height(14.dp))
        }
        }
    }
}

private val SheetMaxHeight = 640.dp


/**
 * G26: the goal-less session's "Set goal" affordance — one row + a text dialog;
 * the wire verb is the SAME sendGoalCommand the /goal slash rides, so refusal
 * banners fire identically.
 */
@Composable
private fun SetGoalRow(onSet: (String) -> Unit, modifier: Modifier = Modifier) {
    var dialogOpen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("goal_set_row"),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { dialogOpen = true }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(com.zcode.android.features.conversation.R.string.goal_set_affordance),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
        }
    }
    if (dialogOpen) {
        var text by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { dialogOpen = false },
            title = { Text(stringResource(com.zcode.android.features.conversation.R.string.goal_set_affordance)) },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("goal_set_field"),
                    singleLine = false,
                    maxLines = 4,
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        dialogOpen = false
                        onSet(text)
                    },
                    enabled = text.isNotBlank(),
                    modifier = Modifier.testTag("goal_set_confirm"),
                ) {
                    Text(stringResource(com.zcode.android.features.conversation.R.string.goal_set_confirm))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { dialogOpen = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}
