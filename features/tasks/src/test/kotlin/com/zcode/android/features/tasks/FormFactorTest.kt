package com.zcode.android.features.tasks

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.domain.model.Task
import com.zcode.android.domain.model.TaskStatus
import com.zcode.android.domain.ids.WorkspaceIdentityKey
import com.zcode.android.features.tasks.ui.TaskListScreen
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

    /** F4a: landscape window — header text lays out HORIZONTALLY (never one-char columns). */
    @Test
    @Config(sdk = [30], qualifiers = "w891dp-h411dp-land")
    fun `landscape - the Tasks header renders horizontal text`() {
        setContent()
        val title = rule.onNodeWithTag("tasks-title").fetchSemanticsNode()
        assertTrue(
            "title must be wider than tall (F4a vertical-column bug): ${title.boundsInRoot}",
            title.boundsInRoot.width > title.boundsInRoot.height,
        )
        val subtitle = boundsOf("TestBook")
        assertTrue("endpoint name must be horizontal: $subtitle", subtitle.width > subtitle.height)
    }

    /** F4b: 200% font scale — the FAB label scales like every other text (§30 invariant). */
    @Test
    @Config(sdk = [30], qualifiers = "w411dp-h891dp")
    fun `font scale 200 - the FAB label scales, never clips`() {
        setContent(fontScale = 1f)
        val at1x = boundsOf("New task").height
        setContent(fontScale = 2f)
        val at2x = boundsOf("New task").height
        assertTrue("FAB label at 1x: $at1x, at 2x: $at2x (must scale)", at2x >= at1x * 1.6f)
        val fab = rule.onNodeWithTag("tasks_create_fab").fetchSemanticsNode()
        assertTrue(
            "the FAB container must grow with the label: ${fab.boundsInRoot}",
            fab.boundsInRoot.height >= at2x + 16f,
        )
    }
}
