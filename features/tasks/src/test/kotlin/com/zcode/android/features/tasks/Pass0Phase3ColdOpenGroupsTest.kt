package com.zcode.android.features.tasks

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * F-P38-B (pass-38 P3): the custom-GROUP sections never load on a fresh open — the
 * one-shot `LaunchedEffect(Unit) { onLoadGroups() }` races the transport connect;
 * `listGroupedTaskViewStructure`'s guarded null (pre-connect) was swallowed by
 * `?: emptyList()` (TasksViewModel) and NOTHING re-triggered, so the home rendered
 * ZERO custom-group sections for the whole session (the seeded "Backend" group
 * invisible; zero wire calls for the session's pid). The intermittency is
 * receipted both ways (pass 39: gen1's cold open lost it, gen4's won).
 *
 * The pinned shape: composing the screen PRE-CONNECT then flipping CONNECTED fires
 * the groups fetch. Pre-fix RED: the Unit effect fired exactly once at compose
 * (the wasted pre-connect call) and the CONNECTED flip re-fired NOTHING.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class Pass0Phase3ColdOpenGroupsTest {

    @get:Rule
    val rule = createComposeRule()

    private fun task(id: String) = Task(
        taskId = id,
        title = "Task $id",
        status = TaskStatus.IDLE,
        workspace = WorkspaceIdentityKey("/synthetic/ws-one"),
        createdAtMs = 1,
        updatedAtMs = System.currentTimeMillis(),
    )

    @Test
    fun `the groups fetch fires when the connection reaches CONNECTED after a cold pre-connect open`() {
        var phase by mutableStateOf(ConnectionPhase.CONNECTING)
        var groupFetches = 0
        rule.setContent {
            ZCodeTheme(themeMode = com.zcode.android.core.ui.theme.ThemeMode.LIGHT) {
                TaskListScreen(
                    state = TasksUiState(
                        phase = phase,
                        loadState = TasksUiState.Load.READY,
                        groups = listOf(TaskGroup("/synthetic/ws-one", "Synthetic One", listOf(task("t1")))),
                        taskCount = 1,
                    ),
                    onTaskClick = {}, onOpenSettings = {}, onOpenConnection = {}, onRetry = {}, onRefresh = {},
                    onLoadGroups = { groupFetches++ },
                )
            }
        }
        rule.waitForIdle()
        // Pre-connect: the old one-shot Unit effect fired a fetch into a connection
        // that could not serve it (the wasted call; post-fix it waits for CONNECTED).
        val preConnectFetches = groupFetches
        // The connect lands: the groups fetch MUST re-fire.
        phase = ConnectionPhase.CONNECTED
        rule.waitForIdle()
        assertTrue(
            "the CONNECTED transition must re-fire the groups fetch (pre=$preConnectFetches, post=$groupFetches)",
            groupFetches > preConnectFetches,
        )
        assertEquals(1, preConnectFetches) // the pre-fix shape: exactly the one wasted call
    }

    @Test
    fun `a warm CONNECTED open still fetches the groups once`() {
        var groupFetches = 0
        rule.setContent {
            ZCodeTheme(themeMode = com.zcode.android.core.ui.theme.ThemeMode.LIGHT) {
                TaskListScreen(
                    state = TasksUiState(
                        phase = ConnectionPhase.CONNECTED,
                        loadState = TasksUiState.Load.READY,
                        groups = listOf(TaskGroup("/synthetic/ws-one", "Synthetic One", listOf(task("t1")))),
                        taskCount = 1,
                    ),
                    onTaskClick = {}, onOpenSettings = {}, onOpenConnection = {}, onRetry = {}, onRefresh = {},
                    onLoadGroups = { groupFetches++ },
                )
            }
        }
        rule.waitForIdle()
        assertEquals(1, groupFetches)
    }
}
