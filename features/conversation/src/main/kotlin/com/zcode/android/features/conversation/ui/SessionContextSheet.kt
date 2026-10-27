package com.zcode.android.features.conversation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
) {
    ZCodeModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier.testTag("session_context_sheet")) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = SheetMaxHeight)
                .verticalScroll(rememberScrollState())
                .testTag("session_context_content"),
        ) {
            // §11.1: the context-window meter rides first — it is context about context.
            ContextUsagePanel(
                usage = sessionState?.usage,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
            // I2.3: the session summary capsule (duration · goal iteration · background
            // tasks · subagents) — nothing renders for an empty session.
            SessionSummaryPanel(
                sessionState = sessionState,
                rows = rows,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
            // D2: the goal + todo surfaces — EXPANDED inside the sheet (the user opened
            // it to read context); both headers remain true toggles.
            sessionState?.goal?.let { goal ->
                GoalPanel(goal, modifier = Modifier.padding(horizontal = 16.dp, vertical = 3.dp))
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

private val SheetMaxHeight = 640.dp
