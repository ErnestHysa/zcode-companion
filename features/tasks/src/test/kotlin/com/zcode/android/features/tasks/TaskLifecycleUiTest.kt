package com.zcode.android.features.tasks

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.Task
import com.zcode.android.domain.model.TaskStatus
import com.zcode.android.domain.model.Task.TaskSummary
import com.zcode.android.domain.ids.WorkspaceIdentityKey
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.features.tasks.ui.TaskListScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * L6.3+J4 + H1.1 (gaps-and-polish Phase 4D) UI pins: the row's "⋯" overflow offers
 * Rename / Archive / Delete (the lifecycle the phone lacked entirely); the confirms
 * carry the desktop's copy ("records cannot be recovered" / "can be restored later");
 * the decoded-but-unrendered change stats (+N −N) render on the row; the search field
 * is reachable from the FAB and the hits replace the list with snippets.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class TaskLifecycleUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun task(id: String, title: String = "Fix auth", files: Long = 0) = Task(
        taskId = id, title = title, status = TaskStatus.RUNNING,
        workspace = WorkspaceIdentityKey("/ws/demo"), createdAtMs = 1, updatedAtMs = 2,
        summary = if (files > 0) TaskSummary(additions = 12, deletions = 3, files = files) else null,
    )

    private fun setContent(vararg tasks: Task) {
        calls.clear()
        rule.setContent {
            ZCodeTheme {
                TaskListScreen(
                    state = TasksUiState(
                            loadState = TasksUiState.Load.READY,
                        endpointName = "TestBook",
                        lastRefreshAtMs = 1,
                        groups = listOf(TaskGroup("/ws/demo", "demo", tasks.toList())),
                        taskCount = tasks.size,
                        searchQuery = "",
                    ),
                    onTaskClick = {},
                    onOpenSettings = {},
                    onOpenConnection = {},
                    onRetry = {},
                    onRefresh = {},
                    onDeleteTask = { calls += "delete:$it" },
                    onRenameTask = { id, title -> calls += "rename:$id:$title" },
                    onSetTaskArchived = { id, archived -> calls += "archive:$id:$archived" },
                    onSearch = { calls += "search:$it" },
                )
            }
        }
        rule.waitForIdle()
    }

    private fun openMenu(taskId: String) {
        rule.onNodeWithTag("task_actions_$taskId").performClick(); rule.waitForIdle()
    }

    @Test
    fun `the row menu offers Rename Archive and Delete and each rides its callback`() {
        setContent(task("t1"))
        openMenu("t1")
        rule.onNodeWithTag("task_menu_rename_t1").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("task_rename_field").performTextInput("Renamed auth")
        rule.onNodeWithTag("task_rename_confirm").performClick(); rule.waitForIdle()
        assertEquals(listOf("rename:t1:Renamed auth"), calls)
    }

    @Test
    fun `delete asks with the desktop's records-cannot-be-recovered copy then confirms`() {
        setContent(task("t2"))
        openMenu("t2")
        rule.onNodeWithTag("task_menu_delete_t2").performClick(); rule.waitForIdle()
        rule.onNodeWithText("All records for this task cannot be recovered.", substring = true).assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTag("task_delete_confirm").fetchSemanticsNodes().filter { false }.size)
        rule.onNodeWithTag("task_delete_confirm").performClick(); rule.waitForIdle()
        assertEquals(listOf("delete:t2"), calls)
    }

    @Test
    fun `archive asks with the can-be-restored-later copy then confirms`() {
        setContent(task("t3"))
        openMenu("t3")
        rule.onNodeWithTag("task_menu_archive_t3").performClick(); rule.waitForIdle()
        rule.onNodeWithText("Archived tasks can be restored later.", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("task_archive_confirm").performClick(); rule.waitForIdle()
        assertEquals(listOf("archive:t3:true"), calls)
    }

    @Test
    fun `change stats render on the row when the wire carries them`() {
        setContent(task("t4", files = 2))
        rule.onNodeWithTag("task_changes_t4").assertIsDisplayed()
        rule.onNodeWithText("+12 −3").assertIsDisplayed()
    }

    @Test
    fun `no summary - no change stats`() {
        setContent(task("t5", files = 0))
        assertEquals(0, rule.onAllNodesWithTag("task_changes_t5").fetchSemanticsNodes().size)
    }

    @Test
    fun `the search FAB reveals the field and typing queries the port`() {
        setContent(task("t6"))
        rule.onNodeWithTag("tasks_search_fab").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("tasks_search_field").assertIsDisplayed()
        rule.onNodeWithTag("tasks_search_field").performTextInput("auth")
        rule.waitForIdle()
        assertTrue("search:auth" in calls)
    }

    @Test
    fun `search hits render with snippets and tapping one opens the task`() {
        var opened: String? = null
        val clicked = mutableListOf<String>()
        rule.setContent {
            ZCodeTheme {
                TaskListScreen(
                    state = TasksUiState(
                            loadState = TasksUiState.Load.READY,
                        groups = listOf(TaskGroup("/ws/demo", "demo", listOf(task("t7")))),
                        taskCount = 1,
                        searchQuery = "auth",
                        searchResults = listOf(
                            com.zcode.android.features.tasks.port.TaskSearchHit("t9", "Fix auth", "refresh token rotation"),
                        ),
                    ),
                    onTaskClick = { opened = it.taskId },
                    onOpenSettings = {},
                    onOpenConnection = {},
                    onRetry = {},
                    onRefresh = {},
                    onSearch = { clicked += it },
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("tasks_search_hit_t9").assertIsDisplayed()
        rule.onNodeWithText("refresh token rotation").assertIsDisplayed()
        rule.onNodeWithTag("tasks_search_hit_t9").performClick(); rule.waitForIdle()
        org.junit.Assert.assertEquals("t9", opened)
        org.junit.Assert.assertEquals("picking a hit clears the query", listOf(""), clicked)
    }
}
