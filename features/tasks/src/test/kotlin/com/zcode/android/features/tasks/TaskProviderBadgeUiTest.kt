package com.zcode.android.features.tasks

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.ids.WorkspaceIdentityKey
import com.zcode.android.domain.model.Task
import com.zcode.android.domain.model.TaskStatus
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.features.tasks.ui.TaskListScreen
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * N-BD-5 (vc52 Phase 13): the task-row provider badge — a bootstrap-decoded
 * `provider` renders the per-task chip (the desktop's per-task model chip);
 * a provider-less row renders none. The chip lives inside the merged row
 * semantics — the UNMERGED tree is the truth surface.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class TaskProviderBadgeUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun rowTask(id: String, provider: String?) = Task(
        taskId = id,
        title = "Task $id",
        status = TaskStatus.IDLE,
        workspace = WorkspaceIdentityKey("/ws/demo-api"),
        createdAtMs = 1,
        updatedAtMs = System.currentTimeMillis() - 60_000,
        workspaceLabel = "demo-api",
        provider = provider,
    )

    private fun state(vararg tasks: Task) = TasksUiState(
        phase = ConnectionPhase.CONNECTED,
        loadState = TasksUiState.Load.READY,
        endpointName = "TestBook",
        groups = listOf(TaskGroup("/ws/demo-api", "demo-api", tasks.toList())),
    )

    private fun setContent(vararg tasks: Task) {
        rule.setContent {
            ZCodeTheme {
                TaskListScreen(
                    state = state(*tasks),
                    onTaskClick = {}, onOpenSettings = {}, onOpenConnection = {}, onRetry = {}, onRefresh = {},
                )
            }
        }
    }

    @Test
    fun `a seeded task with a provider renders the badge`() {
        setContent(rowTask("t-1", provider = "zai-individual-coding-plan"))
        assertTrue(
            "the provider chip renders on the task row",
            rule.onAllNodesWithTag("task_provider_t-1", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun `a provider-less task renders no badge`() {
        setContent(rowTask("t-2", provider = null))
        assertTrue(
            "no chip when the desktop declared no provider",
            rule.onAllNodesWithTag("task_provider_t-2", useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
        )
    }
}
