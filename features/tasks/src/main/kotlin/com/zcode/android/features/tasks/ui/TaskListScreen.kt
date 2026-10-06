package com.zcode.android.features.tasks.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.Icon
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zcode.android.core.ui.components.AttentionStrip
import com.zcode.android.core.ui.components.StatusTone
import com.zcode.android.core.ui.components.ConnectionStatusLine
import com.zcode.android.core.ui.components.SkeletonBar
import com.zcode.android.features.tasks.R
import com.zcode.android.core.ui.components.errorUserMessage
import com.zcode.android.core.ui.components.relativeTimeLabel
import com.zcode.android.core.ui.components.taskStatusGlyph
import com.zcode.android.core.ui.components.taskStatusLabel
import androidx.compose.ui.res.stringResource
import com.zcode.android.core.ui.components.taskStatusRail
import com.zcode.android.core.ui.components.taskStatusTone
import com.zcode.android.core.ui.components.toneColor
import com.zcode.android.domain.model.Task
import com.zcode.android.domain.model.TaskStatus
import com.zcode.android.features.tasks.TasksUiState

/**
 * Task list (§43.2-6.2; ui-ux screen-spec 03/04/14). Dense rows, left status rail,
 * hairline dividers, workspace section headers; pull-to-refresh; every §18.3 state
 * (loading/empty/ready/stale/error) rendered — INV-11.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskListScreen(
    state: TasksUiState,
    onTaskClick: (Task) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenConnection: () -> Unit,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    onRepairPairing: () -> Unit = {},
    /** Phase 7 §18.3: approvals inbox entry with the open-request count. */
    openApprovals: Int = 0,
    onOpenApprovals: () -> Unit = {},
    /** Phase 7 field: create a task in an existing workspace (§6.1 parity). */
    onCreateTask: (String, String) -> Unit = { _, _ -> },
    workspaces: List<com.zcode.android.domain.model.WorkspaceOption> = emptyList(),
    creatingTask: Boolean = false,
    createNotice: String? = null,
    onConsumeCreateNotice: () -> Unit = {},
    /** GAP-14: per-workspace collapse persistence (null = in-memory for the session). */
    collapseStore: com.zcode.android.features.tasks.ui.WorkspaceCollapseStore? = null,
    modifier: Modifier = Modifier,
) {
    var showCreate by remember { androidx.compose.runtime.mutableStateOf(false) }
    val createdNotice = stringResource(R.string.tasks_created)
    // §18.2 workspace switcher selection (endpoint-scoped; null = all workspaces).
    var selectedWorkspaceKey by remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            HeaderRow(
                state = state,
                onOpenSettings = onOpenSettings,
                openApprovals = openApprovals,
                onOpenApprovals = onOpenApprovals,
            )
            ConnectionStatusLine(
                phase = state.phase,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .heightIn(min = 48.dp)
                    .testTag("tasks_connection_line")
                    .clickable(onClick = onOpenConnection),
            )
            // §18.2 workspace switcher (endpoint-scoped): chips = All + one per
            // workspace; selecting one filters the grouped list (U22 switcher semantics).
            if (workspaces.isNotEmpty()) {
                val selectedKey = selectedWorkspaceKey
                androidx.compose.foundation.lazy.LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .testTag("tasks_workspace_switcher"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        FilterChip(
                            selected = selectedWorkspaceKey == null,
                            onClick = { selectedWorkspaceKey = null },
                            label = { Text(stringResource(R.string.tasks_ws_all)) },
                            modifier = Modifier.testTag("tasks_ws_all"),
                        )
                    }
                    for (ws in workspaces) {
                        item(key = ws.key) {
                            FilterChip(
                                selected = selectedWorkspaceKey == ws.key,
                                onClick = { selectedWorkspaceKey = if (selectedWorkspaceKey == ws.key) null else ws.key },
                                label = { Text(ws.label, maxLines = 1) },
                                modifier = Modifier.testTag("tasks_ws_${ws.key.hashCode()}"),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))

            Box(Modifier.fillMaxSize()) {
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = onRefresh,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("tasks_pull_container"),
            ) {
                when (state.loadState) {
                    TasksUiState.Load.LOADING -> LoadingBody()
                    TasksUiState.Load.EMPTY -> EmptyBody(state, onRetry, onRefresh)
                    TasksUiState.Load.READY ->
                        ReadyBody(state, onTaskClick, onRetry, onRepairPairing, selectedWorkspaceKeyParam = selectedWorkspaceKey)
                }
            }
                // §6.1 parity: create tasks in existing workspaces — the "+ New task"
                // entry lives where the eye lands after scanning the list.
                androidx.compose.material3.ExtendedFloatingActionButton(
                    onClick = { showCreate = true },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                        .testTag("tasks_create_fab"),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Text(stringResource(if (creatingTask) R.string.tasks_creating else R.string.tasks_new_task))
                }
            }
            createNotice?.let { notice ->
                AttentionStrip(
                    title = notice,
                    tone = if (notice == createdNotice) StatusTone.INFO else StatusTone.ERROR,
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .testTag("tasks_create_notice"),
                )
            }
        }
    }

    if (showCreate) {
        CreateTaskDialog(
            workspaces = workspaces,
            creating = creatingTask,
            onDismiss = { showCreate = false },
            onCreate = { key, text ->
                showCreate = false
                onCreateTask(key, text)
            },
        )
    }
    LaunchedEffect(createNotice) {
        if (createNotice != null) {
            kotlinx.coroutines.delay(4000)
            onConsumeCreateNotice()
        }
    }
}

/**
 * §6.1 parity dialog: pick an existing workspace, write the first message — the
 * verified `createSession {workspaceId, firstInput{text}}` command does the rest.
 */
@Composable
internal fun CreateTaskDialog(
    workspaces: List<com.zcode.android.domain.model.WorkspaceOption>,
    creating: Boolean,
    onDismiss: () -> Unit,
    onCreate: (workspaceKey: String, firstText: String) -> Unit,
) {
    var text by remember { androidx.compose.runtime.mutableStateOf("") }
    var expanded by remember { androidx.compose.runtime.mutableStateOf(false) }
    var selected by remember { androidx.compose.runtime.mutableStateOf(workspaces.firstOrNull()) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.create_task_title)) },
        text = {
            Column {
                if (workspaces.isEmpty()) {
                    Text(
                        stringResource(R.string.create_no_workspaces),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    androidx.compose.material3.OutlinedButton(
                        onClick = { expanded = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("create_workspace_picker"),
                    ) {
                        Text(
                            selected?.label ?: stringResource(R.string.create_pick_workspace),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    androidx.compose.material3.DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        modifier = Modifier.testTag("create_workspace_menu"),
                    ) {
                        for (ws in workspaces) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(ws.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                onClick = {
                                    selected = ws
                                    expanded = false
                                },
                                modifier = Modifier.testTag("create_workspace_${ws.key.take(20)}"),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(stringResource(R.string.create_hint)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("create_task_text"),
                    maxLines = 4,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { selected?.let { onCreate(it.key, text) } },
                enabled = !creating && text.isNotBlank() && selected != null,
                modifier = Modifier.testTag("create_task_confirm"),
            ) { Text(stringResource(R.string.create_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("create_task_cancel")) { Text(stringResource(com.zcode.android.core.ui.R.string.action_cancel)) }
        },
    )
}

@Composable
private fun HeaderRow(
    state: TasksUiState,
    onOpenSettings: () -> Unit,
    openApprovals: Int,
    onOpenApprovals: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.tasks_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.testTag("tasks-title"),
            )
            state.endpointName?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // §18.3 "approval first": the inbox entry with a live "needs you" count.
        IconButton(onClick = onOpenApprovals, modifier = Modifier.size(48.dp).testTag("tasks_approvals")) {
            Box {
                Icon(
                    imageVector = Icons.Filled.Notifications,
                    contentDescription = stringResource(R.string.tasks_approvals_a11y),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (openApprovals > 0) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .size(16.dp)
                            .background(MaterialTheme.colorScheme.error, androidx.compose.foundation.shape.CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = openApprovals.coerceAtMost(9).toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onError,
                            modifier = Modifier.testTag("tasks_approvals_badge"),
                        )
                    }
                }
            }
        }
        IconButton(onClick = onOpenSettings, modifier = Modifier.size(48.dp).testTag("tasks_settings")) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = stringResource(R.string.tasks_settings_a11y),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LoadingBody() {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        // Workspace header skeleton + 5 row skeletons (§18.3: skeletons over spinners).
        SkeletonBar(Modifier.width(120.dp).testTag("tasks_skeleton"))
        Spacer(Modifier.height(16.dp))
        repeat(5) {
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
                Box(Modifier.width(3.dp).height(40.dp).background(MaterialTheme.colorScheme.surfaceVariant))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SkeletonBar(Modifier.fillMaxWidth(0.7f))
                    SkeletonBar(Modifier.fillMaxWidth(0.4f))
                }
            }
        }
    }
}

@Composable
private fun EmptyBody(state: TasksUiState, onRetry: () -> Unit, onRefresh: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        if (state.error != null) {
            AttentionStrip(
                title = stringResource(R.string.tasks_error_title),
                message = errorUserMessage(state.error),
                tone = com.zcode.android.core.ui.components.StatusTone.ERROR,
                actionLabel = stringResource(com.zcode.android.core.ui.R.string.action_retry),
                onAction = onRetry,
                modifier = Modifier.testTag("tasks_error_strip"),
            )
        }
        EmptyTasksContent(modifier = Modifier.weight(1f), onRefresh = onRefresh)
    }
}

@Composable
private fun ReadyBody(
    state: TasksUiState,
    onTaskClick: (Task) -> Unit,
    onRetry: () -> Unit,
    onRepairPairing: () -> Unit,
    selectedWorkspaceKeyParam: String? = null,
) {
    Column(Modifier.fillMaxSize()) {
        // §14.4.2 stale overlay — cached rows visible + LABELED (never silent stale).
        if (state.stale) {
            AttentionStrip(
                title = stringResource(R.string.tasks_stale_title),
                message = stringResource(
                    R.string.tasks_reconnecting_to,
                    state.endpointName ?: stringResource(R.string.tasks_your_desktop),
                ),
                tone = com.zcode.android.core.ui.components.StatusTone.WAITING,
                actionLabel = stringResource(com.zcode.android.core.ui.R.string.action_retry),
                onAction = onRetry,
                modifier = Modifier.testTag("tasks_stale_strip"),
            )
        }
        if (state.error != null) {
            AttentionStrip(
                title = stringResource(R.string.tasks_error_title),
                message = errorUserMessage(state.error),
                tone = com.zcode.android.core.ui.components.StatusTone.ERROR,
                actionLabel = stringResource(com.zcode.android.core.ui.R.string.action_retry),
                onAction = onRetry,
                modifier = Modifier.testTag("tasks_error_strip"),
            )
        }
        // Round-4: stale-pairing repair — Degraded on repeated desktop-disconnected
        // with an old sealed QR. Retry is pointless; re-pairing is the remedy.
        if (state.suggestRepair) {
            AttentionStrip(
                title = stringResource(R.string.tasks_repair_title),
                message = stringResource(R.string.tasks_repair_body),
                tone = com.zcode.android.core.ui.components.StatusTone.WAITING,
                actionLabel = stringResource(R.string.tasks_repair_action),
                onAction = onRepairPairing,
                modifier = Modifier.testTag("tasks_repair_strip"),
            )
        }
        val shownGroups = selectedWorkspaceKeyParam
            ?.let { key -> state.groups.filter { it.workspaceKey == key } }
            ?: state.groups
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("tasks_list"),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            shownGroups.forEach { group ->
                item(key = "header-${group.workspaceKey}") {
                    WorkspaceHeader(group.label)
                }
                // FIELD (2026-09-30, vc13 crash log): the SAME taskId can appear under MORE than one
                // workspace group (desktop cross-listing) — a bare taskId key collided and
                // crashed LazyColumn on fast scroll ("Key was already used").
                items(group.tasks, key = { "task-${group.workspaceKey.hashCode()}-${it.taskId}" }) { task ->
                    TaskRow(task, onClick = { onTaskClick(task) })
                    HorizontalHairline()
                }
            }
        }
    }
}

@Composable
private fun WorkspaceHeader(label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.tasks_workspace_kicker),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics(mergeDescendants = true) {},
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
fun TaskRow(task: Task, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val rail = taskStatusRail(task.status)
    val label = taskStatusLabel(task.status)
    val glyph = taskStatusGlyph(task.status)
    val tone = taskStatusTone(task.status)
    val time = relativeTimeLabel(task.updatedAtMs)
    val meta = listOfNotNull(task.workspaceLabel ?: task.workspace.raw.substringAfterLast('/').ifBlank { null }, time)
        .joinToString(" · ")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .drawBehind { drawRect(rail, size = size.copy(width = railWidthPx())) }
            .padding(start = 16.dp + 3.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)
            .testTag("task_row_${task.taskId}")
            .semantics(mergeDescendants = true) {
                contentDescription = "${task.title}, $label${if (meta.isBlank()) "" else ", $meta"}"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = task.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = glyph,
                    contentDescription = null, // decorative; the merged row description carries the status
                    tint = toneColor(tone),
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = toneColor(tone),
                )
                if (meta.isNotBlank()) {
                    Text(
                        text = "  ·  $meta",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.railWidthPx(): Float = 3.dp.toPx()

@Composable
private fun HorizontalHairline() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/** Empty state per ui-ux 04: short copy, next step, no fake create-task FAB (§2.2 N7). */
@Composable
fun EmptyTasksContent(modifier: Modifier = Modifier, onRefresh: () -> Unit = {}) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp)
            .testTag("tasks_empty"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.tasks_empty_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.tasks_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onRefresh) {
            Text(stringResource(com.zcode.android.core.ui.R.string.action_refresh))
        }
    }
}

