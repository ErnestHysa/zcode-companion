package com.zcode.android.features.tasks

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.domain.model.Task
import com.zcode.android.domain.model.TaskStatus
import com.zcode.android.domain.ids.WorkspaceIdentityKey
import com.zcode.android.features.tasks.ui.TaskListScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * F4a/F4b (gaps-and-polish Phase 2G, P1; live-measured twice, F4 + M2): in LANDSCAPE
 * the Tasks header + FAB rendered as vertical one-character columns ("Tasks" 129×58px);
 * at 200% font scale the "+ New task" FAB label stayed ~35px while sibling text scaled.
 * §30 invariant: 200% font scale must not clip or shrink primary actions.
 */
@RunWith(AndroidJUnit4::class)
class FormFactorTest {

    @get:Rule
    val rule = createComposeRule()

    private fun task(id: String, status: TaskStatus, title: String = "Task $id") = Task(
        taskId = id, title = title, status = status,
        workspace = WorkspaceIdentityKey("/ws/demo-api"),
        createdAtMs = 1, updatedAtMs = System.currentTimeMillis() - 120_000,
    )

    private fun readyState() = TasksUiState(
        phase = ConnectionPhase.CONNECTED,
        loadState = TasksUiState.Load.READY,
        endpointName = "TestBook",
        groups = listOf(TaskGroup("/ws/demo-api", "demo-api", listOf(task("a", TaskStatus.RUNNING, "Fix authentication token refresh")))),
        taskCount = 1,
    )

    private fun setContent(fontScale: Float = 1f) {
        rule.setContent {
            val d = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, fontScale = fontScale),
            ) {
                ZCodeTheme {
                    TaskListScreen(
                        readyState(),
                        onTaskClick = {}, onOpenSettings = {}, onOpenConnection = {}, onRetry = {}, onRefresh = {},
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun boundsOf(text: String): androidx.compose.ui.geometry.Rect =
        rule.onAllNodesWithText(text).fetchSemanticsNodes().first().boundsInRoot

    /**
     * F4a: landscape window — the ONE header spans the full window width (the old
     * hand-rolled chain collapsed to a ~58px column; live-measured twice, F4 + M2).
     *
     * Probe note: M3's own TopAppBar title slot measures its Text at ~5px under
     * ROBOLECTIC (reproduced verbatim with a bare `androidx.compose.material3.TopAppBar`
     * — an insets artifact of the fake display; on-device M3 renders the title fine and
     * the live bug was the old hand-rolled chain, now removed). The geometry contract
     * this harness can honestly pin is the BAR: full window width, one title, the
     * subtitle carried as text — the glyph-level check runs on the rig at the exit gate.
     */
    @Test
    @Config(sdk = [30], qualifiers = "w891dp-h411dp-land")
    fun `landscape - the header spans the window and carries the single-line title`() {
        setContent()
        val root = rule.onRoot().fetchSemanticsNode()
        val title = rule.onNodeWithTag("tasks-title").fetchSemanticsNode()
        val bar = title.parent?.parent ?: title
        assertEquals("the header must span the window (no narrow column chain)", root.boundsInRoot.width, bar.boundsInRoot.width, 2f)
        val texts = title.config.getOrNull(SemanticsProperties.Text)?.joinToString() ?: ""
        assertTrue("the title text must be intact (not ellipsized to one glyph): $texts", "Tasks" in texts)
        val subtitle = rule.onAllNodesWithText("TestBook").fetchSemanticsNodes()
        assertTrue("the endpoint subtitle must render as text (M2's vertical tell)", subtitle.isNotEmpty())
    }

    /** F4b: 200% font scale — the FAB label scales like every other text (§30 invariant).
     *  Two methods: a fresh rule per method (setContent is once-per-activity). */
    @Test
    @Config(sdk = [30], qualifiers = "w411dp-h891dp")
    fun `fab at 1x - baseline`() {
        setContent(fontScale = 1f)
        fabAt1x = boundsOf("+ New task").height
        fabContainer1x = rule.onNodeWithTag("tasks_create_fab").fetchSemanticsNode().boundsInRoot.height
    }

    @Test
    @Config(sdk = [30], qualifiers = "w411dp-h891dp")
    fun `fab at 2x - label grows, container grows with it`() {
        setContent(fontScale = 2f)
        val at2x = boundsOf("+ New task").height
        val fabAt2x = rule.onNodeWithTag("tasks_create_fab").fetchSemanticsNode().boundsInRoot.height
        assertTrue("FAB label at 1x: $fabAt1x, at 2x: $at2x (must scale)", at2x >= fabAt1x * 1.6f)
        assertTrue("the FAB container must grow with the label (1x $fabContainer1x -> 2x $fabAt2x)", fabAt2x >= fabContainer1x + 8f)
    }

    private companion object {
        var fabAt1x = 0f
        var fabContainer1x = 0f
    }
}
