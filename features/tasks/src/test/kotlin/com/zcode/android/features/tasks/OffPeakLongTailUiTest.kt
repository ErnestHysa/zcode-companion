package com.zcode.android.features.tasks

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
 * L6.2 (Phase 6B) pins — the observe/control half of the off-peak product ruling
 * (the phone OBSERVES the queue and CONTROLS pause/continue/cancel; it never CREATES
 * or hosts queued compute — the templates carousel, keep-awake banner and shade
 * notifications stay desktop-side). The status vocabulary, queue badges, re-queue
 * hints, failure reasons and the cancel confirm use the desktop's own copy
 * (`offPeak.status|badge|action|cancel.*`), and the raw wire word never shows.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "w411dp-h891dp")
class OffPeakLongTailUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun row(id: String, status: String, position: Int? = 2, failure: String? = null) = OffPeakTaskRow(
        offPeakTaskId = id, title = "Idle $id", status = status, queuePosition = position,
        sessionId = null, createdAt = 1, workspaceKey = "/ws", failureReason = failure,
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
        override suspend fun cancelOffPeak(offPeakTaskId: String): Boolean { calls += "cancel:$offPeakTaskId"; return true }
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
    fun `the status vocabulary localizes - the raw wire word never shows`() {
        setContent(listOf(row("q1", "queued"), row("c1", "completed")))
        rule.onNodeWithText("Waiting for idle compute").assertIsDisplayed()
        rule.onNodeWithText("Succeeded").assertIsDisplayed()
        rule.onAllNodesWithTag("offpeak_row_q1").fetchSemanticsNodes().single()
        org.junit.Assert.assertTrue(
            "the raw wire status must not render",
            rule.onAllNodesWithText("queued").fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun `queue badges - in-queue for queued, paused-position for paused`() {
        setContent(listOf(row("q1", "queued", position = 3), row("p1", "paused", position = 3)))
        rule.onNodeWithText("#3 in queue").assertIsDisplayed()
        rule.onNodeWithText("#3 Paused").assertIsDisplayed()
    }

    @Test
    fun `failed rows surface the desktop failure copy and the failure reason`() {
        setContent(listOf(row("f1", "failed", position = null, failure = "quota exhausted")))
        rule.onNodeWithText("Failed").assertIsDisplayed()
        rule.onNodeWithText("quota exhausted").assertIsDisplayed()
    }

    @Test
    fun `queued and running rows offer Cancel behind the desktop confirm - it rides the port`() {
        setContent(listOf(row("q1", "queued"), row("r1", "running"), row("c1", "completed")))
        rule.onNodeWithTag("offpeak_cancel_q1").assertIsDisplayed()
        rule.onNodeWithTag("offpeak_cancel_r1").assertIsDisplayed()
        org.junit.Assert.assertEquals(0, rule.onAllNodesWithTag("offpeak_cancel_c1").fetchSemanticsNodes().size)
        rule.onNodeWithTag("offpeak_cancel_q1").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Cancel idle-time task?").assertIsDisplayed()
        rule.onNodeWithText("\"Idle q1\" will stop running. Files it already changed are kept.").assertIsDisplayed()
        rule.onNodeWithTag("offpeak_cancel_confirm").performClick()
        rule.waitForIdle()
        assertEquals(listOf("cancel:q1"), calls)
    }

    @Test
    fun `pause and continue carry the desktop re-queue hints as their a11y description`() {
        setContent(listOf(row("q1", "queued"), row("p1", "paused")))
        rule.onNodeWithTag("offpeak_pause_q1").assertIsDisplayed()
        org.junit.Assert.assertTrue(
            "the pause hint rides the button's semantics",
            rule.onAllNodesWithTag("offpeak_pause_q1").fetchSemanticsNodes().first().config
                .toString().contains("placed back in the queue"),
        )
        rule.onNodeWithTag("offpeak_continue_p1").assertIsDisplayed()
        org.junit.Assert.assertTrue(
            "the continue hint rides the button's semantics",
            rule.onAllNodesWithTag("offpeak_continue_p1").fetchSemanticsNodes().first().config
                .toString().contains("re-queues the task at the back"),
        )
    }
}
