package com.zcode.android.features.tasks.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import com.zcode.android.core.ui.components.ZCodeModalBottomSheet
import androidx.compose.ui.graphics.Color
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
import com.zcode.android.core.ui.components.minTouchTarget
import com.zcode.android.core.ui.components.relativeTimeLabelLive
import com.zcode.android.core.ui.components.taskStatusGlyph
import com.zcode.android.core.ui.components.taskStatusLabel
import androidx.compose.ui.res.stringResource
import com.zcode.android.core.ui.components.taskStatusRail
import com.zcode.android.core.ui.components.taskStatusTone
import com.zcode.android.core.ui.components.toneColor
import com.zcode.android.domain.model.Task
import com.zcode.android.domain.model.TaskStatus
import com.zcode.android.features.tasks.TasksUiState
import com.zcode.android.core.ui.components.ZCodeButton
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import com.zcode.android.core.ui.components.ZCodeMotion
import com.zcode.android.core.ui.components.rememberMotionSpec
import androidx.compose.material3.Scaffold
import com.zcode.android.core.ui.components.ZCodeTopBar
import com.zcode.android.core.ui.components.rememberZCodeTopBarScroll
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.traversalIndex
import com.zcode.android.core.ui.components.EmptyState
import androidx.compose.ui.semantics.Role
import com.zcode.android.core.ui.components.readerWidth

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
    modifier: Modifier = Modifier,
    onRepairPairing: () -> Unit = {},
    /** Phase 7 §18.3: approvals inbox entry with the open-request count. */
    openApprovals: Int = 0,
    onOpenApprovals: () -> Unit = {},
    /** Phase 7 field: create a task in an existing workspace (§6.1 parity). */
    onCreateTask: (String, String, String?, String?) -> Unit = { _, _, _, _ -> },
    /** FIELD (2026-10-12): the model picker the nav layer injects (app module owns
     * the model-selection transport; features:tasks stays transport-free). */
    modelPicker: @Composable (
        current: Pair<String, String>?,
        onPick: (provider: String, model: String) -> Unit,
        onDismiss: () -> Unit,
    ) -> Unit = { _, _, _ -> },
    workspaces: List<com.zcode.android.domain.model.WorkspaceOption> = emptyList(),
    creatingTask: Boolean = false,
    createNotice: String? = null,
    onConsumeCreateNotice: () -> Unit = {},
    onConsumeActionNotice: () -> Unit = {},
    /** GAP-14: per-workspace collapse persistence (null = in-memory for the session). */
    collapseStore: com.zcode.android.features.tasks.ui.WorkspaceCollapseStore? = null,
    collapseExecutor: java.util.concurrent.Executor = com.zcode.android.features.tasks.ui.WorkspaceCollapseStateBackgroundExecutor(),
    /** GAP-6: read-only automations + off-peak lists (null = section hidden). */
    automationsPort: com.zcode.android.features.tasks.port.AutomationsPort? = null,
    /** H2.2: a launcher shortcut asked for a new task — open the create dialog once. */
    requestCreate: Boolean = false,
    onCreateRequestConsumed: () -> Unit = {},
    /** Phase 4B (A3.2): open-session-from-run — the automation run's back-filled sessionId. */
    onOpenTaskId: (taskId: String) -> Unit = {},
    /** Phase 4C (C7): a scratch create — the caller resolves `{name}` → `{path}` then creates. */
    onCreateScratchTask: (name: String, firstText: String, provider: String?, model: String?) -> Unit = { _, _, _, _ -> },
    // ---- Phase 4D (L6.3+J4 / H1.1) ----
    onDeleteTask: (taskId: String) -> Unit = {},
    onRenameTask: (taskId: String, title: String) -> Unit = { _, _ -> },
    onSetTaskArchived: (taskId: String, archived: Boolean) -> Unit = { _, _ -> },
    onSearch: (query: String) -> Unit = {},
    // ---- Phase 5A (A3.8–A3.10): pin / unread / resume / groups / archived viewer ----
    onSetTaskPinned: (taskId: String, pinned: Boolean) -> Unit = { _, _ -> },
    onSetTaskUnread: (task: Task, unread: Boolean) -> Unit = { _, _ -> },
    onResumeTask: (taskId: String) -> Unit = {},
    onMoveTaskToGroup: (taskId: String, groupId: String?) -> Unit = { _, _ -> },
    onCreateGroup: (title: String, color: com.zcode.android.domain.model.TaskGroupColor) -> Unit = { _, _ -> },
    onRenameGroup: (groupId: String, title: String) -> Unit = { _, _ -> },
    onRecolorGroup: (groupId: String, color: com.zcode.android.domain.model.TaskGroupColor) -> Unit = { _, _ -> },
    onDeleteGroup: (groupId: String) -> Unit = {},
    onLoadArchived: () -> Unit = {},
    onUnarchive: (taskId: String) -> Unit = {},
    onDeleteArchived: (taskId: String) -> Unit = {},
    onLoadGroups: () -> Unit = {},
    /** L6.4 (Phase 5A): start a saved workflow in a task (null = hub hidden). */
    onStartWorkflow: ((taskId: String, name: String, scope: String?, args: Map<String, String>) -> Unit)? = null,
) {
    val motion = rememberMotionSpec()
    var showCreate by remember { androidx.compose.runtime.mutableStateOf(false) }
    var searchOpen by remember { androidx.compose.runtime.mutableStateOf(false) }
    // Phase 5A: the management sheets + confirms.
    var showArchived by remember { androidx.compose.runtime.mutableStateOf(false) }
    var moveFor by remember { androidx.compose.runtime.mutableStateOf<com.zcode.android.domain.model.Task?>(null) }
    var groupEditor by remember { androidx.compose.runtime.mutableStateOf<GroupEditorTarget?>(null) }
    var confirmDeleteArchived by remember { androidx.compose.runtime.mutableStateOf<com.zcode.android.domain.model.TaskMeta?>(null) }
    var confirmDeleteGroup by remember { androidx.compose.runtime.mutableStateOf<com.zcode.android.domain.model.TaskGroupInfo?>(null) }
    LaunchedEffect(Unit) { onLoadGroups() }
    // Phase 4D (L6.3+J4): the lifecycle dialog anchors.
    var confirmDelete by remember { androidx.compose.runtime.mutableStateOf<com.zcode.android.domain.model.Task?>(null) }
    var confirmArchive by remember { androidx.compose.runtime.mutableStateOf<com.zcode.android.domain.model.Task?>(null) }
    var renameFor by remember { androidx.compose.runtime.mutableStateOf<com.zcode.android.domain.model.Task?>(null) }
    var renameText by remember { androidx.compose.runtime.mutableStateOf("") }
    LaunchedEffect(requestCreate) {
        if (requestCreate) {
            showCreate = true
            onCreateRequestConsumed()
        }
    }
    val createdNotice = stringResource(R.string.tasks_created)
    // §18.2 workspace switcher selection (endpoint-scoped; null = all workspaces).
    var selectedWorkspaceKey by remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    // GAP-14: per-workspace collapse state, persisted through the store when supplied.
    val collapse = remember(collapseStore) { WorkspaceCollapseState(collapseStore, collapseExecutor) }
    val topBarScroll = rememberZCodeTopBarScroll()
    Scaffold(
        // §30.1 traversal order: M3 Scaffold subcomposes the BODY before the top bar,
        // so without grouping, TalkBack reads rows before the header actions. The
        // traversal group + indexes pin the header first, matching the visual order.
        modifier = modifier
            .fillMaxSize()
            .semantics { isTraversalGroup = true },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ZCodeTopBar(
                title = stringResource(R.string.tasks_title),
                subtitle = state.endpointName,
                titleTestTag = "tasks-title",
                backTestTag = "tasks_back",
                actions = {
                    ApprovalsBellAction(openApprovals, onOpenApprovals)
                    IconButton(onClick = onOpenSettings, modifier = Modifier.size(48.dp).testTag("tasks_settings")) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.tasks_settings_a11y),
                        )
                    }
                    // Phase 5A: the list-level management overflow (archived viewer, new group).
                    var moreOpen by remember { androidx.compose.runtime.mutableStateOf(false) }
                    androidx.compose.foundation.layout.Box {
                        IconButton(onClick = { moreOpen = true }, modifier = Modifier.size(48.dp).testTag("tasks_more")) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.tasks_more_desc))
                        }
                        androidx.compose.material3.DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(stringResource(R.string.tasks_archived_action)) },
                                onClick = { moreOpen = false; showArchived = true; onLoadArchived() },
                                modifier = Modifier.testTag("tasks_menu_archived"),
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(stringResource(R.string.tasks_new_group_action)) },
                                onClick = { moreOpen = false; groupEditor = GroupEditorTarget.Create },
                                modifier = Modifier.testTag("tasks_menu_new_group"),
                            )
                        }
                    }
                },
            )
        },
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .semantics { traversalIndex = 1f },
        ) {
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
            automationsPort?.let { port ->
                AutomationsSection(
                    port = port,
                    modelPicker = modelPicker,
                    onOpenTask = onOpenTaskId,
                    workflowTasks = state.pinned + state.groups.flatMap { it.tasks },
                    onStartWorkflow = onStartWorkflow,
                )
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
                // B3-motion: the loading/empty/ready swap cross-fades (reduced motion → 0ms).
                Crossfade(
                    targetState = state.loadState,
                    animationSpec = tween(motion.ms(ZCodeMotion.CollapseMs)),
                    label = "tasksLoadState",
                ) { loadState ->
                    when (loadState) {
                        TasksUiState.Load.LOADING -> LoadingBody()
                        TasksUiState.Load.EMPTY -> EmptyBody(state, onRetry, onRefresh)
                        TasksUiState.Load.READY ->
                            ReadyBody(
                                state,
                                onTaskClick,
                                onRetry,
                                onRepairPairing,
                                selectedWorkspaceKeyParam = selectedWorkspaceKey,
                                collapse = collapse,
                                onRowDelete = { confirmDelete = it },
                                onRowArchive = { confirmArchive = it },
                                onRowRename = {
                                    renameFor = it
                                    renameText = it.title
                                },
                                onRowPin = { t -> onSetTaskPinned(t.taskId, !t.pinned) },
                                onRowUnread = { t -> onSetTaskUnread(t, !t.isUnread) },
                                onRowResume = { t -> onResumeTask(t.taskId) },
                                onRowMove = { moveFor = it },
                                onEditGroup = { groupEditor = GroupEditorTarget.Edit(it) },
                            )
                    }
                }
            }
                // Phase 4D (H1.1): the task-search toggle (a small FAB above the create FAB).
                androidx.compose.material3.SmallFloatingActionButton(
                    onClick = {
                        searchOpen = !searchOpen
                        if (!searchOpen) onSearch("")
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(start = 16.dp, end = 16.dp, bottom = 88.dp)
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .testTag("tasks_search_fab"),
                ) {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = stringResource(R.string.tasks_search_hint),
                    )
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
        CreateTaskSheet(
            workspaces = workspaces,
            creating = creatingTask,
            onDismiss = { showCreate = false },
            onCreate = { key, text, provider, model ->
                showCreate = false
                if (key.startsWith("scratch:")) {
                    // C7: resolve the scratch name → path, then create against it.
                    onCreateScratchTask(key.removePrefix("scratch:"), text, provider, model)
                } else {
                    onCreateTask(key, text, provider, model)
                }
            },
            modelPicker = modelPicker,
        )
    }
    // ---- Phase 4D (H1.1): the task-search field — a live port query over
    // `zcode-task.listTaskList` (the desktop's own search surface). ----
    if (searchOpen) {
        androidx.compose.material3.OutlinedTextField(
            value = state.searchQuery,
            onValueChange = onSearch,
            placeholder = { Text(stringResource(R.string.tasks_search_hint)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .testTag("tasks_search_field"),
        )
    }

    // H1.1: while a query is active the search results REPLACE the grouped list
    // (the desktop's taskSearch.* behaviour; snippets render under the title).
    if (state.searchQuery.isNotBlank()) {
        if (state.searchResults.isEmpty()) {
            Text(
                stringResource(R.string.tasks_search_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("tasks_search_empty"),
            )
        } else {
            state.searchResults.forEach { hit ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = androidx.compose.ui.semantics.Role.Button) {
                            searchOpen = false
                            onSearch("")
                            // Open the hit (the run/session path — same as the automation run).
                            onOpenTaskId(hit.taskId)
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .testTag("tasks_search_hit_${hit.taskId}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(hit.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        hit.snippet?.let { snippet ->
                            Text(
                                snippet,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
        LaunchedEffect(createNotice) {
            if (createNotice != null) {
                kotlinx.coroutines.delay(4000)
                onConsumeCreateNotice()
            }
        }
        return
    }

    // ---- Phase 4D (L6.3+J4): the lifecycle confirm dialogs ----
    confirmDelete?.let { doomed ->
        // Desktop confirmDialog.taskDelete*: "records cannot be recovered".
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.task_delete_title)) },
            text = { Text(stringResource(R.string.task_delete_body, doomed.title)) },
            confirmButton = {
                com.zcode.android.core.ui.components.ZCodeButton(
                    onClick = { val id = doomed.taskId; confirmDelete = null; onDeleteTask(id) },
                    modifier = Modifier.testTag("task_delete_confirm"),
                ) { Text(stringResource(R.string.task_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) {
                    Text(stringResource(com.zcode.android.core.ui.R.string.action_cancel))
                }
            },
        )
    }
    confirmArchive?.let { target ->
        // Desktop confirmDialog.taskArchive*: "can be restored later".
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmArchive = null },
            title = { Text(stringResource(if (target.archivedAtMs == null) R.string.task_archive_title else R.string.task_unarchive_title)) },
            text = { Text(stringResource(R.string.task_archive_body, target.title)) },
            confirmButton = {
                com.zcode.android.core.ui.components.ZCodeButton(
                    onClick = {
                        val id = target.taskId
                        val to = target.archivedAtMs == null
                        confirmArchive = null
                        onSetTaskArchived(id, to)
                    },
                    modifier = Modifier.testTag("task_archive_confirm"),
                ) { Text(stringResource(R.string.task_archive_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmArchive = null }) {
                    Text(stringResource(com.zcode.android.core.ui.R.string.action_cancel))
                }
            },
        )
    }
    renameFor?.let { target ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renameFor = null },
            title = { Text(stringResource(R.string.task_rename_title)) },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("task_rename_field"),
                )
            },
            confirmButton = {
                com.zcode.android.core.ui.components.ZCodeButton(
                    onClick = {
                        val id = target.taskId
                        val t = renameText
                        renameFor = null
                        if (t.isNotBlank()) onRenameTask(id, t)
                    },
                    enabled = renameText.isNotBlank(),
                    modifier = Modifier.testTag("task_rename_confirm"),
                ) { Text(stringResource(R.string.task_rename_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { renameFor = null }) {
                    Text(stringResource(com.zcode.android.core.ui.R.string.action_cancel))
                }
            },
        )
    }
    // ---- Phase 5A: the archived viewer (A3.8) ----
    if (showArchived) {
        ZCodeModalBottomSheet(onDismissRequest = { showArchived = false }, modifier = Modifier.testTag("archived_sheet")) {
            Text(
                stringResource(R.string.archived_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            val archivedList = state.archived
            when {
                state.archivedLoading -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                }
                archivedList == null -> {
                    Text(
                        stringResource(R.string.archived_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp).testTag("archived_unavailable"),
                    )
                }
                archivedList.isEmpty() -> {
                    Text(
                        stringResource(R.string.archived_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).testTag("archived_empty"),
                    )
                }
                else -> {
                    archivedList.forEach { meta ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(meta.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    relativeTimeLabelLive(meta.updatedAtMs) ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { onUnarchive(meta.taskId) }, modifier = Modifier.testTag("archived_unarchive_${meta.taskId}")) {
                                Text(stringResource(R.string.archived_unarchive))
                            }
                            TextButton(onClick = { confirmDeleteArchived = meta }, modifier = Modifier.testTag("archived_delete_${meta.taskId}")) {
                                Text(stringResource(R.string.archived_delete), color = MaterialTheme.colorScheme.error)
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    confirmDeleteArchived?.let { meta ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDeleteArchived = null },
            title = { Text(stringResource(R.string.archived_delete)) },
            text = { Text(stringResource(R.string.archived_delete_body, meta.title)) },
            confirmButton = {
                com.zcode.android.core.ui.components.ZCodeButton(
                    onClick = { val id = meta.taskId; confirmDeleteArchived = null; onDeleteArchived(id) },
                    modifier = Modifier.testTag("archived_delete_confirm"),
                ) { Text(stringResource(R.string.archived_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteArchived = null }) {
                    Text(stringResource(com.zcode.android.core.ui.R.string.action_cancel))
                }
            },
        )
    }
    // ---- Phase 5A: move-to-group sheet (A3.9) ----
    moveFor?.let { task ->
        ZCodeModalBottomSheet(onDismissRequest = { moveFor = null }, modifier = Modifier.testTag("move_group_sheet")) {
            Text(
                stringResource(R.string.group_move_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            val current = state.groupMembership[task.taskId]
            androidx.compose.material3.ListItem(
                headlineContent = { Text(stringResource(R.string.group_none)) },
                modifier = Modifier
                    .clickable(role = Role.Button) { val id = task.taskId; moveFor = null; onMoveTaskToGroup(id, null) }
                    .testTag("move_group_none"),
            )
            state.taskGroups.forEach { g ->
                androidx.compose.material3.ListItem(
                    leadingContent = {
                        androidx.compose.foundation.layout.Box(
                            Modifier.size(12.dp).background(groupColor(g.color), androidx.compose.foundation.shape.CircleShape),
                        )
                    },
                    headlineContent = { Text(g.title) },
                    trailingContent = { if (current == g.id) Icon(Icons.Filled.Check, contentDescription = null) },
                    modifier = Modifier
                        .clickable(role = Role.Button) { val id = task.taskId; moveFor = null; onMoveTaskToGroup(id, g.id) }
                        .testTag("move_group_${g.id}"),
                )
            }
            androidx.compose.material3.ListItem(
                headlineContent = { Text(stringResource(R.string.group_new)) },
                modifier = Modifier
                    .clickable(role = Role.Button) { moveFor = null; groupEditor = GroupEditorTarget.Create }
                    .testTag("move_group_new"),
            )
            Spacer(Modifier.height(12.dp))
        }
    }
    // ---- Phase 5A: group editor sheet (create / rename+recolor / delete) ----
    groupEditor?.let { target ->
        GroupEditorSheet(
            target = target,
            onDismiss = { groupEditor = null },
            onCreate = { title, color -> groupEditor = null; onCreateGroup(title, color) },
            onSave = { id, title, color -> groupEditor = null; onRenameGroup(id, title); onRecolorGroup(id, color) },
            onDelete = { g -> confirmDeleteGroup = g },
        )
    }
    confirmDeleteGroup?.let { g ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDeleteGroup = null },
            title = { Text(stringResource(R.string.group_delete)) },
            text = { Text(stringResource(R.string.group_delete_body, g.title)) },
            confirmButton = {
                com.zcode.android.core.ui.components.ZCodeButton(
                    onClick = { val id = g.id; confirmDeleteGroup = null; onDeleteGroup(id) },
                    modifier = Modifier.testTag("group_delete_confirm"),
                ) { Text(stringResource(R.string.group_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteGroup = null }) {
                    Text(stringResource(com.zcode.android.core.ui.R.string.action_cancel))
                }
            },
        )
    }
    // The lifecycle one-shot notice (auto-clears like createNotice).
    state.actionNotice?.let { notice ->
        Text(
            notice,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("tasks_action_notice"),
        )
    }
    LaunchedEffect(createNotice, state.actionNotice) {
        if (createNotice != null || state.actionNotice != null) {
            kotlinx.coroutines.delay(4000)
            onConsumeCreateNotice()
            onConsumeActionNotice()
        }
    }
}

/**
 * §6.1 parity sheet: pick an existing workspace, write the first message — the
 * verified `createSession {workspaceId, firstInput{text}}` command does the rest.
 *
 * B1 (Phase 3A): create-task is ONE bottom sheet with TWO pages. The composer's model
 * chip used to open the picker ON TOP of this AlertDialog — two overlays, double scrim,
 * the live "grey skeleton" (E2/F3). Now the chip swaps this sheet's content to the
 * shared picker BODY ([modelPicker] is injected as a body, not a sheet) and a pick — or
 * Back — swaps the form back in. Nothing ever stacks on this sheet.
 */
@Composable
internal fun CreateTaskSheet(
    workspaces: List<com.zcode.android.domain.model.WorkspaceOption>,
    creating: Boolean,
    onDismiss: () -> Unit,
    onCreate: (workspaceKey: String, firstText: String, provider: String?, model: String?) -> Unit,
    modelPicker: @Composable (
        current: Pair<String, String>?,
        onPick: (provider: String, model: String) -> Unit,
        onDismiss: () -> Unit,
    ) -> Unit = { _, _, _ -> },
) {
    var text by remember { androidx.compose.runtime.mutableStateOf("") }
    var expanded by remember { androidx.compose.runtime.mutableStateOf(false) }
    var selected by remember { androidx.compose.runtime.mutableStateOf(workspaces.firstOrNull()) }
    // Phase 4C (C7): the desktop's scratch workspace — "New scratch workspace" in the
    // picker reveals a name field; creating flows the "scratch:<name>" sentinel key
    // through the SAME create callback (the caller resolves the path first).
    var scratchName by remember { androidx.compose.runtime.mutableStateOf("") }
    var scratchSelected by remember { androidx.compose.runtime.mutableStateOf(false) }
    // FIELD (2026-10-12): model picker on creation — the desktop lets you choose the
    // model BEFORE the first message; createSessionRequestedConfigSchema carries
    // {provider, model} as the new session's requested override (command.ts:30-41).
    var pickedModel by remember { androidx.compose.runtime.mutableStateOf<Pair<String, String>?>(null) }
    var pickingModel by remember { androidx.compose.runtime.mutableStateOf(false) }
    com.zcode.android.core.ui.components.ZCodeModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("create_task_sheet"),
    ) {
        if (pickingModel) {
            // Page 2 — the shared picker body INLINE (B1: no second overlay).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { pickingModel = false },
                    modifier = Modifier.testTag("create_picker_back"),
                ) { Text(stringResource(com.zcode.android.core.ui.R.string.action_back)) }
            }
            modelPicker(
                pickedModel,
                { p, m ->
                    pickedModel = p to m
                    pickingModel = false
                },
                { pickingModel = false },
            )
        } else {
            // C7: scrollable — the scratch name field + confirm stay reachable when the
            // sheet grows past a small viewport (the ATD test device's fold).
            Column(
                Modifier
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    stringResource(R.string.create_task_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .padding(vertical = 8.dp)
                        .testTag("create_task_title"),
                )
                if (workspaces.isEmpty()) {
                    Text(
                        stringResource(R.string.create_no_workspaces),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    com.zcode.android.core.ui.components.ZCodeOutlinedButton(
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
                                    scratchSelected = false
                                    expanded = false
                                },
                                modifier = Modifier.testTag("create_workspace_${ws.key.take(20)}"),
                            )
                        }
                        // C7: the desktop's scratch-workspace entry.
                        androidx.compose.material3.HorizontalDivider()
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(R.string.create_scratch_entry)) },
                            onClick = {
                                scratchSelected = true
                                selected = null
                                expanded = false
                            },
                            modifier = Modifier.testTag("create_workspace_scratch"),
                        )
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
                Spacer(Modifier.height(8.dp))
                // Model chip — tap swaps in the shared picker page.
                com.zcode.android.core.ui.components.ZCodeOutlinedButton(
                    onClick = { pickingModel = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("create_model_chip"),
                ) {
                    Text(
                        pickedModel?.let { (p, m) -> "$p/$m" }
                            ?: stringResource(R.string.create_model_default),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (scratchSelected) {
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = scratchName,
                        onValueChange = { scratchName = it },
                        label = { Text(stringResource(R.string.create_scratch_name)) },
                        modifier = Modifier.fillMaxWidth().testTag("create_scratch_name"),
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss, modifier = Modifier.testTag("create_task_cancel")) {
                        Text(stringResource(com.zcode.android.core.ui.R.string.action_cancel))
                    }
                    Spacer(Modifier.width(8.dp))
                    ZCodeButton(
                        onClick = {
                            if (scratchSelected) {
                                onCreate("scratch:$scratchName", text, pickedModel?.first, pickedModel?.second)
                            } else {
                                selected?.let { onCreate(it.key, text, pickedModel?.first, pickedModel?.second) }
                            }
                        },
                        enabled = !creating && text.isNotBlank() &&
                            (if (scratchSelected) scratchName.isNotBlank() else selected != null),
                        modifier = Modifier.testTag("create_task_confirm"),
                    ) { Text(stringResource(R.string.create_confirm)) }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/** "Approval first" (§18.3): the inbox entry with a live "needs you" count badge. */
@Composable
internal fun ApprovalsBellAction(
    openApprovals: Int,
    onOpenApprovals: () -> Unit,
) {
    IconButton(onClick = onOpenApprovals, modifier = Modifier.size(48.dp).testTag("tasks_approvals")) {
        androidx.compose.material3.BadgedBox(
            badge = {
                if (openApprovals > 0) {
                    androidx.compose.material3.Badge(modifier = Modifier.testTag("tasks_approvals_badge")) {
                        // B2.9: the TRUE count — 12 pending approvals never read as "9".
                        Text(openApprovals.toString())
                    }
                }
            },
        ) {
            Icon(
                imageVector = Icons.Filled.Notifications,
                contentDescription = stringResource(R.string.tasks_approvals_a11y),
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
            // E4 (Phase 4G): a failure with NO successful refresh yet IS the
            // post-pairing first sync — the strip is annotated as such ("first sync
            // failed") instead of rendering a bare "Connection failed" beside "No
            // tasks yet" with no link to the just-finished pairing.
            val firstSync = state.lastRefreshAtMs == null
            AttentionStrip(
                title = if (firstSync) stringResource(R.string.tasks_first_sync_title) else stringResource(R.string.tasks_error_title),
                message = if (firstSync) stringResource(R.string.tasks_first_sync_body) else errorUserMessage(state.error),
                tone = com.zcode.android.core.ui.components.StatusTone.ERROR,
                actionLabel = stringResource(com.zcode.android.core.ui.R.string.action_retry),
                onAction = onRetry,
                modifier = Modifier.testTag(
                    if (firstSync) "tasks_first_sync_strip" else "tasks_error_strip",
                )
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
    collapse: WorkspaceCollapseState = remember { WorkspaceCollapseState(null) },
    onRowDelete: ((com.zcode.android.domain.model.Task) -> Unit)? = null,
    onRowArchive: ((com.zcode.android.domain.model.Task) -> Unit)? = null,
    onRowRename: ((com.zcode.android.domain.model.Task) -> Unit)? = null,
    // Phase 5A (A3.8–A3.10)
    onRowPin: ((com.zcode.android.domain.model.Task) -> Unit)? = null,
    onRowUnread: ((com.zcode.android.domain.model.Task) -> Unit)? = null,
    onRowResume: ((com.zcode.android.domain.model.Task) -> Unit)? = null,
    onRowMove: ((com.zcode.android.domain.model.Task) -> Unit)? = null,
    onEditGroup: ((com.zcode.android.domain.model.TaskGroupInfo) -> Unit)? = null,
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
            // E4 (Phase 4G): a failure with NO successful refresh yet IS the
            // post-pairing first sync — the strip is annotated as such ("first sync
            // failed") instead of rendering a bare "Connection failed" beside "No
            // tasks yet" with no link to the just-finished pairing.
            val firstSync = state.lastRefreshAtMs == null
            AttentionStrip(
                title = if (firstSync) stringResource(R.string.tasks_first_sync_title) else stringResource(R.string.tasks_error_title),
                message = if (firstSync) stringResource(R.string.tasks_first_sync_body) else errorUserMessage(state.error),
                tone = com.zcode.android.core.ui.components.StatusTone.ERROR,
                actionLabel = stringResource(com.zcode.android.core.ui.R.string.action_retry),
                onAction = onRetry,
                modifier = Modifier.testTag(
                    if (firstSync) "tasks_first_sync_strip" else "tasks_error_strip",
                )
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
        // M2: reader width discipline — the task list caps at the reading ceiling and
        // centers on wide windows (the two-pane split is Phase 4D's redesign cluster).
        val reader = Modifier.readerWidth()
        // Phase 5A: the row helper carries every lifecycle + management entry point.
        val rowFor: @Composable (Task, Modifier) -> Unit = { task, rowModifier ->
            TaskRow(
                task,
                onClick = { onTaskClick(task) },
                modifier = rowModifier,
                onDelete = onRowDelete?.let { cb -> { cb(task) } },
                onArchive = onRowArchive?.let { cb -> { cb(task) } },
                onRename = onRowRename?.let { cb -> { cb(task) } },
                onPin = onRowPin?.let { cb -> { cb(task) } },
                onUnread = onRowUnread?.let { cb -> { cb(task) } },
                onResume = onRowResume?.let { cb -> { cb(task) } },
                onMove = onRowMove?.let { cb -> { cb(task) } },
            )
        }
        LazyColumn(
            modifier = reader.fillMaxSize().align(Alignment.CenterHorizontally).testTag("tasks_list"),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            // A3.8: the pinned section — a wire fact (`pinned`), cross-workspace, first.
            val pinnedShown = selectedWorkspaceKeyParam?.let { key -> state.pinned.filter { it.workspace.raw == key } } ?: state.pinned
            if (pinnedShown.isNotEmpty()) {
                item(key = "header-pinned") {
                    SectionLabel(
                        text = stringResource(R.string.tasks_pinned_section),
                        count = pinnedShown.size,
                        modifier = Modifier.animateItem().testTag("tasks_pinned_header"),
                    )
                }
                items(pinnedShown, key = { "pinned-${it.workspace.raw.hashCode()}-${it.taskId}" }) { task ->
                    rowFor(task, Modifier.animateItem())
                    HorizontalHairline()
                }
            }
            shownGroups.forEach { group ->
                // GAP-14 (desktop WorkspaceGroupedTasksSection collapsedGroupIds): the
                // header toggles the group; a collapsed group hides its tasks but the
                // count badge still shows the total.
                val collapsed = collapse.isCollapsed(group.workspaceKey)
                item(key = "header-${group.workspaceKey}") {
                    WorkspaceHeader(
                        modifier = Modifier.animateItem(),
                        workspaceKey = group.workspaceKey,
                        label = group.label,
                        taskCount = group.tasks.size,
                        collapsed = collapsed,
                        onToggle = { collapse.toggle(group.workspaceKey) },
                    )
                }
                if (!collapsed) {
                    // A3.9: custom groups render as colored sub-sections inside the
                    // workspace; members are the host's membership (groupMembership).
                    val byGroup = group.tasks.groupBy { state.groupMembership[it.taskId] }
                    state.taskGroups.forEach { g ->
                        val members = byGroup[g.id] ?: return@forEach
                        item(key = "group-${group.workspaceKey.hashCode()}-${g.id}") {
                            GroupHeader(
                                group = g,
                                count = members.size,
                                modifier = Modifier.animateItem(),
                                onEdit = onEditGroup?.let { cb -> { cb(g) } },
                            )
                        }
                        items(members, key = { "task-${group.workspaceKey.hashCode()}-${g.id}-${it.taskId}" }) { task ->
                            rowFor(task, Modifier.animateItem())
                            HorizontalHairline()
                        }
                    }
                    // FIELD (2026-09-30, vc13 crash log): the SAME taskId can appear under MORE than one
                    // workspace group (desktop cross-listing) — a bare taskId key collided and
                    // crashed LazyColumn on fast scroll ("Key was already used").
                    items(byGroup[null] ?: emptyList(), key = { "task-${group.workspaceKey.hashCode()}-${it.taskId}" }) { task ->
                        rowFor(task, Modifier.animateItem())
                        HorizontalHairline()
                    }
                }
            }
        }
    }
}

/** A3.8: the pinned section label (count badge like the workspace header). */
@Composable
private fun SectionLabel(text: String, count: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.PushPin,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(8.dp))
        Text("$count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A3.9: a custom task group's sub-header — colored dot + title + count (desktop taskGroup.*). */
@Composable
private fun GroupHeader(
    group: com.zcode.android.domain.model.TaskGroupInfo,
    count: Int,
    modifier: Modifier = Modifier,
    onEdit: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 4.dp, top = 4.dp, bottom = 2.dp)
            .testTag("task_group_header_${group.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier
                .size(10.dp)
                .background(groupColor(group.color), androidx.compose.foundation.shape.CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(group.title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("$count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        onEdit?.let { cb ->
            IconButton(onClick = cb, modifier = Modifier.minTouchTarget().testTag("task_group_edit_${group.id}")) {
                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.group_edit_desc), modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** The desktop's seven group colors as theme-stable swatches (hue-only is never the sole cue — the title labels it). */
@Composable
internal fun groupColor(color: com.zcode.android.domain.model.TaskGroupColor): Color = when (color) {
    com.zcode.android.domain.model.TaskGroupColor.GRAY -> MaterialTheme.colorScheme.outline
    com.zcode.android.domain.model.TaskGroupColor.RED -> MaterialTheme.colorScheme.error
    com.zcode.android.domain.model.TaskGroupColor.ORANGE -> Color(0xFFE08A1E)
    com.zcode.android.domain.model.TaskGroupColor.YELLOW -> Color(0xFFC9A400)
    com.zcode.android.domain.model.TaskGroupColor.GREEN -> Color(0xFF3B8F4A)
    com.zcode.android.domain.model.TaskGroupColor.BLUE -> MaterialTheme.colorScheme.primary
    com.zcode.android.domain.model.TaskGroupColor.PURPLE -> MaterialTheme.colorScheme.tertiary
}

@Composable
internal fun groupColorLabel(color: com.zcode.android.domain.model.TaskGroupColor): String = stringResource(
    when (color) {
        com.zcode.android.domain.model.TaskGroupColor.GRAY -> R.string.group_color_gray
        com.zcode.android.domain.model.TaskGroupColor.RED -> R.string.group_color_red
        com.zcode.android.domain.model.TaskGroupColor.ORANGE -> R.string.group_color_orange
        com.zcode.android.domain.model.TaskGroupColor.YELLOW -> R.string.group_color_yellow
        com.zcode.android.domain.model.TaskGroupColor.GREEN -> R.string.group_color_green
        com.zcode.android.domain.model.TaskGroupColor.BLUE -> R.string.group_color_blue
        com.zcode.android.domain.model.TaskGroupColor.PURPLE -> R.string.group_color_purple
    },
)

/** Phase 5A: what the group editor sheet is for. */
internal sealed class GroupEditorTarget {
    data object Create : GroupEditorTarget()
    data class Edit(val group: com.zcode.android.domain.model.TaskGroupInfo) : GroupEditorTarget()
}

/**
 * A3.9: the custom-group editor — name + one of the desktop's seven colors
 * (fixed rows-of-N chips, the F4-RIG dialog rule), with delete for existing groups.
 */
@Composable
private fun GroupEditorSheet(
    target: GroupEditorTarget,
    onDismiss: () -> Unit,
    onCreate: (String, com.zcode.android.domain.model.TaskGroupColor) -> Unit,
    onSave: (String, String, com.zcode.android.domain.model.TaskGroupColor) -> Unit,
    onDelete: (com.zcode.android.domain.model.TaskGroupInfo) -> Unit,
) {
    val existing = (target as? GroupEditorTarget.Edit)?.group
    var title by remember(existing) { androidx.compose.runtime.mutableStateOf(existing?.title ?: "") }
    var color by remember(existing) { androidx.compose.runtime.mutableStateOf(existing?.color ?: com.zcode.android.domain.model.TaskGroupColor.GRAY) }
    ZCodeModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("group_editor_sheet")) {
        Text(
            stringResource(if (existing == null) R.string.group_create_title else R.string.group_edit_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        androidx.compose.material3.OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text(stringResource(R.string.group_title_hint)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .testTag("group_title_field"),
        )
        Text(
            stringResource(R.string.group_color),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        // Fixed rows-of-4 chip grids (F4-RIG: FlowRow never idles inside dialogs).
        val palette = com.zcode.android.domain.model.TaskGroupColor.PALETTE
        palette.chunked(4).forEachIndexed { rowIndex, row ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                row.forEach { c ->
                    androidx.compose.material3.FilterChip(
                        selected = color == c,
                        onClick = { color = c },
                        label = { Text(groupColorLabel(c), style = MaterialTheme.typography.labelSmall, maxLines = 1) },
                        leadingIcon = {
                            androidx.compose.foundation.layout.Box(
                                Modifier.size(10.dp).background(groupColor(c), androidx.compose.foundation.shape.CircleShape),
                            )
                        },
                        modifier = Modifier.testTag("group_color_${c.wire}_$rowIndex"),
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (existing != null) {
                TextButton(onClick = { onDelete(existing) }, modifier = Modifier.testTag("group_edit_delete")) {
                    Text(stringResource(R.string.group_delete), color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.weight(1f))
            com.zcode.android.core.ui.components.ZCodeButton(
                onClick = {
                    if (existing == null) onCreate(title, color)
                    else onSave(existing.id, title, color)
                },
                enabled = title.isNotBlank(),
                modifier = Modifier.testTag(if (existing == null) "group_create_confirm" else "group_save_confirm"),
            ) {
                Text(stringResource(if (existing == null) R.string.group_create else R.string.group_save))
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun WorkspaceHeader(
    workspaceKey: String,
    label: String,
    taskCount: Int,
    collapsed: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val actionWord = stringResource(if (collapsed) R.string.tasks_a11y_expand else R.string.tasks_a11y_collapse)
    val a11y = stringResource(R.string.tasks_group_a11y, label, taskCount, actionWord)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("tasks_group_collapse_${workspaceKey.hashCode()}")
            .semantics(mergeDescendants = true) { contentDescription = a11y },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.tasks_workspace_kicker),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.width(8.dp))
        // GAP-14: the count badge survives collapsing — totals stay visible.
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.testTag("tasks_group_count_${workspaceKey.hashCode()}"),
        ) {
            Text(
                text = taskCount.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        Icon(
            imageVector = if (collapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
            contentDescription = null, // the merged header description carries the state
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun TaskRow(
    task: Task,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Phase 4D (L6.3+J4): the lifecycle entry points (null = read-only context). */
    onDelete: (() -> Unit)? = null,
    onArchive: (() -> Unit)? = null,
    onRename: (() -> Unit)? = null,
    /** Phase 5A (A3.8–A3.10): pin / unread / resume / move-to-group (null = hidden). */
    onPin: (() -> Unit)? = null,
    onUnread: (() -> Unit)? = null,
    onResume: (() -> Unit)? = null,
    onMove: (() -> Unit)? = null,
) {
    val rail = taskStatusRail(task.status)
    val label = taskStatusLabel(task.status)
    val glyph = taskStatusGlyph(task.status)
    val tone = taskStatusTone(task.status)
    val time = relativeTimeLabelLive(task.updatedAtMs)
    val meta = listOfNotNull(task.workspaceLabel ?: task.workspace.raw.substringAfterLast('/').ifBlank { null }, time)
        .joinToString(" · ")
    val unreadA11y = stringResource(R.string.task_unread_a11y)
    val pinnedA11y = stringResource(R.string.task_pinned_a11y)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(role = Role.Button, onClick = onClick)
            .drawBehind { drawRect(rail, size = size.copy(width = railWidthPx())) }
            .padding(start = 16.dp + 3.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)
            .testTag("task_row_${task.taskId}")
            .semantics(mergeDescendants = true) {
                // A3.8: unread/pinned are part of the merged description (never hue-only).
                val flags = listOfNotNull(if (task.isUnread) unreadA11y else null, if (task.pinned) pinnedA11y else null)
                contentDescription = "${task.title}, $label${if (meta.isBlank()) "" else ", $meta"}" +
                    (if (flags.isEmpty()) "" else ", " + flags.joinToString(", "))
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Phase 4D (L6.3+J4): the lifecycle overflow ("⋯") — Delete / Archive /
        // Rename; the confirms live in the screen (desktop confirmDialog.* copy).
        if (onDelete != null || onArchive != null || onRename != null || onPin != null || onUnread != null || onResume != null || onMove != null) {
            var menuOpen by remember { androidx.compose.runtime.mutableStateOf(false) }
            androidx.compose.foundation.layout.Box {
                androidx.compose.material3.IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier
                        .minTouchTarget()
                        .testTag("task_actions_${task.taskId}"),
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.task_actions_desc),
                    )
                }
                androidx.compose.material3.DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    // Phase 5A (A3.8): pin / unread first — the two most-used management verbs.
                    onPin?.let { cb ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(if (task.pinned) R.string.task_unpin else R.string.task_pin)) },
                            onClick = { menuOpen = false; cb() },
                            modifier = Modifier.testTag("task_menu_pin_${task.taskId}"),
                        )
                    }
                    onUnread?.let { cb ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(if (task.isUnread) R.string.task_mark_read else R.string.task_mark_unread)) },
                            onClick = { menuOpen = false; cb() },
                            modifier = Modifier.testTag("task_menu_unread_${task.taskId}"),
                        )
                    }
                    // A3.10: resume a FINISHED task's session (idle/completed/error — never a running one).
                    if (onResume != null && task.status != com.zcode.android.domain.model.TaskStatus.RUNNING && task.status != com.zcode.android.domain.model.TaskStatus.WAITING) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(R.string.task_resume)) },
                            onClick = { menuOpen = false; onResume() },
                            modifier = Modifier.testTag("task_menu_resume_${task.taskId}"),
                        )
                    }
                    // A3.9: move-to-group (pinned tasks are refused by the host — hidden here).
                    if (onMove != null && !task.pinned) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(R.string.task_move_to_group)) },
                            onClick = { menuOpen = false; onMove() },
                            modifier = Modifier.testTag("task_menu_move_${task.taskId}"),
                        )
                    }
                    onRename?.let { cb ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(R.string.task_rename_confirm)) },
                            onClick = { menuOpen = false; cb() },
                            modifier = Modifier.testTag("task_menu_rename_${task.taskId}"),
                        )
                    }
                    onArchive?.let { cb ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(R.string.task_archive_confirm)) },
                            onClick = { menuOpen = false; cb() },
                            modifier = Modifier.testTag("task_menu_archive_${task.taskId}"),
                        )
                    }
                    onDelete?.let { cb ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(R.string.task_delete_confirm)) },
                            onClick = { menuOpen = false; cb() },
                            modifier = Modifier.testTag("task_menu_delete_${task.taskId}"),
                        )
                    }
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // A3.8: the unread dot (wire `unreadAt`) + the pin glyph — both also in the a11y text.
                if (task.isUnread) {
                    androidx.compose.foundation.layout.Box(
                        Modifier
                            .size(8.dp)
                            .background(MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.CircleShape)
                            .testTag("task_unread_${task.taskId}"),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                if (task.pinned) {
                    Icon(
                        imageVector = Icons.Filled.PushPin,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp).testTag("task_pinned_${task.taskId}"),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (task.isUnread) FontWeight.Bold else null,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // J4: the per-task change stats the wire always carried (+N −N on the row,
            // desktop taskList.changeStats) — decoded since Phase 2, never rendered.
            task.summary?.let { sum ->
                if (sum.files > 0) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "+${sum.additions} −${sum.deletions}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("task_changes_${task.taskId}"),
                    )
                }
            }
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
    EmptyState(
        title = stringResource(R.string.tasks_empty_title),
        message = stringResource(R.string.tasks_empty_body),
        modifier = modifier.testTag("tasks_empty"),
        action = {
            TextButton(onClick = onRefresh) {
                Text(stringResource(com.zcode.android.core.ui.R.string.action_refresh))
            }
        },
    )
}

