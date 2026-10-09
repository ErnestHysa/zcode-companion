package com.zcode.android.features.tasks

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.ids.WorkspaceIdentityKey
import com.zcode.android.domain.model.Task
import com.zcode.android.domain.model.TaskStatus
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.features.tasks.ui.TaskListScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Phase 11 §30 acceptance items, scripted:
 *  1. every interactive element exposes a semantics label (§30.1);
 *  2. touch targets ≥ 48 dp for the task-list interactives (§30.2);
 *  3. the task list renders and operates at 200% font scale (§30.3);
 *  4. TalkBack traversal order matches visual (top→bottom) order (§30.4, scripted leg);
 *  6. status is never color-only — textual label accompanies every rail (§30.6).
 * Item 5 (reduced motion) lives in core:ui MotionAndLocalizationTest.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class AccessibilityAuditTest {

    @get:Rule
    val rule = createComposeRule()

    private fun task(id: String, status: TaskStatus, title: String = "Task $id") = Task(
        taskId = id,
        title = title,
        status = status,
        workspace = WorkspaceIdentityKey("/ws/demo-api"),
        createdAtMs = 1,
        updatedAtMs = System.currentTimeMillis() - 120_000,
    )

    private fun readyState() = TasksUiState(
        phase = ConnectionPhase.CONNECTED,
        loadState = TasksUiState.Load.READY,
        endpointName = "TestBook",
        lastRefreshAtMs = 1,
        groups = listOf(
            TaskGroup(
                "/ws/demo-api", "demo-api",
                listOf(
                    task("a", TaskStatus.RUNNING, "Fix authentication token refresh"),
                    task("b", TaskStatus.WAITING, "Add rate limit middleware"),
                ),
            ),
        ),
        taskCount = 2,
    )

    private fun setContent(fontScale: Float = 1f, onTaskClick: (Task) -> Unit = {}) {
        rule.setContent {
            val d = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides Density(d.density, fontScale = fontScale),
            ) {
                ZCodeTheme {
                    TaskListScreen(
                        readyState(),
                        onTaskClick = onTaskClick, onOpenSettings = {}, onOpenConnection = {},
                        onRetry = {}, onRefresh = {},
                    )
                }
            }
        }
    }

    /** Public depth-first walk of the semantics tree (traversal order). */
    private fun allNodes(): List<androidx.compose.ui.semantics.SemanticsNode> {
        val root = rule.onRoot().fetchSemanticsNode()
        val out = ArrayList<androidx.compose.ui.semantics.SemanticsNode>()
        fun walk(n: androidx.compose.ui.semantics.SemanticsNode) {
            out += n
            n.children.forEach { walk(it) }
        }
        walk(root)
        return out
    }

    private fun clickables(): List<androidx.compose.ui.semantics.SemanticsNode> =
        allNodes().filter { it.config.getOrNull(SemanticsActions.OnClick) != null }

    /** §30.1 — every clickable node carries a text or contentDescription label. */
    @Test
    fun everyInteractiveNode_hasALabel() {
        setContent()
        val clickables = clickables()
        assertTrue("expected clickable nodes", clickables.size >= 3)
        val unlabelled = clickables.filter { node ->
            val hasText = node.config.getOrNull(SemanticsProperties.Text)?.isNotEmpty() == true
            val hasDesc = node.config.getOrNull(SemanticsProperties.ContentDescription)?.isNotEmpty() == true
            !hasText && !hasDesc
        }
        assertEquals("every clickable must carry a label", 0, unlabelled.size)
    }

    /** §30.4 (scripted leg) — traversal order == visual top→bottom order. */
    @Test
    fun traversalOrder_matchesVisualOrder() {
        setContent()
        val clickables = clickables()
        assertTrue(clickables.size >= 3)
        val ys = clickables.map { it.boundsInRoot.center.y }
        assertEquals("traversal order must match visual order", ys, ys.sorted())
    }

    /** §30.2 — interactive targets ≥ 48 dp tall (icon buttons and rows). */
    @Test
    fun touchTargets_meetMinimums() {
        setContent()
        val density = rule.density.density
        val tooSmall = clickables().filter { it.boundsInRoot.height / density < 48f }
        assertTrue("targets below 48dp: ${tooSmall.map { it.boundsInRoot }}", tooSmall.isEmpty())
    }

    /** §30.3 — 200% font scale: rows render, titles remain visible, taps still land. */
    @Test
    fun rendersOperable_at200PercentFontScale() {
        var opened: Task? = null
        setContent(fontScale = 2f, onTaskClick = { opened = it })
        rule.onNodeWithText("Fix authentication token refresh").assertExists()
        rule.onNodeWithTag("task_row_a").assertHasClickAction().performClick()
        rule.runOnIdle { assertEquals("a", opened?.taskId) }
    }

    /** §30.6 — status text label present beside the colored rail (glyph+label+color). */
    @Test
    fun statusNeverColorOnly_labelsPresent() {
        setContent()
        rule.onNodeWithText("Running").assertExists()
        rule.onNodeWithText("Needs attention").assertExists()
        val merged = (rule.onNodeWithTag("task_row_a").fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.ContentDescription))?.joinToString(" ").orEmpty()
        assertTrue("row a11y description must carry the status", merged.contains("Running"))
    }
}
