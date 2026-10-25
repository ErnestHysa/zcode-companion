package com.zcode.android.features.git

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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
 * GAP-16 (android-native-gaps.md): Git screen overhauled to the desktop git-panel
 * parity (GitPane.tsx + GitPaneChangeCard.tsx + git-graph pane):
 *  - working-tree changes grouped by the DESKTOP section order (unstaged dataset =
 *    unstaged/untracked/conflicted; staged separate) with per-section path sort;
 *  - entries show filename + dimmed directory + colored +N −N (no kind badge — the
 *    desktop shows none);
 *  - ONE inline diff expansion at a time, lazily fetched (GitPane.tsx:394);
 *  - branch info (branch, tracking, ahead/behind, dirty) + short-hash history rows.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class GitScreenUiTest {

    @get:Rule
    val rule = createComposeRule()

    private class FakeGitPort(private val withCommits: Boolean = true, private val compact: Boolean = false) : GitPort {
        var diffCalls = 0

        @Volatile
        var loaded = false

        override fun workspacePath(): String = "/ws/demo"

        override suspend fun summary(): GitSummary = GitSummary(
            repoRoot = "/ws/demo",
            branchName = "main",
            trackingBranchName = "origin/main",
            headRefType = "branch",
            ahead = 2,
            behind = 1,
            isDirty = true,
            isGitAvailable = true,
            isRepository = true,
        )

        override suspend fun changes(sourceId: String): List<GitChangeEntry> {
            if (compact) {
                return when (sourceId) {
                    "unstaged" -> listOf(
                        entry("src/b/auth.kt", section = "unstaged", added = 3, removed = 1),
                        entry("zzz/a/first.txt", section = "untracked", added = 10, removed = 0),
                        entry("src/a/alpha.kt", section = "unstaged", added = 1, removed = 2),
                        entry("locked.tmp", section = "conflicted", added = 0, removed = 4),
                    )
                    else -> emptyList() // staged card omitted → everything fits the viewport
                }
            }
            return when (sourceId) {
                "unstaged" -> listOf(
                    entry("src/b/auth.kt", section = "unstaged", added = 3, removed = 1),
                    entry("zzz/a/first.txt", section = "untracked", added = 10, removed = 0),
                    entry("src/a/alpha.kt", section = "unstaged", added = 1, removed = 2),
                    entry("locked.tmp", section = "conflicted", added = 0, removed = 4),
                )
                "staged" -> listOf(entry("src/c/staged.kt", section = "staged", added = 5, removed = 5))
                else -> emptyList()
            }
        }

        override suspend fun diff(path: String, staged: Boolean?): GitFileDiff {
            diffCalls++
            return GitFileDiff(
                path = path,
                availability = "patch",
                patch = "--- a/$path\n+++ b/$path\n@@ -1,1 +1,1 @@\n-old line\n+new line",
            )
        }

        override suspend fun commits(skip: Int, maxCount: Int): GitPort.CommitsPage {
            loaded = true
            if (!withCommits) return GitPort.CommitsPage(emptyList(), false)
            return GitPort.CommitsPage(
                listOf(
                    commit("0123456789abcdef", "fix: auth token refresh", "Ernest"),
                    commit("fedcba9876543210", "feat: rate limit", "Ernest"),
                ),
                hasMore = false,
            )
        }

        private fun entry(path: String, section: String, added: Long, removed: Long) =
            GitChangeEntry(
                path = path,
                repoRelativePath = path,
                kind = "modified",
                section = section,
                added = added,
                removed = removed,
            )

        private fun commit(hash: String, subject: String, author: String) = GitCommit(
            hash = hash,
            parents = emptyList(),
            refs = emptyList(),
            subject = subject,
            authorName = author,
            authoredAtMs = 1791000000000L,
        )
    }

    private val port = FakeGitPort()
    private val compactPort = FakeGitPort(withCommits = false, compact = true)

    private fun setContent(thePort: FakeGitPort = compactPort) {
        rule.setContent { ZCodeTheme { GitScreen(port = thePort) } }
        rule.waitUntil(5_000) { thePort.loaded }
    }

    @Test
    fun unstagedSections_groupInDesktopOrder_sortedByPathWithinSection() {
        // Desktop section ORDER (useGitRepository SECTION_ORDER_BY_SOURCE) — the
        // screen must declare the same fixed order per dataset.
        assertEquals(
            listOf("unstaged", "untracked", "conflicted"),
            com.zcode.android.features.git.ui.unstagedSectionOrder,
        )
        assertEquals(listOf("staged"), com.zcode.android.features.git.ui.stagedSectionOrder)

        setContent()
        // GROUPING: each desktop section header renders and carries its own entries;
        // the staged dataset renders no unstaged-dataset section labels.
        rule.onNodeWithTag("git_section_unstaged", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("git_section_untracked", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("git_section_conflicted", useUnmergedTree = true).assertExists()
        rule.onAllNodesWithTag("git_section_staged", useUnmergedTree = true).assertCountEquals(0)
        // Per-section path sort: alpha.kt before auth.kt inside "unstaged".
        // Phase 5D: the Tier-A row actions made rows taller — pixel geometry went
        // viewport-dependent (off-viewport text carries zero bounds), so the sort is
        // pinned by the ROWS' composition order: semantics tree order IS render order.
        val alpha = rule.onAllNodesWithTag("git_change_src/a/alpha.kt", useUnmergedTree = true)
            .fetchSemanticsNodes()
        val auth = rule.onAllNodesWithTag("git_change_src/b/auth.kt", useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertTrue("rows missing: alpha=${alpha.size} auth=${auth.size}", alpha.size == 1 && auth.size == 1)
        // Order pin: the alpha row appears before auth in the root's tree walk
        // (semantics tree order IS composition/render order; pixel bounds went
        // viewport-dependent once the Tier-A row actions landed).
        val root = rule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
        val order = ArrayList<String>()
        fun walk(node: SemanticsNode) {
            if (node.config.contains(SemanticsProperties.TestTag)) order.add(node.config[SemanticsProperties.TestTag])
            node.children.forEach { walk(it) }
        }
        walk(root)
        val a = order.indexOfFirst { it.endsWith("src/a/alpha.kt") }
        val b = order.indexOfFirst { it.endsWith("src/b/auth.kt") }
        assertTrue("per-section path sort broken: $order", a in 0 until b)
    }

    @Test
    fun tappingAChange_expandsItsInlineDiff_singleAtATime() {
        setContent() // compactPort
        // Phase 5D: scroll the row into the viewport first (the Tier-A row actions made
        // rows taller — off-viewport rows have zero bounds and can't take a click).
        rule.onNodeWithTag("git_list").performScrollToNode(hasText("alpha.kt", substring = true))
        rule.onNodeWithText("alpha.kt", substring = true).performClick()
        rule.waitForIdle()
        rule.waitUntil(5_000) { compactPort.diffCalls >= 1 }
        rule.onNodeWithTag("git_inline_diff", useUnmergedTree = true).assertExists()
        val before = compactPort.diffCalls
        // Opening another path REPLACES the expansion (desktop: one at a time).
        rule.onNodeWithTag("git_list").performScrollToNode(hasText("auth.kt", substring = true))
        rule.onNodeWithText("auth.kt", substring = true).performClick()
        rule.waitForIdle()
        rule.waitUntil(5_000) { compactPort.diffCalls > before }
        val expansions = rule.onAllNodesWithTag("git_inline_diff", useUnmergedTree = true)
        assertEquals(1, expansions.fetchSemanticsNodes().count())
    }

    @Test
    fun branchInfo_andHistoryRender() {
        setContent(port) // full port with commits
        rule.onAllNodesWithText("main", substring = true).onFirst().assertIsDisplayed()
        rule.onNodeWithText("↑2 ↓1", substring = true).assertIsDisplayed()
        rule.onNodeWithText("origin/main", substring = true).assertIsDisplayed()
        // History rows: 7-char short hash (git-graph getShortHash) + subject.
        rule.onNodeWithTag("git_list").performScrollToNode(hasText("fix: auth token refresh"))
        rule.onNodeWithText("0123456", substring = true).assertIsDisplayed()
        rule.onNodeWithText("fix: auth token refresh").assertIsDisplayed()
    }
}
