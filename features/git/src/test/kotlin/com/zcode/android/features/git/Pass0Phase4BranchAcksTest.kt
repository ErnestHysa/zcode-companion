package com.zcode.android.features.git

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.GitBranchComparison
import com.zcode.android.domain.model.GitBranchList
import com.zcode.android.domain.model.GitBranchResult
import com.zcode.android.domain.model.GitChangeEntry
import com.zcode.android.domain.model.GitCommit
import com.zcode.android.domain.model.GitFileDiff
import com.zcode.android.domain.model.GitSummary
import com.zcode.android.features.git.port.GitPort
import com.zcode.android.features.git.ui.GitScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * F-P40-A (pass-0 Phase 4): the BranchSheet's switch/create WRITE ACKS re-fetched
 * NOTHING in place — the pane kept pre-switch data (branch block, Changes) until
 * re-entry. The commit handler's fetchChanges() asymmetry IS the shape: every write
 * ack re-reads the pane (summary + changes) WITHOUT re-entry.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class Pass0Phase4BranchAcksTest {

    @get:Rule
    val rule = createComposeRule()

    /** The workspace head moves on a stubbed ok ack — the served reads follow it. */
    private open class SwitchablePort(var head: String = "main") : GitPort {
        var changesCalls = 0

        override suspend fun summary(): GitSummary? = GitSummary(
            repoRoot = "/ws",
            branchName = head,
            trackingBranchName = if (head == "main") "origin/main" else null,
            headRefType = "branch", ahead = 0, behind = 0, isDirty = false,
            isGitAvailable = true, isRepository = true,
        )

        override suspend fun changes(sourceId: String): List<GitChangeEntry> {
            changesCalls += 1
            return if (head == "main") listOf(
                GitChangeEntry("/ws/main.kt", "main.kt", "modified", "unstaged", 1, 0),
            ) else listOf(
                GitChangeEntry("/ws/feature.kt", "feature.kt", "modified", "unstaged", 2, 0),
            )
        }

        override suspend fun diff(path: String, staged: Boolean?): GitFileDiff? = null

        override suspend fun commits(skip: Int, maxCount: Int): GitPort.CommitsPage =
            GitPort.CommitsPage(emptyList<GitCommit>(), false)

        override suspend fun branchComparison(): GitBranchComparison? = GitBranchComparison(
            baseRef = "origin/${head}", headRef = head, comparisonLabel = "$head -> origin/${head}",
            changes = emptyList(),
        )

        override fun workspacePath(): String? = "/ws"

        override suspend fun localBranches(): GitBranchList = GitBranchList(
            headRefType = "branch", currentBranchName = head,
            branches = listOf(
                GitBranchList.Branch("main", "origin/main", head == "main"),
                GitBranchList.Branch("feature", null, head == "feature"),
            ),
        )

        override suspend fun switchBranch(targetBranchName: String): GitBranchResult {
            head = targetBranchName
            return GitBranchResult(ok = true, branchName = targetBranchName, created = false, issues = emptyList())
        }

        override suspend fun createBranchAndSwitch(branchName: String): GitBranchResult {
            head = branchName
            return GitBranchResult(ok = true, branchName = branchName, created = true, issues = emptyList())
        }
    }

    private fun setContent(port: GitPort) {
        rule.setContent { ZCodeTheme { GitScreen(port = port) } }
        rule.waitForIdle()
    }

    @Test
    fun `a switch ok ack refetches the pane in place - F-P40-A`() {
        val port = SwitchablePort()
        setContent(port)
        rule.waitUntil(5_000) { port.changesCalls >= 2 }
        val callsBeforeAck = port.changesCalls

        rule.onNodeWithTag("git_branches_open").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("branch_switch_feature").performClick()
        rule.waitForIdle()

        // the sheet closed and the ack landed — the branch block FLIPS without re-entry
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("feature", substring = true).fetchSemanticsNodes().isNotEmpty() &&
                rule.onAllNodesWithText("main", substring = true).fetchSemanticsNodes().isEmpty()
        }
        assertTrue(
            "the switch ack must re-read the pane's changes in place (RED pre-fix: nothing refetched)",
            port.changesCalls > callsBeforeAck,
        )
        // and the served row follows the moved head
        rule.onAllNodesWithTag("git_change_feature.kt", useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertTrue(
            "the Changes rows must follow the moved head without re-entry",
            rule.onAllNodesWithTag("git_change_feature.kt", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun `a create-and-switch ok ack refetches the pane in place - F-P40-A`() {
        val port = SwitchablePort()
        setContent(port)
        rule.waitUntil(5_000) { port.changesCalls >= 2 }
        val callsBeforeAck = port.changesCalls

        rule.onNodeWithTag("git_branches_open").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("branch_new_field").performTextInput("topic/rig")
        rule.onNodeWithTag("branch_create").performClick()
        rule.waitForIdle()

        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("topic/rig", substring = true).fetchSemanticsNodes().isNotEmpty() &&
                rule.onAllNodesWithText("main", substring = true).fetchSemanticsNodes().isEmpty()
        }
        assertTrue(
            "the create ack must re-read the pane's changes in place",
            port.changesCalls > callsBeforeAck,
        )
        assertEquals("topic/rig", port.head)
    }
}
