package com.zcode.android.app.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.app.settings.UsagePort
import com.zcode.android.domain.model.AppUsageSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * I4+J9 (gaps-and-polish Phase 4E): the pie / trend / efficiency sections render
 * from the EXISTING snapshot data — no new wire surface; the PROBE-J9 window split
 * is a desktop UI fact (the snapshot carries the range the user picked) so the
 * phone's range tabs remain the authority.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class UsageChartsUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun snapshot() = AppUsageSnapshot(
        range = "7d", generatedAt = 1_760_000_000_000, timeZone = "UTC", source = "agent-db",
        summary = AppUsageSnapshot.Summary(
            totalTokens = 1_000_000, inputTokens = 600_000, outputTokens = 300_000,
            reasoningTokens = 100_000, cacheCreationTokens = 50_000, cacheReadTokens = 900_000,
            cacheHitRate = 0.42, totalSessions = 9, totalTurns = 84, toolCallCount = 210,
            toolErrorRate = 0.05, modelErrorRate = 0.02, avgTimeToFirstTokenMs = 800,
            avgTurnDurationMs = 20_000, activeDays = 5, currentStreakDays = 2,
            longestSessionMs = 3_600_000, longestStreakDays = 3, peakDayTokens = 400_000,
            favoriteModel = null,
        ),
        heatmap = AppUsageSnapshot.Heatmap(null, null, 10, emptyList()),
        dailyModelUsage = listOf(
            AppUsageSnapshot.DailyModelUsage(
                "2026-10-17",
                listOf(
                    AppUsageSnapshot.DailyModelItem("glm-5.1", 300_000),
                    AppUsageSnapshot.DailyModelItem("glm-5.1-air", 100_000),
                ),
            ),
            AppUsageSnapshot.DailyModelUsage(
                "2026-10-18",
                listOf(AppUsageSnapshot.DailyModelItem("glm-5.1", 250_000)),
            ),
        ),
        models = listOf(
            AppUsageSnapshot.ModelUsage("glm-5.1", 550_000, 300_000, 250_000, 40, 0.55),
            AppUsageSnapshot.ModelUsage("glm-5.1-air", 450_000, 300_000, 150_000, 30, 0.45),
        ),
        tools = emptyList(),
    )

    @Test
    fun `the pie renders with the top-model legend`() {
        rule.setContent {
            ZCodeTheme {
                UsageScreen(
                    port = object : UsagePort {
                        override suspend fun appUsage(range: String) = UsagePort.Result(snapshot(), null)
                    },
                )
            }
        }
        rule.onNodeWithTag("usage_pie_canvas").performScrollTo()
        org.junit.Assert.assertTrue("the pie legend names the top model", rule.onAllNodesWithText("glm-5.1").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `the trend renders one row per day`() {
        rule.setContent {
            ZCodeTheme {
                UsageScreen(
                    port = object : UsagePort {
                        override suspend fun appUsage(range: String) = UsagePort.Result(snapshot(), null)
                    },
                )
            }
        }
        assertTrue(rule.onAllNodesWithTag("usage_trend_row_2026-10-17", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithTag("usage_trend_row_2026-10-18", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `cache hit and call counts render`() {
        rule.setContent {
            ZCodeTheme {
                UsageScreen(
                    port = object : UsagePort {
                        override suspend fun appUsage(range: String) = UsagePort.Result(snapshot(), null)
                    },
                )
            }
        }
        rule.onNodeWithTag("usage_cache_hit").assertIsDisplayed()
        rule.onNodeWithText("42%").assertIsDisplayed()
        rule.onNodeWithTag("usage_tool_calls").assertIsDisplayed()
        rule.onNodeWithTag("usage_turns").assertIsDisplayed()
    }
}
