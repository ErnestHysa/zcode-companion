package com.zcode.android.features.conversation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.GoalState
import com.zcode.android.domain.model.PlanState
import com.zcode.android.domain.model.SessionControl
import com.zcode.android.domain.model.SessionProjectionState
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.domain.state.TaskStateMachine
import com.zcode.android.features.conversation.ui.ConversationScreen
import com.zcode.android.features.conversation.ui.GoalPanel
import com.zcode.android.features.conversation.ui.PlanPanel
import androidx.compose.ui.Modifier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue

/**
 * D2 (gaps-and-polish Phase 4C): the goal + todo surfaces RENDER from a scripted
 * session projection — the acceptance's "rig-scripted goal session renders the
 * criteria timeline; streaming label reflects turn kind; todos restore".
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class GoalPlanPanelsUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun goal() = GoalState.decode(
        Json.parseToJsonElement(
            """{"targetId":"g1","objective":"Ship the release","summaryTitle":"Ship it","timeUsedSeconds":120,
                "status":"verifying","iteration":3,
                "verifications":[
                  {"iteration":1,"outcome":"pass","at":1760000001000,"anchorRowId":12},
                  {"iteration":2,"outcome":"notSatisfied","at":1760000002000,"reason":"2 criteria unmet"},
                  {"iteration":3,"outcome":"failed","at":1760000003000,"reason":"verifier crashed"}
                ]}""",
        ).jsonObject,
    )

    private fun plan() = PlanState.decode(
        Json.parseToJsonElement(
            """{"items":[
                {"id":"t1","content":"Write the changelog","status":"completed"},
                {"id":"t2","content":"Bump the version","status":"inProgress"},
                {"id":"t3","content":"Tag the release","status":"pending"}
              ],"updatedAt":1760000040000}""",
        ).jsonObject,
    )

    private fun setContent(sessionState: SessionProjectionState?) {
        rule.setContent {
            ZCodeTheme {
                ConversationScreen(
                    state = ConversationUiState(
                        rows = emptyList(),
                        loading = false,
                        taskState = TaskStateMachine.State.RUNNING,
                        phase = ConnectionPhase.CONNECTED,
                        epoch = "e1",
                        sessionState = sessionState,
                    ),
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
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `a goal session renders the criteria timeline with pass notSatisfied and failed`() {
        setContent(SessionProjectionState(goal = goal(), goalKeyPresent = true))
        // F-CHAT-FOCUS v2: the context panels live in the SESSION CONTEXT SHEET —
        // nothing renders inline above the chat anymore. Open the sheet, then pin.
        rule.onNodeWithTag("conv_session_context").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("session_context_sheet").assertIsDisplayed()
        rule.onNodeWithTag("goal_panel").assertIsDisplayed()
        rule.onNodeWithText("Ship it").assertIsDisplayed()
        rule.onNodeWithText("verifying").assertIsDisplayed()
        // Inside the sheet the panel renders EXPANDED — the user opened the sheet to
        // read the context.
        assertEquals(3, rule.onAllNodesWithTag("goal_verification_row").fetchSemanticsNodes().size)
        org.junit.Assert.assertEquals(1, rule.onAllNodesWithText("2 criteria unmet", substring = true).fetchSemanticsNodes().size)
        org.junit.Assert.assertEquals(1, rule.onAllNodesWithText("verifier crashed", substring = true).fetchSemanticsNodes().size)
        // The header is a TRUE two-way toggle (field: "I click to collapse but it
        // doesn't collapse") — tap collapses, tap again re-expands.
        rule.onNodeWithTag("goal_header").performClick()
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithTag("goal_verification_row").fetchSemanticsNodes().size)
        rule.onNodeWithTag("goal_header").performClick()
        rule.waitForIdle()
        assertEquals(3, rule.onAllNodesWithTag("goal_verification_row").fetchSemanticsNodes().size)
    }

    @Test
    fun `todos restore into the checklist with completed inProgress and pending`() {
        setContent(SessionProjectionState(plan = plan(), planKeyPresent = true))
        rule.onNodeWithTag("conv_session_context").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("session_context_sheet").assertIsDisplayed()
        rule.onNodeWithTag("plan_panel").assertIsDisplayed()
        // Inside the sheet the checklist renders EXPANDED; the header is a TRUE
        // two-way toggle — collapse to one line, tap again to restore.
        assertEquals(3, rule.onAllNodesWithTag("plan_row").fetchSemanticsNodes().size)
        rule.onNodeWithText("Bump the version").assertIsDisplayed()
        rule.onNodeWithText("Write the changelog").assertIsDisplayed()
        rule.onNodeWithTag("plan_header").performClick()
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithTag("plan_row").fetchSemanticsNodes().size)
        rule.onNodeWithTag("plan_header").performClick()
        rule.waitForIdle()
        assertEquals(3, rule.onAllNodesWithTag("plan_row").fetchSemanticsNodes().size)
    }

    @Test
    fun `no goal or plan keys render nothing`() {
        setContent(SessionProjectionState())
        assertEquals(0, rule.onAllNodesWithTag("goal_panel").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithTag("plan_panel").fetchSemanticsNodes().size)
    }

    @Test
    fun `the progress label reflects the turn kind`() {
        setContent(
            SessionProjectionState(
                control = SessionControl(
                    phase = "running", sessionEnded = false, canStop = true,
                    stopState = SessionControl.StopState.STOPPABLE,
                    activeWorks = listOf(com.zcode.android.domain.model.SessionControl.ActiveWork(kind = "compact", foregroundExecutionId = null)),
                ),
            ),
        )
        rule.onNodeWithTag("conversation_status_line").assertIsDisplayed()
        rule.onNodeWithText("Running · compacting", substring = true).assertIsDisplayed()
    }
    @Test
    fun `F-UX-PANELS - collapsed by default the header summarizes and tapping expands the todos`() {
        rule.setContent {
            ZCodeTheme { PlanPanel(plan(), modifier = Modifier, collapsedByDefault = true) }
        }
        rule.onNodeWithTag("plan_header").assertIsDisplayed()
        assertTrue(rule.onAllNodesWithTag("plan_progress", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        // collapsed: the rows are hidden
        assertEquals(0, rule.onAllNodesWithTag("plan_row").fetchSemanticsNodes().size)
        // tap the header → expands
        rule.onNodeWithTag("plan_header").performClick()
        rule.waitForIdle()
        assertEquals(3, rule.onAllNodesWithTag("plan_row").fetchSemanticsNodes().size)
    }

    @Test
    fun `F-UX-PANELS - the collapsed goal header carries the objective and expands`() {
        rule.setContent { ZCodeTheme { GoalPanel(goal(), collapsedByDefault = true) } }
        rule.onNodeWithTag("goal_header").assertIsDisplayed()
        rule.onNodeWithText("Ship it").assertIsDisplayed()
        rule.onNodeWithText("verifying").assertIsDisplayed()
        // collapsed: the verification timeline is hidden
        assertEquals(0, rule.onAllNodesWithTag("goal_verification_row").fetchSemanticsNodes().size)
        rule.onNodeWithTag("goal_header").performClick()
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithTag("goal_verification_row").fetchSemanticsNodes().isNotEmpty())
    }
}
