package com.zcode.android.features.git.port

import com.zcode.android.domain.model.GitChangeEntry
import com.zcode.android.domain.model.GitCommit
import com.zcode.android.domain.model.GitFileDiff
import com.zcode.android.domain.model.GitSummary

/**
 * Consumer-side port for the §9.4 git panel (INV-2/INV-6). The READ surface is the
 * verified `git` channel subset (phase9-channels.md §2): summary/changes/diff/
 * commit-graph. The WRITE tier is ADR-021 as shipped: stage/unstage + the branch
 * verbs (switch/create-and-switch) — G32 (vc51 Phase 4C) confirmed the stale
 * "no stage/unstage/commit/push/branch methods EXIST" claim wrong in both
 * directions; commit/push AUTHORING remains desktop-side (the dated ADR-021
 * boundary recorded in the vc51 report).
 */
interface GitPort {

    /**
     * Workspace repository summary; `null` = the channel refused or the workspace
     * is not a repository → the panel renders its disabled/empty state (§13.3).
     */
    suspend fun summary(): GitSummary?

    /** `getChanges(sourceId)` — sourceId: "unstaged" | "staged" (verified set). */
    suspend fun changes(sourceId: String): List<GitChangeEntry>

    /** `getDiff` — unified-diff TEXT for the §22 renderer (`availability` gates rendering). */
    suspend fun diff(path: String, staged: Boolean?): GitFileDiff?

    /** `getCommitGraph` page + hasMore (maxCount bounded client-side). */
    suspend fun commits(skip: Int, maxCount: Int): CommitsPage?

    data class CommitsPage(val commits: List<GitCommit>, val hasMore: Boolean)

    /** The workspace path this panel is scoped to (display-only, INV-16). */
    fun workspacePath(): String?

    // ---------------------------------------------------------------- Phase 5B (A3.11): checkpoints

    /**
     * `createCheckpoint {workspacePath}` — a workspace snapshot (git write-tree/commit-tree);
     * null = refused (the channel is unavailable or the workspace is not a repository).
     */
    suspend fun createCheckpoint(): com.zcode.android.domain.model.CheckpointMeta? = null

    /**
     * vc51 Phase 4A (CKPT-HYDRATE): `listCheckpoints {workspacePath}` — the host's
     * checkpoint history, so the card hydrates on entry and snapshots created before an
     * app restart stay listed/restorable. Empty = the channel has no list word (older
     * hosts) or it refused — the session-known list still applies.
     */
    suspend fun listCheckpoints(): List<com.zcode.android.domain.model.CheckpointMeta> = emptyList()

    /** `diffCheckpoints` between two session-known checkpoint ids; null = unavailable. */
    suspend fun diffCheckpoints(fromCheckpointId: String, toCheckpointId: String): com.zcode.android.domain.model.CheckpointDiff? = null

    /**
     * `restoreBetweenCheckpoints` (⚠ destructive: overwrites worktree files) — the UI
     * warns first; `force` (overwriting local modifications) rides a SECOND confirm.
     * Null = refused.
     */
    suspend fun restoreCheckpoints(fromCheckpointId: String, toCheckpointId: String, force: Boolean): com.zcode.android.domain.model.CheckpointRestore? = null

    // ---------------------------------------------------------------- Phase 5D (ADR-021): the git write tier

    /** Tier A (reversible, one tap): stage/unstage the given change paths. */
    suspend fun stagePaths(paths: List<String>): Boolean = false
    suspend fun unstagePaths(paths: List<String>): Boolean = false

    /**
     * Tier C (DESTRUCTIVE — destroys uncommitted work irreversibly): the transport word
     * is reachable ONLY through the confirmed path (the ADR-020/021 typed-confirm pin:
     * zero calls without the DISCARD confirm).
     */
    suspend fun discardPaths(paths: List<String>, staged: Boolean): Boolean = false

    /** Tier B: commit (identity-missing is a BLOCKED outcome, never a send); null = refused. */
    suspend fun commit(message: String, stagedOnly: Boolean): com.zcode.android.domain.model.GitCommitResult? = null

    /** Tier B: AI generate/regenerate; null = unavailable (the sheet keeps the manual field). */
    suspend fun generateCommitMessage(includeUnstaged: Boolean): String? = null

    /** Tier B: push (the confirm names the branch + ahead/behind first); null = refused. */
    suspend fun push(): com.zcode.android.domain.model.GitPushResult? = null

    /** Tier B: the branch switcher; the result carries the TYPED issue map on refusal. */
    suspend fun switchBranch(targetBranchName: String): com.zcode.android.domain.model.GitBranchResult? = null
    suspend fun createBranchAndSwitch(branchName: String): com.zcode.android.domain.model.GitBranchResult? = null
    suspend fun localBranches(): com.zcode.android.domain.model.GitBranchList? = null
    suspend fun identity(): com.zcode.android.domain.model.GitIdentity? = null
}
