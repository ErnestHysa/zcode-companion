package com.zcode.android.features.tasks.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zcode.android.core.ui.components.LocalNotifier
import com.zcode.android.core.ui.components.ZCodeButton
import com.zcode.android.core.ui.components.ZCodeModalBottomSheet
import com.zcode.android.core.ui.components.friendlyFaultMessage
import com.zcode.android.core.ui.components.relativeTimeLabelLive
import com.zcode.android.domain.model.AutomationRow
import com.zcode.android.domain.model.AutomationRunRow
import com.zcode.android.domain.model.OffPeakTaskRow
import com.zcode.android.features.tasks.R
import com.zcode.android.features.tasks.port.AutomationSchedule
import com.zcode.android.features.tasks.port.AutomationsPort
import kotlinx.coroutines.launch

/**
 * GAP-6 + FIELD (2026-10-12) + Phase 4B (J2-target, A3.2+B2.11): the Tasks-tab
 * AUTOMATIONS + OFF-PEAK section with full CRUD at the desktop's shape. The editor
 * lives in [AutomationEditorDialog]; this file owns the list, the lifecycle
 * (pause / continue / restart), the run-history sheet (list, trigger, status filter,
 * open-session-from-run, delete run) and a Run-Now that LANDS its result (busy state,
 * queued/duplicate/failed notice, double-tap guard — B2.11's fire-and-forget is gone).
 */
@Composable
fun AutomationsSection(
    port: AutomationsPort,
    modifier: Modifier = Modifier,
    modelPicker: @Composable (
        current: Pair<String, String>?,
        onPick: (provider: String, model: String) -> Unit,
        onDismiss: () -> Unit,
    ) -> Unit = { _, _, _ -> },
    /** A3.2: open-session-from-run (the run's back-filled sessionId). */
    onOpenTask: (taskId: String) -> Unit = {},
) {
    var loading by remember { mutableStateOf(true) }
    var lists by remember { mutableStateOf<AutomationsPort.Lists?>(null) }
    val scope = rememberCoroutineScope()
    val notifier = LocalNotifier.current
    var busy by remember { mutableStateOf(false) }
    // B2.11: per-automation in-flight set — a second tap while busy is DROPPED.
    var busyIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var editing by remember { mutableStateOf<AutomationRow?>(null) }
    var creating by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<AutomationRow?>(null) }
    var historyFor by remember { mutableStateOf<AutomationRow?>(null) }

    val limitCopy = stringResource(R.string.automations_limit_reached)
    val createFailedCopy = stringResource(R.string.automations_create_failed)
    val queuedCopy = stringResource(R.string.automation_run_queued)
    val duplicateCopy = stringResource(R.string.automation_run_duplicate)
    val runFailedCopy = stringResource(R.string.automation_run_failed)

    suspend fun load() {
        lists = port.lists()
        loading = false
    }
    LaunchedEffect(port) { scope.launch { load() } }

    fun refresh() {
        scope.launch { load() }
    }

    fun workspaceKeyFor(): String = lists?.automations?.firstOrNull()?.workspaceKey ?: "active"

    /** One guarded op per automation id: drops re-entry while in flight. */
    fun guarded(id: String, op: suspend () -> Unit) {
        if (id in busyIds) return
        busyIds = busyIds + id
        scope.launch {
            try { op() } finally { busyIds = busyIds - id }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("automations_section"),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.automations_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            TextButton(onClick = { creating = true }, modifier = Modifier.testTag("automations_new")) {
                Text(stringResource(R.string.automations_new))
            }
        }
        Spacer(Modifier.height(4.dp))
        when {
            loading -> Text(
                stringResource(R.string.automations_loading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // F5: an errored channel is not a capability statement — classify it.
            lists?.error != null -> Text(
                friendlyFaultMessage(lists?.error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("automations_error"),
            )
            lists?.automations?.isEmpty() == true -> Text(
                stringResource(R.string.automations_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("automations_empty"),
            )
            else -> {
                val autos = lists?.automations.orEmpty()
                autos.forEachIndexed { index, automation ->
                    val id = automation.automationId
                    AutomationLine(
                        a = automation,
                        busy = id in busyIds,
                        onRunNow = {
                            guarded(id) {
                                when (port.runNowTyped(id)) {
                                    AutomationsPort.RunNowOutcome.QUEUED -> notifier.show(queuedCopy)
                                    AutomationsPort.RunNowOutcome.DUPLICATE -> notifier.show(duplicateCopy)
                                    AutomationsPort.RunNowOutcome.FAILED -> notifier.show(runFailedCopy, long = true)
                                }
                                load()
                            }
                        },
                        onToggle = { enabled -> guarded(id) { port.setEnabled(id, enabled); load() } },
                        onRestart = { guarded(id) { port.restart(id); load() } },
                        onEdit = { editing = automation },
                        onDelete = { confirmDelete = automation },
                        onHistory = { historyFor = automation },
                    )
                    // B2.18: dividers BETWEEN rows — no dangling divider before the off-peak section.
                    if (index != autos.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            }
        }

        lists?.offPeak?.takeIf { it.isNotEmpty() }?.let { peaks ->
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.automations_offpeak),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            peaks.forEachIndexed { index, task ->
                // Phase 5A (A3.10 as shipped): pause/continue ride the off-peak channel words.
                OffPeakLine(
                    task,
                    busy = task.offPeakTaskId in busyIds,
                    onPause = { guarded(task.offPeakTaskId) { port.pauseOffPeak(task.offPeakTaskId); load() } },
                    onContinue = { guarded(task.offPeakTaskId) { port.continueOffPeak(task.offPeakTaskId); load() } },
                )
                if (index != peaks.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }
    }

    if (creating || editing != null) {
        AutomationEditorDialog(
            existing = editing,
            busy = busy,
            modelPicker = modelPicker,
            onDismiss = {
                creating = false
                editing = null
            },
            onSave = { r ->
                val target = editing
                creating = false
                editing = null
                scope.launch {
                    busy = true
                    val write = AutomationsPort.AutomationWrite(
                        title = r.title, prompt = r.prompt, schedule = r.schedule,
                        modelProvider = r.model?.first, modelId = r.model?.second, mode = r.mode,
                        scheduleRule = r.scheduleRule, recurring = r.recurring, maxRuns = r.maxRuns,
                        endAt = r.endAt, clearScheduleRule = r.clearScheduleRule,
                    )
                    if (target != null) {
                        port.update(target.automationId, write)
                    } else {
                        when (port.createTyped(workspaceKeyFor(), write)) {
                            AutomationsPort.CreateOutcome.OK -> Unit
                            // automation-types.ts AUTOMATION_CREATE_LIMIT (20): a distinct, honest outcome.
                            AutomationsPort.CreateOutcome.LIMIT_REACHED -> notifier.show(limitCopy, long = true)
                            AutomationsPort.CreateOutcome.FAILED -> notifier.show(createFailedCopy, long = true)
                        }
                    }
                    busy = false
                    load()
                }
            },
        )
    }

    confirmDelete?.let { doomed ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.automations_delete_title)) },
            text = { Text(stringResource(R.string.automations_delete_body, doomed.title)) },
            confirmButton = {
                ZCodeButton(
                    onClick = {
                        val id = doomed.automationId
                        confirmDelete = null
                        guarded(id) { port.delete(id); load() }
                    },
                    enabled = doomed.automationId !in busyIds,
                ) { Text(stringResource(R.string.automations_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.automations_delete_cancel)) }
            },
        )
    }

    historyFor?.let { a ->
        AutomationRunsSheet(
            automation = a,
            port = port,
            onDismiss = { historyFor = null },
            onOpenTask = onOpenTask,
        )
    }
}

@Composable
private fun AutomationLine(
    a: AutomationRow,
    busy: Boolean,
    onRunNow: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onRestart: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onHistory: () -> Unit,
) {
    val labels = scheduleLabels()
    // Desktop: terminal lifecycles (completed/failed) never show pause/resume — Restart re-arms.
    val terminal = a.lifecycleStatus == "completed" || a.lifecycleStatus == "failed"
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .testTag("automation_row_${a.automationId}"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).clickable { onEdit() }) {
            Text(a.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                AutomationSchedule.describeRow(a.recurring, a.maxRuns, AutomationSchedule.forRow(a), labels),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("automation_schedule_${a.automationId}"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                a.nextRunAt?.let { next ->
                    Text(
                        stringResource(R.string.automation_next_run, relativeTimeLabelLive(next) ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    stringResource(R.string.automation_runs, a.runCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            a.lastError?.takeIf { it.isNotBlank() }?.let { err ->
                Text(
                    stringResource(R.string.automation_last_error, err.take(80)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        AssistChip(
            onClick = {},
            enabled = false,
            label = {
                Text(
                    stringResource(if (a.enabled) R.string.automation_on else R.string.automation_off) + " · " + a.lifecycleStatus,
                    style = MaterialTheme.typography.labelSmall,
                )
            },
            colors = AssistChipDefaults.assistChipColors(
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledLabelColor = if (a.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            modifier = Modifier.testTag("automation_state_${a.automationId}"),
        )
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onRunNow, enabled = !busy, modifier = Modifier.testTag("automation_run_${a.automationId}")) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp).testTag("automation_run_busy_${a.automationId}"),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(stringResource(R.string.automation_run_now), style = MaterialTheme.typography.labelSmall)
        }
        if (terminal) {
            TextButton(onClick = onRestart, enabled = !busy, modifier = Modifier.testTag("automation_restart_${a.automationId}")) {
                Text(stringResource(R.string.automation_restart), style = MaterialTheme.typography.labelSmall)
            }
        } else {
            TextButton(onClick = { onToggle(!a.enabled) }, enabled = !busy, modifier = Modifier.testTag("automation_pause_${a.automationId}")) {
                Text(stringResource(if (a.enabled) R.string.automation_pause else R.string.automation_resume), style = MaterialTheme.typography.labelSmall)
            }
        }
        TextButton(onClick = onHistory, modifier = Modifier.testTag("automation_history_${a.automationId}")) {
            Text(stringResource(R.string.automation_history), style = MaterialTheme.typography.labelSmall)
        }
        TextButton(onClick = onEdit, modifier = Modifier.testTag("automation_edit_${a.automationId}")) {
            Text(stringResource(R.string.automation_edit), style = MaterialTheme.typography.labelSmall)
        }
        TextButton(
            onClick = onDelete,
            enabled = !busy,
            modifier = Modifier.testTag("automation_delete_${a.automationId}"),
            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            Text(stringResource(R.string.automation_delete), style = MaterialTheme.typography.labelSmall)
        }
    }
}

/**
 * A3.2 (desktop automations.runs.*): the run history for ONE automation — trigger
 * column, status (the dispatch+outcome fold), duration, open-session-from-run,
 * delete run, a status filter and client-side paging ("Show more").
 */
@Composable
private fun AutomationRunsSheet(
    automation: AutomationRow,
    port: AutomationsPort,
    onDismiss: () -> Unit,
    onOpenTask: (String) -> Unit,
) {
    var result by remember(automation.automationId) { mutableStateOf<AutomationsPort.RunsResult?>(null) }
    var filter by remember { mutableStateOf("all") }
    var shown by remember { mutableStateOf(PAGE) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(automation.automationId) { result = port.runs(automation.automationId) }

    ZCodeModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("automation_runs_sheet")) {
            Text(stringResource(R.string.automations_runs_title), style = MaterialTheme.typography.titleMedium)
            Text(automation.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.automations_runs_awake_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((key, label) in listOf(
                    "all" to R.string.automations_runs_filter_all,
                    "running" to R.string.automations_runs_status_running,
                    "succeeded" to R.string.automations_runs_status_succeeded,
                    "failed" to R.string.automations_runs_status_failed,
                )) {
                    FilterChip(
                        selected = filter == key,
                        onClick = { filter = key; shown = PAGE },
                        label = { Text(stringResource(label), style = MaterialTheme.typography.labelMedium) },
                        modifier = Modifier.testTag("automation_runs_filter_$key"),
                    )
                }
            }
            val r = result
            when {
                r == null -> Text(stringResource(R.string.automations_loading), style = MaterialTheme.typography.bodySmall)
                r.error != null -> Text(
                    friendlyFaultMessage(r.error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("automation_runs_error"),
                )
                else -> {
                    val runs = (r.runs ?: emptyList()).filter { filter == "all" || it.status == filter }
                    if (runs.isEmpty()) {
                        Text(
                            stringResource(R.string.automations_runs_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("automation_runs_empty"),
                        )
                    }
                    runs.take(shown).forEach { run ->
                        RunRow(
                            run = run,
                            onOpen = run.sessionId?.let { sid -> { onOpenTask(sid) } },
                            onDelete = {
                                scope.launch {
                                    if (port.deleteRun(run.runId)) result = port.runs(automation.automationId)
                                }
                            },
                        )
                    }
                    if (runs.size > shown) {
                        TextButton(onClick = { shown += PAGE }, modifier = Modifier.testTag("automation_runs_more")) {
                            Text(stringResource(R.string.automations_runs_show_more))
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun RunRow(run: AutomationRunRow, onOpen: (() -> Unit)?, onDelete: () -> Unit) {
    val statusRes = when (run.status) {
        "succeeded" -> R.string.automations_runs_status_succeeded
        "failed" -> R.string.automations_runs_status_failed
        "stopped" -> R.string.automations_runs_status_stopped
        "skipped" -> R.string.automations_runs_status_skipped
        else -> R.string.automations_runs_status_running
    }
    val statusColor = when (run.status) {
        "succeeded" -> MaterialTheme.colorScheme.primary
        "failed" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("automation_run_row")) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                listOfNotNull(
                    relativeTimeLabelLive(run.createdAt),
                    stringResource(if (run.trigger == "manual") R.string.automations_runs_col_trigger_manual else R.string.automations_runs_col_trigger_schedule),
                    run.durationMs?.let { "${it / 1000}s" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stringResource(statusRes), style = MaterialTheme.typography.labelMedium, color = statusColor)
        }
        run.error?.takeIf { it.isNotBlank() }?.let { err ->
            Text(err.take(120), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (onOpen != null) {
                TextButton(onClick = onOpen, modifier = Modifier.testTag("automation_run_open_${run.runId}")) {
                    Text(stringResource(R.string.automations_runs_open_session), style = MaterialTheme.typography.labelSmall)
                }
            }
            TextButton(
                onClick = onDelete,
                modifier = Modifier.testTag("automation_run_delete_${run.runId}"),
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(R.string.automations_runs_delete), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

private const val PAGE = 10

@Composable
private fun OffPeakLine(
    t: OffPeakTaskRow,
    busy: Boolean = false,
    onPause: (() -> Unit)? = null,
    onContinue: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .testTag("offpeak_row_${t.offPeakTaskId}"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(t.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(relativeTimeLabelLive(t.createdAt) ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            buildString {
                append(t.status)
                t.queuePosition?.let { append(" · #").append(it) }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // A3.10: queued/running → Pause; paused → Continue (the status vocabulary is the wire's).
        when (t.status) {
            "queued", "running" -> onPause?.let { cb ->
                TextButton(onClick = cb, enabled = !busy, modifier = Modifier.testTag("offpeak_pause_${t.offPeakTaskId}")) {
                    Text(stringResource(R.string.offpeak_pause))
                }
            }
            "paused" -> onContinue?.let { cb ->
                TextButton(onClick = cb, enabled = !busy, modifier = Modifier.testTag("offpeak_continue_${t.offPeakTaskId}")) {
                    Text(stringResource(R.string.offpeak_continue))
                }
            }
            else -> Unit
        }
    }
}
