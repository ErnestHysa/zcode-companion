package com.zcode.android.features.tasks

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.zcode.android.domain.ids.WorkspaceIdentityKey
import androidx.compose.runtime.mutableStateOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.TaskStatus
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.features.tasks.port.TaskSearchHit
import com.zcode.android.features.tasks.ui.TaskListScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * GAP-A6 + GAP-A7 (vc51 roadmap, sub-phase 1D; one overlay rewrite):
 *
 * A6 — the search field + result rows were bare siblings emitted AFTER the Scaffold
 * at screen-function top level, so they drew at window level with NO inset handling:
 * the field under the system clock (`[44,11][1036,165]` on device), the single hit row
 * at `[0,0]` behind it, taps dead.
 *
 * A7 — search results did NOT replace the grouped list: the Scaffold body (the
 * Automations/Workflows/Pinned/Workspace sections) composed before the screen-level
 * `return`, so the grouped list stayed fully visible under the results.
 *
 * Pins: the field sits BELOW the top bar (inset-padded body, not the status-bar band),
 * hits navigate, and a query REPLACES the grouped list until cleared.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class TaskSearchOverlayTest {

    @get:Rule
    val rule = createComposeRule()

    private fun task(id: String, title: String): com.zcode.android.domain.model.Task =
        com.zcode.android.domain.model.Task(
            taskId = id,
            title = title,
            status = TaskStatus.RUNNING,
            workspace = WorkspaceIdentityKey("/ws/demo-api"),
            createdAtMs = 1,
            updatedAtMs = System.currentTimeMillis() - 120_000,
        )

    private val grouped = TasksUiState(
        phase = ConnectionPhase.CONNECTED,
        loadState = TasksUiState.Load.READY,
        endpointName = "TestBook",
        groups = listOf(
            TaskGroup("/ws/demo-api", "demo-api", listOf(task("a", "Fix authentication token refresh"))),
        ),
        taskCount = 1,
    )

    private val searching = TasksUiState(
        phase = ConnectionPhase.CONNECTED,
        loadState = TasksUiState.Load.READY,
        endpointName = "TestBook",
        groups = grouped.groups,
        taskCount = 1,
        searchQuery = "rate",
        searchResults = listOf(TaskSearchHit("synth-3", "Synthetic task 3", "Add rate limit middleware")),
    )

    private var openedTaskId: String? = null
    private var lastQuery: String? = null

    private fun setContent(state: TasksUiState) {
        openedTaskId = null
        lastQuery = null
        rule.setContent {
            ZCodeTheme {
                TaskListScreen(
                    state = state,
                    onTaskClick = {},
                    onOpenSettings = {},
                    onOpenConnection = {},
                    onRetry = {},
                    onRefresh = {},
                    onSearch = { lastQuery = it },
                    onOpenTaskId = { openedTaskId = it },
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `A6 - the search field sits below the top bar, not in the status-bar band`() {
        setContent(grouped)
        rule.onNodeWithTag("tasks_search_fab").performClick()
        rule.waitForIdle()
        val field = rule.onNodeWithTag("tasks_search_field")
        field.assertIsDisplayed()
        val fieldTop = field.fetchSemanticsNode().positionInRoot.y
        val titleNode = rule.onNodeWithTag("tasks-title").fetchSemanticsNode()
        val titleBottom = titleNode.positionInRoot.y + titleNode.size.height
        assertTrue(
            "the search field must render INSIDE the inset-padded body (below the top " +
                "bar) — field top $fieldTop vs title bottom $titleBottom (GAP-A6: the old " +
                "bare sibling drew at window level, under the system clock)",
            fieldTop >= titleBottom,
        )
    }

    @Test
    fun `A6 - a search hit is visible and tapping it navigates and clears the query`() {
        setContent(searching)
        val hit = rule.onNodeWithTag("tasks_search_hit_synth-3")
        hit.assertIsDisplayed()
        hit.performClick()
        rule.waitForIdle()
        assertEquals("synth-3", openedTaskId)
        assertEquals("", lastQuery)
    }

    @Test
    fun `A7 - an active query REPLACES the grouped list and it returns on clear`() {
        val state = mutableStateOf(searching)
        rule.setContent {
            ZCodeTheme {
                TaskListScreen(
                    state = state.value,
                    onTaskClick = {},
                    onOpenSettings = {},
                    onOpenConnection = {},
                    onRetry = {},
                    onRefresh = {},
                    onSearch = { lastQuery = it },
                    onOpenTaskId = { openedTaskId = it },
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("tasks_search_hit_synth-3").assertIsDisplayed()
        assertTrue(
            "an active query must REPLACE the grouped list (GAP-A7)",
            rule.onAllNodesWithTag("task_row_a").fetchSemanticsNodes().isEmpty(),
        )
        state.value = grouped
        rule.waitForIdle()
        rule.onNodeWithTag("task_row_a").assertIsDisplayed()
        assertTrue(
            "clearing the query restores the grouped list",
            rule.onAllNodesWithTag("tasks_search_hit_synth-3").fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun `A6 - the field carries a close affordance that clears and closes`() {
        val state = mutableStateOf(searching)
        rule.setContent {
            ZCodeTheme {
                TaskListScreen(
                    state = state.value,
                    onTaskClick = {},
                    onOpenSettings = {},
                    onOpenConnection = {},
                    onRetry = {},
                    onRefresh = {},
                    onSearch = { q ->
                        lastQuery = q
                        // The real owner loop: the VM clears the query on close.
                        state.value = if (q.isBlank()) state.value.copy(searchQuery = "", searchResults = emptyList())
                        else state.value.copy(searchQuery = q)
                    },
                    onOpenTaskId = { openedTaskId = it },
                )
            }
        }
        rule.waitForIdle()
        // With a query active the field is ALWAYS present (the FAB hides while the
        // results replace the list) — the close affordance is the exit: the query
        // clears and the grouped list returns.
        rule.onNodeWithTag("tasks_search_field").assertIsDisplayed()
        rule.onNodeWithTag("tasks_search_close").performClick()
        rule.waitForIdle()
        assertEquals("", lastQuery)
        assertTrue(rule.onAllNodesWithTag("tasks_search_field").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithTag("task_row_a").assertIsDisplayed()
    }
}
