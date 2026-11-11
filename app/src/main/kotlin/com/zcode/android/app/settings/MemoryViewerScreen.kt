package com.zcode.android.app.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zcode.android.app.R
import com.zcode.android.core.ui.components.EmptyState
import com.zcode.android.core.ui.components.LoadingSkeleton
import com.zcode.android.core.ui.components.ZCodeModalBottomSheet
import com.zcode.android.core.ui.components.ZCodeTopBar
import com.zcode.android.core.ui.components.friendlyFaultMessage
import com.zcode.android.core.ui.components.rememberZCodeTopBarScroll
import kotlinx.coroutines.launch

/**
 * G75 (vc52 Phase 12): the read-only workspace MEMORY viewer (Settings → Insights).
 *
 * Fresh-asar fact (4a6080ed…): the desktop's project memories live under the
 * desktop home's `.zcode/cli/memories/projects/<workspaceKey>/memory/` tree and
 * `createMemoryService` reads them with LOCAL node:fs — no memory wire verbs
 * exist (the desktop's own viewer shows remoteUnavailable for remote devices).
 * Ernest's build decision (2026-11-11): build the surface ANYWAY — and the
 * existing `file` channel (readdir/readTextFile, raw paths, no workspace
 * confinement) makes it REAL: this viewer reads the desktop's memory tree
 * through the same channel the Files screen uses. Home is derived from the
 * active workspace path's first segment (the desktop home); read-only (INV-9 —
 * no write affordance); the 5 MiB preview cap mirrors the desktop viewer's
 * fileTooLarge face; a read failure on a listed file surfaces the changed/
 * deleted face honestly ("refresh").
 */
interface MemoryPort {
    data class MemoryFile(val path: String, val name: String, val sizeBytes: Long?)

    data class WorkspaceMemories(
        val dir: String,
        val label: String,
        val files: List<MemoryFile>,
        val indexMissing: Boolean,
    )

    data class Snapshot(val workspaces: List<WorkspaceMemories>?, val error: String?)

    data class TextPage(val content: String?, val totalBytes: Long?, val truncated: Boolean, val error: String?)

    suspend fun snapshot(): Snapshot

    suspend fun readText(path: String): TextPage

    companion object {
        /** The desktop viewer's preview cap (settings.memory.viewer.fileTooLarge). */
        const val MAX_PREVIEW_BYTES: Long = 5L * 1024 * 1024
    }
}

@Composable
fun MemoryViewerScreen(
    port: MemoryPort,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var loading by remember { mutableStateOf(true) }
    var snapshot by remember { mutableStateOf<MemoryPort.Snapshot?>(null) }
    var query by remember { mutableStateOf("") }
    var openFile by remember { mutableStateOf<MemoryPort.MemoryFile?>(null) }
    var page by remember { mutableStateOf<MemoryPort.TextPage?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        snapshot = port.snapshot()
        loading = false
        refreshing = false
    }

    androidx.compose.runtime.LaunchedEffect(port) { scope.launch { load() } }

    val topBarScroll = rememberZCodeTopBarScroll()
    Scaffold(
        modifier = modifier.fillMaxSize().nestedScroll(topBarScroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ZCodeTopBar(
                title = stringResource(R.string.memory_viewer_title),
                onBack = onBack,
                backTestTag = "memory_back",
                scrollBehavior = topBarScroll,
            )
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.memory_search_hint)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .testTag("memory_search"),
            )
            androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = {
                    refreshing = true
                    scope.launch { load() }
                },
                modifier = Modifier.fillMaxSize().testTag("memory_pull"),
            ) {
                when {
                    loading && snapshot == null ->
                        LoadingSkeleton(Modifier.fillMaxSize().testTag("memory_loading"), rows = 4)
                    snapshot?.error != null ->
                        com.zcode.android.core.ui.components.ErrorState(
                            title = stringResource(R.string.memory_viewer_unavailable),
                            message = friendlyFaultMessage(snapshot?.error),
                            modifier = Modifier.testTag("memory_error"),
                            onRetry = { refreshing = true; scope.launch { load() } },
                        )
                    snapshot?.workspaces?.isEmpty() == true ->
                        EmptyState(
                            title = stringResource(R.string.memory_viewer_empty),
                            modifier = Modifier.fillMaxSize().testTag("memory_empty"),
                        )
                    else -> {
                        val groups = snapshot?.workspaces ?: return@PullToRefreshBox
                        val filtered = groups.map { ws ->
                            if (query.isBlank()) ws else ws.copy(files = ws.files.filter { it.name.contains(query, ignoreCase = true) })
                        }.filter { query.isBlank() || it.files.isNotEmpty() }
                        if (filtered.isEmpty()) {
                            EmptyState(
                                title = stringResource(R.string.memory_search_empty),
                                modifier = Modifier.fillMaxSize().testTag("memory_search_empty"),
                            )
                        } else {
                            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("memory_list")) {
                                filtered.forEach { ws ->
                                    item(key = "ws/${ws.dir}") {
                                        Text(
                                            ws.label,
                                            style = MaterialTheme.typography.titleSmall,
                                            modifier = Modifier.padding(top = 12.dp, bottom = 2.dp).testTag("memory_ws_${ws.dir.hashCode()}"),
                                        )
                                        if (ws.indexMissing) {
                                            Text(
                                                stringResource(R.string.memory_index_missing),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.testTag("memory_index_missing_${ws.dir.hashCode()}"),
                                            )
                                        }
                                    }
                                    items(ws.files, key = { it.path }) { file ->
                                        MemoryFileRow(file) {
                                            openFile = file
                                            page = null
                                            scope.launch { page = port.readText(file.path) }
                                        }
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                    }
                                }
                                item { androidx.compose.foundation.layout.Spacer(Modifier.padding(vertical = 24.dp)) }
                            }
                        }
                    }
                }
            }
        }
    }

    if (openFile != null) {
        ZCodeModalBottomSheet(onDismissRequest = { openFile = null; page = null }) {
            Column(Modifier.fillMaxWidth().testTag("memory_file_sheet")) {
                Text(
                    openFile?.name ?: "",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                val p = page
                when {
                    p == null -> androidx.compose.material3.LinearProgressIndicator(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("memory_file_loading"),
                    )
                    p.error == null && p.truncated -> Column(Modifier.padding(horizontal = 16.dp)) {
                        Text(
                            stringResource(R.string.memory_file_too_large),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("memory_file_too_large"),
                        )
                        Text(
                            p.content ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp).testTag("memory_file_content"),
                        )
                    }
                    p.error != null -> Column(Modifier.padding(horizontal = 16.dp)) {
                        Text(
                            stringResource(R.string.memory_file_unreadable),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("memory_file_unreadable"),
                        )
                        Text(
                            // The changed/deleted honest face: the listing is stale — refresh.
                            stringResource(R.string.memory_file_changed_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp).testTag("memory_file_changed"),
                        )
                        Text(
                            friendlyFaultMessage(p.error),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    else -> Text(
                        p.content ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .testTag("memory_file_content"),
                    )
                }
                androidx.compose.foundation.layout.Spacer(Modifier.padding(vertical = 16.dp))
            }
        }
    }
}

@Composable
private fun MemoryFileRow(file: MemoryPort.MemoryFile, onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 8.dp)
            .testTag("memory_file_${file.name.hashCode()}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                file.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
