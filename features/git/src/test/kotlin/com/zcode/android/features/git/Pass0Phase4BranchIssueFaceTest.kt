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
import com.zcode.android.domain.model.GitBranchIssue
import com.zcode.android.domain.model.GitBranchList
import com.zcode.android.domain.model.GitBranchResult
import com.zcode.android.domain.model.GitChangeEntry
import com.zcode.android.domain.model.GitCommit
import com.zcode.android.domain.model.GitFileDiff
import com.zcode.android.domain.model.GitSummary
import com.zcode.android.features.git.port.GitPort
import com.zcode.android.features.git.ui.GitScreen
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * F-P40-C (pass-0 Phase 4): the branch TYPED-ISSUE ack was swallowed — a refused
 * switch was indistinguishable from success. The sheet closes before the async
 * result, onDismiss nulls the issue, and the only render target lived INSIDE the
 * closed sheet; the wire's message/detail were decoded but never rendered (the
 * "detail copyable" doc was unimplemented). The issue face must render AT ACK TIME
 * (pane-level, since the sheet is already closed) with the message text, the detail
 * + the promised copy affordance.
 */
private val ISSUE_MESSAGE = "Untracked files would be overwritten by switching branches."
private val ISSUE_DETAIL = "git switch: scratch/x.txt would be overwritten by checkout."

@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class Pass0Phase4BranchIssueFaceTest {

    @get:Rule
    val rule = createComposeRule()


    /** The desktop-faithful SUCCESS-with-issues ack (the mock's MOCK_GIT_BRANCH_ISSUE shape). */
    private open class IssuePort : GitPort {
        override suspend fun summary(): GitSummary? = GitSummary(
            repoRoot = "/ws", branchName = "main", trackingBranchName = "origin/main",
            headRefType = "branch", ahead = 0, behind = 0, isDirty = false,
            isGitAvailable = true, isRepository = true,
        )

        override suspend fun changes(sourceId: String): List<GitChangeEntry> = emptyList()

        override suspend fun diff(path: String, staged: Boolean?): GitFileDiff? = null

        override suspend fun commits(skip: Int, maxCount: Int): GitPort.CommitsPage =
            GitPort.CommitsPage(emptyList<GitCommit>(), false)

        override fun workspacePath(): String? = "/ws"

        override suspend fun localBranches(): GitBranchList = GitBranchList(
            headRefType = "branch", currentBranchName = "main",
            branches = listOf(
                GitBranchList.Branch("main", "origin/main", true),
                GitBranchList.Branch("feature", null, false),
            ),
        )

        override suspend fun switchBranch(targetBranchName: String): GitBranchResult = GitBranchResult(
            ok = false,
            branchName = null,
            created = false,
            issues = listOf(
                GitBranchIssue(
                    code = "untracked-changes-would-be-overwritten",
                    message = ISSUE_MESSAGE,
                    detail = ISSUE_DETAIL,
                    paths = listOf("scratch/x.txt"),
                ),
            ),
        )
    }

    @Test
    fun `the typed issue renders at ack time with message detail and copy - F-P40-C`() {
        rule.setContent { ZCodeTheme { GitScreen(port = IssuePort()) } }
        rule.waitForIdle()
        rule.onNodeWithTag("git_branches_open").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("branch_switch_feature").performClick()
        rule.waitForIdle()

        // the ack's issue face renders PANE-LEVEL (the sheet closed before the result)
        rule.onNodeWithTag("git_branch_issue_card").assertIsDisplayed()
        rule.onNodeWithText(ISSUE_MESSAGE).assertIsDisplayed()
        rule.onNodeWithText(ISSUE_DETAIL).assertIsDisplayed()
        // the promised copy affordance — the copy ACKNOWLEDGES (the label flips)
        rule.onNodeWithTag("git_branch_issue_copy").assertIsDisplayed()
        rule.onNodeWithTag("git_branch_issue_copy").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Copied").assertIsDisplayed()
        // and the face is dismissable
        rule.onNodeWithTag("git_branch_issue_dismiss").performClick()
        rule.waitForIdle()
        rule.onAllNodesWithTag("git_branch_issue_card").assertCountEquals(0)
    }
}
