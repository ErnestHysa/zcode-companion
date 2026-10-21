package com.zcode.android.features.conversation

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.ConversationPlan
import com.zcode.android.domain.model.ConversationPlanStep
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.domain.model.GoalState
import com.zcode.android.domain.model.PlanState
import com.zcode.android.domain.model.PlanStepStatus
import com.zcode.android.domain.model.SessionConfigState
import com.zcode.android.domain.model.SessionProjectionState
import com.zcode.android.domain.model.WorkflowRunStatus
import com.zcode.android.domain.model.WorkflowRunSummary
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.domain.state.TaskStateMachine
import com.zcode.android.features.conversation.ui.ConversationScreen
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * U1 (verification pass 2, 2026-10-21, live-found on the all-flags rig): the header's
 * context panels (goal · todos · workflow runs · plans) stacked UNBOUNDED in the
 * non-scrollable column — with all four populated the timeline's viewport was 46dp on a
 * Pixel 5, and ZERO once the status capsule expanded; the user could not read the chat at
 * all. The B2.12 squeeze class (strips were budgeted; these panels were not). The vc32
 * pin only ever composed config chips, so it could not see this. Pinned: with every panel
 * populated at a phone size, the timeline keeps real height and the panel stack is bounded.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "w411dp-h891dp")
class HeaderPanelBudgetTest {

    @get:Rule
    val rule = createComposeRule()

    private fun everyPanelPopulated() = ConversationUiState(
        rows = listOf(
            ConversationRow.Unknown(
                rowId = 1,
                turnId = "t1",
                unknownKind = "test-row",
                raw = kotlinx.serialization.json.JsonObject(emptyMap()),
            ),
        ),
        loading = false,
        taskState = TaskStateMachine.State.RUNNING,
        phase = ConnectionPhase.CONNECTED,
        draft = "",
        sessionState = SessionProjectionState(
            config = SessionConfigState(
                provider = "zai",
                model = "glm-5.3",
                thought = "high",
                thoughtLevels = listOf("high"),
                followupMode = null,
                mode = "build",
                planEnabled = null,
                modelSelectionProviderId = null,
                modelSelectionModelId = null,
            ),
            goal = GoalState(
                targetId = "g1",
                objective = "Ship it",
                summaryTitle = "Ship it",
                timeUsedSeconds = 299,
                activeRunStartedAtMs = null,
                status = "verifying",
                iteration = 4,
                verifications = (1L..4L).map { i ->
                    GoalState.Verification(iteration = i, outcome = if (i == 1L) "pass" else "notSatisfied", atMs = i, anchorRowId = null, reason = "$i criteria unmet", nextAction = null)
                },
            ),
            plan = PlanState(
                items = (1..10).map { PlanState.Item(id = "todo-$it", content = "Todo item number $it", status = if (it < 3) "completed" else "pending") },
                updatedAtMs = 1L,
            ),
        ),
    )

    private fun runs(): List<WorkflowRunSummary> = (1..4).map { i ->
        WorkflowRunSummary(
            runId = "run-$i",
            label = "workflow-$i",
            status = WorkflowRunStatus.RUNNING,
            statusRaw = "running",
            stopReason = null,
            toolCallId = null,
            updatedAtMs = i.toLong(),
            resumedFrom = null,
            supersededBy = null,
            failureCode = null,
            failureMessage = null,
            resumable = false,
        )
    }

    private fun plans(): List<ConversationPlan> = (1..2).map { p ->
        ConversationPlan(
            title = "Plan $p",
            status = "inProgress",
            steps = (1..6).map { s -> ConversationPlanStep(id = "$p-$s", title = "Step $s of plan $p", status = if (s == 1) PlanStepStatus.COMPLETED else PlanStepStatus.PENDING) },
        )
    }

    @Test
    fun timelineKeepsRealHeight_whenEveryContextPanelIsPopulated() {
        rule.setContent {
            ZCodeTheme {
                ConversationScreen(
                    state = everyPanelPopulated(),
                    phase = ConnectionPhase.CONNECTED,
                    onBack = {},
                    onLoadOlder = { _, _ -> },
                    onDismissHistoryNotice = {},
                    onRetryOlder = {},
                    onDraftChange = {},
                    onSend = {},
                    onStop = {},
                    onRetrySend = {},
                    onDismissSendNotice = {},
                    onAnswer = { _, _, _, _ -> },
                    onSendQueuedNow = {},
                    onEditQueueItem = { _, _ -> },
                    workflowRuns = runs(),
                    plans = plans(),
                )
            }
        }
        rule.waitForIdle()
        val density = rule.density.density
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val list = rule.onNodeWithTag("conversation_list").fetchSemanticsNode().boundsInRoot

        // The vc32 floor, now under the WORST header: the chat stays readable.
        assertTrue(
            "timeline squeezed to ${list.height / density}dp by the context panels (root ${root.height / density}dp)",
            list.height / density >= 120f,
        )
        // The panel stack is BOUNDED (a screen fraction) and scrolls inside its budget —
        // the goal/todos/runs/plans remain reachable without stealing the timeline.
        val budget = rule.onNodeWithTag("conv_context_budget").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "context panel stack ${budget.height / density}dp exceeds its budget (root ${root.height / density}dp)",
            budget.height <= root.height * 0.45f,
        )
    }
}
