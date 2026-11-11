package com.zcode.android.features.tasks.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zcode.android.core.ui.components.ZCodeButton
import com.zcode.android.core.ui.components.ZCodeModalBottomSheet
import com.zcode.android.core.ui.components.relativeTimeLabelLive
import com.zcode.android.domain.model.SavedWorkflow
import com.zcode.android.domain.model.SavedWorkflowList
import com.zcode.android.domain.model.SavedWorkflowRun
import com.zcode.android.domain.model.Task
import com.zcode.android.domain.model.WorkflowRunStatus
import com.zcode.android.features.tasks.R
import com.zcode.android.features.tasks.port.AutomationsPort
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import kotlinx.coroutines.launch

/**
 * L6.4 (gaps-and-polish Phase 5A): the saved-workflow hub the phone lacked — the desktop's
 * `workflows` tab shares the automations page, so the hub lives under the automations
 * section. Rows carry name · scope · last-run state (`listSavedWorkflowRuns`, newest per
 * name); **Run…** opens [RunWorkflowSheet]: the declared args (required first, typed
 * hints) + the target task, then `startSavedWorkflow {name, scope, args}` in that session.
 * Unavailable (null) hides the hub; zero workflows renders the empty line; invalid files
 * render as an honest warning (never silently dropped).
 */
@Composable
fun WorkflowsHub(
    port: AutomationsPort,
    tasks: List<Task>,
    onStartWorkflow: (taskId: String, name: String, scope: String?, args: Map<String, String>) -> Unit,
    modifier: Modifier = Modifier,
    reloadKey: Any? = null,
    /** F-SPAM: the hub's CONNECTED truth — the load re-runs on the flip (V2 pattern). */
    connected: Boolean = true,
    /** G42-P23: the received workspaces for MOVE-to-project (null = the action hides). */
    workspaces: List<com.zcode.android.domain.model.WorkspaceOption> = emptyList(),
) {
    var list by remember { mutableStateOf<SavedWorkflowList?>(null) }
    var runs by remember { mutableStateOf<List<SavedWorkflowRun>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var runFor by remember { mutableStateOf<SavedWorkflow?>(null) }
    // G42-P23 (vc52 Phase 12): the management verbs (the desktop's
    // workflows/updateMeta | delete | move — Rename / Delete / Move-to-project).
    var deleteFor by remember { mutableStateOf<SavedWorkflow?>(null) }
    var renameFor by remember { mutableStateOf<SavedWorkflow?>(null) }
    var moveFor by remember { mutableStateOf<SavedWorkflow?>(null) }
    var managementError by remember { mutableStateOf<String?>(null) }
    // G42-P23: the management copies are captured in composition (stringResource is
    // @Composable — the coroutines use the captured values).
    val managementUnavailableCopy = stringResource(R.string.workflow_management_unavailable)
    val managementRefusedFmt = stringResource(R.string.workflow_management_refused, "%1\$s")
    LaunchedEffect(port, reloadKey, connected) {
        if (!connected) return@LaunchedEffect
        list = port.savedWorkflows()
        runs = port.savedWorkflowRuns() ?: emptyList()
        loaded = true
    }
    suspend fun reload() {
        list = port.savedWorkflows()
        runs = port.savedWorkflowRuns() ?: emptyList()
    }
    val hub = list ?: return // unavailable → hidden (§13.3 hidden fallback)
    if (!loaded) return
    Column(modifier.fillMaxWidth().testTag("workflows_hub")) {
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.workflows_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (hub.workflows.isEmpty()) {
            Text(
                stringResource(R.string.workflows_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp).testTag("workflows_empty"),
            )
        }
        hub.workflows.forEachIndexed { index, wf ->
            val last = runs.filter { it.name == wf.name }.maxByOrNull { it.updatedAtMs }
            WorkflowRow(
                wf, last,
                onRun = { runFor = wf },
                onDelete = { deleteFor = wf },
                onRename = { renameFor = wf },
                onMove = { moveFor = wf },
                moveAvailable = workspaces.isNotEmpty(),
            )
            if (index != hub.workflows.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        }
        if (hub.invalid.isNotEmpty()) {
            Text(
                stringResource(R.string.workflows_invalid, hub.invalid.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 4.dp).testTag("workflows_invalid"),
            )
        }
    }
    runFor?.let { wf ->
        RunWorkflowSheet(
            workflow = wf,
            tasks = tasks,
            onDismiss = { runFor = null },
            onStart = { taskId, args -> runFor = null; onStartWorkflow(taskId, wf.name, wf.scope, args) },
        )
    }
    // ---- G42-P23: the management sheets (Delete confirm / Rename dialog / Move sheet) ----
    managementError?.let {
        Text(
            it,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 4.dp).testTag("workflow_management_error"),
        )
    }
    deleteFor?.let { wf ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text(stringResource(R.string.workflow_delete_title, wf.name)) },
            text = { Text(stringResource(R.string.workflow_delete_body, wf.path)) },
            confirmButton = {
                val unavailable = stringResource(R.string.workflow_management_unavailable)
                val refusedFmt = stringResource(R.string.workflow_management_refused, "%1\$s")
                TextButton(onClick = {
                    val target = wf
                    deleteFor = null
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main.immediate).launch {
                        val result = port.deleteSavedWorkflow(target.name)
                        managementError = when {
                            result == null -> unavailable
                            !result.ok -> refusedFmt.replace("%1\$s", result.reason ?: "unknown")
                            else -> null
                        }
                        reload()
                    }
                }, modifier = Modifier.testTag("workflow_delete_confirm")) {
                    Text(stringResource(R.string.workflow_delete_action), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text(stringResource(com.zcode.android.core.ui.R.string.action_cancel)) } },
        )
    }
    renameFor?.let { wf ->
        var name by remember(wf.name) { mutableStateOf(wf.description) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renameFor = null },
            title = { Text(stringResource(R.string.workflow_rename_title, wf.name)) },
            text = {
                Column {
                    androidx.compose.material3.OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("workflow_rename_field"),
                    )
                }
            },
            confirmButton = {
                val unavailable = stringResource(R.string.workflow_management_unavailable)
                val refusedFmt = stringResource(R.string.workflow_management_refused, "%1\$s")
                TextButton(enabled = name.isNotBlank(), onClick = {
                    val target = wf
                    val newDescription = name
                    renameFor = null
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main.immediate).launch {
                        val result = port.renameSavedWorkflow(target.name, newDescription)
                        managementError = when {
                            result == null -> unavailable
                            !result.ok -> refusedFmt.replace("%1\$s", result.reason ?: "unknown")
                            else -> null
                        }
                        reload()
                    }
                }, modifier = Modifier.testTag("workflow_rename_save")) {
                    Text(stringResource(R.string.workflow_rename_action))
                }
            },
            dismissButton = { TextButton(onClick = { renameFor = null }) { Text(stringResource(com.zcode.android.core.ui.R.string.action_cancel)) } },
        )
    }
    moveFor?.let { wf ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { moveFor = null },
            title = { Text(stringResource(R.string.workflow_move_title, wf.name)) },
            text = {
                Column {
                    if (workspaces.isEmpty()) {
                        Text(stringResource(R.string.workflow_move_none))
                    } else {
                        workspaces.take(8).forEach { ws ->
                            androidx.compose.material3.ListItem(
                                headlineContent = { Text(ws.label) },
                                supportingContent = { Text(ws.key) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val unavailable = managementUnavailableCopy
                                        val refusedFmt = managementRefusedFmt
                                        val target = wf
                                        val wsKey = ws.key
                                        moveFor = null
                                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main.immediate).launch {
                                            val result = port.moveSavedWorkflow(target.name, wsKey)
                                            managementError = when {
                                                result == null -> unavailable
                                                !result.ok -> refusedFmt.replace("%1\$s", result.reason ?: "unknown")
                                                else -> null
                                            }
                                            reload()
                                        }
                                    }
                                    .testTag("workflow_move_target_${ws.key}"),
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { moveFor = null }) { Text(stringResource(com.zcode.android.core.ui.R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun WorkflowRow(
    wf: SavedWorkflow,
    last: SavedWorkflowRun?,
    onRun: () -> Unit,
    onDelete: () -> Unit = {},
    onRename: () -> Unit = {},
    onMove: () -> Unit = {},
    moveAvailable: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .testTag("workflow_row_${wf.name}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(wf.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(if (wf.scope == "global") R.string.workflow_scope_global else R.string.workflow_scope_project),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (wf.description.isNotBlank()) {
                Text(wf.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                lastRunLabel(last),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("workflow_lastrun_${wf.name}"),
            )
        }
        TextButton(onClick = onRun, modifier = Modifier.testTag("workflow_run_${wf.name}")) {
            Text(stringResource(R.string.workflow_run_action))
        }
        // G42-P23: the management overflow (Delete / Rename / Move-to-project).
        var menuOpen by remember { mutableStateOf(false) }
        androidx.compose.material3.IconButton(
            onClick = { menuOpen = true },
            modifier = Modifier.testTag("workflow_manage_${wf.name}"),
        ) {
            androidx.compose.material.icons.Icons.Filled.MoreVert.let {
                androidx.compose.material3.Icon(imageVector = it, contentDescription = stringResource(R.string.workflow_manage_a11y))
            }
        }
        androidx.compose.material3.DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(stringResource(R.string.workflow_rename_action)) },
                onClick = { menuOpen = false; onRename() },
                modifier = Modifier.testTag("workflow_menu_rename"),
            )
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(stringResource(R.string.workflow_move_action)) },
                onClick = { menuOpen = false; onMove() },
                enabled = moveAvailable,
                modifier = Modifier.testTag("workflow_menu_move"),
            )
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(stringResource(R.string.workflow_delete_action), color = MaterialTheme.colorScheme.error) },
                onClick = { menuOpen = false; onDelete() },
                modifier = Modifier.testTag("workflow_menu_delete"),
            )
        }
    }
}

/** Desktop `workflows.lastRun.*`: never / completed / errored / running / stopped (+ relative time). */
@Composable
internal fun lastRunLabel(last: SavedWorkflowRun?): String {
    if (last == null) return stringResource(R.string.workflow_lastrun_never)
    val state = when (last.status) {
        WorkflowRunStatus.COMPLETED -> stringResource(R.string.workflow_lastrun_completed)
        WorkflowRunStatus.ERRORED -> stringResource(R.string.workflow_lastrun_errored)
        WorkflowRunStatus.RUNNING, WorkflowRunStatus.PENDING -> stringResource(R.string.workflow_lastrun_running)
        WorkflowRunStatus.STOPPED -> stringResource(R.string.workflow_lastrun_stopped)
        WorkflowRunStatus.UNKNOWN -> last.statusRaw ?: stringResource(R.string.workflow_lastrun_never)
    }
    val time = relativeTimeLabelLive(last.updatedAtMs)
    return if (time == null) state else "$state · $time"
}

/**
 * Run with required args (desktop `workflows.run.*`): required fields first with their
 * declared type as the hint; the target task is the session the command targets.
 */
@Composable
internal fun RunWorkflowSheet(
    workflow: SavedWorkflow,
    tasks: List<Task>,
    onDismiss: () -> Unit,
    onStart: (taskId: String, args: Map<String, String>) -> Unit,
) {
    val values = remember(workflow.name) { mutableStateOf(workflow.args.associate { it.name to (it.default?.toString()?.trim('"') ?: "") }) }
    var targetTaskId by remember(workflow.name) { mutableStateOf(tasks.firstOrNull()?.taskId) }
    val requiredMissing = workflow.requiredArgs.any { values.value[it.name].isNullOrBlank() }
    ZCodeModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("run_workflow_sheet")) {
        Text(
            stringResource(R.string.workflow_run_title, workflow.name),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        workflow.whenToUse?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        val ordered = workflow.requiredArgs + workflow.args.filterNot { it.required }
        ordered.forEach { arg ->
            OutlinedTextField(
                value = values.value[arg.name] ?: "",
                onValueChange = { values.value = values.value + (arg.name to it) },
                label = { Text(if (arg.required) "${arg.name} *" else arg.name) },
                placeholder = { Text(arg.description ?: arg.type) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("workflow_arg_${arg.name}"),
            )
        }
        Text(
            stringResource(R.string.workflow_target_task),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        if (tasks.isEmpty()) {
            Text(
                stringResource(R.string.workflow_no_tasks),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp).testTag("workflow_no_tasks"),
            )
        }
        tasks.take(8).forEach { t ->
            ListItem(
                headlineContent = { Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingContent = { if (targetTaskId == t.taskId) Text("✓", color = MaterialTheme.colorScheme.primary) },
                modifier = Modifier
                    .clickable(role = Role.RadioButton) { targetTaskId = t.taskId }
                    .testTag("workflow_target_${t.taskId}"),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            ZCodeButton(
                onClick = { targetTaskId?.let { onStart(it, values.value.filterValues { v -> v.isNotBlank() }) } },
                enabled = targetTaskId != null && !requiredMissing,
                modifier = Modifier.testTag("workflow_start_confirm"),
            ) { Text(stringResource(R.string.workflow_run_action)) }
        }
        Spacer(Modifier.height(8.dp))
    }
}
