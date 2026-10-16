package com.zcode.android.features.git.ui

import androidx.compose.ui.res.stringResource
import com.zcode.android.features.git.R

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zcode.android.core.common.diff.UnifiedDiffParser
import com.zcode.android.core.ui.components.DiffCountBadge
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
import com.zcode.android.core.ui.components.ZCodeButton

/**
 * §9.4 git panel — READ-ONLY (INV-9). GAP-16 overhaul to the desktop git-panel
 * parity (GitPane.tsx / GitPaneChangeCard.tsx / git-graph pane):
 *  - working-tree changes grouped by the DESKTOP section order — the unstaged
 *    dataset is [unstaged, untracked, conflicted], the staged dataset [staged]
 *    (useGitRepository.ts:84-88) — each section sorted by repo-relative path
 *    (GitPane.tsx:249-251);
 *  - change rows = file icon + filename + DIMMED directory + colored +N −N
 *    (GitPaneChangeCard.tsx:102-117; the desktop shows NO kind badge);
 *  - ONE inline diff expansion at a time, lazily fetched on expand
 *    (GitPane.tsx:394,166-180) — replaces the old dialog overlay;
 *  - branch info (branch, tracking, ↑ahead ↓behind, dirty) and short-hash history
 *    rows (git-graph: 7-char hash, MM/dd HH:mm date).
 * Unavailable/refused channels degrade to the honest disabled state (§13.3).
 */
@Composable
fun GitScreen(
    port: GitPort,
    modifier: Modifier = Modifier,
) {
    var ui by remember { mutableStateOf(GitUiState()) }
    val scope = rememberCoroutineScope()

    // B2.7: both datasets fetched through ONE path so the error state and the
    // Retry share it. A failure renders an error state — never a lying empty list.
    suspend fun fetchChanges(): Pair<List<GitChangeEntry>, List<GitChangeEntry>> {
        val unstaged = runCatching { port.changes("unstaged") }
        val staged = runCatching { port.changes("staged") }
        val error = unstaged.exceptionOrNull() ?: staged.exceptionOrNull()
        ui = ui.copy(
            unstaged = unstaged.getOrElse { emptyList() },
            staged = staged.getOrElse { emptyList() },
            changesError = error?.message ?: error?.javaClass?.simpleName,
        )
        return unstaged.getOrElse { emptyList() } to staged.getOrElse { emptyList() }
    }

    LaunchedEffect(Unit) {
        val summary = port.summary()
        if (summary == null || !summary.isRepository) {
            ui = ui.copy(unavailable = true)
            return@LaunchedEffect
        }
        ui = ui.copy(summary = summary)
        fetchChanges()
        ui = ui.copy(
            commits = port.commits(0, COMMIT_PAGE)?.let { it.commits to it.hasMore }
                ?: (emptyList<GitCommit>() to false),
        )
    }

    // ONE scroll container: long change lists + an expanded inline diff must both
    // stay reachable (the old fixed Column pushed lower sections off-viewport).
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(8.dp).testTag("git_list"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "header") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // B2.18: the route already renders "Git" — no second header.
                Text(stringResource(R.string.git_read_only), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        ui.changesError?.let { errorText ->
            item(key = "changes-error") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        errorText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("git_changes_error"),
                    )
                    ZCodeButton(
                        onClick = {
                            scope.launch { fetchChanges() }
                        },
                        modifier = Modifier.testTag("git_changes_retry"),
                    ) { Text(stringResource(com.zcode.android.core.ui.R.string.action_retry)) }
                }
            }
        }

        if (ui.unavailable) {
            item(key = "unavailable") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.git_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ZCodeButton(onClick = {
                        scope.launch {
                            val summary = port.summary()
                            ui = ui.copy(unavailable = summary == null || !summary.isRepository)
                        }
                    }) { Text(stringResource(com.zcode.android.core.ui.R.string.action_retry)) }
                }
            }
        } else {
            item(key = "summary") { ui.summary?.let { s ->
                Surface(tonalElevation = 1.dp) {
                    Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val dirtyMarker = stringResource(R.string.git_dirty_marker)
                        Text(
                            buildString {
                                append(s.branchName ?: "(detached)")
                                if (s.ahead > 0 || s.behind > 0) append("  ↑${s.ahead} ↓${s.behind}")
                                if (s.isDirty) append(dirtyMarker)
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
            }

            item(key = "changes-title") {
                Text(stringResource(R.string.git_changes_header), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            // Desktop datasets: unstaged = [unstaged, untracked, conflicted]; staged = [staged].
            // ONE item: every section header then carries real placed bounds even when
            // the card extends past the viewport (LazyColumn disposes later items).
            item(key = "changes") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChangeCard(
                        title = stringResource(R.string.git_section_unstaged),
                        staged = false,
                        entries = ui.unstaged,
                        expanded = ui.expanded,
                        load = { path, isStaged -> port.diff(path, isStaged) },
                        onToggle = { path, wasStaged ->
                            val key = path to wasStaged
                            ui = ui.copy(expanded = if (ui.expanded == key) null else key)
                        },
                    )
                    ChangeCard(
                        title = stringResource(R.string.git_section_staged),
                        staged = true,
                        entries = ui.staged,
                        expanded = ui.expanded,
                        load = { path, isStaged -> port.diff(path, isStaged) },
                        onToggle = { path, wasStaged ->
                            val key = path to wasStaged
                            ui = ui.copy(expanded = if (ui.expanded == key) null else key)
                        },
                    )
                }
            }

            item(key = "history-title") {
                Text(stringResource(R.string.git_history_header), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
                items(ui.commits.first, key = { it.hash }) { commit ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(commit.subject, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                        Text(
                            buildString {
                                append(commit.hash.take(SHORT_HASH))
                                append("  ")
                                append(commit.authorName ?: "")
                                commit.authoredAtMs?.let {
                                    append("  ")
                                    append(
                                        DateTimeFormatter.ofPattern("MM/dd HH:mm")
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
                    item(key = "load-more") {
                        AssistChip(
                            onClick = {
                                scope.launch {
                                    val page = port.commits(ui.commits.first.size, COMMIT_PAGE) ?: return@launch
                                    ui = ui.copy(commits = (ui.commits.first + page.commits) to page.hasMore)
                                }
                            },
                            label = { Text(stringResource(R.string.git_load_more)) },
                        )
                    }
                }
        }
    }
}

private const val COMMIT_PAGE = 50

/** git-graph getShortHash (GitGraphDisplay.ts:4-6): 7-char short hashes. */
private const val SHORT_HASH = 7

/** Desktop section order per dataset (useGitRepository SECTION_ORDER_BY_SOURCE). */
internal val stagedSectionOrder: List<String> = listOf("staged")
internal val unstagedSectionOrder: List<String> = listOf("unstaged", "untracked", "conflicted")
private val STAGED_SECTIONS = stagedSectionOrder
private val UNSTAGED_SECTIONS = unstagedSectionOrder

@Composable
private fun ChangeCard(
    title: String,
    staged: Boolean,
    entries: List<GitChangeEntry>,
    expanded: Pair<String, Boolean>?,
    load: suspend (String, Boolean) -> GitFileDiff?,
    onToggle: (path: String, staged: Boolean) -> Unit,
) {
    if (entries.isEmpty()) return
    Surface(tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(6.dp)) {
            Text(
                "$title (${entries.size})",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            val sections = if (staged) STAGED_SECTIONS else UNSTAGED_SECTIONS
            for (section in sections) {
                val group = entries
                    .filter { it.section == section }
                    .sortedBy { it.repoRelativePath.ifBlank { it.path } }
                if (group.isEmpty()) continue
                Text(
                    section.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(top = 4.dp, bottom = 2.dp)
                        .testTag("git_section_$section"),
                )
                for (entry in group) {
                    ChangeRow(
                        entry = entry,
                        staged = staged,
                        expanded = expanded == (entry.path to staged),
                        load = load,
                        onToggle = onToggle,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChangeRow(
    entry: GitChangeEntry,
    staged: Boolean,
    expanded: Boolean,
    load: suspend (String, Boolean) -> GitFileDiff?,
    onToggle: (path: String, staged: Boolean) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onToggle(entry.path, staged) }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.size(6.dp))
            Column(Modifier.weight(1f, fill = true)) {
                Text(
                    entry.repoRelativePath.substringAfterLast('/').ifBlank { entry.repoRelativePath },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val dir = entry.repoRelativePath.substringBeforeLast('/', "")
                if (dir.isNotBlank() && dir.contains('/')) {
                    Text(
                        dir,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // GitPaneChangeCard: colored +N −N (desktop diff-added/diff-removed colors).
            DiffCountBadge(added = entry.added, removed = entry.removed)
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        if (expanded) {
            InlineDiff(
                path = entry.path,
                staged = staged,
                load = { load(entry.path, staged) },
            )
        }
    }
}

@Composable
private fun InlineDiff(
    path: String,
    staged: Boolean,
    load: suspend () -> GitFileDiff?,
) {
    val content by produceState<GitFileDiff?>(initialValue = null, key1 = path, key2 = staged) {
        value = load()
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, bottom = 4.dp)
            .testTag("git_inline_diff"),
    ) {
        when {
            content == null -> com.zcode.android.core.ui.components.SkeletonBar(Modifier.fillMaxWidth(0.6f).testTag("git_diff_loading"))
            content!!.availability == "binary" -> Text(stringResource(R.string.git_binary_changed), style = MaterialTheme.typography.labelSmall)
            content!!.availability == "truncated" ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.git_diff_truncated), style = MaterialTheme.typography.labelSmall)
                    DiffBody(content!!.patch.orEmpty())
                }
            content!!.patch.isNullOrBlank() ->
                Text(stringResource(R.string.git_no_textual_diff, content!!.availability), style = MaterialTheme.typography.labelSmall)
            else -> DiffBody(content!!.patch.orEmpty())
        }
    }
}

@Composable
private fun DiffBody(patch: String) {
    // Plain Column: this renders INLINE inside a LazyColumn item — a nested
    // scrollable is illegal and the patch is already desktop-truncated (INV-5).
    val files = UnifiedDiffParser.parse(patch)
    Column { DiffViewerContent(files = files) }
}

private data class GitUiState(
    val summary: GitSummary? = null,
    val unstaged: List<GitChangeEntry> = emptyList(),
    val staged: List<GitChangeEntry> = emptyList(),
    val commits: Pair<List<GitCommit>, Boolean> = emptyList<GitCommit>() to false,
    /** The ONE expanded diff (desktop expandedPath), with its dataset (staged flag). */
    val expanded: Pair<String, Boolean>? = null,
    val unavailable: Boolean = false,
    /** B2.7: a changes() failure — rendered with a Retry; empty is never a lie. */
    val changesError: String? = null,
)
