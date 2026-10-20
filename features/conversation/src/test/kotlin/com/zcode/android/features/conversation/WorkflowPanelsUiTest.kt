package com.zcode.android.features.conversation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.WorkflowRunEvent
import com.zcode.android.domain.model.WorkflowRunStatus
import com.zcode.android.domain.model.WorkflowRunSummary
import com.zcode.android.features.conversation.ui.WorkflowRunSheet
import com.zcode.android.features.conversation.ui.WorkflowRunsPanel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A3.3 (Phase 5A) UI pins: the runs panel renders every run with a TEXT status (never
 * hue-only), live runs first, and a tap opens the run; the run sheet renders the title,
 * failure line, the event timeline (sequence + type + headline), the load-more button
 * only while hasMore, and the honest unavailable / empty faces.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class WorkflowPanelsUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun run(id: String, status: WorkflowRunStatus, label: String? = id, failure: String? = null, updated: Long = 1) = WorkflowRunSummary(
        runId = id, label = label, status = status, statusRaw = status.wire.ifEmpty { "weird" }, stopReason = null, toolCallId = null,
        updatedAtMs = updated, resumedFrom = null, supersededBy = null, failureCode = null, failureMessage = failure, resumable = status == WorkflowRunStatus.ERRORED,
    )

    @Test
    fun `panel renders runs live-first with text statuses and opens on tap`() {
        val opened = mutableListOf<String>()
        rule.setContent {
            ZCodeTheme {
                WorkflowRunsPanel(
                    runs = listOf(run("done", WorkflowRunStatus.COMPLETED, updated = 9), run("live", WorkflowRunStatus.RUNNING, updated = 1), run("odd", WorkflowRunStatus.UNKNOWN)),
                    onOpen = { opened += it.runId },
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("workflow_runs_panel").assertIsDisplayed()
        rule.onNodeWithText("Running").assertIsDisplayed()
        rule.onNodeWithText("Completed").assertIsDisplayed()
        rule.onNodeWithText("weird").assertIsDisplayed() // unknown keeps the raw word
        // live first: the running row precedes the completed one in the tree
        val liveY = rule.onNodeWithTag("workflow_run_live").fetchSemanticsNode().positionInRoot.y
        val doneY = rule.onNodeWithTag("workflow_run_done").fetchSemanticsNode().positionInRoot.y
        assert(liveY < doneY) { "live runs render first" }
        rule.onNodeWithTag("workflow_run_live").performClick(); rule.waitForIdle()
        assertEquals(listOf("live"), opened)
    }

    @Test
    fun `panel renders nothing for no runs`() {
        rule.setContent { ZCodeTheme { WorkflowRunsPanel(runs = emptyList(), onOpen = {}) } }
        rule.waitForIdle()
        rule.onAllNodesWithTag("workflow_runs_panel").assertCountEquals(0)
    }

    @Test
    fun `sheet renders title failure events and load-more while hasMore`() {
        val more = mutableListOf<Unit>()
        val events = listOf(
            WorkflowRunEvent(1, "run.started", JsonObject(mapOf("message" to JsonPrimitive("started lint-all"))), truncated = false),
            WorkflowRunEvent(2, "node.settled", JsonObject(emptyMap()), truncated = true),
        )
        rule.setContent {
            ZCodeTheme {
                WorkflowRunSheet(
                    open = ConversationViewModel.OpenWorkflowRun(run("r1", WorkflowRunStatus.ERRORED, label = "lint-all", failure = "exit 2"), events, hasMore = true, loading = false, unavailable = false),
                    onDismiss = {},
                    onLoadMore = { more += Unit },
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("workflow_sheet_title").assertIsDisplayed()
        rule.onNodeWithTag("workflow_sheet_failure").assertIsDisplayed()
        rule.onNodeWithText("Resumable").assertIsDisplayed()
        rule.onNodeWithTag("workflow_event_1").assertIsDisplayed()
        rule.onNodeWithText("started lint-all").assertIsDisplayed()
        rule.onNodeWithText("(truncated)").assertIsDisplayed()
        rule.onNodeWithTag("workflow_events_more").performClick(); rule.waitForIdle()
        assertEquals(1, more.size)
    }

    @Test
    fun `sheet renders the unavailable and empty faces`() {
        rule.setContent {
            ZCodeTheme {
                WorkflowRunSheet(
                    open = ConversationViewModel.OpenWorkflowRun(run("r1", WorkflowRunStatus.COMPLETED), emptyList(), hasMore = false, loading = false, unavailable = true),
                    onDismiss = {}, onLoadMore = {},
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("workflow_events_unavailable").assertIsDisplayed()
        rule.onAllNodesWithTag("workflow_events_more").assertCountEquals(0)
    }
}
