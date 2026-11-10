package com.zcode.android.features.git

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.GitBranchComparison
import com.zcode.android.domain.model.GitBranchFileChange
import com.zcode.android.domain.model.GitChangeEntry
import com.zcode.android.domain.model.GitCommit
import com.zcode.android.domain.model.GitFileDiff
import com.zcode.android.domain.model.GitSummary
import com.zcode.android.features.git.port.GitPort
import com.zcode.android.features.git.ui.GitScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * G53 (vc52 Phase 11) + N-BG-2 (vc52 Phase 11) UI legs: the Git screen renders the
 * branch comparison (the desktop's getBranchComparison — label + per-file changes
 * with +added −removed), and a REFUSED comparison read renders the typed reason
 * (never a silent no-op — the recorded live face was an empty area).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class BranchComparisonUiTest {

    private open class ComparisonPort(
        var comparison: GitBranchComparison? = null,
        var comparisonError: String? = null,
    ) : GitPort {
        override suspend fun summary(): GitSummary? = GitSummary(
            repoRoot = "/ws", branchName = "main", trackingBranchName = "origin/main",
            headRefType = "branch", ahead = 0, behind = 0, isDirty = false,
            isGitAvailable = true, isRepository = true,
        )

        override suspend fun changes(sourceId: String): List<GitChangeEntry> = listOf(
            GitChangeEntry(
                path = "/ws/src/main.rs", repoRelativePath = "src/main.rs",
                kind = "modified", section = sourceId, added = 3, removed = 1,
            ),
        )

        override suspend fun diff(path: String, staged: Boolean?): GitFileDiff? = null
        override suspend fun commits(skip: Int, maxCount: Int): GitPort.CommitsPage =
            GitPort.CommitsPage(emptyList<GitCommit>(), false)

        override suspend fun branchComparison(): GitBranchComparison? {
            comparisonError?.let { throw IllegalStateException(it) }
            return comparison
        }

        override fun workspacePath(): String? = "/ws"
    }

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `the comparison renders the label and the per-file changes`() {
        val port = ComparisonPort(
            comparison = GitBranchComparison(
                baseRef = "origin/main", headRef = "main", comparisonLabel = "main -> origin/main",
                changes = listOf(
                    GitBranchFileChange("src/feature.ts", null, "added", 42, 0),
                    GitBranchFileChange("src/readme.md", null, "modified", 3, 7),
                ),
            ),
        )
        rule.setContent { ZCodeTheme { GitScreen(port = port) } }
        rule.waitForIdle()
        rule.onNodeWithTag("git_branch_comparison")
        rule.onNodeWithText("main -> origin/main")
        rule.onNodeWithText("2 changed file(s) vs the tracking branch")
        rule.onNodeWithTag("git_comparison_file_src/feature.ts")
        rule.onNodeWithText("+42 −0")
        rule.onNodeWithText("+3 −7")
    }

    @Test
    fun `a refused comparison renders the typed reason (never a silent no-op)`() {
        val port = ComparisonPort(comparisonError = "fault.git.notAvailable")
        rule.setContent { ZCodeTheme { GitScreen(port = port) } }
        rule.waitForIdle()
        rule.onNodeWithTag("git_comparison_error")
        rule.onNodeWithText("Branch comparison unavailable — the desktop refused the read")
        rule.onNodeWithText("fault.git.notAvailable")
    }

    @Test
    fun `a comparison with no tracking branch renders nothing (no comparison exists)`() {
        val port = ComparisonPort(
            comparison = GitBranchComparison(
                baseRef = "", headRef = "HEAD", comparisonLabel = null, changes = emptyList(),
            ),
        )
        rule.setContent { ZCodeTheme { GitScreen(port = port) } }
        rule.waitForIdle()
        rule.onAllNodesWithTag("git_branch_comparison").assertCountEquals(0)
        rule.onAllNodesWithTag("git_comparison_error").assertCountEquals(0)
    }
}
