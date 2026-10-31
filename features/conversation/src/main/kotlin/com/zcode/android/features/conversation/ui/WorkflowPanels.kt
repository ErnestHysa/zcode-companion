package com.zcode.android.features.conversation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zcode.android.core.ui.components.StatusTone
import com.zcode.android.core.ui.components.ZCodeButton
import com.zcode.android.core.ui.components.ZCodeModalBottomSheet
import com.zcode.android.core.ui.components.toneColor
import com.zcode.android.core.ui.components.relativeTimeLabelLive
import com.zcode.android.domain.model.WorkflowRunEvent
import com.zcode.android.domain.model.WorkflowRunStatus
import com.zcode.android.domain.model.WorkflowRunSummary
import com.zcode.android.features.conversation.ConversationViewModel
import com.zcode.android.features.conversation.R

/**
 * A3.3 (gaps-and-polish Phase 5A): the workflow runs the desktop renders live and the phone
 * never showed. [WorkflowRunsPanel] is a pinned header under the goal/todo panels (live runs
 * first); tapping a run opens [WorkflowRunSheet] — the run's status line + its event timeline
 * (`conversationWorkflowRunEventsV4`, paged forward by afterSequence). Absent/unavailable
 * data renders nothing / an honest line, never a guess (INV-11).
 */
@Composable
fun WorkflowRunsPanel(
    runs: List<WorkflowRunSummary>,
    onOpen: (WorkflowRunSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (runs.isEmpty()) return
    val ordered = runs.sortedWith(compareByDescending<WorkflowRunSummary> { it.status.isLive }.thenByDescending { it.updatedAtMs ?: 0 })
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.medium)
            .padding(12.dp)
            .testTag("workflow_runs_panel"),
    ) {
        Text(
            stringResource(R.string.workflow_runs_title),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        ordered.forEach { run ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { onOpen(run) }
                    .padding(vertical = 6.dp)
                    .testTag("workflow_run_${run.runId}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    run.label ?: stringResource(R.string.workflow_run_untitled),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                WorkflowStatusChip(run.status, run.statusRaw)
            }
        }
    }
}

/** Status as text + tone (never hue-only — the label carries it). */
@Composable
fun WorkflowStatusChip(status: WorkflowRunStatus, raw: String?, modifier: Modifier = Modifier) {
    val (label, tone) = when (status) {
        WorkflowRunStatus.PENDING -> stringResource(R.string.workflow_status_pending) to StatusTone.WAITING
        WorkflowRunStatus.RUNNING -> stringResource(R.string.workflow_status_running) to StatusTone.ACCENT
        WorkflowRunStatus.COMPLETED -> stringResource(R.string.workflow_status_completed) to StatusTone.SUCCESS
        WorkflowRunStatus.ERRORED -> stringResource(R.string.workflow_status_errored) to StatusTone.ERROR
        WorkflowRunStatus.STOPPED -> stringResource(R.string.workflow_status_stopped) to StatusTone.MUTED
        WorkflowRunStatus.UNKNOWN -> (raw ?: stringResource(R.string.workflow_status_unknown)) to StatusTone.MUTED
    }
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = toneColor(tone),
        modifier = modifier.testTag("workflow_status_${status.wire.ifEmpty { "unknown" }}"),
    )
}

/** The run detail sheet: status line, failure/stop reason, resumable tag, event timeline with load-more. */
@Composable
fun WorkflowRunSheet(
    open: ConversationViewModel.OpenWorkflowRun,
    onDismiss: () -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * G3 (vc51): the desktop's `resumeWorkflowRun` gate (`workflowResumeGate.ts`) — a
     * resumable run's sheet offers Resume; null = the caller has no resume path (the
     * action hides, the passive "Resumable" tag still renders).
     */
    onResume: ((WorkflowRunSummary) -> Unit)? = null,
    /** G3: the desktop's `amendWorkflowRunSettings` (omitted field = keep current). */
    onAmendSettings: ((WorkflowRunSummary, subagentModel: String?, maxConcurrency: Int?) -> Unit)? = null,
) {
    ZCodeModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier.testTag("workflow_run_sheet")) {
        val run = open.run
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                run.label ?: stringResource(R.string.workflow_run_untitled),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).testTag("workflow_sheet_title"),
            )
            Spacer(Modifier.width(8.dp))
            WorkflowStatusChip(run.status, run.statusRaw)
        }
        val meta = buildList {
            run.updatedAtMs?.let { relativeTimeLabelLive(it)?.let(::add) }
            if (run.resumable) add(stringResource(R.string.workflow_resumable))
            run.stopReason?.let { add(stringResource(R.string.workflow_stop_reason, it)) }
        }
        if (meta.isNotEmpty()) {
            Text(
                meta.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        (run.failureMessage ?: run.failureCode)?.let { failure ->
            Text(
                stringResource(R.string.workflow_failure, failure),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("workflow_sheet_failure"),
            )
        }
        // G3: the run sheet's actions — Resume (a resumable run) and Run settings (amend).
        if (run.resumable && onResume != null || onAmendSettings != null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                if (run.resumable && onResume != null) {
                    TextButton(
                        onClick = { onResume(run) },
                        modifier = Modifier.testTag("workflow_sheet_resume"),
                    ) { Text(stringResource(R.string.workflow_resume)) }
                    Spacer(Modifier.width(8.dp))
                }
                if (onAmendSettings != null) {
                    var amendOpen by remember { mutableStateOf(false) }
                    TextButton(
                        onClick = { amendOpen = true },
                        modifier = Modifier.testTag("workflow_sheet_settings"),
                    ) { Text(stringResource(R.string.workflow_settings)) }
                    if (amendOpen) {
                        WorkflowAmendSheet(
                            onDismiss = { amendOpen = false },
                            onApply = { model, concurrency ->
                                amendOpen = false
                                onAmendSettings(run, model, concurrency)
                            },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.workflow_events_title),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        when {
            open.unavailable -> Text(
                stringResource(R.string.workflow_events_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp).testTag("workflow_events_unavailable"),
            )
            open.events.isEmpty() && !open.loading -> Text(
                stringResource(R.string.workflow_events_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp).testTag("workflow_events_empty"),
            )
            else -> LazyColumn(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightInBounded(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(open.events, key = { it.sequence }) { ev -> EventLine(ev) }
                if (open.hasMore) {
                    item(key = "more") {
                        TextButton(onClick = onLoadMore, enabled = !open.loading, modifier = Modifier.testTag("workflow_events_more")) {
                            Text(stringResource(R.string.workflow_events_more))
                        }
                    }
                }
            }
        }
        if (open.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun EventLine(ev: WorkflowRunEvent) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("workflow_event_${ev.sequence}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "#${ev.sequence}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(ev.type, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            if (ev.truncated) {
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.workflow_event_truncated), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (ev.headline != ev.type) {
            Text(ev.headline, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    }
}

/**
 * G3: the amend sheet — the desktop's `amendWorkflowRunSettings` payload (both fields
 * three-state: blank = omitted = keep the current value; `providerId/modelId[$level]`
 * canonical string; concurrency is an integer ≥ 1). Deliberately NOT CAS-guarded — the
 * workflowRuns plane is revision-free (workflow-run-settings-command.ts).
 */
@Composable
private fun WorkflowAmendSheet(
    onDismiss: () -> Unit,
    onApply: (subagentModel: String?, maxConcurrency: Int?) -> Unit,
) {
    ZCodeModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("workflow_amend_sheet")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.workflow_settings_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            var model by remember { mutableStateOf("") }
            var concurrency by remember { mutableStateOf("") }
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text(stringResource(R.string.workflow_settings_model)) },
                supportingText = { Text(stringResource(R.string.workflow_settings_model_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("workflow_amend_model"),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = concurrency,
                onValueChange = { concurrency = it.filter { ch -> ch.isDigit() } },
                label = { Text(stringResource(R.string.workflow_settings_concurrency)) },
                supportingText = { Text(stringResource(R.string.workflow_settings_blank_keeps)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("workflow_amend_concurrency"),
            )
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("workflow_amend_cancel")) {
                    Text(stringResource(com.zcode.android.core.ui.R.string.action_cancel))
                }
                Spacer(Modifier.width(8.dp))
                val concurrencyValue = concurrency.toIntOrNull()
                val modelValue = model.trim().takeIf { it.isNotEmpty() }
                ZCodeButton(
                    onClick = { onApply(modelValue, concurrencyValue) },
                    enabled = modelValue != null || concurrencyValue != null,
                    modifier = Modifier.testTag("workflow_amend_apply"),
                ) { Text(stringResource(R.string.workflow_settings_apply)) }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** The sheet's timeline is bounded so the sheet itself stays scrollable on small screens. */
private fun Modifier.heightInBounded(): Modifier = this.heightIn(max = 360.dp)
