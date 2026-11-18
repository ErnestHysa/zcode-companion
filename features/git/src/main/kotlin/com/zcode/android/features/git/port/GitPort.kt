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

    /**
     * N-BG-2 (vc52 Phase 11): the typed generate outcome — the refusal reason rides
     * the result so the Commit sheet renders it (the recorded live face: the Generate
     * button did NOTHING on `git.generateCommitMessage refused`). [message] non-null
     * = generated; [refusal] non-null = the desktop refused with that reason; both
     * null = no channel (the honest unavailable face).
     */
    data class GenerateOutcome(val message: String?, val refusal: String?)

    suspend fun generateCommitMessageOutcome(includeUnstaged: Boolean): GenerateOutcome {
        val message = generateCommitMessage(includeUnstaged)
        return GenerateOutcome(message = message, refusal = null)
    }

    /** Tier B: push (the confirm names the branch + ahead/behind first); null = refused. */
    suspend fun push(): com.zcode.android.domain.model.GitPushResult? = null

    /**
     * F-P41-A (pass-0 Phase 4): the typed write refusal. [noTarget] = the channel /
     * workspace is unavailable (render the localized unavailable face); otherwise
     * [message] is the desktop's refusal text — rendered VERBATIM (never swallowed
     * to a silent success-only path).
     */
    data class GitWriteRefusal(val noTarget: Boolean, val message: String?)

    /** The typed push outcome: [result] non-null = pushed; [refusal] non-null = refused. */
    data class PushOutcome(val result: com.zcode.android.domain.model.GitPushResult?, val refusal: GitWriteRefusal?)

    /** Default delegates to [push] (which cannot carry the refusal text — the
     * DefaultGitPort override is the honest shape). */
    suspend fun pushOutcome(): PushOutcome = PushOutcome(push(), null)

    /** F-P10-A: the typed commit outcome — a null workspace target is a TYPED refusal
     * (never the silent `?: return null` exit), and a refused call carries its reason. */
    data class CommitOutcome(val result: com.zcode.android.domain.model.GitCommitResult?, val refusal: GitWriteRefusal?)

    /** Default delegates to [commit] (the null-target/refused distinction is the
     * DefaultGitPort override's shape). */
    suspend fun commitOutcome(message: String, stagedOnly: Boolean): CommitOutcome =
        CommitOutcome(commit(message, stagedOnly), null)

    /**
     * G53 (vc52 Phase 11): `getBranchComparison` — the base/head + per-file changes
     * between the tracking branch and HEAD. null = the channel refused (the §13.3
     * disabled state; N-BG-2 surfaces the reason on the UI).
     */
    suspend fun branchComparison(): com.zcode.android.domain.model.GitBranchComparison? = null

    /** G53 plumbing: `getIgnoredPaths` — the subset of [paths] git ignores. */
    suspend fun ignoredPaths(paths: List<String>): List<String>? = null

    /** G53 plumbing: `getWorkspaceRepositoryInfo` — the repo layout kind. */
    suspend fun repositoryInfo(): com.zcode.android.domain.model.GitRepositoryInfo? = null

    /** Tier B: the branch switcher; the result carries the TYPED issue map on refusal. */
    suspend fun switchBranch(targetBranchName: String): com.zcode.android.domain.model.GitBranchResult? = null
    suspend fun createBranchAndSwitch(branchName: String): com.zcode.android.domain.model.GitBranchResult? = null
    suspend fun localBranches(): com.zcode.android.domain.model.GitBranchList? = null
    suspend fun identity(): com.zcode.android.domain.model.GitIdentity? = null
}
