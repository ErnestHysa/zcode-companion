package com.zcode.android.features.conversation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.ConversationPlan
import com.zcode.android.domain.model.PlanStepStatus
import com.zcode.android.domain.model.TrajectoryRecord
import com.zcode.android.domain.model.TrajectoryResult
import com.zcode.android.features.conversation.ui.PlansViewer
import com.zcode.android.features.conversation.ui.TrajectorySheet
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A3.12 + A3.13 (Phase 5B) UI pins: the plans viewer renders each plan's title + step
 * checklist with per-step status glyphs and counts; the trajectory sheet renders the
 * search field + per-call lines (kind/model/duration/finish, the error face, tools) and
 * the honest unavailable face when the read fails.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class PlansTrajectoryUiTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `plans viewer renders titles counts and per-step status glyphs`() {
        rule.setContent {
            ZCodeTheme {
                PlansViewer(
                    listOf(
                        ConversationPlan(
                            "Ship the release", status = null,
                            steps = listOf(
                                ConversationPlanStep("s1", "Write the changelog", PlanStepStatus.COMPLETED),
                                ConversationPlanStep("s2", "Bump the version", PlanStepStatus.IN_PROGRESS),
                                ConversationPlanStep("s3", "Tag and publish", PlanStepStatus.PENDING),
                            ),
                        ),
                    ),
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("plans_viewer").assertIsDisplayed()
        rule.onNodeWithText("Ship the release").assertIsDisplayed()
        rule.onNodeWithText("1/3").assertIsDisplayed()
        rule.onNodeWithTag("plan_step_0_0").assertIsDisplayed()
        rule.onNodeWithText("Bump the version").assertIsDisplayed()
    }

    @Test
    fun `plans viewer renders nothing for no plans`() {
        rule.setContent { ZCodeTheme { PlansViewer(emptyList()) } }
        rule.waitForIdle()
        rule.onAllNodesWithTag("plans_viewer").assertCountEquals(0)
    }

    private fun rec(id: String, error: String? = null, tools: List<String> = emptyList()) = TrajectoryRecord(
        requestId = id, attempt = 1, startedAt = "2026-10-20T10:00:00Z", completedAt = null, durationMs = 1200,
        callKind = "main", modelId = "glm-5.3", providerId = "zai", finishReason = "tool_calls",
        textHead = "Checking the workspace", toolNames = tools, error = error,
    )

    @Test
    fun `trajectory sheet renders the search field call lines error face and tools`() {
        rule.setContent {
            ZCodeTheme {
                TrajectorySheet(
                    trajectory = TrajectoryResult(
                        records = listOf(rec("r1", tools = listOf("bash")), rec("r2", error = "RateLimitError: 429")),
                        sourceFiles = emptyList(), truncated = true,
                    ),
                    loading = false, searchResults = null, query = "", onQueryChange = {}, onDismiss = {},
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("trajectory_sheet").assertIsDisplayed()
        rule.onNodeWithTag("trajectory_search").assertIsDisplayed()
        rule.onNodeWithTag("trajectory_r1").assertIsDisplayed()
        rule.onNodeWithText("RateLimitError: 429").assertIsDisplayed()
        rule.onNodeWithText("tools: bash").assertIsDisplayed()
        rule.onNodeWithTag("trajectory_truncated").assertIsDisplayed()
    }

    @Test
    fun `trajectory search filters the timeline`() {
        var q = ""
        rule.setContent {
            ZCodeTheme {
                TrajectorySheet(
                    trajectory = TrajectoryResult(records = listOf(rec("r1"), rec("r2")), sourceFiles = emptyList(), truncated = false),
                    loading = false, searchResults = null, query = q, onQueryChange = { q = it }, onDismiss = {},
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("trajectory_search").performTextInput("r2")
        rule.waitForIdle()
        // The state is parent-owned in this pin; assert through a re-set with the filtered query.
        rule.setContent {
            ZCodeTheme {
                TrajectorySheet(
                    trajectory = TrajectoryResult(records = listOf(rec("r1"), rec("r2")), sourceFiles = emptyList(), truncated = false),
                    loading = false, searchResults = null, query = "r2", onQueryChange = { q = it }, onDismiss = {},
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("trajectory_r2").assertIsDisplayed()
        rule.onAllNodesWithTag("trajectory_r1").assertCountEquals(0)
        assertEquals("r2", q)
    }

    @Test
    fun `trajectory sheet renders the unavailable face and search hits`() {
        rule.setContent {
            ZCodeTheme {
                TrajectorySheet(
                    trajectory = null, loading = false,
                    searchResults = listOf(ConversationViewModel.SessionSearchResult("user", "Ship the release this week")),
                    query = "release", onQueryChange = {}, onDismiss = {},
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("trajectory_unavailable").assertIsDisplayed()
        rule.onNodeWithTag("trajectory_hit_user").assertIsDisplayed()
        rule.onNodeWithText("Ship the release this week").assertIsDisplayed()
    }
}
