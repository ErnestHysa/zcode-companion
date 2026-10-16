package com.zcode.android.features.git

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.GitChangeEntry
import com.zcode.android.domain.model.GitCommit
import com.zcode.android.domain.model.GitFileDiff
import com.zcode.android.domain.model.GitSummary
import com.zcode.android.features.git.port.GitPort
import com.zcode.android.features.git.ui.GitScreen
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** B3-a11y (Phase 2E): git change rows measured ~36dp; rows must be ≥48dp and carry a role. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "w411dp-h891dp")
class GitA11yTest {

    @get:Rule
    val rule = createComposeRule()

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
    fun `change rows are 48dp targets with a role`() {
        rule.setContent { ZCodeTheme { GitScreen(port = port) } }
        rule.waitForIdle()
        val section = rule.onNodeWithTag("git_section_unstaged").fetchSemanticsNode()
        fun clickables(n: SemanticsNode): List<SemanticsNode> =
            (if (n.config.getOrNull(SemanticsActions.OnClick) != null) listOf(n) else emptyList()) + n.children.flatMap { clickables(it) }
        val rows = clickables(section).filter { it.config.getOrNull(SemanticsProperties.Role) != null || true }
        assertTrue("expected a clickable change row", rows.isNotEmpty())
        rows.forEach { r ->
            val h = r.boundsInRoot.height / rule.density.density
            assertTrue("git row ${h}dp tall (needs 48)", h >= 48f)
            assertNotNull("git row must carry a role", r.config.getOrNull(SemanticsProperties.Role))
        }
    }
}
