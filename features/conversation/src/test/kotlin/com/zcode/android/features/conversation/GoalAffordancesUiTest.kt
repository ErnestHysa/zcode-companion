package com.zcode.android.features.conversation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.GoalState
import com.zcode.android.domain.model.SessionProjectionState
import com.zcode.android.features.conversation.ui.SessionContextSheet
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * G26 (vc51) — the goal affordances. The recorded gap: sendGoal/pauseGoal/
 * resumeGoal were wired AND live-fired but reachable ONLY by typing slash text;
 * the context-sheet goal panel was strictly read-only (the desktop offers
 * add-goal on the composer menu + pause/resume icons on the goal banner).
 * Pins: a goal-less session renders the Set-goal row (dialog → onSetGoal), and
 * the goal panel's pause/resume icons fire the VM verbs per status.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class GoalAffordancesUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun activeGoal() = GoalState(
        targetId = "t-1", objective = "ship the release", summaryTitle = null,
        timeUsedSeconds = 10, activeRunStartedAtMs = null, status = "active",
        iteration = 1, verifications = emptyList(),
    )

    @Test
    fun `a goal-less session offers the Set-goal affordance which fires the wire verb`() {
        var setGoals = mutableListOf<String>()
        rule.setContent {
            ZCodeTheme(themeMode = com.zcode.android.core.ui.theme.ThemeMode.LIGHT) {
                SessionContextSheet(
                    sessionState = SessionProjectionState(),
                    rows = emptyList(),
                    workflowRuns = null,
                    plans = null,
                    sessionStatusProvider = { null },
                    onDismiss = {},
                    onSetGoal = { setGoals.add(it) },
                )
            }
        }
        rule.onNodeWithTag("goal_set_row").performClick()
        rule.onNodeWithTag("goal_set_field").performTextInput("finish the report")
        rule.onNodeWithTag("goal_set_confirm").performClick()
        assertEquals(listOf("finish the report"), setGoals)
    }

    @Test
    fun `an active goal shows the pause icon which fires the wire verb`() {
        var pauses = 0
        rule.setContent {
            ZCodeTheme(themeMode = com.zcode.android.core.ui.theme.ThemeMode.LIGHT) {
                SessionContextSheet(
                    sessionState = SessionProjectionState(goal = activeGoal(), goalKeyPresent = true),
                    rows = emptyList(),
                    workflowRuns = null,
                    plans = null,
                    sessionStatusProvider = { null },
                    onDismiss = {},
                    onPauseGoal = { pauses++ },
                )
            }
        }
        rule.onNodeWithTag("goal_pause").performClick()
        assertEquals(1, pauses)
        // A session WITH a goal hides the Set-goal row.
        rule.onAllNodesWithTag("goal_set_row").assertCountEquals(0)
    }

    @Test
    fun `a paused goal shows the resume icon which fires the wire verb`() {
        var resumes = 0
        val paused = activeGoal().copy(status = "paused")
        rule.setContent {
            ZCodeTheme(themeMode = com.zcode.android.core.ui.theme.ThemeMode.LIGHT) {
                SessionContextSheet(
                    sessionState = SessionProjectionState(goal = paused, goalKeyPresent = true),
                    rows = emptyList(),
                    workflowRuns = null,
                    plans = null,
                    sessionStatusProvider = { null },
                    onDismiss = {},
                    onResumeGoal = { resumes++ },
                )
            }
        }
        rule.onNodeWithTag("goal_resume").performClick()
        assertEquals(1, resumes)
    }
}
