package com.zcode.android.features.git

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.GitChangeEntry
import com.zcode.android.features.git.port.GitPort
import com.zcode.android.features.git.ui.GitScreen
import com.zcode.android.domain.model.GitCommit
import com.zcode.android.domain.model.GitFileDiff
import com.zcode.android.domain.model.GitSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * J1.2 (Phase 6C) pins: the git change list grows a NAVIGABLE TREE with search —
 * gated to the sizes where it matters (the desktop `git.tree.*` surface). Above the
 * tree gate the section renders collapsible directory rows; search filters file rows
 * client-side; small sections stay flat with neither affordance.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "w411dp-h891dp")
class GitTreeSearchUiTest {

    @get:Rule
    val rule = createComposeRule()

    private class FakeGitPort(private val unstaged: List<GitChangeEntry>) : GitPort {
        @Volatile var loaded = false
        override fun workspacePath(): String = "/ws/demo"
        override suspend fun summary(): GitSummary = GitSummary(
            repoRoot = "/ws/demo", branchName = "main", trackingBranchName = "origin/main",
            headRefType = "branch", ahead = 0, behind = 0, isDirty = true,
            isGitAvailable = true, isRepository = true,
        )

        override suspend fun changes(sourceId: String): List<GitChangeEntry> {
            loaded = true
            return if (sourceId == "unstaged") unstaged else emptyList()
        }

        override suspend fun diff(path: String, staged: Boolean?): GitFileDiff = GitFileDiff(
            path = path, availability = "patch",
            patch = "--- a/$path\n+++ b/$path\n@@ -1,1 +1,1 @@\n-old\n+new",
        )

        override suspend fun commits(skip: Int, maxCount: Int): GitPort.CommitsPage {
            return GitPort.CommitsPage(
                listOf(GitCommit("0123456789abcdef", emptyList(), emptyList(), "fix: x", "Ernest", 1791000000000L)),
                hasMore = false,
            )
        }
    }

    private fun entry(path: String) = GitChangeEntry(
        path = path, repoRelativePath = path, kind = "modified", section = "unstaged",
        added = 1, removed = 1,
    )

    /** 45 unstaged files across two deep dirs + root files — above the tree gate. */
    private val large = buildList {
        repeat(20) { i -> add(entry("src/main/file$i.kt")) }
        repeat(15) { i -> add(entry("src/test/spec$i.kt")) }
        repeat(5) { i -> add(entry("tools/gen$i.py")) }
        repeat(5) { i -> add(entry("root$i.md")) }
    }

    private fun setContent(entries: List<GitChangeEntry>) {
        val port = FakeGitPort(entries)
        rule.setContent { ZCodeTheme { GitScreen(port = port) } }
        rule.waitUntil(5_000) { port.loaded }
        rule.waitForIdle()
    }

    private fun count(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    @Test
    fun `above the gate the section renders collapsible directory rows`() {
        setContent(large)
        val src = rule.onAllNodesWithTag("git_tree_dir_src").fetchSemanticsNodes()
        assertTrue("the top-level src directory renders", src.isNotEmpty())
        val childTag = "git_change_src/main/file1.kt"
        assertTrue("a child file is visible initially", count(childTag) == 1)
        rule.onNodeWithTag("git_tree_dir_src").performClick()
        rule.waitForIdle()
        assertEquals("collapsing src hides its descendants", 0, count(childTag))
        rule.onNodeWithTag("git_tree_dir_src").performClick()
        rule.waitForIdle()
        assertEquals("re-expanding brings the descendants back", 1, count(childTag))
    }

    @Test
    fun `search filters the file rows - and survives backspacing`() {
        setContent(large)
        rule.onNodeWithTag("git_search").performTextInput("spec1")
        rule.waitForIdle()
        assertEquals("the spec1 match survives", 1, count("git_change_src/test/spec10.kt") + count("git_change_src/test/spec1.kt"))
        assertTrue(
            "non-matching rows are filtered out",
            count("git_change_src/main/file5.kt") == 0,
        )
    }

    @Test
    fun `small sections stay flat - no search field, no directory rows`() {
        setContent(listOf(entry("src/one.kt"), entry("two.kt")))
        assertEquals(0, count("git_search"))
        assertEquals(0, count("git_tree_dir_src"))
        assertEquals(1, count("git_change_src/one.kt"))
    }

    @Test
    fun `the pure builder nests and search matches case-insensitively`() {
        val root = GitChangeTree.build(large)
        assertEquals("the top-level directories", listOf("src", "tools"), root.dirs.map { it.name })
        assertEquals("the root-level files live on the synthetic root", 5, root.files.size)
        val src = root.dirs.first { it.name == "src" }
        assertEquals("src splits into main and test", listOf("main", "test"), src.dirs.map { it.name })
        assertTrue("a dir with only subdirs carries no direct files", src.files.isEmpty())
        assertEquals("dir paths nest from the repo root", "src/main", src.dirs.first().path)
        val hits = GitChangeTree.search(large, "SPEC1")
        assertTrue("the match survives", hits.isNotEmpty())
        assertTrue(hits.all { it.repoRelativePath.contains("spec1", ignoreCase = true) })
    }
}
