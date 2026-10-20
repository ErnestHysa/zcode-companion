package com.zcode.android.features.git.port

import com.zcode.android.domain.model.GitChangeEntry
import com.zcode.android.domain.model.GitCommit
import com.zcode.android.domain.model.GitFileDiff
import com.zcode.android.domain.model.GitSummary

/**
 * Consumer-side port for the §9.4 read-only git panel (INV-2/INV-6). The surface
 * is a STRICT read-only subset of the verified `git` channel (phase9-channels.md
 * §2): summary/changes/diff/commit-graph. No stage/unstage/commit/push/branch
 * methods EXIST on this port — INV-9 is enforced by the typed surface.
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

    /** `diffCheckpoints` between two session-known checkpoint ids; null = unavailable. */
    suspend fun diffCheckpoints(fromCheckpointId: String, toCheckpointId: String): com.zcode.android.domain.model.CheckpointDiff? = null

    /**
     * `restoreBetweenCheckpoints` (⚠ destructive: overwrites worktree files) — the UI
     * warns first; `force` (overwriting local modifications) rides a SECOND confirm.
     * Null = refused.
     */
    suspend fun restoreCheckpoints(fromCheckpointId: String, toCheckpointId: String, force: Boolean): com.zcode.android.domain.model.CheckpointRestore? = null
}
