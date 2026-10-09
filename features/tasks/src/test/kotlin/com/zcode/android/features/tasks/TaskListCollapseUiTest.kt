package com.zcode.android.features.tasks

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.ids.WorkspaceIdentityKey
import com.zcode.android.domain.model.Task
import com.zcode.android.domain.model.TaskStatus
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.features.tasks.ui.TaskListScreen
import com.zcode.android.features.tasks.ui.WorkspaceCollapseStore
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * GAP-14 (the project roadmap): collapsible workspace groups — collapsing hides
 * the group's tasks, the count badge STILL shows totals while collapsed, and the
 * collapse state round-trips through the store (app restart proxy: a freshly
 * constructed screen over the same store starts collapsed).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class TaskListCollapseUiTest {

    @get:Rule
    val rule = createComposeRule()

    private class RecordingStore(initial: Set<String> = emptySet()) : WorkspaceCollapseStore {
        var persisted: Set<String> = initial
        override fun load(): Set<String> = persisted
        override fun save(collapsed: Set<String>) {
            persisted = collapsed
        }
    }

    private val wsKey = "/ws/demo-api"
    private val headerTag = "tasks_group_collapse_${wsKey.hashCode()}"

    private fun task(id: String, title: String) = Task(
        taskId = id,
        title = title,
        status = TaskStatus.RUNNING,
        workspace = WorkspaceIdentityKey(wsKey),
        createdAtMs = 1,
        updatedAtMs = System.currentTimeMillis(),
    )

    private fun state(): TasksUiState = TasksUiState(
        phase = ConnectionPhase.CONNECTED,
        loadState = TasksUiState.Load.READY,
        groups = listOf(
            TaskGroup(wsKey, "demo-api", listOf(task("a", "Fix authentication token refresh"), task("b", "Add rate limit middleware"))),
        ),
        taskCount = 2,
    )

    private fun setContent(store: RecordingStore) {
        rule.setContent {
            ZCodeTheme {
                TaskListScreen(
                    state = state(),
                    onTaskClick = {}, onOpenSettings = {}, onOpenConnection = {}, onRetry = {}, onRefresh = {},
                    collapseStore = store,
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun collapse_hidesTasks_countBadgeStaysVisible_andPersists() {
        val store = RecordingStore()
        setContent(store)
        rule.onNodeWithTag("task_row_a").assertIsDisplayed()
        rule.onNodeWithTag("tasks_group_count_${wsKey.hashCode()}", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("2").assertIsDisplayed()

        rule.onNodeWithTag(headerTag).performClick()
        rule.waitForIdle()

        rule.onAllNodesWithTag("task_row_a").assertCountEquals(0)
        rule.onAllNodesWithTag("task_row_b").assertCountEquals(0)
        // Acceptance: the count badge still shows totals while collapsed.
        rule.onNodeWithTag("tasks_group_count_${wsKey.hashCode()}", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("2").assertIsDisplayed()
        // Persisted on toggle.
        assertEquals(setOf(wsKey), store.persisted)
    }

    @Test
    fun expandAgain_showsTasks() {
        val store = RecordingStore(initial = setOf(wsKey))
        setContent(store)
        rule.onAllNodesWithTag("task_row_a").assertCountEquals(0)
        rule.onNodeWithTag(headerTag).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("task_row_a").assertIsDisplayed()
        assertEquals(emptySet<String>(), store.persisted)
    }

    @Test
    fun freshScreenOverSameStore_startsCollapsed_restartProxy() {
        val store = RecordingStore(initial = setOf(wsKey))
        setContent(store)
        rule.onAllNodesWithTag("task_row_a").assertCountEquals(0)
        rule.onNodeWithTag("tasks_group_count_${wsKey.hashCode()}", useUnmergedTree = true).assertIsDisplayed()
    }

}
