package com.zcode.android.features.conversation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.SessionProjectionState
import com.zcode.android.domain.model.TaskTokenUsage
import com.zcode.android.features.conversation.ui.SessionContextSheet
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * G60 (vc52 Phase 12) compose leg: the context sheet's per-task TOKEN usage panel
 * (the desktop tokenDebug totals) — fetched once per sheet open; the total + the
 * expanded split + the model request/error counts render; a null provider or a
 * null result renders NOTHING (honest absent face); an erroring provider renders
 * nothing (runCatching, never a crash, never a silent 0).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class TaskTokenUsageSheetUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun sheet(provider: (suspend () -> TaskTokenUsage?)?) {
        rule.setContent {
            ZCodeTheme(themeMode = com.zcode.android.core.ui.theme.ThemeMode.LIGHT) {
                SessionContextSheet(
                    sessionState = SessionProjectionState(),
                    rows = emptyList(),
                    workflowRuns = null,
                    plans = null,
                    sessionStatusProvider = { null },
                    onDismiss = {},
                    taskUsageProvider = provider,
                )
            }
        }
        rule.waitForIdle()
    }

    private val usage = TaskTokenUsage(
        sessionId = "s", totalTokens = 184_320, inputTokens = 151_000, outputTokens = 21_000,
        reasoningTokens = 6_400, cacheCreationTokens = 2_800, cacheReadTokens = 48_600,
        modelRequestCount = 37, modelErrorCount = 1,
    )

    @Test
    fun `the panel renders the total and expands to the split + request counts`() {
        var reads = 0
        sheet(provider = { reads++; usage })
        rule.onNodeWithTag("task_usage_panel").assertExists()
        rule.onNodeWithText("Task tokens · 184320").assertExists()
        rule.onNodeWithTag("task_usage_toggle").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("in · 151000").assertExists()
        rule.onNodeWithText("reasoning · 6400").assertExists()
        rule.onNodeWithText("cache read · 48600").assertExists()
        rule.onNodeWithTag("task_usage_requests").assertExists()
        rule.onNodeWithText("model requests 37 · errors 1").assertExists()
        assertEquals("the read fires ONCE per sheet open", 1, reads)
    }

    @Test
    fun `a null provider renders nothing`() {
        sheet(provider = null)
        rule.onAllNodesWithTag("task_usage_panel").assertCountEquals(0)
    }

    @Test
    fun `a null result renders nothing (the honest absent face, never a silent 0)`() {
        sheet(provider = { null })
        rule.waitForIdle()
        rule.onAllNodesWithTag("task_usage_panel").assertCountEquals(0)
    }

    @Test
    fun `an erroring provider renders nothing and never crashes`() {
        sheet(provider = { error("boom") })
        rule.waitForIdle()
        rule.onAllNodesWithTag("task_usage_panel").assertCountEquals(0)
    }
}
