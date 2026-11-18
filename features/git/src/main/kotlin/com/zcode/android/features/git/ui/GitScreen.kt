package com.zcode.android.features.git.ui

import androidx.compose.ui.res.stringResource
import com.zcode.android.features.git.R

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.OutlinedTextField
import com.zcode.android.features.git.GitChangeTree
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.zcode.android.core.ui.components.minRowTouchTarget
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.saveable.rememberSaveable

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
    // C6: the ONE expanded inline diff (desktop expandedPath) survives process death.
    var expandedPath by rememberSaveable { mutableStateOf<String?>(null) }
    var expandedStaged by rememberSaveable { mutableStateOf(false) }
    val expandedDiff: Pair<String, Boolean>? = expandedPath?.let { it to expandedStaged }
    fun toggleExpanded(key: Pair<String, Boolean>) {
        if (expandedDiff == key) {
            expandedPath = null
        } else {
            expandedPath = key.first
            expandedStaged = key.second
        }
    }
    val scope = rememberCoroutineScope()
    // ADR-021 (Phase 5D): the write-tier sheet state.
    var showCommit by remember { mutableStateOf(false) }
    var showBranches by remember { mutableStateOf(false) }
    var gitIdentity by remember { mutableStateOf<com.zcode.android.domain.model.GitIdentity?>(null) }
    var gitBranches by remember { mutableStateOf<com.zcode.android.domain.model.GitBranchList?>(null) }
    var gitBranchIssue by remember { mutableStateOf<com.zcode.android.domain.model.GitBranchIssue?>(null) }
    var generating by remember { mutableStateOf(false) }
    // N-BG-2 (vc52 Phase 11): the generate flow's typed outcome — the generated
    // message lands in the sheet's field; a refusal renders inline.
    var generatedMessage by remember { mutableStateOf<String?>(null) }
    var generateRefusal by remember { mutableStateOf<String?>(null) }
    var pushFor by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var discardFor by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val notifierGit = com.zcode.android.core.ui.components.LocalNotifier.current
    val commitDoneFmt = stringResource(R.string.commit_done, "x")
    val pushDoneFmt = stringResource(R.string.push_done, "x")
    val discardOneCopy = stringResource(R.string.discard_done, 1)
    val switchedFmt = stringResource(R.string.branches_switched, "x")
    val generateUnavailableCopy = stringResource(R.string.commit_generate_unavailable)
    // F-P10-A/F-P41-A: the write tier's typed unavailable face (the null-target path).
    val writeUnavailableCopy = stringResource(R.string.git_write_unavailable)

    // G53 (vc52 Phase 11): the comparison read — a refusal carries the typed reason
    // (N-BG-2: the empty state NAMES it; never a silent no-op).
    suspend fun fetchComparison() {
        val comparison = runCatching { port.branchComparison() }
        val error = comparison.exceptionOrNull()
        ui = ui.copy(
            comparison = comparison.getOrNull()?.takeIf { it.comparisonLabel != null },
            comparisonError = error?.message ?: error?.javaClass?.simpleName,
        )
    }

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

    // F-P40-A (pass-0 Phase 4): the write-ack refetch. The commit handler's
    // fetchChanges() asymmetry was the shape — a switch/create ack re-reads the
    // whole pane IN PLACE (summary + changes + comparison) so the branch block,
    // Changes and comparison follow the moved head without re-entry.
    suspend fun refetchAfterWriteAck() {
        val summary = port.summary()
        if (summary != null && summary.isRepository) {
            ui = ui.copy(summary = summary, unavailable = false)
        }
        fetchChanges()
        fetchComparison()
    }

    LaunchedEffect(Unit) {
        val summary = port.summary()
        if (summary == null || !summary.isRepository) {
            ui = ui.copy(unavailable = true)
            return@LaunchedEffect
        }
        ui = ui.copy(summary = summary)
        fetchChanges()
        val history = runCatching { port.commits(0, COMMIT_PAGE) }
        val historyError = history.exceptionOrNull()
        ui = ui.copy(
            commits = history.getOrNull()?.let { it.commits to it.hasMore }
                ?: (emptyList<GitCommit>() to false),
            historyError = historyError?.message ?: historyError?.javaClass?.simpleName,
        )
        // G53 (vc52 Phase 11): the branch comparison hydrates with the rest of the panel.
        fetchComparison()
    }

    // ONE scroll container: long change lists + an expanded inline diff must both
    // stay reachable (the old fixed Column pushed lower sections off-viewport).
    // H2.5 (Phase 3E): pull-to-refresh — one reload path re-fetches summary+changes.
    var refreshing by remember { mutableStateOf(false) }
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            scope.launch {
                refreshing = true
                val summary = port.summary()
                if (summary != null && summary.isRepository) {
                    ui = ui.copy(summary = summary, unavailable = false)
                    fetchChanges()
                    fetchComparison()
                }
                refreshing = false
            }
        },
        modifier = modifier.fillMaxSize().testTag("git_pull"),
    ) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(8.dp).testTag("git_list"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "header") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // B2.18: the route already renders "Git" — no second header.
                Text(stringResource(R.string.git_read_only), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // F-P40-C (pass-0 Phase 4): the branch write ack's TYPED issue renders at ack
        // time, pane-level — the sheet is already closed when the result lands (the
        // old only-render-target lived inside the closed sheet).
        gitBranchIssue?.let { issue ->
            item(key = "branch-issue") {
                BranchIssueCard(
                    issue = issue,
                    onDismiss = { gitBranchIssue = null },
                )
            }
        }

        // A3.11 (Phase 5B): the checkpoints card — create (snapshot), diff/restore between
        // session-known checkpoints (restore = ⚠: a warned confirm; force overwriting local
        // modifications needs a SECOND confirm).
        item(key = "checkpoints") {
            CheckpointsCard(
                port = port,
                modifier = Modifier.fillMaxWidth(),
            )
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
                            stringResource(R.string.git_tracking, s.trackingBranchName ?: "—"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                }
            }

            // G53 (vc52 Phase 11): the branch comparison — base/head + per-file changes
            // (the desktop's getBranchComparison). A refusal renders the typed reason
            // (N-BG-2); no tracking branch renders nothing (no comparison exists).
            item(key = "git-branch-comparison") {
                val comparison = ui.comparison
                when {
                    comparison != null -> Surface(tonalElevation = 1.dp) {
                        Column(
                            Modifier.fillMaxWidth().padding(10.dp).testTag("git_branch_comparison"),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                comparison.comparisonLabel ?: "",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.testTag("git_comparison_label"),
                            )
                            Text(
                                stringResource(R.string.git_comparison_files, comparison.changes.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            comparison.changes.forEach { change ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 1.dp).testTag("git_comparison_file_${change.path}"),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        change.path,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    Text(
                                        "+${change.added} −${change.removed}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    ui.comparisonError != null -> Surface(tonalElevation = 1.dp) {
                        Column(
                            Modifier.fillMaxWidth().padding(10.dp).testTag("git_comparison_error"),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                stringResource(R.string.git_comparison_unavailable),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                ui.comparisonError.orEmpty(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    else -> Unit
                }
            }

            // ADR-021 Tier B: the write actions row (commit / branches) — dialog-gated.
            item(key = "git-write-actions") {
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        scope.launch { gitIdentity = port.identity() }
                        showCommit = true
                    }, modifier = Modifier.testTag("git_commit_open")) { Text(stringResource(R.string.git_commit_action)) }
                    TextButton(onClick = {
                        scope.launch { gitBranches = port.localBranches(); gitBranchIssue = null }
                        showBranches = true
                    }, modifier = Modifier.testTag("git_branches_open")) { Text(stringResource(R.string.git_branches_action)) }
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
                        expanded = expandedDiff,
                        load = { path, isStaged -> port.diff(path, isStaged) },
                        onToggle = { path, wasStaged ->
                            val key = path to wasStaged
                            toggleExpanded(key)
                        },
                        onStage = { path, stage ->
                            scope.launch {
                                if (if (stage) port.stagePaths(listOf(path)) else port.unstagePaths(listOf(path))) fetchChanges()
                            }
                        },
                        onDiscard = { path, staged -> discardFor = path to staged },
                    )
                    ChangeCard(
                        title = stringResource(R.string.git_section_staged),
                        staged = true,
                        entries = ui.staged,
                        expanded = expandedDiff,
                        load = { path, isStaged -> port.diff(path, isStaged) },
                        onToggle = { path, wasStaged ->
                            val key = path to wasStaged
                            toggleExpanded(key)
                        },
                        onStage = { path, stage ->
                            scope.launch {
                                if (if (stage) port.stagePaths(listOf(path)) else port.unstagePaths(listOf(path))) fetchChanges()
                            }
                        },
                        onDiscard = { path, staged -> discardFor = path to staged },
                    )
                }
            }

            item(key = "history-title") {
                Text(stringResource(R.string.git_history_header), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            // N-BG-2 (vc52 Phase 11): a refused history read renders the reason — the
            // tabs rendering EMPTY was the recorded silent-no-op face.
            if (ui.commits.first.isEmpty() && ui.historyError != null) {
                item(key = "history-error") {
                    Column(
                        Modifier.fillMaxWidth().padding(8.dp).testTag("git_history_error"),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            stringResource(R.string.git_history_unavailable),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            ui.historyError.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // N-BK-1 (pass-0 Phase 4): a HEALTHY empty history renders its empty state —
            // never bare-blank (the app half; the rig half's fixture contract is fixed).
            if (ui.commits.first.isEmpty() && ui.historyError == null) {
                item(key = "history-empty") {
                    Text(
                        stringResource(R.string.git_history_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(8.dp).testTag("git_history_empty"),
                    )
                }
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
    // ---- ADR-021 (Phase 5D): the write-tier sheets ----
    if (showCommit) {
        CommitSheet(
            identity = gitIdentity,
            generating = generating,
            onDismiss = { showCommit = false },
            onGenerate = {
                scope.launch {
                    generating = true
                    generateRefusal = null
                    try {
                        val outcome = port.generateCommitMessageOutcome(includeUnstaged = true)
                        generatedMessage = outcome.message
                        generateRefusal = outcome.refusal
                    } finally {
                        generating = false
                    }
                }
            },
            generatedMessage = generatedMessage,
            generateRefusal = generateRefusal,
            onCommit = { message, includeUnstaged, andPush ->
                showCommit = false
                scope.launch {
                    // F-P10-A: the outcome is typed — a null workspace target or a refused
                    // call renders a refusal face (the `if (hash != null)…else nothing` was
                    // the silent exit).
                    val outcome = port.commitOutcome(message, stagedOnly = !includeUnstaged)
                    val hash = outcome.result?.commitHash
                    if (hash != null) {
                        notifierGit.show(commitDoneFmt.replace("x", hash.take(8)))
                        fetchChanges()
                        if (andPush) {
                            val summary = ui.summary
                            pushFor = (summary?.branchName ?: "") to (summary?.trackingBranchName != null)
                        }
                    } else {
                        val face = outcome.refusal
                        notifierGit.show(
                            when {
                                face == null || face.noTarget -> writeUnavailableCopy
                                else -> face.message ?: writeUnavailableCopy
                            },
                            long = true,
                        )
                    }
                }
            },
        )
    }
    pushFor?.let { (branch, hasUpstream) ->
        PushConfirm(
            branchName = branch,
            hasUpstream = hasUpstream,
            onDismiss = { pushFor = null },
            onPush = {
                val branchName = branch
                pushFor = null
                scope.launch {
                    // F-P41-A: the refused push renders its reason — the success-only
                    // branch was the silent kill (the dialog closed pre-result).
                    val outcome = port.pushOutcome()
                    if (outcome.result != null) {
                        notifierGit.show(pushDoneFmt.replace("x", branchName))
                    } else {
                        val face = outcome.refusal
                        notifierGit.show(
                            when {
                                face == null || face.noTarget -> writeUnavailableCopy
                                else -> face.message ?: writeUnavailableCopy
                            },
                            long = true,
                        )
                    }
                }
            },
        )
    }
    discardFor?.let { (path, staged) ->
        DiscardConfirm(
            paths = listOf(path),
            onDismiss = { discardFor = null },
            onDiscard = {
                val target = path to staged
                discardFor = null
                scope.launch {
                    if (port.discardPaths(listOf(target.first), target.second)) {
                        notifierGit.show(discardOneCopy)
                        fetchChanges()
                    }
                }
            },
        )
    }
    if (showBranches) {
        BranchSheet(
            branches = gitBranches,
            issue = gitBranchIssue,
            onDismiss = { showBranches = false; gitBranchIssue = null },
            onSwitch = { name ->
                showBranches = false
                scope.launch {
                    val result = port.switchBranch(name)
                    if (result?.issues?.isNotEmpty() == true) gitBranchIssue = result.issues.first()
                    else if (result != null) notifierGit.show(switchedFmt.replace("x", name))
                    if (result != null) refetchAfterWriteAck()
                }
            },
            onCreateAndSwitch = { name ->
                showBranches = false
                scope.launch {
                    val result = port.createBranchAndSwitch(name)
                    if (result?.issues?.isNotEmpty() == true) gitBranchIssue = result.issues.first()
                    else if (result != null) notifierGit.show(switchedFmt.replace("x", name))
                    if (result != null) refetchAfterWriteAck()
                }
            },
        )
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
    // ---- ADR-021 (Phase 5D): the write tier (null = read-only) ----
    onStage: ((path: String, stage: Boolean) -> Unit)? = null,
    onDiscard: ((path: String, staged: Boolean) -> Unit)? = null,
) {
    if (entries.isEmpty()) return
    Surface(tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(6.dp)) {
            Text(
                "$title (${entries.size})",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            // J1.2 (Phase 6C): search + the collapsible tree — gated to the sizes where
            // they beat the flat list (the desktop `git.tree.*` surface).
            var query by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
            if (entries.size > GitChangeTree.SEARCH_GATE) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(com.zcode.android.features.git.R.string.git_tree_search)) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .testTag("git_search"),
                )
            }
            val visible = if (query.isBlank()) entries else GitChangeTree.search(entries, query)
            val collapsed = androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(setOf<String>()) }
            val sections = if (staged) STAGED_SECTIONS else UNSTAGED_SECTIONS
            for (section in sections) {
                val group = visible
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
                val tree = if (visible.size > GitChangeTree.TREE_GATE) {
                    remember(group) { GitChangeTree.build(group) }
                } else null
                if (tree == null) {
                    for (entry in group) {
                        ChangeRow(
                            entry = entry,
                            staged = staged,
                            expanded = expanded == (entry.path to staged),
                            load = load,
                            onToggle = onToggle,
                            onStage = onStage,
                            onDiscard = onDiscard,
                        )
                    }
                } else {
                    GitTreeRows(
                        dir = tree,
                        depth = 0,
                        staged = staged,
                        collapsed = collapsed.value,
                        onCollapseDir = { path ->
                            collapsed.value = if (path in collapsed.value) collapsed.value - path else collapsed.value + path
                        },
                        expanded = expanded,
                        load = load,
                        onToggle = onToggle,
                        onStage = onStage,
                        onDiscard = onDiscard,
                    )
                }
            }
        }
    }
}

/** J1.2: the collapsible directory walk — dirs toggle, files render as ChangeRows. */
@Composable
private fun GitTreeRows(
    dir: GitChangeTree.Dir,
    depth: Int,
    staged: Boolean,
    collapsed: Set<String>,
    expanded: Pair<String, Boolean>?,
    load: suspend (String, Boolean) -> GitFileDiff?,
    onToggle: (path: String, staged: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onCollapseDir: (String) -> Unit = { },
    onStage: ((path: String, stage: Boolean) -> Unit)? = null,
    onDiscard: ((path: String, staged: Boolean) -> Unit)? = null,
) {
    val indent = modifier.padding(start = (depth * 12).dp)
    if (dir.name.isNotEmpty()) {
        val isCollapsed = dir.path in collapsed
        Row(
            Modifier
                .fillMaxWidth()
                .then(indent)
                .clickable { onCollapseDir(dir.path) }
                .padding(vertical = 4.dp)
                .testTag("git_tree_dir_${dir.path}")
                .semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (isCollapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                dir.name,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                (dir.dirs.sumOf { countTree(it) } + dir.files.size).toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (dir.name.isEmpty() || dir.path !in collapsed) {
        dir.files.forEach { entry ->
            ChangeRow(
                entry = entry,
                staged = staged,
                expanded = expanded == (entry.path to staged),
                load = load,
                onToggle = onToggle,
                onStage = onStage,
                onDiscard = onDiscard,
                modifier = Modifier.padding(start = ((depth + 1) * 12).dp),
            )
        }
        dir.dirs.forEach { child ->
            GitTreeRows(child, depth + 1, staged, collapsed, expanded, load, onToggle, onCollapseDir = onCollapseDir, onStage = onStage, onDiscard = onDiscard)
        }
    }
}

private fun countTree(dir: GitChangeTree.Dir): Int =
    dir.files.size + dir.dirs.sumOf { countTree(it) }

@Composable
private fun ChangeRow(
    entry: GitChangeEntry,
    staged: Boolean,
    expanded: Boolean,
    load: suspend (String, Boolean) -> GitFileDiff?,
    onToggle: (path: String, staged: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onStage: ((path: String, stage: Boolean) -> Unit)? = null,
    onDiscard: ((path: String, staged: Boolean) -> Unit)? = null,
) {
    val stageCdFmt = stringResource(R.string.git_stage_cd)
    val unstageCdFmt = stringResource(R.string.git_unstage_cd)
    val discardCdFmt = stringResource(R.string.git_discard_cd)
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .minRowTouchTarget()
                .clickable(role = Role.Button) { onToggle(entry.path, staged) }
                .padding(vertical = 4.dp)
                .testTag("git_change_${entry.repoRelativePath}"),
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
            // ADR-021 Tier A: reversible index moves — one tap, no confirm; the DISCARD
            // entry (Tier C) opens the typed-confirm (never an inline destructive tap).
            if (onStage != null) {
                // ADR-021 Tier A: reversible index moves — one tap, no confirm. Compact
                // text (the a11y description carries the verb; the row height stays stable
                // so the desktop section sort stays observable in pixels).
                // N-BY-1: the resources are POSITIONAL ("Stage %1$s") — the replace uses
                // the positional slot (the old "%s" literal never matched, so TalkBack
                // heard the raw format string), and the verb follows the staged state.
                val stageCd = (if (staged) unstageCdFmt else stageCdFmt).replace("%1\$s", entry.repoRelativePath)
                TextButton(
                    onClick = { onStage(entry.repoRelativePath.ifBlank { entry.path }, !staged) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
                    modifier = Modifier
                        .height(32.dp)
                        .testTag("git_stage_${entry.repoRelativePath}")
                        .semantics { contentDescription = stageCd },
                ) { Text(stringResource(if (staged) R.string.git_unstage else R.string.git_stage), style = MaterialTheme.typography.labelSmall) }
            }
            if (onDiscard != null) {
                val discardCd = discardCdFmt.replace("%1\$s", entry.repoRelativePath)
                TextButton(
                    onClick = { onDiscard(entry.repoRelativePath.ifBlank { entry.path }, staged) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
                    modifier = Modifier
                        .height(32.dp)
                        .testTag("git_discard_entry_${entry.repoRelativePath}")
                        .semantics { contentDescription = discardCd },
                ) { Text(stringResource(R.string.git_discard), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
            }
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
    val unavailable: Boolean = false,
    /** B2.7: a changes() failure — rendered with a Retry; empty is never a lie. */
    val changesError: String? = null,
    /** G53 (vc52 Phase 11): the branch comparison (null = refused/unavailable — the reason rides [comparisonError]). */
    val comparison: com.zcode.android.domain.model.GitBranchComparison? = null,
    /** N-BG-2: the comparison read's refusal reason (the honest empty-state face). */
    val comparisonError: String? = null,
    /** N-BG-2: the history read's refusal reason (the empty state NAMES it). */
    val historyError: String? = null,
)

/**
 * A3.11 (Phase 5B): the git-checkpoint card. Create snapshots a workspace (the meta's
 * short id + relative time render); Restore between two checkpoints is the ⚠ surface —
 * the confirm names BOTH ids and the diff's files; a conflict result offers the
 * force-overwrite behind a second confirm. vc51 Phase 4A (CKPT-HYDRATE): the list
 * hydrates the host's checkpoint history on entry (`listCheckpoints`), so snapshots
 * created before an app restart stay listed and restorable.
 */
@Composable
private fun CheckpointsCard(port: GitPort, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val notifier = com.zcode.android.core.ui.components.LocalNotifier.current
    val createdFmt = stringResource(R.string.checkpoint_created, "x")
    val failedCopy = stringResource(R.string.checkpoint_failed)
    val restoredFmt = stringResource(R.string.checkpoint_restored, 0)

    var checkpoints by remember { mutableStateOf<List<com.zcode.android.domain.model.CheckpointMeta>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var restoringFor by remember { mutableStateOf<com.zcode.android.domain.model.CheckpointMeta?>(null) }
    // F-P45-A: the force confirm carries the CONFIRMED (from, to) pair — recomputing
    // from the list index matched only at exactly two checkpoints.
    var forceConfirmFor by remember { mutableStateOf<Triple<com.zcode.android.domain.model.CheckpointMeta, com.zcode.android.domain.model.CheckpointMeta, List<String>>?>(null) }
    // N-CA-1 (pass-0 Phase 4): the restore confirm NAMES the diff's files — the
    // docstring's promise; diffCheckpoints was fully wired but had ZERO UI callers.
    var confirmDiffFiles by remember { mutableStateOf<List<String>?>(null) }
    // vc51 Phase 4A (CKPT-HYDRATE): the card hydrates the host's checkpoint history on
    // entry — the list is no longer session-local, so snapshots created before an app
    // restart stay listed and restorable. Empty hydration (older hosts) keeps the
    // session-only note honest.
    var hostHistoryEmpty by remember { mutableStateOf(true) }
    LaunchedEffect(port) {
        val host = runCatching { port.listCheckpoints() }.getOrDefault(emptyList())
        hostHistoryEmpty = host.isEmpty()
        if (host.isNotEmpty()) {
            checkpoints = (checkpoints + host)
                .distinctBy { it.checkpointId }
                .sortedByDescending { it.createdAtMs }
        }
    }

    Column(
        modifier
            .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.medium)
            .padding(12.dp)
            .testTag("git_checkpoints"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.checkpoints_title),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    if (busy) return@TextButton
                    busy = true
                    scope.launch {
                        try {
                            val meta = port.createCheckpoint()
                            if (meta != null) {
                                // dedupe against the hydrated history (a create can
                                // re-report an id the host already listed)
                                checkpoints = (listOf(meta) + checkpoints).distinctBy { it.checkpointId }
                                notifier.show(createdFmt.replace("x", meta.checkpointId))
                            } else {
                                notifier.show(failedCopy, long = true)
                            }
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.testTag("checkpoint_create"),
            ) { Text(stringResource(R.string.checkpoint_create)) }
        }
        if (checkpoints.isEmpty()) {
            Text(
                stringResource(R.string.checkpoints_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("checkpoints_empty"),
            )
        } else {
            checkpoints.forEachIndexed { index, cp ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            cp.checkpointId,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("checkpoint_id_$index"),
                        )
                        com.zcode.android.core.ui.components.relativeTimeLabelLive(cp.createdAtMs)?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    // Restore into an EARLIER checkpoint (the newest is the head; index+1 exists).
                    if (index + 1 < checkpoints.size) {
                        TextButton(
                            onClick = { restoringFor = checkpoints[index + 1] },
                            enabled = !busy,
                            modifier = Modifier.testTag("checkpoint_restore_$index"),
                        ) { Text(stringResource(R.string.checkpoint_restore)) }
                    }
                }
            }
            // CKPT-HYDRATE: the note stays honest — only claim "created elsewhere aren't
            // listed" when the host's history leg actually came back empty.
            if (hostHistoryEmpty) {
                Text(
                    stringResource(R.string.checkpoints_session_only),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    restoringFor?.let { from ->
        val to = checkpoints.firstOrNull()
        // N-CA-1: fetch the confirmed pair's diff so the confirm NAMES its files
        // (the docstring's promise — the verb was fully wired below the UI, UI-dead).
        androidx.compose.runtime.LaunchedEffect(from.checkpointId, to?.checkpointId) {
            confirmDiffFiles = null
            confirmDiffFiles = runCatching {
                port.diffCheckpoints(from.checkpointId, to?.checkpointId ?: "")
            }.getOrNull()?.files
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { restoringFor = null },
            title = { Text(stringResource(R.string.checkpoint_restore_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.checkpoint_restore_body, from.checkpointId, to?.checkpointId ?: ""))
                    confirmDiffFiles?.takeIf { it.isNotEmpty() }?.let { files ->
                        Text(
                            stringResource(R.string.checkpoint_diff_files, files.joinToString(", ")),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp).testTag("checkpoint_diff_files"),
                        )
                    }
                }
            },
            confirmButton = {
                com.zcode.android.core.ui.components.ZCodeButton(
                    onClick = {
                        val f = from
                        val t = to
                        restoringFor = null
                        if (t != null) {
                            busy = true
                            scope.launch {
                                try {
                                    val result = port.restoreCheckpoints(f.checkpointId, t.checkpointId, force = false)
                                    when {
                                        result == null -> notifier.show(failedCopy, long = true)
                                        result.success -> notifier.show(restoredFmt.replace("0", result.restoredPaths.size.toString()))
                                        // F-P45-A: the conflict arm carries the CONFIRMED
                                        // pair (f = the restore target, t = the head) —
                                        // never a recomputed list index.
                                        else -> forceConfirmFor = Triple(f, t, result.conflicts)
                                    }
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    },
                    modifier = Modifier.testTag("checkpoint_restore_confirm"),
                ) { Text(stringResource(R.string.checkpoint_restore)) }
            },
            dismissButton = {
                TextButton(onClick = { restoringFor = null }) { Text(stringResource(com.zcode.android.core.ui.R.string.action_cancel)) }
            },
        )
    }
    forceConfirmFor?.let { (from, to, conflicts) ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { forceConfirmFor = null },
            title = { Text(stringResource(R.string.checkpoint_conflict_title)) },
            text = { Text(stringResource(R.string.checkpoint_conflict_body, conflicts.size, conflicts.take(3).joinToString(", "))) },
            confirmButton = {
                com.zcode.android.core.ui.components.ZCodeButton(
                    onClick = {
                        val f = from
                        forceConfirmFor = null
                        if (f != null) {
                            busy = true
                            scope.launch {
                                try {
                                    val result = port.restoreCheckpoints(f.checkpointId, to.checkpointId, force = true)
                                    if (result?.success == true) notifier.show(restoredFmt.replace("0", result.restoredPaths.size.toString()))
                                    else notifier.show(failedCopy, long = true)
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    },
                    modifier = Modifier.testTag("checkpoint_force_confirm"),
                ) { Text(stringResource(R.string.checkpoint_force)) }
            },
            dismissButton = {
                TextButton(onClick = { forceConfirmFor = null }) { Text(stringResource(com.zcode.android.core.ui.R.string.action_cancel)) }
            },
        )
    }
}

