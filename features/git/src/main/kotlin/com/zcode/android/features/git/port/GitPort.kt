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
}
