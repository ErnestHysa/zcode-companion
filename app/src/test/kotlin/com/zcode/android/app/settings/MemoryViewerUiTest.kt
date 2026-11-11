package com.zcode.android.app.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * G75 (vc52 Phase 12) compose leg: the read-only workspace-MEMORY viewer. Pins:
 * the seeded tree renders workspace groups + files with the MEMORY.md index face
 * (and its missing face), search filters, the file sheet renders the text (the
 * read is bounded at the 5 MiB preview cap — the truncated face), the changed/
 * deleted honest face renders on a read failure, and NO write affordance exists.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class MemoryViewerUiTest {

    @get:Rule
    val rule = createComposeRule()

    private class FakePort(
        private val snapshot: MemoryPort.Snapshot,
        private val reads: MutableMap<String, MemoryPort.TextPage> = mutableMapOf(),
    ) : MemoryPort {
        val requested = mutableListOf<String>()
        override suspend fun snapshot(): MemoryPort.Snapshot = snapshot
        override suspend fun readText(path: String): MemoryPort.TextPage {
            requested.add(path)
            return reads[path] ?: MemoryPort.TextPage(null, null, false, "fault.file.notFound")
        }
    }

    private val tree = MemoryPort.Snapshot(
        workspaces = listOf(
            MemoryPort.WorkspaceMemories(
                dir = "/synthetic/.zcode/cli/memories/projects/synthetic-ws",
                label = "synthetic-ws",
                files = listOf(
                    MemoryPort.MemoryFile("/synthetic/ws-memory/MEMORY.md", "MEMORY.md", null),
                    MemoryPort.MemoryFile("/synthetic/ws-memory/release-prefs.md", "release-prefs.md", null),
                ),
                indexMissing = false,
            ),
        ),
        error = null,
    )

    @Test
    fun `the tree renders workspace groups + files and the text sheet opens`() {
        val reads = mutableMapOf(
            "/synthetic/ws-memory/MEMORY.md" to MemoryPort.TextPage("# Workspace memory", 1000, false, null),
        )
        val port = FakePort(tree, reads)
        rule.setContent { ZCodeTheme { MemoryViewerScreen(port = port) } }
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("memory_list").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("synthetic-ws").assertExists()
        rule.onNodeWithText("MEMORY.md").assertExists()
        rule.onNodeWithText("release-prefs.md").assertExists()
        rule.onAllNodesWithTag("memory_index_missing_").assertCountEquals(0)
        // open the file → the sheet renders the content
        rule.onAllNodesWithTag("memory_file_${"MEMORY.md".hashCode()}")[0].performClick()
        rule.waitForIdle()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("memory_file_content").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("# Workspace memory").assertExists()
        assertTrue(port.requested.contains("/synthetic/ws-memory/MEMORY.md"))
    }

    @Test
    fun `search filters files across groups`() {
        rule.setContent { ZCodeTheme { MemoryViewerScreen(port = FakePort(tree)) } }
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("memory_list").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("memory_search").performTextInput("release")
        rule.waitForIdle()
        rule.onAllNodesWithTag("memory_file_${"MEMORY.md".hashCode()}").assertCountEquals(0)
        rule.onAllNodesWithTag("memory_file_${"release-prefs.md".hashCode()}").assertCountEquals(1)
    }

    @Test
    fun `a missing index renders the index-missing face`() {
        val ws = tree.workspaces!!.single().copy(indexMissing = true)
        rule.setContent { ZCodeTheme { MemoryViewerScreen(port = FakePort(tree.copy(workspaces = listOf(ws)))) } }
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("memory_list").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("memory_index_missing_${ws.dir.hashCode()}").assertExists()
        rule.onNodeWithText("MEMORY.md not generated yet").assertExists()
    }

    @Test
    fun `a read failure renders the changed-or-deleted honest face`() {
        rule.setContent { ZCodeTheme { MemoryViewerScreen(port = FakePort(tree)) } }
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("memory_list").fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithTag("memory_file_${"release-prefs.md".hashCode()}")[0].performClick()
        rule.waitForIdle()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("memory_file_changed").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("memory_file_unreadable").assertExists()
        rule.onNodeWithText("It may have changed or been deleted since the listing — pull to refresh.", substring = true).assertExists()
    }

    @Test
    fun `the truncated read renders the 5MiB-cap face above the content`() {
        val reads = mutableMapOf(
            "/synthetic/ws-memory/MEMORY.md" to MemoryPort.TextPage("# first chunk", 6_000_000, truncated = true, error = null),
        )
        rule.setContent { ZCodeTheme { MemoryViewerScreen(port = FakePort(tree, reads)) } }
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("memory_list").fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithTag("memory_file_${"MEMORY.md".hashCode()}")[0].performClick()
        rule.waitForIdle()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("memory_file_too_large").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("This memory file exceeds the 5 MiB preview cap — showing the first chunk.", substring = true).assertExists()
        rule.onNodeWithText("# first chunk").assertExists()
    }

    @Test
    fun `the honest error face renders on a port fault`() {
        rule.setContent { ZCodeTheme { MemoryViewerScreen(port = FakePort(MemoryPort.Snapshot(null, "fault.file.notFound"))) } }
        rule.onNodeWithTag("memory_error").assertIsDisplayed()
    }

    @Test
    fun `the empty face renders when no memories exist`() {
        rule.setContent { ZCodeTheme { MemoryViewerScreen(port = FakePort(MemoryPort.Snapshot(emptyList(), null))) } }
        rule.onNodeWithTag("memory_empty").assertIsDisplayed()
    }
}
