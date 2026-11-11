package com.zcode.android.features.conversation

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.BackgroundBashOutputUnavailable
import com.zcode.android.features.conversation.ui.OutputViewer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * N-BK-1(c) (vc52 Phase 12) compose leg: the bash-output viewer's unavailable /
 * query-failed faces carry the desktop's bashOutput.retry — tapping re-fires the
 * fetch; a healthy output renders NO retry (the retry is for FAILURE faces only).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class BackgroundRetryUiTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `the unavailable face carries the Retry affordance`() {
        val retries = mutableListOf<Int>()
        rule.setContent {
            ZCodeTheme(themeMode = com.zcode.android.core.ui.theme.ThemeMode.LIGHT) {
                OutputViewer(BackgroundBashOutputUnavailable(kind = "query_failed", workId = "w1", code = null), onRetry = { retries.add(1) })
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("background_work_retry").performClick()
        rule.waitForIdle()
        assertEquals(listOf(1), retries)
    }

    @Test
    fun `the thrown-query face carries Retry too`() {
        val retries = mutableListOf<Int>()
        rule.setContent {
            ZCodeTheme(themeMode = com.zcode.android.core.ui.theme.ThemeMode.LIGHT) {
                OutputViewer("THROWN", onRetry = { retries.add(1) })
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("background_work_retry").performClick()
        rule.waitForIdle()
        assertEquals(listOf(1), retries)
    }

    @Test
    fun `healthy output renders no retry`() {
        rule.setContent {
            ZCodeTheme(themeMode = com.zcode.android.core.ui.theme.ThemeMode.LIGHT) {
                OutputViewer(
                    com.zcode.android.domain.model.BackgroundBashOutput(
                        workId = "w1", status = "completed", output = "line one\nline two", truncated = false, outputPath = "/tmp/out",
                    ),
                    onRetry = { },
                )
            }
        }
        rule.waitForIdle()
        rule.onAllNodesWithTag("background_work_retry").assertCountEquals(0)
    }
}
