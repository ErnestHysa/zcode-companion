package com.zcode.android.domain.model

import com.zcode.android.core.common.diff.DiffHunk

/**
 * Phase 9 domain models — architectural.md §16 (Diff/FileRef/GitState/ModelOption
 * rows) + the read-surface projections of docs/protocol/phase9-channels.md.
 * Pure Kotlin; protocol→domain mapping lives in :data (§40.2). Desktop paths are
 * display-only metadata (INV-16) — these models never derive one from another.
 */

/** One file's diff for the §22 renderer — hunks arrive pre-parsed (v4 fileChanges plane). */
data class TurnFileChange(
    val path: String,
    val additions: Long,
    val deletions: Long,
    val writeCount: Int,
    val toolNames: List<String>,
    val hunks: List<DiffHunk>,
)

data class TurnFileChanges(
    val files: Int,
    val additions: Long,
    val deletions: Long,
    /** "active" | "reverted" | unknown (rendered verbatim). */
    val state: String?,
    val items: List<TurnFileChange>,
)

// ---------------------------------------------------------------- git (read-only, §9.4)

data class GitSummary(
    val repoRoot: String?,
    val branchName: String?,
    val trackingBranchName: String?,
    val headRefType: String,
    val ahead: Int,
    val behind: Int,
    val isDirty: Boolean,
    val isGitAvailable: Boolean,
    val isRepository: Boolean,
)

/**
 * G53 (vc52 Phase 11) — the desktop's branch comparison (host/index.js
 * `getBranchComparison`, byte-verified asar `12a2fc93…`): the tracking-branch
 * base/head pair + the per-file numstat changes between base...HEAD.
 * `comparisonLabel` renders verbatim ("branch -> tracking" / "HEAD -> tracking")
 * and is null when the repo has no tracking branch (no comparison to show).
 */
data class GitBranchComparison(
    val baseRef: String,
    val headRef: String,
    val comparisonLabel: String?,
    val changes: List<GitBranchFileChange>,
)

data class GitBranchFileChange(
    val path: String,
    val originalPath: String?,
    /** added | deleted | renamed | modified (the git numstat letter, kept raw). */
    val kind: String,
    val added: Long,
    val removed: Long,
)

/** G53 plumbing: `getWorkspaceRepositoryInfo` → {kind: main-tree|linked-worktree|not-repository, isGitAvailable}. */
data class GitRepositoryInfo(
    val workspacePath: String?,
    val kind: String,
    val isGitAvailable: Boolean,
)

data class GitChangeEntry(
    val path: String,
    val repoRelativePath: String,
    val kind: String,
    /** staged | unstaged | untracked | conflicted (tolerant — unknown renders verbatim). */
    val section: String,
    val added: Long,
    val removed: Long,
)

data class GitFileDiff(
    val path: String,
    /** patch | binary | truncated | unavailable (tolerant). */
    val availability: String,
    /** Unified-diff TEXT (git plane) — the §22 parser's input. */
    val patch: String?,
)

data class GitCommit(
    val hash: String,
    val parents: List<String>,
    val refs: List<String>,
    val subject: String,
    val authorName: String?,
    val authoredAtMs: Long?,
)

// ---------------------------------------------------------------- files (read-only, §23.4)

data class WorkspaceFileEntry(
    val name: String,
    /** Desktop path — display/navigation token ONLY (INV-16: never joined locally). */
    val path: String,
    val isDirectory: Boolean,
    val isSymbolicLink: Boolean,
)

data class WorkspaceTextSlice(
    val path: String,
    val content: String,
    val offset: Long,
    val bytesRead: Long,
    val totalBytes: Long,
    val truncated: Boolean,
    val isBinary: Boolean,
) {
    val hasMore: Boolean get() = offset + bytesRead < totalBytes
}

// ---------------------------------------------------------------- model/mode picker (§19.5)

data class ModelProviderOption(
    val providerId: String,
    val providerName: String?,
    val models: List<ModelOption>,
    /** D4: the desktop provider-group `labelBadge` — wire-fed, never synthesized. */
    val badge: String? = null,
)
