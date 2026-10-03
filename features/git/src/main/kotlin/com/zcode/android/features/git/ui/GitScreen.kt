package com.zcode.android.features.git.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zcode.android.core.common.diff.UnifiedDiffParser
import com.zcode.android.core.ui.components.DiffViewerContent
import com.zcode.android.domain.model.GitChangeEntry
import com.zcode.android.domain.model.GitCommit
import com.zcode.android.domain.model.GitFileDiff
import com.zcode.android.domain.model.GitSummary
import com.zcode.android.features.git.port.GitPort
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/**
 * §9.4 git panel — READ-ONLY (INV-9): repository summary, working-tree changes
 * by section, per-file unified diff via the §22 renderer, commit history.
 * Unavailable/refused channels degrade to the honest disabled state (§13.3).
 */
@Composable
fun GitScreen(
    port: GitPort,
    modifier: Modifier = Modifier,
) {
    var ui by remember { mutableStateOf(GitUiState()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val summary = port.summary()
        if (summary == null || !summary.isRepository) {
            ui = ui.copy(unavailable = true)
            return@LaunchedEffect
        }
        ui = ui.copy(
            summary = summary,
            unstaged = runCatching { port.changes("unstaged") }.getOrDefault(emptyList()),
            staged = runCatching { port.changes("staged") }.getOrDefault(emptyList()),
            commits = port.commits(0, COMMIT_PAGE)?.let { it.commits to it.hasMore }
                ?: (emptyList<GitCommit>() to false),
        )
    }

    Column(modifier = modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Git", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("read-only", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (ui.unavailable) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Git is not available for this workspace (not a repository, or this connection does not expose the git channel).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = {
                    scope.launch {
                        val summary = port.summary()
                        ui = ui.copy(unavailable = summary == null || !summary.isRepository)
                    }
                }) { Text("Retry") }
            }
        } else {
            ui.summary?.let { s ->
                Surface(tonalElevation = 1.dp) {
                    Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            buildString {
                                append(s.branchName ?: "(detached)")
                                if (s.ahead > 0 || s.behind > 0) append("  ↑${s.ahead} ↓${s.behind}")
                                if (s.isDirty) append("  • dirty")
                            },
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            "tracking: ${s.trackingBranchName ?: "—"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Text("Changes", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            ChangeSection("Unstaged", ui.unstaged) { path -> ui = ui.copy(openDiff = path to false) }
            ChangeSection("Staged", ui.staged) { path -> ui = ui.copy(openDiff = path to true) }

            Text("History", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            LazyColumn {
                items(ui.commits.first, key = { it.hash }) { commit ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(commit.subject, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                        Text(
                            buildString {
                                append(commit.hash.take(8))
                                append("  ")
                                append(commit.authorName ?: "")
                                commit.authoredAtMs?.let {
                                    append("  ")
                                    append(
                                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                                            .withZone(ZoneId.systemDefault())
                                            .format(Instant.ofEpochMilli(it)),
                                    )
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (ui.commits.second) {
                    item {
                        AssistChip(
                            onClick = {
                                scope.launch {
                                    val page = port.commits(ui.commits.first.size, COMMIT_PAGE) ?: return@launch
                                    ui = ui.copy(commits = (ui.commits.first + page.commits) to page.hasMore)
                                }
                            },
                            label = { Text("Load more") },
                        )
                    }
                }
            }
        }

        ui.openDiff?.let { (path, staged) ->
            DiffOverlay(
                path = path,
                staged = staged,
                onDismiss = { ui = ui.copy(openDiff = null) },
                load = { port.diff(path, staged) },
            )
        }
    }
}

private const val COMMIT_PAGE = 50

@Composable
private fun ChangeSection(title: String, entries: List<GitChangeEntry>, onOpen: (String) -> Unit) {
    if (entries.isEmpty()) return
    Surface(tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(6.dp)) {
            Text(
                "$title (${entries.size})",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            entries.forEach { entry ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(entry.path) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        entry.repoRelativePath.ifBlank { entry.path },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f, fill = true),
                        maxLines = 1,
                    )
                    Text(
                        "+${entry.added} −${entry.removed}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DiffOverlay(
    path: String,
    staged: Boolean,
    onDismiss: () -> Unit,
    load: suspend () -> GitFileDiff?,
) {
    val content by produceState<GitFileDiff?>(initialValue = null, key1 = path, key2 = staged) {
        value = load()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text(path, style = MaterialTheme.typography.titleSmall, maxLines = 2) },
        text = {
            when {
                content == null -> Text("Loading diff…")
                content!!.availability == "binary" -> Text("binary file changed")
                content!!.availability == "truncated" ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("diff truncated by the desktop", style = MaterialTheme.typography.labelSmall)
                        DiffBody(content!!.patch.orEmpty())
                    }
                content!!.patch.isNullOrBlank() -> Text("No textual diff available (availability: ${content!!.availability}).")
                else -> DiffBody(content!!.patch.orEmpty())
            }
        },
    )
}

@Composable
private fun DiffBody(patch: String) {
    val files = UnifiedDiffParser.parse(patch)
    LazyColumn {
        item { DiffViewerContent(files = files) }
    }
}

private data class GitUiState(
    val summary: GitSummary? = null,
    val unstaged: List<GitChangeEntry> = emptyList(),
    val staged: List<GitChangeEntry> = emptyList(),
    val commits: Pair<List<GitCommit>, Boolean> = emptyList<GitCommit>() to false,
    val openDiff: Pair<String, Boolean>? = null,
    val unavailable: Boolean = false,
)
