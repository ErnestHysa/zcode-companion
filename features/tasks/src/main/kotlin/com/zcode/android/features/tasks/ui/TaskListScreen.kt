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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zcode.android.core.ui.components.AttentionStrip
import com.zcode.android.core.ui.components.ConnectionStatusLine
import com.zcode.android.core.ui.components.SkeletonBar
import com.zcode.android.core.ui.components.taskStatusLabel
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
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            HeaderRow(
                state = state,
                onOpenSettings = onOpenSettings,
            )
            ConnectionStatusLine(
                phase = state.phase,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .testTag("tasks_connection_line")
                    .clickable(onClick = onOpenConnection),
            )
            Spacer(Modifier.height(4.dp))

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
                    TasksUiState.Load.READY -> ReadyBody(state, onTaskClick, onRetry, onRepairPairing)
                }
            }
        }
    }
}

@Composable
private fun HeaderRow(state: TasksUiState, onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "Tasks",
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
        IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("tasks_settings")) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "Settings",
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
                title = "Couldn't refresh tasks",
                message = state.error.userMessage,
                tone = com.zcode.android.core.ui.components.StatusTone.ERROR,
                actionLabel = "Retry",
                onAction = onRetry,
                modifier = Modifier.testTag("tasks_error_strip"),
            )
        }
        EmptyTasksContent(modifier = Modifier.weight(1f), onRefresh = onRefresh)
    }
}

@Composable
private fun ReadyBody(state: TasksUiState, onTaskClick: (Task) -> Unit, onRetry: () -> Unit, onRepairPairing: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        // §14.4.2 stale overlay — cached rows visible + LABELED (never silent stale).
        if (state.stale) {
            AttentionStrip(
                title = "Connection lost — showing cached tasks",
                message = "Reconnecting to ${state.endpointName ?: "your desktop"}…",
                tone = com.zcode.android.core.ui.components.StatusTone.WAITING,
                actionLabel = "Retry",
                onAction = onRetry,
                modifier = Modifier.testTag("tasks_stale_strip"),
            )
        }
        if (state.error != null) {
            AttentionStrip(
                title = "Couldn't refresh tasks",
                message = state.error.userMessage,
                tone = com.zcode.android.core.ui.components.StatusTone.ERROR,
                actionLabel = "Retry",
                onAction = onRetry,
                modifier = Modifier.testTag("tasks_error_strip"),
            )
        }
        // Round-4: stale-pairing repair — Degraded on repeated desktop-disconnected
        // with an old sealed QR. Retry is pointless; re-pairing is the remedy.
        if (state.suggestRepair) {
            AttentionStrip(
                title = "Pairing looks stale",
                message = "The relay no longer sees your desktop for this pairing. " +
                    "On the Mac: open the phone icon in ZCode, press Stop, then show a fresh QR and scan it.",
                tone = com.zcode.android.core.ui.components.StatusTone.WAITING,
                actionLabel = "Re-pair",
                onAction = onRepairPairing,
                modifier = Modifier.testTag("tasks_repair_strip"),
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("tasks_list"),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            state.groups.forEach { group ->
                item(key = "header-${group.workspaceKey}") {
                    WorkspaceHeader(group.label)
                }
                items(group.tasks, key = { "task-${it.taskId}" }) { task ->
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
            text = "WORKSPACE",
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
    val tone = taskStatusTone(task.status)
    val time = RelativeTime.label(task.updatedAtMs)
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
            text = "No tasks yet",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Tasks appear here when you start work in ZCode on your desktop. " +
                "Pull to refresh, or start a task on the desktop.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onRefresh) {
            Text("Refresh")
        }
    }
}

/** Compact relative time for task rows (ui-ux mockups: "2m", "3h", "Yesterday", "2d"). */
object RelativeTime {
    fun label(thenMs: Long, nowMs: Long = System.currentTimeMillis()): String? {
        if (thenMs <= 0) return null
        val delta = nowMs - thenMs
        val minute = 60_000L
        val hour = 60 * minute
        val day = 24 * hour
        return when {
            delta < minute -> "now"
            delta < hour -> "${delta / minute}m"
            delta < day -> "${delta / hour}h"
            delta < 2 * day -> "Yesterday"
            delta < 7 * day -> "${delta / day}d"
            else -> null
        }
    }
}
