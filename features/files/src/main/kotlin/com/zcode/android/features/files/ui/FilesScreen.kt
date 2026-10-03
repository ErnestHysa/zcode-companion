package com.zcode.android.features.files.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
    var downloadState by remember { mutableStateOf<String?>(null) }

    fun currentPath(): String = stack.lastOrNull() ?: root.orEmpty()

    suspend fun load(path: String) {
        loading = true
        error = null
        val result = port.list(path)
        entries = result.entries
        error = result.error
        loading = false
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (root != null) load(root)
        else error = "No workspace connected."
    }

    Column(modifier = modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Files", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
                    label = { Text("Up") },
                )
            }
        }
        // Breadcrumb (display-only path rendering, INV-16).
        Text(
            currentPath().ifBlank { "—" },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        if (loading) {
            Text("Loading…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (viewer == null) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(entries.size, key = { entries[it].path }) { index ->
                    val entry = entries[index]
                    Row(
                        Modifier
                            .fillMaxWidth()
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
                                            if (first.slice.isBinary) downloadState = "binary file — use Download"
                                        } else {
                                            downloadState = first.error ?: "cannot display this file"
                                        }
                                        textLoading = false
                                    }
                                }
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (entry.isDirectory) "▸ " else "  ",
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(entry.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = true))
                    }
                }
            }
        } else {
            // Text viewer (paged, INV-13: slice-by-slice loads only).
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { viewer = null; downloadState = null }, label = { Text("Back") })
                AssistChip(
                    onClick = {
                        scope.launch {
                            downloadState = "downloading…"
                            val result = port.download(viewer!!)
                            downloadState = when {
                                result.file != null -> "downloaded ${result.bytes} bytes — share sheet opened"
                                else -> result.error ?: "download failed"
                            }
                        }
                    },
                    label = { Text("Download") },
                )
            }
            if (downloadState != null) {
                Text(downloadState!!, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (textLoading) {
                Text("Loading…", style = MaterialTheme.typography.bodySmall)
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
                                Text("Load more (${slice.offset + slice.bytesRead}/${slice.totalBytes})")
                            }
                        }
                    }
                }
            }
        }
    }

    // Malformed/error download notice dialog (never a silent failure).
    if (downloadState != null && viewer == null && error == null && downloadState!!.startsWith("binary")) {
        AlertDialog(
            onDismissRequest = { downloadState = null },
            confirmButton = { TextButton(onClick = { downloadState = null }) { Text("OK") } },
            title = { Text("File") },
            text = { Text(downloadState!!) },
        )
    }
}

private const val TEXT_CHUNK: Long = 64L * 1024
