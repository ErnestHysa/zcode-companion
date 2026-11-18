package com.zcode.android.features.git

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 * N-BG-2 (vc52 Phase 11): refused git-plane calls SURFACE — the Commit dialog's
 * Generate renders the desktop's refusal reason inline (and the generated message
 * lands IN the field — the old flow dropped both), and an empty History names the
 * reason instead of rendering a lying empty list. The recorded live face:
 * `git.generateCommitMessage refused: fault.mock.unscripted` produced NO user
 * feedback; `git.getChanges refused: fault.git.notAvailable` emptied the tabs.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class GitRefusalSurfaceUiTest {

    private open class RefusalPort(
        var generateOutcome: GitPort.GenerateOutcome = GitPort.GenerateOutcome(null, null),
        var historyRefusal: String? = null,
    ) : GitPort {
        var generateCalls = 0

        override suspend fun summary(): GitSummary? = GitSummary(
            repoRoot = "/ws", branchName = "main", trackingBranchName = "origin/main",
            headRefType = "branch", ahead = 0, behind = 0, isDirty = false,
            isGitAvailable = true, isRepository = true,
        )

        override suspend fun changes(sourceId: String): List<GitChangeEntry> = emptyList()

        override suspend fun diff(path: String, staged: Boolean?): GitFileDiff? = null

        override suspend fun commits(skip: Int, maxCount: Int): GitPort.CommitsPage {
            historyRefusal?.let { throw IllegalStateException(it) }
            return GitPort.CommitsPage(emptyList<GitCommit>(), false)
        }

        override suspend fun branchComparison(): GitBranchComparison? = GitBranchComparison(
            baseRef = "origin/main", headRef = "main", comparisonLabel = "main -> origin/main",
            changes = listOf(GitBranchFileChange("src/feature.ts", null, "added", 42, 0)),
        )

        override suspend fun generateCommitMessageOutcome(includeUnstaged: Boolean): GitPort.GenerateOutcome {
            generateCalls += 1
            return generateOutcome
        }

        override fun workspacePath(): String? = "/ws"
    }

    @get:Rule
    val rule = createComposeRule()

    private fun openCommitSheet(port: RefusalPort) {
        rule.setContent { ZCodeTheme { GitScreen(port = port) } }
        rule.waitForIdle()
        rule.onNodeWithTag("git_commit_open").performClick()
        rule.waitForIdle()
    }

    @Test
    fun `a refused generate renders the typed reason inline`() {
        val port = RefusalPort(generateOutcome = GitPort.GenerateOutcome(null, "fault.git.notAvailable"))
        openCommitSheet(port)
        rule.onNodeWithTag("commit_generate").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("commit_generate_refusal")
        rule.onNodeWithText("fault.git.notAvailable")
        kotlin.test.assertEquals(1, port.generateCalls, "the generate wire FIRED (the outcome just refused)")
    }

    @Test
    fun `a generated message lands in the field (the old flow dropped it)`() {
        val port = RefusalPort(generateOutcome = GitPort.GenerateOutcome("fix: the pinned stack", null))
        openCommitSheet(port)
        rule.onNodeWithTag("commit_generate").performClick()
        rule.waitForIdle()
        // The generated text IS the field's editable value now (the commit button enables).
        rule.onNodeWithText("fix: the pinned stack")
    }

    @Test
    fun `an empty history names the refusal reason`() {
        val port = RefusalPort(historyRefusal = "fault.git.notAvailable")
        rule.setContent { ZCodeTheme { GitScreen(port = port) } }
        rule.waitForIdle()
        rule.onNodeWithTag("git_history_error")
        rule.onNodeWithText("History unavailable — the desktop refused the read")
        rule.onNodeWithText("fault.git.notAvailable")
    }

    @Test
    fun `a healthy history renders no error row`() {
        val port = RefusalPort()
        rule.setContent { ZCodeTheme { GitScreen(port = port) } }
        rule.waitForIdle()
        rule.onAllNodesWithTag("git_history_error").assertCountEquals(0)
    }

    @Test
    fun `an empty history renders the No-commits-yet state - N-BK-1`() {
        // N-BK-1 (app half): the rig half was contract-fixed (2b12fc46); the app half
        // rendered BARE-BLANK on an empty history — a silent-empty face again. The
        // empty state renders (never bare-blank), distinct from the refusal face.
        val port = RefusalPort()
        rule.setContent { ZCodeTheme { GitScreen(port = port) } }
        rule.waitForIdle()
        rule.onAllNodesWithTag("git_history_error").assertCountEquals(0)
        rule.onNodeWithTag("git_history_empty").assertIsDisplayed()
        rule.onNodeWithText("No commits yet").assertIsDisplayed()
    }
}
