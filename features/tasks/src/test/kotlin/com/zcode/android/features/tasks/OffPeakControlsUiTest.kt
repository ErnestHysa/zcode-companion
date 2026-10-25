package com.zcode.android.features.tasks

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.OffPeakTaskRow
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.features.tasks.port.AutomationsPort
import com.zcode.android.features.tasks.ui.TaskListScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A3.10 as shipped (Phase 5A): the off-peak rows carry Pause (queued/running) or Continue
 * (paused) — the `off-peak-task` channel's pauseTask/continueTask words — and nothing for
 * terminal states; a tap rides the port and reloads the lists.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class OffPeakControlsUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun row(id: String, status: String) = OffPeakTaskRow(
        offPeakTaskId = id, title = "Idle $id", status = status, queuePosition = 2, sessionId = null,
        createdAt = 1, workspaceKey = "/ws", failureReason = null,
    )

    private fun port(rows: List<OffPeakTaskRow>) = object : AutomationsPort {
        override suspend fun lists() = AutomationsPort.Lists(emptyList(), rows, null)
        override suspend fun create(workspaceKey: String, write: AutomationsPort.AutomationWrite) = false
        override suspend fun update(automationId: String, write: AutomationsPort.AutomationWrite) = false
        override suspend fun delete(automationId: String) = false
        override suspend fun setEnabled(automationId: String, enabled: Boolean) = false
        override suspend fun runNow(automationId: String) = false
        override suspend fun pauseOffPeak(offPeakTaskId: String): Boolean { calls += "pause:$offPeakTaskId"; return true }
        override suspend fun continueOffPeak(offPeakTaskId: String): Boolean { calls += "continue:$offPeakTaskId"; return true }
    }

    private fun setContent(rows: List<OffPeakTaskRow>) {
        calls.clear()
        rule.setContent {
            ZCodeTheme {
                TaskListScreen(
                    state = TasksUiState(phase = ConnectionPhase.CONNECTED, loadState = TasksUiState.Load.READY, groups = emptyList()),
                    onTaskClick = {}, onOpenSettings = {}, onOpenConnection = {}, onRetry = {}, onRefresh = {},
                    automationsPort = port(rows),
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `queued shows Pause paused shows Continue completed shows neither and taps ride the port`() {
        setContent(listOf(row("q1", "queued"), row("p1", "paused"), row("c1", "completed")))
        rule.onNodeWithTag("offpeak_pause_q1").assertExists().performClick(); rule.waitForIdle()
        rule.onNodeWithTag("offpeak_continue_p1").assertExists().performClick(); rule.waitForIdle()
        rule.onAllNodesWithTag("offpeak_pause_c1").assertCountEquals(0)
        rule.onAllNodesWithTag("offpeak_continue_c1").assertCountEquals(0)
        rule.onAllNodesWithTag("offpeak_continue_q1").assertCountEquals(0)
        assertEquals(listOf("pause:q1", "continue:p1"), calls)
    }
}
