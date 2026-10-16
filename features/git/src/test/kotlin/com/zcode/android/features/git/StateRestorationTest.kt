package com.zcode.android.features.git

import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
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
 * C6-state (gaps-and-polish Phase 2F, P1): the ONE expanded inline diff died on
 * process death (`GitUiState.expanded` lived in plain `remember`). Pinned: it survives.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class StateRestorationTest {

    @get:Rule
    val rule = createComposeRule()

    private val restorer = StateRestorationTester(rule)

    private val port = object : GitPort {
        override suspend fun summary(): GitSummary? = GitSummary("/ws", "main", "origin/main", "branch", 0, 0, true, true, true)
        override suspend fun changes(sourceId: String): List<GitChangeEntry> = if (sourceId == "unstaged") listOf(
            GitChangeEntry(path = "/ws/src/main.rs", repoRelativePath = "src/main.rs", kind = "modified", section = "unstaged", added = 3, removed = 1),
        ) else emptyList()
        override suspend fun diff(path: String, staged: Boolean?): GitFileDiff? = null
        override suspend fun commits(skip: Int, maxCount: Int): GitPort.CommitsPage = GitPort.CommitsPage(emptyList<GitCommit>(), false)
        override fun workspacePath(): String? = "/ws"
    }

    @Test
    fun `the expanded inline diff survives process death`() {
        restorer.setContent { ZCodeTheme { GitScreen(port = port) } }
        rule.waitForIdle()
        rule.onNodeWithTag("git_change_src/main.rs").performClick()
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithTag("git_diff_loading").fetchSemanticsNodes().isNotEmpty())

        restorer.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertTrue(
            "the expanded diff must survive the restore",
            rule.onAllNodesWithTag("git_diff_loading").fetchSemanticsNodes().isNotEmpty(),
        )
    }
}
