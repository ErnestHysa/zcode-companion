package com.zcode.android.features.files.ui

import androidx.compose.ui.res.stringResource
import com.zcode.android.features.files.R

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zcode.android.features.files.fileIconVector
import com.zcode.android.features.files.fileKind
import com.zcode.android.features.files.fileKindLabel
import com.zcode.android.features.files.port.FilesPort
import kotlinx.coroutines.launch

/**
 * §23.4 read-only workspace file browser (Phase 9.2): folder navigation via
 * protocol-returned paths (INV-16), paged text viewer, binary/media handling
 * (download card only — preview rides the desktop media surfaces), bounded
 * download-to-device with share-out (INV-9: read-only everywhere).
 */
@Composable
fun FilesScreen(
    port: FilesPort,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val root = port.workspaceRoot()
    // Directory navigation stack (INV-16: only protocol-returned paths are pushed).
    var stack by remember { mutableStateOf(listOf<String>()) }
    var entries by remember { mutableStateOf(listOf<com.zcode.android.domain.model.WorkspaceFileEntry>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var viewer by remember { mutableStateOf<String?>(null) }
    var textState by remember { mutableStateOf(listOf<com.zcode.android.domain.model.WorkspaceTextSlice>()) }
    var textLoading by remember { mutableStateOf(false) }
    // B2.16: TYPED state — display strings no longer double as the state machine
    // (the old machine routed its dialog on downloadState.startsWith("binary")).
    var downloadState by remember { mutableStateOf<DownloadUi?>(null) }

    fun currentPath(): String = stack.lastOrNull() ?: root.orEmpty()

    suspend fun load(path: String) {
        loading = true
        error = null
        val result = port.list(path)
        entries = result.entries
        error = result.error
        loading = false
    }

    val noWorkspaceMsg = stringResource(R.string.files_no_workspace)
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (root != null) load(root)
        else error = noWorkspaceMsg
    }

    Column(modifier = modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // B2.18: the route already renders "Workspace files" — no second header.
            Text("read-only", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            if (stack.isNotEmpty()) {
                AssistChip(
                    onClick = {
                        scope.launch {
                            stack = stack.dropLast(1)
                            load(currentPath())
                        }
                    },
                    label = { Text(stringResource(R.string.files_up)) },
                )
            }
        }
        // Breadcrumb (GAP-15: the visited chain root..current, each segment tappable —
        // display-only path rendering, INV-16).
        val crumbs = remember(stack) { listOf(root.orEmpty()) + stack }
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            crumbs.forEachIndexed { index, crumbPath ->
                if (index > 0) {
                    Text(
                        "/",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val isCurrent = index == crumbs.lastIndex
                Text(
                    crumbPath.substringAfterLast('/').ifBlank { crumbPath.ifBlank { "—" } },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isCurrent) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .testTag("files_crumb_$index")
                        .let { m ->
                            if (isCurrent) m else m.clickable {
                                scope.launch {
                                    stack = stack.take(index)
                                    load(currentPath())
                                }
                            }
                        },
                )
            }
        }

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        if (loading) {
            Text(stringResource(R.string.files_loading), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (viewer == null) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(entries.size, key = { entries[it].path }) { index ->
                    val entry = entries[index]
                    val kind = fileKind(entry.name, entry.isDirectory)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .testTag("files_row_${entry.path}")
                            .clickable {
                                if (entry.isDirectory) {
                                    scope.launch {
                                        stack = stack + entry.path
                                        load(entry.path)
                                    }
                                } else {
                                    viewer = entry.path
                                    textState = emptyList()
                                    textLoading = true
                                    scope.launch {
                                        val first = port.readText(entry.path, 0L, TEXT_CHUNK)
                                        if (first.slice != null) {
                                            textState = listOf(first.slice)
                                            // Binary files never render as text (protocol-gated).
                                            if (first.slice.isBinary) {
                                                downloadState = DownloadUi.Binary("binary file — use Download")
                                            } else {
                                                downloadState = first.error?.let { DownloadUi.Failed(it) }
                                            }
                                        }
                                        textLoading = false
                                    }
                                }
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // GAP-15: desktop tree-row affordances — chevron on directories
                        // (row tap toggles/toggles-into), a typed file icon for files
                        // (WorkspaceFileTreeRowView resolveIconName families).
                        if (entry.isDirectory) {
                            Icon(
                                imageVector = Icons.Filled.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(2.dp))
                        }
                        Icon(
                            imageVector = fileIconVector(kind),
                            contentDescription = fileKindLabel(kind),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(entry.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = true))
                    }
                }
            }
        } else {
            // Text viewer (paged, INV-13: slice-by-slice loads only).
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { viewer = null; downloadState = null }, label = { Text(stringResource(com.zcode.android.core.ui.R.string.action_back)) })
                AssistChip(
                    onClick = {
                        scope.launch {
                            downloadState = DownloadUi.Downloading
                            val result = port.download(viewer!!)
                            downloadState = when {
                                result.file != null -> DownloadUi.Done(result.bytes)
                                else -> DownloadUi.Failed(result.error ?: "download failed")
                            }
                        }
                    },
                    modifier = Modifier.testTag("files_download_button"),
                    label = { Text(stringResource(R.string.files_download)) },
                )
            }
            downloadState?.let { state ->
                Text(
                    state.display,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(
                        when (state) {
                            is DownloadUi.Binary -> "files_binary_notice"
                            else -> "files_download_state"
                        },
                    ),
                )
            }
            if (textLoading) {
                Text(stringResource(R.string.files_loading), style = MaterialTheme.typography.bodySmall)
            }
            Surface(tonalElevation = 1.dp, modifier = Modifier.fillMaxSize()) {
                LazyColumn(Modifier.padding(6.dp)) {
                    items(textState.size) { index ->
                        val slice = textState[index]
                        Text(
                            slice.content,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (index == textState.lastIndex && slice.hasMore && !slice.isBinary) {
                            TextButton(onClick = {
                                scope.launch {
                                    textLoading = true
                                    val next = port.readText(viewer!!, slice.offset + slice.bytesRead, TEXT_CHUNK)
                                    next.slice?.let { textState = textState + it }
                                    textLoading = false
                                }
                            }) {
                                Text(stringResource(R.string.files_load_more, slice.offset + slice.bytesRead, slice.totalBytes))
                            }
                        }
                    }
                }
            }
        }
    }

    // Malformed/error download notice dialog (never a silent failure). The routing
    // is TYPED (B2.16) — no display-string prefix matching.
    val dialogState = downloadState
    if (dialogState is DownloadUi.Binary && viewer == null && error == null) {
        AlertDialog(
            onDismissRequest = { downloadState = null },
            modifier = Modifier.testTag("files_download_dialog"),
            confirmButton = { TextButton(onClick = { downloadState = null }) { Text(stringResource(R.string.files_ok)) } },
            title = { Text(stringResource(R.string.files_dialog_title)) },
            text = { Text(dialogState.message) },
        )
    }
}

private const val TEXT_CHUNK: Long = 64L * 1024

/** B2.16: the download/notice states — display text derives from the type. */
private sealed interface DownloadUi {
    data object Downloading : DownloadUi
    data class Done(val bytes: Long) : DownloadUi
    data class Failed(val message: String) : DownloadUi

    /** A binary file opened in the (text) viewer — the "use Download" notice. */
    data class Binary(val message: String) : DownloadUi

    val display: String
        get() = when (this) {
            Downloading -> "downloading…"
            is Done -> "downloaded $bytes bytes — share sheet opened"
            is Failed -> message
            is Binary -> message
        }
}
