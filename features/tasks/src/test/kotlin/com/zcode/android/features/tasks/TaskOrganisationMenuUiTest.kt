package com.zcode.android.features.tasks

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
 * G69 (vc52 Phase 13): the task-home organisation UI — the overflow menu carries
 * the organize-by (workspace/timeline) + sort (created/updated) + collapse/expand
 * items, each firing its callback; a workspace's declared kind renders the badge
 * chip on the header (the desktop companion's Local/Remote/Conversation badge).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class TaskOrganisationMenuUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun groupTask(id: String, workspace: String = "/ws/demo-api") = Task(
        taskId = id,
        title = "Task $id",
        status = TaskStatus.IDLE,
        workspace = WorkspaceIdentityKey(workspace),
        createdAtMs = 1,
        updatedAtMs = System.currentTimeMillis() - 60_000,
    )

    private fun state(kind: String? = "remote") = TasksUiState(
        phase = ConnectionPhase.CONNECTED,
        loadState = TasksUiState.Load.READY,
        endpointName = "TestBook",
        groups = listOf(
            TaskGroup("/ws/demo-api", "demo-api", listOf(groupTask("a"), groupTask("b")), kind),
        ),
    )

    private fun setContent(
        kind: String? = "remote",
        onOrganizeBy: (TasksUiState.OrganizeBy) -> Unit = {},
        onSortBy: (TasksUiState.SortBy) -> Unit = {},
    ) {
        rule.setContent {
            ZCodeTheme {
                TaskListScreen(
                    state = state(kind),
                    onTaskClick = {}, onOpenSettings = {}, onOpenConnection = {}, onRetry = {}, onRefresh = {},
                    onOrganizeBy = onOrganizeBy,
                    onSortBy = onSortBy,
                )
            }
        }
    }

    private fun openMenu() {
        rule.onNodeWithTag("tasks_more").performClick()
    }

    @Test
    fun `the overflow menu carries the four organisation items`() {
        setContent()
        openMenu()
        rule.onNodeWithTag("tasks_menu_organize_workspace").assertExists()
        rule.onNodeWithTag("tasks_menu_organize_timeline").assertExists()
        rule.onNodeWithTag("tasks_menu_sort_created").assertExists()
        rule.onNodeWithTag("tasks_menu_sort_updated").assertExists()
        rule.onNodeWithTag("tasks_menu_collapse_all").assertExists()
        rule.onNodeWithTag("tasks_menu_expand_all").assertExists()
    }

    @Test
    fun `organize-timeline and sort-created fire their callbacks`() {
        var organize: TasksUiState.OrganizeBy? = null
        var sort: TasksUiState.SortBy? = null
        setContent(
            onOrganizeBy = { organize = it },
            onSortBy = { sort = it },
        )
        openMenu()
        rule.onNodeWithTag("tasks_menu_organize_timeline").performClick()
        openMenu()
        rule.onNodeWithTag("tasks_menu_sort_created").performClick()
        assertEquals(TasksUiState.OrganizeBy.TIMELINE, organize)
        assertEquals(TasksUiState.SortBy.CREATED, sort)
    }

    @Test
    fun `a declared workspace kind renders the badge chip`() {
        setContent(kind = "remote")
        val tag = "tasks_group_kind_" + ("/ws/demo-api".hashCode())
        // The badge sits INSIDE the merged header semantics — the unmerged tree.
        assertTrue(
            "the kind badge chip renders on the workspace header",
            rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun `a kind-less workspace renders no badge`() {
        setContent(kind = null)
        val tag = "tasks_group_kind_" + ("/ws/demo-api".hashCode())
        assertTrue(
            "no badge chip when the desktop declared no kind",
            rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
        )
    }
}
