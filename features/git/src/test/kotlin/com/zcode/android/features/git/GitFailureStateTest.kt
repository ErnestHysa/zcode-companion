package com.zcode.android.features.git

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
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
 * B2.7 (gaps-and-polish Phase 1E, P0): `changes()` errors were swallowed to an
 * EMPTY list — "no changes" rendered when the channel had actually FAILED (a
 * lie), and the Retry button only re-checked `summary()`. Accept: errors render
 * an error state whose Retry re-runs `changes()`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class GitFailureStateTest {

    private class FailingChangesPort : GitPort {
        var changesCalls = 0
        var failChanges = true

        override suspend fun summary(): GitSummary? = GitSummary(
            repoRoot = "/ws", branchName = "main", trackingBranchName = "origin/main",
            headRefType = "branch", ahead = 0, behind = 0, isDirty = false,
            isGitAvailable = true, isRepository = true,
        )

        override suspend fun changes(sourceId: String): List<GitChangeEntry> {
            changesCalls += 1
            if (failChanges) throw IllegalStateException("git channel refused")
            return listOf(
                GitChangeEntry(
                    path = "/ws/src/main.rs", repoRelativePath = "src/main.rs",
                    kind = "modified", section = "unstaged", added = 3, removed = 1,
                ),
            )
        }

        override suspend fun diff(path: String, staged: Boolean?): GitFileDiff? = null
        override suspend fun commits(skip: Int, maxCount: Int): GitPort.CommitsPage =
            GitPort.CommitsPage(emptyList<GitCommit>(), false)

        override fun workspacePath(): String? = "/ws"
    }

    private fun setContent(port: FailingChangesPort) {
        rule.setContent {
            ZCodeTheme { GitScreen(port = port) }
        }
        rule.waitForIdle()
    }

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `a failed changes call renders an error state - not a lying empty list`() {
        val port = FailingChangesPort()
        setContent(port)

        assertTrue(
            "changes() failure must render an error state (RED: errors were swallowed to empty)",
            rule.onAllNodesWithTag("git_changes_error").fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun `the error Retry re-runs changes - and recovery renders the rows`() {
        val port = FailingChangesPort()
        setContent(port)

        val callsAfterLoad = port.changesCalls
        assertTrue("both datasets are fetched on load", callsAfterLoad >= 2)

        rule.onNodeWithTag("git_changes_retry").performClick()
        rule.waitForIdle()
        assertTrue(
            "Retry must re-run changes() (RED: Retry only re-checked summary())",
            port.changesCalls > callsAfterLoad,
        )

        port.failChanges = false
        rule.onNodeWithTag("git_changes_retry").performClick()
        rule.waitForIdle()
        assertEquals(
            "a recovered changes() must render its rows",
            1,
            rule.onAllNodesWithTag("git_section_unstaged").fetchSemanticsNodes().size,
        )
        org.junit.Assert.assertTrue(
            "the recovered row must render",
            rule.onAllNodesWithTag("git_section_unstaged").fetchSemanticsNodes().isNotEmpty() &&
                rule.onAllNodesWithText("src/main.rs").fetchSemanticsNodes().isNotEmpty(),
        )
    }
}
